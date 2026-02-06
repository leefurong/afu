(ns agent.tools.execute-clojure.context
  "execute_clojure 内部：按 conversation-id 保存 SCI 上下文，使同一会话内多次调用共享 def/defn 等状态（渐进式 REPL）。
  agent 对此无感知，仅透传 :tool-ctx {:conversation-id ...}。"
  (:require [agent.tools.execute-clojure.exec :as exec]))

(def ^:private conversation->ctx (atom {}))

(defn get-or-create-ctx
  "按 conversation-id 获取或创建 SCI 上下文。同一 id 在多次用户消息间复用同一 ctx。"
  [conversation-id]
  (when conversation-id
    (or (get @conversation->ctx conversation-id)
        (let [ctx (exec/create-ctx)]
          (swap! conversation->ctx assoc conversation-id ctx)
          ctx))))
