(ns agent.tools.execute-clojure.sci-sandbox.stock
  "SCI 沙箱内可调用的 K 线数据：日k 委托给 k-line-store；request-tushare-api 委托给 tushare。"
  (:require [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k-line-store]
            [agent.tools.execute-clojure.sci-sandbox.tushare :as tushare]
            [clojure.string :as str])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private basic-iso DateTimeFormatter/BASIC_ISO_DATE)

(def ^:private default-daily-fields
  "与 k-line-store 日 K 返回的 :fields 一致，用于 ma-for-multiple-stocks 构建 payload。"
  ["ts_code" "trade_date" "open" "high" "low" "close" "pre_close" "change" "pct_chg" "vol" "amount"])

(defn- parse-ymd
  "将 YYYYMMDD 字符串转为 LocalDate。"
  [s]
  (when (and (string? s) (= 8 (count s)))
    (try
      (LocalDate/parse s basic-iso)
      (catch Exception _ nil))))

(defn- date->str
  [^LocalDate d]
  (.format d basic-iso))

(defn- plus-days
  [^LocalDate d n]
  (.plus d n ChronoUnit/DAYS))

(defn- minus-days
  [^LocalDate d n]
  (.minus d n ChronoUnit/DAYS))

(defn- next-weekday
  "若 d 为周六/周日则顺延到下一周一；否则返回 d。"
  [^LocalDate d]
  (case (.getValue (.getDayOfWeek d))
    6 (plus-days d 2)   ;; Saturday -> Monday
    7 (plus-days d 1)   ;; Sunday -> Monday
    d))

(defn- start-date-from-beg
  "起始日：beg-date 若为周末则顺延到下一交易日（周一）。"
  [beg-date]
  (when-let [d (parse-ymd beg-date)]
    (date->str (next-weekday d))))

(defn- end-date-from-beg-and-count
  "根据起始日与需要的 K 线条数计算 end_date，保证区间内至少有 count 根 K 线。"
  [beg-date count dwmsy]
  (when-let [start-d (parse-ymd beg-date)]
    (let [start-d (next-weekday start-d)
          days    (case dwmsy
                    "日k" (* count 2)   ;; 约 count 个交易日
                    "周k" (* count 7)
                    "月k" (* (max 1 count) 31)
                    31)]
      (date->str (plus-days start-d (max 1 days))))))

(defn- dwmsy->api
  "K 线类型 -> [api_name, params 中的 freq]。日k 用 daily 无 freq；周/月用 stk_weekly_monthly。"
  [dwmsy]
  (case dwmsy
    "日k" [:daily nil]
    "周k" [:stk_weekly_monthly "week"]
    "月k" [:stk_weekly_monthly "month"]
    "季k" [:unsupported nil]
    "年k" [:unsupported nil]
    nil))

(defn- normalize-ts-code
  "若用户只传 000001，补全为 000001.SZ（深市）或 000001.SH（沪市）。简单规则：6 开头为沪，否则为深。"
  [s]
  (cond
    (str/blank? s) s
    (str/includes? (str s) ".") (str s)
    (str/starts-with? (str s) "6") (str s ".SH")
    :else (str s ".SZ")))

(defn request-tushare-api
  "供其他 ns 调用的 Tushare 请求。委托给 tushare ns。"
  [api-name params]
  (tushare/request-tushare-api api-name params))

(defn- field-index
  [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

(defn get-k
  "获取 K 线数据。日k/周k/月k 均为 date-from + date-to；3 参数时 date-to=date-from，只取这一根（周k/月k 用该周/月第一天代表）。"
  ([stock-code dwmsy date-from]
   (get-k stock-code dwmsy date-from date-from))
  ([stock-code dwmsy date-from date-to]
   (k-line-store/get-k stock-code dwmsy date-from date-to)))

(defn- parse-close
  [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch NumberFormatException _ nil))
    :else nil))

(defn- simple-moving-average
  "计算 closes 的 days 日简单移动平均，返回与 closes 等长的序列，前 (dec days) 个为 nil。"
  [closes days]
  (let [n (count closes)
        d (long days)]
    (mapv (fn [i]
            (if (< i (dec d))
              nil
              (let [seg (subvec closes (inc (- i d)) (inc i))
                    s (reduce + 0.0 seg)]
                (/ s d))))
          (range n))))

(defn- row-date-str
  "取 row（map，含 :trade_date）的 trade_date 并转为可比较的 YYYYMMDD 字符串。"
  [row]
  (str (get row :trade_date "")))

(defn- ma-from-payload
  "根据 get-k 的 :ok payload 计算 MA 并返回最后 back-days 天的 items。payload :items 为 map 行。"
  [payload ma-days back-days till-date]
  (let [fields    (:fields payload)
        items-raw (or (:items payload) [])
        items-asc (vec (reverse items-raw))
        has-date? (field-index fields "trade_date")
        has-close? (field-index fields "close")]
    (if (or (nil? has-date?) (nil? has-close?))
      {:error "K 线数据缺少 trade_date 或 close 字段"}
      (let [till-str   (if (str/blank? (str till-date))
                         (row-date-str (peek items-asc))
                         (str till-date))
            i-end      (last (keep-indexed (fn [i row]
                                             (when (<= (compare (row-date-str row) till-str) 0) i))
                                           items-asc))
            slice-len  (+ (dec ma-days) back-days)
            i-start    (when (and (some? i-end) (>= i-end (dec slice-len)))
                         (max 0 (- i-end slice-len -1)))
            slice      (when (and (some? i-start) (some? i-end))
                         (subvec items-asc i-start (inc i-end)))]
        (cond
          (or (nil? slice) (< (count slice) ma-days))
          {:error (str "无法在截止日 " (or till-str till-date) " 前取到 " back-days " 天数据。")}
          :else
          (let [closes    (mapv (fn [row] (parse-close (:close row))) slice)
                ma-vals   (simple-moving-average closes ma-days)
                ma-kw     (keyword (str "ma" ma-days))
                out-start (- (count slice) back-days)
                out-items (mapv (fn [i]
                                 (let [row (nth slice i)
                                       d   (:trade_date row)
                                       c   (nth closes i)
                                       m   (nth ma-vals i)]
                                   {:trade_date (str d) :close c ma-kw m}))
                               (vec (range out-start (count slice))))]
            {:ok {:items out-items}}))))))

(defn ma
  "计算日 K 收盘价的 N 周期简单移动平均。语义：截止日 till-date，往前取 back-days 个交易日。
   - stock-code: 股票代码；period: MA 周期（如 5、10、20、60）。
   - till-date: 截止日 YYYYMMDD（含）；不传则用「最近交易日」。
   - back-days: 从 till-date 往前取多少个交易日，默认 1（就取截止日当天）。
   用法：今天 MA5 → (ma \"000001\" 5)；某日当天 → (ma \"000001\" 5 \"20260206\")；某日及前 4 天 → (ma \"000001\" 5 \"20260206\" 5)。
   返回 {:ok {:items [{:trade_date \"...\" :close x :maN y} ...]}} 按日期升序（最早一天在前）。"
  ([stock-code period]
   (ma stock-code period nil 1))
  ([stock-code period till-date]
   (ma stock-code period till-date 1))
  ([stock-code period till-date back-days]
   (let [ma-days    (long period)
         back-days  (if (or (nil? back-days) (neg? (long back-days))) 1 (long back-days))]
     (cond
       (< ma-days 2) {:error "MA 周期至少为 2。"}
       (and till-date (not (parse-ymd till-date))) {:error "截止日格式须为 YYYYMMDD。"}
       :else
       (let [start-d   (if till-date
                         (when-let [d (parse-ymd till-date)]
                           (date->str (minus-days (next-weekday d) 400)))
                         (date->str (minus-days (LocalDate/now) 400)))
             date-to   (if till-date
                         (if (string? till-date) till-date (date->str till-date))
                         (k-line-store/effective-today-ymd))
             k         (get-k (str stock-code) "日k" start-d date-to)]
         (cond
           (:error k) k
           (nil? start-d) {:error "截止日格式须为 YYYYMMDD。"}
           :else (ma-from-payload (:ok k) ma-days back-days till-date)))))))

(defn ma-for-multiple-stocks
  "多股票版 MA：对多只股票一次性取日 K 并计算 N 周期移动平均。语义与 ma 一致：截止日 till-date，往前 back-days 个交易日。
   - stock-codes: 股票代码序列（如 [\"000001\" \"000002\"]）；period: MA 周期；可选 till-date、back-days。
   返回 {:ok {:by_ts_code {\"000001.SZ\" {:items [...]} \"000002.SZ\" {:items [...]} ...}}} 或 {:error _}。"
  ([stock-codes period]
   (ma-for-multiple-stocks stock-codes period nil 1))
  ([stock-codes period till-date]
   (ma-for-multiple-stocks stock-codes period till-date 1))
  ([stock-codes period till-date back-days]
   (let [ma-days    (long period)
         back-days  (if (or (nil? back-days) (neg? (long back-days))) 1 (long back-days))
         ts-codes   (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))]
     (cond
       (empty? ts-codes) {:error "至少需要一只股票代码。"}
       (< ma-days 2) {:error "MA 周期至少为 2。"}
       (and till-date (not (parse-ymd till-date))) {:error "截止日格式须为 YYYYMMDD。"}
       :else
       (let [start-d   (if till-date
                         (when-let [d (parse-ymd till-date)]
                           (date->str (minus-days (next-weekday d) 400)))
                         (date->str (minus-days (LocalDate/now) 400)))
             date-to   (if till-date
                         (if (string? till-date) till-date (date->str till-date))
                         (k-line-store/effective-today-ymd))
             all-rows  (when start-d (k-line-store/get-daily-k-for-multiple-stocks ts-codes start-d date-to))]
         (cond
           (nil? all-rows) {:error "K 线缓存未初始化"}
           (nil? start-d) {:error "截止日格式须为 YYYYMMDD。"}
           :else
           (let [by-code   (group-by :ts_code all-rows)
                 results   (map (fn [code]
                                  (let [rows   (get by-code code [])
                                        payload {:fields default-daily-fields
                                                 :items  (mapv #(update % :trade_date str) rows)}
                                        res    (ma-from-payload payload ma-days back-days till-date)]
                                    [code res]))
                               ts-codes)
                 first-err (first (keep (fn [[_code res]] (when (:error res) res)) results))]
             (if first-err
               first-err
               {:ok {:by_ts_code (into {} (map (fn [[code res]] [code (:ok res)]) results))}}))))))))

(defn- crosses-from-ma-items
  "根据短期、长期 MA 的 items（均按 trade_date 升序）检测金叉，返回 [{:date _ :short_ma _ :long_ma _} ...]。"
  [short-items long-items short-period long-period]
  (let [skw (keyword (str "ma" short-period))
        lkw (keyword (str "ma" long-period))
        n   (min (count short-items) (count long-items))]
    (if (< n 2)
      []
      (into []
            (comp
             (map (fn [i]
                    (let [prev-s (get (nth short-items (dec i)) skw)
                          prev-l (get (nth long-items (dec i)) lkw)
                          curr-s (get (nth short-items i) skw)
                          curr-l (get (nth long-items i) lkw)
                          d     (:trade_date (nth short-items i))]
                      (when (and (number? prev-s) (number? prev-l) (number? curr-s) (number? curr-l)
                                 (<= prev-s prev-l) (> curr-s curr-l))
                        {:date d :short_ma curr-s :long_ma curr-l}))))
             (filter some?))
            (range 1 n)))))

(defn golden-cross
  "检测短期均线上穿长期均线（金叉）。语义与 ma 一致：截止日 till-date，往前 back-days 天。
   参数: stock-code, short-days, long-days；可选 till-date（默认最近交易日）、back-days（默认 5）。
   返回 {:ok {:crosses [{:date \"YYYYMMDD\" :short_ma x :long_ma y} ...]}} 或 {:error _}。"
  ([stock-code short-days long-days]
   (golden-cross stock-code short-days long-days nil 5))
  ([stock-code short-days long-days till-date]
   (golden-cross stock-code short-days long-days till-date 5))
  ([stock-code short-days long-days till-date back-days]
   (cond
     (< (long short-days) 2) {:error "短期 MA 周期至少为 2。"}
     (< (long long-days) 2) {:error "长期 MA 周期至少为 2。"}
     (>= (long short-days) (long long-days)) {:error "短期周期应小于长期周期（如 5 与 20）。"}
     :else
     (let [ma-short (ma (str stock-code) (long short-days) till-date back-days)
           ma-long  (ma (str stock-code) (long long-days) till-date back-days)]
       (cond
         (:error ma-short) ma-short
         (:error ma-long)  ma-long
         :else
         (let [short-items (get-in ma-short [:ok :items])
               long-items  (get-in ma-long [:ok :items])
               crosses    (crosses-from-ma-items short-items long-items (long short-days) (long long-days))]
           {:ok {:crosses crosses}}))))))

(defn golden-cross-for-multiple-stocks
  "多股票版金叉：对多只股票一次性取短期/长期 MA 并检测金叉。语义与 golden-cross 一致。
   - stock-codes: 股票代码序列；short-period、long-period: 短期/长期 MA 周期；可选 till-date（默认最近交易日）、back-days（默认 5）。
   返回 {:ok {:by_ts_code {\"000001.SZ\" {:crosses [...]} ...}}} 或 {:error _}。"
  ([stock-codes short-period long-period]
   (golden-cross-for-multiple-stocks stock-codes short-period long-period nil 5))
  ([stock-codes short-period long-period till-date]
   (golden-cross-for-multiple-stocks stock-codes short-period long-period till-date 5))
  ([stock-codes short-period long-period till-date back-days]
   (let [short-period (long short-period)
         long-period  (long long-period)
         ts-codes     (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))]
     (cond
       (empty? ts-codes) {:error "至少需要一只股票代码。"}
       (< short-period 2) {:error "短期 MA 周期至少为 2。"}
       (< long-period 2) {:error "长期 MA 周期至少为 2。"}
       (>= short-period long-period) {:error "短期周期应小于长期周期（如 5 与 20）。"}
       :else
       (let [ma-short (ma-for-multiple-stocks ts-codes short-period till-date back-days)
             ma-long  (ma-for-multiple-stocks ts-codes long-period till-date back-days)]
         (cond
           (:error ma-short) ma-short
           (:error ma-long)  ma-long
           :else
           (let [short-by (get-in ma-short [:ok :by_ts_code])
                 long-by  (get-in ma-long [:ok :by_ts_code])
                 by-code  (map (fn [code]
                                [code {:crosses (crosses-from-ma-items
                                                 (get-in short-by [code :items])
                                                 (get-in long-by [code :items])
                                                 short-period long-period)}])
                              ts-codes)]
             {:ok {:by_ts_code (into {} by-code)}})))))))
