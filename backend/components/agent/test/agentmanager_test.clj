(ns agentmanager-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [agent :refer [->agent]]
            [agentmanager]
            [datomic.client.api :as d]))

;; 根本做法：测试用独立 DB（与正式库 afu-test 隔离）；可改为每次运行用唯一名或内存库
(def ^:private test-db-name "afu-agentmanager-test")

(def ^:dynamic *conn* nil)

(defn- test-client []
  (d/client {:server-type :dev-local
             :system "dev"}))

(defn- create-test-conn []
  (let [client (test-client)]
    (d/create-database client {:db-name test-db-name})
    (d/connect client {:db-name test-db-name})))

(defn- delete-test-db []
  (try
    (d/delete-database (test-client) {:db-name test-db-name})
    (catch Exception _ nil)))

(defn with-test-db [f]
  (delete-test-db)
  (let [conn (create-test-conn)]
    (agentmanager/ensure-schema! conn)
    (binding [*conn* conn]
      (f))
    (delete-test-db)))

(use-fixtures :each with-test-db)

;; 业务内容比较：gene 会带默认 :model-opts，断言时只比较业务字段
(defn- gene-content [g] (dissoc (or g {}) :model-opts))
(defn- memory-content [m] (or m {}))

(deftest get-or-create-agent!-create-new
  (testing "无 id 时创建新 agent 并落库"
    (let [a (agentmanager/get-or-create-agent! *conn*)]
      (is (some? (:agent-id a)))
      (is (= 1 (:version a)))
      (is (contains? a :gene))
      (is (contains? a :memory))
      ;; 再次用该 id 加载应得到相同 agent
      (let [loaded (agentmanager/get-or-create-agent! *conn* (:agent-id a))]
        (is (= (:agent-id a) (:agent-id loaded)))
        (is (= (:version a) (:version loaded)))))))

(deftest get-or-create-agent!-load-latest
  (testing "有 id 时加载最新版本"
    (let [a (agentmanager/get-or-create-agent! *conn*)
          id (:agent-id a)]
      (agentmanager/save-agent! *conn* (assoc a :gene {:x 1} :memory {:m 1}))
      (agentmanager/save-agent! *conn* (assoc a :gene {:x 2} :memory {:m 2}))
      (let [latest (agentmanager/get-or-create-agent! *conn* id)]
        (is (= id (:agent-id latest)))
        (is (= 3 (:version latest)))
        (is (= {:x 2} (gene-content (:gene latest))))
        (is (= {:m 2} (memory-content (:memory latest))))))))

(deftest get-or-create-agent!-load-specific-version
  (testing "有 id 和 version 时加载指定版本（还原历史）"
    (let [a (agentmanager/get-or-create-agent! *conn*)
          id (:agent-id a)]
      (agentmanager/save-agent! *conn* (assoc a :gene {:v 1} :memory {}))
      (agentmanager/save-agent! *conn* (assoc a :gene {:v 2} :memory {}))
      (let [v1 (agentmanager/get-or-create-agent! *conn* id 1)
            v2 (agentmanager/get-or-create-agent! *conn* id 2)
            v3 (agentmanager/get-or-create-agent! *conn* id 3)]
        (is (= 1 (:version v1)))
        (is (empty? (gene-content (:gene v1))) "v1 gene 业务内容为空（可有默认 :model-opts）")
        (is (= 2 (:version v2)))
        (is (= {:v 1} (gene-content (:gene v2))))
        (is (= 3 (:version v3)))
        (is (= {:v 2} (gene-content (:gene v3))))))))

(deftest save-agent!-requires-agent-id
  (testing "无 :agent-id 时抛出"
    (let [a (->agent nil nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"agent-id"
            (agentmanager/save-agent! *conn* a))))))

(deftest save-agent!-content-addressable
  (testing "相同 gene/memory 不重复占实体；与最新版本完全一致时不落新版本"
    (let [a (agentmanager/get-or-create-agent! *conn*)
          gene {:same :content}
          memory {:same :mem}
          a1 (agentmanager/save-agent! *conn* (assoc a :gene gene :memory memory))
          a2 (agentmanager/save-agent! *conn* (assoc a1 :gene gene :memory memory))
          a3 (agentmanager/save-agent! *conn* (assoc a2 :gene gene :memory memory))]
      (is (= 2 (:version a1)))
      (is (= 2 (:version a2)) "相同内容不落新版本")
      (is (= 2 (:version a3)))
      ;; 指定版本加载应得到相同内容（gene 可能带默认 :model-opts，只比业务内容）
      (let [v2 (agentmanager/get-or-create-agent! *conn* (:agent-id a) 2)]
        (is (= gene (gene-content (:gene v2))))
        (is (= memory (memory-content (:memory v2)))))
      ;; 无版本 4
      (is (nil? (agentmanager/get-or-create-agent! *conn* (:agent-id a) 4))))))

(deftest get-or-create-agent!-nonexistent-id-creates-agent
  (testing "不存在的 id 时用该 id 创建新 agent"
    (let [id (random-uuid)
          a (agentmanager/get-or-create-agent! *conn* id)]
      (is (some? a))
      (is (= id (:agent-id a)))
      (is (= 1 (:version a)))
      ;; 再次用同一 id 应加载到同一 agent
      (let [loaded (agentmanager/get-or-create-agent! *conn* id)]
        (is (= id (:agent-id loaded)))
        (is (= 1 (:version loaded)))))))
