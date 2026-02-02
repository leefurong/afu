(ns llm
  "LLM 基础设施：供 agent 调用的推理能力。

  消息格式
  ---------
  messages 为 [{:role \"system\"|\"user\"|\"assistant\" :content ...} ...]。
  :content 可为：
  - 字符串：纯文本；
  - 或 content-parts 的 vector（多模态），如
    [{:type \"text\" :text \"...\"} {:type \"image_url\" :image_url {:url \"...\"}}]。

  多模态扩展
  ----------
  - 输入：上述 :content 为 vector 时即支持图文等多模态输入。
  - stream-chat 的 channel 上为 content delta：可为字符串（文本片段）或 map
    （如 {:type \"text\" :delta \"...\"}、{:type \"image\" :url \"...\"}），调用方按类型分发。
  - complete 返回的 :content 可为字符串或 vector of parts，与消息格式一致。

  配置（实现时）
  -------------
  从环境变量读取，如 MOONSHOT_API_KEY（Kimi）、LLM_BASE_URL、LLM_MODEL；opts 可覆盖。"
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 内部
;; ---------------------------------------------------------------------------

(defn- chat-url [client]
  (let [base (str/replace (str (:base-url client)) #"/+$" "")]
    (str base "/chat/completions")))

(defn- auth-header [client]
  (let [k (str/trim (str (or (:api-key client) "")))]
    (when (seq k)
      {"Authorization" (str "Bearer " k)})))

(defn- merge-opts [client opts]
  (merge {:model (:model client)
          :temperature (:temperature client)}
         (select-keys opts [:model :temperature :max-tokens])))

(defn- request-body [client messages opts]
  (let [merged (merge-opts client opts)]
    (cond-> {:messages messages}
      (:model merged) (assoc :model (:model merged))
      (contains? merged :temperature) (assoc :temperature (:temperature merged))
      (:max-tokens merged) (assoc :max_tokens (:max-tokens merged)))))

;; ---------------------------------------------------------------------------
;; 接口
;; ---------------------------------------------------------------------------

(defn make-client
  "根据 opts 构造一个 LLM 客户端（用于依赖注入，便于多 LLM 选择）。
  opts 可含 :api-key :base-url :model 等，未提供的从环境变量补全（如 MOONSHOT_API_KEY）。
  返回的 client 为不透明值，仅用于传给 stream-chat 与 complete。"
  [opts]
  (merge {:api-key (System/getenv "MOONSHOT_API_KEY")
          :base-url "https://api.moonshot.cn/v1"
          :model "kimi-k2-turbo-preview"}
         opts))

(defn- ensure-api-key [client]
  (when (str/blank? (str (or (:api-key client) "")))
    (throw (ex-info "MOONSHOT_API_KEY 未设置或为空，请在环境变量中配置" {:client (dissoc client :api-key)}))))

(defn complete
  "非流式完成：向 LLM 发送 messages，返回完整回复。
  返回形如 {:role \"assistant\" :content ...}；:content 可为字符串或 content-parts 的 vector（多模态）。
  实现时可扩展 :usage 等。opts 同 stream-chat。"
  [client messages opts]
  (ensure-api-key client)
  (let [url (chat-url client)
        headers (-> (auth-header client)
                    (assoc "Content-Type" "application/json"))
        body (request-body client messages opts)
        resp (http/post url
                        {:headers headers
                         :body (json/generate-string body)
                         :content-type :json
                         :accept :json
                         :throw-exceptions false})
        status (:status resp)
        body-parsed (when (:body resp) (json/parse-string (:body resp) true))]
    (if (and (>= status 200) (< status 300))
      (let [content (get-in body-parsed [:choices 0 :message :content])]
        {:role "assistant" :content (or content "")})
      (throw (ex-info "LLM complete request failed"
                      {:status status :body (:body resp)})))))

(defn stream-chat
  "流式对话：向 LLM 发送 messages，返回一个 channel。
  channel 上会依次 put 内容片段（content delta），put 完毕后 close。
  内容片段可为字符串（文本 delta）或 map（多模态时，如 {:type \"text\" :delta \"...\"}）。
  调用方（如 agent）可将每个片段包装为 [:content s] 等事件。
  opts 可含 :model :temperature :max-tokens 等，覆盖 client 默认。"
  [client messages opts]
  (ensure-api-key client)
  (let [ch (a/chan 16)
        url (chat-url client)
        headers (-> (auth-header client)
                    (assoc "Content-Type" "application/json"))
        body (assoc (request-body client messages opts) :stream true)]
    (a/thread
      (try
        (let [resp (http/post url
                              {:headers headers
                               :body (json/generate-string body)
                               :content-type :json
                               :as :stream
                               :throw-exceptions false})
              status (:status resp)
              stream (:body resp)]
          (if (and (>= status 200) (< status 300))
            (when stream
              (with-open [s stream
                          reader (io/reader s)]
                (loop [line (.readLine reader)]
                  (when line
                    (when (str/starts-with? line "data: ")
                      (let [payload (str/trim (subs line 5))]
                        (when (and (seq payload) (not= payload "[DONE]"))
                          (when-let [parsed (try (json/parse-string payload true)
                                                 (catch Exception _ nil))]
                            (when-let [delta-content (get-in parsed [:choices 0 :delta :content])]
                              (a/>!! ch delta-content))))))
                    (recur (.readLine reader))))))
            (throw (ex-info "LLM stream request failed" {:status status}))))
        (catch Exception _ nil)
        (finally
          (a/close! ch))))
    ch))
