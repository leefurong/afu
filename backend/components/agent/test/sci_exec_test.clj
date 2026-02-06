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
      (is (str/includes? (:out res) "hi")))))

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
  (testing "stock/get-k is available and returns map with :ok or :error"
    (let [res (se/eval-string "(stock/get-k \"000001\" \"日k\" \"20250101\")")]
      (is (or (contains? res :ok) (contains? res :error)))
      (when (:ok res)
        (let [r (:ok res)]
          (is (map? r))
          ;; get-k 成功返回 {:ok {:fields _ :items _}}，失败返回 {:error _}
          (is (or (contains? r :ok) (contains? r :error)))))))
  (testing "stock/get-k with 3 args (no count) defaults to 20"
    (let [res (se/eval-string "(stock/get-k \"000001.SZ\" \"日k\" \"20250101\")")]
      (is (map? res))
      (when (and (:ok res) (seq (get-in (:ok res) [:ok :items])))
        (is (<= (count (get-in (:ok res) [:ok :items])) 20)))))
  (testing "stock/get-k 季k returns error (unsupported)"
    (is (= {:ok {:error "暂不支持季k/年k，请使用 日k、周k 或 月k。"}}
           (se/eval-string "(stock/get-k \"000001\" \"季k\" \"20250101\")")))))

(deftest stock-ma
  (testing "stock/ma with days < 2 returns error"
    (let [res (se/eval-string "(stock/ma \"000001\" 1)")]
      (is (contains? res :ok))
      (is (= {:error "MA 周期 days 至少为 2。"} (get-in res [:ok])))))
  (testing "stock/ma returns :ok with fields and items when given valid args"
    (let [res (se/eval-string "(stock/ma \"000001\" 5 \"20250101\" 30)")]
      (is (contains? res :ok))
      (let [inner (:ok res)]
        (when (contains? inner :ok)
          (let [payload (:ok inner)
                fields (:fields payload)
                items  (:items payload)]
            (is (vector? fields))
            (is (vector? items))
            (is (some #(= % "trade_date") fields))
            (is (some #(= % "close") fields))
            (when (seq items)
              ;; 至少最后一行应有 [date close ma]，且 ma 为数字（前 days-1 条 ma 可能为 nil）
              (let [last-row (peek items)]
                (is (= 3 (count last-row)))
                (is (number? (nth last-row 2)) "最后一行的 ma 应为数字"))))))))
  (testing "stock/ma two-arity (stock-code days) returns map with :ok or :error"
    (let [res (se/eval-string "(stock/ma \"000001\" 5)")]
      (is (or (contains? (get res :ok) :ok)
              (contains? (get res :ok) :error))))))
