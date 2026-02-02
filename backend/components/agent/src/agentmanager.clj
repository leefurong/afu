(ns agentmanager
  (:require [agent :refer [->agent]]
            [memory :refer [->memory]]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]))

;;; Schema: content-addressable gene/memory + agent-version
;;; 相同内容的 gene/memory 只存一份（按 content-hash 去重），版本只存引用 → 可还原历史且不重复占空间
(def agent-schema
  [{:db/ident :gene/content-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "SHA-256 of gene EDN, content-addressable"}
   {:db/ident :gene/edn
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized gene (EDN)"}
   {:db/ident :memory/content-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "SHA-256 of memory EDN, content-addressable"}
   {:db/ident :memory/edn
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized memory (EDN)"}
   {:db/ident :agent-version/agent-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Agent 业务 ID"}
   {:db/ident :agent-version/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "版本号，同一 agent-id 递增"}
   {:db/ident :agent-version/gene
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Ref to gene entity"}
   {:db/ident :agent-version/memory
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Ref to memory entity"}
   {:db/ident :agent-version/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "该版本创建时间"}])

(defn ensure-schema!
  "应用 agent 相关 schema，启动时调用一次。"
  [conn]
  (d/transact conn {:tx-data agent-schema}))

(defn- sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- content-hash [data]
  (sha256 (pr-str data)))

(defn- ensure-gene-entity [conn gene]
  (let [edn (pr-str gene)
        h (content-hash gene)]
    (d/transact conn {:tx-data [{:gene/content-hash h
                                 :gene/edn edn}]})
    (d/pull (d/db conn) [:db/id] [:gene/content-hash h])))

(defn- ensure-memory-entity [conn memory]
  (let [edn (pr-str memory)
        h (content-hash memory)]
    (d/transact conn {:tx-data [{:memory/content-hash h
                                 :memory/edn edn}]})
    (d/pull (d/db conn) [:db/id] [:memory/content-hash h])))

(defn- next-version [db agent-id]
  ;; Client API :find 只支持 find-rel，不支持 (max ?v) .
  (let [q '[:find ?v
            :in $ ?aid
            :where [?e :agent-version/agent-id ?aid]
                   [?e :agent-version/version ?v]]
        r (d/q q db agent-id)]
    (inc (or (when (seq r) (apply max (map first r))) 0))))

(defn- latest-version-eids [db agent-id]
  "返回 [version gene-eid memory-eid]，无则 nil。"
  (let [vers (d/q '[:find ?v
                    :in $ ?aid
                    :where [?e :agent-version/agent-id ?aid]
                           [?e :agent-version/version ?v]]
                  db agent-id)
        v (when (seq vers) (apply max (map first vers)))]
    (when v
      (let [res (d/q '[:find ?e
                       :in $ ?aid ?v
                       :where [?e :agent-version/agent-id ?aid]
                              [?e :agent-version/version ?v]]
                     db agent-id v)
            e (when (seq res) (d/pull db [:agent-version/version :agent-version/gene :agent-version/memory] (ffirst res)))]
        (when e
          (let [eid (fn [x] (if (map? x) (:db/id x) x))]
            [(:agent-version/version e)
             (eid (:agent-version/gene e))
             (eid (:agent-version/memory e))]))))))

(defn- load-agent-version [db id version]
  ;; Client API :find 只支持 find-rel
  (let [v (if (some? version)
            version
            (when-let [max-v (d/q '[:find ?v
                                    :in $ ?aid
                                    :where [?e :agent-version/agent-id ?aid]
                                           [?e :agent-version/version ?v]]
                                  db id)]
              (when (seq max-v) (apply max (map first max-v)))))]
    (when v
      (let [res (d/q '[:find ?e
                       :in $ ?aid ?v
                       :where [?e :agent-version/agent-id ?aid]
                              [?e :agent-version/version ?v]]
                     db id v)
            e (when (seq res) (d/pull db [:agent-version/gene :agent-version/memory :agent-version/version] (ffirst res)))]
        (when e
          ;; Client API pull 的 ref 可能返回 {:db/id n}，需取出 eid
          (let [eid (fn [x] (if (map? x) (:db/id x) x))
                gene-eid (eid (:agent-version/gene e))
                memory-eid (eid (:agent-version/memory e))
                gene (-> (d/pull db [:gene/edn] gene-eid) :gene/edn edn/read-string)
                memory (-> (d/pull db [:memory/edn] memory-eid) :memory/edn edn/read-string)
                a (->agent gene memory)]
            (assoc a :agent-id id :version (:agent-version/version e))))))))

(defn- create-agent-with-id! [conn agent-id]
  "用指定 agent-id 创建新 agent 并落库初始版本。"
  (let [a (->agent nil (->memory))
        gene (:gene a)
        memory (:memory a)
        gene-eid (:db/id (ensure-gene-entity conn (or gene {})))
        memory-eid (:db/id (ensure-memory-entity conn (or memory {})))
        v (next-version (d/db conn) agent-id)]
    (d/transact conn
                {:tx-data [{:agent-version/agent-id agent-id
                            :agent-version/version v
                            :agent-version/gene gene-eid
                            :agent-version/memory memory-eid
                            :agent-version/created-at (java.util.Date.)}]})
    (assoc a :agent-id agent-id :version v)))

(defn get-or-create-agent!
  "conn 为 Datomic 连接；id 可选；version 可选（仅在有 id 时有效）。
  - 无 id：创建新 agent 并落库初始版本。
  - 有 id、无 version：加载该 agent 的最新版本；若不存在则用该 id 创建。
  - 有 id、有 version：加载该 agent 的指定版本（还原历史）。
  返回 agent map，含 :gene :memory :agent-id :version。"
  ([conn] (get-or-create-agent! conn nil nil))
  ([conn id] (get-or-create-agent! conn id nil))
  ([conn id version]
   (let [db (d/db conn)
         loaded (when id (load-agent-version db id version))
         result (or loaded
                   (when (or (nil? id) (nil? version))
                     (create-agent-with-id! conn (or id (random-uuid)))))]
     (when result
       (log/info "[agentmanager] get-or-create-agent! id=" id "version=" version
                 "=>" (if loaded "loaded" "created")
                 "agent-id=" (:agent-id result) "version=" (:version result)
                 "gene=" (pr-str (:gene result)) "memory=" (pr-str (:memory result))))
     result)))

(defn save-agent!
  "保存 agent（含 gene、memory）。按内容哈希去重：gene/memory 未变则复用已有实体，仅新增一条版本记录。
  若与当前最新版本内容完全一致，则不写入新版本，直接返回当前版本（避免无意义的历史膨胀）。
  conn 为 Datomic 连接；传入的 agent 须含 :agent-id（来自 get-or-create-agent!）。"
  [conn agent]
  (let [db (d/db conn)
        agent-id (:agent-id agent)
        gene (:gene agent)
        memory (:memory agent)]
    (when-not agent-id
      (throw (ex-info "save-agent requires :agent-id on agent" {:agent agent})))
    (let [gene-eid (:db/id (ensure-gene-entity conn (or gene {})))
          memory-eid (:db/id (ensure-memory-entity conn (or memory {})))
          latest (latest-version-eids db agent-id)]
      (if (and latest
               (= gene-eid (nth latest 1))
               (= memory-eid (nth latest 2)))
        (do
          (log/info "[agentmanager] save-agent! agent-id=" agent-id "=> skipped (same as latest v" (first latest) ")"
                    "gene=" (pr-str gene) "memory=" (pr-str memory))
          (assoc agent :version (first latest)))
        (let [v (next-version db agent-id)]
          (d/transact conn
                      {:tx-data [{:agent-version/agent-id agent-id
                                  :agent-version/version v
                                  :agent-version/gene gene-eid
                                  :agent-version/memory memory-eid
                                  :agent-version/created-at (java.util.Date.)}]})
          (log/info "[agentmanager] save-agent! agent-id=" agent-id "=> saved version=" v
                    "gene=" (pr-str gene) "memory=" (pr-str memory))
          (assoc agent :version v))))))

