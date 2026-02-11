(ns memory-store.sqlite
  "Memory 的 SQLite 实现：每个 key 一个独立 .db 文件。
   使用 sqlite-vec 的 vec_f32/vec_distance_L2 做向量存储与 KNN；扩展未加载时抛错。"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private content-table-ddl
  "CREATE TABLE IF NOT EXISTS memory_content (
     id TEXT PRIMARY KEY,
     content TEXT NOT NULL,
     created_at INTEGER NOT NULL
   )")

(def ^:private embedding-table-ddl
  "CREATE TABLE IF NOT EXISTS vec_embedding (
     content_id TEXT PRIMARY KEY,
     embedding BLOB NOT NULL
   )")

(defn- db-path [base-dir key]
  (let [safe-key (str/replace (str key) #"[^a-zA-Z0-9_-]" "_")]
    (str base-dir "/" safe-key ".db")))

(defn- jdbc-url [path]
  (str "jdbc:sqlite:" path "?enable_load_extension=true"))

(defn- get-ds [base-dir key]
  (let [path (db-path base-dir key)
        parent (.getParentFile (io/file path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    (jdbc/get-datasource (jdbc-url path))))

(defn- with-conn [ds vec-extension-path _embed-dim f]
  (with-open [conn (jdbc/get-connection ds)]
    (when vec-extension-path
      (jdbc/execute! conn ["SELECT load_extension(?)" vec-extension-path]))
    (f conn)))

(defn- ensure-schema! [conn _embed-dim vec-extension-path]
  (jdbc/execute! conn [content-table-ddl])
  (when vec-extension-path
    (jdbc/execute! conn [embedding-table-ddl])))

(defn ensure-memory!
  [base-dir key opts]
  (let [{:keys [vec-extension-path embed-dim]} opts
        embed-dim (or embed-dim 1536)
        ds (get-ds base-dir key)]
    (with-conn ds vec-extension-path embed-dim
      (fn [conn]
        (ensure-schema! conn embed-dim vec-extension-path)))
    key))

(defn remember!
  [base-dir key content embed-fn opts]
  (let [{:keys [vec-extension-path embed-dim]} opts
        embed-dim (or embed-dim 1536)
        ds (get-ds base-dir key)]
    (when-not vec-extension-path
      (throw (ex-info ":vec-extension-path required for remember! (vector search)" {})))
    (with-conn ds vec-extension-path embed-dim
      (fn [conn]
        (ensure-schema! conn embed-dim vec-extension-path)
        (let [id (str (random-uuid))
              embedding (embed-fn content)
              _ (when-not (and (seq embedding) (= (count embedding) embed-dim))
                  (throw (ex-info "embed-fn must return vector of length embed-dim"
                                  {:expected embed-dim :got (count embedding)})))
              embedding-json (json/generate-string embedding)
              now (System/currentTimeMillis)]
          (jdbc/execute! conn
            ["INSERT INTO memory_content (id, content, created_at) VALUES (?, ?, ?)"
             id content now])
          (jdbc/execute! conn
            ["INSERT INTO vec_embedding(content_id, embedding) VALUES (?, vec_f32(?))" id embedding-json])
          id)))))

(defn forget!
  [base-dir key content-id opts]
  (let [{:keys [vec-extension-path embed-dim]} opts
        ds (get-ds base-dir key)]
    (with-conn ds vec-extension-path embed-dim
      (fn [conn]
        (when vec-extension-path
          (jdbc/execute! conn ["DELETE FROM vec_embedding WHERE content_id = ?" content-id]))
        (jdbc/execute! conn ["DELETE FROM memory_content WHERE id = ?" content-id])
        true))))

(defn recall
  [base-dir key content-id _opts]
  (let [ds (get-ds base-dir key)]
    (with-open [conn (jdbc/get-connection ds)]
      (when-let [row (jdbc/execute-one! conn
                    ["SELECT content FROM memory_content WHERE id = ?" content-id]
                    {:builder-fn rs/as-unqualified-maps})]
        (:content row)))))

(defn change!
  [base-dir key content-id new-content embed-fn opts]
  (let [{:keys [vec-extension-path embed-dim]} opts
        embed-dim (or embed-dim 1536)
        ds (get-ds base-dir key)]
    (with-conn ds vec-extension-path embed-dim
      (fn [conn]
        (jdbc/execute! conn ["UPDATE memory_content SET content = ? WHERE id = ?" new-content content-id])
        (when vec-extension-path
          (let [embedding (embed-fn new-content)
                embedding-json (json/generate-string embedding)]
            (jdbc/execute! conn ["DELETE FROM vec_embedding WHERE content_id = ?" content-id])
            (jdbc/execute! conn ["INSERT INTO vec_embedding(content_id, embedding) VALUES (?, vec_f32(?))" content-id embedding-json])))
        true))))

(defn list-content
  [base-dir key opts embed-fn store-opts]
  (let [{:keys [vec-extension-path embed-dim]} store-opts
        ds (get-ds base-dir key)
        q (get opts :q)
        page (max 1 (get opts :page 1))
        page-size (max 1 (min 100 (get opts :page-size 10)))
        offset (* (dec page) page-size)]
    (if (str/blank? q)
      ;; 无搜索：按 created_at 倒序分页
      (with-open [conn (jdbc/get-connection ds)]
        (let [total-row (jdbc/execute-one! conn
                         ["SELECT COUNT(*) as cnt FROM memory_content"]
                         {:builder-fn rs/as-unqualified-maps})
              total-count (get total-row :cnt 0)
              rows (jdbc/execute! conn
                     ["SELECT id, content, created_at FROM memory_content
                       ORDER BY created_at DESC LIMIT ? OFFSET ?"
                      page-size offset]
                     {:builder-fn rs/as-unqualified-maps})]
          {:items (mapv (fn [r]
                         {:id (:id r)
                          :content (:content r)
                          :created-at (:created_at r)})
                       rows)
           :total-count total-count}))
      ;; 有搜索：vec_distance_L2 做 KNN；扩展未加载直接抛错
      (if-not vec-extension-path
        (throw (ex-info "Vector search requires :vec-extension-path in ->memory-store opts"
                       {:q q}))
        (let [query-vec (embed-fn q)
              query-json (json/generate-string query-vec)]
          (with-conn ds vec-extension-path embed-dim
            (fn [conn]
              (let [query-literal (str "vec_f32('" (str/replace query-json #"'" "''") "')")
                    sql (str "SELECT content_id, vec_distance_L2(embedding, " query-literal ") as distance
                              FROM vec_embedding ORDER BY distance LIMIT " (int page-size) " OFFSET " (int offset))
                    knn-rows (jdbc/execute! conn [sql] {:builder-fn rs/as-unqualified-maps})
                    page-ids (mapv :content_id knn-rows)
                    total-row (jdbc/execute-one! conn
                                ["SELECT COUNT(*) as cnt FROM vec_embedding"]
                                {:builder-fn rs/as-unqualified-maps})
                    total-count (get total-row :cnt 0)]
                (when (empty? page-ids)
                  (throw (ex-info "vec_distance_L2 returned no rows; sqlite-vec extension may not be loaded"
                                  {:q q :total-count total-count})))
                (let [placeholders (str/join "," (repeat (count page-ids) "?"))
                      rows (jdbc/execute! conn
                             (into [(str "SELECT id, content, created_at FROM memory_content
                                         WHERE id IN (" placeholders ")")]
                                   page-ids)
                             {:builder-fn rs/as-unqualified-maps})
                      id->idx (into {} (map-indexed (fn [i cid] [cid i]) page-ids))]
                  (when (empty? rows)
                    (throw (ex-info "vec_embedding content_ids 在 memory_content 中不存在，可能是扩展加载异常"
                                    {:page-ids page-ids})))
                  {:items (mapv (fn [r]
                                 {:id (:id r)
                                  :content (:content r)
                                  :created-at (:created_at r)})
                               (sort-by #(get id->idx (:id %) 999) rows))
                   :total-count total-count})))))))))
