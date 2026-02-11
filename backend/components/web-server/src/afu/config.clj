(ns afu.config
  "Backend 级配置，从环境变量或 -D 系统属性读取。供 web-server 及各 component 复用。
   deps.edn :web-server 已配置默认 -D，可被 .env 覆盖。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- env-or-prop
  "优先环境变量，其次 -D 系统属性，最后 default。"
  ([env-key prop-key] (env-or-prop env-key prop-key nil))
  ([env-key prop-key default]
   (or (when-let [v (System/getenv env-key)] (when-not (str/blank? v) v))
       (when-let [v (System/getProperty prop-key)] (when-not (str/blank? v) v))
       default)))

(defn vec-extension-path
  "sqlite-vec 扩展路径（不含 .dylib/.so），供 memory-store 等向量搜索组件使用。
   来源：VEC_EXTENSION_PATH 或 -DVEC_EXTENSION_PATH"
  []
  (when-let [v (env-or-prop "VEC_EXTENSION_PATH" "VEC_EXTENSION_PATH")]
    (let [f (io/file v)]
      (if (.isAbsolute f)
        (.getAbsolutePath f)
        (str (.getAbsolutePath (io/file (System/getProperty "user.dir") v)))))))

(defn memory-base-dir
  "Memory 组件 SQLite 文件目录。来源：MEMORY_BASE_DIR 或 -DMEMORY_BASE_DIR，默认 data/memory"
  []
  (env-or-prop "MEMORY_BASE_DIR" "MEMORY_BASE_DIR" "data/memory"))

(defn memory-store-opts
  "供 memory-store/->memory-store 的 opts（不含 :embed-fn，调用方需注入）。
   embed-dim 默认 512，匹配 bge-small-zh-v1.5；可通过 EMBED_DIM 或 -DEMBED_DIM 覆盖。"
  [embed-fn]
  (let [vec-path  (vec-extension-path)
        embed-dim (or (when-let [v (env-or-prop "EMBED_DIM" "EMBED_DIM")]
                        (try (Long/parseLong v) (catch NumberFormatException _ nil)))
                      512)]
    {:base-dir (memory-base-dir)
     :embed-fn embed-fn
     :vec-extension-path vec-path
     :embed-dim embed-dim}))
