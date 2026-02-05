(ns llm
  "LLM 基础设施：供 agent 调用的推理能力。

  消息格式
  ---------
  messages 为 [{:role \"system\"|\"user\"|\"assistant\" :content ...} ...]。
  :content 可为：
  - 字符串：纯文本；
  - 或 content-parts 的 vector（多模态），如
    [{:type \"text\" :text \"...\"} {:type \"image_url\" :image_url {:url \"...\"}}]。

  Tools（工具调用，OpenAI/Moonshot 兼容）
  ---------------------------------------
  - 请求：opts 中传 :tools，为 vector of map，每项含 :type :function。
    :function 含 :name :description :parameters（JSON Schema，含 :type \"object\"、:properties、:required）。
  - 非流式 complete：若模型返回工具调用，choices[0].message 会带 tool_calls。
    本命名空间返回的 message 保留 :content 与 :tool_calls（有则存在）。
    :tool_calls 为 [{:id \"...\" :type \"function\" :function {:name \"...\" :arguments \"{\\\"key\\\":\\\"value\\\"}\"}}]，
    :arguments 为 JSON 字符串，需自行解析。
  - 流式 stream-chat：暂不解析 tool_calls，仅推送 content delta。

  参考文档：https://platform.moonshot.cn 的 API 文档（工具调用）。"
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci-env :as sci-env]))

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
      (:max-tokens merged) (assoc :max_tokens (:max-tokens merged))
      (seq (:tools opts)) (assoc :tools (:tools opts)))))

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

;; ---------------------------------------------------------------------------
;; Tools 定义与解析（供 agent 工具循环使用）
;; ---------------------------------------------------------------------------

(def ^:private env-whitelist-desc
  (str "仅可读取以下环境变量：" (str/join "、" (sort sci-env/env-whitelist)) "，其他名称返回 nil。"))

(def execute-clojure-tool
  "供 complete opts :tools 使用的「执行 Clojure 代码」工具定义（OpenAI/Moonshot 兼容）。"
  (let [desc (str "在沙箱中执行一段 Clojure 代码并返回结果。除标准 Clojure 外，沙箱内还提供：1) http 命名空间：(http/get \"url\")、(http/post \"url\" {:body \"...\" :headers {...}})，返回 {:status N :headers {...} :body \"...\"} 或 {:error \"...\"}；2) json 命名空间：(json/parse-string \"...\")、(json/write-str {...})；3) env 命名空间：(env/get-env \"VAR_NAME\") 读取环境变量，" env-whitelist-desc " 用于计算、数据处理、调用外部 API（配合 http + json，密钥用 env/get-env 读取上述变量）等。入参为 code（字符串）。返回为 {:ok 结果} 或 {:error 错误信息}，可能带 :out（标准输出）。")
        code-desc (str "要执行的 Clojure 代码。可写纯计算如 (+ 1 2)；或 HTTP+JSON，如 (http/post \"...\" {:body (json/write-str {:token (env/get-env \"变量名\")}) :headers {\"Content-Type\" \"application/json\"}})，变量名仅限：" (str/join "、" (sort sci-env/env-whitelist)) "。直接使用 json/*、env/get-env，不要 require 其他库。")]
    {:type "function"
     :function {:name "execute_clojure"
                :description desc
                :parameters {:type "object"
                             :properties {:code {:type "string" :description code-desc}}
                             :required ["code"]}}}))

(defn parse-tool-arguments
  "解析 tool_call 的 :arguments 字符串为 map。空串或非法 JSON 时返回 nil。"
  [arguments]
  (if (str/blank? (str arguments))
    nil
    (try
      (json/parse-string (str arguments) true)
      (catch Exception _ nil))))

(defn- ensure-api-key [client]
  (when (str/blank? (str (or (:api-key client) "")))
    (throw (ex-info "MOONSHOT_API_KEY 未设置或为空，请在环境变量中配置" {:client (dissoc client :api-key)}))))

(defn complete
  "非流式完成：向 LLM 发送 messages，返回完整回复。
  opts 可含 :tools（工具定义列表）、:model :temperature :max-tokens 等。
  返回 message 形如 {:role \"assistant\" :content \"...\"}，当模型发起工具调用时还会带 :tool_calls。
  :tool_calls 为 [{:id \"...\" :type \"function\" :function {:name \"...\" :arguments \"JSON 字符串\"}}]，
  其中 :arguments 需调用方用 cheshire/parse-string 解析。无 tool_calls 时不带该键。"
  [client messages opts]
  (ensure-api-key client)
  (let [url (chat-url client)
        headers (-> (auth-header client)
                    (assoc "Content-Type" "application/json"))
        body (request-body client messages opts)]
    (println "[llm] complete: POST" url "messages count =" (count (:messages body)))
    (let [resp (http/post url
                          {:headers headers
                           :body (json/generate-string body)
                           :content-type :json
                           :accept :json
                           :throw-exceptions false})
          status (:status resp)
          body-parsed (when (:body resp) (json/parse-string (:body resp) true))]
      (println "[llm] complete: response status =" status)
      (if (and (>= status 200) (< status 300))
        (let [msg (get-in body-parsed [:choices 0 :message])
              content (get msg :content)
              tool-calls (get msg :tool_calls)]
          (cond-> {:role "assistant" :content (or content "")}
            (seq tool-calls) (assoc :tool_calls tool-calls)))
        (throw (ex-info "LLM complete request failed"
                        {:status status :body (:body resp)}))))))

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
