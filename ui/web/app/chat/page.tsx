"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import ReactMarkdown from "react-markdown";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";
import { Send, Loader2, ChevronRight, ChevronDown } from "lucide-react";
import { streamChat } from "@/services/chat";

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
        <div className="max-h-[7.5rem] overflow-y-auto border-t border-border/40">
          <pre className="whitespace-pre-wrap break-words p-2 font-mono text-xs">
            {body || "—"}
          </pre>
        </div>
      )}
    </div>
  );
}

export default function ChatPage() {
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [streamingContent, setStreamingContent] = useState("");
  const [streamSteps, setStreamSteps] = useState<StreamStep[]>([]);
  const [status, setStatus] = useState<"idle" | "streaming">("idle");
  const [error, setError] = useState<Error | null>(null);
  const [expandedStepIds, setExpandedStepIds] = useState<Set<string>>(new Set());
  const [conversationId, setConversationId] = useState<string | null>(null);
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

  return (
    <main className="flex min-h-screen flex-col bg-gradient-to-b from-background via-background to-muted/20">
      <div className="container mx-auto flex max-w-2xl flex-1 flex-col gap-4 p-4">
        <Card className="flex flex-1 flex-col overflow-hidden border-border/60 bg-card/95 shadow-sm">
          <CardHeader className="shrink-0 border-b px-4 py-3">
            <CardTitle className="text-lg font-semibold tracking-tight">
              Chat
            </CardTitle>
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
                          "w-full min-w-0 max-w-[85%] rounded-lg px-3 py-2 text-sm",
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
                          <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_ul]:my-1">
                            <ReactMarkdown>
                              {getMessageText(message) || "…"}
                            </ReactMarkdown>
                          </div>
                        )}
                      </div>
                    </div>
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
                    <div className="w-full min-w-0 max-w-[85%] rounded-lg px-3 py-2 text-sm bg-muted/60 text-foreground">
                      <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_ul]:my-1">
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
                  if (e.key === "Enter" && !e.shiftKey) {
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
