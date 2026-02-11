"use client";

import { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronRight, Search } from "lucide-react";
import type { MemoryItem } from "./memory-item-card";

export type MemorySectionPagination = {
  page: number;
  totalPages: number;
  onPrev: () => void;
  onNext: () => void;
};

export type MemorySectionSearch = {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
};

export type MemorySectionProps = {
  title: string;
  searchResultCount?: number;
  list: MemoryItem[];
  pagination?: MemorySectionPagination | null;
  expandByDefault?: boolean;
  renderItem: (item: MemoryItem) => React.ReactNode;
  emptyMessage?: string;
  /** 展开时在列表上方渲染（如其他附加内容） */
  extraContent?: React.ReactNode;
  /** 首次展开时调用（用于如重置分页） */
  onExpand?: () => void;
  search?: MemorySectionSearch;
  totalCount?: number;
  pageSize?: number;
};

export function MemorySection({
  title,
  searchResultCount,
  list,
  pagination = null,
  expandByDefault = false,
  renderItem,
  emptyMessage = "暂无记忆",
  extraContent,
  onExpand,
  search,
  totalCount = 0,
  pageSize = 1,
}: MemorySectionProps) {
  const [expanded, setExpanded] = useState(expandByDefault);
  const prevExpandedRef = useRef(false);
  useEffect(() => {
    if (expanded && !prevExpandedRef.current) onExpand?.();
    prevExpandedRef.current = expanded;
  }, [expanded, onExpand]);
  const showPagination = pagination && pagination.totalPages > 1;
  const showSearch =
    search &&
    totalCount > pageSize &&
    pageSize > 0;

  return (
    <section>
      <button
        type="button"
        className="hover:bg-muted/50 flex w-full items-center gap-2 rounded-md py-2 text-left text-sm font-medium transition-colors"
        onClick={() => setExpanded((e) => !e)}
      >
        {expanded ? (
          <ChevronDown className="size-4 shrink-0" />
        ) : (
          <ChevronRight className="size-4 shrink-0" />
        )}
        {title}
        {searchResultCount != null && (
          <span className="text-muted-foreground font-normal">
            （{searchResultCount} 条）
          </span>
        )}
      </button>

      {expanded && (
        <div className="mt-2 flex flex-col gap-3">
          {showSearch && (
            <div className="flex items-center gap-2">
              <Search className="text-muted-foreground size-4 shrink-0" />
              <input
                type="text"
                value={search.value}
                onChange={(e) => search.onChange(e.target.value)}
                placeholder={search.placeholder ?? "自然语言搜索记忆…"}
                className="border-input placeholder:text-muted-foreground w-full rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
          )}
          {extraContent}
          {list.length === 0 ? (
            <p className="text-muted-foreground py-4 text-center text-sm">
              {emptyMessage}
            </p>
          ) : (
            <>
              <div className="flex flex-col gap-3">
                {list.map((item) => renderItem(item))}
              </div>
              {showPagination && (
                <div className="flex items-center justify-center gap-2 py-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={pagination.page <= 1}
                    onClick={pagination.onPrev}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-sm">
                    {pagination.page} / {pagination.totalPages}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={pagination.page >= pagination.totalPages}
                    onClick={pagination.onNext}
                  >
                    下一页
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
