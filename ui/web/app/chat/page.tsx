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
import { ManageMemorySheet } from "@/components/manage-memory-sheet";
import { cn } from "@/lib/utils";
import {
  Send,
  Loader2,
  ChevronRight,
  ChevronDown,
  ChevronLeft,
  PanelLeft,
  MoreHorizontal,
  X,
  Brain,
} from "lucide-react";
import { MessageToolBar } from "@/components/message-tool-bar";
import { streamChat } from "@/services/chat";
import {
  listConversations,
  getConversationMessages,
  switchConversationBranchLeft,
  switchConversationBranchRight,
  type ConversationItem,
  type ConversationMessage,
} from "@/services/conversation";
import { parseEDNString } from "edn-data";

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
  can_left?: boolean;
  can_right?: boolean;
  sibling_index?: number;
  sibling_total?: number;
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
        can_left: m.can_left,
        can_right: m.can_right,
        sibling_index: m.sibling_index,
        sibling_total: m.sibling_total,
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
        can_left: m.can_left,
        can_right: m.can_right,
        sibling_index: m.sibling_index,
        sibling_total: m.sibling_total,
      });
      i += 1 + toolCalls.length;
      continue;
    }
    if (m.role === "assistant") {
      out.push({
        id: m.id,
        role: "assistant",
        parts: [{ type: "text", text: m.content ?? "" }],
        can_left: m.can_left,
        can_right: m.can_right,
        sibling_index: m.sibling_index,
        sibling_total: m.sibling_total,
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

/** 归一化 tool result：流式是对象，历史可能是 JSON 或 EDN 字符串。用 edn-data 解析 EDN，统一成对象再按形状渲染。 */
function normalizeToolResult(result: unknown): unknown {
  if (typeof result !== "string") return result;
  try {
    return JSON.parse(result) as unknown;
  } catch {
    try {
      return parseEDNString(result, {
        mapAs: "object",
        keywordAs: "string",
      }) as unknown;
    } catch {
      return result;
    }
  }
}

/** execute_clojure 工具返回的结构：{ ok?, out?, error? }。仅按数据形状判断，不区分来源。 */
function isExecuteClojureResult(
  r: unknown
): r is { ok?: unknown; out?: string; error?: string } {
  return (
    r !== null &&
    typeof r === "object" &&
    !Array.isArray(r) &&
    ("ok" in r || "out" in r || "error" in r)
  );
}

function formatOkForDisplay(ok: unknown): string {
  if (ok === null || ok === undefined) return "成功";
  if (typeof ok === "string") return ok;
  if (typeof ok === "number" || typeof ok === "boolean") return String(ok);
  if (typeof ok === "object") return JSON.stringify(ok, null, 2);
  return "成功";
}

/** 声明式：根据 result 形状选择展示方式。来源（流式 / 历史）无关。 */
function ToolResultBody({ result }: { result: unknown }) {
  const normalized = normalizeToolResult(result);
  if (!isExecuteClojureResult(normalized)) {
    const body = typeof normalized === "string" ? normalized : JSON.stringify(normalized, null, 2);
    return (
      <pre className="min-w-0 whitespace-pre-wrap break-words p-2 font-mono text-xs">
        {body || "—"}
      </pre>
    );
  }

  const hasError = typeof normalized.error === "string" && normalized.error.length > 0;
  const hasOk = "ok" in normalized;
  const hasOut = typeof normalized.out === "string" && normalized.out.length > 0;

  return (
    <div className="flex flex-col gap-2 p-2">
      {hasError && (
        <pre className="min-w-0 max-h-[14rem] overflow-auto rounded border border-red-500/70 bg-red-500/10 px-2 py-1.5 text-xs text-red-700 dark:text-red-300 whitespace-pre-wrap break-words">
          {normalized.error}
        </pre>
      )}
      {hasOk && !hasError && (
        <pre className="min-w-0 max-h-[14rem] overflow-auto rounded border border-green-600/60 bg-green-500/10 px-2 py-1.5 text-xs text-green-800 dark:text-green-200 whitespace-pre-wrap break-words">
          {formatOkForDisplay(normalized.ok)}
        </pre>
      )}
      {hasOut && (
        <pre className="min-w-0 max-h-[14rem] overflow-auto rounded bg-[#1e1e1e] px-2 py-1.5 font-mono text-xs text-[#e0e0e0] whitespace-pre-wrap break-words">
          {(normalized.out ?? "").replace(/\\n/g, "\n")}
        </pre>
      )}
      {!hasError && !hasOk && !hasOut && (
        <span className="text-muted-foreground text-xs">—</span>
      )}
    </div>
  );
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
  const isToolResult = step.type === "tool_result";
  const body = !isToolResult ? stepBody(step) : "";

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
          {isToolResult ? (
            <ToolResultBody result={step.result} />
          ) : (
            <pre className="min-w-0 whitespace-pre-wrap break-words p-2 font-mono text-xs">
              {body || "—"}
            </pre>
          )}
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
  const [headerStuck, setHeaderStuck] = useState(false);
  /** 编辑/重发时：新消息将接在此 id 对应消息之后（fork）。有值时输入框为「编辑后发送」态。 */
  const [targetParentId, setTargetParentId] = useState<string | null>(null);
  /** 编辑第一条消息时为 true，发送时走「从根分叉」逻辑。 */
  const [branchFromRoot, setBranchFromRoot] = useState(false);
  const [memoryOpen, setMemoryOpen] = useState(false);

  const isLoading = status === "streaming";

  useEffect(() => {
    const threshold = 8;
    const onScroll = () => setHeaderStuck(window.scrollY > threshold);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

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
    setTargetParentId(null);
    setBranchFromRoot(false);
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
    setTargetParentId(null);
    setBranchFromRoot(false);
    setDrawerOpen(false);
  }, []);

  const clearError = useCallback(() => setError(null), []);

  const handleSwitchBranch = useCallback(
    (direction: "left" | "right", childMessageId: string) => {
      if (!conversationId) return;
      const fn =
        direction === "left" ? switchConversationBranchLeft : switchConversationBranchRight;
      fn(conversationId, childMessageId)
        .then((list) => setMessages(apiMessagesToMessages(list)))
        .catch((err) => setError(err instanceof Error ? err : new Error(String(err))));
    },
    [conversationId]
  );

  const streamingRef = useRef("");
  const streamStepsRef = useRef<StreamStep[]>([]);

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      const text = input.trim();
      if (!text || isLoading) return;

      const parentId = targetParentId;
      const fromRoot = branchFromRoot;
      const userMessage: Message = {
        id: newId(),
        role: "user",
        parts: [{ type: "text", text }],
      };
      setMessages((prev) => {
        if (fromRoot) return [userMessage];
        if (parentId == null) return [...prev, userMessage];
        const idx = prev.findIndex((m) => m.id === parentId);
        if (idx < 0) return [...prev, userMessage];
        return [...prev.slice(0, idx + 1), userMessage];
      });
      setInput("");
      setTargetParentId(null);
      setBranchFromRoot(false);
      setStreamingContent("");
      streamingRef.current = "";
      setStreamSteps([]);
      streamStepsRef.current = [];
      setError(null);
      setStatus("streaming");

      streamChat(
        CHAT_API,
        {
          text,
          ...(conversationId && { conversation_id: conversationId }),
          ...(parentId != null && { prev_message_id: parentId }),
          ...(fromRoot && { branch_from_root: true }),
        },
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
          const cid = opts?.conversation_id ?? conversationId;
          if (opts?.conversation_id) setConversationId(opts.conversation_id);
          setTargetParentId(null);
          setBranchFromRoot(false);
          setStreamingContent("");
          setStreamSteps([]);
          streamStepsRef.current = [];
          setStatus("idle");
          if (cid) {
            getConversationMessages(cid)
              .then(apiMessagesToMessages)
              .then(setMessages);
          } else {
            setMessages((prev) => [
              ...prev,
              {
                id: newId(),
                role: "assistant",
                parts: [{ type: "text", text: finalContent }],
                steps: steps.length > 0 ? steps : undefined,
              },
            ]);
          }
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
    [input, isLoading, conversationId, targetParentId, branchFromRoot]
  );

  const grouped = groupConversationsByTime(conversationList);
  const hasAny = grouped.today.length + grouped.yesterday.length + grouped.within7.length > 0;

  return (
    <main className="flex h-screen flex-col overflow-hidden bg-gradient-to-b from-background via-background to-muted/20">
      <div className="container mx-auto flex min-h-0 max-w-2xl flex-1 flex-col gap-4 p-4">
        <Card className="flex min-h-0 flex-1 flex-col border-border/60 bg-card/95 shadow-sm">
          <CardHeader
            className={cn(
              "sticky top-0 z-10 shrink-0 border-b bg-card/95 backdrop-blur-[6px] px-4 transition-[padding] duration-200",
              headerStuck ? "py-2" : "py-3"
            )}
          >
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
                      className="w-full justify-start rounded-none border-b font-normal shrink-0"
                      onClick={startNewChat}
                    >
                      新对话
                    </Button>
                    <ScrollArea className="flex-1 min-h-0">
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
              <CardTitle
                className={cn(
                  "font-semibold tracking-tight transition-[font-size] duration-200",
                  headerStuck ? "text-base" : "text-lg"
                )}
              >
                Chat
              </CardTitle>
              <div className="w-10" />
            </div>
          </CardHeader>
          <CardContent className="flex min-h-0 flex-1 flex-col gap-4 p-0">
            <ScrollArea className="min-h-0 flex-1 px-4">
              <div className="flex flex-col gap-4 py-4">
                {messages.length === 0 && !streamingContent && streamSteps.length === 0 && (
                  <p className="text-muted-foreground text-center text-sm">
                    Send a message to start.
                  </p>
                )}
                {messages.map((message, index) => (
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
                          "flex w-full min-w-0 flex-col gap-1",
                          message.role === "user"
                            ? "items-end justify-end"
                            : "items-start justify-start"
                        )}
                      >
                        {message.role === "user" ? (
                          <div className="group flex w-full min-w-0 max-w-[85%] flex-col items-end gap-1">
                            {(message.can_left || message.can_right) && (
                                <div className="flex items-center gap-0.5 rounded-md border border-border/60 bg-card/80 px-1 py-0.5 text-muted-foreground">
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="h-6 w-6"
                                    disabled={!message.can_left}
                                    onClick={() => handleSwitchBranch("left", message.id)}
                                    aria-label="上一分支"
                                  >
                                    <ChevronLeft className="size-3.5" />
                                  </Button>
                                  <span className="min-w-[2rem] text-center text-xs">
                                    {message.sibling_index ?? 1}/{message.sibling_total ?? 1}
                                  </span>
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="h-6 w-6"
                                    disabled={!message.can_right}
                                    onClick={() => handleSwitchBranch("right", message.id)}
                                    aria-label="下一分支"
                                  >
                                    <ChevronRight className="size-3.5" />
                                  </Button>
                                </div>
                              )}
                            <div
                              className={cn(
                                "w-full min-w-0 overflow-auto rounded-lg px-3 py-2 text-sm",
                                "bg-primary text-primary-foreground"
                              )}
                            >
                              <p className="min-w-0 whitespace-pre-wrap break-words">
                                {getMessageText(message)}
                              </p>
                            </div>
                            <MessageToolBar
                              alignRight
                              onEdit={() => {
                                setInput(getMessageText(message));
                                if (index > 0) {
                                  setTargetParentId(messages[index - 1].id);
                                  setBranchFromRoot(false);
                                } else {
                                  setTargetParentId(null);
                                  setBranchFromRoot(true);
                                }
                              }}
                            />
                          </div>
                        ) : (
                          <div className="flex w-full min-w-0 flex-col items-start gap-1">
                            {(message.can_left || message.can_right) && (
                                <div className="flex items-center gap-0.5 rounded-md border border-border/60 bg-card/80 px-1 py-0.5 text-muted-foreground">
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="h-6 w-6"
                                    disabled={!message.can_left}
                                    onClick={() => handleSwitchBranch("left", message.id)}
                                    aria-label="上一分支"
                                  >
                                    <ChevronLeft className="size-3.5" />
                                  </Button>
                                  <span className="min-w-[2rem] text-center text-xs">
                                    {message.sibling_index ?? 1}/{message.sibling_total ?? 1}
                                  </span>
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="h-6 w-6"
                                    disabled={!message.can_right}
                                    onClick={() => handleSwitchBranch("right", message.id)}
                                    aria-label="下一分支"
                                  >
                                    <ChevronRight className="size-3.5" />
                                  </Button>
                                </div>
                              )}
                            <div
                              className={cn(
                                "w-full min-w-0 max-w-[85%] overflow-auto rounded-lg px-3 py-2 text-sm",
                                "bg-muted/60 text-foreground"
                              )}
                            >
                              <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_pre]:max-w-full [&_ul]:my-1">
                                <ReactMarkdown>
                                  {getMessageText(message)}
                                </ReactMarkdown>
                              </div>
                            </div>
                          </div>
                        )}
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
            {(targetParentId != null || branchFromRoot) && (
              <div className="flex items-center justify-between gap-2 border-t border-border/60 px-4 py-1.5 text-sm text-muted-foreground">
                <span>
                  {branchFromRoot
                    ? "将从会话开头发送（编辑第一条并重发）"
                    : "将在此消息后发送（编辑并重发）"}
                </span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setTargetParentId(null);
                    setBranchFromRoot(false);
                    setInput("");
                  }}
                >
                  取消
                </Button>
              </div>
            )}
            <div className="flex shrink-0 flex-col border-t">
              <div className="flex items-center gap-1 px-4 py-2">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-muted-foreground hover:text-foreground"
                  onClick={() => setMemoryOpen(true)}
                  aria-label="记忆"
                >
                  <Brain className="size-4" />
                </Button>
              </div>
            <form
              onSubmit={handleSubmit}
              className="flex gap-2 p-4 pt-0"
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
                placeholder={
                  branchFromRoot
                    ? "修改后发送，将从会话开头重新开始…"
                    : targetParentId != null
                      ? "修改后发送，将接在上方选中消息后…"
                      : "Type a message… (Shift+Enter for new line)"
                }
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
            </div>
            <ManageMemorySheet open={memoryOpen} onOpenChange={setMemoryOpen} />
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
