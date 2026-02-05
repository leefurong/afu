(ns agent.tools.execute-clojure.handler
  "execute_clojure 工具：在 SCI 沙箱中执行代码。"
  (:require [agent.tools.registry :as registry]
            [agent.tools.execute-clojure.exec :as exec]
            [clojure.string :as str]))

(def ^:private sci-timeout-ms 10000)
(def ^:private sci-capture-out? true)

(defn- execute [args sci-ctx]
  (let [code (str (get args :code))]
    (if (str/blank? code)
      {:error "参数 code 为空"}
      (let [opts (cond-> {:timeout-ms sci-timeout-ms :capture-out? sci-capture-out?}
                   sci-ctx (assoc :ctx sci-ctx))]
        (exec/eval-string code opts)))))

(defn- call-display
  "本次调用写入展示用 msg 时提供的内容，内聚在本 tool。"
  [args]
  {:code (str (or (get args :code) ""))})

;; 加载时注册，供 agent 按 name 查找执行；call-display 由本 tool 提供
(registry/register! "execute_clojure" {:handler execute :call-display call-display})
