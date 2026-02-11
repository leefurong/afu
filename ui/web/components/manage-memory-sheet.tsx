"use client";

import { useState, useRef, useEffect, useMemo, useCallback } from "react";
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
import {
  listMemories,
  addMemory,
  updateMemory,
  deleteMemory,
} from "@/services/memory";

const SESSION_RELATED_COUNT = 5;
const PAGE_SIZE = 10;

export type { MemoryItem };

type ManageMemorySheetProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ManageMemorySheet({ open, onOpenChange }: ManageMemorySheetProps) {
  const [sessionMemories, setSessionMemories] = useState<MemoryItem[]>([]);
  const [mainList, setMainList] = useState<MemoryItem[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingContent, setEditingContent] = useState("");
  const [addFormVisible, setAddFormVisible] = useState(false);
  const [addDraft, setAddDraft] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [allPage, setAllPage] = useState(1);
  const [searchPage, setSearchPage] = useState(1);
  const menuRef = useRef<HTMLDivElement>(null);

  const isSearchMode = searchQuery.trim() !== "";
  const currentPage = isSearchMode ? searchPage : allPage;
  const setCurrentPage = isSearchMode ? setSearchPage : setAllPage;
  const totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));

  const fetchSession = useCallback(async () => {
    const { items } = await listMemories({ pageSize: SESSION_RELATED_COUNT });
    setSessionMemories(items);
  }, []);

  const fetchMain = useCallback(
    async (page: number, q: string) => {
      setLoading(true);
      setError(null);
      try {
        const { items, totalCount: total } = await listMemories({
          page,
          pageSize: PAGE_SIZE,
          q: q.trim() || undefined,
        });
        setMainList(items);
        setTotalCount(total);
      } catch (e) {
        setError(e instanceof Error ? e.message : "加载失败");
        setMainList([]);
        setTotalCount(0);
      } finally {
        setLoading(false);
      }
    },
    []
  );

  const [debouncedQuery, setDebouncedQuery] = useState("");
  const lastFetchRef = useRef({ page: 0, query: "" });
  useEffect(() => {
    if (!open) return;
    const t = setTimeout(() => setDebouncedQuery(searchQuery), 300);
    return () => clearTimeout(t);
  }, [open, searchQuery]);

  useEffect(() => {
    if (!open) return;
    setError(null);
    fetchSession();
  }, [open, fetchSession]);

  useEffect(() => {
    if (!open) return;
    const page =
      debouncedQuery !== lastFetchRef.current.query ? 1 : currentPage;
    if (debouncedQuery !== lastFetchRef.current.query) {
      setAllPage(1);
      setSearchPage(1);
    }
    if (
      lastFetchRef.current.page === page &&
      lastFetchRef.current.query === debouncedQuery
    ) {
      return;
    }
    lastFetchRef.current = { page, query: debouncedQuery };
    fetchMain(page, debouncedQuery);
  }, [open, currentPage, debouncedQuery, isSearchMode, fetchMain]);

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

  const handleSaveEdit = async () => {
    if (!editingId) return;
    try {
      await updateMemory(editingId, editingContent);
      setEditingId(null);
      setEditingContent("");
      fetchSession();
      fetchMain(currentPage, debouncedQuery);
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  const handleCancelEdit = () => {
    setEditingId(null);
    setEditingContent("");
  };

  const handleForget = async (id: string) => {
    setOpenMenuId(null);
    try {
      await deleteMemory(id);
      fetchSession();
      fetchMain(currentPage, debouncedQuery);
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    }
  };

  const handleAddSubmit = async () => {
    const trimmed = addDraft.trim();
    if (!trimmed) return;
    try {
      await addMemory(trimmed);
      setAddDraft("");
      setAddFormVisible(false);
      fetchSession();
      fetchMain(currentPage, debouncedQuery);
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
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
            {error && (
              <p className="text-destructive text-sm">{error}</p>
            )}
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
                    disabled={!addDraft.trim() || loading}
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
              searchResultCount={mainList.length}
              list={mainList}
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
              emptyMessage={
                loading ? "加载中…" : isSearchMode ? "无匹配记忆" : "暂无记忆"
              }
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
              totalCount={totalCount}
              pageSize={PAGE_SIZE}
            />
          </div>
        </ScrollArea>
      </SheetContent>
    </Sheet>
  );
}
