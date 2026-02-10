(ns conversation
  "会话历史：每条消息为独立 record，通过 prev-id/next-ids 组成链表（支持 fork）。
   消息正文存 resource-store，Datomic 只存结构 + :message/content-resource-id。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [resource-store :as res]
            [sci-context-serde.store.datomic :as sci-store]))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def conversation-schema
  [{:db/ident       :conversation/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "会话唯一 id"}
   {:db/ident       :conversation/head
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "主分支当前头节点 message id；fork 后主分支不变，新分支由调用方持 head"}])

(def message-schema
  [{:db/ident       :message/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "消息 record 唯一 id"}
   {:db/ident       :message/conversation-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "所属会话 id，便于 delete-conversation! 按会话删"}
   {:db/ident       :message/prev-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "前驱消息 id；nil 表示分支首条（由 create 时或 fork 时写入）"}
   {:db/ident       :message/next-ids
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/many
    :db/doc         "后继消息 id 集合；多条即 fork 多分支"}
   {:db/ident       :message/selected-next-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "当前选中的后继（多后继时有效）；左右切换分支时只改此字段即可"}
   {:db/ident       :message/content-resource-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "消息正文在 resource-store 中的 id，按此 id 取内容"}
   {:db/ident       :message/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "创建时间（也可用 :db/txInstant 做时间序）"}
   {:db/ident       :message/sci-context-snapshot
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "该条为 execute_clojure 的 tool 结果时，存 SCI context 快照的 root（EDN 字符串），blob 在全局 blob 表"}])

(defn ensure-schema!
  "应用会话与消息 schema（含 sci-context-serde blob 表），启动时调用一次。"
  [conn]
  (d/transact conn {:tx-data (into conversation-schema (into message-schema sci-store/blob-schema))}))

;; ---------------------------------------------------------------------------
;; 内部：按 message id 拉取 entity；正文从 resource-store 按 content-resource-id 取
;; ---------------------------------------------------------------------------

(defn- pull-message
  [db message-id]
  (when message-id
    (d/pull db [:message/id :message/conversation-id :message/prev-id :message/next-ids :message/selected-next-id :message/content-resource-id :message/created-at :message/sci-context-snapshot]
            [:message/id message-id])))

(defn- resolve-content
  "从 resource-store 按 content-resource-id 取正文，再 edn 解析为 data map。"
  [resource-store content-resource-id]
  (when content-resource-id
    (when-let [s (res/get* resource-store content-resource-id)]
      (try (edn/read-string s) (catch Exception _ {})))))

(defn- retract-entity-tx
  [db lookup-ref]
  (let [[attr id] lookup-ref]
    (mapv (fn [[a v]] [:db/retract lookup-ref a v])
          (d/q '[:find ?a ?v :in $ ?attr ?id :where [?e ?attr ?id] [?e ?a ?v]] db attr id))))

;; ---------------------------------------------------------------------------
;; 会话 CRUD
;; ---------------------------------------------------------------------------

(defn create!
  "创建新会话，返回其 id（uuid）。此时尚无消息，不写入 head（Datomic 不可 assert nil）。"
  [conn]
  (let [id (random-uuid)]
    (d/transact conn {:tx-data [{:conversation/id id}]})
    id))

(defn get-head
  "返回会话主分支头节点 message id（conversation 上缓存的 head），无则 nil。"
  [conn conversation-id]
  (let [e (d/pull (d/db conn) [:conversation/head] [:conversation/id conversation-id])]
    (:conversation/head e)))

(defn compute-head-from
  [conn message-id]
  (when message-id
    (let [db (d/db conn)]
      (loop [mid message-id]
        (when mid
          (let [m (pull-message db mid)
                next-ids (vec (or (:message/next-ids m) []))]
            (cond
              (nil? m) mid
              (empty? next-ids) mid
              (= 1 (count next-ids)) (recur (first next-ids))
              :else (let [sel (or (:message/selected-next-id m) (first next-ids))]
                      (when (some #{sel} next-ids)
                        (recur sel))))))))))

(defn- find-fork-point-and-siblings
  [db message-id]
  (loop [mid message-id]
    (when mid
      (let [pid (ffirst (d/q '[:find ?prev :in $ ?id :where [?e :message/id ?id] [?e :message/prev-id ?prev]] db mid))]
        (when pid
          (let [next-ids (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db pid))]
            (if (> (count next-ids) 1)
              (let [id->at (into {} (map (fn [id] [id (get (pull-message db id) :message/created-at)]) next-ids))
                    ordered (vec (sort-by (fn [id] [(or (get id->at id) (java.util.Date. 0)) (str id)]) next-ids))
                    p (pull-message db pid)
                    current (or (:message/selected-next-id p) (first ordered))]
                (when (some #{current} ordered)
                  {:fork-id pid :ordered-next-ids ordered :current-selected current}))
              (recur pid))))))))

(defn change-next!
  [conn delta message-id]
  (when (and message-id (#{-1 1} delta))
    (let [db (d/db conn)
          info (find-fork-point-and-siblings db message-id)]
      (when info
        (let [ordered (:ordered-next-ids info)
              current (:current-selected info)
              idx (first (keep-indexed (fn [i e] (when (= e current) i)) ordered))
              new-idx (when (integer? idx) (+ idx delta))]
          (when (and (integer? new-idx) (>= new-idx 0) (< new-idx (count ordered)))
            (let [new-selected (nth ordered new-idx)]
              (d/transact conn {:tx-data [{:message/id (:fork-id info) :message/selected-next-id new-selected}]})
              new-selected)))))))

(defn left! [conn message-id]
  (change-next! conn -1 message-id))

(defn right! [conn message-id]
  (change-next! conn 1 message-id))

(defn- can-left-right-for-message
  [db mid]
  (let [m (pull-message db mid)
        next-ids (vec (or (:message/next-ids m) []))]
    (if (<= (count next-ids) 1)
      {:can-left? false :can-right? false}
      (let [id->at (into {} (map (fn [id] [id (get (pull-message db id) :message/created-at)]) next-ids))
            ordered (vec (sort-by (fn [id] [(or (get id->at id) (java.util.Date. 0)) (str id)]) next-ids))
            cur (or (:message/selected-next-id m) (first ordered))
            idx (first (keep-indexed (fn [i e] (when (= e cur) i)) ordered))]
        (if (integer? idx)
          {:can-left? (> idx 0) :can-right? (< idx (dec (count ordered)))}
          {:can-left? false :can-right? false})))))

(defn get-messages
  "从指定头节点沿 prev-id 向前遍历，得到该分支消息列表。需传入 resource-store 以取正文。"
  ([conn conversation-id resource-store]
   (get-messages conn conversation-id (get-head conn conversation-id) resource-store))
  ([conn conversation-id head-message-id resource-store]
   (if (or (nil? conversation-id) (nil? head-message-id) (nil? resource-store))
     []
     (let [db (d/db conn)
           raw (loop [acc [], mid head-message-id]
                 (if-let [m (pull-message db mid)]
                   (let [data (or (resolve-content resource-store (:message/content-resource-id m)) {})
                         sci (get m :message/sci-context-snapshot)]
                     (recur (conj acc {:id mid :data data :sci-context-snapshot sci}) (:message/prev-id m)))
                   (reverse acc)))]
       (mapv (fn [{:keys [id data sci-context-snapshot]}]
               (let [{:keys [can-left? can-right?]} (can-left-right-for-message db id)]
                 (assoc data :id id :can-left? can-left? :can-right? can-right? :sci-context-snapshot sci-context-snapshot)))
             raw)))))

(defn append-messages!
  "在 prev-message-id 之后追加一串消息；正文写入 resource-store，Datomic 只存 content-resource-id。"
  ([conn conversation-id new-messages resource-store]
   (append-messages! conn conversation-id new-messages nil true resource-store))
  ([conn conversation-id new-messages prev-message-id resource-store]
   (append-messages! conn conversation-id new-messages prev-message-id true resource-store))
  ([conn conversation-id new-messages prev-message-id update-main-head? resource-store]
   (when (and (seq new-messages) resource-store)
     (let [db (d/db conn)
           main-head (get-head conn conversation-id)
           prev-id (or prev-message-id main-head)
           update-head? (and update-main-head?
                             (or (nil? prev-id)
                                 (and main-head (= prev-id main-head))))
           new-ids (mapv (fn [_] (random-uuid)) new-messages)
           prev-next-ids (if prev-id (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db prev-id)) [])
           now (java.util.Date.)
           tx (into []
                   (concat
                    (map-indexed
                     (fn [i msg]
                       (let [id (nth new-ids i)
                             prev (if (zero? i) prev-id (nth new-ids (dec i)))
                             next-ids (if (< (inc i) (count new-ids))
                                        [(nth new-ids (inc i))]
                                        [])
                             content-id (res/put! resource-store (pr-str msg))
                             m (cond-> {:message/id id
                                        :message/conversation-id conversation-id
                                        :message/next-ids next-ids
                                        :message/content-resource-id content-id
                                        :message/created-at now}
                                 (some? prev) (assoc :message/prev-id prev))]
                         m))
                     new-messages)
                    (when prev-id
                      [{:message/id prev-id
                        :message/next-ids (into (vec (remove #{(first new-ids)} prev-next-ids))
                                                [(first new-ids)])
                        :message/selected-next-id (first new-ids)}])
                    (when update-head?
                      [{:conversation/id conversation-id
                        :conversation/head (last new-ids)}])))]
       (d/transact conn {:tx-data tx})
       new-ids))))

(defn delete-message!
  "删除一条消息 record 并维护链表；同时从 resource-store 删除对应正文。"
  [conn message-id resource-store]
  (when message-id
    (let [db (d/db conn)
          msg (pull-message db message-id)
          content-id (:message/content-resource-id msg)
          pid (ffirst (d/q '[:find ?prev :in $ ?id :where [?e :message/id ?id] [?e :message/prev-id ?prev]] db message-id))
          nids (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db message-id))
          cid (ffirst (d/q '[:find ?cid :in $ ?id :where [?e :message/id ?id] [?e :message/conversation-id ?cid]] db message-id))]
      (when (some? cid)
        (when (and resource-store content-id)
          (res/delete! resource-store content-id))
        (let [conv (d/pull db [:db/id :conversation/head] [:conversation/id cid])
              head-was-me? (= message-id (:conversation/head conv))
              p-next (when pid (vec (remove #{message-id} (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db pid)))))
              new-next (when pid (into (or p-next []) (or nids [])))
              tx (into []
                   (concat
                    (keep (fn [nid]
                            (when (some? nid)
                              {:message/id nid :message/prev-id pid}))
                          (or nids []))
                    (when pid
                      [{:message/id pid :message/next-ids new-next}])
                    (when head-was-me?
                      (if (some? pid)
                        [{:conversation/id cid :conversation/head pid}]
                        [[:db/retract [:conversation/id cid] :conversation/head (:conversation/head conv)]]))
                    (retract-entity-tx db [:message/id message-id])))]
          (d/transact conn {:tx-data tx}))))))

(defn delete-conversation!
  "按 conversation-id 删除整个会话：所有 message 及其 resource-store 正文。"
  [conn conversation-id resource-store]
  (when conversation-id
    (let [db (d/db conn)
          msg-ids (mapv second (d/q '[:find ?e ?id :in $ ?cid :where [?e :message/conversation-id ?cid] [?e :message/id ?id]] db conversation-id))]
      (when (seq msg-ids)
        (when resource-store
          (doseq [mid msg-ids]
            (when-let [rid (get (pull-message db mid) :message/content-resource-id)]
              (res/delete! resource-store rid))))
        (let [msg-retracts (mapcat #(retract-entity-tx db [:message/id %]) msg-ids)
              conv-retracts (retract-entity-tx db [:conversation/id conversation-id])
              tx (into (vec msg-retracts) (or conv-retracts []))]
          (when (seq tx)
            (d/transact conn {:tx-data tx})))))))

;; ---------------------------------------------------------------------------
;; 列表：近期会话
;; ---------------------------------------------------------------------------

(def ^:private max-title-len 40)

(defn- first-user-content
  [db head-id resource-store]
  (when head-id
    (let [chronological (reverse (loop [acc [], mid head-id]
                                  (if-let [m (pull-message db mid)]
                                    (let [data (or (resolve-content resource-store (:message/content-resource-id m)) {})]
                                      (recur (conj acc {:role (get data :role) :content (get data :content "")})
                                             (:message/prev-id m)))
                                    (reverse acc))))]
      (when (seq chronological)
        (when-let [content (some #(when (= "user" (:role %)) (:content %)) chronological)]
          (if (> (count content) max-title-len)
            (str (subs content 0 max-title-len) "…")
            content))))))

(defn list-recent
  "返回近期会话列表，需传入 resource-store 以生成 title。"
  [conn resource-store]
  (if (nil? resource-store)
    []
    (let [db (d/db conn)
          conv-heads (d/q '[:find ?cid ?head :where [?e :conversation/id ?cid] [?e :conversation/head ?head]] db)
          with-time (for [[cid head] conv-heads
                          :when cid]
                      (let [title (or (first-user-content db head resource-store) "新对话")
                            updated-at (when head (get (pull-message db head) :message/created-at))]
                        {:id cid
                         :title title
                         :updated_at (or updated-at (java.util.Date. 0))}))]
      (reverse (sort-by :updated_at with-time)))))
