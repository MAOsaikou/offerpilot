import type { AgentMode } from './agent-events'

export interface Conversation {
  id: string
  title: string
  mode: AgentMode
  pinned: boolean
  createdAt: string
  updatedAt: string
}
export type MessageStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'
export interface StoredMessage {
  id: string
  conversationId: string
  role: 'USER' | 'ASSISTANT'
  plainText: string
  runId: string
  model: string
  status: MessageStatus
  createdAt: string
}
export interface ConversationPage {
  items: Conversation[]
  page: number
  size: number
  total: number
}
export interface MessagePage {
  items: StoredMessage[]
  nextBefore?: string
  hasMore: boolean
}
