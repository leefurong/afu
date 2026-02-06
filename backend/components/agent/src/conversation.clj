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

;; Datomic segment/memcached limit ~1MB; keep messages-edn under this to avoid "Item too large"
(def ^:private max-messages-edn-bytes (int 512000))

(defn- truncate-content [s max-len]
  (if (and (string? s) (> (count s) max-len))
    (str (subs s 0 max-len) "... [truncated]")
    s))

(defn- cap-message-content [msg max-content-len]
  (if (string? (:content msg))
    (update msg :content #(truncate-content % max-content-len))
    msg))

(def ^:private max-single-content-chars (int 100000))

(defn- trim-messages-to-size
  "Drop oldest messages until pr-str of messages is under max-bytes.
   Each message :content is capped to max-single-content-chars so one huge message doesn't force dropping all."
  [messages max-bytes]
  (let [capped (mapv #(cap-message-content % max-single-content-chars) messages)]
    (if (<= (count (pr-str capped)) max-bytes)
      capped
      (recur (subvec capped 1) max-bytes))))

(defn append-messages!
  "向会话追加若干条消息（末尾追加），每条为 {:role \"user\"|\"assistant\"|\"system\" :content \"...\"}。
   若合并后 EDN 超过 max-messages-edn-bytes，会丢弃最旧的消息以避免 Datomic \"Item too large\"。"
  [conn conversation-id new-messages]
  (when (seq new-messages)
    (let [current (get-messages conn conversation-id)
          updated (into (vec current) new-messages)
          trimmed (trim-messages-to-size updated max-messages-edn-bytes)
          updated-at (java.util.Date.)]
      (d/transact conn
                  {:tx-data [{:conversation/id conversation-id
                              :conversation/messages-edn (pr-str trimmed)
                              :conversation/updated-at updated-at}]}))))
