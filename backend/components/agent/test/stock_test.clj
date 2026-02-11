(ns stock-test
  "测试 stock 命名空间：get-daily-k、ma、golden-cross 的返回结构与数值正确性。使用固定 fixture 数据，不依赖 Tushare。"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k]
            [agent.tools.execute-clojure.sci-sandbox.stock :as stock])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

;; 25 个交易日：20250102～20250205（跳过周末）
(def ^:private fixture-dates
  ["20250102" "20250103" "20250106" "20250107" "20250108" "20250109" "20250110"
   "20250113" "20250114" "20250115" "20250116" "20250117" "20250120" "20250121"
   "20250122" "20250123" "20250124" "20250127" "20250128" "20250129" "20250130"
   "20250131" "20250203" "20250204" "20250205"])

;; 收盘价：前 20 日从 30 递减到 11，后 5 日 20,21,22,23,24。便于构造「前低后高」产生金叉。
(def ^:private fixture-closes
  [30 29 28 27 26 25 24 23 22 21 20 19 18 17 16 15 14 13 12 11 20 21 22 23 24])

;; 手算：最后一日 20250205 的 MA5 = (20+21+22+23+24)/5 = 22.0
(def ^:private expected-ma5-last 22.0)
;; 最后一日 MA20 = 过去 20 日收盘和/20。日 6～25 的 close：25,24,...,12,11,20,21,22,23,24；和 = (11+25)*15/2 + (20+21+22+23+24) = 270+110 = 380，MA20 = 19.0
(def ^:private expected-ma20-last 19.0)
;; 金叉：在 20250204 当日 MA5(19.4) 上穿 MA20(19.1)；前一日 20250203 仍 MA5<=MA20
(def ^:private expected-golden-cross-date "20250204")
(def ^:private expected-golden-cross-short-ma 19.4)
(def ^:private expected-golden-cross-long-ma 19.1)

(defn- fixture-rows []
  (mapv (fn [d c]
          [d {:close c :open c :high c :low c :pre_close (dec c) :vol 1000000 :amount 1.0e7}])
        fixture-dates
        fixture-closes))

(defn- setup-fixture! [ts-code]
  (k/reset-for-test!)
  (k/ensure-schema! nil ":memory:")
  (k/insert-daily-rows-for-test! ts-code (fixture-rows)))

(deftest get-daily-k-values
  (setup-fixture! "000001.SZ")
  (testing "get-daily-k-for-multiple-stocks 返回的日 K 中，指定日期的 close 与 fixture 一致"
    (let [res (stock/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250102" "20250205")]
      (is (not (:error res)) (str "应成功: " res))
      (when-not (:error res)
        (let [rows (get-in res [:ok :by_ts_code "000001.SZ"])
              row-20250205 (first (filter #(= "20250205" (str (:trade_date %))) rows))]
          (is row-20250205 "应有 20250205 的 K 线")
          (when row-20250205
            (is (= 24.0 (double (:close row-20250205)))
                "20250205 的 close 应为 24（fixture 最后一日）")))))
  (testing "首日 20250102 的 close 为 30"
    (let [res (stock/get-daily-k-for-multiple-stocks ["000001.SZ"] "20250102" "20250102")]
      (when-not (:error res)
        (let [rows (get-in res [:ok :by_ts_code "000001.SZ"])
              row (first (filter #(= "20250102" (str (:trade_date %))) rows))]
          (is (= 30.0 (double (:close row))) "20250102 close 应为 30")))))))

(defn- approx=
  "浮点近似相等，tolerance 为绝对误差上限。"
  [a b tolerance]
  (and (number? a) (number? b)
       (<= (Math/abs (- (double a) (double b))) tolerance)))
(deftest ma-values
  (setup-fixture! "000001.SZ")
  (testing "ma-for-multiple-stocks 最后一日 MA5=22"
    (let [res (stock/ma-for-multiple-stocks ["000001.SZ"] 5 "20250102" "20250205")]
      (is (not (:error res)) (str "MA 应成功: " res))
      (when-not (:error res)
        (let [items (get-in res [:ok :by_ts_code "000001.SZ" :items])
              last-row (peek (vec items))]
          (is last-row "应有至少一行 MA")
          (when last-row
            (is (= "20250205" (str (:trade_date last-row))) "最后一行日期应为 20250205")
            (is (approx= expected-ma5-last (:ma5 last-row) 0.01)
                (str "MA5 应为 " expected-ma5-last "，实际 " (:ma5 last-row))))))))
  (testing "ma-for-multiple-stocks 最后一日 MA20=19"
    (let [res (stock/ma-for-multiple-stocks ["000001.SZ"] 20 "20250102" "20250205")]
      (when-not (:error res)
        (let [items (get-in res [:ok :by_ts_code "000001.SZ" :items])
              last-row (peek (vec items))]
          (when last-row
            (is (approx= expected-ma20-last (get last-row :ma20) 0.01)
                (str "MA20 应为 " expected-ma20-last "，实际 " (get last-row :ma20)))))))))



(deftest golden-cross-values
  (setup-fixture! "000001.SZ")
  (testing "golden-cross-for-multiple-stocks 在 [20250102, 20250205] 内检测到一次金叉，日期与 MA 值正确"
    (let [res (stock/golden-cross-for-multiple-stocks ["000001.SZ"] 5 20 "20250102" "20250205")]
      (is (not (:error res)) (str "金叉应成功: " res))
      (when-not (:error res)
        (let [crosses (get-in res [:ok :by_ts_code "000001.SZ" :crosses])]
          (is (= 1 (count crosses)) (str "应恰好 1 次金叉，实际: " (count crosses)))
          (when (= 1 (count crosses))
            (let [c (first crosses)]
              (is (= expected-golden-cross-date (str (:date c)))
                  (str "金叉日期应为 " expected-golden-cross-date))
              (is (approx= expected-golden-cross-short-ma (:ma5 c) 0.01)
                  (str "ma5 应为 " expected-golden-cross-short-ma))
              (is (approx= expected-golden-cross-long-ma (:ma20 c) 0.01)
                  (str "ma20 应为 " expected-golden-cross-long-ma)))))))))

(deftest cross-signals-on-date-matches-ma-criteria
  "cross-signals-on-date 返回的金叉/死叉清单，必须与按 MA5/MA20 现算结果一致：金叉日该码在 golden_cross，非金叉日不在。"
  (setup-fixture! "000001.SZ")
  ;; 同步写 MA 与 cross_type 到 DB（与 k_line_store 写库逻辑一致）
  (k/update-ma-for-stock-date-range! "000001.SZ" "20250102" "20250205" false)
  (testing "20250204 为金叉日，cross-signals-on-date 当日应包含 000001.SZ 在 golden_cross"
    (let [res (stock/cross-signals-on-date ["000001.SZ"] "20250204")]
      (is (some #(= (:ts_code %) "000001.SZ") (:golden_cross res))
          "金叉日 20250204 应在 golden_cross 清单中")
      (is (not (some #(= (:ts_code %) "000001.SZ") (:death_cross res)))
          "金叉日不应在 death_cross 中")))
  (testing "20250203 非金叉日，cross-signals-on-date 当日不应在 golden_cross"
    (let [res (stock/cross-signals-on-date ["000001.SZ"] "20250203")]
      (is (not (some #(= (:ts_code %) "000001.SZ") (:golden_cross res)))
          "前一日 20250203 尚未金叉，不应在 golden_cross 中"))))

(deftest ma-period-validation
  (setup-fixture! "000001.SZ")
  (testing "ma-for-multiple-stocks period < 2 返回错误"
    (is (= {:error "MA 周期至少为 2。"}
           (stock/ma-for-multiple-stocks ["000001.SZ"] 1 "20250102" "20250205"))))
  (testing "golden-cross-for-multiple-stocks short >= long 返回错误"
    (is (str/includes?
         (str (:error (stock/golden-cross-for-multiple-stocks ["000001.SZ"] 20 5 "20250102" "20250205")))
         "短期周期应小于长期周期"))))

(def ^:private basic-iso (DateTimeFormatter/ofPattern "yyyyMMdd"))

(deftest cross-signals-on-date-real-data-consistent-with-ma
  "真实数据测试：调 cross-signals-on-date，若无数据会触发 backfill 则等 3 秒后再查；用 MA 接口取当日及前日 MA5/MA20，
   按金叉定义计算是否金叉，与 cross-signals 返回的 golden_cross 清单比对是否一致。
   使用文件 DB（非全新库，可能已有历史数据）；日期与股票代码放在 let 中便于修改。"
  (let [trade-date "20250506"
        code      "000623.SZ"
        codes     [code]
        date-from (-> (LocalDate/parse trade-date basic-iso)
                      (.minus 60 ChronoUnit/DAYS)
                      (.format basic-iso))]
    (k/reset-for-test!)
    (k/ensure-schema! nil)
    (let [res1 (stock/cross-signals-on-date codes trade-date)
          stock-count (get-in res1 [:summary :stock_count] 0)
          data-count  (get-in res1 [:summary :data_count] 0)
          triggered?  (get-in res1 [:summary :backfill_triggered])]
      (when (and (= stock-count 1) (= data-count 0))
        (is triggered? "stock_count 1 且 data_count 0 时必须触发 backfill_triggered"))
      (when triggered?
        (Thread/sleep 3000))
      (let [res2     (stock/cross-signals-on-date codes trade-date)
          in-golden? (boolean (some #(= (:ts_code %) code) (:golden_cross res2)))
          ma5-res  (stock/ma-for-multiple-stocks codes 5 date-from trade-date)
          ma20-res (stock/ma-for-multiple-stocks codes 20 date-from trade-date)]
      (is (not (:error ma5-res)) (str "MA5 应成功: " ma5-res))
      (is (not (:error ma20-res)) (str "MA20 应成功: " ma20-res))
      (when (and (not (:error ma5-res)) (not (:error ma20-res)))
        (let [items-5   (get-in ma5-res [:ok :by_ts_code code :items])
              items-20  (get-in ma20-res [:ok :by_ts_code code :items])
              by-date-5 (into {} (for [item (seq (or items-5 []))
                                      :when (map? item)
                                      :let [d (str (get item :trade_date))]
                                      :when (seq d)]
                                  [d item]))
              by-date-20 (into {} (for [item (seq (or items-20 []))
                                       :when (map? item)
                                       :let [d (str (get item :trade_date))]
                                       :when (seq d)]
                                   [d item]))
              dates     (sort (seq (set/intersection (set (keys by-date-5)) (set (keys by-date-20)))))
              merged    (mapv (fn [d]
                               (let [a (get by-date-5 d), b (get by-date-20 d)]
                                 {:date d :ma5 (:ma5 a) :ma20 (:ma20 b)}))
                              dates)]
          (when (>= (count merged) 2)
            (let [prev   (nth merged (- (count merged) 2))
                  curr   (nth merged (dec (count merged)))
                  p5     (:ma5 prev)
                  p20    (:ma20 prev)
                  c5     (:ma5 curr)
                  c20    (:ma20 curr)
                  golden-by-ma? (and (number? p5) (number? p20) (number? c5) (number? c20)
                                     (<= p5 p20) (> c5 c20))]
              (is (and in-golden? golden-by-ma?)
                  (str "cross-signals 清单与按 MA 现算应一致: 当日 " trade-date " " code
                       " in_golden_cross=" in-golden?
                       " 按MA金叉=" golden-by-ma?
                       " (prev MA5=" p5 " MA20=" p20 " curr MA5=" c5 " MA20=" c20 ")"))))))))))
