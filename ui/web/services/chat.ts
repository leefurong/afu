/**
 * 流式聊天：POST 到后端，消费 NDJSON 流（thinking / content / done / tool_call / tool_result），通过回调更新 UI。
 */

export type ToolCallPayload = { name: string; code: string };
export type ToolResultPayload = unknown; // {:ok v} | {:error "..."} from backend

export type ChatStreamCallbacks = {
  onThinking?: (text: string | null) => void;
  onContent?: (chunk: string) => void;
  onToolCall?: (data: ToolCallPayload) => void;
  onToolResult?: (data: ToolResultPayload) => void;
  /** 收到 done 时调用，若后端返回了 conversation_id 会传入，用于后续请求延续会话 */
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
};

export async function streamChat(
  apiUrl: string,
  body: ChatRequestBody,
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const { onThinking, onContent, onToolCall, onToolResult, onDone, onError } =
    callbacks;

  const res = await fetch(apiUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

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
              onDone?.(
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
          onDone?.(
            event.conversation_id
              ? { conversation_id: event.conversation_id }
              : undefined
          );
      } catch {
        // ignore
      }
    } else {
      onDone?.();
    }
  } catch (err) {
    onError?.(err instanceof Error ? err : new Error(String(err)));
  }
}
