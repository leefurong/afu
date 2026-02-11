# memory-store

以 key 隔离的 SQLite 记忆库，支持自然语言向量搜索。使用 [sqlite-vec](https://github.com/asg017/sqlite-vec) 做 KNN 索引，**搜索时不逐条比对**。

## 安装 sqlite-vec

1. **推荐**：在项目 backend 目录下运行安装脚本（自动识别平台并下载）：
   ```bash
   backend/scripts/setup-vec.sh
   ```
   扩展会安装到 `backend/extensions/vec0.dylib`（或 .so/.dll）。

2. 运行测试：`clj -M:test`（在 memory-store 或 backend 目录下，会自动查找 extensions/vec0）

## 使用

```clojure
(require '[memory-store :as ms])
(require '[afu.config :as config])

;; 从 backend 配置获取 opts（需自行注入 embed-fn）
(def store (ms/->memory-store (config/memory-store-opts your-embed-fn)))

(ms/->memory store :agent-1-user-1)
(def id (ms/remember! store :agent-1-user-1 "用户喜欢 Clojure"))
(ms/list-content store :agent-1-user-1 {:q "编程语言" :page 1 :page-size 10})
(ms/recall store :agent-1-user-1 id)
(ms/change! store :agent-1-user-1 id "用户喜欢 Clojure 和 Next.js")
(ms/forget! store :agent-1-user-1 id)
```
