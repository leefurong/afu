# memory-store

以 key 隔离的 SQLite 记忆库，支持自然语言向量搜索。使用 [sqlite-vec](https://github.com/asg017/sqlite-vec) 做 KNN 索引，**搜索时不逐条比对**。

## 安装 sqlite-vec

1. **推荐**：在项目 backend 目录下运行安装脚本（自动识别平台并下载）：
   ```bash
   backend/scripts/setup-vec.sh
   ```
   扩展会安装到 `backend/extensions/vec0.dylib`（或 .so/.dll）。

2. 运行测试：在 `backend/components/memory-store` 目录下执行 `clj -M:test`（deps.edn 中已配置 `VEC_EXTENSION_PATH=../../extensions/vec0`）

## API

| 函数 | 说明 |
|------|------|
| `->memory-store opts` | 创建 store。opts：`:base-dir`（必填）、`:embed-fn`（必填）、`:vec-extension-path`（向量搜索必填）、`:embed-dim`（默认 1536） |
| `->memory store key` | 为 key 创建/打开独立库（key 隔离不同 agent/user） |
| `remember! store key content` | 新增 content，返回 content-id。**需 vec-extension-path** |
| `list-content store key opts` | 获取列表。opts：未传 `:q` 时按 created_at 倒序分页；传 `:q` 时做向量 KNN。返回 `{:items [{:id :content :created-at}] :total-count N}`。**有 :q 时需 vec-extension-path** |
| `recall store key content-id` | 根据 id 取回 content |
| `change! store key content-id new-content` | 更新 content |
| `forget! store key content-id` | 删除 content |

## 使用

```clojure
(require '[memory-store :as ms])

;; 方式一：本组件 standalone 使用（需自己提供 embed-fn）
(def embed-fn (fn [text] (vec (take 1536 (repeat 0.0))))) ; 示例：实际应接 embedding API
(def store (ms/->memory-store {:base-dir "data/memory"
                              :embed-fn embed-fn
                              :vec-extension-path "path/to/extensions/vec0" ; 不含 .dylib/.so
                              :embed-dim 1536}))

;; 方式二：结合 afu.config（从环境变量 VEC_EXTENSION_PATH、MEMORY_BASE_DIR 读取）
(require '[afu.config :as config])
(def store (ms/->memory-store (config/memory-store-opts your-embed-fn)))

(ms/->memory store :agent-1-user-1)
(def id (ms/remember! store :agent-1-user-1 "用户喜欢 Clojure"))
(ms/list-content store :agent-1-user-1 {:q "编程语言" :page 1 :page-size 10})
(ms/recall store :agent-1-user-1 id)
(ms/change! store :agent-1-user-1 id "用户喜欢 Clojure 和 Next.js")
(ms/forget! store :agent-1-user-1 id)
```
