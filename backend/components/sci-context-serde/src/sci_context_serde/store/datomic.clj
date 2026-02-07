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
      (let [;; lookup-ref 必须是 [attr value]，否则后面 :db/add 会错
            _ (when-not (and (vector? lookup-ref) (= 2 (count lookup-ref)))
                (throw (ex-info "DatomicStore save!: lookup-ref must be [attr value]"
                                {:lookup-ref lookup-ref :attr attr})))
            {:keys [root blobs]} (ca/snapshot->root+blobs snapshot)
            root-str (pr-str root)
            ;; 用 :db/add 列表形式提交，避免 entity map 在 Java 端被迭代时 keyword 被误当 Map$Entry
            blob-datoms (if (map? blobs)
                          (mapcat (fn [[i [h edn-str]]]
                                    (let [h (str h), edn-str (str edn-str)]
                                      [[:db/add (str "blob-" i) :blob/hash h]
                                       [:db/add (str "blob-" i) :blob/edn edn-str]]))
                                  (map vector (range) (seq blobs)))
                          [])
            root-datom [[:db/add lookup-ref attr root-str]]
            tx-data (into (vec blob-datoms) root-datom)]
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))
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
   - lookup-ref: 存 root 的实体，如 [:message/id msg-id]，或 (conn attr msg-id) 误写时第二参为 keyword、第三参为 entity-id 则自动纠正
   - attr: 存 root EDN 字符串的 attribute（keyword）
  使用前需安装 blob-schema，并确保 attr 已存在于 schema。"
  [conn lookup-ref attr]
  (let [;; 若第二参是 keyword、第三参像 entity id（uuid 等），说明调用方写成了 (conn attr msg-id)，纠正为 lookup-ref=[::id attr-value], attr=keyword
        [lookup-ref attr] (if (and (keyword? lookup-ref) (not (vector? attr)))
                            [[:message/id attr] lookup-ref]
                            [lookup-ref attr])]
    (->DatomicStore conn lookup-ref attr)))
