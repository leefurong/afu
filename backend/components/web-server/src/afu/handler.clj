(ns afu.handler
  (:require [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.middleware.json :as ring-json]
            [ring.middleware.cors :as cors]
            [ring.util.io :as ring-io]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [agent :as agent]
            [agent.tools.execute-clojure.context :as exec-ctx]
            [agentmanager :as agentmanager]
            [afu.account.core :as account]
            [afu.db :as db]
            [conversation :as conversation]
            [resource-store]
            [sci-context-serde.core :as serde]
            [sci-context-serde.store :as store]
            [sci-context-serde.store.datomic :as store.datomic]))

;; 目前聊天统一用这个固定 agent id（后续可改为按用户/会话）
(def ^:private fixed-agent-id #uuid "00000000-0000-0000-0000-000000000001")

;; Resource store 由 afu.core 启动时注入，供 conversation 存/取消息正文
(def ^:private resource-store (atom nil))
(defn set-resource-store! [store] (reset! resource-store store))
(defn- get-resource-store [] @resource-store)

;; Memory store 由 afu.core 启动时注入，供记忆 API 使用。需要 VEC_EXTENSION_PATH + embed-fn。
(def ^:private memory-store (atom nil))
(defn set-memory-store! [store] (reset! memory-store store))
(defn get-memory-store [] @memory-store)

;; ---------------------------------------------------------------------------
;; 登录逻辑：调用 account 组件做真实校验
;; ---------------------------------------------------------------------------

(defn login-handler
  "处理 POST /api/login。
   从请求 Body 解析 JSON {:username \"...\" :password \"...\"}，
   使用 account/authenticate 校验，成功返回 200 + token，失败返回 401。"
  [request]
  (let [body (get request :body {})
        username (get body :username)
        password (get body :password)
        user (when (and (string? username) (string? password))
               (account/authenticate (db/db) username password))]
    (if user
      {:status 200
       :body   {:token   "fake-jwt-token-123456"
                :message "Login successful"
                :user    (select-keys user [:account/username :account/id])}}
      {:status 401
       :body   {:error "Invalid credentials"}})))

;; ---------------------------------------------------------------------------
;; 流式聊天：POST /api/chat（委托 agent 组件，流式写回）
;; ---------------------------------------------------------------------------

(defn- last-user-text
  "从请求 body 中取最后一条用户消息文本。支持 :text 或 :messages 数组最后一条。"
  [body]
  (or (get body :text)
      (when-let [messages (seq (get body :messages))]
        (let [last-msg (last messages)
              content  (get last-msg :content)
              parts    (get last-msg :parts)]
          (cond
            (string? content) content
            (seq parts)
            (apply str (keep #(when (= "text" (get % :type)) (get % :text)) parts))
            :else nil)))))

(def ^:private max-cross-signals-entries 500)

(defn- truncate-tool-result
  "对已知的大结果（如 cross-signals 的 golden_cross/death_cross）做条数截断，避免 NDJSON 单行过大。"
  [v]
  (if (and (map? v) (or (contains? v :golden_cross) (contains? v :death_cross)))
    (let [take-n    (fn [xs] (if (sequential? xs) (take max-cross-signals-entries (vec xs)) xs))
          new-g     (take-n (:golden_cross v))
          new-d     (take-n (:death_cross v))
          truncated? (or (> (count (vec (:golden_cross v))) (count new-g))
                        (> (count (vec (:death_cross v))) (count new-d)))]
      (cond-> (assoc v :golden_cross new-g :death_cross new-d)
        truncated? (assoc :truncated true
                         :truncated_message (str "结果条数过多已截断，仅保留前 " max-cross-signals-entries " 条；请按日期分页。"))))
    v))

(defn- event->ndjson-line
  "把 agent 事件变成 NDJSON 一行。类型与 thinking/content/done 区分：tool_call、tool_result。
   tool_result 过大会先截断再序列化，避免 OOM/服务器异常退出。
   当前分支由后端 root + selected-next-id 推出，前端统一 GET messages 即可，不再传 branch_head。"
  [[k v] conversation-id]
  (json/generate-string
   (case k
     :thinking   {:type "thinking" :text v}
     :content    {:type "content" :text v}
     :tool-call  {:type "tool_call" :name (:name v) :code (:code v)}
     :tool-result {:type "tool_result" :result (truncate-tool-result v)}
     :done       (cond-> {:type "done"}
                   conversation-id (assoc :conversation_id (str conversation-id)))
     ;; 未知类型仍输出，便于调试
     (cond-> {:type (name k)}
       (some? v) (assoc :value v)))))

(defn- tool-steps->api-shaped-messages
  "将流式收集的 [tool_call tool_result ...] 转为与 LLM API 一致：一条 assistant（tool_calls）+ N 条 role=tool。"
  [tool-steps]
  (let [pairs (partition 2 (vec tool-steps))
        tool-calls (mapv (fn [i [tc _tr]]
                           {:id (str "p-" i)
                            :type "function"
                            :function {:name (get tc :name "execute_clojure")
                                       :arguments (json/generate-string {:code (get tc :content "")})}})
                         (range) pairs)
        tool-msgs (mapv (fn [i [_tc tr]]
                          {:role "tool"
                           :tool_call_id (str "p-" i)
                           :content (get tr :content "")})
                        (range) pairs)]
    (into [{:role "assistant" :content "" :tool_calls tool-calls}]
          tool-msgs)))

(defn- write-event-ch-to-stream
  "把 event ch 里的事件写成 NDJSON 到 out；收集 content 与 tool_call/tool_result 用于会话历史；:done 时按 LLM 消息格式（assistant+tool_calls, role=tool）追加。
   root-branch? 为 true 时从根分叉，append 时 prev 为 nil。"
  [ch out conn conversation-id user-text branch-head update-main-head? root-branch?]
  (let [content-acc (StringBuilder.)
        tool-steps (atom [])]
    (loop []
      (when-let [ev (a/<!! ch)]
        (let [k (first ev) v (second ev)]
          (println "[chat] event:" k (case k :content (str "len=" (count (str v))) :tool-call (str "name=" (:name v)) :tool-result "result" :done "done" :thinking (str "text=" (pr-str v)) :else ""))
          (case k
            :thinking nil
            :content (when (string? v)
                       (.append content-acc v))
            :tool-call (swap! tool-steps conj {:role "tool_call" :name (:name v) :content (str (:code v))})
            :tool-result (swap! tool-steps conj {:role "tool_result" :content (pr-str v)})
            :done (try
                    (when (and conn conversation-id (seq user-text))
                      (let [steps (vec @tool-steps)
                            api-shaped (when (seq steps)
                                         (tool-steps->api-shaped-messages steps))
                            final-content (str content-acc)
                            new-msgs (into [{:role "user" :content user-text}]
                                           (cond-> (or api-shaped [])
                                             (seq final-content) (conj {:role "assistant" :content final-content})))
                            new-ids (if root-branch?
                                      (conversation/append-messages! conn conversation-id new-msgs nil update-main-head? (get-resource-store) {:root-branch? true})
                                      (conversation/append-messages! conn conversation-id new-msgs branch-head update-main-head? (get-resource-store)))]
                        (when (and (seq steps) (seq new-ids))
                          (let [ctx (exec-ctx/get-or-create-ctx conversation-id)]
                            (doseq [i (range (/ (count steps) 2))]
                              (let [tc (nth steps (* 2 i))
                                    name (get tc :name "")]
                                (when (= name "execute_clojure")
                                  (let [msg-id (nth new-ids (+ 2 i) nil)
                                        code (str (get tc :content ""))]
                                    (when msg-id
                                      (try
                                        (let [snapshot (serde/serialize ctx {:recent-code code})
                                              backend (store.datomic/->datomic-store conn [:message/id msg-id] :message/sci-context-snapshot)]
                                          (store/save-snapshot! backend snapshot))
                                        (catch Throwable ex
                                          (println "[chat] save sci-context-snapshot failed:" (.getMessage ex)
                                                   "| ex-data:" (ex-data ex)))))))))))))
                    (when (and conn v (some? (:agent-id v)))
                      (agentmanager/save-agent! conn v))
                    (catch Throwable t
                      (println "[chat] :done persist failed (e.g. Item too large):" (.getMessage t)))))
          (try
            (let [line (str (event->ndjson-line ev conversation-id) "\n")]
              (.write out (.getBytes line "UTF-8"))
              (.flush out))
            (catch Throwable t
              (println "[chat] stream write failed (event=" (first ev) "):" (.getMessage t))))
          nil)
        (recur)))))

(defn- history->api-messages
  "把 DB 里存的会话历史转成 LLM API 的 messages。存储与 API 一致：user/assistant（含 tool_calls）/tool，直接透传。"
  [history]
  (when (seq history)
    (mapv (fn [msg]
            (case (get msg :role "")
              "user"    (select-keys msg [:role :content])
              "system"  (select-keys msg [:role :content])
              "assistant" (cond-> {:role "assistant" :content (get msg :content "")}
                            (seq (get msg :tool_calls)) (assoc :tool_calls (get msg :tool_calls)))
              "tool"    (select-keys msg [:role :tool_call_id :content])
              nil))
          (filter (fn [m] (#{"user" "system" "assistant" "tool"} (get m :role ""))) history))))

(defn- resolve-conversation-id
  "从 body 取 conversation_id（keyword 或 string），若为 uuid 则返回 UUID，否则 nil。"
  [body]
  (let [raw (or (get body :conversation_id) (get body "conversation_id"))]
    (when raw
      (if (instance? java.util.UUID raw)
        raw
        (try (java.util.UUID/fromString (str raw)) (catch Exception _ nil))))))

(defn- resolve-prev-message-id
  "从 body 取 prev_message_id（fork 时「在这条之后追加」的 message id），UUID 或 nil。"
  [body]
  (let [raw (or (get body :prev_message_id) (get body "prev_message_id"))]
    (when raw
      (if (instance? java.util.UUID raw)
        raw
        (try (java.util.UUID/fromString (str raw)) (catch Exception _ nil))))))

(defn- resolve-branch-from-root
  "从 body 取 branch_from_root（编辑第一条消息时从根分叉），boolean。"
  [body]
  (boolean (or (get body :branch_from_root) (get body "branch_from_root"))))

(defn chat-handler
  "POST /api/chat：支持会话历史与 fork。body 可带 conversation_id（可选）、prev_message_id（可选，fork 时指定「在此之后追加」）、text 或 messages。
   无 conversation_id 时新建会话；有则按 prev_message_id 或主分支 head 加载该分支历史，拼上本条 user 消息后请求 agent；流式返回，:done 时写回并返回 conversation_id。"
  [request]
  (let [conn       db/conn
        body       (get request :body {})
        user-text  (last-user-text body)
        _          (println "[chat] request body keys:" (keys body) "user-text len:" (count (str user-text)) "conv-id from body?" (boolean (resolve-conversation-id body)))
        conv-id    (or (resolve-conversation-id body) (conversation/create! conn (get-resource-store)))
        prev-msg-id (resolve-prev-message-id body)
        branch-from-root? (resolve-branch-from-root body)
        ;; 当前分支：branch_from_root 时从根分叉；否则 prev_message_id；否则由 root + selected-next-id 推出
        branch-head (cond branch-from-root? nil
                          prev-msg-id prev-msg-id
                          :else (conversation/get-current-head conn conv-id))
        _          (println "[chat] conv-id:" conv-id "branch-head:" branch-head "fork?" (boolean prev-msg-id) "branch-from-root?" branch-from-root?)
        history    (conversation/get-messages conn conv-id branch-head (get-resource-store))
        ;; 若历史中有 execute_clojure 的 tool 结果（带 sci-context-snapshot），恢复该条对应的 ctx 供本轮使用
        _          (let [with-snapshot (filter :sci-context-snapshot history)
                         last-snapshot-msg (last with-snapshot)]
                     (println "[chat] load-ctx: history count =" (count history)
                              "| messages with sci-context-snapshot =" (count with-snapshot)
                              "| their msg-ids =" (mapv :id with-snapshot))
                     (if (empty? with-snapshot)
                       (println "[chat] load-ctx: no message with sci-context-snapshot, skip restore")
                       (let [msg-id (:id last-snapshot-msg)
                             backend (store.datomic/->datomic-store conn [:message/id msg-id] :message/sci-context-snapshot)
                             snapshot (store/load-snapshot backend)]
                         (println "[chat] load-ctx: last snapshot msg-id =" msg-id
                                  "| load-snapshot result =" (if snapshot
                                                               (str "ok, bindings count=" (count (get snapshot :bindings {})))
                                                               "nil"))
                         (if snapshot
                           (do (exec-ctx/restore-ctx! conv-id snapshot)
                               (println "[chat] load-ctx: restored ctx for conv-id =" conv-id))
                           (println "[chat] load-ctx: snapshot nil, did not restore")))))
        ;; 发给 LLM 的是完整真实历史（含工具调用顺序），转为 API 格式
        api-msg    (history->api-messages history)
        full-msg   (conj (vec api-msg) {:role "user" :content (or user-text "")})
        _          (println "[chat] full-msg count:" (count full-msg) "calling agent/chat ...")
        current    (agentmanager/get-or-create-agent! conn fixed-agent-id)
        opts       (assoc (agent/chat-with-tools-opts) :tool-ctx {:conversation-id conv-id})
        ch         (agent/chat current full-msg opts)
        ;; 流式写回时需传入 branch-head 与是否更新主分支，以便 append 挂在正确 prev 上
        _          (println "[chat] agent/chat returned ch, returning 200 + stream")]
    {:status  200
     :headers {"Content-Type" "application/x-ndjson; charset=UTF-8"}
     :body    (ring-io/piped-input-stream
               (fn [out]
                 (try
                   (println "[chat] stream producer started, reading from ch ...")
                   ;; 编辑或续写后都切换到新写的那条线：update-main-head? 恒为 true，让父的 selected-next-id 指向新消息
                   (write-event-ch-to-stream ch out conn conv-id (or user-text "") branch-head
                                             true
                                             branch-from-root?)
                   (println "[chat] stream producer finished (ch closed)")
                   (catch Throwable t
                     (println "[chat] stream producer error:" (.getMessage t))
                     (throw t))
                   (finally
                     (ring-io/close! out)))))}))

;; ---------------------------------------------------------------------------
;; 会话列表与历史：GET /api/conversations、GET /api/conversations/:id/messages
;; ---------------------------------------------------------------------------

(defn- temporal-to-iso-str
  "将 Date 或 Instant 转为 ISO 字符串，避免序列化时 NPE。只返回字符串或 nil。"
  [t]
  (when t
    (let [millis (cond (instance? java.util.Date t)   (.getTime ^java.util.Date t)
                       (instance? java.time.Instant t) (.toEpochMilli ^java.time.Instant t)
                       :else nil)]
      (when (and millis (number? millis))
        (str (java.time.Instant/ofEpochMilli (long millis)))))))

(defn list-conversations-handler
  "GET /api/conversations：返回近期会话列表。body 仅用字符串 key，避免 ring-json 序列化抛错导致 Response nil。"
  [_request]
  (try
    (let [conn  db/conn
          items (conversation/list-recent conn (get-resource-store))
          ;; 仅字符串 key + 字符串/nil 值，保证 cheshire 序列化不抛错
          body  (mapv (fn [m]
                       {"id"         (str (:id m))
                        "title"      (str (or (:title m) ""))
                        "updated_at" (or (temporal-to-iso-str (:updated_at m)) nil)})
                     items)]
      {:status 200
       :body   body})
    (catch Throwable t
      {:status 500 :body {"error" (str (.getMessage t))}})))

(defn get-conversation-messages-handler
  "GET /api/conversations/:id/messages：返回该会话当前分支消息列表。
   当前分支由 root + 各 message 的 selected-next-id 推出，统一经 get-current-head。"
  [request]
  (let [conn    db/conn
        id-str  (get-in request [:path-params :id])
        raw?    (get-in request [:query-params "raw"])
        conv-id (when id-str
                  (try (java.util.UUID/fromString id-str) (catch Exception _ nil)))]
    (if-not conv-id
      {:status 400 :body {:error "Invalid conversation id"}}
      (let [head (conversation/get-current-head conn conv-id)
            msgs (conversation/get-messages conn conv-id head (get-resource-store))]
        {:status 200
         :body   (if raw?
                   (mapv (fn [m] (update m :id str)) msgs)
                   (mapv (fn [m]
                           (cond-> {:id      (str (:id m))
                                    :role    (get m :role "")
                                    :content (get m :content "")
                                    :can_left (boolean (get m :can-left?))
                                    :can_right (boolean (get m :can-right?))
                                    :sibling_index (get m :sibling-index 1)
                                    :sibling_total (get m :sibling-total 1)}
                             (= (get m :role "") "tool")
                             (assoc :tool_call_id (get m :tool_call_id ""))
                             (and (= (get m :role "") "assistant") (seq (get m :tool_calls)))
                             (assoc :tool_calls (get m :tool_calls))))
                         msgs))}))))

(defn- switch-branch-handler
  "POST /api/conversations/:id/messages/:message_id/switch-left 或 switch-right。
   找到该 message 的父，把父的 selected-next-id 换成左/右兄弟；然后返回当前分支最新消息列表。"
  [request delta]
  (let [conn       db/conn
        id-str     (get-in request [:path-params :id])
        msg-id-str (get-in request [:path-params :message_id])
        raw?       (get-in request [:query-params "raw"])
        conv-id    (when id-str (try (java.util.UUID/fromString id-str) (catch Exception _ nil)))
        message-id (when msg-id-str (try (java.util.UUID/fromString msg-id-str) (catch Exception _ nil)))]
    (if (or (nil? conv-id) (nil? message-id))
      {:status 400 :body {"error" "Invalid conversation id or message id"}}
      (if (conversation/change-next! conn delta message-id)
        (let [tip (conversation/get-current-head conn conv-id)
              msgs (conversation/get-messages conn conv-id tip (get-resource-store))]
          {:status 200
           :body   (if raw?
                     (mapv (fn [m] (update m :id str)) msgs)
                     (mapv (fn [m]
                             (cond-> {:id      (str (:id m))
                                      :role    (get m :role "")
                                      :content (get m :content "")
                                      :can_left (boolean (get m :can-left?))
                                      :can_right (boolean (get m :can-right?))
                                      :sibling_index (get m :sibling-index 1)
                                      :sibling_total (get m :sibling-total 1)}
                               (= (get m :role "") "tool")
                               (assoc :tool_call_id (get m :tool_call_id ""))
                               (and (= (get m :role "") "assistant") (seq (get m :tool_calls)))
                               (assoc :tool_calls (get m :tool_calls))))
                           msgs))})
        {:status 400 :body {"error" "No sibling in that direction"}}))))

(defn switch-left-handler [request]
  (switch-branch-handler request -1))

(defn switch-right-handler [request]
  (switch-branch-handler request 1))

;; ---------------------------------------------------------------------------
;; Memory API（当前返回假数据，后续接入 memory-store）
;; ---------------------------------------------------------------------------

(def ^:private fake-memories
  "假数据，用于打通 UI -> handler 路径。"
  (mapv (fn [[id content mins-ago]]
          {:id id
           :content content
           :created_at (str (java.time.Instant/ofEpochMilli
                            (- (System/currentTimeMillis) (* mins-ago 60 1000))))})
        [["1" "用户正在开发一个以客户为中心的智能体项目，以智能客户代理和真人居间团队相结合的商业模式为主，计划以高科技智能背景为基础，提供软件开发、采购代理、咨询等多项服务。" 5]
         ["2" "用户偏好使用 Next.js 与 TypeScript 进行前端开发，后端使用 Clojure。" (* 2 60)]
         ["3" "用户关注股票数据与均线金叉等信号验证。" (* 24 60)]
         ["4" "用户常使用 Cursor 与 Calva 进行开发。" (* 2 24 60)]
         ["5" "用户对无限记忆与长程上下文能力有需求。" (* 3 24 60)]
         ["6" "项目代号阿福，目标是打造能听懂话、能跑代码的智能体。" (* 4 24 60)]
         ["7" "技术栈包含 SCI 沙盒、Datomic、resource-store。" (* 5 24 60)]
         ["8" "对话与消息正文存 resource-store，Datomic 只存结构。" (* 6 24 60)]
         ["9" "用户希望记忆支持向量检索与自然语言搜索。" (* 7 24 60)]
         ["10" "黄伟伟要的全套功能可通过聊天窗口与 SCI 函数实现。" (* 8 24 60)]
         ["11" "短信通知可附带通向聊天线索的 URL。" (* 9 24 60)]
         ["12" "定时任务让用户设定未来发生的事件。" (* 10 24 60)]
         ["13" "福聊中话题独立但信息互通。" (* 11 24 60)]
         ["14" "后端使用 reitit 做路由，next.jdbc 与 honey.sql。" (* 12 24 60)]
         ["15" "前端使用 Shadcn UI 与 Tailwind。" (* 13 24 60)]
         ["16" "消息可编辑重发，支持从根分叉或接在选中消息后。" (* 14 24 60)]
         ["17" "K 线数据有缓存与增量更新逻辑。" (* 15 24 60)]
         ["18" "tushare 用于获取股票行情。" (* 16 24 60)]
         ["19" "Agent 可主动发言，基于定时任务。" (* 17 24 60)]
         ["20" "Code is Data 为第一性原理。" (* 18 24 60)]]))

(defn- fake-memory-text-match? [content q]
  (let [q (str/trim q)
        terms (filter seq (str/split q #"\s+"))]
    (or (empty? terms)
        (every? #(str/includes? content %) terms))))

(defn list-memory-handler
  "GET /api/memory：返回记忆列表。query: q(可选), page(默认1), page-size(默认10)。
   当前返回假数据。"
  [request]
  (let [q         (get-in request [:query-params "q"])
        page      (max 1 (Long/parseLong (str (get-in request [:query-params "page"] "1"))))
        page-size (max 1 (min 100 (Long/parseLong (str (get-in request [:query-params "page-size"] "10")))))
        filtered  (if (str/blank? q)
                    fake-memories
                    (filterv #(fake-memory-text-match? (:content %) q) fake-memories))
        sorted    (sort-by :created_at #(compare %2 %1) filtered)
        total     (count sorted)
        offset    (* (dec page) page-size)
        items     (take page-size (drop offset sorted))]
    {:status 200
     :body   {"items"       (mapv (fn [m] {"id" (:id m) "content" (:content m) "created_at" (:created_at m)}) items)
              "total_count" total}}))

(defn add-memory-handler
  "POST /api/memory：新增记忆。body: {:content \"...\"}。当前返回假 id。"
  [request]
  (let [body   (get request :body {})
        content (or (get body :content) (get body "content") "")]
    (if (str/blank? content)
      {:status 400 :body {"error" "content is required"}}
      {:status 200
       :body   {"id" (str (random-uuid))}})))

(defn update-memory-handler
  "PUT /api/memory/:id：更新记忆。body: {:content \"...\"}。当前假实现，直接返回成功。"
  [request]
  (let [_ (get-in request [:path-params :id])
        body    (get request :body {})
        content (or (get body :content) (get body "content") "")]
    (if (str/blank? content)
      {:status 400 :body {"error" "content is required"}}
      {:status 200
       :body   {"ok" true}})))

(defn delete-memory-handler
  "DELETE /api/memory/:id：删除记忆。当前假实现，直接返回成功。"
  [request]
  (let [_ (get-in request [:path-params :id])]
    {:status 200
     :body   {"ok" true}}))

;; ---------------------------------------------------------------------------
;; 路由与 Ring Handler
;; ---------------------------------------------------------------------------

(def router
  (ring/router
   [["/api"
     ["/login" {:post login-handler}]
     ["/chat"  {:post chat-handler}]
     ["/conversations" {:get list-conversations-handler}]
     ["/conversations/:id/messages" {:get get-conversation-messages-handler}]
     ["/conversations/:id/messages/:message_id/switch-left" {:post switch-left-handler}]
     ["/conversations/:id/messages/:message_id/switch-right" {:post switch-right-handler}]
     ["/memory" {:get list-memory-handler
                 :post add-memory-handler}]
     ["/memory/:id" {:put update-memory-handler
                     :delete delete-memory-handler}]]]))

(defn ring-handler
  "无中间件的纯 Ring handler。无匹配路由时返回 404，避免返回 nil 导致 Response map is nil。"
  []
  (ring/ring-handler
   router
   (fn [_] {:status 404 :headers {"Content-Type" "application/json"} :body "{\"error\":\"Not found\"}"})))

;; ---------------------------------------------------------------------------
;; 中间件：JSON Body 解析 + JSON 响应 + CORS
;; ---------------------------------------------------------------------------

(def ^:private json-options
  {:keywords? true})

(defn wrap-json
  "解析请求 Body 为 JSON，并将响应的 :body（map/vector）序列化为 JSON。流式 body（如 InputStream）由 ring-json 原样返回。"
  [handler]
  (-> handler
      (ring-json/wrap-json-body json-options)
      (ring-json/wrap-json-response json-options)))

(defn wrap-cors-policy
  "允许前端 localhost:3000 跨域访问，并允许常用 Methods / Headers。"
  [handler]
  (cors/wrap-cors handler
                  :access-control-allow-origin   [#"http://localhost:3000" #"http://127\.0\.0\.1:3000"]
                  :access-control-allow-methods  [:get :post :put :delete :options]
                  :access-control-allow-headers  ["Content-Type" "Authorization" "Accept"]
                  :access-control-expose-headers ["Authorization"]))

;; ---------------------------------------------------------------------------
;; 异常兜底：防止 handler 或 wrap-json 抛错导致 Response map is nil
;; ---------------------------------------------------------------------------

(defn wrap-catch-response
  "最外层：捕获异常返回 500；若 handler 返回 nil 则返回 404，避免 Response map is nil。"
  [handler]
  (fn [request]
    (try
      (let [response (handler request)]
        (if (nil? response)
          {:status 404 :headers {"Content-Type" "application/json"} :body "{\"error\":\"Not found\"}"}
          response))
      (catch Throwable t
        {:status  500
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string {"error" (str (.getMessage t))})}))))

;; ---------------------------------------------------------------------------
;; 对外入口：带 JSON + CORS 的 App
;; ---------------------------------------------------------------------------

(defn app
  "带 JSON 解析、JSON 响应与 CORS 的 Ring Handler。
   用于挂载到 Jetty 等服务器，处理 POST /api/login、POST /api/chat（流式）。"
  []
  (-> (ring-handler)
      wrap-json
      wrap-cors-policy
      wrap-catch-response))
