"use client";

import { useChat } from "@ai-sdk/react";
import { TextStreamChatTransport } from "ai";
import { useState, useRef, useEffect } from "react";
import ReactMarkdown from "react-markdown";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";
import { Send, Loader2 } from "lucide-react";

const CHAT_API =
  typeof process.env.NEXT_PUBLIC_CHAT_API !== "undefined"
    ? process.env.NEXT_PUBLIC_CHAT_API
    : "http://localhost:4000/api/chat";

function getMessageText(message: { parts: Array<{ type: string; text?: string }> }): string {
  return message.parts
    .filter((p): p is { type: "text"; text: string } => p.type === "text" && typeof p.text === "string")
    .map((p) => p.text)
    .join("");
}

export default function ChatPage() {
  const [input, setInput] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  const { messages, sendMessage, status, error, clearError } = useChat({
    transport: new TextStreamChatTransport({ api: CHAT_API }),
  });

  const isLoading = status === "submitted" || status === "streaming";

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const text = input.trim();
    if (!text || isLoading) return;
    sendMessage({ text });
    setInput("");
  }

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
                {messages.length === 0 && (
                  <p className="text-muted-foreground text-center text-sm">
                    Send a message to start.
                  </p>
                )}
                {messages.map((message) => (
                  <div
                    key={message.id}
                    className={cn(
                      "flex",
                      message.role === "user"
                        ? "justify-end"
                        : "justify-start"
                    )}
                  >
                    <div
                      className={cn(
                        "max-w-[85%] rounded-lg px-3 py-2 text-sm",
                        message.role === "user"
                          ? "bg-primary text-primary-foreground"
                          : "bg-muted/60 text-foreground"
                      )}
                    >
                      {message.role === "user" ? (
                        <p className="whitespace-pre-wrap break-words">
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
                ))}
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
              <Input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Type a message…"
                disabled={isLoading}
                className="min-w-0 flex-1"
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
