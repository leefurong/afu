(ns conversation
  "会话历史：每条消息为独立 record，通过 prev-id/next-ids 组成链表（支持 fork）。
   每条 record 带 conversation-id，便于按会话批量删除。
   与 memory（跨会话记忆）区分：此处仅存单次会话的消息链，用于连续对话上下文。"
  (:require [datomic.client.api :as d]
            [clojure.edn :as edn]))

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
   {:db/ident       :message/data
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "消息内容 EDN：{:role \"user\"|\"assistant\"|\"system\" :content \"...\"} 等"}
   {:db/ident       :message/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "创建时间（也可用 :db/txInstant 做时间序）"}])

(defn ensure-schema!
  "应用会话与消息 schema，启动时调用一次。"
  [conn]
  (d/transact conn {:tx-data (into conversation-schema message-schema)}))

;; ---------------------------------------------------------------------------
;; 内部：按 message id 查 entity id / 拉取 entity
;; ---------------------------------------------------------------------------

;; Datomic Client API 不支持按 eid pull，用 query 按 message id（lookup ref）取数据
(defn- pull-message
  [db message-id]
  (when message-id
    (d/pull db [:message/id :message/conversation-id :message/prev-id :message/next-ids :message/selected-next-id :message/data :message/created-at]
            [:message/id message-id])))

(defn- retract-entity-tx
  "按 lookup-ref 查出该实体所有 [a v]，生成 retract tx。避免在 query 中传 eid（Client 会变成 invalid [eid]）。"
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
  "返回会话主分支头节点 message id（conversation 上缓存的 head，用于快速加载），无则 nil。"
  [conn conversation-id]
  (let [e (d/pull (d/db conn) [:conversation/head] [:conversation/id conversation-id])]
    (:conversation/head e)))

(defn compute-head-from
  "从某条 record（message-id）开始，顺藤摸瓜找 head：有唯一后继则沿其后继走；
   多后继则走 selected-next-id（未设则取第一个），直到无后继。返回该分支的 head message id。"
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
  "从 message-id 沿 prev-id 回退，找到第一个多后继的节点（fork 点）P。
   返回 {:fork-id P的id :ordered-next-ids 按 created-at 升序的后继 id 列表 :current-selected P 的 selected-next-id（或第一个后继）}，若无 fork 则 nil。"
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
  "在 message-id 所在分支的 fork 点上，把「当前选中的后继」向左（delta=-1）或向右（delta=+1）切换。
   后继按 created-at 升序排列，-1=选更早的，+1=选更晚的。返回切换后的被选中的 id；若无左/右则返回 nil。"
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

(defn left!
  "在 message-id 所在分支的 fork 点上向左切换（选时间更早的后继）。返回新选中的 id，若无则 nil。"
  [conn message-id]
  (change-next! conn -1 message-id))

(defn right!
  "在 message-id 所在分支的 fork 点上向右切换（选时间更晚的后继）。返回新选中的 id，若无则 nil。"
  [conn message-id]
  (change-next! conn 1 message-id))

(defn- can-left-right-for-message
  "某条消息若有多个后继，按 selected-next-id 在有序后继中的位置：最左则不能左，最右则不能右。"
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
  "从指定头节点沿 prev-id 向前遍历，得到该分支消息列表（ Chronological 顺序）。
   head-message-id 为 nil 时使用主分支 head；会话不存在或无消息时返回 []。
   每条为 map，含 :id、:can-left? / :can-right?（该条若有多个后继，当前选中是否可再点左/右），以及 data 中所有键。"
  ([conn conversation-id]
   (get-messages conn conversation-id (get-head conn conversation-id)))
  ([conn conversation-id head-message-id]
   (if (or (nil? conversation-id) (nil? head-message-id))
     []
     (let [db (d/db conn)
           raw (loop [acc [], mid head-message-id]
                 (if-let [m (pull-message db mid)]
                   (let [data (try (edn/read-string (or (:message/data m) "{}"))
                                   (catch Exception _ {}))]
                     (recur (conj acc {:id mid :data data}) (:message/prev-id m)))
                   (reverse acc)))]
       (mapv (fn [{:keys [id data]}]
               (let [{:keys [can-left? can-right?]} (can-left-right-for-message db id)]
                 (assoc data :id id :can-left? can-left? :can-right? can-right?)))
             raw)))))

(defn append-messages!
  "在 prev-message-id 之后追加一串消息，形成一条新链；并可选更新主分支 head。
   - prev-message-id: 新链挂在其后；为 nil 时表示从会话「当前主分支 head」后追加（无 head 则为首条）。
   - new-messages: 顺序为 [msg1 msg2 ...]，每条为 map（如 {:role \"user\" :content \"...\"}）。
   - update-main-head?: 为 true 且 prev 为当前主分支 head 时，把主分支 head 更新为最后一条新消息 id（用于正常续写）；fork 时传 false，不改主分支。"
  ([conn conversation-id new-messages]
   (append-messages! conn conversation-id new-messages nil true))
  ([conn conversation-id new-messages prev-message-id]
   (append-messages! conn conversation-id new-messages prev-message-id true))
  ([conn conversation-id new-messages prev-message-id update-main-head?]
   (when (seq new-messages)
     (let [db (d/db conn)
           main-head (get-head conn conversation-id)
           prev-id (or prev-message-id main-head)
           ;; 首条消息或「挂在主分支头后面」且要求更新时，才改 main head
           update-head? (and update-main-head?
                             (or (nil? prev-id)
                                 (and main-head (= prev-id main-head))))
           new-ids (mapv (fn [_] (random-uuid)) new-messages)
           prev-next-ids (if prev-id (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db prev-id)) [])
           now (java.util.Date.)
           tx (into []
                   (concat
                    ;; 新消息链：首条 prev = prev-id，其余 prev = 上一条 id；next-ids 链式
                    (map-indexed
                     (fn [i msg]
                       (let [id (nth new-ids i)
                             prev (if (zero? i) prev-id (nth new-ids (dec i)))
                             next-ids (if (< (inc i) (count new-ids))
                                        [(nth new-ids (inc i))]
                                        [])
                             m {:message/id id
                                :message/conversation-id conversation-id
                                :message/next-ids next-ids
                                :message/data (pr-str msg)
                                :message/created-at now}]
                         (if (some? prev)
                           (assoc m :message/prev-id prev)
                           m)))
                     new-messages)
                    ;; 前驱的 next-ids 加入首条新消息，并设 selected-next-id 为新分支首条（顺藤摸瓜时走这条）
                    (when prev-id
                      [{:message/id prev-id
                        :message/next-ids (into (vec (remove #{(first new-ids)} prev-next-ids))
                                                [(first new-ids)])
                        :message/selected-next-id (first new-ids)}])
                    ;; 主分支 head 更新为最后一条新消息
                    (when update-head?
                      [{:conversation/id conversation-id
                        :conversation/head (last new-ids)}])))]
       (d/transact conn {:tx-data tx})))))

(defn delete-message!
  "删除一条消息 record，并维护链表：前驱的 next-ids 去掉本节点并接上本节点的 next-ids；后继的 prev-id 指向前驱。"
  [conn message-id]
  (when message-id
    (let [db (d/db conn)
          pid (ffirst (d/q '[:find ?prev :in $ ?id :where [?e :message/id ?id] [?e :message/prev-id ?prev]] db message-id))
          nids (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db message-id))
          cid (ffirst (d/q '[:find ?cid :in $ ?id :where [?e :message/id ?id] [?e :message/conversation-id ?cid]] db message-id))]
      (when (some? cid)
        (let [conv (d/pull db [:db/id :conversation/head] [:conversation/id cid])
              head-was-me? (= message-id (:conversation/head conv))
              p-next (when pid (vec (remove #{message-id} (mapv first (d/q '[:find ?v :in $ ?id :where [?e :message/id ?id] [?e :message/next-ids ?v]] db pid)))))
              new-next (when pid (into (or p-next []) (or nids [])))
              tx (into []
                   (concat
                    ;; 后继的 prev-id 改为前驱（用 lookup ref）
                    (keep (fn [nid]
                            (when (some? nid)
                              {:message/id nid :message/prev-id pid}))
                          (or nids []))
                    ;; 前驱的 next-ids：去掉 me，接上 nids（用 lookup ref）
                    (when pid
                      [{:message/id pid :message/next-ids new-next}])
                    ;; 若主分支 head 是当前节点，回退到前驱（或 retract head）
                    (when head-was-me?
                      (if (some? pid)
                        [{:conversation/id cid :conversation/head pid}]
                        [[:db/retract [:conversation/id cid] :conversation/head (:conversation/head conv)]]))
                    ;; 删除本实体（按 lookup ref 逐属性 retract）
                    (retract-entity-tx db [:message/id message-id])))]
          (d/transact conn {:tx-data tx}))))))

(defn delete-conversation!
  "按 conversation-id 删除整个会话：删除该会话下所有 message record 及会话实体。"
  [conn conversation-id]
  (when conversation-id
    (let [db (d/db conn)
          msg-ids (mapv second (d/q '[:find ?e ?id :in $ ?cid :where [?e :message/conversation-id ?cid] [?e :message/id ?id]] db conversation-id))
          msg-retracts (mapcat #(retract-entity-tx db [:message/id %]) msg-ids)
          conv-retracts (retract-entity-tx db [:conversation/id conversation-id])
          tx (into (vec msg-retracts) (or conv-retracts []))]
      (when (seq tx)
        (d/transact conn {:tx-data tx})))))

;; ---------------------------------------------------------------------------
;; 列表：近期会话（供侧边栏展示）
;; ---------------------------------------------------------------------------

(def ^:private max-title-len 40)

(defn- first-user-content
  "从 head 沿 prev-id 回溯得到时间正序消息，取第一条 user 的 content，截断。"
  [db head-id]
  (when head-id
    (let [chronological (reverse (loop [acc [], mid head-id]
                                  (if-let [m (pull-message db mid)]
                                    (let [data (try (edn/read-string (or (:message/data m) "{}"))
                                                    (catch Exception _ {}))]
                                      (recur (conj acc {:role (get data :role) :content (get data :content "")})
                                             (:message/prev-id m)))
                                    (reverse acc))))]
      (when (seq chronological)
        (when-let [content (some #(when (= "user" (:role %)) (:content %)) chronological)]
          (if (> (count content) max-title-len)
            (str (subs content 0 max-title-len) "…")
            content))))))

(defn list-recent
  "返回近期会话列表，每项 {:id uuid :title string :updated_at instant}，按 updated_at 降序。
   title 为第一条 user 消息内容截断，无则「新对话」。"
  [conn]
  (let [db (d/db conn)
        conv-heads (d/q '[:find ?cid ?head :where [?e :conversation/id ?cid] [?e :conversation/head ?head]] db)
        with-time (for [[cid head] conv-heads
                        :when cid]
                    (let [title (or (first-user-content db head) "新对话")
                          updated-at (when head (get (pull-message db head) :message/created-at))]
                      {:id cid
                       :title title
                       :updated_at (or updated-at (java.util.Date. 0))}))]
    (reverse (sort-by :updated_at with-time))))
