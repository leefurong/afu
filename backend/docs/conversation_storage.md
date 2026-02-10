# 对话消息存储设计

## 背景与问题

- **Datomic 不适合存大体积单条数据**：单条 attribute value 有长度限制（约 4KB）。把整条消息的 EDN 塞进 `:message/data` 时，工具结果或长回复会触发 "Item too large"，整轮 transact 失败，导致该轮对话未落库，下一轮加载历史时缺少上一条回复，表现为「上下文丢失」。
- **设计结论**：Datomic 适合存「结构」（图、关系、小事实），不适合做 blob 库。消息正文应存到专门的大内容存储，Datomic 只存结构 + 引用。

## 当前方案（方案 A）

- **Datomic**：只存会话与消息的**结构**
  - `conversation`：id、head
  - `message`：id、conversation-id、prev-id、next-ids、selected-next-id、**content-resource-id**、created-at、sci-context-snapshot（仅 execute_clojure 的 SCI 快照）
- **消息正文**：不存 Datomic，存 **resource-store**
  - 每条消息对应一个 resource：`(put! resource-store (pr-str msg))` 得到 id，写入 message 的 `:message/content-resource-id`
  - 读历史时按 `content-resource-id` 做 `(get* resource-store id)`，再 `edn/read-string` 得到消息体
  - 删消息/删会话时，同时对每个 content-resource-id 调用 `(delete! resource-store id)`

## Resource 系统

- **位置**：`backend/components/resource-store/`
- **协议** `ResourceStore`：`put!`（存新，返回 id）、`put-at!`（按 id 存/覆盖）、`get*`、`delete!`
- **调用方不感知底层**：当前实现为 SQLite（`resource_store.sqlite`），可替换为 S3、Postgres 等，只要实现同一协议并在启动时注入即可。
- **使用约定**：conversation 的 `get-messages`、`append-messages!`、`delete-message!`、`delete-conversation!`、`list-recent` 均需传入 `resource-store` 实例；handler 在启动时从 core 注入，请求时通过 `get-resource-store` 取得并传入。

## 约定（改代码时请遵守）

1. **不要在 Datomic 的 message 上再存整段正文**：不要加回 `:message/data` 或把大内容塞进 Datomic；正文只通过 resource-store 存，Datomic 只存 `:message/content-resource-id`。
2. **新增或修改 conversation 相关调用时**：记得传入 `resource-store` 参数，并在删除消息/会话时同步删除对应 resource。
3. **换存储后端**：实现 `resource-store.protocol/ResourceStore`，在 `afu.core` 里改用新实现的 factory（如 `->s3-store`），conversation 与 handler 无需改。
