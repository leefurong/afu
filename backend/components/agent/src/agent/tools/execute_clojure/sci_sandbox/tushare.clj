(ns agent.tools.execute-clojure.sci-sandbox.tushare
  "Tushare API 请求，供 stock、k-line-store、stock-list-store 等调用。"
  (:require [agent.tools.execute-clojure.sci-sandbox.env :as env]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as str]))

(def ^:private api-url "http://api.tushare.pro")
(def ^:private timeout-ms 15000)

(defn- request-tushare [token api-name params]
  (let [body (json/generate-string {:api_name api-name :token token :params params})]
    (try
      (let [resp (client/post api-url
                             {:body body :content-type :json :accept :json
                              :socket-timeout timeout-ms :conn-timeout timeout-ms})
            data (json/parse-string (:body resp) true)]
        (if (contains? data :code)
          (let [code (long (:code data))]
            (if (zero? code)
              (let [inner (or (:data data) data)
                    payload (-> (select-keys inner [:fields :items])
                                (update-vals #(or % [])))]
                {:ok (assoc payload :items (or (:items payload) []))})
              {:error (str "Tushare 返回错误: " (or (:msg data) "未知") " (code=" code ")")}))
          {:error "Tushare 返回格式异常"}))
      (catch Exception e
        {:error (str "请求 Tushare 失败: " (.getMessage e))}))))

(defn request-tushare-api
  "api-name 字符串，params map。返回 {:ok {:fields _ :items _}} 或 {:error _}。"
  [api-name params]
  (let [token (env/get-env "TUSHARE_API_TOKEN")]
    (if (str/blank? token)
      {:error "未配置 TUSHARE_API_TOKEN"}
      (request-tushare token api-name params))))
