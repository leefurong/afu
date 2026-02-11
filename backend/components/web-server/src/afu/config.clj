(ns afu.config
  "Backend 级配置，从环境变量读取。供 web-server 及各 component 复用。"
  (:require [clojure.string :as str]))

(defn vec-extension-path
  "sqlite-vec 扩展路径（不含 .dylib/.so），供 memory-store 等向量搜索组件使用。
   环境变量：VEC_EXTENSION_PATH"
  []
  (when-let [v (System/getenv "VEC_EXTENSION_PATH")]
    (when-not (str/blank? v)
      v)))

(defn memory-base-dir
  "Memory 组件 SQLite 文件目录。环境变量：MEMORY_BASE_DIR，默认 data/memory"
  []
  (or (when-let [v (System/getenv "MEMORY_BASE_DIR")]
        (when-not (str/blank? v) v))
      "data/memory"))

(defn memory-store-opts
  "供 memory-store/->memory-store 的 opts（不含 :embed-fn，调用方需注入）。"
  [embed-fn]
  (let [vec-path (vec-extension-path)]
    {:base-dir (memory-base-dir)
     :embed-fn embed-fn
     :vec-extension-path vec-path
     :embed-dim 1536}))
