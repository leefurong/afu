(ns sci-context-serde.store
  "存/取快照的抽象：协议 + 辅助函数。不依赖具体存储，实现见 store/*。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defprotocol SnapshotStore
  "快照的存、取。snapshot 为 core/serialize 返回的 map。"
  (save! [this snapshot] "持久化 snapshot，返回 transact 结果或 truthy。")
  (load* [this] "读取最近一次保存的 snapshot，无则返回 nil。（协议内用 load* 避免与 clojure.core/load 冲突）"))

(defn save-snapshot!
  "对实现了 SnapshotStore 的 backend 调用 save!。"
  [backend snapshot]
  (save! backend snapshot))

(defn load-snapshot
  "对实现了 SnapshotStore 的 backend 调用 load*。"
  [backend]
  (load* backend))

(defn snapshot->edn-str
  "将 snapshot map 转为可持久化的 EDN 字符串。"
  [snapshot]
  (pr-str snapshot))

(defn edn-str->snapshot
  "将 EDN 字符串还原为 snapshot map。"
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (edn/read-string s)))
