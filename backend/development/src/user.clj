(ns user
  (:require [afu.account.core :as account]
            [afu.db]))

(defn create-user! [username password]
  (account/create-account! afu.db/conn username password))

(defn authenticate [username password]
  (account/authenticate (afu.db/db) username password))

(defn user-exists? [username]
  (account/user-exists? (afu.db/db) username))



