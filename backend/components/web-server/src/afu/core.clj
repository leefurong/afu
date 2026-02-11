(ns afu.core
  "Web 服务入口：启动 Jetty，挂载 afu.handler/app；可选启动 nREPL 供边运行边连。"
  (:require [afu.config :as config]
            [afu.db :as db]
            [afu.embedding :as embedding]
            [afu.handler :as handler]
            [afu.nrepl-middleware :as nrepl-mw]
            [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k-line-store]
            [agent.tools.execute-clojure.sci-sandbox.stock-list-store :as stock-list-store]
            [agentmanager :as agentmanager]
            [clojure.java.io :as io]
            [conversation :as conversation]
            [memory-store :as ms]
            [resource-store :as res]
            [ring.adapter.jetty :as jetty]))

(def ^:private default-port 4000)

(def ^:private jetty-defaults
  "Jetty 选项：小 output-buffer 使 /api/chat 流式响应能及时刷新到客户端。"
  {:output-buffer-size 1})

(defn start-server
  "启动 Jetty，默认端口 4000。返回 server 实例，便于 stop。"
  ([] (start-server nil))
  ([opts]
   (io/make-parents "data/resources.db")
   (handler/set-resource-store! (res/->sqlite-store "jdbc:sqlite:data/resources.db"))
   ;; Memory store：需 sqlite-vec 扩展，embed-fn 调用 LM Studio /embeddings
   (let [vec-path (config/vec-extension-path)
         vec-ok?  (when vec-path
                    (some #(.exists (io/file (str vec-path %)))
                          [".dylib" ".so" ".dll"]))]
     (if vec-ok?
       (handler/set-memory-store!
        (ms/->memory-store (config/memory-store-opts (embedding/->embed-fn))))
       (println "[Memory] sqlite-vec 未安装或未找到，记忆功能不可用。运行 clj -M:setup 安装扩展。")))
   (io/make-parents (str (config/memory-base-dir) "/.keep"))
   (agentmanager/ensure-schema! db/conn)
   (conversation/ensure-schema! db/conn)
   (stock-list-store/ensure-schema! db/conn)
   (stock-list-store/init! db/conn)
   (k-line-store/ensure-schema! db/conn)
   (k-line-store/init! db/conn)
   (let [port (or (:port opts) default-port)
         app  (handler/app)
         jetty-opts (merge jetty-defaults {:port port :join? false} opts)]
     (jetty/run-jetty app jetty-opts))))

(def ^:private nrepl-port 7888)

(defn start-nrepl!
  "启动 nREPL（默认 eval + eval-in-sci 同一套），便于 Cursor/Calva 与 sci-repl 共用。"
  []
  (nrepl-mw/start-nrepl! nrepl-port))

(defn -main
  "供 clj -M:web-server 调用：启动 nREPL + Jetty。
   Jetty 端口：第一个参数或环境变量 PORT，默认 4000。
   nREPL 端口：7888（JVM REPL），7889（SCI REPL）。连 7889 时 Cursor/Calva 的 eval 在 SCI 沙箱执行。"
  [& [port-arg]]
  (start-nrepl!)
  (nrepl-mw/start-nrepl-sci!)
  (let [port (or (when port-arg (parse-long port-arg))
                 (when-let [p (System/getenv "PORT")] (parse-long p))
                 default-port)]
    (println "Starting web server on http://localhost:" port)
    (start-server (merge jetty-defaults {:port port :join? true}))))
