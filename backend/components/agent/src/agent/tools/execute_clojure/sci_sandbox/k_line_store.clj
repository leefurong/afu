(ns agent.tools.execute-clojure.sci-sandbox.k-line-store
  "K 线数据统一入口：get-k 对外提供数据，内部负责日 k 缓存（Datomic 按日存储）与 Tushare 拉取。
   get-k 完全负责业务：有缓存且够用则返回缓存，缺的日期向 Tushare 要并写入后再返回。"
  (:require [agent.tools.execute-clojure.sci-sandbox.env :as env]
            [agent.tools.execute-clojure.sci-sandbox.stock-list-store :as stock-list-store]
            [agent.tools.execute-clojure.sci-sandbox.tushare :as tushare]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cronj.core :as cronj]
            [datomic.client.api :as d])
  (:import (java.time LocalDate Instant)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.util Date)))

;; ---------------------------------------------------------------------------
;; Schema：按日存储，一个 (ts_code, trade_date) 一条实体
;; ---------------------------------------------------------------------------

(def k-line-store-schema
  [{:db/ident       :k-line-cache-meta/id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "固定 :default，全量更新时刻"}
   {:db/ident       :k-line-cache-meta/updated-at-instant
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident       :k-line-daily-fields/id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "固定 :default，日 k fields 的 EDN"}
   {:db/ident       :k-line-daily-fields/edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :k-line-day/code+date
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "如 000001.SZ_20250101"}
   {:db/ident       :k-line-day/row-edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "该日一行数据的 EDN（向量）"}])

(defn ensure-schema!
  [conn]
  (log/info "[k-line-store] ensure-schema! installing per-day schema")
  (d/transact conn {:tx-data k-line-store-schema}))

;; ---------------------------------------------------------------------------
;; 日期与代码：与 stock 语义一致，便于 get-k 独立计算区间
;; ---------------------------------------------------------------------------

(def ^:private basic-iso (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- parse-ymd [s]
  (when (and (string? s) (= 8 (count s)))
    (try (LocalDate/parse s basic-iso) (catch Exception _ nil))))

(defn- date->str [^LocalDate d] (.format d basic-iso))

(defn- plus-days [^LocalDate d n] (.plus d n ChronoUnit/DAYS))

(defn- next-weekday [^LocalDate d]
  (case (.getValue (.getDayOfWeek d))
    6 (plus-days d 2)
    7 (plus-days d 1)
    d))

(defn- start-date-from-beg [beg-date]
  (when-let [d (parse-ymd beg-date)]
    (date->str (next-weekday d))))

(defn- end-date-from-beg-and-count [beg-date count dwmsy]
  (when-let [start-d (parse-ymd beg-date)]
    (let [start-d (next-weekday start-d)
          days    (case dwmsy
                    "日k" (* count 2)
                    "周k" (* count 7)
                    "月k" (* (max 1 count) 31)
                    31)]
      (date->str (plus-days start-d (max 1 days))))))

(defn- normalize-ts-code [s]
  (cond
    (str/blank? s) s
    (str/includes? (str s) ".") (str s)
    (str/starts-with? (str s) "6") (str s ".SH")
    :else (str s ".SZ")))

(defn- dwmsy->api [dwmsy]
  (case dwmsy
    "日k" [:daily nil]
    "周k" [:stk_weekly_monthly "week"]
    "月k" [:stk_weekly_monthly "month"]
    "季k" [:unsupported nil]
    "年k" [:unsupported nil]
    nil))

;; ---------------------------------------------------------------------------
;; 按日缓存：读区间、写单日
;; ---------------------------------------------------------------------------

(def ^:private conn-atom (atom nil))
(def ^:private meta-ref [:k-line-cache-meta/id :default])
(def ^:private fields-ref [:k-line-daily-fields/id :default])
(def ^:private interval-ms (* 24 60 60 1000))
(def ^:private calendar-days-for-60-trading 120)
(def ^:private throttle-ms 200)

(defn- today-ymd []
  (.format (LocalDate/now) basic-iso))

(defn- code+date [ts-code trade-date-str]
  (str (str ts-code) "_" (str trade-date-str)))

(defn- field-index [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

(defn- row-date-str [row date-idx]
  (str (nth row date-idx "")))

(defn- get-cached-range
  "查询 [start-d, end-d] 内已缓存的日 k，按日期降序。返回 {:fields _ :items _} 或 nil。"
  [conn ts-code start-d end-d]
  (when (and conn start-d end-d)
    (let [db (d/db conn)
          prefix (str (str ts-code) "_")
          lo (str prefix start-d)
          hi (str prefix end-d)
          eids (d/q '[:find ?e :in $ ?lo ?hi
                      :where [?e :k-line-day/code+date ?c]
                      [(>= (compare ?c ?lo) 0)]
                      [(<= (compare ?c ?hi) 0)]]
                    db lo hi)
          rows (mapv (fn [[eid]]
                       (edn/read-string (:k-line-day/row-edn (d/pull db [:k-line-day/row-edn] eid))))
                     eids)
          fields-edn (d/pull db [:k-line-daily-fields/edn] fields-ref)]
      (when (and (seq rows) (:k-line-daily-fields/edn fields-edn))
        (let [fields (edn/read-string (:k-line-daily-fields/edn fields-edn))
              date-idx (field-index fields "trade_date")]
          (when date-idx
            {:fields fields
             :items  (vec (sort #(compare (row-date-str %2 date-idx) (row-date-str %1 date-idx)) rows))}))))))

(defn- ensure-fields! [conn fields]
  (when (and conn (seq fields))
    (let [db (d/db conn)
          e (d/pull db [:k-line-daily-fields/edn] fields-ref)]
      (when-not (:k-line-daily-fields/edn e)
        (d/transact conn {:tx-data [{:k-line-daily-fields/id :default
                                     :k-line-daily-fields/edn (pr-str (vec fields))}]})))))

(defn- save-days!
  "按日写入：每条 item 一行，code+date = ts_code_trade_date。"
  [conn ts-code fields items]
  (when (and conn (seq items))
    (let [date-idx (field-index fields "trade_date")]
      (when date-idx
        (ensure-fields! conn fields)
        (doseq [row items]
          (let [d (row-date-str row date-idx)
                code+d (code+date ts-code d)]
            (d/transact conn {:tx-data [{:k-line-day/code+date code+d
                                         :k-line-day/row-edn (pr-str (vec row))}]})))))))

(defn- fetch-daily-from-tushare [ts-code start-d end-d]
  (tushare/request-tushare-api "daily" {:ts_code (str ts-code)
                                      :start_date start-d
                                      :end_date end-d}))

(defn- fetch-weekly-monthly-from-tushare [ts-code start-d end-d freq]
  (tushare/request-tushare-api "stk_weekly_monthly"
                            {:ts_code (str ts-code) :start_date start-d :end_date end-d :freq freq}))

;; ---------------------------------------------------------------------------
;; 对外 API：get-k
;; ---------------------------------------------------------------------------

(defn get-k
  "K 线统一入口。日 k：先读缓存 [start-d, end-d]，不够则向 Tushare 拉缺的区间并按日写入后再返回。
   周 k/月 k：直接请求 Tushare。返回 {:ok {:fields _ :items _}} 或 {:error _}，items 最多 count 条（从起始日起），日期降序。"
  ([stock-code dwmsy beg-date]
   (get-k stock-code dwmsy beg-date 20))
  ([stock-code dwmsy beg-date count]
   (let [count (if (or (nil? count) (neg? (long count))) 20 (long count))
         [api-name freq] (dwmsy->api dwmsy)]
     (cond
       (= api-name :unsupported)
       {:error "暂不支持季k/年k，请使用 日k、周k 或 月k。"}
       (not (parse-ymd beg-date))
       {:error "起始日期格式须为 YYYYMMDD，例如 20250101。"}
       (str/blank? (env/get-env "TUSHARE_API_TOKEN"))
       {:error "未配置 TUSHARE_API_TOKEN，请在环境中设置"}
       :else
       (let [ts-code (normalize-ts-code (str stock-code))
             start-d (start-date-from-beg beg-date)
             end-d   (end-date-from-beg-and-count beg-date count dwmsy)]
         (if (nil? start-d)
           {:error "起始日期格式须为 YYYYMMDD。"}
           (case api-name
             :daily
             (let [conn @conn-atom
                   cached (get-cached-range conn ts-code start-d end-d)
                   n     (count (:items cached))]
               (if (and cached (>= n count))
                 {:ok (update cached :items #(vec (take count %)))}
                 (let [res (fetch-daily-from-tushare ts-code start-d end-d)]
                   (if (:error res)
                     res
                     (let [payload (:ok res)
                           fields (:fields payload)
                           items  (or (:items payload) [])]
                       (when (and conn (seq items))
                         (save-days! conn ts-code fields items))
                       {:ok (update payload :items
                                    #(vec (take count (or % []))))})))))
             :stk_weekly_monthly
             (let [res (fetch-weekly-monthly-from-tushare ts-code start-d end-d freq)]
               (if (:error res)
                 res
                 (let [items (get-in res [:ok :items])
                       taken (take count (or items []))]
                   {:ok (-> res :ok (assoc :items (vec taken)))})))
             {:error "未知 K 线类型"})))))))

;; ---------------------------------------------------------------------------
;; Cron：全量/按日更新最近 60 交易日
;; ---------------------------------------------------------------------------

(defn- beg-date-60-trading-days-ago []
  (.format (.minus (LocalDate/now) calendar-days-for-60-trading ChronoUnit/DAYS) basic-iso))

(defn- fetch-last-60-for-stock [ts-code]
  (let [beg (beg-date-60-trading-days-ago)
        end (today-ymd)
        res (fetch-daily-from-tushare ts-code beg end)]
    (if (:error res)
      res
      (let [items (get-in res [:ok :items])
            taken (take 60 (or items []))]
        (if (empty? taken)
          {:error (str "无 K 线数据: " ts-code)}
          {:ok (-> res :ok (assoc :items (vec taken)))})))))

(defn- fetch-and-save-all! [conn]
  (let [codes (stock-list-store/get-all-stock-codes)]
    (if (empty? codes)
      (log/warn "[k-line-store] fetch-and-save-all! no stock codes, skip")
      (let [total (count codes)
            _ (log/info "[k-line-store] fetch-and-save-all! start, stocks=" total)]
        (doseq [i (range total)
                :let [ts-code (nth codes i)
                      res (fetch-last-60-for-stock ts-code)]]
          (when (pos? i) (Thread/sleep throttle-ms))
          (when (contains? res :ok)
            (save-days! conn ts-code
                        (get-in res [:ok :fields])
                        (get-in res [:ok :items]))
            (when (zero? (mod (inc i) 500))
              (log/info "[k-line-store] progress" (inc i) "/" total))))
        (d/transact conn {:tx-data [{:k-line-cache-meta/id :default
                                    :k-line-cache-meta/updated-at-instant (Date/from (Instant/now))}]})
        (log/info "[k-line-store] fetch-and-save-all! done")))))

(defn do-daily-update-if-needed [conn]
  (when conn
    (let [db (d/db conn)
          e (d/pull db [:k-line-cache-meta/updated-at-instant] meta-ref)
          last-ms (some-> (:k-line-cache-meta/updated-at-instant e) (.getTime))
          now-ms (.toEpochMilli (Instant/now))
          stale? (or (nil? last-ms) (>= (- now-ms last-ms) interval-ms))]
      (when stale?
        (log/info "[k-line-store] do-daily-update-if-needed: running fetch-and-save-all! ...")
        (fetch-and-save-all! conn)))))

(def ^:private cron-daily-midnight "0 0 * * * *")
(def ^:private cronj-tick-ms 60000)

(defn init!
  "注入 Datomic 连接；立即执行一次 do-daily-update-if-needed，并启动 cronj 每日 0 点更新。"
  [conn]
  (log/info "[k-line-store] init! conn=" (some? conn))
  (reset! conn-atom conn)
  (do-daily-update-if-needed conn)
  (let [cnj (cronj/cronj :interval cronj-tick-ms
                        :entries [{:id :k-line-daily-update
                                  :handler (fn [_ _] (when-let [c @conn-atom] (do-daily-update-if-needed c)))
                                  :schedule cron-daily-midnight}])]
    (cronj/start! cnj)))
