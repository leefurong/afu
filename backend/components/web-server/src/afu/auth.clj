(ns afu.auth
  "JWT 签发与解析，用于 resolve-user-id。"
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn- jwt-secret []
  (or (when-let [v (System/getenv "JWT_SECRET")] (when-not (str/blank? v) v))
      (System/getProperty "JWT_SECRET")
      "dev-secret-change-in-production"))

(defn sign-token
  "签发 JWT，包含 :user-id (account/id 的字符串形式) 与 :username。"
  [user-id username]
  (jwt/sign {:user-id (str user-id) :username username}
            (jwt-secret)
            {:alg :hs256}))

(defn- get-claim [claims k]
  "JWT claims 可能是 keyword 或 string key；常见有 :user-id \"user-id\" \"userId\"。"
  (or (get claims k)
      (get claims (name k))
      (when (= k :user-id)
        (or (get claims "userId") (get claims :userId)))))

(defn unsign-token
  "解析并校验 JWT，成功返回 {:user-id \"uuid\" :username \"...\"}，失败返回 nil。
   user-id 为空字符串时视为无效，返回 nil。"
  [token]
  (when (and token (string? token) (not (str/blank? token)))
    (let [claims (jwt/unsign token (jwt-secret) {:alg :hs256})
          uid    (get-claim claims :user-id)]
      (when (and uid (not (str/blank? (str uid))))
        {:user-id (str uid)
         :username (get-claim claims :username)}))))
