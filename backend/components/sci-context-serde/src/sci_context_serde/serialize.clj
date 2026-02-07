(ns sci-context-serde.serialize
  "序列化：按类型将「单个绑定值」转为快照条目。每个策略一个函数，顶层只做分发。"
  (:require [sci-context-serde.classify :as classify]
            [sci-context-serde.edn :as edn]
            [sci-context-serde.source :as source]))

(defn serialize-edn
  "可 EDN 序列化的值 -> {:type :data :value v}。"
  [v]
  {:type :data :value v})

(defn serialize-atom
  "Atom -> {:type :atom :value <deref 后若可 EDN 则序列化>}。若内容不可 EDN 则返回 nil（表示跳过）。"
  [v]
  (let [inner (deref v)]
    (when (edn/edn-serializable? inner)
      {:type :atom :value inner})))

(defn serialize-function
  "函数 + 符号 + 已解析形式列表 -> {:type :function :name sym :source <code-str>} 或 nil（找不到源码时）。"
  [_v sym forms]
  (when-let [src (source/find-source sym forms)]
    {:type :function :name (str sym) :source src}))

(defn serialize-one
  "对单个绑定 (sym, value) 做序列化，返回快照条目 map 或 nil（无法序列化时跳过）。
  opts 含 :recent-forms（由 source/read-forms 得到的形式列表），用于函数源码查找。"
  [sym value opts]
  (let [forms (get opts :recent-forms [])]
    (case (classify/classify-value value)
      :edn           (serialize-edn value)
      :atom          (serialize-atom value)
      :function      (serialize-function value sym forms)
      :unserializable nil)))
