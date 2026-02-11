"use client";

import { useState, useRef, useEffect, useMemo } from "react";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Plus } from "lucide-react";
import { MemoryItemCard, type MemoryItem } from "@/components/memory-item-card";
import { MemorySection } from "@/components/memory-section";

const SESSION_RELATED_COUNT = 5;
const PAGE_SIZE = 10;

function createMockMemory(
  id: string,
  content: string,
  minutesAgo: number
): MemoryItem {
  return {
    id,
    content,
    createdAt: new Date(Date.now() - minutesAgo * 60 * 1000),
  };
}

const MOCK_ALL_MEMORIES: MemoryItem[] = [
  createMockMemory(
    "1",
    "用户正在开发一个以客户为中心的智能体项目，以智能客户代理和真人居间团队相结合的商业模式为主，计划以高科技智能背景为基础，提供软件开发、采购代理、咨询等多项服务。",
    5
  ),
  createMockMemory(
    "2",
    "用户偏好使用 Next.js 与 TypeScript 进行前端开发，后端使用 Clojure。",
    2 * 60
  ),
  createMockMemory("3", "用户关注股票数据与均线金叉等信号验证。", 24 * 60),
  createMockMemory("4", "用户常使用 Cursor 与 Calva 进行开发。", 2 * 24 * 60),
  createMockMemory("5", "用户对无限记忆与长程上下文能力有需求。", 3 * 24 * 60),
  createMockMemory("6", "项目代号阿福，目标是打造能听懂话、能跑代码的智能体。", 4 * 24 * 60),
  createMockMemory("7", "技术栈包含 SCI 沙盒、Datomic、resource-store。", 5 * 24 * 60),
  createMockMemory("8", "对话与消息正文存 resource-store，Datomic 只存结构。", 6 * 24 * 60),
  createMockMemory("9", "用户希望记忆支持向量检索与自然语言搜索。", 7 * 24 * 60),
  createMockMemory("10", "黄伟伟要的全套功能可通过聊天窗口与 SCI 函数实现。", 8 * 24 * 60),
  createMockMemory("11", "短信通知可附带通向聊天线索的 URL。", 9 * 24 * 60),
  createMockMemory("12", "定时任务让用户设定未来发生的事件。", 10 * 24 * 60),
  createMockMemory("13", "福聊中话题独立但信息互通。", 11 * 24 * 60),
  createMockMemory("14", "后端使用 reitit 做路由，next.jdbc 与 honey.sql。", 12 * 24 * 60),
  createMockMemory("15", "前端使用 Shadcn UI 与 Tailwind。", 13 * 24 * 60),
  createMockMemory("16", "消息可编辑重发，支持从根分叉或接在选中消息后。", 14 * 24 * 60),
  createMockMemory("17", "K 线数据有缓存与增量更新逻辑。", 15 * 24 * 60),
  createMockMemory("18", "tushare 用于获取股票行情。", 16 * 24 * 60),
  createMockMemory("19", "Agent 可主动发言，基于定时任务。", 17 * 24 * 60),
  createMockMemory("20", "Code is Data 为第一性原理。", 18 * 24 * 60),
];

function textMatch(content: string, query: string): boolean {
  const q = query.trim();
  if (!q) return true;
  const terms = q.split(/\s+/).filter(Boolean);
  return terms.every((term) => content.includes(term));
}

export type { MemoryItem };

type ManageMemorySheetProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ManageMemorySheet({ open, onOpenChange }: ManageMemorySheetProps) {
  const [allMemories, setAllMemories] = useState<MemoryItem[]>(MOCK_ALL_MEMORIES);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingContent, setEditingContent] = useState("");
  const [addFormVisible, setAddFormVisible] = useState(false);
  const [addDraft, setAddDraft] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [allPage, setAllPage] = useState(1);
  const [searchPage, setSearchPage] = useState(1);
  const menuRef = useRef<HTMLDivElement>(null);

  const sessionMemories = useMemo(
    () =>
      [...allMemories]
        .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
        .slice(0, SESSION_RELATED_COUNT),
    [allMemories]
  );

  const searchResults = useMemo(() => {
    const q = searchQuery.trim();
    if (!q) return [];
    return allMemories.filter((m) => textMatch(m.content, q));
  }, [allMemories, searchQuery]);

  const isSearchMode = searchQuery.trim() !== "";
  const allListToShow = isSearchMode ? searchResults : allMemories;
  const totalPages = Math.max(1, Math.ceil(allListToShow.length / PAGE_SIZE));
  const currentPage = isSearchMode ? searchPage : allPage;
  const setCurrentPage = isSearchMode ? setSearchPage : setAllPage;
  const paginatedList = useMemo(
    () =>
      allListToShow.slice(
        (currentPage - 1) * PAGE_SIZE,
        currentPage * PAGE_SIZE
      ),
    [allListToShow, currentPage]
  );

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

  const updateMemory = (id: string, updater: (m: MemoryItem) => MemoryItem) => {
    setAllMemories((prev) =>
      prev.map((m) => (m.id === id ? updater(m) : m))
    );
  };

  const removeMemory = (id: string) => {
    setOpenMenuId(null);
    setAllMemories((prev) => prev.filter((m) => m.id !== id));
  };

  const handleEdit = (item: MemoryItem) => {
    setOpenMenuId(null);
    setEditingId(item.id);
    setEditingContent(item.content);
  };

  const handleSaveEdit = () => {
    if (!editingId) return;
    updateMemory(editingId, (m) => ({ ...m, content: editingContent }));
    setEditingId(null);
    setEditingContent("");
  };

  const handleCancelEdit = () => {
    setEditingId(null);
    setEditingContent("");
  };

  const handleForget = (id: string) => removeMemory(id);

  const handleAddSubmit = () => {
    const trimmed = addDraft.trim();
    if (!trimmed) return;
    setAllMemories((prev) => [
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

  const renderCard = (item: MemoryItem) => (
    <MemoryItemCard
      key={item.id}
      item={item}
      isEditing={editingId === item.id}
      editingContent={editingContent}
      onEditingContentChange={setEditingContent}
      onSaveEdit={handleSaveEdit}
      onCancelEdit={handleCancelEdit}
      onEdit={() => handleEdit(item)}
      onForget={() => handleForget(item.id)}
      menuOpen={openMenuId === item.id}
      onMenuToggle={() =>
        setOpenMenuId((id) => (id === item.id ? null : item.id))
      }
      menuRef={menuRef}
    />
  );

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="flex min-h-0 w-full flex-col gap-0 overflow-hidden sm:max-w-md"
        showCloseButton={true}
      >
        <SheetHeader className="flex h-12 shrink-0 flex-row items-center gap-2 border-b px-4 pr-12">
          <SheetTitle className="text-lg font-semibold leading-none">
            管理记忆
          </SheetTitle>
          <div className="ml-auto flex h-8 items-center gap-1">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-8 w-8 shrink-0"
              onClick={() => setAddFormVisible(true)}
              aria-label="添加记忆"
            >
              <Plus className="size-4" />
            </Button>
          </div>
        </SheetHeader>

        <ScrollArea className="min-h-0 flex-1 px-4 py-3">
          <div className="flex flex-col gap-4">
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

            <MemorySection
              title="当前会话相关"
              searchResultCount={sessionMemories.length}
              list={sessionMemories}
              expandByDefault={true}
              renderItem={renderCard}
              emptyMessage="暂无记忆"
            />

            <MemorySection
              title={isSearchMode ? "搜索记忆" : "所有记忆"}
              searchResultCount={allListToShow.length}
              list={paginatedList}
              pagination={
                totalPages > 1
                  ? {
                      page: currentPage,
                      totalPages,
                      onPrev: () =>
                        setCurrentPage((p) => Math.max(1, p - 1)),
                      onNext: () =>
                        setCurrentPage((p) =>
                          Math.min(totalPages, p + 1)
                        ),
                    }
                  : null
              }
              expandByDefault={false}
              renderItem={renderCard}
              emptyMessage={isSearchMode ? "无匹配记忆" : "暂无记忆"}
              onExpand={() => {
                setAllPage(1);
                setSearchPage(1);
              }}
              search={{
                value: searchQuery,
                onChange: (v) => {
                  setSearchQuery(v);
                  setSearchPage(1);
                },
                placeholder: "自然语言搜索记忆…",
              }}
              totalCount={allMemories.length}
              pageSize={PAGE_SIZE}
            />
          </div>
        </ScrollArea>
      </SheetContent>
    </Sheet>
  );
}
