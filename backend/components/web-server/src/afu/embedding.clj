(ns afu.embedding
  "Embedding 服务：调用 LM Studio 或 OpenAI 兼容的 /v1/embeddings API。
   来源：环境变量或 -D 系统属性（deps.edn :web-server 已配默认值）。"
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defn- env-or-prop [env-key prop-key default]
  (or (when-let [v (System/getenv env-key)] (when-not (str/blank? v) v))
      (when-let [v (System/getProperty prop-key)] (when-not (str/blank? v) v))
      default))

(defn- base-url []
  (env-or-prop "EMBEDDING_BASE_URL" "EMBEDDING_BASE_URL" "http://127.0.0.1:1234/v1"))

(defn- model-name []
  (env-or-prop "EMBEDDING_MODEL" "EMBEDDING_MODEL" "text-embedding-bge-small-zh-v1.5"))

(defn embed
  "调用 embedding API，将 text 转为向量。返回 [f0 f1 ...] 或抛错。"
  [text]
  (let [url     (str (base-url) "/embeddings")
        body    (json/generate-string {:model (model-name) :input [text]})
        resp    (http/post url
                           {:body            body
                            :content-type    :json
                            :accept          :json
                            :socket-timeout  30000
                            :conn-timeout    5000})
        parsed  (json/parse-string (:body resp) true)]
    (when-let [err (:error parsed)]
      (throw (ex-info (str "Embedding API error: " (pr-str err)) {:response parsed})))
    (get-in parsed [:data 0 :embedding])))

(defn ->embed-fn
  "返回 (fn [text] embedding-vector)，供 memory-store 使用。"
  []
  (fn [text]
    (when-let [v (embed text)]
      (vec v))))
