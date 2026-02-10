(ns resource-store.sqlite
  "ResourceStore 的 SQLite 实现：单表 id + content，无大小限制。"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resource-store.protocol :as protocol]))

(def ^:private table-ddl
  "CREATE TABLE IF NOT EXISTS resource (
     id TEXT PRIMARY KEY,
     content TEXT NOT NULL,
     updated_at INTEGER NOT NULL
   )")

(defn- ensure-schema! [ds]
  (jdbc/execute! ds [table-ddl]))

(deftype SqliteResourceStore [ds]
  protocol/ResourceStore
  (put! [this content]
    (let [id (str (random-uuid))]
      (protocol/put-at! this id content)
      id))
  (put-at! [this id content]
    (ensure-schema! ds)
    (let [now (System/currentTimeMillis)]
      (jdbc/execute! ds
        ["INSERT OR REPLACE INTO resource (id, content, updated_at) VALUES (?, ?, ?)"
         id (str content) now]))
    id)
  (get* [this id]
    (ensure-schema! ds)
    (when id
      (let [row (jdbc/execute-one! ds
                    ["SELECT content FROM resource WHERE id = ?" id]
                    {:builder-fn rs/as-unqualified-maps})]
        (:content row))))
  (delete! [this id]
    (when id
      (ensure-schema! ds)
      (jdbc/execute! ds ["DELETE FROM resource WHERE id = ?" id])
      true)))

(defn ->store
  [jdbc-url]
  (let [ds (jdbc/get-datasource jdbc-url)]
    (->SqliteResourceStore ds)))
