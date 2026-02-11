/**
 * 记忆 API：调用后端 GET/POST/PUT/DELETE /api/memory
 */

import { fetchClient } from "@/lib/api-client";

export type MemoryItem = {
  id: string;
  content: string;
  createdAt: Date;
};

export type ListMemoriesResponse = {
  items: { id: string; content: string; created_at: string }[];
  total_count: number;
};

function toMemoryItem(raw: {
  id: string;
  content: string;
  created_at: string;
}): MemoryItem {
  return {
    id: raw.id,
    content: raw.content,
    createdAt: raw.created_at ? new Date(raw.created_at) : new Date(),
  };
}

export async function listMemories(opts?: {
  q?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: MemoryItem[]; totalCount: number }> {
  const params: Record<string, string> = {};
  if (opts?.q != null && opts.q.trim() !== "")
    params.q = opts.q.trim();
  if (opts?.page != null) params.page = String(opts.page);
  if (opts?.pageSize != null) params["page-size"] = String(opts.pageSize);

  const res = await fetchClient.get<ListMemoriesResponse>(
    "/api/memory",
    params ? { params } : undefined
  );
  return {
    items: (res.items ?? []).map(toMemoryItem),
    totalCount: res.total_count ?? 0,
  };
}

export async function addMemory(content: string): Promise<{ id: string }> {
  const res = await fetchClient.post<{ id: string }>("/api/memory", {
    content: content.trim(),
  });
  return { id: res.id };
}

export async function updateMemory(
  id: string,
  content: string
): Promise<void> {
  await fetchClient.put(`/api/memory/${id}`, { content: content.trim() });
}

export async function deleteMemory(id: string): Promise<void> {
  await fetchClient.delete(`/api/memory/${id}`);
}
