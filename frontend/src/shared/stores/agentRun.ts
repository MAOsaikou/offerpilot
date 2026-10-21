import { translate as uiText } from '@/shared/utils/language'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { assertNever } from '@/shared/utils/assertNever'
import type { CardType } from '@/shared/types/cards'
import type {
  AgentEvent,
  AgentMode,
  CitationSource,
  PlanStepView,
  StepStatus,
  ToolAccess,
} from '@/shared/types/agent-events'

/**
 * 单次 Agent 运行的客户端状态。
 *
 * 这里只放「运行中的会话状态」，服务端可缓存的数据（会话、消息、报告）
 * 归 TanStack Query —— 见 docs/07 §5.1。
 * 一次运行的全部事件都由 apply() 归约进来，按 runId 隔离，支持多标签并发。
 */

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'

/** 计划步骤：比事件里的 PlanStepView 多了执行结果与耗时 */
export interface PlanStepState extends PlanStepView {
  summary?: string
  durationMs?: number
}

export interface ToolCallView {
  id: string
  stepId?: string
  name: string
  serverName?: string
  source: string
  access: ToolAccess
  args: unknown
  status: 'RUNNING' | 'OK' | 'FAILED' | 'CANCELLED'
  result?: unknown
  resultPreview?: string
  durationMs?: number
  truncated?: boolean
}

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'

export interface ApprovalView {
  approvalId: string
  toolCallId: string
  name: string
  args: unknown
  reason: string
  expiresAt: string
  editableFields?: string[]
  status: ApprovalStatus
}

export interface CardView {
  cardId: string
  cardType: CardType
  payload: unknown
}

export interface UsageView {
  source?: 'llm_usage_logs' | 'legacy_message'
  model: string
  promptTokens: number
  completionTokens: number
  costEstimate: number | null
  durationMs?: number | null
  usageReported?: boolean
  fallbackUsed: boolean
}

export interface RunErrorView {
  code: number
  message: string
  recoverable: boolean
}

export interface RunState {
  runId: string
  conversationId: string | null
  messageId: string | null
  mode: AgentMode | null
  model: string | null
  goal: string | null
  status: RunStatus
  steps: PlanStepState[]
  toolCalls: ToolCallView[]
  approvals: ApprovalView[]
  citations: CitationSource[]
  memoryFacts: Array<{ id: string; fact: string }>
  cards: CardView[]
  /** 思考摘要，reasoning.delta 累积 */
  reasoning: string
  /** 正文，text.delta 累积 */
  text: string
  usage: UsageView | null
  error: RunErrorView | null
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  /** 最近处理过的事件 id，断线续传时带上去 */
  lastEventId: number
}

type EventOf<T extends AgentEvent['type']> = Extract<AgentEvent, { type: T }>

function emptyRun(runId: string): RunState {
  return {
    runId,
    conversationId: null,
    messageId: null,
    mode: null,
    model: null,
    goal: null,
    status: 'RUNNING',
    steps: [],
    toolCalls: [],
    approvals: [],
    citations: [],
    memoryFacts: [],
    cards: [],
    reasoning: '',
    text: '',
    usage: null,
    error: null,
    startedAt: null,
    finishedAt: null,
    durationMs: null,
    lastEventId: 0,
  }
}

export const useAgentRunStore = defineStore('agentRun', () => {
  const runs = ref(new Map<string, RunState>())
  const activeRunId = ref<string | null>(null)

  const activeRun = computed<RunState | null>(() =>
    activeRunId.value ? (runs.value.get(activeRunId.value) ?? null) : null,
  )

  /** 待用户处理的审批，UI 用它决定要不要弹审批卡 */
  const pendingApprovals = computed<ApprovalView[]>(() =>
    activeRun.value ? activeRun.value.approvals.filter((a) => a.status === 'PENDING') : [],
  )

  function getRun(runId: string): RunState | undefined {
    return runs.value.get(runId)
  }

  /** 取出运行状态，不存在就建一个；顺便把它设为当前活跃运行 */
  function ensure(runId: string): RunState {
    let run = runs.value.get(runId)
    if (!run) {
      run = emptyRun(runId)
      runs.value.set(runId, run)
    }
    activeRunId.value = runId
    return run
  }

  function begin(event: EventOf<'run.started'>): void {
    const run = ensure(event.runId)
    run.conversationId = event.conversationId
    run.messageId = event.messageId
    run.model = event.model
    run.mode = event.mode
    run.status = 'RUNNING'
    run.startedAt = event.ts
  }

  function setPlan(runId: string, goal: string, steps: PlanStepView[]): void {
    const run = ensure(runId)
    run.goal = goal
    run.steps = steps.map((step) => ({ ...step }))
  }

  function updateStep(
    runId: string,
    stepId: string,
    status: StepStatus,
    summary?: string,
    durationMs?: number,
  ): void {
    const step = ensure(runId).steps.find((s) => s.id === stepId)
    if (!step) return
    step.status = status
    if (summary !== undefined) step.summary = summary
    if (durationMs !== undefined) step.durationMs = durationMs
  }

  function appendReasoning(runId: string, text: string): void {
    ensure(runId).reasoning += text
  }

  function appendText(runId: string, text: string): void {
    ensure(runId).text += text
  }

  function addToolCall(event: EventOf<'tool.call'>): void {
    const call: ToolCallView = {
      id: event.toolCallId,
      name: event.name,
      source: event.source,
      access: event.access,
      args: event.args,
      status: 'RUNNING',
    }
    if (event.serverName) call.serverName = event.serverName
    if (event.stepId) call.stepId = event.stepId
    ensure(event.runId).toolCalls.push(call)
  }

  function resolveToolCall(event: EventOf<'tool.result'>): void {
    const call = ensure(event.runId).toolCalls.find((c) => c.id === event.toolCallId)
    if (!call) return
    call.status = event.ok ? 'OK' : 'FAILED'
    if (event.source) call.source = event.source
    if (event.serverName) call.serverName = event.serverName
    else if (event.source === 'LOCAL') delete call.serverName
    call.result = event.result
    call.resultPreview = event.resultPreview
    call.durationMs = event.durationMs
    call.truncated = event.truncated
  }

  function addCard(event: EventOf<'ui.card'>): void {
    ensure(event.runId).cards.push({
      cardId: event.cardId,
      cardType: event.cardType,
      payload: event.payload,
    })
  }

  function addApproval(event: EventOf<'approval.required'>): void {
    const approval: ApprovalView = {
      approvalId: event.approvalId,
      toolCallId: event.toolCallId,
      name: event.name,
      args: event.args,
      reason: event.reason,
      expiresAt: event.expiresAt,
      status: 'PENDING',
    }
    if (event.editableFields) approval.editableFields = event.editableFields
    ensure(event.runId).approvals.push(approval)
  }

  function resolveApproval(event: EventOf<'approval.resolved'>): void {
    const approval = ensure(event.runId).approvals.find((a) => a.approvalId === event.approvalId)
    if (!approval) return
    approval.status =
      event.decision === 'APPROVE'
        ? 'APPROVED'
        : event.decision === 'REJECT'
          ? 'REJECTED'
          : 'TIMEOUT'
  }

  function addCitations(runId: string, sources: CitationSource[]): void {
    ensure(runId).citations.push(...sources)
  }

  function setMemoryUsed(runId: string, facts: Array<{ id: string; fact: string }>): void {
    ensure(runId).memoryFacts = facts
  }

  function setUsage(runId: string, usage: UsageView): void {
    ensure(runId).usage = usage
  }

  function setError(runId: string, error: RunErrorView): void {
    const run = ensure(runId)
    run.error = error
    if (!error.recoverable) {
      run.status = 'FAILED'
      settleUnfinished(run, 'FAILED')
    }
  }

  function settleUnfinished(run: RunState, status: RunStatus): void {
    for (const step of run.steps) {
      if (step.status !== 'RUNNING' && step.status !== 'PENDING') continue
      step.status = status === 'FAILED' && step.status === 'RUNNING' ? 'FAILED' : 'SKIPPED'
      step.summary = status === 'CANCELLED' ? uiText("Run canceled") : uiText("Run ended before this step completed")
    }
    for (const call of run.toolCalls) {
      if (call.status !== 'RUNNING') continue
      call.status = status === 'CANCELLED' ? 'CANCELLED' : 'FAILED'
      call.resultPreview = status === 'CANCELLED' ? uiText("Run canceled") : uiText("Run ended without a tool result")
    }
  }

  function complete(
    runId: string,
    status: RunStatus,
    durationMs: number,
    messageId: string,
    finishedAt: string,
  ): void {
    const run = ensure(runId)
    run.status = status
    run.durationMs = durationMs
    run.messageId = messageId
    run.finishedAt = finishedAt
    settleUnfinished(run, status)
  }

  /** 事件归约入口：新增事件类型时 default 分支会编译期报错 */
  function apply(event: AgentEvent): void {
    if (event.id <= (runs.value.get(event.runId)?.lastEventId ?? 0)) return
    switch (event.type) {
      case 'run.started':
        begin(event)
        break
      case 'plan.created':
        setPlan(event.runId, event.goal, event.steps)
        break
      case 'step.updated':
        updateStep(event.runId, event.stepId, event.status, event.summary, event.durationMs)
        break
      case 'reasoning.delta':
        appendReasoning(event.runId, event.text)
        break
      case 'text.delta':
        appendText(event.runId, event.text)
        break
      case 'tool.call':
        addToolCall(event)
        break
      case 'tool.result':
        resolveToolCall(event)
        break
      case 'ui.card':
        addCard(event)
        break
      case 'approval.required':
        addApproval(event)
        break
      case 'approval.resolved':
        resolveApproval(event)
        break
      case 'citation':
        addCitations(event.runId, event.sources)
        break
      case 'memory.used':
        setMemoryUsed(event.runId, event.facts)
        break
      case 'usage':
        setUsage(event.runId, {
          source: 'llm_usage_logs',
          model: event.model,
          promptTokens: event.promptTokens,
          completionTokens: event.completionTokens,
          costEstimate: event.costEstimate,
          usageReported: event.usageReported,
          fallbackUsed: event.fallbackUsed,
          durationMs: event.durationMs,
        })
        break
      case 'error':
        setError(event.runId, {
          code: event.code,
          message: event.message,
          recoverable: event.recoverable,
        })
        break
      case 'run.completed':
        complete(event.runId, event.status, event.durationMs, event.messageId, event.ts)
        break
      default:
        assertNever(event)
    }

    const run = runs.value.get(event.runId)
    if (run) run.lastEventId = event.id
  }

  function reset(): void {
    runs.value = new Map()
    activeRunId.value = null
  }

  return {
    runs,
    activeRunId,
    activeRun,
    pendingApprovals,
    getRun,
    apply,
    reset,
  }
})
