(ns sci-context-serde.store.datomic
  "SnapshotStore 的 Datomic 实现：按 lookup-ref 写入/读出一个 string 类型的 attribute。
  使用前需在项目 schema 中声明该 attribute（如 :message/sci-context-snapshot 或 :conversation/sci-context-snapshot）。"
  (:require [datomic.client.api :as d]
            [sci-context-serde.store :as store]))

(defrecord DatomicStore [conn lookup-ref attr]
  store/SnapshotStore
  (save! [_ snapshot]
    (when (and conn lookup-ref attr snapshot)
      (d/transact conn {:tx-data [(merge (into {} lookup-ref)
                                        {attr (store/snapshot->edn-str snapshot)})]})
      true))
  (load* [_]
    (when (and conn lookup-ref attr)
      (let [e (d/pull (d/db conn) [attr] lookup-ref)]
        (store/edn-str->snapshot (get e attr))))))

(defn ->datomic-store
  "构造 Datomic 存贮后端。
   - conn: Datomic client 连接（d/connect 返回值）
   - lookup-ref: 实体查找 ref，如 [:conversation/id conv-id] 或 [:message/id msg-id]
   - attr: 存 snapshot 的 attribute keyword，需在项目 schema 中已定义且为 string 类型
  示例：(->datomic-store conn [:conversation/id conv-id] :conversation/sci-context-snapshot)"
  [conn lookup-ref attr]
  (->DatomicStore conn lookup-ref attr))
