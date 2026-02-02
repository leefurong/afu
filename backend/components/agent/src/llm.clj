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
  从环境变量读取，如 LLM_API_KEY、LLM_BASE_URL、LLM_MODEL；opts 可覆盖。"
  (:require [clojure.core.async :as a]))

;; ---------------------------------------------------------------------------
;; 接口
;; ---------------------------------------------------------------------------

(defn make-client
  "根据 opts 构造一个 LLM 客户端（用于依赖注入，便于多 LLM 选择）。
  opts 可含 :api-key :base-url :model 等，未提供的从环境变量补全。
  返回的 client 为不透明值，仅用于传给 stream-chat 与 complete。"
  [_opts]
  ;; TODO: 从 env 与 opts 合并配置，构造并返回 client
  {})

(defn stream-chat
  "流式对话：向 LLM 发送 messages，返回一个 channel。
  channel 上会依次 put 内容片段（content delta），put 完毕后 close。
  内容片段可为字符串（文本 delta）或 map（多模态时，如 {:type \"text\" :delta \"...\"}）。
  调用方（如 agent）可将每个片段包装为 [:content s] 等事件。
  opts 可含 :model :temperature :max-tokens 等，覆盖 client 默认。"
  [_client _messages _opts]
  ;; TODO: 使用 client 发起流式请求，将内容 delta 依次 put 到 ch，最后 close
  (let [ch (a/chan 16)]
    (a/close! ch)
    ch))

(defn complete
  "非流式完成：向 LLM 发送 messages，返回完整回复。
  返回形如 {:role \"assistant\" :content ...}；:content 可为字符串或 content-parts 的 vector（多模态）。
  实现时可扩展 :usage 等。opts 同 stream-chat。"
  [_client _messages _opts]
  ;; TODO: 使用 client 发起请求，返回完整响应
  {:role "assistant" :content ""})
