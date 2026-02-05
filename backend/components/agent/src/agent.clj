(ns agent
  "Agent：支持普通对话与「工具调用」循环（如执行 Clojure）。"
  (:require [memory :refer [->memory]]
            [llm :as llm]
            [llm-models-config :as llm-cfg]
            [sci-exec :as sci-exec]
            [clojure.core.async :as a]
            [clojure.string :as str]))

(def default-model-opts
  (llm-cfg/get-model-opts :moonshot :kimi-k2-turbo-preview))

(def chat-with-tools-opts
  "传给 chat 的 opts 以启用「执行 Clojure」工具循环。handler 可直接传此常量。"
  {:tools [llm/execute-clojure-tool]})

(defn print-ch
  "阻塞读取 ch 并打印每个值，直到 ch 关闭。便于 REPL 里查看 (agent/chat ...) 返回的 channel。
   用法：(agent/print-ch (agent/chat a \"你好\" agent/chat-with-tools-opts))"
  [ch]
  (loop []
    (when-let [v (a/<!! ch)]
      (println v)
      (recur))))

(defn ->agent
  "构造 agent。gene 若无 :model-opts 则使用默认 moonshot kimi-k2-turbo-preview。"
  ([gene] (->agent gene (->memory)))
  ([gene memory]
   (let [gene (or gene {})
         gene (if (contains? gene :model-opts)
                gene
                (assoc gene :model-opts default-model-opts))]
     {:gene gene :memory memory})))

(defn- normalize-messages
  "单条字符串 -> [{:role \"user\" :content s}]；已是 message map 序列则转成 vector。"
  [message-or-messages]
  (if (string? message-or-messages)
    [{:role "user" :content message-or-messages}]
    (vec message-or-messages)))

;; ---------------------------------------------------------------------------
;; 工具循环：complete → 若有 tool_calls 则执行并写回 → 再 complete
;; ---------------------------------------------------------------------------

(def ^:private sci-timeout-ms 10000)
(def ^:private sci-capture-out? true)

(defn- execute-one-tool
  "执行单条 tool_call（仅支持 execute_clojure），返回 result map（{:ok v} 或 {:error \"...\"}）供写 tool 消息与发射 :tool-result。"
  [tc sci-ctx]
  (let [name (get-in tc [:function :name])
        args (llm/parse-tool-arguments (get-in tc [:function :arguments]))]
    (if (not= name "execute_clojure")
      {:error (str "未知工具: " name)}
      (let [code (get args :code)
            opts (cond-> {:timeout-ms sci-timeout-ms :capture-out? sci-capture-out?}
                   sci-ctx (assoc :ctx sci-ctx))
            result (if (str/blank? (str code))
                     {:error "参数 code 为空"}
                     (sci-exec/eval-string (str code) opts))]
        result))))

(defn- run-tool-loop
  "带 tools 的 complete 循环：直到返回无 tool_calls 的 assistant 消息。
   emit-fn 可选 (fn [event-type payload])，会收到 :tool-call 与 :tool-result，便于流式输出和会话记录。"
  [client messages opts sci-ctx emit-fn]
  (let [resp (llm/complete client messages opts)]
    (if (seq (:tool_calls resp))
      (let [assistant-msg (assoc resp :content (or (:content resp) ""))
            tool-msgs (mapv (fn [tc]
                              (let [name (get-in tc [:function :name])
                                    args (llm/parse-tool-arguments (get-in tc [:function :arguments]))
                                    code (str (get args :code))
                                    result (execute-one-tool tc sci-ctx)]
                                (when emit-fn
                                  (emit-fn :tool-call {:name name :code code})
                                  (emit-fn :tool-result result))
                                {:role "tool"
                                 :tool_call_id (get tc :id)
                                 :content (pr-str result)}))
                            (:tool_calls resp))
            next-msg (into (conj messages assistant-msg) tool-msgs)]
        (recur client next-msg opts sci-ctx emit-fn))
      resp)))


(defn chat
  "发送消息给 agent，返回一个 ch。
   message-or-messages：可为单条用户文本（字符串），或完整 messages 列表（vector of {:role :content}），用于带会话历史。
   opts 可选，若含 :tools（如 [llm/execute-clojure-tool]）则走工具循环：非流式 complete 直到无 tool_calls，再输出最终 content。
   ch 中事件：[:thinking \"...\"]、[:tool-call {:name \"...\" :code \"...\"}]、[:tool-result result]、[:content \"...\"]、[:done new-agent]。"
  ([agent message-or-messages] (chat agent message-or-messages nil))
  ([agent message-or-messages opts]
   (let [ch (a/chan 8)
         messages (normalize-messages message-or-messages)
         opts (or opts {})
         use-tools? (seq (:tools opts))
         sci-ctx (:sci-ctx opts)
         model-opts (:model-opts (:gene agent))
         client (llm/make-client model-opts)]
     (if use-tools?
       ;; 工具循环在 thread 中跑，用 >!! 写 ch，避免 run-tool-loop 内 emit-fn 调用 >! 报错（非 go 上下文）
       (a/thread
         (try
           (a/>!! ch [:thinking "思考中..."])
           (let [emit-fn (fn [k v] (a/>!! ch [k v]))
                 final (run-tool-loop client messages opts sci-ctx emit-fn)
                 content (or (:content final) "")]
             (a/>!! ch [:content content])
             (a/>!! ch [:done agent]))
           (catch Exception _e
             (a/>!! ch [:done agent]))
           (finally
             (a/close! ch))))
       (a/go
         (try
           (a/>! ch [:thinking "思考中..."])
           (let [stream-ch (llm/stream-chat client messages {})]
             (loop []
               (when-let [delta (a/<! stream-ch)]
                 (a/>! ch [:content delta])
                 (recur)))
             (a/>! ch [:done agent]))
           (catch Exception _e
             (a/>! ch [:done agent]))
           (finally
             (a/close! ch)))))
     ch))) 