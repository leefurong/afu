(ns resource-store
  "通用 resource 系统：存、取、删、改任意内容，由 resource id 引用。
   底层实现（SQLite、S3、文件等）对调用方不可见，通过协议抽象。"
  (:require [resource-store.protocol :as protocol])
  (:refer-clojure :exclude [get]))

(def put! protocol/put!)
(def put-at! protocol/put-at!)
(def get* protocol/get*)
(def delete! protocol/delete!)

(defn ->sqlite-store
  "基于 SQLite 的 ResourceStore 实现。jdbc-url 如 jdbc:sqlite:path/to/resources.db。"
  [jdbc-url]
  (require '[resource-store.sqlite :as sqlite])
  ((resolve 'resource-store.sqlite/->store) jdbc-url))
