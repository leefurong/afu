(ns agent.tools.execute-clojure.sci-repl
  "开发用：连接 web-server 的 nREPL，在服务端同一 SCI 沙箱中求值（与聊天 execute_clojure 一致）。
  必须先启动 clj -M:web-server，否则连接失败并退出。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.core :as nrepl]))

(def ^:private default-port 7888)
(def ^:private prompt "sci=> ")

(defn- nrepl-port []
  (or (when (.exists (io/file ".nrepl-port"))
        (try (Long/parseLong (str/trim (slurp (io/file ".nrepl-port"))))
             (catch Exception _ nil)))
      default-port))

(defn- paren-balance [s]
  (- (count (filter #{\(} s))
     (count (filter #{\)} s))))

(defn- read-forms
  "从 *in* 读取直到括号平衡，返回字符串。空输入或仅空白返回 nil。"
  []
  (let [first-line (read-line)]
    (when first-line
      (loop [acc (str first-line "\n")]
        (let [trimmed (str/trim acc)]
          (cond
            (empty? trimmed) nil
            (= 0 (paren-balance trimmed)) trimmed
            :else (if-let [next-line (read-line)]
                    (recur (str acc next-line "\n"))
                    trimmed)))))))

(defn- connect-or-exit!
  "连接 web-server 的 nREPL；失败则打印错误并 exit 1。"
  [port]
  (try
    (nrepl/connect :port (int port))
    (catch Exception e
      (println "Error: Web server (nREPL) not running? Connection refused.")
      (println "Start the server first: clj -M:web-server")
      (println "Detail:" (.getMessage e))
      (System/exit 1))))

(defn- eval-in-sci [client code]
  (let [responses (doall (nrepl/message client {:op "eval-in-sci" :code code}))]
    (doseq [r responses]
      (when (contains? r :out)
        (print (:out r))
        (flush))
      (when (contains? r :value)
        (println (:value r)))
      (when (contains? r :err)
        (binding [*out* *err*]
          (print (:err r))
          (flush))))
    (flush)))

(defn run-sandbox-repl
  "连接 web 进程的 nREPL，在服务端 SCI 沙箱中求值。输入 :quit 或 EOF 退出。"
  []
  (let [port (nrepl-port)
        _ (println "Connecting to nREPL at port" port "(web-server must be running)...")
        transport (connect-or-exit! port)
        client (nrepl/client transport 10000)]
    (println "SCI sandbox REPL (same env as chat). http, json, env, stock. :quit to exit.")
    (println)
    (loop []
      (print prompt)
      (flush)
      (when-let [code (read-forms)]
        (when-not (= ":quit" (str/trim code))
          (try
            (eval-in-sci client code)
            (catch Exception e
              (println (str "Error: " (.getMessage e)))))
          (recur))))))

(defn -main []
  (run-sandbox-repl))
