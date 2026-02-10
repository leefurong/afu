"use client";

import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export type MessageToolBarProps = {
  /** 是否靠右对齐（用户消息为 true，助手消息为 false） */
  alignRight?: boolean;
  /** 点击编辑：将消息正文填入输入框并设置目标 parent（在此消息后追加） */
  onEdit?: () => void;
  className?: string;
};

/**
 * 消息下方的功能栏，hover 时显示，便于后续扩展（分享、复制、引用等）。
 * 当前仅提供「编辑」：点击后消息内容进入底部输入框，并记录目标 parent id（发送时接在该条之后）。
 */
export function MessageToolBar({
  alignRight = false,
  onEdit,
  className,
}: MessageToolBarProps) {
  return (
    <div
      className={cn(
        "flex items-center gap-0.5 rounded-md border border-border/60 bg-card px-1 py-0.5 opacity-0 transition-opacity duration-150 group-hover:opacity-100",
        alignRight ? "self-end" : "self-start",
        className
      )}
      role="toolbar"
      aria-label="消息操作"
    >
      {onEdit != null && (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-7 w-7 text-muted-foreground hover:text-foreground"
          onClick={onEdit}
          aria-label="编辑"
        >
          <Pencil className="size-3.5" />
        </Button>
      )}
    </div>
  );
}
