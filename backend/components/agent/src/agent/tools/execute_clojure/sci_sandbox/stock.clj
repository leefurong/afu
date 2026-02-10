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
  "多股票日 K：仅从本地缓存读取 [date-from, date-to]；若返回数据的最早/最晚日期未覆盖请求区间则异步触发补数并算 MA 写库，本次仍只返回当前缓存。
   - 返回 {:ok {:fields _ :by_ts_code _ :backfill_triggered _}} 或 {:error _}；:backfill_triggered 为 true 表示已触发异步补数。"
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
      (let [rows        (k-line-store/get-daily-k-for-multiple-stocks-from-cache-only ts-codes from-str to-str)
            by-code     (when rows (group-by :ts_code rows))
            ;; 每只股票缓存的 [min-date, max-date] 是否覆盖 [from-str, to-str]，用 compare 做字符串日期比较
            sufficient? (and (some? rows) (seq ts-codes)
                            (every? (fn [code]
                                      (let [code-rows (get by-code code [])
                                            str-dates (mapv #(str (get % :trade_date)) code-rows)]
                                        (and (seq str-dates)
                                             (let [sorted (sort str-dates)
                                                   min-d  (first sorted)
                                                   max-d  (last sorted)]
                                               (and (<= (compare min-d from-str) 0)
                                                    (>= (compare max-d to-str) 0))))))
                                    ts-codes))
            backfill?   (and (some? rows) (seq ts-codes) (not sufficient?))]
        (when backfill?
          (future (k-line-store/ensure-range-and-update-ma-for-codes-range! ts-codes from-str to-str)))
        (if (nil? rows)
          {:error "K 线缓存未初始化"}
          {:ok {:fields             default-daily-fields
                :by_ts_code          (into {}
                                           (map (fn [[code items]]
                                                  [code (mapv #(update % :trade_date str) items)])
                                                (group-by :ts_code rows)))
                :backfill_triggered  (boolean backfill?)}})))))

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
  "多股票版 MA：仅从本地缓存取日 K，现算 [date-from, date-to] 区间内 period 日 MA；数据不足时异步补数。
   返回 {:ok {:by_ts_code _ :backfill_triggered _}} 或 {:error _}。"
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
       (let [start-d   (when-let [d (parse-ymd from-str)]
                         (date->str (minus-days (next-weekday d) 400)))
             rows      (when start-d (k-line-store/get-daily-k-for-multiple-stocks-from-cache-only ts-codes start-d to-str))]
         (cond
           (nil? start-d) {:error "date-from 格式须为 YYYYMMDD。"}
           (nil? rows) {:error "K 线缓存未初始化"}
           :else
           (let [by-code     (group-by :ts_code rows)
                 results     (map (fn [code]
                                    (let [code-rows (sort-by #(str (:trade_date %)) (get by-code code []))
                                          payload   {:fields default-daily-fields
                                                     :items  (mapv #(update % :trade_date str) code-rows)}
                                          res       (ma-from-payload-by-range payload ma-days from-str to-str)]
                                      [code res]))
                                  ts-codes)
                 first-err   (first (keep (fn [[_code res]] (when (:error res) res)) results))
                 ;; 每只股票缓存的 [min-date, max-date] 是否覆盖 [start-d, to-str]，用 compare 做字符串日期比较
                 sufficient? (every? (fn [code]
                                       (let [code-rows (get by-code code [])
                                             str-dates (mapv #(str (get % :trade_date)) code-rows)]
                                         (and (seq str-dates)
                                              (let [sorted (sort str-dates)
                                                    min-d  (first sorted)
                                                    max-d  (last sorted)]
                                                (and (<= (compare min-d start-d) 0)
                                                     (>= (compare max-d to-str) 0))))))
                                     ts-codes)
                 backfill?   (and (seq ts-codes) (not sufficient?))]
             (when backfill?
               (future (k-line-store/ensure-range-and-update-ma-for-codes-range! ts-codes start-d to-str)))
             (if first-err
               first-err
               {:ok {:by_ts_code         (into {} (map (fn [[code res]] [code (:ok res)]) results))
                     :backfill_triggered (boolean backfill?)}})))))))
  ([stock-codes period date]
   (ma-for-multiple-stocks stock-codes period (str date) (str date))))

(defn- align-ma-items-by-date
  "将 short-items 与 long-items 按 trade_date 对齐，仅保留两段均有的日期并排序，返回 [short-aligned long-aligned]。"
  [short-items long-items]
  (let [short-by-date (into {} (map (fn [r] [(str (:trade_date r)) r]) short-items))
        long-by-date  (into {} (map (fn [r] [(str (:trade_date r)) r]) long-items))
        common-dates  (sort (filter #(and (get short-by-date %) (get long-by-date %))
                                   (into #{} (concat (map #(str (:trade_date %)) short-items)
                                                     (map #(str (:trade_date %)) long-items)))))
        short-aligned (mapv #(get short-by-date %) common-dates)
        long-aligned  (mapv #(get long-by-date %) common-dates)]
    [short-aligned long-aligned]))

(defn- crosses-from-ma-items
  "根据短期、长期 MA 的 items（按 trade_date 对齐后）检测金叉，返回 [{:date _ :ma5 _ :ma20 _} ...]（key 为 ma{short-period}, ma{long-period}）。"
  [short-items long-items short-period long-period]
  (let [skw    (keyword (str "ma" short-period))
        lkw    (keyword (str "ma" long-period))
        [s l]  (align-ma-items-by-date short-items long-items)
        n      (count s)]
    (if (< n 2)
      []
      (into []
            (comp
             (map (fn [i]
                    (let [prev-s (get (nth s (dec i)) skw)
                          prev-l (get (nth l (dec i)) lkw)
                          curr-s (get (nth s i) skw)
                          curr-l (get (nth l i) lkw)
                          d     (:trade_date (nth s i))]
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
            (doseq [code ts-codes]
              (future (k-line-store/update-ma-for-stock-date-range! code from-str to-str false)))
            {:ok {:by_ts_code (into {} by-code)}}))))))

(def ^:private max-per-page 500)

(defn- total-pages
  [total-items per-page]
  (if (or (zero? total-items) (zero? per-page))
    0
    (max 1 (int (Math/ceil (double (/ total-items per-page)))))))

(defn- slice-page
  [coll page per-page]
  (let [start (* (dec page) per-page)
        end   (min (+ start per-page) (count coll))]
    (subvec (vec coll) start end)))

(defn cross-signals-on-date
  "某交易日、在给定股票代码中，从 DB 统计金叉/死叉信号（仅读缓存，无数据则不计）。
   只返回股票代码 {:ts_code _}，不包含 MA；需详情可再调 golden-cross 等。分页默认第 1 页，每页最多 500 条。
   opts：:page（默认 1）、:per-page（默认 500，最大 500）。
   返回 {:summary _ :pagination _ :golden_cross _ :death_cross _}；summary 含 :backfill_triggered，为 true 表示已触发异步补数。"
  ([stock-codes trade-date]
   (cross-signals-on-date stock-codes trade-date nil))
  ([stock-codes trade-date opts]
   (let [default-opts {:page 1 :per-page max-per-page}
         opts         (merge default-opts opts)
         trade-date   (str trade-date)
         ts-codes     (vec (distinct (map #(normalize-ts-code (str %)) (filter (complement str/blank?) (seq stock-codes)))))
         page         (max 1 (int (:page opts)))
         per-page     (min max-per-page (max 1 (int (:per-page opts))))]
     (if (empty? ts-codes)
       {:summary     {:stock_count 0 :data_count 0 :backfill_triggered false :golden_cross_count 0 :death_cross_count 0}
        :pagination  {:total_pages 0 :per_page per-page :current_page 1 :total_golden_cross 0 :total_death_cross 0}
        :golden_cross [] :death_cross []}
       (let [data-count   (or (k-line-store/get-row-count-by-date-and-codes trade-date ts-codes) 0)
             expected     (count ts-codes)
             backfill?    (and (seq ts-codes) (< data-count expected))
             _            (when backfill?
                            (future (k-line-store/ensure-range-and-update-ma-for-codes! ts-codes trade-date)))
             golden       (or (k-line-store/get-rows-by-date-and-codes-with-cross-type trade-date ts-codes "金叉") [])
             death        (or (k-line-store/get-rows-by-date-and-codes-with-cross-type trade-date ts-codes "死叉") [])
             golden-list  (mapv (fn [r] {:ts_code (:ts_code r)}) golden)
             death-list   (mapv (fn [r] {:ts_code (:ts_code r)}) death)
             g-total      (count golden-list)
             d-total      (count death-list)
             total-pages-g (total-pages g-total per-page)
             total-pages-d (total-pages d-total per-page)
             total-pages  (max total-pages-g total-pages-d)
             page         (min page (max 1 total-pages))
             summary      {:stock_count        (count ts-codes)
                           :data_count         data-count
                           :backfill_triggered backfill?
                           :golden_cross_count g-total
                           :death_cross_count  d-total}
             pagination   {:total_pages       total-pages
                          :per_page          per-page
                          :current_page      page
                          :total_golden_cross g-total
                          :total_death_cross  d-total}]
         {:summary     summary
          :pagination   pagination
          :golden_cross (slice-page golden-list page per-page)
          :death_cross  (slice-page death-list page per-page)})))))
