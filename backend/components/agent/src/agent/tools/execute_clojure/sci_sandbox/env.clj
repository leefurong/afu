(ns agent.tools.execute-clojure.sci-sandbox.env
  "供 execute_clojure 沙箱内读取环境变量：get-env，仅返回白名单内的变量。")

(def ^:const env-whitelist
  "允许通过 get-env 读取的环境变量名。"
  #{"TUSHARE_API_TOKEN"})

(defn get-env
  "根据名称获取环境变量。仅当 name 在 env-whitelist 中时返回系统值，否则返回 nil。"
  [name]
  (when (and name (contains? env-whitelist (str name)))
    (System/getenv (str name))))
