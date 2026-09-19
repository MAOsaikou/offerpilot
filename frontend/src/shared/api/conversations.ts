import { apiRequest } from './client'
import type { Conversation, ConversationPage, MessagePage } from '@/shared/types/conversation'
import type { AgentMode } from '@/shared/types/agent-events'

const path = (id: string) => `/conversations/${encodeURIComponent(id)}`
export const conversationsApi = {
  list: (page = 1) => apiRequest<ConversationPage>(`/conversations?page=${page}&size=20`),
  get: (id: string) => apiRequest<Conversation>(path(id)),
  create: (mode: AgentMode) =>
    apiRequest<Conversation>('/conversations', { method: 'POST', body: JSON.stringify({ mode }) }),
  update: (id: string, body: { title?: string; pinned?: boolean }) =>
    apiRequest<Conversation>(path(id), { method: 'PATCH', body: JSON.stringify(body) }),
  delete: (id: string) => apiRequest<void>(path(id), { method: 'DELETE' }),
  messages: (id: string, before?: string) =>
    apiRequest<MessagePage>(
      `${path(id)}/messages?size=30${before ? `&before=${encodeURIComponent(before)}` : ''}`,
    ),
}
