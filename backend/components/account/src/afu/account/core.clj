(ns afu.account.core
  (:require [datomic.client.api :as d]
            [buddy.hashers :as hashers]))

(def schema
  [{:db/ident :account/username
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "登录用的用户名"}
   {:db/ident :account/password-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Bcrypt 加密后的密码"}
   {:db/ident :account/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "公开的业务ID"}])

(defn create-account! [conn username password]
  (let [tx-data [{:account/id (java.util.UUID/randomUUID)
                  :account/username username
                  :account/password-hash (hashers/derive password)}]]
    (d/transact conn {:tx-data tx-data})))

(defn user-exists?
  "检查用户名是否已经被注册"
  [db username]
  (some? (:db/id (d/pull db '[:db/id] [:account/username username]))))

(defn authenticate
  "验证身份。如果成功返回用户信息，失败返回 nil"
  [db username password]
  (when-let [user (d/pull db
                          '[:db/id :account/password-hash :account/username]
                          [:account/username username])]
    (when (and user
               (hashers/check password (:account/password-hash user)))
      ;; 认证成功，返回用户实体（通常不返回密码哈希）
      (dissoc user :account/password-hash))))