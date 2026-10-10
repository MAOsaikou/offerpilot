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

  it('可恢复错误继续运行，失败和取消终止未完成步骤及调用', () => {
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
    store.apply(ev({ id: 3, type: 'error', code: 40001, message: '模型超时', recoverable: false }))

    const run = store.getRun(RUN)
    expect(run?.status).toBe('FAILED')
    expect(run?.error?.code).toBe(40001)
    store.reset()
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
    store.apply(
      ev({
        id: 2,
        type: 'plan.created',
        goal: '取消中的任务',
        steps: [{ id: 's1', title: '未完成步骤', agent: 'JOB', status: 'RUNNING', dependsOn: [] }],
      }),
    )
    store.apply(
      ev({
        id: 3,
        type: 'tool.call',
        toolCallId: 'call',
        stepId: 's1',
        name: 'jobs.search',
        args: {},
        source: 'local',
        access: 'READ',
      }),
    )
    store.apply(
      ev({ id: 4, type: 'run.completed', messageId: 'm', status: 'CANCELLED', durationMs: 0 }),
    )
    expect(store.getRun(RUN)?.steps[0]?.status).toBe('SKIPPED')
    expect(store.getRun(RUN)?.toolCalls[0]?.status).toBe('CANCELLED')
    expect(store.getRun(RUN)?.status).toBe('CANCELLED')
  })
})
