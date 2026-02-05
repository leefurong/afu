(ns afu.handler
  (:require [reitit.ring :as ring]
            [ring.middleware.json :as ring-json]
            [ring.middleware.cors :as cors]
            [ring.util.io :as ring-io]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [agent :as agent]
            [agentmanager :as agentmanager]
            [afu.account.core :as account]
            [afu.db :as db]
            [conversation :as conversation]))

;; 目前聊天统一用这个固定 agent id（后续可改为按用户/会话）
(def ^:private fixed-agent-id #uuid "00000000-0000-0000-0000-000000000001")

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

(defn- event->ndjson-line
  "把 agent 事件变成 NDJSON 一行。类型与 thinking/content/done 区分：tool_call、tool_result。"
  [[k v] conversation-id]
  (json/generate-string
   (case k
     :thinking   {:type "thinking" :text v}
     :content    {:type "content" :text v}
     :tool-call  {:type "tool_call" :name (:name v) :code (:code v)}
     :tool-result {:type "tool_result" :result v}
     :done       (cond-> {:type "done"}
                   conversation-id (assoc :conversation_id (str conversation-id)))
     ;; 未知类型仍输出，便于调试
     (cond-> {:type (name k)}
       (some? v) (assoc :value v)))))

(defn- write-event-ch-to-stream
  "把 event ch 里的事件写成 NDJSON 到 out；收集 content 与 tool_call/tool_result 用于会话历史；:done 时保存 agent 并追加会话消息（含工具调用过程）。"
  [ch out conn conversation-id user-text]
  (let [content-acc (StringBuilder.)
        tool-steps (atom [])]
    (loop []
      (when-let [ev (a/<!! ch)]
        (let [k (first ev) v (second ev)]
          (case k
            :content (when (string? v)
                       (.append content-acc v))
            :tool-call (swap! tool-steps conj {:role "tool_call" :name (:name v) :content (str (:code v))})
            :tool-result (swap! tool-steps conj {:role "tool_result" :content (pr-str v)})
            :done (do
                    (when (and conn conversation-id (seq user-text))
                      (conversation/append-messages! conn conversation-id
                        (into [{:role "user" :content user-text}]
                              (conj (vec @tool-steps) {:role "assistant" :content (str content-acc)}))))
                    (when (and conn v (some? (:agent-id v)))
                      (agentmanager/save-agent! conn v))))
          nil)
        (.write out (.getBytes (str (event->ndjson-line ev conversation-id) "\n") "UTF-8"))
        (.flush out)
        (recur)))))

(defn- resolve-conversation-id
  "从 body 取 conversation_id（keyword 或 string），若为 uuid 则返回 UUID，否则 nil。"
  [body]
  (let [raw (or (get body :conversation_id) (get body "conversation_id"))]
    (when raw
      (if (instance? java.util.UUID raw)
        raw
        (try (java.util.UUID/fromString (str raw)) (catch Exception _ nil))))))

(defn chat-handler
  "POST /api/chat：支持会话历史。body 可带 conversation_id（可选）、text 或 messages。
   无 conversation_id 时新建会话；有则加载历史，拼上本条 user 消息后请求 agent；流式返回，:done 时写回会话并返回 conversation_id。"
  [request]
  (let [conn       db/conn
        body       (get request :body {})
        user-text  (last-user-text body)
        conv-id    (or (resolve-conversation-id body) (conversation/create! conn))
        history    (conversation/get-messages conn conv-id)
        ;; 发给 LLM 的只保留 user/assistant/system；tool_call、tool_result 仅用于展示
        api-msg    (filterv #(contains? #{"user" "assistant" "system"} (:role %)) history)
        full-msg   (conj api-msg {:role "user" :content (or user-text "")})
        current    (agentmanager/get-or-create-agent! conn fixed-agent-id)
        ch         (agent/chat current full-msg agent/chat-with-tools-opts)]
    {:status  200
     :headers {"Content-Type" "application/x-ndjson; charset=UTF-8"}
     :body    (ring-io/piped-input-stream
               (fn [out]
                 (try
                   (write-event-ch-to-stream ch out conn conv-id (or user-text ""))
                   (finally
                     (ring-io/close! out)))))}))

;; ---------------------------------------------------------------------------
;; 路由与 Ring Handler
;; ---------------------------------------------------------------------------

(def router
  (ring/router
   [["/api"
     ["/login" {:post login-handler}]
     ["/chat"  {:post chat-handler}]]]))

(defn ring-handler
  "无中间件的纯 Ring handler（供测试或内嵌使用）"
  []
  (ring/ring-handler router))

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
;; 对外入口：带 JSON + CORS 的 App
;; ---------------------------------------------------------------------------

(defn app
  "带 JSON 解析、JSON 响应与 CORS 的 Ring Handler。
   用于挂载到 Jetty 等服务器，处理 POST /api/login、POST /api/chat（流式）。"
  []
  (-> (ring-handler)
      wrap-json
      wrap-cors-policy))
