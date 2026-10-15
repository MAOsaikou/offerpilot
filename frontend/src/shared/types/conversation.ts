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
/** docs/05 §3 消息 parts；text、card，以及用户消息的 image（fileId/name） */
export interface MessagePart {
  type: string
  text?: string
  cardId?: string
  cardType?: string
  payload?: unknown
  /** type=image：用户随消息发送的图片 */
  fileId?: string
  name?: string
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
