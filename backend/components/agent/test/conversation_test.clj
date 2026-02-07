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
  (testing "append-messages! 追加后 get-messages 能取到（含 :id :can-left? :can-right?）"
    (let [id (conv/create! *conn*)
          user-msg {:role "user" :content "你好"}
          assistant-msg {:role "assistant" :content "你好，有什么可以帮你？"}]
      (conv/append-messages! *conn* id [user-msg assistant-msg])
      (let [msgs (conv/get-messages *conn* id)]
        (is (= [user-msg assistant-msg] (mapv #(select-keys % [:role :content]) msgs)))
        (is (every? #(and (contains? % :id) (contains? % :can-left?) (contains? % :can-right?)) msgs))))))

(deftest append-messages!-accumulates
  (testing "多次 append 按顺序累积"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
      (is (= [{:role "user" :content "1"}
              {:role "assistant" :content "a"}
              {:role "user" :content "2"}
              {:role "assistant" :content "b"}]
             (mapv #(select-keys % [:role :content]) (conv/get-messages *conn* id)))))))

(deftest append-messages!-empty-no-op
  (testing "空列表 append 不报错、不改变内容"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "x"}])
      (conv/append-messages! *conn* id [])
      (is (= [{:role "user" :content "x"}] (mapv #(select-keys % [:role :content]) (conv/get-messages *conn* id)))))))

(deftest fork-append-after-prev
  (testing "在 prev 后追加且 update-main-head? false 时，主分支 head 不变（fork 语义）"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (let [main-head (conv/get-head *conn* id)]
        (conv/append-messages! *conn* id [{:role "user" :content "fork"} {:role "assistant" :content "fork-reply"}]
                              main-head false)
        (is (= main-head (conv/get-head *conn* id)) "主分支 head 应不变")
        (is (= 2 (count (conv/get-messages *conn* id))) "主分支仍为 2 条")))))

(deftest delete-message-relinks
  (testing "delete-message! 删除 head 后链表重连，主分支 head 变为前一条"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
      (let [head (conv/get-head *conn* id)
            hist (conv/get-messages *conn* id)]
        (is (= 4 (count hist)))
        (conv/delete-message! *conn* head) ;; 删除最后一条 "b"
        (let [after (conv/get-messages *conn* id)]
          (is (= 3 (count after)))
          (is (= "2" (get (last after) :content))))))))

(deftest delete-conversation-removes-all
  (testing "delete-conversation! 删除会话下所有 message 及会话本身"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "x"}])
      (is (= 1 (count (conv/get-messages *conn* id))))
      (conv/delete-conversation! *conn* id)
      (is (= [] (conv/get-messages *conn* id)))
      (is (nil? (conv/get-head *conn* id))))))

(deftest compute-head-from
  (testing "从 head 开始 compute-head-from 返回自身；顺藤摸瓜沿 selected-next-id 走到无后继"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (let [h (conv/get-head *conn* id)]
        (is (= h (conv/compute-head-from *conn* h)) "从 head 算 head 即自身"))
      (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
      (let [h (conv/get-head *conn* id)]
        (is (= h (conv/compute-head-from *conn* h)) "续写后从 head 算仍为自身")))))

(deftest get-messages-includes-id-and-can-left-right
  (testing "get-messages 每条含 :id :can-left? :can-right?；有多个后继的那条上 can-left? 或 can-right? 依当前选中是否最左/最右"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (let [a-id (conv/get-head *conn* id)]
        (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
        (conv/append-messages! *conn* id [{:role "user" :content "fork"} {:role "assistant" :content "fr"}] a-id false)
        (let [main-msgs (conv/get-messages *conn* id)
              fork-head (conv/compute-head-from *conn* a-id)
              fork-msgs (conv/get-messages *conn* id fork-head)]
          (is (every? #(contains? % :id) main-msgs))
          ;; 只有「有多个后继」的那条（a）会有 can-left?/can-right? 为 true；当前选中 fork 时该条 can-left? true
          (is (true? (some :can-left? fork-msgs)) "有 fork 的那条上 can-left? 为 true（当前选中最右，可向左）")
          (is (or (some :can-left? main-msgs) (some :can-right? main-msgs)) "主分支上有一条可左或可右"))))))

(deftest left!-and-right!
  (testing "fork 后 left! / right! 切换选中的后继，返回新选中的 id；无左/右时返回 nil"
    (let [id (conv/create! *conn*)]
      (conv/append-messages! *conn* id [{:role "user" :content "1"} {:role "assistant" :content "a"}])
      (let [a-id (conv/get-head *conn* id)]
        (conv/append-messages! *conn* id [{:role "user" :content "2"} {:role "assistant" :content "b"}])
        (conv/append-messages! *conn* id [{:role "user" :content "fork"} {:role "assistant" :content "fr"}]
                              a-id false)
        ;; fork 点在 a，next-ids = [2 的 id, fork 的 id]，ordered 按时间 = [2, fork]，当前 selected = fork
        (let [fork-head (conv/compute-head-from *conn* a-id)
              main-head (conv/get-head *conn* id)]
          (is (some? fork-head) "fork 分支有 head")
          (let [left-id (conv/left! *conn* fork-head)]
            (is (some? left-id) "向左切到主分支，返回被选中的后继 id（主分支首条 2）")
            (is (= (conv/compute-head-from *conn* left-id) main-head) "从该 id 顺藤摸瓜得到主分支 head"))
          (let [again (conv/left! *conn* (conv/compute-head-from *conn* a-id))]
            (is (nil? again) "已在最左，再 left 返回 nil"))
          (let [right-id (conv/right! *conn* main-head)]
            (is (some? right-id) "从主分支 right 切到 fork，返回 fork 首条 id"))
          (let [again (conv/right! *conn* (conv/compute-head-from *conn* a-id))]
            (is (nil? again) "已在最右，再 right 返回 nil")))))))
