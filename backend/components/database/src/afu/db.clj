(ns afu.db
  (:require [datomic.client.api :as d]))

;; 配置信息
(def cfg {:server-type :dev-local
          :system "dev"
          :db-name "afu-test"})

;; 1. 创建 Client (defonce 保证只创建一次)
(defonce client (d/client cfg))

;; 2. 辅助函数：确保数据库存在并连接
(defn get-conn []
  ;; 如果数据库不存在，先创建
  (d/create-database client {:db-name (:db-name cfg)})
  ;; 连接
  (d/connect client {:db-name (:db-name cfg)}))

;; 3. 全局连接对象 (开发时用，生产环境通常用 Component/Integrant 管理)
(defonce conn (get-conn))

;; 4. 辅助函数：获取最新的 db 快照
(defn db []
  (d/db conn))