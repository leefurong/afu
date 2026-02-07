(ns sci-context-serde.edn
  "EDN 可序列化性判定。纯函数，可独立测试。")

(defn edn-serializable?
  "判定 v 是否可直接用 clojure.edn 序列化/反序列化。
  递归检查：nil、boolean、number、string、keyword、symbol（视为可序列化）、
  map/vector/set/list 仅当其元素均可序列化时为 true。
  不认为 function、Var、Atom、Object 等可序列化。"
  [v]
  (cond
    (nil? v) true
    (boolean? v) true
    (number? v) true
    (string? v) true
    (keyword? v) true
    (symbol? v) true
    (map? v)   (every? (fn [[k v]] (and (edn-serializable? k) (edn-serializable? v))) v)
    (vector? v) (every? edn-serializable? v)
    (set? v)   (every? edn-serializable? v)
    (seq? v)   (every? edn-serializable? (seq v))
    :else      false))
