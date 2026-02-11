(ns afu.create-user
  "命令行脚本：创建用户账户。类似 Django createsuperuser。
   用法：clj -M:create-user [用户名] [密码]
   若未传参，会从 stdin 逐行读取。
   注意：需先停止 web-server，否则 Datomic 文件锁冲突；或从已连接的 REPL 调用
   (require 'afu.create-user) (afu.create-user/-main \"用户名\" \"密码\")。"
  (:require [afu.account.core :as account]
            [afu.db :as db]
            [clojure.string :as str]
            [datomic.client.api :as d])
  (:gen-class))

(defn- ensure-account-schema! [conn]
  (when (seq account/schema)
    (try
      (d/transact conn {:tx-data account/schema})
      (catch Exception _ nil))))

(defn- read-line-trimmed []
  (when-let [line (read-line)]
    (str/trim line)))

(defn -main
  [& [username password]]
  (let [username (or (when (seq username) username)
                     (do (print "Username: ") (flush) (read-line-trimmed)))
        password (or (when (seq password) password)
                     (do (print "Password: ") (flush) (read-line-trimmed)))]
    (cond
      (or (str/blank? username) (str/blank? password))
      (do (println "Error: username and password are required.")
          (System/exit 1))

      (account/user-exists? (db/db) username)
      (do (println "Error: user" (pr-str username) "already exists.")
          (System/exit 1))

      :else
      (try
        (ensure-account-schema! db/conn)
        (account/create-account! db/conn username password)
        (println "User" (pr-str username) "created successfully.")
        (System/exit 0)
        (catch Exception e
          (println "Error:" (.getMessage e))
          (System/exit 1))))))
