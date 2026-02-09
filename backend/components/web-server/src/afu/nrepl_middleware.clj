(ns afu.nrepl-middleware
  "nREPL 统一入口：默认 handler（Cursor/Calva 等） + eval-in-sci（sci-repl 用同一 SCI 沙箱）。复用同一套 nREPL，不另起一套。
   另提供 7889 端口的 SCI-only nREPL，Cursor/Calva 连 7889 时所有 eval 在 SCI 沙箱执行。"
  (:require [agent.tools.execute-clojure.exec :as exec]
            [nrepl.middleware :as mw]
            [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]))

(defn- base-response
  "nREPL 响应需带 :id 和 :session，否则 Calva 等客户端无法关联并显示结果。"
  [msg]
  (select-keys msg [:id :session]))

(defn- send-sci-result
  [transport msg result]
  (when transport
    (let [base (merge {:id (or (:id msg) (str (random-uuid)))}
                     (base-response msg))]
      (when (and (contains? result :out) (seq (:out result)))
        (transport/send transport (assoc base :out (:out result))))
      (when (contains? result :ok)
        (transport/send transport (assoc base :value (pr-str (:ok result)))))
      (when (contains? result :error)
        (transport/send transport (assoc base :err (str (:error result)))))
      (transport/send transport (assoc base :status #{"done"})))))

(defn wrap-eval-in-sci
  "处理 op \"eval-in-sci\"：:code 在 SCI 沙箱中求值，返回 :value/:err + :status done。"
  [handler]
  (fn [{:keys [op transport code] :as msg}]
    (if (= "eval-in-sci" op)
      (send-sci-result transport msg (exec/eval-string (str code) {:capture-out? true}))
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

(defn wrap-eval-to-sci
  "把标准 eval 转成在 SCI 沙箱中执行，其它 op 交给默认 handler。用于 7889 端口，让 Cursor/Calva 连 7889 即得 SCI REPL。"
  [handler]
  (fn [{:keys [op code transport] :as msg}]
    (if (= "eval" op)
      (send-sci-result transport msg (exec/eval-string (str code) {:capture-out? true}))
      (handler msg))))

(defn sci-only-handler
  []
  (nrepl-server/default-handler #'wrap-eval-to-sci))

(def ^:private sci-nrepl-port 7889)

(defn start-nrepl!
  "启动 nREPL（复用同一 handler：默认 + eval-in-sci），写 .nrepl-port，返回 server。"
  [port]
  (let [server (nrepl-server/start-server :port port :handler (handler))]
    (spit ".nrepl-port" (str port))
    (println "nREPL server on port" port "(see .nrepl-port)")
    server))

(defn start-nrepl-sci!
  "启动仅 SCI 的 nREPL（端口 7889）。Cursor/Calva 连此端口时，eval 在 SCI 沙箱执行，与聊天 execute_clojure 一致。写 .nrepl-port-sci。"
  []
  (let [port sci-nrepl-port
        server (nrepl-server/start-server :port port :handler (sci-only-handler))]
    (spit ".nrepl-port-sci" (str port))
    (println "nREPL (SCI only) on port" port "(see .nrepl-port-sci). Connect here for SCI sandbox REPL.")
    server))
