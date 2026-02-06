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

  ;; 三种用法：days + 可选 beg-date、bar-count
  ;; 1) 今天的 MA5：days=5，beg-date 不设，bar-count 不设 → 最后一行为最近交易日，最后一行的 ma 即「今天的 MA5」
  (testing "today's MA5: (ma stock-code 5) — no beg-date, no bar-count; last row is latest trading day, last row ma is today's MA5"
    (let [res (se/eval-string "(stock/ma \"000001\" 5)")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when (and payload (seq items))
          (let [last-row (peek items)]
            (is (= 3 (count last-row)) "last row is [trade_date close ma]")
            (is (number? (nth last-row 2)) "last row ma is today's MA5 (numeric)"))))))

  ;; 2) 昨天的 MA5：days=5，beg-date=昨天，bar-count 不设 → 第一行日期为从 beg-date 起的交易日，即「从昨天起」的数据
  (testing "yesterday's: (ma stock-code 5 beg-date) — beg-date set, no bar-count; first row is start date (or next weekday), many rows"
    (let [res (se/eval-string "(stock/ma \"000001\" 5 \"20250101\")")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when (and payload (seq items))
          (let [first-date (nth (first items) 0)]
            (is (= 8 (count (str first-date))) "first row date is YYYYMMDD")
            (is (>= (count items) 5) "enough rows for MA5"))))))

  ;; 3) 今天和昨天两条：days=5，beg-date=昨天，bar-count=2 → 仅 2 行，第一行昨天、第二行今天
  (testing "today and yesterday: (ma stock-code 5 beg-date 2) — beg-date + bar-count=2; exactly 2 rows"
    (let [res (se/eval-string "(stock/ma \"000001\" 5 \"20250101\" 2)")]
      (is (contains? res :ok))
      (let [payload (get-in res [:ok :ok])
            items   (get payload :items)]
        (when payload
          (is (= 2 (count items)) "bar-count=2 => exactly 2 rows"))))))

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
