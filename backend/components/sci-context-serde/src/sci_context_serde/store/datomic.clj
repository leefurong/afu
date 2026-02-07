(ns sci-context-serde.store.datomic
  "SnapshotStore 的 Datomic 实现：按内容寻址存储。
  - root（namespace + name→hash）存在 lookup-ref 对应实体的 attr 上；
  - 每个 blob 存为独立实体 :blob/hash + :blob/edn，相同内容只存一份。
  使用前需在项目 schema 中安装 blob-schema，并在存 root 的实体上声明 attr（string）。"
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [sci-context-serde.store :as store]
            [sci-context-serde.store.content-addressable :as ca]))

(def blob-schema
  "内容寻址 blob 的 schema，需由项目在 DB 中安装一次（如 merge 进 conversation 的 ensure-schema）。"
  [{:db/ident       :blob/hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "内容哈希（SHA-256 十六进制），用于去重"}
   {:db/ident       :blob/edn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "单条 binding entry 的 EDN 字符串"}])

(defrecord DatomicStore [conn lookup-ref attr]
  store/SnapshotStore
  (save! [_ snapshot]
    (when (and conn lookup-ref attr snapshot)
      (let [{:keys [root blobs]} (ca/snapshot->root+blobs snapshot)
            blob-tx (mapv (fn [[h edn-str]] {:blob/hash h :blob/edn edn-str}) blobs)
            root-tx (merge (into {} lookup-ref)
                           {attr (pr-str root)})]
        (when (seq blob-tx)
          (d/transact conn {:tx-data blob-tx}))
        (d/transact conn {:tx-data [root-tx]})
        true)))
  (load* [_]
    (when (and conn lookup-ref attr)
      (let [db (d/db conn)
            e (d/pull db [attr] lookup-ref)
            root-str (get e attr)]
        (when (and root-str (string? root-str))
          (try
            (when-let [root-map (edn/read-string root-str)]
            (when (map? root-map)
              (ca/root+blobs->snapshot root-map
                                       (fn [h]
                                         (get (d/pull db [:blob/edn] [:blob/hash h])
                                              :blob/edn)))))
            (catch Exception _ nil)))))))

(defn ->datomic-store
  "构造按内容寻址的 Datomic 存贮后端。
   - conn: Datomic client 连接
   - lookup-ref: 存 root 的实体，如 [:conversation/id conv-id]
   - attr: 存 root EDN 字符串的 attribute（string 类型）
  使用前需安装 blob-schema，并确保 attr 已存在于 schema。"
  [conn lookup-ref attr]
  (->DatomicStore conn lookup-ref attr))
