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
   {:db/ident       :conversation/root
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "会话的空根节点 message id；从 root 沿 selected-next-id 可推出当前分支 tip，无 head；首条真实消息挂在 root 下，便于「编辑第一条」时在 root 下切分支"}
   {:db/ident       :conversation/user-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         "所属用户 account/id；未登录时用 default-user-id"}])

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
  "创建新会话，返回其 id（uuid）。user-id 为 account/id（uuid），未登录时传 default-user-id。
   同时创建一条空 root 消息，conversation/root 指向它；首条真实消息将挂在 root 下，便于「编辑第一条」时在 root 下切分支。"
  [conn resource-store user-id]
  (when resource-store
    (let [conv-id (random-uuid)
          root-id (random-uuid)
          content-id (res/put! resource-store "{}")
          now (java.util.Date.)]
      (d/transact conn {:tx-data [{:conversation/id conv-id
                                  :conversation/root root-id
                                  :conversation/user-id user-id}
                                 {:message/id root-id
                                  :message/conversation-id conv-id
                                  :message/next-ids []
                                  :message/content-resource-id content-id
                                  :message/created-at now}]})
      conv-id)))

(defn get-root
  "返回会话的空根节点 message id，无则 nil。"
  [conn conversation-id]
  (let [e (d/pull (d/db conn) [:conversation/root] [:conversation/id conversation-id])]
    (:conversation/root e)))

(defn get-conversation-user-id
  "返回会话的 user-id，无则 nil（旧数据未设 user-id）。"
  [conn conversation-id]
  (:conversation/user-id (d/pull (d/db conn) [:conversation/user-id] [:conversation/id conversation-id])))

(defn compute-head-from
  "从 message-id 起沿 selected-next-id 走到无后继，返回当前分支 tip。
   单后继时沿用该后继；多后继时仅当 selected-next-id 在 next-ids 内才沿之走。由 root 调用即得当前分支 tip，无需存 head。"
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
  "找到 message-id 所在 fork：其父有多个后继时返回 ordered 列表。不依赖父的 selected-next-id。"
  [db message-id]
  (loop [mid message-id]
    (when mid
      (let [pid (ffirst (d/q '[:find ?prev :in $ ?id :where [?e :message/id ?id] [?e :message/prev-id ?prev]] db mid))]
        (when pid
          (let [next-ids (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db pid))]
            (if (> (count next-ids) 1)
              (let [id->at (into {} (map (fn [id] [id (get (pull-message db id) :message/created-at)]) next-ids))
                    ordered (vec (sort-by (fn [id] [(or (get id->at id) (java.util.Date. 0)) (str id)]) next-ids))]
                (when (some #{message-id} ordered)
                  {:fork-id pid :ordered-next-ids ordered}))
              (recur pid))))))))

(defn change-next!
  "在父的多个后继中切换：delta -1 为左，+1 为右。以传入的 message-id（用户当前看的那条）为「当前」算新下标，不依赖父的 selected-next-id。"
  [conn delta message-id]
  (when (and message-id (#{-1 1} delta))
    (let [db (d/db conn)
          info (find-fork-point-and-siblings db message-id)]
      (when info
        (let [ordered (:ordered-next-ids info)
              idx (first (keep-indexed (fn [i e] (when (= e message-id) i)) ordered))
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
  "返回该条消息（作为 fork 点）是否有左/右兄弟可切换；供 change-next! 等内部用。"
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

(defn- can-left-right-as-child
  "返回该条消息（作为「父消息的后继」之一）是否有左/右兄弟可切换。即：找这条消息 B 的上一条 A，若 A 有多个后继，则 B 是其中之一，返回 B 在 A 的后继里的序号与可否左/右切换。供前端在「子消息」上显示 < 2/2 >。"
  [db mid]
  (let [m (pull-message db mid)
        pid (:message/prev-id m)]
    (if (nil? pid)
      {:can-left? false :can-right? false :sibling-index 1 :sibling-total 1}
      (let [p (pull-message db pid)
            next-ids (vec (or (:message/next-ids p) []))]
        (if (<= (count next-ids) 1)
          {:can-left? false :can-right? false :sibling-index 1 :sibling-total 1}
          (let [id->at (into {} (map (fn [id] [id (get (pull-message db id) :message/created-at)]) next-ids))
                ordered (vec (sort-by (fn [id] [(or (get id->at id) (java.util.Date. 0)) (str id)]) next-ids))
                idx (first (keep-indexed (fn [i e] (when (= e mid) i)) ordered))]
            (if (integer? idx)
              {:can-left? (> idx 0)
               :can-right? (< idx (dec (count ordered)))
               :sibling-index (inc idx)
               :sibling-total (count ordered)}
              {:can-left? false :can-right? false :sibling-index 1 :sibling-total 1})))))))

(defn get-current-head
  "由 root 沿各节点的 selected-next-id 推出当前分支的 tip；无 root 或 tip 仍是 root（尚无真实消息）时返回 nil。"
  [conn conversation-id]
  (when-let [root (get-root conn conversation-id)]
    (let [tip (compute-head-from conn root)]
      (when (not= tip root) tip))))

(defn get-messages
  "从当前分支 tip 沿 prev-id 向前遍历到 root（不含 root），得到该分支消息列表。无 tip 时用 get-current-head。每条消息的 can-left?/can-right?/sibling-* 按「该条作为子节点」计算。"
  ([conn conversation-id resource-store]
   (get-messages conn conversation-id (get-current-head conn conversation-id) resource-store))
  ([conn conversation-id tip-message-id resource-store]
   (if (or (nil? conversation-id) (nil? tip-message-id) (nil? resource-store))
     []
     (let [db (d/db conn)
           root-id (get-root conn conversation-id)
           raw (loop [acc [], mid tip-message-id]
                 (cond
                   (= mid root-id) (reverse acc)
                   (nil? mid) (reverse acc)
                   :else (if-let [m (pull-message db mid)]
                           (let [data (or (resolve-content resource-store (:message/content-resource-id m)) {})
                                 sci (get m :message/sci-context-snapshot)]
                             (recur (conj acc {:id mid :data data :sci-context-snapshot sci}) (:message/prev-id m)))
                           (reverse acc))))]
       (mapv (fn [{:keys [id data sci-context-snapshot]}]
               (let [{:keys [can-left? can-right? sibling-index sibling-total]} (can-left-right-as-child db id)]
                 (assoc data :id id :can-left? can-left? :can-right? can-right?
                        :sibling-index sibling-index :sibling-total sibling-total
                        :sci-context-snapshot sci-context-snapshot)))
             raw)))))

(defn append-messages!
  "在 prev-message-id 之后追加一串消息；正文写入 resource-store，Datomic 只存 content-resource-id。
   opts 可选 {:root-branch? true}：从根分叉，prev-id 为 nil，不接在任何已有消息后。"
  ([conn conversation-id new-messages resource-store]
   (append-messages! conn conversation-id new-messages nil true resource-store nil))
  ([conn conversation-id new-messages prev-message-id resource-store]
   (append-messages! conn conversation-id new-messages prev-message-id true resource-store nil))
  ([conn conversation-id new-messages prev-message-id update-main-head? resource-store]
   (append-messages! conn conversation-id new-messages prev-message-id update-main-head? resource-store nil))
  ([conn conversation-id new-messages prev-message-id update-main-head? resource-store opts]
   (when (and (seq new-messages) resource-store)
     (let [db (d/db conn)
           root-branch? (get opts :root-branch? false)
           root-id (get-root conn conversation-id)
           ;; 首条消息或从根分叉时挂在 root 下；否则挂在 prev-message-id 或当前分支 tip 下
           prev-id (or (when (not root-branch?) (or prev-message-id (get-current-head conn conversation-id)))
                       root-id)
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
                      [(cond-> {:message/id prev-id
                                :message/next-ids (into (vec (remove #{(first new-ids)} prev-next-ids))
                                                        [(first new-ids)])}
                         update-main-head? (assoc :message/selected-next-id (first new-ids)))])))]
       (d/transact conn {:tx-data tx})
       new-ids))))

(defn delete-message!
  "删除一条消息 record 并维护链表；同时从 resource-store 删除对应正文。不允许删除空 root 节点。"
  [conn message-id resource-store]
  (when message-id
    (let [db (d/db conn)
          cid (ffirst (d/q '[:find ?cid :in $ ?id :where [?e :message/id ?id] [?e :message/conversation-id ?cid]] db message-id))
          root-id (when cid (get-root conn cid))]
      (when (and (some? cid) (not= message-id root-id))
        (let [msg (pull-message db message-id)
              content-id (:message/content-resource-id msg)
              pid (ffirst (d/q '[:find ?prev :in $ ?id :where [?e :message/id ?id] [?e :message/prev-id ?prev]] db message-id))
              nids (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db message-id))
              p (when pid (pull-message db pid))
              p-next (when pid (vec (remove #{message-id} (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db pid)))))
              new-next (when pid (into (or p-next []) (or nids [])))
              p-selected (when p (:message/selected-next-id p))
              new-selected (when (= p-selected message-id) (first new-next))
              parent-tx (when pid
                         (cond
                           (some? new-selected)
                           [(cond-> {:message/id pid :message/next-ids new-next}
                              true (assoc :message/selected-next-id new-selected))]
                           (and (= p-selected message-id) (nil? new-selected))
                           [{:message/id pid :message/next-ids new-next}
                            [:db/retract [:message/id pid] :message/selected-next-id p-selected]]
                           :else
                           [{:message/id pid :message/next-ids new-next}]))
              tx (into []
                   (concat
                    (keep (fn [nid]
                            (when (some? nid)
                              {:message/id nid :message/prev-id pid}))
                          (or nids []))
                    (when parent-tx parent-tx)
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
  "从 tip 沿 prev-id 向前到 root（不含 root），取第一条 user 的 content 做标题。"
  [db head-id resource-store root-id]
  (when (and head-id (not= head-id root-id))
    (let [chronological (reverse (loop [acc [], mid head-id]
                                  (cond
                                    (= mid root-id) (reverse acc)
                                    (nil? mid) (reverse acc)
                                    :else (if-let [m (pull-message db mid)]
                                            (let [data (or (resolve-content resource-store (:message/content-resource-id m)) {})]
                                              (recur (conj acc {:role (get data :role) :content (get data :content "")})
                                                     (:message/prev-id m)))
                                            (reverse acc)))))]
      (when (seq chronological)
        (when-let [content (some #(when (= "user" (:role %)) (:content %)) chronological)]
          (if (> (count content) max-title-len)
            (str (subs content 0 max-title-len) "…")
            content))))))

(def ^:private default-user-id #uuid "00000000-0000-0000-0000-000000000000")

(defn list-recent
  "返回近期会话列表，按 user-id 过滤。user-id 为 account/id；未登录时传 default-user-id。
  需传入 resource-store 以生成 title。当前分支由 root + selected-next-id 推出。
  已登录用户仅看本人的；default 用户看 default + 无 user-id 的旧数据。"
  [conn resource-store user-id]
  (if (nil? resource-store)
    []
    (let [db         (d/db conn)
          user-id    (or user-id default-user-id)
          own        (d/q '[:find ?cid ?root :in $ ?uid :where
                            [?e :conversation/id ?cid] [?e :conversation/root ?root] [?e :conversation/user-id ?uid]]
                          db user-id)
          legacy     (when (= user-id default-user-id)
                      (concat
                       (d/q '[:find ?cid ?root :in $ ?def :where
                              [?e :conversation/id ?cid] [?e :conversation/root ?root] [?e :conversation/user-id ?def]]
                            db default-user-id)
                       (d/q '[:find ?cid ?root :where
                              [?e :conversation/id ?cid] [?e :conversation/root ?root]
                              [(missing? $ ?e :conversation/user-id)]]
                            db)))
          conv-roots (vec (distinct (concat own (or legacy []))))
          with-time (for [[cid root] conv-roots
                          :when cid]
                      (let [tip (compute-head-from conn root)
                            title (or (first-user-content db tip resource-store root) "新对话")
                            updated-at (when (and tip (not= tip root))
                                         (get (pull-message db tip) :message/created-at))]
                        {:id cid
                         :title title
                         :updated_at (or updated-at (java.util.Date. 0))}))]
      (reverse (sort-by :updated_at with-time)))))
