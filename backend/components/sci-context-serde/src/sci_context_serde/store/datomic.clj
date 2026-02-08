(ns sci-context-serde.store.datomic
  "SnapshotStore 的 Datomic 实现：按内容寻址存储。
  - root（namespace + name→hash）存在 lookup-ref 对应实体的 attr 上；
  - 每个 blob 存为独立实体 :blob/hash + :blob/edn；单条超过 Datomic 4KB 限制时拆成 :blob-chunk 多块。
  使用前需在项目 schema 中安装 blob-schema（含 blob-chunk），并在存 root 的实体上声明 attr（string）。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [sci-context-serde.store :as store]
            [sci-context-serde.store.content-addressable :as ca]))

(def ^:private max-blob-chars 4000)

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
    :db/doc         "单条 binding entry 的 EDN 字符串（≤4KB）；超长时改用 blob-chunk"}
   {:db/ident       :blob-chunk/hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "所属 blob 的 content hash"}
   {:db/ident       :blob-chunk/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "块序号，从 0 起"}
   {:db/ident       :blob-chunk/part
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "该块子串，单块 ≤4KB"}])

(defn- blob-parts [s max-len]
  (when (and s (pos? max-len))
    (mapv (fn [i] (subs s i (min (+ i max-len) (count s))))
          (range 0 (count s) max-len))))

(defn- eids-for-hash [db h]
  (mapv first (d/q '[:find ?e :in $ ?h
                     :where (or [?e :blob/hash ?h] [?e :blob-chunk/hash ?h])]
                   db h)))

(defrecord DatomicStore [conn lookup-ref attr]
  store/SnapshotStore
  (save! [_ snapshot]
    (when (and conn lookup-ref attr snapshot)
      (let [;; lookup-ref 必须是 [attr value]，否则后面 :db/add 会错
            _ (when-not (and (vector? lookup-ref) (= 2 (count lookup-ref)))
                (throw (ex-info "DatomicStore save!: lookup-ref must be [attr value]"
                                {:lookup-ref lookup-ref :attr attr})))
            db (d/db conn)
            {:keys [root blobs]} (ca/snapshot->root+blobs snapshot)
            root-str (pr-str root)
            blob-tx (when (map? blobs)
                      (mapcat (fn [[h edn-str]]
                                (let [h (str h)
                                      edn-str (str edn-str)
                                      n (count edn-str)
                                      retract-tx (mapv #(vector :db/retractEntity %)
                                                       (eids-for-hash db h))]
                                  (if (<= n max-blob-chars)
                                    (into retract-tx [{:blob/hash h :blob/edn edn-str}])
                                    (let [parts (blob-parts edn-str max-blob-chars)
                                          chunk-tx (map-indexed
                                                    (fn [i part]
                                                      {:blob-chunk/hash h
                                                       :blob-chunk/index i
                                                       :blob-chunk/part part})
                                                    parts)]
                                      (into retract-tx chunk-tx)))))
                              blobs))
            root-datom [[:db/add lookup-ref attr root-str]]
            tx-data (into (vec (or blob-tx [])) root-datom)]
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))
        true)))
  (load* [_]
    (when (and conn lookup-ref attr)
      (let [db (d/db conn)
            e (d/pull db [attr] lookup-ref)
            root-str (get e attr)
            get-blob (fn [h]
                       (let [blob-e (d/pull db [:blob/edn] [:blob/hash h])
                             edn (:blob/edn blob-e)]
                         (if (str/blank? edn)
                           (when-let [chunks (seq (d/q '[:find ?i ?part :in $ ?h
                                                         :where [?e :blob-chunk/hash ?h]
                                                         [?e :blob-chunk/index ?i]
                                                         [?e :blob-chunk/part ?part]]
                                                       db h))]
                             (str/join (mapv second (sort-by first chunks))))
                           edn)))]
        (when (and root-str (string? root-str))
          (try
            (when-let [root-map (edn/read-string root-str)]
              (when (map? root-map)
                (ca/root+blobs->snapshot root-map get-blob)))
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
