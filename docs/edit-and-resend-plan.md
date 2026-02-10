# 编辑历史消息并重新发送 — 实现规划

## 一、已有基础设施（简要结论）

### 1. 后端 Fork 语义（已就绪）

- **conversation.clj**
  - 消息为链表：`prev-id` / `next-ids`，支持多后继（fork）。
  - `append-messages!` 支持 `prev-message-id` 和 `update-main-head?`：在 `prev-message-id` 之后追加；若 `update-main-head?` 为 false，主分支 head 不变，新消息形成新分支。
  - `get-messages(conn, conv-id, head-message-id, resource-store)` 从指定头沿 `prev-id` 向前遍历，返回该分支消息（含 `:id`、`:can-left?`、`:can-right?`）。

- **handler.clj — chat-handler**
  - Body 支持：`conversation_id`、**`prev_message_id`**（可选）、`text`。
  - `branch_head = prev_message_id ?? get_head(conv_id)`；历史 = `get-messages(conv_id, branch_head, resource-store)`。
  - 流式结束后 `write-event-ch-to-stream` 里调用 `append-messages!(..., branch_head, update-main-head?)`，其中 **`update_main_head? = (prev_message_id == nil)`**。
  - 因此：**传了 `prev_message_id` = 在该条之后 fork，新回复挂在 prev 之后且不更新主分支**。

结论：**「编辑某条用户消息并重新发送」= 以该条用户消息的「前一条消息」为 prev，发送编辑后的文本；后端无需改逻辑，已支持 fork。**

### 2. 前端现状

- **加载历史**：`getConversationMessages(id)` → 主分支消息列表；`apiMessagesToMessages` 转成 `Message[]`，**保留后端 `id`**（`m.id`）。
- **发送**：`streamChat({ text, conversation_id? })`，**未传 `prev_message_id`**；`ChatRequestBody` 类型里也没有该字段。
- **消息列表**：顺序即分支顺序，故「某条消息的前一条」= 列表中前一条的 `id`。

---

## 二、产品语义

- **仅支持编辑「用户消息」**：在一条 user 消息上提供「编辑」入口，用户修改文案后「重新发送」。
- **行为**：从「被编辑的那条用户消息的**前一条**」之后 fork，发送编辑后的内容；后端在该 prev 后追加 [新 user, 新 assistant...]，形成新分支，主分支不变。
- **前端视图**：发送后当前对话区应显示「新分支」：即历史截断到被编辑消息的前一条，再拼接「新 user 消息 + 新 assistant 消息」（与正常发送一致，只是截断点不同）。

---

## 三、实现任务拆解

### 1. 后端（可选增强，非必须）

- **当前**：不传 `prev_message_id` 即主分支续写；传则在该条之后 fork。编辑重发只需前端传 `prev_message_id` 与 `text`，**无需改 handler**。
- **可选**：
  - 在 `done` 事件中增加 `branch_head` 或 `last_message_id`，便于前端记录「当前分支 head」或刷新后拉取同一分支。
  - `GET /api/conversations/:id/messages` 支持 `?head=<message_id>`，按指定分支 head 返回消息列表，以便「编辑并重发后」刷新页面仍能看到 fork 分支。  
  这两项可放在后续迭代，首版可只做「编辑 + 重发」的 UI 与请求参数。

### 2. 前端 — 类型与请求

- **services/chat.ts**
  - `ChatRequestBody` 增加可选字段：`prev_message_id?: string`。
  - `streamChat` 的 body 中在调用处传入 `prev_message_id`（有则传，无则省略）。

### 3. 前端 — 编辑入口与状态

- **chat/page.tsx**
  - 仅在 **user 消息** 的气泡或行上提供「编辑」入口（如 hover 时显示图标/按钮，或消息右上角菜单）。
  - 状态建议：
    - `editingMessageId: string | null`：当前处于编辑态的消息 id。
    - 或 `editingMessageIndex: number | null`，便于知道「前一条」和截断位置。
  - 点击「编辑」后：将该条消息的 `getMessageText(message)` 填到输入框（或单独编辑区），并记下 `editingMessageId` / `editingMessageIndex`；可提供「取消」清空编辑态。

### 4. 前端 — 重新发送逻辑

- **提交时**（原「发送」或新「重新发送」按钮）：
  - 若当前为「编辑态」：
    - `prev_message_id` = 被编辑消息在 `messages` 中的**前一条**的 `id`；若被编辑的是第一条（index 0），则 `prev_message_id` 不传。
    - `text` = 编辑后的内容（输入框或编辑区内容）。
    - 调用 `streamChat({ conversation_id?, prev_message_id, text }, callbacks)`。
  - 若为普通发送：保持现有逻辑（不传 `prev_message_id`）。

- **发送后 UI 更新**：
  - 编辑并重发时：在发起请求前将 `messages` 截断到「被编辑消息的前一条」：  
    `setMessages(prev => prev.slice(0, editingMessageIndex))`（即只保留到 prev，不包含被编辑的那条）。
  - 然后按现有流式逻辑：先追加新 user 消息，流式显示 assistant，`onDone` 时再追加完整 assistant 消息。这样当前视图即为「新分支」：prev 之前的历史 + 新 user + 新 assistant。
  - 发送完成后清空 `editingMessageId` / `editingMessageIndex`，并清空输入框。

### 5. 前端 — 边界与体验

- 编辑「第一条用户消息」：`editingMessageIndex === 0` 时 `prev_message_id` 不传，等价于从会话开头新开一条分支。
- 若当前没有 `conversation_id`（新会话），编辑态不应出现（或仅在有历史且已加载会话时显示编辑）；若在已有会话中编辑，则始终带 `conversation_id`。
- 取消编辑：清空编辑态、恢复输入框占位符，不改变 `messages`。

---

## 四、依赖关系小结

| 层级     | 改动 |
|----------|------|
| 后端     | 无需改（已支持 `prev_message_id` fork）；可选：done 带 branch_head、GET messages 支持 `?head=`。 |
| chat.ts  | `ChatRequestBody` 增加 `prev_message_id?: string`；调用处传入。 |
| chat 页面 | 用户消息展示「编辑」入口；编辑态状态；提交时区分「普通发送 / 编辑重发」并传 `prev_message_id`；编辑重发时先截断 `messages` 再走流式追加。 |

按上述顺序实现即可在现有 fork 能力上完成「编辑旧的历史消息并重新发送」功能；后续若要支持「刷新后仍显示当前 fork 分支」，再加后端 GET `?head=` 与 done 的 `branch_head` 即可。
