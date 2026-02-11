"use client";

import { Button } from "@/components/ui/button";
import { Pencil, Trash2, MoreVertical } from "lucide-react";

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

export type MemoryItemCardProps = {
  item: MemoryItem;
  isEditing: boolean;
  editingContent: string;
  onEditingContentChange: (v: string) => void;
  onSaveEdit: () => void;
  onCancelEdit: () => void;
  onEdit: () => void;
  onForget: () => void;
  menuOpen: boolean;
  onMenuToggle: () => void;
  menuRef: React.RefObject<HTMLDivElement | null>;
};

export function MemoryItemCard({
  item,
  isEditing,
  editingContent,
  onEditingContentChange,
  onSaveEdit,
  onCancelEdit,
  onEdit,
  onForget,
  menuOpen,
  onMenuToggle,
  menuRef,
}: MemoryItemCardProps) {
  return (
    <div className="rounded-lg border bg-card p-3 text-card-foreground shadow-sm">
      {isEditing ? (
        <>
          <textarea
            value={editingContent}
            onChange={(e) => onEditingContentChange(e.target.value)}
            rows={3}
            className="border-input mb-2 w-full resize-y rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" size="sm" onClick={onCancelEdit}>
              取消
            </Button>
            <Button type="button" size="sm" onClick={onSaveEdit}>
              保存
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-sm leading-relaxed line-clamp-4">{item.content}</p>
          <div className="mt-2 flex items-center justify-between gap-2">
            <span className="text-muted-foreground text-xs">
              {formatRelativeTime(item.createdAt)}
            </span>
            <div className="relative" ref={menuOpen ? menuRef : undefined}>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-7 w-7"
                onClick={(e) => {
                  e.stopPropagation();
                  onMenuToggle();
                }}
                aria-label="更多操作"
              >
                <MoreVertical className="size-4" />
              </Button>
              {menuOpen && (
                <div
                  className="bg-popover text-popover-foreground absolute right-0 top-full z-10 mt-1 min-w-[100px] rounded-md border py-1 shadow-md"
                  role="menu"
                >
                  <button
                    type="button"
                    className="hover:bg-accent flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
                    onClick={onEdit}
                  >
                    <Pencil className="size-3.5" />
                    编辑
                  </button>
                  <button
                    type="button"
                    className="hover:bg-accent text-destructive flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
                    onClick={onForget}
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
  );
}
