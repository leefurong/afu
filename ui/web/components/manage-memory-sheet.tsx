"use client";

import { useState, useRef, useEffect } from "react";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Plus, Pencil, Trash2, MoreVertical } from "lucide-react";

const MEMORY_LIMIT = 15;

export type MemoryItem = {
  id: string;
  content: string;
  createdAt: Date;
};

function formatRelativeTime(date: Date): string {
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60_000);
  const diffHours = Math.floor(diffMs / 3_600_000);
  const diffDays = Math.floor(diffMs / 86_400_000);
  if (diffMins < 1) return "刚刚";
  if (diffMins < 60) return `${diffMins}分钟前`;
  if (diffHours < 24) return `${diffHours}小时前`;
  if (diffDays < 7) return `${diffDays}天前`;
  return date.toLocaleDateString();
}

const MOCK_MEMORIES: MemoryItem[] = [
  {
    id: "1",
    content:
      "用户正在开发一个以客户为中心的智能体项目，以智能客户代理和真人居间团队相结合的商业模式为主，计划以高科技智能背景为基础，提供软件开发、采购代理、咨询等多项服务。",
    createdAt: new Date(Date.now() - 5 * 60 * 1000),
  },
  {
    id: "2",
    content: "用户偏好使用 Next.js 与 TypeScript 进行前端开发，后端使用 Clojure。",
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000),
  },
  {
    id: "3",
    content: "用户关注股票数据与均线金叉等信号验证。",
    createdAt: new Date(Date.now() - 24 * 60 * 60 * 1000),
  },
];

type ManageMemorySheetProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ManageMemorySheet({ open, onOpenChange }: ManageMemorySheetProps) {
  const [memories, setMemories] = useState<MemoryItem[]>(MOCK_MEMORIES);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingContent, setEditingContent] = useState("");
  const [addFormVisible, setAddFormVisible] = useState(false);
  const [addDraft, setAddDraft] = useState("");
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      setOpenMenuId(null);
      setEditingId(null);
      setAddFormVisible(false);
      setAddDraft("");
    }
  }, [open]);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpenMenuId(null);
      }
    };
    document.addEventListener("click", handleClickOutside);
    return () => document.removeEventListener("click", handleClickOutside);
  }, []);

  const handleEdit = (item: MemoryItem) => {
    setOpenMenuId(null);
    setEditingId(item.id);
    setEditingContent(item.content);
  };

  const handleSaveEdit = () => {
    if (!editingId) return;
    setMemories((prev) =>
      prev.map((m) =>
        m.id === editingId ? { ...m, content: editingContent } : m
      )
    );
    setEditingId(null);
    setEditingContent("");
  };

  const handleCancelEdit = () => {
    setEditingId(null);
    setEditingContent("");
  };

  const handleForget = (id: string) => {
    setOpenMenuId(null);
    setMemories((prev) => prev.filter((m) => m.id !== id));
  };

  const handleAddSubmit = () => {
    const trimmed = addDraft.trim();
    if (!trimmed || memories.length >= MEMORY_LIMIT) return;
    setMemories((prev) => [
      {
        id: crypto.randomUUID(),
        content: trimmed,
        createdAt: new Date(),
      },
      ...prev,
    ]);
    setAddDraft("");
    setAddFormVisible(false);
  };

  const canAdd = memories.length < MEMORY_LIMIT;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="flex w-full flex-col gap-0 sm:max-w-md"
        showCloseButton={true}
      >
        <SheetHeader className="flex h-12 shrink-0 flex-row items-center gap-2 border-b px-4 pr-12">
          <SheetTitle className="text-lg font-semibold leading-none">管理记忆</SheetTitle>
          <div className="ml-auto flex h-8 items-center gap-1">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-8 w-8 shrink-0"
              onClick={() => setAddFormVisible(true)}
              disabled={!canAdd}
              aria-label="添加记忆"
            >
              <Plus className="size-4" />
            </Button>
          </div>
        </SheetHeader>

        <p className="text-muted-foreground border-b px-4 py-3 text-sm">
          阿福最多会帮你存储 {MEMORY_LIMIT} 条记忆，记忆条目已满将不会新增记忆条目，阿福会动态地帮你更新和维护已有的记忆内容。
        </p>

        <ScrollArea className="flex-1 px-4 py-3">
          <div className="flex flex-col gap-3">
            {addFormVisible && (
              <div className="rounded-lg border bg-muted/30 p-3">
                <textarea
                  value={addDraft}
                  onChange={(e) => setAddDraft(e.target.value)}
                  placeholder="输入新记忆内容…"
                  rows={3}
                  className="border-input placeholder:text-muted-foreground w-full resize-y rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
                />
                <div className="mt-2 flex justify-end gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setAddFormVisible(false);
                      setAddDraft("");
                    }}
                  >
                    取消
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    onClick={handleAddSubmit}
                    disabled={!addDraft.trim()}
                  >
                    保存
                  </Button>
                </div>
              </div>
            )}

            {memories.map((item) => (
              <div
                key={item.id}
                className="rounded-lg border bg-card p-3 text-card-foreground shadow-sm"
              >
                {editingId === item.id ? (
                  <>
                    <textarea
                      value={editingContent}
                      onChange={(e) => setEditingContent(e.target.value)}
                      rows={3}
                      className="border-input mb-2 w-full resize-y rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
                    />
                    <div className="flex justify-end gap-2">
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={handleCancelEdit}
                      >
                        取消
                      </Button>
                      <Button type="button" size="sm" onClick={handleSaveEdit}>
                        保存
                      </Button>
                    </div>
                  </>
                ) : (
                  <>
                    <p className="text-sm leading-relaxed line-clamp-4">
                      {item.content}
                    </p>
                    <div className="mt-2 flex items-center justify-between gap-2">
                      <span className="text-muted-foreground text-xs">
                        {formatRelativeTime(item.createdAt)}
                      </span>
                      <div className="relative" ref={openMenuId === item.id ? menuRef : undefined}>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={(e) => {
                            e.stopPropagation();
                            setOpenMenuId((id) => (id === item.id ? null : item.id));
                          }}
                          aria-label="更多操作"
                        >
                          <MoreVertical className="size-4" />
                        </Button>
                        {openMenuId === item.id && (
                          <div
                            className="bg-popover text-popover-foreground absolute right-0 top-full z-10 mt-1 min-w-[100px] rounded-md border py-1 shadow-md"
                            role="menu"
                          >
                            <button
                              type="button"
                              className="hover:bg-accent flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
                              onClick={() => handleEdit(item)}
                            >
                              <Pencil className="size-3.5" />
                              编辑
                            </button>
                            <button
                              type="button"
                              className="hover:bg-accent text-destructive flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
                              onClick={() => handleForget(item.id)}
                            >
                              <Trash2 className="size-3.5" />
                              忘记
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  </>
                )}
              </div>
            ))}
          </div>
        </ScrollArea>
      </SheetContent>
    </Sheet>
  );
}
