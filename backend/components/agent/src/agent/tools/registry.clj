(ns agent.tools.registry
  "工具执行器注册表：name -> (fn [args context] result)。")

(def ^:private handlers (atom {}))

(defn register!
  "注册工具执行器。handler 签名为 (fn [args context] result)，result 为 {:ok v} 或 {:error \"...\"}。"
  [tool-name handler]
  (swap! handlers assoc (str tool-name) handler)
  nil)

(defn get-handler
  "根据工具名返回注册的 handler，未注册返回 nil。"
  [tool-name]
  (get @handlers (str tool-name)))

(defn registered-names
  []
  (vec (keys @handlers)))
