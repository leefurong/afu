(ns afu.core
  "Web 服务入口：启动 Jetty，挂载 afu.handler/app。"
  (:require [afu.handler :as handler]
            [ring.adapter.jetty :as jetty]))

(def ^:private default-port 4000)

(defn start-server
  "启动 Jetty，默认端口 4000。返回 server 实例，便于 stop。"
  ([] (start-server nil))
  ([opts]
   (let [port (or (:port opts) default-port)
         app  (handler/app)]
     (jetty/run-jetty app (merge {:port port :join? false} opts)))))

(defn -main
  "供 clj -M:web-server 调用：启动 Jetty 并阻塞。
   端口可通过第一个参数或环境变量 PORT 指定，例如：
   clj -M:web-server 4001
   PORT=4001 clj -M:web-server"
  [& [port-arg]]
  (let [port (or (when port-arg (parse-long port-arg))
                 (when-let [p (System/getenv "PORT")] (parse-long p))
                 default-port)]
    (println "Starting web server on http://localhost:" port)
    (start-server {:port port :join? true})))
