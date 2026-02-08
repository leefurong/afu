(ns agent.tools.execute-clojure.sci-sandbox.k-line-store
  "K 线：日线 SQLite 缓存（按 (ts_code, trade_date) 连续区间，非交易日占位符 -1）；周k/月k 直连 Tushare。"
  (:require [agent.tools.execute-clojure.sci-sandbox.tushare :as tushare]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cronj.core :as cronj])
  (:import (java.time LocalDate LocalTime ZonedDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

;; ---------------------------------------------------------------------------
;; 常量与配置
;; ---------------------------------------------------------------------------

(def ^:private placeholder -1)
(def ^:private basic-iso (DateTimeFormatter/ofPattern "yyyyMMdd"))

(def ^:private shanghai-zone (ZoneId/of "Asia/Shanghai"))
(def ^:private cutoff-time (LocalTime/of 16 0))

(defn effective-today-ymd
  "今日下午收盘且 Tushare 已有当日 K 线后才算「今天」；否则用昨天。
   约定：北京时间 16:00 前用昨天，16:00 及之后用今天。"
  []
  (let [now (ZonedDateTime/now shanghai-zone)
        today (LocalDate/from now)
        t (.toLocalTime now)]
    (if (.isBefore t cutoff-time)
      (.format (.minus today 1 ChronoUnit/DAYS) basic-iso)
      (.format today basic-iso))))

(defn- today-ymd []
  (.format (LocalDate/now) basic-iso))

(defn- db-path []
  (or (System/getenv "K_LINE_DB_PATH")
      "backend/data/sqlite.db"))

(def ^:private datasource-atom (atom nil))
(def ^:private scheduler-atom (atom nil))

(defn reset-for-test!
  "仅用于测试：清空 datasource 与 scheduler，便于使用 :memory: 重新初始化。"
  []
  (reset! scheduler-atom nil)
  (reset! datasource-atom nil))

;; ---------------------------------------------------------------------------
;; Schema：SQLite 建表与索引
;; ---------------------------------------------------------------------------

(defn ensure-schema!
  "创建日线表与索引。接受 conn 参数以兼容 afu.core 调用，实际使用内部 SQLite。
   可传第二参数 path-override，如 :memory: 供测试使用。"
  ([_conn] (ensure-schema! _conn nil))
  ([_conn path-override]
   (when-not @datasource-atom
     (let [path (or path-override (db-path))
           jdbc-url (if (= path ":memory:")
                      "jdbc:sqlite::memory:"
                      (str "jdbc:sqlite:" path))]
       (when (and (not= path ":memory:")
                  (not (str/blank? path)))
         (try
           (.mkdirs (.getParentFile (java.io.File. path)))
           (catch Exception _ nil)))
     (let [;; :memory: 存单连接（next.jdbc 传入 Connection 时不会关闭），保证建表与后续查询同一 DB
           db (if (= (or path-override (db-path)) ":memory:")
                (jdbc/get-connection (jdbc/get-datasource jdbc-url))
                (jdbc/get-datasource jdbc-url))]
       (reset! datasource-atom db)
       (jdbc/execute! db ["
         CREATE TABLE IF NOT EXISTS k_line_daily (
           ts_code     TEXT NOT NULL,
           trade_date  TEXT NOT NULL,
           row_payload TEXT NOT NULL,
           PRIMARY KEY (ts_code, trade_date)
         );
         CREATE INDEX IF NOT EXISTS idx_k_line_daily_ts_code_trade_date
         ON k_line_daily (ts_code, trade_date)
       "])
       (log/info "[k-line-store] ensure-schema! SQLite ready:" jdbc-url))))))

(defn- get-ds []
  @datasource-atom)

;; ---------------------------------------------------------------------------
;; 区间与智能拓展
;; ---------------------------------------------------------------------------

(defn- parse-ymd [s]
  (when (and (string? s) (= 8 (count s)))
    (try
      (LocalDate/parse s basic-iso)
      (catch Exception _ nil))))

(defn- date-str [^LocalDate d]
  (.format d basic-iso))

(defn- ymd-between [start-ymd end-ymd]
  (let [start (parse-ymd start-ymd)
        end   (parse-ymd end-ymd)]
    (when (and start end (not (.isBefore end start)))
      (loop [acc [] d start]
        (if (.isAfter d end)
          acc
          (recur (conj acc (date-str d)) (.plus d 1 ChronoUnit/DAYS)))))))

(defn uncovered-date-ranges
  "计算需要向 Tushare 请求的日期区间（当前缓存未覆盖的部分）。
   cache-left、cache-right 为当前缓存区间（含）；need-left、need-right 为需要覆盖的区间（含）。
   返回 [{:start \"YYYYMMDD\" :end \"YYYYMMDD\"} ...]，按时间顺序。"
  [cache-left cache-right need-left need-right]
  (cond
    (nil? cache-left)
    ;; 无缓存，整段都要请求
    [{:start need-left :end need-right}]

    ;; 需要向左拓展
    (and need-left (neg? (compare need-left cache-left)))
    (let [left-end (date-str (.minus (parse-ymd cache-left) 1 ChronoUnit/DAYS))
          end-ymd (or need-right left-end)
          left-seg {:start need-left :end (if (neg? (compare left-end end-ymd)) left-end end-ymd)}
          right-seg (when (and need-right (neg? (compare cache-right need-right)))
                      {:start (date-str (.plus (parse-ymd cache-right) 1 ChronoUnit/DAYS)) :end need-right})]
      (cond
        (and right-seg (<= (compare (:start right-seg) (:end right-seg)) 0))
        [left-seg right-seg]
        :else [left-seg]))

    ;; 只需向右拓展
    (neg? (compare cache-right need-right))
    [{:start (date-str (.plus (parse-ymd cache-right) 1 ChronoUnit/DAYS)) :end need-right}]

    :else []))

;; ---------------------------------------------------------------------------
;; 数据库读写
;; ---------------------------------------------------------------------------

(defn- row-placeholder? [row-payload]
  (try
    (= placeholder (edn/read-string row-payload))
    (catch Exception _ false)))

(defn- cache-bounds [ds ts-code]
  (let [rows (jdbc/execute! ds
                ["SELECT MIN(trade_date) AS left_d, MAX(trade_date) AS right_d FROM k_line_daily WHERE ts_code = ?" ts-code]
                {:builder-fn rs/as-unqualified-lower-maps})]
    (when-let [r (first rows)]
      (let [left (get r :left_d)
            right (get r :right_d)]
        (when (and left right) {:left left :right right})))))

(defn- insert-rows! [ds ts-code date-rows]
  (doseq [[trade-date payload] date-rows]
    (jdbc/execute! ds
      ["INSERT OR REPLACE INTO k_line_daily (ts_code, trade_date, row_payload) VALUES (?, ?, ?)"
       ts-code trade-date (if (= payload placeholder) (str placeholder) (pr-str payload))])))

(defn- rows-from-cache
  "从 start-ymd 起按日期升序取行，跳过占位符，最多取 n-max 条有数据的行。返回 [{:trade_date _ ...} ...] 按日期升序。"
  [ds ts-code start-ymd n-max]
  (let [all (jdbc/execute! ds
               ["SELECT trade_date, row_payload FROM k_line_daily WHERE ts_code = ? AND trade_date >= ? ORDER BY trade_date ASC"
                ts-code start-ymd]
               {:builder-fn rs/as-unqualified-lower-maps})
        with-parsed (map (fn [r]
                           (let [p (edn/read-string (get r :row_payload))]
                             (when (not= p placeholder)
                               (assoc (or p {}) :trade_date (get r :trade_date)))))
                         all)
        real-rows (filter some? with-parsed)]
    (take n-max real-rows)))

(defn- count-real-from
  "从 start-ymd 起按日期升序，统计非占位符的行数。"
  [ds ts-code start-ymd]
  (let [all (jdbc/execute! ds
               ["SELECT row_payload FROM k_line_daily WHERE ts_code = ? AND trade_date >= ? ORDER BY trade_date ASC"
                ts-code start-ymd]
               {:builder-fn rs/as-unqualified-lower-maps})]
    (count (filter (fn [r] (not (row-placeholder? (get r :row_payload)))) all))))

;; ---------------------------------------------------------------------------
;; Tushare 拉取与写入
;; ---------------------------------------------------------------------------

(defn- field-index [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

(defn- row-from-fields [fields item]
  (into {} (map (fn [k v] [(keyword (str k)) v]) fields item)))

(defn- fetch-daily-from-tushare [ts-code start-date end-date]
  (let [res (tushare/request-tushare-api "daily" {:ts_code ts-code :start_date start-date :end_date end-date})]
    (if (:error res)
      {:error (:error res)}
      (let [payload (:ok res)
            fields  (or (:fields payload) [])
            items   (or (:items payload) [])]
        {:ok {:fields fields :items items}}))))

(defn- calendar-days-set [start-ymd end-ymd]
  (set (ymd-between start-ymd end-ymd)))

(defn- extend-cache-with-range!
  "向 Tushare 请求 [start-ymd, end-ymd]，按日历日填充：有数据写行（map），无数据写占位符。"
  [ds ts-code start-ymd end-ymd]
  (let [res (fetch-daily-from-tushare ts-code start-ymd end-ymd)]
    (if (:error res)
      {:error (:error res)}
      (let [payload  (:ok res)
            fields   (:fields payload)
            items    (:items payload)
            date-set (calendar-days-set start-ymd end-ymd)
            idx      (when (seq fields) (field-index fields "trade_date"))
            by-date  (when (number? idx)
                       (into {} (map (fn [item]
                                       (let [d (str (nth item idx))]
                                         [d (row-from-fields fields item)]))
                                     items)))]
        (if (map? by-date)
          (do (let [date-rows (map (fn [d] [d (get by-date d placeholder)]) (sort (seq date-set)))]
                (insert-rows! ds ts-code date-rows))
              {:ok true})
          ;; Tushare 返回缺少 trade_date 或无法解析时，不写入仍返回 :ok 会导致缓存为空、测试误判；改为返回 :error
          {:error "Tushare 日线返回缺少 trade_date 或无法解析"})))))

(defn- extend-to-cover!
  "拓展缓存以覆盖 [need-left, need-right]。只请求未覆盖的区间并写入。"
  [ds ts-code need-left need-right]
  (let [bounds (cache-bounds ds ts-code)
        ranges (uncovered-date-ranges (:left bounds) (:right bounds) need-left need-right)]
    (loop [ranges ranges]
      (if (empty? ranges)
        {:ok true}
        (let [r (first ranges)
              res (extend-cache-with-range! ds ts-code (:start r) (:end r))]
          (if (:error res)
            res
            (recur (rest ranges))))))))

;; ---------------------------------------------------------------------------
;; get-daily-k：命中判断与返回
;; ---------------------------------------------------------------------------

(def ^:private default-daily-fields
  ["ts_code" "trade_date" "open" "high" "low" "close" "pre_close" "change" "pct_chg" "vol" "amount"])

(defn get-daily-k
  "获取日 K：stock-code, beg-date(YYYYMMDD), req-count。
   返回 {:ok {:fields _ :items _ :source :cache|:tushare}} 或 {:error _}。
   非交易日在 items 中对应行为 -1 占位（设计上可返回占位行；此处按「只返回有数据的行」实现，与 design 中「跳过占位符」一致）。"
  [stock-code beg-date req-count]
  (let [ts-code (if (str/includes? (str stock-code) ".")
                  (str stock-code)
                  (if (str/starts-with? (str stock-code) "6")
                    (str stock-code ".SH")
                    (str stock-code ".SZ")))
        n-max (max 1 (long (or req-count 20)))
        start-d (str beg-date)
        ds (get-ds)]
    (if (nil? ds)
      (do (log/warn "[k-line-store] get-daily-k: DB not initialized")
          {:error "K 线缓存未初始化"})
      (let [bounds (cache-bounds ds ts-code)
            need-right (effective-today-ymd)]
        (cond
          ;; 无缓存或 start-d 在区间外 -> 拓展到 [min(cache_left, start-d), today]
          (or (nil? bounds)
              (neg? (compare start-d (:left bounds)))
              (pos? (compare start-d (:right bounds))))
          (let [need-left (if bounds
                            (if (neg? (compare start-d (:left bounds))) start-d (:left bounds))
                            start-d)
                ext (extend-to-cover! ds ts-code need-left need-right)]
            (if (:error ext)
              ext
              (let [rows (rows-from-cache ds ts-code start-d n-max)]
                {:ok {:fields default-daily-fields
                      :items (mapv #(update % :trade_date str) rows)
                      :source :tushare}})))

          ;; start-d 在区间内：从 start-d 起扫，跳过占位，够 n-max 则命中
          :else
          (let [cnt (count-real-from ds ts-code start-d)]
            (if (>= cnt n-max)
              (let [rows (rows-from-cache ds ts-code start-d n-max)]
                {:ok {:fields default-daily-fields
                      :items (mapv #(update % :trade_date str) rows)
                      :source :cache}})
              ;; 条数不足，向右拓展到今天再取
              (let [ext (extend-to-cover! ds ts-code (:left bounds) need-right)]
                (if (:error ext)
                  ext
                  (let [rows (rows-from-cache ds ts-code start-d n-max)]
                    {:ok {:fields default-daily-fields
                          :items (mapv #(update % :trade_date str) rows)
                          :source :tushare}}))))))))))

;; ---------------------------------------------------------------------------
;; get-k：供 stock.clj 调用，日k 走缓存，周k/月k 直连 Tushare
;; ---------------------------------------------------------------------------

(defn- normalize-ts-code [s]
  (cond
    (str/blank? (str s)) (str s)
    (str/includes? (str s) ".") (str s)
    (str/starts-with? (str s) "6") (str s ".SH")
    :else (str s ".SZ")))

(defn get-cache-bounds
  "返回某只股票当前缓存区间 {:left \"YYYYMMDD\" :right \"YYYYMMDD\"}，无缓存时 nil。供测试/调试。"
  [ts-code]
  (when-let [ds (get-ds)]
    (cache-bounds ds (normalize-ts-code ts-code))))

(defn count-calendar-days-in-range
  "返回 [start-ymd, end-ymd] 闭区间内的日历天数。供测试/调试。"
  [start-ymd end-ymd]
  (count (or (ymd-between start-ymd end-ymd) [])))

(defn get-cache-row-count
  "返回某只股票在缓存中的行数（含占位符）。供测试/调试，用于校验 left~right 间每日一条。"
  [ts-code]
  (when-let [ds (get-ds)]
    (let [r (jdbc/execute-one! ds
                ["SELECT COUNT(*) AS n FROM k_line_daily WHERE ts_code = ?" (normalize-ts-code ts-code)]
                {:builder-fn rs/as-unqualified-lower-maps})]
      (get r :n 0))))

(defn- fetch-week-month-k [stock-code freq beg-date count]
  (let [ts-code (normalize-ts-code stock-code)
        res (tushare/request-tushare-api "stk_weekly_monthly" {:ts_code ts-code :freq freq})]
    (if (:error res)
      {:error (:error res)}
      (let [payload (:ok res)
            fields  (or (:fields payload) [])
            items   (or (:items payload) [])
            idx     (field-index fields "trade_date")]
        (if (nil? idx)
          {:ok {:fields fields :items (take (long (or count 20)) items) :source :tushare}}
          (let [beg-str (str beg-date)
                filtered (filter #(>= (compare (str (nth % idx)) beg-str) 0) items)
                sorted   (reverse (sort-by #(nth % idx) filtered))
                taken    (take (long (or count 20)) sorted)
                rows     (mapv #(assoc (row-from-fields fields %) :trade_date (str (nth % idx))) taken)]
            {:ok {:fields fields :items rows :source :tushare}}))))))

(defn get-k
  "获取 K 线。日k 走缓存 get-daily-k；周k/月k 直连 Tushare；季k/年k 返回 :error。"
  [stock-code dwmsy beg-date count]
  (case dwmsy
    "日k" (get-daily-k stock-code beg-date count)
    "周k" (fetch-week-month-k stock-code "week" beg-date count)
    "月k" (fetch-week-month-k stock-code "month" beg-date count)
    "季k" {:error "暂不支持季k/年k，请使用 日k、周k 或 月k。"}
    "年k" {:error "暂不支持季k/年k，请使用 日k、周k 或 月k。"}
    {:error "不支持的 K 线类型，请使用 日k、周k 或 月k。"}))

;; ---------------------------------------------------------------------------
;; Cron：每日拓展
;; ---------------------------------------------------------------------------

(defn do-daily-extend!
  "对所有需要维护的股票：右边延展到今天；若无任何缓存则从一年前今天拉到今天。"
  []
  (let [ds (get-ds)]
    (when ds
      (let [today (effective-today-ymd)
            one-year-ago (date-str (.minus (parse-ymd today) 365 ChronoUnit/DAYS))
            ts-codes (jdbc/execute! ds ["SELECT DISTINCT ts_code FROM k_line_daily"] {:builder-fn rs/as-unqualified-lower-maps})
            codes (mapv #(get % :ts_code) ts-codes)]
        (if (empty? codes)
          (log/info "[k-line-store] do-daily-extend! no stocks in cache, skip")
          (do
            (doseq [ts-code codes]
              (let [bounds (cache-bounds ds ts-code)
                    need-left (if bounds (:left bounds) one-year-ago)
                    need-right today]
                (when-let [res (extend-to-cover! ds ts-code need-left need-right)]
                  (when (:error res)
                    (log/warn "[k-line-store] do-daily-extend! failed for" ts-code (:error res))))))
            (log/info "[k-line-store] do-daily-extend! done for" (count codes) "stocks")))))))

(def ^:private cron-daily-midnight "0 0 * * * *")
(def ^:private cronj-tick-ms 60000)

(defn init!
  "初始化 SQLite 并启动每日 0 点拓展任务。接受 conn 以兼容 afu.core，实际使用内部 SQLite。"
  [_conn]
  (ensure-schema! nil)
  (let [cnj (cronj/cronj :interval cronj-tick-ms
                         :entries [{:id :k-line-daily-extend
                                    :handler (fn [_ _] (do-daily-extend!))
                                    :schedule cron-daily-midnight}])]
    (cronj/start! cnj)
    (reset! scheduler-atom cnj))
  (log/info "[k-line-store] init! cron started"))
