(ns sci-context-serde.deserialize
  "反序列化：按快照条目的 :type 将一条恢复为值。每个策略一个函数，顶层只做分发。"
  (:require [sci.core :as sci]))

(defn deserialize-edn
  "条目 {:type :data :value v} -> v。"
  [entry]
  (get entry :value))

(defn deserialize-atom
  "条目 {:type :atom :value v} -> (atom v)。"
  [entry]
  (atom (get entry :value)))

(defn deserialize-function
  "条目 {:type :function :source code} 在 ctx 中求值（副作用：在 ctx 中定义绑定），无返回值。
  调用方不应再对该 sym 做 intern。"
  [entry ctx]
  (when (and ctx (get entry :source))
    (sci/eval-string* ctx (get entry :source))))

(defn deserialize-one
  "将一条快照条目恢复为值。:function 类型需传入 :ctx，会在 ctx 内直接 eval，返回 nil。
  其他类型返回 [sym value]，由调用方写入 ctx。"
  [sym entry opts]
  (when (and sym (map? entry))
    (case (get entry :type)
      :function (do (deserialize-function entry (get opts :ctx)) nil)
      :data     (let [v (deserialize-edn entry)] (when (some? v) [(symbol (str sym)) v]))
      :atom     (let [v (deserialize-atom entry)] (when (some? v) [(symbol (str sym)) v]))
      nil)))
