(ns agent.tools.execute-clojure.context
  "execute_clojure 内部：按 conversation-id 保存 SCI 上下文，使同一会话内多次调用共享 def/defn 等状态（渐进式 REPL）。
  agent 对此无感知，仅透传 :tool-ctx {:conversation-id ...}。"
  (:require [agent.tools.execute-clojure.exec :as exec]
            [sci-context-serde.core :as serde]))

(def ^:private conversation->ctx (atom {}))

(defn get-or-create-ctx
  "按 conversation-id 获取或创建 SCI 上下文。同一 id 在多次用户消息间复用同一 ctx。"
  [conversation-id]
  (when conversation-id
    (or (get @conversation->ctx conversation-id)
        (let [ctx (exec/create-ctx)]
          (swap! conversation->ctx assoc conversation-id ctx)
          ctx))))

(defn restore-ctx!
  "用快照恢复 SCI 上下文并缓存到 conversation-id。用于打开历史后从某条 tool 消息的 snapshot 恢复。
  snapshot 为 serde/serialize 返回的 map（或 store/load-snapshot 的结果）。"
  [conversation-id snapshot]
  (when (and conversation-id snapshot (map? snapshot))
    (let [ctx (exec/create-ctx)]
      (serde/deserialize snapshot {:ctx ctx})
      (swap! conversation->ctx assoc conversation-id ctx)
      ctx)))
