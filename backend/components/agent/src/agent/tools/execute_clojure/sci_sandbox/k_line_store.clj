(ns agent.tools.execute-clojure.sci-sandbox.k-line-store
  "K 线：日线 SQLite 缓存（按 (ts_code, trade_date) 连续区间，非交易日占位符 -1）。对外仅提供 get-daily-k-for-multiple-stocks。"
  (:require [agent.tools.execute-clojure.sci-sandbox.stock-list-store :as stock-list-store]
            [agent.tools.execute-clojure.sci-sandbox.tushare :as tushare]
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

(defn- calendar-today-ymd
  "当前日历日（上海时区）yyyyMMdd。用于判断某日是否为「今天」，避免对今天写 -1（无法区分非交易日与数据未更新）。"
  []
  (.format (LocalDate/from (ZonedDateTime/now shanghai-zone)) basic-iso))

(defn- db-path []
  (or (System/getenv "K_LINE_DB_PATH")
      "backend/data/sqlite.db"))

(def ^:private datasource-atom (atom nil))
(def ^:private scheduler-atom (atom nil))

(declare normalize-ts-code)
(declare update-ma-for-stock-date-range!)

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
       ;; 迁移：添加 cross_type 列与索引（便于按金叉/死叉快速检索）
       (let [info (jdbc/execute! db ["PRAGMA table_info(k_line_daily)"]
                                {:builder-fn rs/as-unqualified-lower-maps})
             has-cross-type? (some #(= "cross_type" (get % :name)) info)]
         (when-not has-cross-type?
           (jdbc/execute! db ["ALTER TABLE k_line_daily ADD COLUMN cross_type TEXT"])
           (jdbc/execute! db ["CREATE INDEX IF NOT EXISTS idx_k_line_daily_cross_type ON k_line_daily(cross_type)"])
           (log/info "[k-line-store] ensure-schema! added column cross_type and index")))
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


(defn- cache-bounds [ds ts-code]
  (let [rows (jdbc/execute! ds
                ["SELECT MIN(trade_date) AS left_d, MAX(trade_date) AS right_d FROM k_line_daily WHERE ts_code = ?" ts-code]
                {:builder-fn rs/as-unqualified-lower-maps})]
    (when-let [r (first rows)]
      (let [left (get r :left_d)
            right (get r :right_d)]
        (when (and left right) {:left left :right right})))))

(defn- insert-rows!
  "按 (trade_date, payload) 逐行 INSERT OR REPLACE，非单事务；每次调用写一个区间的数据。"
  [ds ts-code date-rows]
  (doseq [[trade-date payload] date-rows]
    (jdbc/execute! ds
      ["INSERT OR REPLACE INTO k_line_daily (ts_code, trade_date, row_payload) VALUES (?, ?, ?)"
       ts-code trade-date (if (= payload placeholder) (str placeholder) (pr-str payload))])))

(defn update-daily-row-mas!
  "更新单行：将 row_payload 置为 payload-map 的 pr-str，cross_type 置为 cross-type-str。"
  [ts-code trade-date payload-map cross-type-str]
  (when-let [ds (get-ds)]
    (jdbc/execute! ds
      ["UPDATE k_line_daily SET row_payload = ?, cross_type = ? WHERE ts_code = ? AND trade_date = ?"
       (pr-str payload-map) (str cross-type-str) (normalize-ts-code ts-code) (str trade-date)])))

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
  "向 Tushare 请求 [start-ymd, end-ymd]，按日历日填充：有数据写行（map），无数据写占位符 -1。
   例外：若某日无数据且该日为日历今天，则不写入（避免把「数据未更新」误当成非交易日）。"
  [ds ts-code start-ymd end-ymd]
  (let [res (fetch-daily-from-tushare ts-code start-ymd end-ymd)]
    (if (:error res)
      {:error (:error res)}
      (let [payload    (:ok res)
            fields     (:fields payload)
            items      (:items payload)
            date-set   (calendar-days-set start-ymd end-ymd)
            idx        (when (seq fields) (field-index fields "trade_date"))
            by-date    (when (number? idx)
                         (into {} (map (fn [item]
                                         (let [d (str (nth item idx))]
                                           [d (row-from-fields fields item)]))
                                       items)))
            today-ymd  (calendar-today-ymd)
            date-rows  (->> (sort (seq date-set))
                            (map (fn [d] [d (get by-date d placeholder)]))
                            (remove (fn [[d payload]] (and (= payload placeholder) (= d today-ymd)))))]
        (if (map? by-date)
          (do (insert-rows! ds ts-code date-rows)
              {:ok true})
          ;; Tushare 返回缺少 trade_date 或无法解析时，不写入仍返回 :ok 会导致缓存为空、测试误判；改为返回 :error
          {:error "Tushare 日线返回缺少 trade_date 或无法解析"})))))

(defn- extend-to-cover!
  "拓展缓存以覆盖 [need-left, need-right]。按未覆盖区间顺序逐段请求 Tushare 并 insert-rows!；
   每段独立写入，整只股票写完需本函数返回后。MA 异步在「本只股票全部写完」后触发（do-daily-extend! 内）。"
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

(declare get-daily-k-for-multiple-stocks)

(defn- normalize-ts-code [s]
  (cond
    (str/blank? (str s)) (str s)
    (str/includes? (str s) ".") (str s)
    (str/starts-with? (str s) "6") (str s ".SH")
    :else (str s ".SZ")))

(defn- row-payload->map [ts-code trade-date row-payload source]
  (let [p (edn/read-string row-payload)]
    (if (= p placeholder)
      {:ts_code ts-code :trade_date trade-date :source source :placeholder true}
      (assoc (or p {}) :ts_code ts-code :trade_date trade-date :source source))))

(defn- get-daily-k-in-cache
  "Private. 一次 SQL 查询：获取 codes 在 [date-from, date-to]（含）内的缓存数据；含非交易日占位行。返回 [{:ts_code _ :trade_date _ :source :cache ...} ...]。"
  [codes date-from date-to]
  (if (or (empty? codes) (nil? (get-ds)))
    []
    (let [ds (get-ds)
          placeholders (str/join ", " (repeat (count codes) "?"))
          sql (str "SELECT ts_code, trade_date, row_payload FROM k_line_daily WHERE ts_code IN (" placeholders ") AND trade_date >= ? AND trade_date <= ? ORDER BY ts_code, trade_date")
          params (into [] (concat (vec codes) [date-from date-to]))
          rows (jdbc/execute! ds (into [sql] params) {:builder-fn rs/as-unqualified-lower-maps})]
      (map (fn [r]
             (row-payload->map (get r :ts_code) (get r :trade_date) (get r :row_payload) :cache))
           rows))))

(defn- read-daily-k-range-including-placeholders
  "Private. 读取单只股票 [date-from, date-to] 区间内的所有行（含占位符），按 trade_date 升序。"
  [ds ts-code date-from date-to]
  (let [rows (jdbc/execute! ds
                ["SELECT trade_date, row_payload FROM k_line_daily WHERE ts_code = ? AND trade_date >= ? AND trade_date <= ? ORDER BY trade_date ASC"
                 ts-code date-from date-to]
                {:builder-fn rs/as-unqualified-lower-maps})]
    (map (fn [r]
           (row-payload->map ts-code (get r :trade_date) (get r :row_payload) :cache))
         rows)))

(defn- get-daily-k-from-tushare
  "Private. 单只股票 [date-from, date-to] 从 Tushare 拉取并合并到 DB，返回该区间行（含占位符），:source :tushare。
   date-from > date-to 时返回空序列。写入后异步触发该区间的 MA/金叉死叉计算并写回。"
  [code date-from date-to]
  (if (pos? (compare date-from date-to))
    []
    (let [ds (get-ds)]
      (if (nil? ds)
        []
        (let [res (extend-cache-with-range! ds code date-from date-to)]
          (if (:error res)
            []
            (do (let [bounds-after   (cache-bounds ds (normalize-ts-code code))
                      extended-left? (and bounds-after (= (:left bounds-after) date-from))]
                  (future (update-ma-for-stock-date-range! code date-from date-to extended-left?)))
                (->> (read-daily-k-range-including-placeholders ds code date-from date-to)
                     (map #(assoc % :source :tushare))))))))))

(defn- ensure-daily-k
  "Private. daily-k-rows 为 get-daily-k-in-cache 对该 code 的返回值（已按 trade_date 升序，由 SQL ORDER BY 保证）。
   计算缺的左边、缺的右边，分别调 get-daily-k-from-tushare，再拼接 [缺的左边] + cache 行 + [缺的右边] 返回。"
  [code daily-k-rows date-from date-to]
  (let [date-from (str date-from)
        date-to (str date-to)
        date-strings (map #(str (:trade_date %)) daily-k-rows)
        min-date (when (seq date-strings) (first (sort date-strings)))
        max-date (when (seq date-strings) (last (sort date-strings)))
        left-end (cond
                   (empty? daily-k-rows) date-to
                   (neg? (compare (str date-from) (str min-date))) (date-str (.minus (parse-ymd (str min-date)) 1 ChronoUnit/DAYS))
                   :else nil)
        right-start (when (and max-date (neg? (compare (str max-date) date-to)))
                     (date-str (.plus (parse-ymd (str max-date)) 1 ChronoUnit/DAYS)))
        left-rows (if left-end
                    (get-daily-k-from-tushare code date-from left-end)
                    [])
        right-rows (if right-start
                     (get-daily-k-from-tushare code right-start date-to)
                     [])]
    (concat left-rows daily-k-rows right-rows)))

(defn get-daily-k-for-multiple-stocks
  "Public. 先按 codes + date-from + date-to 批量从缓存取数，再对每个 code 用 ensure-daily-k 补全左右缺段，合并后排除非交易日返回。"
  [codes date-from date-to]
  (when (seq codes)
    (when-let [_ds (get-ds)]
      (let [all-cache (get-daily-k-in-cache codes date-from date-to)
            by-code (group-by :ts_code all-cache)
            normalized (map #(normalize-ts-code %) codes)
            merged (mapcat (fn [code]
                             (ensure-daily-k code (get by-code code []) date-from date-to))
                           normalized)]
        (filter (complement :placeholder) merged)))))

(defn get-cache-bounds
  "返回某只股票当前缓存区间 {:left \"YYYYMMDD\" :right \"YYYYMMDD\"}，无缓存时 nil。供测试/调试。"
  [ts-code]
  (when-let [ds (get-ds)]
    (cache-bounds ds (normalize-ts-code ts-code))))

(defn get-all-ts-codes-in-cache
  "返回缓存中所有出现过的 ts_code（去重）。供 add-ma-to-cache 等脚本使用。"
  []
  (when-let [ds (get-ds)]
    (let [rows (jdbc/execute! ds ["SELECT DISTINCT ts_code FROM k_line_daily ORDER BY ts_code"]
                             {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv :ts_code rows))))

(defn get-cached-rows-for-code
  "仅从缓存读取某只股票在 [left, right] 内的有效 K 线（payload 非占位符），按 trade_date 升序。"
  [ts-code left right]
  (when-let [ds (get-ds)]
    (let [code (normalize-ts-code ts-code)
          rows (jdbc/execute! ds
                 ["SELECT trade_date, row_payload FROM k_line_daily WHERE ts_code = ? AND trade_date >= ? AND trade_date <= ? AND row_payload != ? ORDER BY trade_date ASC"
                  code (str left) (str right) (str placeholder)]
                 {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [r] (row-payload->map code (get r :trade_date) (get r :row_payload) :cache)) rows))))

(defn get-row-count-by-date-and-codes
  "某交易日、给定代码在 DB 中的行数（含占位符）。用于 cross-signals 的 data_count。"
  [trade-date ts-codes]
  (when-let [ds (get-ds)]
    (when (seq ts-codes)
      (let [codes   (vec (map normalize-ts-code (distinct ts-codes)))
            ph     (str/join ", " (repeat (count codes) "?"))
            sql    (str "SELECT COUNT(*) AS n FROM k_line_daily WHERE trade_date = ? AND ts_code IN (" ph ")")
            params (into [sql (str trade-date)] codes)
            r      (jdbc/execute-one! ds (vec params) {:builder-fn rs/as-unqualified-lower-maps})]
        (get r :n 0)))))

(defn get-rows-by-date-and-codes-with-cross-type
  "某交易日、给定代码中 cross_type = cross-type-str 且 row_payload 非占位符的行。用于金叉/死叉列表，过滤在 DB 完成。"
  [trade-date ts-codes cross-type-str]
  (when-let [ds (get-ds)]
    (when (and (seq ts-codes) (str cross-type-str))
      (let [codes   (vec (map normalize-ts-code (distinct ts-codes)))
            ph     (str/join ", " (repeat (count codes) "?"))
            sql    (str "SELECT ts_code, trade_date, row_payload, cross_type FROM k_line_daily WHERE trade_date = ? AND ts_code IN (" ph ") AND cross_type = ? AND row_payload != ?")
            params (into [sql (str trade-date)] (concat codes [(str cross-type-str) (str placeholder)]))
            rows   (jdbc/execute! ds (vec params) {:builder-fn rs/as-unqualified-lower-maps})]
        (mapv (fn [r]
                (let [rp (get r :row_payload)
                      p  (when rp (try (edn/read-string rp) (catch Exception _ nil)))]
                  {:ts_code    (get r :ts_code)
                   :trade_date (get r :trade_date)
                   :payload    p
                   :cross_type (get r :cross_type)}))
              rows)))))

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

(defn insert-daily-rows-for-test!
  "仅用于测试：向 k_line_daily 插入指定 ts_code 的若干行。
   date-rows: [[\"YYYYMMDD\" {:close 10.0 :open 9.8 ...}] ...]，payload 需含 :close（及可选 :open :high :low 等）。"
  [ts-code date-rows]
  (when-let [ds (get-ds)]
    (insert-rows! ds (normalize-ts-code ts-code) date-rows)))

;; ---------------------------------------------------------------------------
;; MA / 金叉死叉 区间更新（供异步触发）
;; ---------------------------------------------------------------------------

(defn- parse-close [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch NumberFormatException _ nil))
    :else nil))

(defn- simple-moving-average [closes days]
  (let [n (count closes)
        d (long days)]
    (mapv (fn [i]
            (if (< i (dec d))
              nil
              (let [seg (subvec closes (inc (- i d)) (inc i))
                    s (reduce + 0.0 seg)]
                (/ s d))))
          (range n))))

(defn- cross-type-at [ma-short ma-long i]
  (if (< i 1)
    "无"
    (let [prev-s (nth ma-short (dec i))
          prev-l (nth ma-long (dec i))
          curr-s (nth ma-short i)
          curr-l (nth ma-long i)]
      (if (or (nil? prev-s) (nil? prev-l) (nil? curr-s) (nil? curr-l))
        "无"
        (cond
          (and (<= prev-s prev-l) (> curr-s curr-l)) "金叉"
          (and (>= prev-s prev-l) (< curr-s curr-l)) "死叉"
          :else "无")))))

(defn- payload-for-db [row]
  (dissoc row :ts_code :trade_date :source))

(defn update-ma-for-stock-date-range!
  "对 ts-code 在 [date-from, date-to] 内的缓存行计算 MA5/10/20/30/60 与金叉/死叉并写回。
   extended-left? 为 true 时，date-to 扩展为 min(今日, date-from 起第 60 个交易日)，因补左边后最多需 59 个交易日才有 MA60。"
  [ts-code date-from date-to extended-left?]
  (when-let [bounds (get-cache-bounds ts-code)]
    (let [today (effective-today-ymd)
          left  (:left bounds)
          right (:right bounds)
          date-to-ma (if extended-left?
                       (let [rows (get-cached-rows-for-code ts-code left right)
                             td-dates (mapv :trade_date rows)
                             from-idx (first (keep-indexed (fn [i d] (when (<= (compare date-from d) 0) i)) td-dates))]
                         (if (and (number? from-idx) (<= (+ from-idx 60) (count td-dates)))
                           (min today (nth td-dates (+ from-idx 59)))
                           today))
                       date-to)
          date-to-ma (or date-to-ma date-to)
          rows (get-cached-rows-for-code ts-code left date-to-ma)]
      (when (seq rows)
        (let [rows-v    (vec rows)
              closes   (mapv (fn [r] (parse-close (:close r))) rows-v)
              ma5      (simple-moving-average closes 5)
              ma20     (simple-moving-average closes 20)
              ma10     (simple-moving-average closes 10)
              ma30     (simple-moving-average closes 30)
              ma60     (simple-moving-average closes 60)
              date-from (str date-from)
              date-to-ma (str date-to-ma)]
          (doseq [i (range (count rows-v))]
            (let [row (nth rows-v i)
                  d   (str (:trade_date row))]
              (when (and (<= (compare date-from d) 0) (<= (compare d date-to-ma) 0))
                (let [ct (cross-type-at ma5 ma20 i)
                      enriched (assoc row
                                     :ma5 (nth ma5 i)
                                     :ma10 (nth ma10 i)
                                     :ma20 (nth ma20 i)
                                     :ma30 (nth ma30 i)
                                     :ma60 (nth ma60 i)
                                     :cross_type ct)
                      payload (payload-for-db enriched)]
                  (update-daily-row-mas! ts-code d payload ct))))))))))

;; ---------------------------------------------------------------------------
;; Cron：每日拓展
;; ---------------------------------------------------------------------------

(def ^:private progress-log-interval 100)

(defn do-daily-extend!
  "使用 stock-list-store 全量代码，对每只股票右边延展到今天；无缓存则从一年前今天拉到今天。"
  []
  (let [ds (get-ds)
        codes (stock-list-store/get-all-stock-codes)]
    (when ds
      (if (empty? codes)
        (log/info "[k-line-store] do-daily-extend! no ts-codes from stock-list-store, skip")
        (let [total (count codes)
              today (effective-today-ymd)
              one-year-ago (date-str (.minus (parse-ymd today) 365 ChronoUnit/DAYS))]
          (log/info "[k-line-store] do-daily-extend! start, extending" total "stocks")
          (println "[k-line-store] do-daily-extend! start, extending" total "stocks (Ctrl+C 或 REPL 里「Interrupt」可中断)")
          (flush)
          (doseq [[i ts-code] (map-indexed vector codes)]
            (let [bounds     (cache-bounds ds ts-code)
                  need-left  (if bounds (:left bounds) one-year-ago)
                  need-right today
                  res        (extend-to-cover! ds ts-code need-left need-right)]
              (when (:error res)
                (log/warn "[k-line-store] do-daily-extend! failed for" ts-code (:error res)))
              (when-not (:error res)
                (future (update-ma-for-stock-date-range! ts-code need-left need-right (nil? bounds)))))
            (when (and (pos? progress-log-interval)
                       (zero? (mod (inc i) progress-log-interval)))
              (let [msg (str "[k-line-store] do-daily-extend! progress " (inc i) " / " total)]
                (log/info msg)
                (println msg)
                (flush))))
          (log/info "[k-line-store] do-daily-extend! done for" total "stocks")
          (println "[k-line-store] do-daily-extend! done for" total "stocks")
          (flush))))))

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
