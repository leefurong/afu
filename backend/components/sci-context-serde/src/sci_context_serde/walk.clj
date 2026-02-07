(ns sci-context-serde.walk
  "对 context 的「绑定树」做遍历：对每个 (sym, value) 调用 f，收集非 nil 结果。"
  (:require [sci-context-serde.ctx-access :as ctx-access]))

(defn walk-namespace
  "对 ctx 中命名空间 ns-sym 的每个绑定 (sym, value) 调用 (f sym value)，
   返回 { sym -> (f sym value) }，仅包含 f 返回非 nil 的条目。"
  [ctx ns-sym f]
  (when (and ctx ns-sym f)
    (let [bindings (ctx-access/namespace-bindings ctx ns-sym)]
      (into {}
            (keep (fn [[sym value]]
                    (when-let [entry (f sym value)]
                      [(str sym) entry])))
            bindings))))
