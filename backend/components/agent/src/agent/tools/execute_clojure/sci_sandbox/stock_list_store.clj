(ns agent.tools.execute-clojure.sci-sandbox.stock-list-store
  "A 股全量股票代码：从 Tushare 拉取并存入 Datomic。init! 时执行一次更新并启动 cronj 每日 0 点定时更新。"
  (:require [agent.tools.execute-clojure.sci-sandbox.stock :as stock]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [cronj.core :as cronj]
            [datomic.client.api :as d])
  (:import (java.time LocalDate Instant)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

;; ---------------------------------------------------------------------------
;; Schema（归属本模块，由 ensure-schema! 安装）
;; ---------------------------------------------------------------------------

(def stock-list-schema
  "A 股全量股票代码列表，按日更新；代码序列按块存入 stock-list-chunk，避免单值超 Datomic 限制。"
  [{:db/ident       :stock-list/id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "固定 :default 表示唯一一条记录"}
   {:db/ident       :stock-list/updated-at
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "最近更新日期 YYYYMMDD"}
   {:db/ident       :stock-list/updated-at-instant
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "最近更新时刻，用于判断是否超过 24 小时需重新拉取"}
   {:db/ident       :stock-list-chunk/list-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "所属列表 id，:default"}
   {:db/ident       :stock-list-chunk/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "块序号，从 0 起"}
   {:db/ident       :stock-list-chunk/edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "该块内代码子序列的 EDN 字符串，单块控制在 4KB 内"}])

(defn ensure-schema!
  "安装 stock-list 相关 schema，启动时调用一次（在 init! 之前）。"
  [conn]
  (log/info "[stock-list-store] ensure-schema! installing stock-list schema")
  (d/transact conn {:tx-data stock-list-schema}))

;; ---------------------------------------------------------------------------
;; 存储与拉取
;; ---------------------------------------------------------------------------

(def ^:private lookup-ref [:stock-list/id :default])
(def ^:private basic-iso (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def ^:private interval-ms (* 24 60 60 1000))
(def ^:private chunk-size 250)

(def ^:private conn-atom (atom nil))
(def ^:private scheduler-atom (atom nil))

(defn- today-ymd []
  (.format (LocalDate/now) basic-iso))

(defn- chunk-entity-ids [db list-id]
  (d/q '[:find ?e :in $ ?lid :where [?e :stock-list-chunk/list-id ?lid]] db list-id))

(defn- load-codes-from-db [conn]
  (when conn
    (let [db (d/db conn)
          chunks (d/q '[:find ?i ?edn :in $ ?lid :where [?e :stock-list-chunk/list-id ?lid]
                        [?e :stock-list-chunk/index ?i] [?e :stock-list-chunk/edn ?edn]]
                     db :default)]
      (if (seq chunks)
        (let [sorted (sort-by first chunks)
              parts (mapv (fn [[_i edn]] (edn/read-string edn)) sorted)
              codes (into [] (mapcat identity parts))]
          (log/info "[stock-list-store] load-codes-from-db: chunks=" (count chunks) " codes=" (count codes))
          codes)
        (do (log/info "[stock-list-store] load-codes-from-db: no chunks in DB")
            nil)))))

(defn- save! [conn codes updated-at ^Instant instant]
  (when (and conn (seq codes))
    (let [db (d/db conn)
          old-eids (mapv first (chunk-entity-ids db :default))
          retract-tx (mapv (fn [eid] [:db/retractEntity eid]) old-eids)
          chunks (partition chunk-size (vec codes))
          chunk-tx (mapcat (fn [i part]
                             [{:stock-list-chunk/list-id :default
                               :stock-list-chunk/index i
                               :stock-list-chunk/edn (pr-str (vec part))}])
                          (range) chunks)
          root-tx [{:stock-list/id :default
                    :stock-list/updated-at updated-at
                    :stock-list/updated-at-instant (Date/from instant)}]
          tx-data (into (into retract-tx root-tx) chunk-tx)]
      (d/transact conn {:tx-data tx-data}))
    (when (and conn (empty? codes))
      (log/warn "[stock-list-store] save! skipped: codes empty, not recording success time"))))

(defn- field-index [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

(defn- fetch-from-tushare []
  (let [res (stock/request-tushare-api "stock_basic" {:list_status "L"})]
    (if (:error res)
      (do (log/warn "[stock-list-store] fetch-from-tushare failed:" (:error res))
          {:error (:error res)})
      (let [payload (:ok res)
            fields  (:fields payload)
            items   (or (:items payload) [])
            idx     (field-index fields "ts_code")]
        (if (nil? idx)
          (do (log/warn "[stock-list-store] fetch-from-tushare: response missing ts_code field")
              {:error "Tushare stock_basic 返回缺少 ts_code 字段"})
          (let [codes (mapv #(str (nth % idx)) items)]
            (log/info "[stock-list-store] fetch-from-tushare ok: count=" (count codes))
            {:ok codes}))))))

(defn do-daily-update-if-needed
  "若上次更新距现在超过 24 小时（或从未更新），则从 Tushare 拉取并写入库；否则不执行。"
  [conn]
  (log/info "[stock-list-store] do-daily-update-if-needed called, conn=" (some? conn))
  (when conn
    (let [db (d/db conn)
          e (d/pull db [:stock-list/updated-at-instant] lookup-ref)
          last-instant (:stock-list/updated-at-instant e)
          now (Instant/now)
          now-ms (.toEpochMilli now)
          last-ms (when last-instant (.getTime ^Date last-instant))
          chunk-count (count (d/q '[:find ?e :in $ ?lid :where [?e :stock-list-chunk/list-id ?lid]] db :default))
          ;; 无 chunk 也视为过期（避免曾写入 root 但 codes 为空导致一直不重拉）
          stale? (or (nil? last-ms) (>= (- now-ms last-ms) interval-ms) (zero? chunk-count))]
      (log/info "[stock-list-store] do-daily-update-if-needed: stale?=" stale? " last-ms=" last-ms " chunk-count=" chunk-count)
      (when stale?
        (log/info "[stock-list-store] do-daily-update-if-needed: stale? true, fetching from Tushare ...")
        (when-let [res (fetch-from-tushare)]
          (when (contains? res :ok)
            (let [codes (:ok res)
                  today (today-ymd)]
              (log/info "[stock-list-store] do-daily-update-if-needed: saving" (count codes) "codes to DB")
              (save! conn codes today now)))))
      (when (not stale?)
        (log/debug "[stock-list-store] do-daily-update-if-needed: not stale, skip")))))

(def ^:private cron-daily-midnight "0 0 * * * *")
(def ^:private cronj-tick-ms 60000)

(defn- daily-update-handler [_tid _opts]
  (when-let [conn @conn-atom]
    (do-daily-update-if-needed conn)))

(defn init!
  "注入 Datomic 连接；立即执行一次 do-daily-update-if-needed，并启动 cronj：每日 0 点执行更新。未调用时 get-all-stock-codes 返回空序列。"
  [conn]
  (log/info "[stock-list-store] init! conn=" (some? conn))
  (reset! conn-atom conn)
  (do-daily-update-if-needed conn)
  (let [cnj (cronj/cronj :interval cronj-tick-ms
                        :entries [{:id :stock-list-daily-update
                                  :handler daily-update-handler
                                  :schedule cron-daily-midnight}])]
    (cronj/start! cnj)
    (reset! scheduler-atom cnj)))

(defn get-all-stock-codes
  "返回 A 股全量股票代码序列（字符串如 \"000001.SZ\"）。仅从库中读取，不触发拉取；未 init! 或无数据时返回 []。"
  []
  (let [conn @conn-atom
        result (or (when conn (load-codes-from-db conn)) [])]
    (log/info "[stock-list-store] get-all-stock-codes: conn=" (some? conn) "count=" (count result))
    (when (nil? conn)
      (log/warn "[stock-list-store] get-all-stock-codes: conn is nil (init! not called?), returning []"))
    result))
