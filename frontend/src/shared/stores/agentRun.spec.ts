import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAgentRunStore } from './agentRun'
import type { AgentEvent } from '@/shared/types/agent-events'

const RUN = 'run_test'
const TS = '2026-09-11T20:00:00+08:00'

/** Omit 直接用在联合类型上会塌缩成公共字段，这里让它分配到每个成员上 */
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never
type EventDraft = DistributiveOmit<AgentEvent, 'runId' | 'ts'>

function ev<T extends EventDraft>(draft: T): AgentEvent {
  return { ...draft, runId: RUN, ts: TS } as AgentEvent
}

describe('useAgentRunStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('按事件顺序归约出运行状态', () => {
    const store = useAgentRunStore()

    store.apply(
      ev({
        id: 1,
        type: 'run.started',
        conversationId: 'conv_1',
        messageId: 'msg_1',
        model: 'qwen3-mock',
        mode: 'AUTO',
      }),
    )
    store.apply(
      ev({
        id: 2,
        type: 'plan.created',
        goal: '找工作',
        steps: [{ id: 's1', title: '搜索岗位', agent: 'JOB', status: 'PENDING', dependsOn: [] }],
      }),
    )
    store.apply(ev({ id: 3, type: 'step.updated', stepId: 's1', status: 'RUNNING' }))
    store.apply(ev({ id: 4, type: 'text.delta', text: '你好' }))
    store.apply(ev({ id: 5, type: 'text.delta', text: '，世界' }))
    store.apply(
      ev({
        id: 6,
        type: 'run.completed',
        messageId: 'msg_2',
        status: 'COMPLETED',
        durationMs: 1234,
      }),
    )

    const run = store.getRun(RUN)
    expect(run?.status).toBe('COMPLETED')
    expect(run?.text).toBe('你好，世界')
    expect(run?.goal).toBe('找工作')
    expect(run?.steps[0]?.status).toBe('SKIPPED')
    expect(run?.durationMs).toBe(1234)
    expect(run?.lastEventId).toBe(6)
    expect(store.activeRunId).toBe(RUN)
  })

  it('没有对应调用或审批的关联事件安全忽略', () => {
    const store = useAgentRunStore()
    store.apply(
      ev({
        id: 1,
        type: 'tool.result',
        toolCallId: 'missing',
        name: 'x',
        ok: true,
        resultPreview: '',
        durationMs: 1,
        truncated: false,
      }),
    )
    store.apply(
      ev({ id: 2, type: 'approval.resolved', approvalId: 'missing', decision: 'APPROVE' }),
    )
    expect(store.getRun(RUN)?.toolCalls).toHaveLength(0)
    expect(store.getRun(RUN)?.approvals).toHaveLength(0)
  })

  it('不可恢复的 error 会把运行标记为失败', () => {
    const store = useAgentRunStore()

    store.apply(
      ev({
        id: 1,
        type: 'run.started',
        conversationId: 'c',
        messageId: 'm',
        model: 'x',
        mode: 'AUTO',
      }),
    )
    store.apply(ev({ id: 2, type: 'error', code: 40001, message: '模型超时', recoverable: false }))

    const run = store.getRun(RUN)
    expect(run?.status).toBe('FAILED')
    expect(run?.error?.code).toBe(40001)
  })

  it('可恢复的 error 不会中断运行', () => {
    const store = useAgentRunStore()

    store.apply(
      ev({
        id: 1,
        type: 'run.started',
        conversationId: 'c',
        messageId: 'm',
        model: 'x',
        mode: 'AUTO',
      }),
    )
    store.apply(ev({ id: 2, type: 'error', code: 40002, message: '限流', recoverable: true }))

    expect(store.getRun(RUN)?.status).toBe('RUNNING')
  })
})
