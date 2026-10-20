import { effectScope } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, expect, it, vi } from 'vitest'
import { useRunReplay } from './useRunReplay'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent } from '@/shared/types/agent-events'
const api = vi.hoisted(() => ({ getAgentWorkbench: vi.fn() }))
vi.mock('@/shared/api/agent', () => api)
const events: AgentEvent[] = [
  {
    id: 1,
    runId: 'r',
    ts: '2026-10-20T00:00:00Z',
    type: 'run.started',
    conversationId: 'c',
    messageId: 'm',
    mode: 'COACH',
    model: 'model',
  },
  { id: 2, runId: 'r', ts: '2026-10-20T00:00:01Z', type: 'text.delta', text: '真实回复' },
  {
    id: 3,
    runId: 'r',
    ts: '2026-10-20T00:00:02Z',
    type: 'ui.card',
    cardId: 'card',
    cardType: 'skill_radar',
    payload: { axes: [] },
  },
  {
    id: 4,
    runId: 'r',
    ts: '2026-10-20T00:00:03Z',
    type: 'usage',
    model: 'model',
    promptTokens: 10,
    completionTokens: 2,
    costEstimate: null,
    durationMs: 200,
    fallbackUsed: false,
  },
  {
    id: 5,
    runId: 'r',
    ts: '2026-10-20T00:00:04Z',
    type: 'run.completed',
    messageId: 'm',
    status: 'COMPLETED',
    durationMs: 4000,
  },
]
// Include every process view in the equivalence check, including a resolved approval.
events.splice(
  4,
  1,
  {
    id: 5,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'plan.created',
    goal: '目标',
    steps: [{ id: 's', title: '步骤', agent: 'COACH', status: 'PENDING', dependsOn: [] }],
  },
  { id: 6, runId: 'r', ts: events[0]!.ts, type: 'step.updated', stepId: 's', status: 'RUNNING' },
  {
    id: 7,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'tool.call',
    toolCallId: 't',
    name: 'read',
    args: {},
    source: 'LOCAL',
    access: 'READ',
  },
  {
    id: 8,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'tool.result',
    toolCallId: 't',
    name: 'read',
    ok: true,
    resultPreview: '真实结果',
    durationMs: 20,
    truncated: false,
  },
  {
    id: 9,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'citation',
    sources: [
      { docId: 'd', title: '来源', snippet: '摘录', score: null, collection: 'notes', index: 1 },
    ],
  },
  {
    id: 10,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'memory.used',
    facts: [{ id: 'f', fact: '本人自述' }],
  },
  {
    id: 11,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'approval.required',
    approvalId: 'a',
    toolCallId: 't',
    name: 'write',
    args: {},
    reason: '确认',
    expiresAt: events[0]!.ts,
  },
  {
    id: 12,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'approval.resolved',
    approvalId: 'a',
    decision: 'REJECT',
  },
  { id: 13, runId: 'r', ts: events[0]!.ts, type: 'reasoning.delta', text: '摘要' },
  {
    id: 14,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'step.updated',
    stepId: 's',
    status: 'DONE',
    durationMs: 20,
  },
  {
    id: 15,
    runId: 'r',
    ts: events[0]!.ts,
    type: 'run.completed',
    messageId: 'm',
    status: 'COMPLETED',
    durationMs: 4000,
  },
)
let scope = effectScope()
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
  vi.resetAllMocks()
})
it('逐事件/定位/播放复用实时 reducer，最终结果完全一致，暂停不会消费事件', async () => {
  vi.useFakeTimers()
  scope = effectScope()
  const replay = scope.run(useRunReplay)!
  api.getAgentWorkbench.mockResolvedValue(events)
  const live = useAgentRunStore(createPinia())
  events.forEach(live.apply)
  await replay.open('r')
  expect(replay.position.value).toBe(0)
  replay.step()
  expect(replay.run.value?.text).toBe('')
  replay.play()
  expect(replay.run.value?.text).toBe('真实回复')
  replay.pause()
  await vi.advanceTimersByTimeAsync(2000)
  expect(replay.position.value).toBe(2)
  replay.seek(1)
  expect(replay.run.value?.cards).toEqual([])
  replay.play()
  await vi.runAllTimersAsync()
  expect(replay.run.value).toEqual(live.activeRun)
  replay.seek(0)
  expect(replay.run.value).toBeNull()
  expect(live.activeRun?.text).toBe('真实回复')
})
it('失败可重读，切换后迟到响应不会覆盖当前运行', async () => {
  scope = effectScope()
  const replay = scope.run(useRunReplay)!
  let resolve!: (events: AgentEvent[]) => void
  api.getAgentWorkbench
    .mockImplementationOnce(() => new Promise((r) => (resolve = r)))
    .mockRejectedValueOnce(new Error('离线'))
  const pending = replay.open('old')
  await replay.open('new')
  resolve(events)
  await pending
  expect(replay.runId.value).toBe('new')
  expect(replay.error.value).toBe('离线')
  expect(replay.events.value).toEqual([])
  api.getAgentWorkbench.mockResolvedValue(events)
  await replay.open('r')
  expect(replay.error.value).toBe('')
})
