/**
 * 会话列表与历史：调用后端 GET /api/conversations、GET /api/conversations/:id/messages
 */

import { fetchClient } from "@/lib/api-client";

export type ConversationItem = {
  id: string;
  title: string;
  updated_at: string;
};

export type ConversationMessage = {
  id: string;
  role: string;
  content: string;
};

export async function listConversations(): Promise<ConversationItem[]> {
  return fetchClient.get<ConversationItem[]>("/api/conversations");
}

export async function getConversationMessages(
  conversationId: string
): Promise<ConversationMessage[]> {
  return fetchClient.get<ConversationMessage[]>(
    `/api/conversations/${conversationId}/messages`
  );
}
