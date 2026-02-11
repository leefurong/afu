/**
 * 流式聊天：POST 到后端，消费 NDJSON 流（thinking / content / done / tool_call / tool_result），通过回调更新 UI。
 */

import { getToken } from "@/services/auth";

export type ToolCallPayload = { name: string; code: string };
export type ToolResultPayload = unknown; // {:ok v} | {:error "..."} from backend

export type ChatStreamCallbacks = {
  onThinking?: (text: string | null) => void;
  onContent?: (chunk: string) => void;
  onToolCall?: (data: ToolCallPayload) => void;
  onToolResult?: (data: ToolResultPayload) => void;
  /** 收到 done 时调用；conversation_id 由后端返回，前端可再 GET messages 拉当前分支。 */
  onDone?: (opts?: { conversation_id?: string }) => void;
  onError?: (err: Error) => void;
};

type NDJSONEvent = {
  type: string;
  text?: string;
  name?: string;
  code?: string;
  result?: unknown;
  conversation_id?: string;
};

export type ChatRequestBody = {
  text: string;
  conversation_id?: string;
  /** 在此消息之后追加（fork）；不传则接在当前分支 tip 后（由 root + selected-next-id 推出） */
  prev_message_id?: string;
  /** 编辑第一条消息时从根分叉，新消息不接在任何已有消息后 */
  branch_from_root?: boolean;
};

function isNetworkError(e: unknown): boolean {
  if (e instanceof TypeError && e.message === "Failed to fetch") return true;
  if (e instanceof Error && /fetch|network|connection/i.test(e.message)) return true;
  return false;
}

export async function streamChat(
  apiUrl: string,
  body: ChatRequestBody,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const { onThinking, onContent, onToolCall, onToolResult, onDone, onError } =
    callbacks;

  const headers: HeadersInit = { "Content-Type": "application/json" };
  const token = getToken();
  if (token) (headers as Record<string, string>)["Authorization"] = `Bearer ${token}`;

  let res: Response;
  try {
    res = await fetch(apiUrl, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
  } catch (e) {
    const err = isNetworkError(e)
      ? new Error("无法连接服务器，请确认后端已启动（默认端口 4000）")
      : (e instanceof Error ? e : new Error(String(e)));
    onError?.(err);
    return;
  }

  if (!res.ok) {
    const err = new Error(res.statusText || "Request failed");
    onError?.(err);
    return;
  }

  const reader = res.body?.getReader();
  if (!reader) {
    onError?.(new Error("No response body"));
    return;
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let doneCalled = false;

  const callOnDone = (opts?: { conversation_id?: string }) => {
    if (doneCalled) return;
    doneCalled = true;
    onDone?.(opts);
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        try {
          const event = JSON.parse(trimmed) as NDJSONEvent;
          switch (event.type) {
            case "thinking":
              onThinking?.(event.text ?? null);
              break;
            case "content":
              if (event.text != null) onContent?.(event.text);
              break;
            case "tool_call":
              if (event.name != null)
                onToolCall?.({ name: event.name, code: event.code ?? "" });
              break;
            case "tool_result":
              if (event.result !== undefined) onToolResult?.(event.result);
              break;
              case "done":
              callOnDone(
                event.conversation_id
                  ? { conversation_id: event.conversation_id }
                  : undefined
              );
              break;
            default:
              break;
          }
        } catch {
          // 忽略非 JSON 行
        }
      }
    }
    if (buffer.trim()) {
      try {
        const event = JSON.parse(buffer.trim()) as NDJSONEvent;
        if (event.type === "done")
          callOnDone(
            event.conversation_id
              ? { conversation_id: event.conversation_id }
              : undefined
          );
      } catch {
        // ignore
      }
    }
    // 若流结束但从未收到 done 事件（异常断流），仍通知一次以便 UI 退出 loading
    callOnDone();
  } catch (err) {
    onError?.(err instanceof Error ? err : new Error(String(err)));
  }
}
