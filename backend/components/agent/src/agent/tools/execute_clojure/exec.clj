(ns agent.tools.execute-clojure.exec
  "execute_clojure 工具用 SCI 执行层：在沙箱中执行 Clojure 代码并返回结果。

  - 使用 sci/init 构建上下文，通过 :namespaces 控制可用符号。
  - 支持无状态单次求值（eval-string）和带状态的会话上下文（create-ctx + eval-string*）。
  - 可选超时、可选捕获 stdout。
  - 沙箱内提供 http、json、env 命名空间（env/get-env 读环境变量）。"
  (:require [sci.core :as sci]
            [agent.tools.execute-clojure.sci-sandbox :as sci-sandbox]))

(def ^:private denied-symbols
  "禁止在 agent 代码中使用的符号，避免文件、网络、反射、逃逸沙箱。"
  '[eval
    load-file
    load-reader
    slurp
    spit
    read-string])

(defn- namespaces-with-env []
  (sci-sandbox/namespaces-for-sci))

(defn default-opts
  "返回默认 SCI 选项：SCI 自带 core，:deny 危险符号，无 :classes（无 Java 互操作）。
  提供 http、json、env 命名空间。env/get-env 仅返回 env-whitelist 中的变量。"
  []
  {:deny denied-symbols
   :namespaces (namespaces-with-env)})

(defn create-ctx
  "创建可复用的 SCI 上下文。同一 ctx 上多次 eval-string* 会共享 def/defn 等状态。"
  ([] (create-ctx (default-opts)))
  ([opts]
   (sci/init (merge (default-opts) opts))))

(def ^:private shared-ctx
  "无状态单次求值时使用的共享上下文（不保存 def 等）。"
  (create-ctx))

(defn- result->serializable
  "将求值结果转为 JSON 可序列化形式，避免 Var 等 Java 对象导致前端/LLM 收不到结果。
   仅递归处理值，保留 map 的 keyword 键（Cheshire 会序列化为字符串）。"
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (number? v) v
    (boolean? v) v
    (keyword? v) v
    (symbol? v) (str v)
    (map? v)    (into {} (map (fn [[k v]] [k (result->serializable v)]) v))
    (vector? v) (mapv result->serializable v)
    (seq? v)    (doall (map result->serializable (seq v)))
    (set? v)    (mapv result->serializable (vec v))
    ;; Var、Object 等无法被 Cheshire 序列化，转为字符串
    :else       (str v)))

(defn eval-string*
  "在给定 SCI 上下文 ctx 中执行代码字符串，返回求值结果。"
  ([code] (eval-string* shared-ctx code))
  ([ctx code]
   (sci/eval-string* ctx code)))

(defn eval-string
  "执行 Clojure 代码字符串，返回 {:ok value} 或 {:error \"...\"}。
   value 会转为 JSON 可序列化形式（如 def 返回的 Var 转为字符串），确保前端与 LLM 能收到结果。
  opts 可选：:ctx :timeout-ms :capture-out?。"
  ([code] (eval-string code nil))
  ([code opts]
   (let [opts   (or opts {})
         ctx    (or (:ctx opts) shared-ctx)
         timeout-ms (:timeout-ms opts)
         capture-out? (:capture-out? opts)]
     (try
       (let [body (fn []
                    (if capture-out?
                      (let [sw (java.io.StringWriter.)]
                        (sci/binding [sci/out sw]
                          (let [result (sci/eval-string* ctx code)]
                            {:result result :out (str sw)})))
                      (sci/eval-string* ctx code)))]
         (if (and timeout-ms (pos? timeout-ms))
           (let [f (future (body))
                 result (deref f timeout-ms ::timeout)]
             (if (identical? result ::timeout)
               (do (future-cancel f)
                   {:error (str "Execution timed out after " timeout-ms " ms")})
               (if capture-out?
                 {:ok (result->serializable (:result result)) :out (:out result)}
                 {:ok (result->serializable result)})))
           (let [result (body)]
             (if capture-out?
               {:ok (result->serializable (:result result)) :out (:out result)}
               {:ok (result->serializable result)}))))
       (catch Exception e
         {:error (str (.getMessage e))})))))
