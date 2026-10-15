import {
  apiUrl,
  authenticatedHeaders,
  assertResponseIdentity,
  fetchWithIdentity,
  toApiError,
} from './client'
import { parseSse } from './sse'
import type { ApplicationStatus } from '@/shared/types/board'
import type { AgentEvent, ChatRequest } from '@/shared/types/agent-events'

export interface AgentStreamOptions {
  signal?: AbortSignal
  onEvent?: (event: AgentEvent) => void
}

function decodeEvent(data: string): AgentEvent | null {
  try {
    return JSON.parse(data) as AgentEvent
  } catch {
    return null
  }
}

function isTerminal(event: AgentEvent): boolean {
  if (event.type === 'run.completed') return true
  return event.type === 'error' && !event.recoverable
}

/**
 * POST /agent/chat，消费 SSE 事件流，返回 runId（docs/05 §4.1）。
 * 用 fetch 而非 EventSource：需要 POST 与自定义请求头。
 */
export async function streamAgentChat(
  body: ChatRequest,
  options: AgentStreamOptions = {},
): Promise<string> {
  const response = await fetchWithIdentity(apiUrl('/agent/chat'), {
    method: 'POST',
    headers: await authenticatedHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    }),
    body: JSON.stringify(body),
    signal: options.signal ?? null,
  })

  return consume(response, options)
}

/**
 * 断线续传：GET /agent/runs/{runId}/events，回放 Last-Event-ID 之后的事件再接上实时事件（AG-07）。
 * 运行已结束且超过回放保留期、或服务重启后返回 404，调用方改为读取已保存的消息。
 */
export async function resumeAgentRun(
  runId: string,
  lastEventId: number,
  options: AgentStreamOptions = {},
): Promise<string> {
  const response = await fetchWithIdentity(
    apiUrl(`/agent/runs/${encodeURIComponent(runId)}/events`),
    {
      method: 'GET',
      headers: await authenticatedHeaders({
        Accept: 'text/event-stream',
        'Last-Event-ID': String(lastEventId),
      }),
      signal: options.signal ?? null,
    },
  )
  return consume(response, options)
}

async function consume(response: Response, options: AgentStreamOptions): Promise<string> {
  if (!response.ok || !response.body) throw await toApiError(response)

  let runId = ''
  let completed = false
  for await (const frame of parseSse(response.body)) {
    assertResponseIdentity(response)
    const event = decodeEvent(frame.data)
    if (!event) continue
    runId ||= event.runId
    options.onEvent?.(event)
    if (isTerminal(event)) {
      completed = true
      break
    }
  }
  if (!completed) throw new Error('连接在回复完成前中断，请检查后端后重试。')
  return runId
}

/** 中断运行：POST /agent/runs/{runId}/cancel */
export async function cancelAgentRun(runId: string): Promise<void> {
  const response = await fetchWithIdentity(
    apiUrl(`/agent/runs/${encodeURIComponent(runId)}/cancel`),
    {
      method: 'POST',
      headers: await authenticatedHeaders(),
    },
  )
  if (!response.ok) throw await toApiError(response)
}

export interface ApprovalDecision {
  decision: 'APPROVE' | 'REJECT'
  args?: { status?: ApplicationStatus; note?: string }
  reason?: string
}

export async function submitApproval(
  runId: string,
  approvalId: string,
  body: ApprovalDecision,
): Promise<void> {
  const response = await fetchWithIdentity(
    apiUrl(`/agent/runs/${encodeURIComponent(runId)}/approvals/${encodeURIComponent(approvalId)}`),
    {
      method: 'POST',
      headers: await authenticatedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    },
  )
  if (!response.ok) throw await toApiError(response)
  assertResponseIdentity(response)
}
