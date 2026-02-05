(ns agent.tools.execute-clojure.handler
  "execute_clojure 工具：在 SCI 沙箱中执行代码。实现 Tool 协议（handler + call-display + spec）。"
  (:require [agent.tools.execute-clojure.exec :as exec]
            [agent.tools.execute-clojure.spec :as spec]
            [agent.tools.registry :as registry]
            [clojure.string :as str]))

(def ^:private sci-timeout-ms 10000)
(def ^:private sci-capture-out? true)

(defrecord ExecuteClojureTool []
  registry/Tool
  (handle [_ args sci-ctx]
    (let [code (str (get args :code))]
      (if (str/blank? code)
        {:error "参数 code 为空"}
        (let [opts (cond-> {:timeout-ms sci-timeout-ms :capture-out? sci-capture-out?}
                     sci-ctx (assoc :ctx sci-ctx))]
          (exec/eval-string code opts)))))
  (call-display [_ args]
    {:code (str (or (get args :code) ""))})
  (spec [_]
    (spec/tool-spec)))

;; 加载时注册
(registry/register! "execute_clojure" (->ExecuteClojureTool))
