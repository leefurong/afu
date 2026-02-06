(ns agent.tools.tushare-daily.api
  "调用 Tushare 开放接口：A股日线行情 daily。"
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as str]))

;; 官方接口: http://api.tushare.pro （勿用 open.tushare.pro，该域名无法解析）
(def ^:private api-url "http://api.tushare.pro")
(def ^:private timeout-ms 15000)

(defn- build-params
  "从工具入参构建 Tushare params，只保留非空字段。"
  [args]
  (into {} (remove (comp nil? second)
                   (select-keys args [:ts_code :trade_date :start_date :end_date]))))

(defn daily
  "请求 Tushare daily 接口。token 为 API token；args 为 {:ts_code \"000001.SZ\" :start_date \"20180701\" :end_date \"20180718\"} 或 {:trade_date \"20180810\"} 等。
  返回 {:ok {:fields [...] :items [[...] ...]}} 或 {:error \"...\"}。"
  [token args]
  (if (str/blank? token)
    {:error "未配置 TUSHARE_API_TOKEN，请在环境中设置"}
    (let [params (build-params args)
          body   (json/generate-string {:api_name "daily"
                                        :token    token
                                        :params   params})
          _ (println "body: " body)]
      (try
        (let [resp (client/post api-url
                               {:body            body
                                :content-type    :json
                                :accept          :json
                                :socket-timeout  timeout-ms
                                :conn-timeout    timeout-ms})
              data (json/parse-string (:body resp) true)]
          (if (contains? data :code)
            (let [code (long (:code data))]
              (if (zero? code)
                {:ok (select-keys data [:fields :items])}
                {:error (str "Tushare 返回错误: " (or (:msg data) "未知") " (code=" code ")")}))
            {:error "Tushare 返回格式异常"}))
        (catch Exception e
          {:error (str "请求 Tushare 失败: " (.getMessage e))})))))
