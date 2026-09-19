import type { CardView } from '@/shared/stores/agentRun'

export interface ChatMessageView {
  id: string
  role: 'user' | 'assistant'
  text: string
  /** 助手的思考摘要，来自 reasoning.delta */
  reasoning?: string
  /** 本轮运行产出的生成式 UI 卡片 */
  cards?: CardView[]
  streaming?: boolean
  status?: import('@/shared/types/conversation').MessageStatus
  runId?: string
}
