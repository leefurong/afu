(ns afu.core
  "Web 服务入口：启动 Jetty，挂载 afu.handler/app；可选启动 nREPL 供边运行边连。"
  (:require [afu.handler :as handler]
            [afu.db :as db]
            [conversation :as conversation]
            [agentmanager :as agentmanager]
            [ring.adapter.jetty :as jetty]
            [nrepl.server :as nrepl]))

(def ^:private default-port 4000)

(def ^:private jetty-defaults
  "Jetty 选项：小 output-buffer 使 /api/chat 流式响应能及时刷新到客户端。"
  {:output-buffer-size 1})

(defn start-server
  "启动 Jetty，默认端口 4000。返回 server 实例，便于 stop。"
  ([] (start-server nil))
  ([opts]
   (agentmanager/ensure-schema! db/conn)
   (conversation/ensure-schema! db/conn)
   (let [port (or (:port opts) default-port)
         app  (handler/app)
         jetty-opts (merge jetty-defaults {:port port :join? false} opts)]
     (jetty/run-jetty app jetty-opts))))

(def ^:private nrepl-port 7888)

(defn start-nrepl!
  "启动 nREPL，便于 Cursor/Calva 等边运行边连。端口写入 .nrepl-port。"
  []
  (let [server (nrepl/start-server :port nrepl-port)]
    (spit ".nrepl-port" (str nrepl-port))
    (println "nREPL server on port" nrepl-port "(see .nrepl-port)")
    server))

(defn -main
  "供 clj -M:web-server 调用：启动 nREPL + Jetty。
   Jetty 端口：第一个参数或环境变量 PORT，默认 4000。
   nREPL 端口：7888，可连 Cursor/Calva 边运行边 eval。"
  [& [port-arg]]
  (start-nrepl!)
  (let [port (or (when port-arg (parse-long port-arg))
                 (when-let [p (System/getenv "PORT")] (parse-long p))
                 default-port)]
    (println "Starting web server on http://localhost:" port)
    (start-server (merge jetty-defaults {:port port :join? true}))))
