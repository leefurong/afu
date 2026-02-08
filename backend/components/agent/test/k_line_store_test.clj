(ns k-line-store-test
  "测试 k_line_store/get-k：连接真实 Datomic（afu.db），依赖库里已有全量 K 线数据。"
  (:require [clojure.test :refer [deftest is testing]]
            [afu.db :as db]
            [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k-line-store]))

(defn- ensure-k-line-store-ready []
  (k-line-store/ensure-schema! db/conn)
  (k-line-store/init! db/conn))

(deftest get-k-day-with-real-db
  (ensure-k-line-store-ready)
  (testing "日k get-k 返回 {:ok {:fields _ :items _}} 或 {:error _}，不抛异常"
    (let [res (k-line-store/get-k "000006.SZ" "日k" "20250205" 5)]
      (is (map? res) "返回 map")
      (is (or (contains? res :ok) (contains? res :error)) "含 :ok 或 :error")
      (when (:ok res)
        (let [inner (:ok res)]
          (is (contains? inner :fields) ":ok 含 :fields")
          (is (contains? inner :items) ":ok 含 :items")
          (is (#{:cache :tushare} (:source inner)) ":ok 含 :source 且为 :cache 或 :tushare")
          (is (vector? (:items inner)) ":items 为向量")
          (when (seq (:items inner))
            (is (<= (count (:items inner)) 5) "最多 count 条")
            (is (every? map? (:items inner)) "每条 item 为 map"))))
      (when (:error res)
        (is (string? (:error res)) ":error 为字符串")))))

(deftest get-k-day-second-call-hits-cache
  "同一参数连续调用两次，第二次应命中缓存（:source :cache）。"
  (ensure-k-line-store-ready)
  (let [res1 (k-line-store/get-k "000006.SZ" "日k" "20250205" 5)
        res2 (k-line-store/get-k "000006.SZ" "日k" "20250205" 5)]
    (is (:ok res1) "第一次成功")
    (is (:ok res2) "第二次成功")
    (is (= :cache (get-in res2 [:ok :source])) "第二次来自缓存")))

(deftest get-k-day-default-count
  (ensure-k-line-store-ready)
  (testing "日k 三参 get-k 默认 count=20"
    (let [res (k-line-store/get-k "000001.SZ" "日k" "20250101")]
      (when (and (:ok res) (seq (get-in res [:ok :items])))
        (is (<= (count (get-in res [:ok :items])) 20) "默认最多 20 条")))))

(deftest get-k-validation
  (ensure-k-line-store-ready)
  (testing "非法日期返回 {:error _}"
    (let [res (k-line-store/get-k "000001" "日k" "not-a-date")]
      (is (contains? res :error))
      (is (string? (:error res)))))
  (testing "季k 返回不支持错误"
    (let [res (k-line-store/get-k "000001" "季k" "20250101")]
      (is (= "暂不支持季k/年k，请使用 日k、周k 或 月k。" (:error res))))))
