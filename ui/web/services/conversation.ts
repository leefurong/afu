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
  tool_calls?: { id: string; type: string; function: { name: string; arguments: string } }[];
  tool_call_id?: string;
  can_left?: boolean;
  can_right?: boolean;
  sibling_index?: number;
  sibling_total?: number;
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

export async function switchConversationBranchLeft(
  conversationId: string,
  messageId: string
): Promise<ConversationMessage[]> {
  return fetchClient.post<ConversationMessage[]>(
    `/api/conversations/${conversationId}/messages/${messageId}/switch-left`
  );
}

export async function switchConversationBranchRight(
  conversationId: string,
  messageId: string
): Promise<ConversationMessage[]> {
  return fetchClient.post<ConversationMessage[]>(
    `/api/conversations/${conversationId}/messages/${messageId}/switch-right`
  );
}
