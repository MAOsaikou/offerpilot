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
/** docs/05 §3 消息 parts；目前后端只写 text，card 等类型按契约兼容读取 */
export interface MessagePart {
  type: string
  text?: string
  cardId?: string
  cardType?: string
  payload?: unknown
}
export interface StoredMessage {
  id: string
  conversationId: string
  role: 'USER' | 'ASSISTANT'
  parts?: MessagePart[]
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
