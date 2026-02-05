(ns tool-loader
  "从 agent/tools 加载工具定义：每工具实现 Tool 协议（handler + call-display + spec），loader 只负责 require 与组装 API 格式。"
  (:require [agent.tools.registry :as registry]
            [clojure.edn :as edn]
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

(defn- require-tool-handler!
  [tool-name]
  (let [ns-symbol (symbol (str "agent.tools." (str/replace (str tool-name) "_" "-") ".handler"))]
    (require ns-symbol)))

(defn load-tool-definitions
  "加载 registry.edn 中列出的工具（require 各 handler ns 即完成注册），从各 tool 的 spec 组装 OpenAI/Moonshot 工具定义。"
  []
  (let [names (registry-tool-names)
        _ (doseq [n names] (require-tool-handler! n))]
    (into []
          (keep (fn [name]
                  (when-let [tool (registry/get-tool name)]
                    (let [s (registry/spec tool)]
                      (when (and (map? s) (get s :name))
                        {:type "function"
                         :function {:name (get s :name)
                                    :description (str (get s :description ""))
                                        :parameters (get s :parameters)}})))))
          names)))
