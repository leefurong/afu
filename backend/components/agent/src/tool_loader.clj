(ns tool-loader
  "从 agent/tools 加载工具定义：每工具一目录，含 spec.clj（提供 tool-spec）+ handler；loader 只负责组装 API 格式，不解析占位或依赖任何工具内部状态。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private tools-base "agent/tools")

(defn- resource-slurp [path]
  (when-let [r (io/resource path)]
    (with-open [s (io/input-stream r)]
      (slurp s))))

(defn- registry-tool-names []
  (let [s (resource-slurp (str tools-base "/registry.edn"))]
    (when s
      (get (edn/read-string s) :tools []))))

(defn- load-one-tool
  "要求工具命名空间提供 agent.tools.<name>.spec/tool-spec，返回 {:name :description :parameters}。"
  [tool-name]
  (let [spec-ns (symbol (str "agent.tools." (str/replace (str tool-name) "_" "-") ".spec"))]
    (try
      (require spec-ns)
      (when-let [f (ns-resolve spec-ns 'tool-spec)]
        (let [spec ((var-get f))]
          (when (and (map? spec) (get spec :name))
            {:type "function"
             :function {:name (get spec :name)
                        :description (str (get spec :description ""))
                        :parameters (get spec :parameters)}})))
      (catch Exception _ nil))))

(defn- require-tool-handler!
  [tool-name]
  (let [ns-symbol (symbol (str "agent.tools." (str/replace (str tool-name) "_" "-") ".handler"))]
    (require ns-symbol)))

(defn load-tool-definitions
  "从 agent/tools 加载所有在 registry.edn 中列出的工具。每个工具通过 spec.clj 的 tool-spec 提供完整说明，通过 handler 注册执行器；loader 不依赖任何工具内部实现。"
  []
  (let [names (registry-tool-names)
        _ (doseq [n names] (require-tool-handler! n))]
    (into [] (keep load-one-tool names))))
