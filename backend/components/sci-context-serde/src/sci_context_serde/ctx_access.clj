(ns sci-context-serde.ctx-access
  "从 SCI context 读取命名空间绑定（只读）。依赖 SCI 内部 :env 结构，仅用于本组件内。
  抽取成独立命名空间便于单测与替换。")

(defn binding-value
  "从「绑定」中取出当前值。绑定可能是 Var（IDeref）或直接值。"
  [binding]
  (if (instance? clojure.lang.IDeref binding)
    (deref binding)
    binding))

(defn namespace-bindings
  "从 ctx 中读出指定命名空间 ns-sym 的所有绑定，返回 [[sym value] ...]。
  依赖：ctx 为 sci/init 返回值，内部有 :env（atom），@env 含 :namespaces，且 :namespaces 为 { ns-sym -> { sym -> binding } }。
  跳过 nil 的 value（例如未绑定的 Var）。"
  [ctx ns-sym]
  (when (and ctx ns-sym)
    (let [env   (when-let [e (:env ctx)] (deref e))
          nss   (get env :namespaces)
          ns-map (get nss ns-sym)]
      (when (map? ns-map)
        (into []
              (comp (filter (fn [[k _]] (symbol? k)))
                    (map (fn [[sym binding]] [sym (binding-value binding)]))
                    (remove (fn [[_ v]] (nil? v))))
              ns-map)))))
