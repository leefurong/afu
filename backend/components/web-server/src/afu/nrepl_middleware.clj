(ns afu.nrepl-middleware
  "nREPL 统一入口：默认 handler（Cursor/Calva 等） + eval-in-sci（sci-repl 用同一 SCI 沙箱）。复用同一套 nREPL，不另起一套。"
  (:require [agent.tools.execute-clojure.exec :as exec]
            [nrepl.middleware :as mw]
            [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]))

(defn wrap-eval-in-sci
  "处理 op \"eval-in-sci\"：:code 在 SCI 沙箱中求值，返回 :value/:err + :status done。"
  [handler]
  (fn [{:keys [op transport code id] :as msg}]
    (if (= "eval-in-sci" op)
      (let [result (exec/eval-string (str code) {:capture-out? true})
            id (or id (str (random-uuid)))]
        (when transport
          (when (and (contains? result :out) (seq (:out result)))
            (transport/send transport {:id id :out (:out result)}))
          (when (contains? result :ok)
            (transport/send transport {:id id :value (pr-str (:ok result))}))
          (when (contains? result :error)
            (transport/send transport {:id id :err (str (:error result))}))
          (transport/send transport {:id id :status #{"done"}})))
      (handler msg))))

(mw/set-descriptor! #'wrap-eval-in-sci
  {:handles {"eval-in-sci"
             {:doc "Evaluate code in the same SCI sandbox as execute_clojure (http, json, env, stock)."
              :requires {"code" "Clojure code string to evaluate."}
              :returns {"value" "pr-str of result on success" "err" "error message on failure"}}}})

(defn handler
  "返回与 web 进程共用的 nREPL handler：默认 eval（普通 REPL）+ eval-in-sci（SCI 沙箱）。"
  []
  (nrepl-server/default-handler #'wrap-eval-in-sci))

(defn start-nrepl!
  "启动 nREPL（复用同一 handler：默认 + eval-in-sci），写 .nrepl-port，返回 server。"
  [port]
  (let [server (nrepl-server/start-server :port port :handler (handler))]
    (spit ".nrepl-port" (str port))
    (println "nREPL server on port" port "(see .nrepl-port)")
    server))
