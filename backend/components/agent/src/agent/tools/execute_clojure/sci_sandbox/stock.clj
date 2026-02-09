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

(defn get-daily-k-for-multiple-stocks
  "多股票日 K：在 [date-from, date-to] 区间内获取多只股票的日 K 线。
   - stock-codes: 股票代码序列（如 [\"000001\" \"000002\"]）；date-from、date-to: 日期闭区间 YYYYMMDD。
   - 返回 {:ok {:fields _ :by_ts_code {\"000001.SZ\" [{:trade_date _ :close _ :open _ ...} ...]} ...}} 或 {:error _}。未初始化时 {:error \"K 线缓存未初始化\"}。"
  [stock-codes date-from date-to]
  (let [from-str (str date-from)
        to-str   (str date-to)
        ts-codes (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))]
    (cond
      (empty? ts-codes) {:error "至少需要一只股票代码。"}
      (not (parse-ymd from-str)) {:error "date-from 格式须为 YYYYMMDD。"}
      (not (parse-ymd to-str)) {:error "date-to 格式须为 YYYYMMDD。"}
      (> (compare from-str to-str) 0) {:error "date-from 不能晚于 date-to。"}
      :else
      (let [rows (k-line-store/get-daily-k-for-multiple-stocks ts-codes from-str to-str)]
        (if (nil? rows)
          {:error "K 线缓存未初始化"}
          {:ok {:fields default-daily-fields
                :by_ts_code (into {}
                                  (map (fn [[code items]]
                                         [code (mapv #(update % :trade_date str) items)])
                                       (group-by :ts_code rows)))}})))))

(defn- field-index
  [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

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

(defn- ma-from-payload-by-range
  "根据日 K payload 计算 MA，仅返回 [date-from, date-to] 区间内（含）每个交易日的 items。
   payload :items 为 map 行；区间为闭区间，日期 YYYYMMDD 字符串。"
  [payload ma-days date-from date-to]
  (let [fields    (:fields payload)
        items-raw (or (:items payload) [])
        items-asc (vec (reverse items-raw))
        has-date? (field-index fields "trade_date")
        has-close? (field-index fields "close")
        from-str   (str date-from)
        to-str     (str date-to)]
    (if (or (nil? has-date?) (nil? has-close?))
      {:error "K 线数据缺少 trade_date 或 close 字段"}
      (let [i-date-from (first (keep-indexed (fn [i row]
                                               (when (>= (compare (row-date-str row) from-str) 0) i))
                                             items-asc))
            i-end       (last (keep-indexed (fn [i row]
                                              (when (<= (compare (row-date-str row) to-str) 0) i))
                                            items-asc))]
        (cond
          (or (nil? i-date-from) (nil? i-end) (> i-date-from i-end))
          {:ok {:items []}}
          :else
          (let [i-start   (max 0 (- i-date-from (dec ma-days)))
                slice     (subvec items-asc i-start (inc i-end))
                ;; simple-moving-average 假定日期升序（最早在前），前 (dec ma-days) 个为 nil；故用升序 slice 计算
                slice-asc (vec (reverse slice))]
            (if (< (count slice) ma-days)
              {:error (str "无法在 " from-str " 前取到足够 " ma-days " 日数据以计算 MA。")}
              (let [closes   (mapv (fn [row] (parse-close (:close row))) slice-asc)
                    ma-vals  (simple-moving-average closes ma-days)
                    ma-kw    (keyword (str "ma" ma-days))
                    out-items (into []
                                    (keep-indexed (fn [i row]
                                                    (let [d (row-date-str row)
                                                          m (nth ma-vals i)]
                                                      (when (and (number? m)
                                                                 (>= (compare d from-str) 0)
                                                                 (<= (compare d to-str) 0))
                                                        {:trade_date (str (:trade_date row))
                                                         :close     (nth closes i)
                                                         ma-kw      m})))
                                                  slice-asc))]
                {:ok {:items out-items}}))))))))

(defn ma-for-multiple-stocks
  "多股票版 MA：对多只股票一次性取日 K，计算 [date-from, date-to] 区间内每个交易日的 period 日移动平均。
   - stock-codes: 股票代码序列（如 [\"000001\" \"000002\"]）；period: MA 周期（如 5、10、20）。
   - date-from, date-to: 日期闭区间 YYYYMMDD。3 参数时 (stock-codes period date) 表示单日。
   返回 {:ok {:by_ts_code {\"000001.SZ\" {:items [...]} \"000002.SZ\" {:items [...]} ...}}} 或 {:error _}。"
  ([stock-codes period date-from date-to]
   (let [ma-days   (long period)
         ts-codes  (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))
         from-str  (str date-from)
         to-str    (str date-to)]
     (cond
       (empty? ts-codes) {:error "至少需要一只股票代码。"}
       (< ma-days 2) {:error "MA 周期至少为 2。"}
       (not (parse-ymd from-str)) {:error "date-from 格式须为 YYYYMMDD。"}
       (not (parse-ymd to-str)) {:error "date-to 格式须为 YYYYMMDD。"}
       (> (compare from-str to-str) 0) {:error "date-from 不能晚于 date-to。"}
       :else
       (let [start-d  (when-let [d (parse-ymd from-str)]
                        (date->str (minus-days (next-weekday d) 400)))
             all-rows  (when start-d (k-line-store/get-daily-k-for-multiple-stocks ts-codes start-d to-str))]
         (cond
           (nil? all-rows) {:error "K 线缓存未初始化"}
           (nil? start-d) {:error "date-from 格式须为 YYYYMMDD。"}
           :else
           (let [by-code   (group-by :ts_code all-rows)
                 results   (map (fn [code]
                                  (let [rows   (sort-by #(str (:trade_date %)) (get by-code code []))
                                        payload {:fields default-daily-fields
                                                 :items  (mapv #(update % :trade_date str) rows)}
                                        res    (ma-from-payload-by-range payload ma-days from-str to-str)]
                                    [code res]))
                                ts-codes)
                 first-err (first (keep (fn [[_code res]] (when (:error res) res)) results))]
             (if first-err
               first-err
               {:ok {:by_ts_code (into {} (map (fn [[code res]] [code (:ok res)]) results))}})))))))
  ([stock-codes period date]
   (ma-for-multiple-stocks stock-codes period (str date) (str date))))

(defn- crosses-from-ma-items
  "根据短期、长期 MA 的 items（均按 trade_date 升序）检测金叉，返回 [{:date _ :ma5 _ :ma20 _} ...]（key 为 ma{short-period}, ma{long-period}）。"
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
                        (assoc {:date d} skw curr-s lkw curr-l)))))
             (filter some?))
            (range 1 n)))))

(defn golden-cross-for-multiple-stocks
  "多股票版金叉：在 [date-from, date-to] 区间内检测短期均线上穿长期均线。
   - stock-codes: 股票代码序列；short-period、long-period: 短期/长期 MA 周期；date-from、date-to: 日期闭区间 YYYYMMDD。
   - 若区间内无交易日，该股票返回 {:crosses []}。
   返回 {:ok {:by_ts_code {\"000001.SZ\" {:crosses [...]} ...}}} 或 {:error _}。"
  [stock-codes short-period long-period date-from date-to]
  (let [short-period (long short-period)
        long-period  (long long-period)
        from-str     (str date-from)
        to-str       (str date-to)
        ts-codes     (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))]
    (cond
      (empty? ts-codes) {:error "至少需要一只股票代码。"}
      (< short-period 2) {:error "短期 MA 周期至少为 2。"}
      (< long-period 2) {:error "长期 MA 周期至少为 2。"}
      (>= short-period long-period) {:error "短期周期应小于长期周期（如 5 与 20）。"}
      (not (parse-ymd from-str)) {:error "date-from 格式须为 YYYYMMDD。"}
      (not (parse-ymd to-str)) {:error "date-to 格式须为 YYYYMMDD。"}
      (> (compare from-str to-str) 0) {:error "date-from 不能晚于 date-to。"}
      :else
      (let [ma-short (ma-for-multiple-stocks ts-codes short-period from-str to-str)
            ma-long  (ma-for-multiple-stocks ts-codes long-period from-str to-str)]
        (cond
          (:error ma-short) ma-short
          (:error ma-long)  ma-long
          :else
          (let [short-by (get-in ma-short [:ok :by_ts_code])
                long-by  (get-in ma-long [:ok :by_ts_code])
                by-code  (map (fn [code]
                                [code {:crosses (crosses-from-ma-items
                                                 (or (get-in short-by [code :items]) [])
                                                 (or (get-in long-by [code :items]) [])
                                                 short-period long-period)}])
                              ts-codes)]
            {:ok {:by_ts_code (into {} by-code)}}))))))
