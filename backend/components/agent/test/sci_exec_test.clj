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
  (testing "stock/get-k 日k with 3 args (date-to=date-from) 只取这一根"
    (let [res (se/eval-string "(stock/get-k \"000001.SZ\" \"日k\" \"20250101\")")]
      (is (map? res))
      (when (and (:ok res) (get-in (:ok res) [:ok :items]))
        (is (vector? (get-in (:ok res) [:ok :items])))
        (is (<= (count (get-in (:ok res) [:ok :items])) 1) "只取一根"))))
  (testing "stock/get-k 季k returns error (unsupported)"
    (let [res (se/eval-string "(stock/get-k \"000001\" \"季k\" \"20250101\")")]
      (is (contains? res :ok))
      (is (string? (get-in res [:ok :error]))))))

(deftest stock-ma
  (testing "stock/ma with period < 2 returns error"
    (let [res (se/eval-string "(stock/ma \"000001\" 1)")]
      (is (contains? res :ok))
      (is (= {:error "MA 周期至少为 2。"} (get-in res [:ok])))))

  ;; 三种用法：period + 可选 beg-date、bar-count
  ;; 1) 今天的 MA5：period=5，beg-date 不设，bar-count 不设 → 最后一行为最近交易日，最后一行的 ma 即「今天的 MA5」
  (testing "today's MA5: (ma stock-code 5) — no beg-date, no bar-count; last row is latest trading day, last row ma is today's MA5"
    (let [res (se/eval-string "(stock/ma \"000001\" 5)")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when (and payload (seq items))
          (let [last-row (peek items)]
            (is (map? last-row))
            (is (number? (:ma5 last-row)) "last row :ma5 is today's MA5 (numeric)"))))))

  ;; 2) 从 beg-date 起 1 天 MA（默认 num-days=1）
  (testing "ma with beg-date only: (ma stock-code 5 beg-date) — default num-days=1; exactly 1 row from beg-date"
    (let [res (se/eval-string "(stock/ma \"000001\" 5 \"20250101\")")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when (and payload (seq items))
          (let [first-date (:trade_date (first items))]
            (is (= 8 (count (str first-date))) "first row :trade_date is YYYYMMDD")
            (is (= 1 (count items)) "default num-days=1 => 1 row"))))))

  ;; 3) 从 beg-date 起 2 天 MA：num-days=2 → 仅 2 行
  (testing "2 days of MA from beg-date: (ma stock-code 5 beg-date 2) — num-days=2; exactly 2 rows"
    (let [res (se/eval-string "(stock/ma \"000001\" 5 \"20250101\" 2)")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when payload
          (is (= 2 (count items)) "bar-count=2 => exactly 2 rows"))))))

(deftest stock-ma-for-multiple-stocks
  (testing "ma-for-multiple-stocks empty codes returns error"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [] 5)")]
      (is (contains? res :ok))
      (is (= {:error "至少需要一只股票代码。"} (get-in res [:ok])))))
  (testing "ma-for-multiple-stocks period < 2 returns error"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [\"000001\"] 1)")]
      (is (contains? res :ok))
      (is (= {:error "MA 周期至少为 2。"} (get-in res [:ok])))))
  (testing "ma-for-multiple-stocks returns :ok with :by_ts_code when data available"
    (let [res (se/eval-string "(stock/ma-for-multiple-stocks [\"000001\" \"000002\"] 5 \"20250101\" 2)")]
      (is (contains? res :ok))
      (let [inner (get res :ok)]
        (when (contains? inner :ok)
          (let [by-code (get-in inner [:ok :by_ts_code])]
            (is (map? by-code) "by_ts_code is a map")
            (when (seq by-code)
              (is (every? (fn [[_k v]] (contains? v :items)) (seq by-code)) "each entry has :items"))))))))

(deftest stock-golden-cross
  (testing "golden-cross returns :ok with :crosses vector (calls ma twice)"
    (let [res (se/eval-string "(stock/golden-cross \"000001\" 5 20 \"20250101\" 60)")]
      (is (contains? res :ok))
      (let [inner (get res :ok)]
        (when (contains? inner :ok)
          (is (vector? (get-in inner [:ok :crosses])) "crosses is a vector")))))
  (testing "golden-cross short >= long returns error"
    (let [res (se/eval-string "(stock/golden-cross \"000001\" 20 5)")]
      (is (contains? res :ok))
      (is (clojure.string/includes? (str (get-in res [:ok :error])) "短期周期应小于长期周期")))))

(deftest stock-golden-cross-for-multiple-stocks
  (testing "golden-cross-for-multiple-stocks empty codes returns error"
    (let [res (se/eval-string "(stock/golden-cross-for-multiple-stocks [] 5 20)")]
      (is (contains? res :ok))
      (is (= {:error "至少需要一只股票代码。"} (get-in res [:ok])))))
  (testing "golden-cross-for-multiple-stocks short >= long returns error"
    (let [res (se/eval-string "(stock/golden-cross-for-multiple-stocks [\"000001\"] 20 5)")]
      (is (contains? res :ok))
      (is (clojure.string/includes? (str (get-in res [:ok :error])) "短期周期应小于长期周期")))))
  (testing "golden-cross-for-multiple-stocks returns :ok with :by_ts_code when data available"
    (let [res (se/eval-string "(stock/golden-cross-for-multiple-stocks [\"000001\" \"000002\"] 5 20 \"20250101\" 60)")]
      (is (contains? res :ok))
      (let [inner (get res :ok)]
        (when (contains? inner :ok)
          (let [by-code (get-in inner [:ok :by_ts_code])]
            (is (map? by-code) "by_ts_code is a map")
            (when (seq by-code)
              (is (every? (fn [[_k v]] (contains? v :crosses)) (seq by-code)) "each entry has :crosses")))))))
