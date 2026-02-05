(ns agent.tools.registry
  "工具执行器注册表：name -> handler 或 {:handler fn :call-display (fn [args] display-map)}。
   call-display 可选，用于「本次调用如何写入展示用 msg」，由各 tool 内聚实现。")

(def ^:private handlers (atom {}))

(defn register!
  "注册工具。entry 可为 handler 函数，或 map {:handler fn :call-display (fn [args] {...})}。
   handler 签名为 (fn [args context] result)。call-display 返回的 map 会与 {:name :arguments} 合并后作为 :tool-call 事件 payload。"
  [tool-name entry]
  (swap! handlers assoc (str tool-name) entry)
  nil)

(defn get-handler
  "根据工具名返回注册的 handler 函数，未注册返回 nil。"
  [tool-name]
  (let [v (get @handlers (str tool-name))]
    (cond (fn? v) v
          (map? v) (:handler v)
          :else nil)))

(defn get-call-display
  "根据工具名返回注册的 call-display 函数 (fn [args] display-map)，未注册或未提供则返回 nil。"
  [tool-name]
  (let [v (get @handlers (str tool-name))]
    (when (map? v) (:call-display v))))

(defn registered-names
  []
  (vec (keys @handlers)))
