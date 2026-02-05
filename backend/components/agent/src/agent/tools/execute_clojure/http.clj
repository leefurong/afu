(ns agent.tools.execute-clojure.http
  "供 execute_clojure 沙箱内调用的 HTTP 能力：get/post，带超时与纯数据返回值。"
  (:require [clj-http.client :as client]))

(def ^:private default-timeout-ms 10000)

(defn- safe-headers [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [(str k) (str v)])) m)))

(defn- response->map [resp]
  {:status  (int (:status resp))
   :headers (safe-headers (:headers resp))
   :body    (let [b (:body resp)]
              (cond
                (string? b) b
                (instance? (Class/forName "[B") b) (String. ^bytes b)
                :else       (str b)))})

(defn http-get
  "GET 请求。url 为字符串；opts 可选，如 {:timeout-ms 5000 :headers {\"Accept\" \"application/json\"}}。
  返回 {:status N :headers {...} :body \"...\"}。"
  ([url]
   (http-get url nil))
  ([url opts]
   (let [opts (or opts {})
         timeout-ms (or (:timeout-ms opts) default-timeout-ms)
         req (merge {:url url
                     :method :get
                     :as :string
                     :socket-timeout timeout-ms
                     :connection-timeout timeout-ms}
                    (when-let [h (:headers opts)]
                      {:headers (safe-headers h)}))]
     (try
       (response->map (client/request req))
       (catch Exception e
         {:error (.getMessage e)})))))

(defn http-post
  "POST 请求。url 为字符串；opts 可选，含 :body :headers :timeout-ms。
  :body 为字符串（如 JSON 字符串）。返回 {:status N :headers {...} :body \"...\"}。"
  ([url]
   (http-post url nil))
  ([url opts]
   (let [opts (or opts {})
         timeout-ms (or (:timeout-ms opts) default-timeout-ms)
         req (merge {:url url
                     :method :post
                     :as :string
                     :socket-timeout timeout-ms
                     :connection-timeout timeout-ms}
                    (when-let [b (:body opts)]
                      {:body b})
                    (when-let [h (:headers opts)]
                      {:headers (safe-headers h)}))]
     (try
       (response->map (client/request req))
       (catch Exception e
         {:error (.getMessage e)})))))
