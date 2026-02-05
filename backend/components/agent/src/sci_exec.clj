(ns sci-exec
  "Agent 用 SCI 执行层：在沙箱中执行 Clojure 代码并返回结果。

  设计参考：ruguoname 的 sci.cljc + server/engine/api.clj。
  - 使用 sci/init 构建上下文，通过 :namespaces 控制可用符号。
  - 支持无状态单次求值（eval-string）和带状态的会话上下文（create-ctx + eval-string*）。
  - 可选超时、可选捕获 stdout。"
  (:require [sci.core :as sci]))

;; ==============================================================================
;; 默认选项：使用 SCI 自带 clojure.core，仅 :deny 危险符号
;; ==============================================================================

(def ^:private denied-symbols
  "禁止在 agent 代码中使用的符号，避免文件、网络、反射、逃逸沙箱。"
  '[eval
    load-file
    load-reader
    slurp
    spit
    read-string])

(defn default-opts
  "返回默认 SCI 选项：SCI 自带 core，:deny 危险符号，无 :classes（无 Java 互操作）。"
  []
  {:deny denied-symbols})

;; ==============================================================================
;; 上下文创建（可选：跨多次求值保持 def/defn 状态）
;; ==============================================================================

(defn create-ctx
  "创建可复用的 SCI 上下文。同一 ctx 上多次 eval-string* 会共享 def/defn 等状态。
  opts 可覆盖或合并默认选项；不传则使用 default-opts。"
  ([] (create-ctx (default-opts)))
  ([opts]
   (sci/init (merge (default-opts) opts))))

(def ^:private shared-ctx
  "无状态单次求值时使用的共享上下文（不保存 def 等）。"
  (create-ctx))

;; ==============================================================================
;; 求值接口
;; ==============================================================================

(defn eval-string*
  "在给定 SCI 上下文 ctx 中执行代码字符串，返回求值结果。
  不捕获 stdout，不设超时。ctx 默认为共享无状态 ctx。"
  ([code] (eval-string* shared-ctx code))
  ([ctx code]
   (sci/eval-string* ctx code)))

(defn eval-string
  "执行 Clojure 代码字符串，返回 {:ok value} 或 {:error \"...\"}。
  opts 可选：
  - :ctx          SCI 上下文；无则用共享 ctx（无状态）。
  - :timeout-ms   超时毫秒数；无则不超时。
  - :capture-out? 为 true 时把 stdout 捕获到结果里，键为 :out。"
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
                 {:ok (:result result) :out (:out result)}
                 {:ok result})))
           (let [result (body)]
             (if capture-out?
               {:ok (:result result) :out (:out result)}
               {:ok result}))))
       (catch Exception e
         {:error (str (.getMessage e))})))))
