"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import ReactMarkdown from "react-markdown";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { cn } from "@/lib/utils";
import {
  Send,
  Loader2,
  ChevronRight,
  ChevronDown,
  PanelLeft,
  MoreHorizontal,
  X,
} from "lucide-react";
import { streamChat } from "@/services/chat";
import {
  listConversations,
  getConversationMessages,
  type ConversationItem,
  type ConversationMessage,
} from "@/services/conversation";

const CHAT_API =
  typeof process.env.NEXT_PUBLIC_CHAT_API !== "undefined"
    ? process.env.NEXT_PUBLIC_CHAT_API
    : "http://localhost:4000/api/chat";

type MessagePart = { type: "text"; text: string };

export type StreamStep =
  | { id: string; type: "thinking"; text: string }
  | { id: string; type: "tool_call"; name: string; code: string }
  | { id: string; type: "tool_result"; result: unknown };

type Message = {
  id: string;
  role: "user" | "assistant";
  parts: MessagePart[];
  steps?: StreamStep[];
};

function getMessageText(message: Message): string {
  return message.parts
    .filter((p): p is MessagePart => p.type === "text" && typeof p.text === "string")
    .map((p) => p.text)
    .join("");
}

/** 将 API 返回的消息列表（assistant+tool_calls, role=tool）转为带 steps 的 Message[]。 */
function apiMessagesToMessages(list: ConversationMessage[]): Message[] {
  const out: Message[] = [];
  let i = 0;
  while (i < list.length) {
    const m = list[i];
    if (m.role === "user") {
      out.push({
        id: m.id,
        role: "user",
        parts: [{ type: "text", text: m.content ?? "" }],
      });
      i++;
      continue;
    }
    if (m.role === "assistant" && m.tool_calls?.length) {
      const toolCalls = m.tool_calls;
      const steps: StreamStep[] = [];
      for (let j = 0; j < toolCalls.length; j++) {
        const tc = toolCalls[j];
        let code = "";
        try {
          const args = JSON.parse(tc.function?.arguments ?? "{}");
          code = typeof args.code === "string" ? args.code : "";
        } catch {
          code = tc.function?.arguments ?? "";
        }
        steps.push({
          id: tc.id ?? newId(),
          type: "tool_call",
          name: tc.function?.name ?? "execute_clojure",
          code,
        });
        const toolMsg = list[i + 1 + j];
        if (toolMsg?.role === "tool") {
          let result: unknown = toolMsg.content ?? "";
          try {
            result = JSON.parse(toolMsg.content ?? "{}");
          } catch {
            result = toolMsg.content ?? "";
          }
          steps.push({
            id: toolMsg.id,
            type: "tool_result",
            result,
          });
        }
      }
      out.push({
        id: m.id,
        role: "assistant",
        parts: [{ type: "text", text: m.content ?? "" }],
        steps: steps.length > 0 ? steps : undefined,
      });
      i += 1 + toolCalls.length;
      continue;
    }
    if (m.role === "assistant") {
      out.push({
        id: m.id,
        role: "assistant",
        parts: [{ type: "text", text: m.content ?? "" }],
      });
      i++;
      continue;
    }
    if (m.role === "tool") {
      i++;
      continue;
    }
    i++;
  }
  return out;
}

function newId(): string {
  return crypto.randomUUID();
}

function stepSummary(step: StreamStep): string {
  switch (step.type) {
    case "thinking":
      return "Thinking";
    case "tool_call":
      return step.name;
    case "tool_result": {
      const r = step.result as Record<string, unknown>;
      return r && typeof r.error === "string" ? "Error" : "Result";
    }
    default:
      return "Step";
  }
}

function stepBody(step: StreamStep): string {
  switch (step.type) {
    case "thinking":
      return step.text;
    case "tool_call":
      return step.code;
    case "tool_result":
      return typeof step.result === "string"
        ? step.result
        : JSON.stringify(step.result, null, 2);
    default:
      return "";
  }
}

function CollapsibleStepBlock({
  step,
  expanded,
  onToggle,
}: {
  step: StreamStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const summary = stepSummary(step);
  const body = stepBody(step);
  return (
    <div className="rounded-md border border-border/60 bg-muted/30 text-sm">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-2 py-1.5 text-left text-muted-foreground hover:bg-muted/50"
      >
        {expanded ? (
          <ChevronDown className="size-4 shrink-0" />
        ) : (
          <ChevronRight className="size-4 shrink-0" />
        )}
        <span className="truncate">{summary}</span>
      </button>
      {expanded && (
        <div className="max-h-[16rem] overflow-auto border-t border-border/40">
          <pre className="min-w-0 whitespace-pre-wrap break-words p-2 font-mono text-xs">
            {body || "—"}
          </pre>
        </div>
      )}
    </div>
  );
}

type GroupKey = "today" | "yesterday" | "within7";

function groupConversationsByTime(items: ConversationItem[]): Record<GroupKey, ConversationItem[]> {
  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterdayStart = new Date(todayStart);
  yesterdayStart.setDate(yesterdayStart.getDate() - 1);
  const sevenDaysStart = new Date(todayStart);
  sevenDaysStart.setDate(sevenDaysStart.getDate() - 7);

  const today: ConversationItem[] = [];
  const yesterday: ConversationItem[] = [];
  const within7: ConversationItem[] = [];

  for (const item of items) {
    const t = item.updated_at ? new Date(item.updated_at) : new Date(0);
    if (t >= todayStart) today.push(item);
    else if (t >= yesterdayStart) yesterday.push(item);
    else if (t >= sevenDaysStart) within7.push(item);
  }

  return { today, yesterday, within7 };
}

const GROUP_LABELS: Record<GroupKey, string> = {
  today: "今天",
  yesterday: "昨天",
  within7: "7天内",
};

export default function ChatPage() {
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [streamingContent, setStreamingContent] = useState("");
  const [streamSteps, setStreamSteps] = useState<StreamStep[]>([]);
  const [status, setStatus] = useState<"idle" | "streaming">("idle");
  const [error, setError] = useState<Error | null>(null);
  const [expandedStepIds, setExpandedStepIds] = useState<Set<string>>(new Set());
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [conversationList, setConversationList] = useState<ConversationItem[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const isLoading = status === "streaming";

  const toggleStepExpanded = useCallback((id: string) => {
    setExpandedStepIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingContent, streamSteps]);

  useEffect(() => {
    if (!drawerOpen) return;
    setLoadingList(true);
    listConversations()
      .then(setConversationList)
      .catch(() => setConversationList([]))
      .finally(() => setLoadingList(false));
  }, [drawerOpen]);

  const loadConversation = useCallback(async (id: string) => {
    setLoadingMessages(true);
    try {
      const list = await getConversationMessages(id);
      const msgs = apiMessagesToMessages(list);
      setMessages(msgs);
      setConversationId(id);
      setDrawerOpen(false);
    } finally {
      setLoadingMessages(false);
    }
  }, []);

  const startNewChat = useCallback(() => {
    setMessages([]);
    setConversationId(null);
    setStreamingContent("");
    setStreamSteps([]);
    setError(null);
    setDrawerOpen(false);
  }, []);

  const clearError = useCallback(() => setError(null), []);

  const streamingRef = useRef("");
  const streamStepsRef = useRef<StreamStep[]>([]);

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      const text = input.trim();
      if (!text || isLoading) return;

      const userMessage: Message = {
        id: newId(),
        role: "user",
        parts: [{ type: "text", text }],
      };
      setMessages((prev) => [...prev, userMessage]);
      setInput("");
      setStreamingContent("");
      streamingRef.current = "";
      setStreamSteps([]);
      streamStepsRef.current = [];
      setError(null);
      setStatus("streaming");

      streamChat(
        CHAT_API,
        { text, ...(conversationId && { conversation_id: conversationId }) },
        {
        onThinking: (t) => {
          if (t != null) {
            const step: StreamStep = {
              id: newId(),
              type: "thinking",
              text: t,
            };
            streamStepsRef.current = [...streamStepsRef.current, step];
            setStreamSteps(streamStepsRef.current);
          }
        },
        onContent: (chunk) => {
          setStreamingContent((prev) => {
            const next = prev + chunk;
            streamingRef.current = next;
            return next;
          });
        },
        onToolCall: (data) => {
          const step: StreamStep = {
            id: newId(),
            type: "tool_call",
            name: data.name,
            code: data.code,
          };
          streamStepsRef.current = [...streamStepsRef.current, step];
          setStreamSteps(streamStepsRef.current);
        },
        onToolResult: (data) => {
          const step: StreamStep = {
            id: newId(),
            type: "tool_result",
            result: data,
          };
          streamStepsRef.current = [...streamStepsRef.current, step];
          setStreamSteps(streamStepsRef.current);
        },
        onDone: (opts) => {
          const finalContent = streamingRef.current;
          const steps = streamStepsRef.current;
          if (opts?.conversation_id) setConversationId(opts.conversation_id);
          setMessages((prev) => [
            ...prev,
            {
              id: newId(),
              role: "assistant",
              parts: [{ type: "text", text: finalContent }],
              steps: steps.length > 0 ? steps : undefined,
            },
          ]);
          setStreamingContent("");
          setStreamSteps([]);
          streamStepsRef.current = [];
          setStatus("idle");
        },
        onError: (err) => {
          setError(err);
          setStreamingContent("");
          setStreamSteps([]);
          setStatus("idle");
        },
      }
    );
    },
    [input, isLoading, conversationId]
  );

  const grouped = groupConversationsByTime(conversationList);
  const hasAny = grouped.today.length + grouped.yesterday.length + grouped.within7.length > 0;

  return (
    <main className="flex min-h-screen flex-col bg-gradient-to-b from-background via-background to-muted/20">
      <div className="container mx-auto flex max-w-2xl flex-1 flex-col gap-4 p-4">
        <Card className="flex flex-1 flex-col overflow-hidden border-border/60 bg-card/95 shadow-sm">
          <CardHeader className="shrink-0 border-b px-4 py-3">
            <div className="flex items-center justify-between gap-2">
              <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
                <SheetTrigger asChild>
                  <Button variant="ghost" size="icon" className="shrink-0" aria-label="打开历史会话">
                    <PanelLeft className="size-5" />
                  </Button>
                </SheetTrigger>
                <SheetContent side="left" showCloseButton={false} className="w-[280px] sm:max-w-[320px] p-0 flex flex-col">
                  <SheetHeader className="border-b px-4 py-3">
                    <div className="flex items-center justify-between gap-2">
                      <SheetTitle className="text-base font-semibold">最近</SheetTitle>
                      <div className="flex items-center gap-1">
                        <Button variant="ghost" size="icon" className="h-8 w-8" aria-label="更多">
                          <MoreHorizontal className="size-4 text-muted-foreground" />
                        </Button>
                        <SheetClose asChild>
                          <Button variant="ghost" size="icon" className="h-8 w-8" aria-label="关闭">
                            <X className="size-4 text-muted-foreground" />
                          </Button>
                        </SheetClose>
                      </div>
                    </div>
                  </SheetHeader>
                  <div className="flex-1 overflow-hidden flex flex-col min-h-0">
                    <Button
                      variant="ghost"
                      className="w-full justify-start rounded-none border-b font-normal"
                      onClick={startNewChat}
                    >
                      新对话
                    </Button>
                    <ScrollArea className="flex-1">
                      <div className="p-2">
                        {loadingList ? (
                          <p className="text-muted-foreground text-sm py-4 text-center">加载中…</p>
                        ) : !hasAny ? (
                          <p className="text-muted-foreground text-sm py-4 text-center">暂无历史会话</p>
                        ) : (
                          (["today", "yesterday", "within7"] as const).map((key) => {
                            const list = grouped[key];
                            if (list.length === 0) return null;
                            return (
                              <div key={key} className="mb-4">
                                <p className="text-muted-foreground text-xs font-medium px-2 py-1.5">
                                  {GROUP_LABELS[key]}
                                </p>
                                <ul className="space-y-0.5">
                                  {list.map((item) => (
                                    <li key={item.id}>
                                      <button
                                        type="button"
                                        disabled={loadingMessages}
                                        onClick={() => loadConversation(item.id)}
                                        className={cn(
                                          "w-full text-left rounded-md px-2 py-2 text-sm truncate flex items-center gap-2 group",
                                          conversationId === item.id
                                            ? "bg-muted"
                                            : "hover:bg-muted/60"
                                        )}
                                      >
                                        <span className="min-w-0 flex-1 truncate">{item.title}</span>
                                        <span className="shrink-0 opacity-0 group-hover:opacity-70">
                                          <MoreHorizontal className="size-4 text-muted-foreground" />
                                        </span>
                                      </button>
                                    </li>
                                  ))}
                                </ul>
                              </div>
                            );
                          })
                        )}
                      </div>
                    </ScrollArea>
                  </div>
                </SheetContent>
              </Sheet>
              <CardTitle className="text-lg font-semibold tracking-tight">
                Chat
              </CardTitle>
              <div className="w-10" />
            </div>
          </CardHeader>
          <CardContent className="flex min-h-0 flex-1 flex-col gap-4 p-0">
            <ScrollArea className="flex-1 px-4">
              <div className="flex flex-col gap-4 py-4">
                {messages.length === 0 && !streamingContent && streamSteps.length === 0 && (
                  <p className="text-muted-foreground text-center text-sm">
                    Send a message to start.
                  </p>
                )}
                {messages.map((message) => (
                  <div
                    key={message.id}
                    className={cn(
                      "flex flex-col gap-2",
                      message.role === "user"
                        ? "items-end"
                        : "items-start"
                    )}
                  >
                    {message.role === "assistant" &&
                      message.steps &&
                      message.steps.length > 0 && (
                        <div className="flex max-w-[85%] flex-col gap-1">
                          {message.steps.map((step) => (
                            <CollapsibleStepBlock
                              key={step.id}
                              step={step}
                              expanded={expandedStepIds.has(step.id)}
                              onToggle={() => toggleStepExpanded(step.id)}
                            />
                          ))}
                        </div>
                      )}
                    {(message.role === "user" || getMessageText(message) !== "") && (
                      <div
                        className={cn(
                          "flex w-full min-w-0",
                          message.role === "user"
                            ? "justify-end"
                            : "justify-start"
                        )}
                      >
                        <div
                          className={cn(
                            "w-full min-w-0 max-w-[85%] overflow-auto rounded-lg px-3 py-2 text-sm",
                            message.role === "user"
                              ? "bg-primary text-primary-foreground"
                              : "bg-muted/60 text-foreground"
                          )}
                        >
                          {message.role === "user" ? (
                            <p className="min-w-0 whitespace-pre-wrap break-words">
                              {getMessageText(message)}
                            </p>
                          ) : (
                            <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_pre]:max-w-full [&_ul]:my-1">
                              <ReactMarkdown>
                                {getMessageText(message)}
                              </ReactMarkdown>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                ))}
                {streamSteps.length > 0 && (
                  <div className="flex max-w-[85%] flex-col gap-1">
                    {streamSteps.map((step) => (
                      <CollapsibleStepBlock
                        key={step.id}
                        step={step}
                        expanded={expandedStepIds.has(step.id)}
                        onToggle={() => toggleStepExpanded(step.id)}
                      />
                    ))}
                  </div>
                )}
                {streamingContent && (
                  <div className="flex w-full min-w-0 justify-start">
                    <div className="w-full min-w-0 max-w-[85%] overflow-auto rounded-lg px-3 py-2 text-sm bg-muted/60 text-foreground">
                      <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_pre]:max-w-full [&_ul]:my-1">
                        <ReactMarkdown>{streamingContent || "…"}</ReactMarkdown>
                      </div>
                    </div>
                  </div>
                )}
                <div ref={scrollRef} />
              </div>
            </ScrollArea>
            {error && (
              <div className="border-t px-4 py-2">
                <p className="text-destructive text-sm">{error.message}</p>
                <Button
                  variant="ghost"
                  size="sm"
                  className="mt-1"
                  onClick={clearError}
                >
                  Dismiss
                </Button>
              </div>
            )}
            <form
              onSubmit={handleSubmit}
              className="flex shrink-0 gap-2 border-t p-4"
            >
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    (e.target as HTMLTextAreaElement).form?.requestSubmit();
                  }
                }}
                placeholder="Type a message… (Shift+Enter for new line)"
                disabled={isLoading}
                rows={1}
                className="border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] dark:bg-input/30 min-h-9 w-full min-w-0 flex-1 resize-y rounded-md border border-input bg-transparent px-3 py-2 text-base shadow-xs outline-none disabled:pointer-events-none disabled:opacity-50 md:text-sm max-h-[12rem]"
              />
              <Button type="submit" disabled={isLoading || !input.trim()} size="icon">
                {isLoading ? (
                  <Loader2 className="size-4 animate-spin" />
                ) : (
                  <Send className="size-4" />
                )}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
