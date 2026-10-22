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
  model?: string
  usage?: import('@/shared/stores/agentRun').UsageView | null
  citations?: import('@/shared/types/agent-events').CitationSource[]
  durationMs?: number | null
  runId?: string
  /** 用户随消息发送的图片 */
  images?: Array<{ fileId: string; name: string }>
}
