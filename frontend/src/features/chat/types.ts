export interface ChatMessageView {
  id: string
  role: 'user' | 'assistant'
  text: string
  /** 助手的思考摘要，来自 reasoning.delta */
  reasoning?: string
  streaming?: boolean
}
