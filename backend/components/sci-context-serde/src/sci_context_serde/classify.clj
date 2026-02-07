(ns sci-context-serde.classify
  "对「绑定值」做类型分类，用于序列化时选择策略。纯函数，可独立测试。"
  (:require [sci-context-serde.edn :as edn]))

(defn classify-value
  "将绑定值 v 分类为以下之一：
   - :edn          可 EDN 序列化（含 nil、基础类型、纯集合）
   - :atom         clojure.lang.Atom，其内容可再分类
   - :function     可调用（IFn），需从源码恢复
   - :unserializable 其它（Var、Class、对象等），本组件不序列化。
  调用方可根据类型调用对应 serializer。"
  [v]
  (cond
    (instance? clojure.lang.Atom v) :atom
    (edn/edn-serializable? v)      :edn
    (instance? clojure.lang.IFn v)  :function
    :else                          :unserializable))
