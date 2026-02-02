/**
 * 流式聊天：POST 到后端，消费 NDJSON 流（thinking / content / done），通过回调更新 UI。
 */

export type ChatStreamCallbacks = {
  onThinking?: (text: string | null) => void;
  onContent?: (chunk: string) => void;
  onDone?: () => void;
  onError?: (err: Error) => void;
};

type NDJSONEvent = { type: string; text?: string };

export async function streamChat(
  apiUrl: string,
  body: { text: string },
  callbacks: ChatStreamCallbacks
): Promise<void> {
  const { onThinking, onContent, onDone, onError } = callbacks;

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
            case "done":
              onDone?.();
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
        if (event.type === "done") onDone?.();
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
