(ns memory-store
  "Memory 组件：以 key 隔离的独立 SQLite 库，支持 content CRUD 与自然语言向量搜索。
   使用 sqlite-vec 做 KNN 索引，搜索时不逐条比对。
   所有操作必须传入 ->memory 返回的 memory 对象，保证 schema 已确保。"
  (:require [memory-store.sqlite :as sqlite]))

(defn ->memory-store
  "创建 memory-store。opts:
   :base-dir     - SQLite 文件目录
   :embed-fn     - (fn [text] vector)，调用方不关心实现
   :vec-extension-path - sqlite-vec 的 vec0.so/vec0.dylib 路径，必填（搜索依赖）
   :embed-dim    - embedding 维度，默认 1536"
  [{:keys [base-dir embed-fn vec-extension-path embed-dim]}]
  {:pre [(string? base-dir) (ifn? embed-fn)]}
  {:base-dir base-dir
   :embed-fn embed-fn
   :vec-extension-path vec-extension-path
   :embed-dim embed-dim})

(defn ->memory
  "为 key 新建或打开库（ensure schema），返回 memory 对象。后续 remember!/list-content/recall/change!/forget! 必须传入此对象。"
  [store key]
  (sqlite/ensure-memory! (:base-dir store) key (select-keys store [:vec-extension-path :embed-dim]))
  {:store store :key key})

(defn remember!
  "向 memory 新增 content，返回 content-id。memory 需为 ->memory 返回值。"
  [memory content]
  (let [{:keys [store key]} memory]
    (sqlite/remember! (:base-dir store) key content (:embed-fn store)
                     (select-keys store [:vec-extension-path :embed-dim]))))

(defn forget!
  "删除一条 content。memory 需为 ->memory 返回值。"
  [memory content-id]
  (let [{:keys [store key]} memory]
    (sqlite/forget! (:base-dir store) key content-id
                    (select-keys store [:vec-extension-path :embed-dim]))))

(defn list-content
  "获取 content 列表。opts: {:q \"自然语言\" :page 1 :page-size 10}。返回 {:items [...] :total-count N}。memory 需为 ->memory 返回值。"
  [memory opts]
  (let [{:keys [store key]} memory]
    (sqlite/list-content (:base-dir store) key opts (:embed-fn store)
                         (select-keys store [:vec-extension-path :embed-dim]))))

(defn recall
  "根据 content-id 提取一条 content。memory 需为 ->memory 返回值。"
  [memory content-id]
  (let [{:keys [store key]} memory]
    (sqlite/recall (:base-dir store) key content-id nil)))

(defn change!
  "修改一条 content。memory 需为 ->memory 返回值。"
  [memory content-id new-content]
  (let [{:keys [store key]} memory]
    (sqlite/change! (:base-dir store) key content-id new-content (:embed-fn store)
                    (select-keys store [:vec-extension-path :embed-dim]))))
