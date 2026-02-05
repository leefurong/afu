(ns conversation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [conversation :as conv]
            [datomic.client.api :as d]))

(def ^:private test-db-name "afu-conversation-test")

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
    (conv/ensure-schema! conn)
    (binding [*conn* conn]
      (f))
    (delete-test-db)))

(use-fixtures :each with-test-db)

(deftest create!-returns-uuid
  (testing "create! 返回新会话的 uuid"
    (let [id (conv/create! *conn*)]
      (is (instance? java.util.UUID id)))))

(deftest get-messages-empty-after-create
  (testing "新建会话后 get-messages 返回空 vector"
    (let [id (conv/create! *conn*)]
      (is (= [] (conv/get-messages *conn* id))))))

(deftest get-messages-nonexistent
  (testing "不存在的 conversation-id 返回空 vector"
    (is (= [] (conv/get-messages *conn* (random-uuid))))))

(deftest append-messages!
  (testing "append-messages! 追加后 get-messages 能取到"
    (let [id (conv/create! *conn*)
          user-msg {:role "user" :content "你好"}
          assistant-msg {:role "assistant" :content "你好，有什么可以帮你？"}]
      (conv/append-messages! *conn* id [user-msg assistant-msg])
      (is (= [user-msg assistant-msg] (conv/get-messages *conn* id))))))

(deftest append-messages!-accumulates
  (testing "多次 append 按顺序累积"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
      (is (= [{:role "user" :content "1"}
              {:role "assistant" :content "a"}
              {:role "user" :content "2"}
              {:role "assistant" :content "b"}]
             (conv/get-messages *conn* id))))))

(deftest append-messages!-empty-no-op
  (testing "空列表 append 不报错、不改变内容"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "x"}])
      (conv/append-messages! *conn* id [])
      (is (= [{:role "user" :content "x"}] (conv/get-messages *conn* id))))))
