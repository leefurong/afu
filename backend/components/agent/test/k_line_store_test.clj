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
    (let [res (k/get-daily-k "000001.SZ" "20250101" 5)]
      (is (contains? res :error))
      (is (string? (:error res)))))
  (testing "使用 :memory: 初始化后 get-daily-k 可调用（会请求 Tushare，无 token 则 :error）"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (let [res (k/get-daily-k "000001.SZ" "20250101" 5)]
      (is (or (contains? res :ok) (contains? res :error)))
      (when (:ok res)
        (is (contains? (:ok res) :source))
        (is (contains? (:ok res) :items))
        (is (contains? (:ok res) :fields))))))

(deftest get-k-dwmsy
  (testing "日k 委托 get-daily-k"
    (k/reset-for-test!)
    (k/ensure-schema! nil ":memory:")
    (let [res (k/get-k "000001" "日k" "20250101" 10)]
      (is (or (contains? res :ok) (contains? res :error)))))
  (testing "周k 直连 Tushare，返回 :ok 或 :error"
    (let [res (k/get-k "000001" "周k" "20250101" 10)]
      (is (or (contains? res :ok) (contains? res :error)))))
  (testing "季k 返回错误"
    (is (contains? (k/get-k "000001" "季k" "20250101" 10) :error))))

(deftest cache-continuity-after-left-extend
  "先请求 2025-01-01，再请求 2023-01-01 后，缓存应覆盖 [2023-01-01, 有效今日]，且再次请求 2023-01-01 应命中缓存。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [effective-today (k/effective-today-ymd)
        r1 (k/get-daily-k "000001.SZ" "20250101" 5)
        r2 (k/get-daily-k "000001.SZ" "20230101" 10)]
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
      (let [r3 (k/get-daily-k "000001.SZ" "20230101" 5)]
        (when (contains? r3 :ok)
          (is (= :cache (get-in r3 [:ok :source]))
              "再次请求 2023-01-01 应命中缓存"))))))

(deftest cache-right-is-effective-today
  "get-daily-k 成功后，缓存右界应为当前求得的有效今日。"
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (let [effective-today (k/effective-today-ymd)
        res (k/get-daily-k "000001.SZ" "20250201" 3)]
    (is (or (contains? res :ok) (contains? res :error)) "get-daily-k 应返回 :ok 或 :error")
    (when (contains? res :ok)
      (let [bounds (k/get-cache-bounds "000001.SZ")]
        (when bounds
          (is (= effective-today (:right bounds))
              "右界应为有效今日（测试内用 effective-today-ymd 求得）"))))))
