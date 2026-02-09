(ns k-line-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k]))

(deftest uncovered-date-ranges
  (testing "无缓存时返回整段"
    (is (= [{:start "20250101" :end "20250110"}]
           (k/uncovered-date-ranges nil nil "20250101" "20250110"))))
  (testing "只需向右拓展"
    (is (= [{:start "20250111" :end "20250120"}]
           (k/uncovered-date-ranges "20250101" "20250110" "20250101" "20250120"))))
  (testing "只需向左拓展"
    (is (= [{:start "20241220" :end "20241231"}]
           (k/uncovered-date-ranges "20250101" "20250110" "20241220" "20250110"))))
  (testing "左右都需拓展"
    (let [r (k/uncovered-date-ranges "20250105" "20250115" "20250101" "20250120")]
      (is (= 2 (count r)))
      (is (= "20250101" (get (first r) :start)))
      (is (= "20250104" (get (first r) :end)))
      (is (= "20250116" (get (second r) :start)))
      (is (= "20250120" (get (second r) :end)))))
  (testing "已完全覆盖时返回空"
    (is (= [] (k/uncovered-date-ranges "20250101" "20250110" "20250103" "20250108")))))

(deftest schema-and-get-daily-k
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (testing "未初始化时 get-daily-k 返回 :error"
    (k/reset-for-test!)
    (let [res (k/get-daily-k "000001.SZ" "20250101" "20250110")]
      (is (contains? res :error))
      (is (string? (:error res)))))
  (testing "使用 :memory: 初始化后 get-daily-k 可调用（会请求 Tushare，无 token 则 :error）"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (let [res (k/get-daily-k "000001.SZ" "20250101" "20250110")]
      (is (or (contains? res :ok) (contains? res :error)))
      (when (:ok res)
        (is (contains? (:ok res) :source))
        (is (contains? (:ok res) :items))
        (is (contains? (:ok res) :fields))))))

(deftest get-k-dwmsy
  (testing "日k 委托 get-daily-k"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (let [res (k/get-k "000001" "日k" "20250101" "20250115")]
      (is (or (contains? res :ok) (contains? res :error)))))
  (testing "周k 直连 Tushare，返回 :ok 或 :error"
    (let [res (k/get-k "000001" "周k" "20250101" "20250115")]
      (is (or (contains? res :ok) (contains? res :error)))))
  (testing "季k 返回错误"
    (is (contains? (k/get-k "000001" "季k" "20250101" "20250115") :error))))

(deftest cache-continuity-after-left-extend
  "先请求 2025-01-01，再请求 2023-01-01 后，缓存应覆盖 [2023-01-01, 有效今日]，且再次请求 2023-01-01 应命中缓存。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [effective-today (k/effective-today-ymd)
        r1 (k/get-daily-k "000001.SZ" "20250101" "20250110")
        r2 (k/get-daily-k "000001.SZ" "20230101" effective-today)]
    (when (and (contains? r1 :ok) (contains? r2 :ok))
      (let [bounds (k/get-cache-bounds "000001.SZ")]
        (is bounds "拓展后应有缓存区间")
        (when bounds
          (is (= "20230101" (:left bounds))
              "左界应为 2023-01-01（第二次请求触发向左拓展）")
          (is (= effective-today (:right bounds))
              "右界应为有效今日（测试内用 effective-today-ymd 求得）")
          (is (= (k/get-cache-row-count "000001.SZ")
                 (k/count-calendar-days-in-range (:left bounds) (:right bounds)))
              "left 到 right 之间每一天都应有一条数据（行数 = 日历天数）")))
      (let [r3 (k/get-daily-k "000001.SZ" "20230101" "20250110")]
        (when (contains? r3 :ok)
          (is (= :cache (get-in r3 [:ok :source]))
              "再次请求 2023-01-01 应命中缓存"))))))

(deftest cache-right-is-effective-today
  "get-daily-k 成功后，缓存右界应为当前求得的有效今日。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [effective-today (k/effective-today-ymd)
        res (k/get-daily-k "000001.SZ" "20250201" effective-today)]
    (is (or (contains? res :ok) (contains? res :error)) "get-daily-k 应返回 :ok 或 :error")
    (when (contains? res :ok)
      (let [bounds (k/get-cache-bounds "000001.SZ")]
        (when bounds
          (is (= effective-today (:right bounds))
              "右界应为有效今日（测试内用 effective-today-ymd 求得）"))))))

;; ---------------------------------------------------------------------------
;; get-daily-k-for-multiple-stocks 及配套 private 逻辑
;; ---------------------------------------------------------------------------

(deftest get-daily-k-for-multiple-stocks-edge-cases
  (testing "空 codes 返回 nil"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (is (nil? (k/get-daily-k-for-multiple-stocks [] "20250101" "20250110"))))
  (testing "未初始化 DB 时返回 nil"
    (k/reset-for-test!)
    (is (nil? (k/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250101" "20250110"))))
  (testing "date-from > date-to 时返回空序列（不报错）"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (let [res (k/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250110" "20250101")]
      (is (sequential? res))
      (is (empty? res)))))

(deftest get-daily-k-for-multiple-stocks-returns-data-and-structure
  "有数据时：返回非空、每条含 :ts_code/:trade_date/:source、无占位符、日期在请求区间内。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [fill (k/get-daily-k "000001.SZ" "20250102" "20250115")
        res  (k/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250101" "20250115")]
    (is (sequential? res) "应返回序列（可能空）")
    (when (contains? fill :ok)
      (is (seq res) "get-daily-k 成功时，同 code 同区间 get-daily-k-for-multiple-stocks 应有数据")
      (is (every? #(contains? % :ts_code) res) "每条应有 :ts_code")
      (is (every? #(contains? % :trade_date) res) "每条应有 :trade_date")
      (is (every? #(contains? % :source) res) "每条应有 :source（用于区分 cache/tushare）")
      (is (every? #(#{:cache :tushare} (:source %)) res) ":source 只能是 :cache 或 :tushare")
      (is (every? (complement :placeholder) res) "返回中不应含占位符行")
      (doseq [row res]
        (let [d (:trade_date row)]
          (is (and (string? d) (= 8 (count d))) (str "trade_date 应为 8 位字符串: " d))
          (is (and (<= (compare "20250101" d) 0) (<= (compare d "20250115") 0))
              (str "trade_date 应在 [20250101, 20250115] 内: " d)))))))

(deftest get-daily-k-for-multiple-stocks-cache-hit
  "第二次请求同一区间应命中缓存：先拉取一段，再请求同一段，返回中该段应全部为 :source :cache。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [date-from "20250102"
        date-to   "20250112"
        r1       (k/get-daily-k "000001.SZ" date-from date-to)
        res      (k/get-daily-k-for-multiple-stocks ["000001.SZ"] date-from date-to)]
    (when (and (contains? r1 :ok) (seq res))
      (let [rows-001 (filter #(= "000001.SZ" (:ts_code %)) res)]
        (is (seq rows-001) "应有 000001.SZ 的数据")
        (doseq [row rows-001]
          (is (= :cache (:source row))
              (str "同一区间第二次请求应走缓存，但得到 :source=" (:source row) " trade_date=" (:trade_date row))))))))

(deftest get-daily-k-for-multiple-stocks-left-from-tushare
  "请求区间左侧超出缓存时，缺的左边应来自 tushare（:source :tushare），缓存段为 :cache。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  ;; 只拉 20250106 起 5 条，缓存约 [20250106, 有效今日]；再请求 [20250101, 20250110]，则 20250101-20250105 应为 tushare，20250106-20250110 应为 cache
  (let [r1  (k/get-daily-k "000001.SZ" "20250106" "20250110")
        res (k/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250101" "20250110")]
    (when (and (contains? r1 :ok) (seq res))
      (let [rows-001 (filter #(= "000001.SZ" (:ts_code %)) res)
            left    (filter #(neg? (compare (:trade_date %) "20250106")) rows-001)
            mid    (filter #(and (<= (compare "20250106" (:trade_date %)) 0)
                                 (<= (compare (:trade_date %) "20250110") 0)) rows-001)]
        (when (seq mid)
          (is (every? #(= :cache (:source %)) mid)
              "缓存区间 [20250106, 20250110] 内的行应来自 cache")
          (when (seq left)
            (is (every? #(= :tushare (:source %)) left)
                "请求区间内、缓存左侧的行应来自 tushare"))))))

(deftest get-daily-k-for-multiple-stocks-right-from-tushare
  "请求区间右侧超出缓存时，缺的右边应来自 tushare。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [r1  (k/get-daily-k "000001.SZ" "20250102" "20250115")
        res (k/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250101" "20250115")]
    (when (and (contains? r1 :ok) (seq res))
      (let [bounds    (k/get-cache-bounds "000001.SZ")
            rows-001 (filter #(= "000001.SZ" (:ts_code %)) res)]
        (is (seq rows-001) "应有 000001.SZ 的数据")
        (when bounds
          (let [cache-right     (:right bounds)
                right-of-cache (filter #(pos? (compare (:trade_date %) cache-right)) rows-001)]
            (when (seq right-of-cache)
              (is (every? #(= :tushare (:source %)) right-of-cache)
                  "超出缓存右界的行应来自 tushare 补全"))))))))

(deftest get-daily-k-for-multiple-stocks-multi-code
  "多 code 时：按 ts_code 分组各有数据，且来源标记正确（有 cache 的来自 cache）。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [_   (k/get-daily-k "000001.SZ" "20250103" "20250115")
        res (k/get-daily-k-for-multiple-stocks ["000001.SZ" "000002.SZ"] "20250101" "20250115")]
    (is (sequential? res))
    (when (seq res)
      (let [by-ts (group-by :ts_code res)]
        (is (every? (complement :placeholder) res) "不应含占位符")
        (is (every? #(contains? % :source) res) "每条应有 :source")
        (when (seq (get by-ts "000001.SZ"))
          (is (some #(= :cache (:source %)) (get by-ts "000001.SZ"))
              "000001.SZ 已预填缓存，返回中应至少有一条 :source :cache")))))))
