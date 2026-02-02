(ns agent
  (:require [memory :refer [->memory]]
            [llm :as llm]
            [llm-models-config :as llm-cfg]
            [clojure.core.async :as a]))

(def default-model-opts
  (llm-cfg/get-model-opts :moonshot :kimi-k2-turbo-preview))

(defn ->agent
  "构造 agent。gene 若无 :model-opts 则使用默认 moonshot kimi-k2-turbo-preview。"
  ([gene] (->agent gene (->memory)))
  ([gene memory]
   (let [gene (or gene {})
         gene (if (contains? gene :model-opts)
                gene
                (assoc gene :model-opts default-model-opts))]
     {:gene gene :memory memory})))

(defn chat
  "发送消息给 agent，返回一个 ch。
   ch 中事件：[:thinking \"...\"] [:content \"...\"] ... [:done new-agent]。
   使用 gene 的 :model-opts 构建 LLM client，流式请求由 llm/stream-chat 完成。"
  [agent message]
  (let [ch (a/chan 8)]
    (a/go
      (try
        (a/>! ch [:thinking "思考中..."])
        (let [model-opts (:model-opts (:gene agent))
              client (llm/make-client model-opts)
              messages [{:role "user" :content message}]
              stream-ch (llm/stream-chat client messages {})]
          (loop []
            (when-let [delta (a/<! stream-ch)]
              (a/>! ch [:content delta])
              (recur)))
          (a/>! ch [:done agent]))
        (catch Exception _e
          (a/>! ch [:done agent]))
        (finally
          (a/close! ch))))
    ch)) 