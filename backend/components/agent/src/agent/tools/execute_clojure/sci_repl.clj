(ns agent.tools.execute-clojure.sci-repl
  "开发用：与 LLM execute_clojure 相同的 SCI 沙箱 REPL（http、json、env，同 deny）。"
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [agent.tools.execute-clojure.exec :as exec]))

(def ^:private prompt "sci=> ")

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

(defn run-sandbox-repl
  "启动与 execute_clojure 环境一致的 SCI REPL：同一 ctx（http、json、env、deny）。
  输入 :quit 或 EOF 退出。支持多行输入（括号平衡后求值）。"
  []
  (let [ctx (exec/create-ctx)]
    (println "SCI sandbox REPL (same as LLM). http, json, env available. :quit to exit.")
    (println)
    (loop []
      (print prompt)
      (flush)
      (when-let [code (read-forms)]
        (when-not (= ":quit" (str/trim code))
          (try
            (let [result (sci/eval-string* ctx code)]
              (println result)
              )
            (catch Exception e
              (println (str "Error: " (.getMessage e)))
              ))
          (recur)))))
  nil)

(defn -main []
  (run-sandbox-repl))
