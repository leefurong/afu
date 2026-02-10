(ns sci-exec-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [agent.tools.execute-clojure.exec :as se]))

(deftest eval-string-basic
  (testing "simple expressions return {:ok value}"
    (is (= {:ok 3} (se/eval-string "(+ 1 2)")))
    (is (= {:ok 6} (se/eval-string "(* 2 3)")))
    (is (= {:ok [1 2 3]} (se/eval-string "[1 2 3]")))
    (is (= {:ok 4} (se/eval-string "(count [1 2 3 4])")))))

(deftest eval-string-seq-and-functions
  (testing "seq, map, filter"
    (is (= {:ok '(2 3 4)} (se/eval-string "(map inc [1 2 3])")))
    (is (= {:ok [2 4]} (se/eval-string "(filter even? [1 2 3 4])")))
    (is (= {:ok 10} (se/eval-string "(reduce + [1 2 3 4])")))))

(deftest eval-string-error
  (testing "syntax or runtime error returns {:error ...}"
    (is (contains? (se/eval-string "(+ 1") :error))   ; 括号未闭合，语法错误
    (is (string? (:error (se/eval-string "(inc nil)")))) ; inc 不接受 nil，运行时错误
    (is (contains? (se/eval-string "(nth [] 99)") :error)))) ; 越界，运行时错误

(deftest eval-string-capture-out
  (testing "capture-out returns :out and :ok"
    (let [res (se/eval-string "(println \"hi\") 42" {:capture-out? true})]
      (is (contains? res :ok))
      (is (= 42 (:ok res)))
      (is (contains? res :out))
      (is (str/includes? (:out res) "hi"))))
  (testing "pprint output appears in :out"
    (let [res (se/eval-string "(pprint/pprint {:a 1 :b 2}) 99" {:capture-out? true})]
      (is (contains? res :ok))
      (is (= 99 (:ok res)))
      (is (contains? res :out))
      (is (str/includes? (:out res) ":a"))
      (is (str/includes? (:out res) ":b")))))

(deftest eval-string-with-ctx-stateful
  (testing "ctx keeps def state"
    (let [ctx (se/create-ctx)]
      (is (= {:ok 1} (se/eval-string "(def x 1) x" {:ctx ctx})))
      (is (= {:ok 2} (se/eval-string "(def x 2) x" {:ctx ctx})))
      (is (= {:ok 2} (se/eval-string "x" {:ctx ctx}))))))

(deftest eval-string-def-returns-serializable
  (testing "bare (def ...) returns {:ok string} so JSON/frontend/LLM get a result (Var -> str)"
    (let [ctx (se/create-ctx)
          res (se/eval-string "(def demo \"hello, persistent REPL \")" {:ctx ctx})]
      (is (contains? res :ok))
      (is (string? (:ok res)))
      (is (str/includes? (:ok res) "demo"))
      (is (= {:ok "hello, persistent REPL "} (se/eval-string "demo" {:ctx ctx}))))))

(comment (deftest eval-string-timeout
           (testing "timeout returns error"
             (let [res (se/eval-string "(range)" {:timeout-ms 50})]
               (is (contains? res :error))
               (is (str/includes? (:error res) "timed out")))))
         )

(deftest denied-symbols
  (testing "denied symbols return error"
    (is (contains? (se/eval-string "(slurp \"/nonexistent\")") :error))
    (is (contains? (se/eval-string "(eval (quote 1))") :error))))

(deftest eval-string-star-direct
  (testing "eval-string* returns value"
    (is (= 3 (se/eval-string* "(+ 1 2)")))
    (is (= 5 (se/eval-string* "(inc 4)")))))

(deftest http-namespace-available
  (testing "http/get and http/post are available and return map"
    (let [res (se/eval-string "(http/get \"https://httpbin.org/get\")")]
      (is (or (contains? res :ok) (contains? res :error)))
      (when (:ok res)
        (let [r (:ok res)]
          (is (map? r))
          (is (or (contains? r :status) (contains? r :error)))
          (when (:status r)
            (is (= 200 (:status r)))))))))

(deftest json-namespace-available
  (testing "json/parse-string and json/write-str work in sandbox"
    (is (= {:ok {"a" 1}} (se/eval-string "(json/parse-string \"{\\\"a\\\":1}\")")))
    (is (= {:ok {:a 1}} (se/eval-string "(json/parse-string \"{\\\"a\\\":1}\" true)")))
    (is (= {:ok "{\"x\":2}"} (se/eval-string "(json/write-str {:x 2})")))))

(deftest env-namespace-available
  (testing "env/get-env returns nil for names not in whitelist"
    (is (= {:ok nil} (se/eval-string "(env/get-env \"NONEXISTENT_VAR_FOR_TEST\")")))
    (is (= {:ok nil} (se/eval-string "(env/get-env nil)")))
    (is (= {:ok nil} (se/eval-string "(env/get-env \"PATH\")"))))
  (testing "env/get-env returns value only for whitelisted names (TUSHARE_API_TOKEN in sci_sandbox)"
    (let [res (se/eval-string "(env/get-env \"TUSHARE_API_TOKEN\")")]
      (is (contains? res :ok))
      (when (:ok res) (is (string? (:ok res)))))))

(deftest stock-namespace-available
  (testing "stock/ma-for-multiple-stocks is available and returns :ok or :error"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [\"000001\"] 5 \"20250101\" \"20250110\")")]
      (is (contains? res :ok))
      (is (or (contains? (get res :ok) :ok) (contains? (get res :ok) :error)))))

(deftest stock-ma-for-multiple-stocks
  (testing "ma-for-multiple-stocks empty codes returns error"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [] 5 \"20250101\" \"20250110\")")]
      (is (contains? res :ok))
      (is (= {:error "至少需要一只股票代码。"} (get-in res [:ok])))))
  (testing "ma-for-multiple-stocks period < 2 returns error"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [\"000001\"] 1 \"20250101\" \"20250110\")")]
      (is (contains? res :ok))
      (is (= {:error "MA 周期至少为 2。"} (get-in res [:ok])))))
  (testing "ma-for-multiple-stocks returns :ok with :by_ts_code and :backfill_triggered when data available"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [\"000001\" \"000002\"] 5 \"20250101\" \"20250110\")")]
      (is (contains? res :ok))
      (let [inner (get res :ok)]
        (when (contains? inner :by_ts_code)
          (let [by-code (get inner :by_ts_code)]
            (is (map? by-code) "by_ts_code is a map")
            (when (seq by-code)
              (is (every? (fn [[_k v]] (contains? v :items)) (seq by-code)) "each entry has :items")))
        (is (contains? inner :backfill_triggered) "response includes :backfill_triggered")))))))

(deftest stock-cross-signals-on-date
  "cross-signals-on-date 已暴露给 SCI；golden-cross-for-multiple-stocks 已从 SCI 移除。"
  (testing "cross-signals-on-date returns summary with backfill_triggered and golden_cross/death_cross"
    (let [res (se/eval-string "(stock/cross-signals-on-date [\"000001\" \"000002\"] \"20250115\")")]
      (is (contains? res :ok))
      (let [inner (get res :ok)]
        (when (map? inner)
          (is (contains? inner :summary) "response has :summary")
          (is (contains? (get inner :summary) :backfill_triggered) "summary has :backfill_triggered")
          (is (contains? inner :golden_cross) "response has :golden_cross")
          (is (contains? inner :death_cross) "response has :death_cross"))))))
