(ns sci-context-serde.core
  "SCI context 序列化/反序列化对外 API。组合 walk、serialize、deserialize，不直接操作类型。"
  (:require [sci-context-serde.deserialize :as deserialize]
            [sci-context-serde.serialize :as serialize]
            [sci-context-serde.source :as source]
            [sci-context-serde.walk :as walk]
            [sci.core :as sci]))

(def ^:private default-ns 'user)

(defn serialize
  "将 ctx 中指定命名空间的绑定序列化为可 EDN 写入的快照。
   opts:
     :namespace  命名空间符号，默认 'user
     :recent-code 最近一条 assistant 代码字符串，用于解析出 def/defn 源码（函数类型必需）
  返回 { :namespace <ns-sym> :bindings { sym-str -> {:type :data|:atom|:function :value _ :name _ :source _} } }。"
  [ctx opts]
  (let [opts   (or opts {})
        ns-sym (get opts :namespace default-ns)
        code   (get opts :recent-code)
        forms  (source/read-forms code)
        f      (fn [sym value]
                 (serialize/serialize-one sym value {:recent-forms forms}))]
    {:namespace (str ns-sym)
     :bindings  (walk/walk-namespace ctx ns-sym f)}))

(defn deserialize
  "将快照恢复到 ctx。会修改 ctx：对 :function 条目执行 eval，对 :data/:atom 条目 intern 到指定命名空间。
   opts:
     :ctx        SCI context（必填）
     :namespace  目标命名空间，默认 'user
   snapshot 为 serialize 返回的结构。"
  [snapshot opts]
  (when (and snapshot (get opts :ctx))
    (let [opts    (or opts {})
          ctx     (get opts :ctx)
          ns-sym  (symbol (or (get snapshot :namespace) (str default-ns)))
          sci-ns  (sci/find-ns ctx ns-sym)
          bindings (get snapshot :bindings {})]
      (when (map? bindings)
        ;; 先恢复函数（eval 进 ctx），再恢复数据/atom（intern）
        (doseq [[sym-str entry] bindings]
          (let [sym (symbol (str sym-str))
                res (deserialize/deserialize-one sym entry opts)]
            (when (and res (vector? res) (= 2 (count res)))
              (let [[s v] res]
                (sci/intern ctx sci-ns s v))))))
      ctx)))
