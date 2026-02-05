(ns conversation
  "会话历史：后端维护、按 conversation_id 存取。
  与 memory（跨会话记忆）区分：此处仅存单次会话的消息列表，用于连续对话上下文。"
  (:require [datomic.client.api :as d]
            [clojure.edn :as edn]))

(def conversation-schema
  [{:db/ident       :conversation/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "会话唯一 id"}
   {:db/ident       :conversation/messages-edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "消息列表的 EDN：[{:role \"user\"|\"assistant\"|\"system\" :content \"...\"} ...]"}
   {:db/ident       :conversation/updated-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "最后更新时间"}])

(defn ensure-schema!
  "应用会话相关 schema，启动时调用一次。"
  [conn]
  (d/transact conn {:tx-data conversation-schema}))

(defn create!
  "创建新会话，返回其 id（uuid）。"
  [conn]
  (let [id (random-uuid)]
    (d/transact conn
                {:tx-data [{:conversation/id id
                            :conversation/messages-edn "[]"
                            :conversation/updated-at (java.util.Date.)}]})
    id))

(defn get-messages
  "取会话的消息列表。若会话不存在返回空 vector。"
  [conn conversation-id]
  (let [db (d/db conn)
        e (d/pull db [:conversation/messages-edn] [:conversation/id conversation-id])]
    (if-let [edn-str (:conversation/messages-edn e)]
      (try
        (edn/read-string edn-str)
        (catch Exception _
          []))
      [])))

(defn append-messages!
  "向会话追加若干条消息（末尾追加），每条为 {:role \"user\"|\"assistant\"|\"system\" :content \"...\"}。"
  [conn conversation-id new-messages]
  (when (seq new-messages)
    (let [db (d/db conn)
          current (get-messages conn conversation-id)
          updated (into (vec current) new-messages)
          updated-at (java.util.Date.)]
      (d/transact conn
                  {:tx-data [{:conversation/id conversation-id
                              :conversation/messages-edn (pr-str updated)
                              :conversation/updated-at updated-at}]}))))
