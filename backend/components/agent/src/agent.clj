(ns agent
  "Agent：支持普通对话与「工具调用」循环（如执行 Clojure）。"
  (:require [memory :refer [->memory]]
            [llm :as llm]
            [llm-models-config :as llm-cfg]
            [tool-loader :as tool-loader]
            [agent.tools.registry :as tool-registry]
            [clojure.core.async :as a]
            [clojure.string :as str])
  (:import [java.time LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

;; 工具 handler 在 tool-loader/load-tool-definitions 时按 registry 自动 require 并注册

(def default-model-opts
  (llm-cfg/get-model-opts :moonshot :kimi-k2-turbo-preview))

(defn chat-with-tools-opts
  "返回传给 chat 的 opts 以启用工具循环。仅包含已注册 handler 的工具。"
  []
  (let [defs (tool-loader/load-tool-definitions)
        names (set (tool-registry/registered-names))]
    {:tools (filterv #(names (get-in % [:function :name])) defs)}))

(defn print-ch
  "阻塞读取 ch 并打印每个值，直到 ch 关闭。便于 REPL 里查看 (agent/chat ...) 返回的 channel。
   用法：(agent/print-ch (agent/chat a \"你好\" (agent/chat-with-tools-opts)))"
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

(def ^:private date-format (DateTimeFormatter/ofPattern "yyyy年M月d日 EEEE" Locale/SIMPLIFIED_CHINESE))
(def ^:private time-format (DateTimeFormatter/ofPattern "HH:mm"))

(defn- current-datetime-system-content
  "返回用于 system 消息的当前日期时间描述，保证 LLM 在回答「今天」「最近」时用对日期。"
  []
  (let [now (LocalDateTime/now (ZoneId/systemDefault))
        date-str (.format (LocalDate/now (ZoneId/systemDefault)) date-format)
        time-str (.format now time-format)]
    (str "当前日期与时间：" date-str " " time-str "。回答中涉及「今天」「最近」「当前」等时间概念时，请严格以此为准。")))

(defn- messages-with-date-context
  "在 messages 前插入一条包含当前日期时间的 system 消息（若首条已是 system 则合并日期到其 content 前）。"
  [messages]
  (let [date-ctx (current-datetime-system-content)]
    (if (and (seq messages) (= (get-in (first messages) [:role]) "system"))
      (update messages 0 update :content #(str date-ctx "\n\n" (or % "")))
      (into [{:role "system" :content date-ctx}] messages))))

(defn- normalize-messages
  "单条字符串 -> [{:role \"user\" :content s}]；已是 message map 序列则转成 vector。"
  [message-or-messages]
  (if (string? message-or-messages)
    [{:role "user" :content message-or-messages}]
    (vec message-or-messages)))

;; ---------------------------------------------------------------------------
;; 工具循环：complete → 若有 tool_calls 则执行并写回 → 再 complete
;; ---------------------------------------------------------------------------

(defn- run-tool-loop
  "带 tools 的 complete 循环：直到返回无 tool_calls 的 assistant 消息。
   emit-fn 可选 (fn [event-type payload])，会收到 :tool-call 与 :tool-result。"
  [client messages opts sci-ctx emit-fn]
  (println "[agent] run-tool-loop: calling llm/complete, messages count =" (count messages))
  (let [resp (llm/complete client messages opts)]
    (println "[agent] llm/complete returned: tool_calls?" (seq (:tool_calls resp)) "content len =" (count (str (:content resp))))
    (if (seq (:tool_calls resp))
      (let [assistant-msg (assoc resp :content (or (:content resp) ""))
            tool-msgs (mapv (fn [tc]
                              (let [name (get-in tc [:function :name])
                                    args (llm/parse-tool-arguments (get-in tc [:function :arguments]))
                                    tool (tool-registry/get-tool name)]
                                (if (nil? tool)
                                  (let [result {:error (str "未知工具: " name)}]
                                    (when emit-fn (emit-fn :tool-result result))
                                    {:role "tool" :tool_call_id (get tc :id) :content (pr-str result)})
                                  (let [result (tool-registry/handle tool args sci-ctx)]
                                    (when emit-fn
                                      (emit-fn :tool-call (merge {:name name :arguments args}
                                                                 (tool-registry/call-display tool args)))
                                      (emit-fn :tool-result result))
                                    {:role "tool" :tool_call_id (get tc :id) :content (pr-str result)}))))
                            (:tool_calls resp))
            next-msg (into (conj messages assistant-msg) tool-msgs)]
        (println "[agent] tool loop recur, next-msg count =" (count next-msg))
        (recur client next-msg opts sci-ctx emit-fn))
      resp)))


(defn chat
  "发送消息给 agent，返回一个 ch。
   message-or-messages：可为单条用户文本（字符串），或完整 messages 列表（vector of {:role :content}），用于带会话历史。
   opts 可选，若含 :tools（如 (chat-with-tools-opts) 返回的列表）则走工具循环：非流式 complete 直到无 tool_calls，再输出最终 content。
   ch 中事件：[:thinking \"...\"]、[:tool-call payload]、[:tool-result result]、[:content \"...\"]、[:done new-agent]；payload 含 :name :arguments，以及该 tool 的 call-display 返回值（若有）。"
  ([agent message-or-messages] (chat agent message-or-messages nil))
  ([agent message-or-messages opts]
   (let [ch (a/chan 8)
         messages (-> message-or-messages normalize-messages messages-with-date-context)
         opts (or opts {})
         use-tools? (seq (:tools opts))
         sci-ctx (:sci-ctx opts)
         model-opts (:model-opts (:gene agent))
         client (llm/make-client model-opts)]
     (if use-tools?
       ;; 工具循环在 thread 中跑，用 >!! 写 ch，避免 run-tool-loop 内 emit-fn 调用 >! 报错（非 go 上下文）
       (do
         (println "[agent] chat with tools, messages count =" (count messages))
         (a/thread
           (try
             (a/>!! ch [:thinking "思考中..."])
             (println "[agent] emitted :thinking")
             (let [emit-fn (fn [k v] (a/>!! ch [k v]))
                   final (run-tool-loop client messages opts sci-ctx emit-fn)
                   content (or (:content final) "")]
               (println "[agent] run-tool-loop done, emitting content len =" (count content) "then :done")
               (a/>!! ch [:content content])
               (a/>!! ch [:done agent]))
             (catch Exception e
               (println "[agent] exception in tool loop:" (.getMessage e))
               (a/>!! ch [:done agent]))
             (finally
               (a/close! ch)))))
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