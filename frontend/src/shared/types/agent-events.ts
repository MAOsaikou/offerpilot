import type { ModelTier } from './model-tier'
/**
 * SSE 事件契约，与 docs/05-接口文档.md §17 一一对应。
 * 前端消费时对 type 做 switch，default 分支用 assertNever 保证新增事件编译期可见。
 */
import type { CardType } from './cards'

export type AgentMode = 'AUTO' | 'CAREER_DISCOVERY' | 'RESUME' | 'JOB' | 'INTERVIEWER' | 'COACH'
export type ExpertType = Exclude<AgentMode, 'AUTO'>

export type StepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'

export type ToolSource = 'LOCAL' | 'MCP' | 'local' | `mcp:${string}`

export type ToolAccess = 'READ' | 'WRITE' | 'EXTERNAL_SIDE_EFFECT'

export type RunResultStatus = 'COMPLETED' | 'CANCELLED' | 'FAILED'

export interface PlanStepView {
  id: string
  title: string
  agent: ExpertType
  status: StepStatus
  dependsOn: string[]
}

export interface CitationSource {
  docId: string
  title: string
  snippet: string
  score: number | null
  url?: string
  collection: string
  index: number
}

export interface EventBase {
  id: number
  runId: string
  stepId?: string
  ts: string
}

export type AgentEvent =
  | (EventBase & {
      type: 'run.started'
      conversationId: string
      messageId: string
      model: string
      mode: AgentMode
    })
  | (EventBase & { type: 'plan.created'; goal: string; steps: PlanStepView[] })
  | (EventBase & {
      type: 'step.updated'
      stepId: string
      status: StepStatus
      summary?: string
      durationMs?: number
    })
  | (EventBase & { type: 'reasoning.delta'; text: string })
  | (EventBase & { type: 'text.delta'; text: string })
  | (EventBase & {
      type: 'tool.call'
      toolCallId: string
      name: string
      args: unknown
      serverName?: string
      source: ToolSource
      access: ToolAccess
    })
  | (EventBase & {
      type: 'tool.result'
      toolCallId: string
      name: string
      ok: boolean
      source?: ToolSource
      serverName?: string
      result?: unknown
      resultPreview: string
      durationMs: number
      truncated: boolean
    })
  | (EventBase & { type: 'ui.card'; cardId: string; cardType: CardType; payload: unknown })
  | (EventBase & {
      type: 'approval.required'
      approvalId: string
      toolCallId: string
      name: string
      args: unknown
      reason: string
      expiresAt: string
      editableFields?: string[]
    })
  | (EventBase & {
      type: 'approval.resolved'
      approvalId: string
      decision: 'APPROVE' | 'REJECT' | 'TIMEOUT'
    })
  | (EventBase & { type: 'citation'; sources: CitationSource[] })
  | (EventBase & { type: 'memory.used'; facts: Array<{ id: string; fact: string }> })
  | (EventBase & {
      type: 'usage'
      model: string
      promptTokens: number
      completionTokens: number
      costEstimate: number | null
      durationMs?: number | null
      usageReported?: boolean
      fallbackUsed: boolean
    })
  | (EventBase & { type: 'error'; code: number; message: string; recoverable: boolean })
  | (EventBase & {
      type: 'run.completed'
      messageId: string
      status: RunResultStatus
      durationMs: number
    })

export type AgentEventType = AgentEvent['type']

/** 请求体：POST /agent/chat（docs/05 §4.1） */
export interface ChatRequest {
  conversationId?: string
  content: string
  /** AG-02：目前只支持图片，先经 POST /agent/attachments 上传 */
  attachments?: Array<{ fileId: string; kind: 'image' }>
  mode?: AgentMode
  options?: {
    modelTier?: ModelTier
    enableReasoning?: boolean
    context?: Record<string, unknown>
  }
}
