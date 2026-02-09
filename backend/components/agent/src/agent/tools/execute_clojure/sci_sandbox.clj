(ns agent.tools.execute-clojure.sci-sandbox
  "SCI 沙箱能力聚合：http、json、env、stock、pprint。对外提供 :namespaces 与 env-whitelist 供 exec/spec 使用。"
  (:require [agent.tools.execute-clojure.sci-sandbox.env :as env]
            [agent.tools.execute-clojure.sci-sandbox.http :as http]
            [agent.tools.execute-clojure.sci-sandbox.json :as json]
            [agent.tools.execute-clojure.sci-sandbox.stock :as stock]
            [agent.tools.execute-clojure.sci-sandbox.stock-list-store :as stock-list-store]
            [clojure.pprint :as pprint]
            [sci.core :as sci]))

(def env-whitelist
  "允许沙箱内 env/get-env 读取的环境变量名。"
  env/env-whitelist)

(defn namespaces-for-sci
  "返回注入到 SCI 的命名空间映射：http、json、env、stock、pprint。"
  []
  {'http   {'get  http/http-get
            'post http/http-post}
   'json   {'parse-string json/parse-string
            'write-str    json/write-str}
   'env    {'get-env env/get-env}
   'stock  {'get-daily-k-for-multiple-stocks stock/get-daily-k-for-multiple-stocks
            'ma-for-multiple-stocks       stock/ma-for-multiple-stocks
            'golden-cross-for-multiple-stocks stock/golden-cross-for-multiple-stocks
            'cross-signals-on-date       stock/cross-signals-on-date
            'all-stock-codes (fn [] (stock-list-store/get-all-stock-codes))}
   'pprint {'pprint (fn pprint-to-sci-out [x]
                       "写入到 SCI 的 out，使 capture-out? 时出现在 :out 中。"
                       (let [sci-out (try (deref sci/out) (catch Throwable _ nil))]
                         (binding [*out* (or sci-out *out*)]
                           (pprint/pprint x))))}})
