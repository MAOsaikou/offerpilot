import { createPinia, setActivePinia } from 'pinia'
import { afterEach, expect, it, vi } from 'vitest'
import { resumeAgentRun, streamAgentChat } from '@/shared/api/agent'
import { queryClient } from '@/shared/api/queryClient'
import { useAgentStream } from './useAgentStream'
import { useAgentRunStore } from '@/shared/stores/agentRun'

vi.mock('@/shared/api/agent', () => ({
  streamAgentChat: vi.fn(),
  resumeAgentRun: vi.fn(),
  cancelAgentRun: vi.fn(),
}))
afterEach(() => vi.restoreAllMocks())

it('board_update 到达后刷新 applications 查询，等待实际提交结果', async () => {
  setActivePinia(createPinia())
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue()
  vi.mocked(streamAgentChat).mockImplementation(async (_body, options) => {
    options?.onEvent?.({
      type: 'ui.card',
      id: 1,
      runId: 'run1',
      ts: '2026-10-09T00:00:00Z',
      cardId: 'board1',
      cardType: 'board_update',
      payload: {
        application: {
          id: 'app1',
          company: '公司',
          title: 'Java',
          status: 'INTERVIEW',
          nextActionAt: null,
        },
        change: 'UPDATED',
      },
    })
    return 'run1'
  })
  await useAgentStream().start({ content: '改成面试中' })
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['applications'] })
  expect(useAgentRunStore().getRun('run1')?.cards[0]?.cardType).toBe('board_update')
})

it('连接中断后带最后事件 id 续传，正文不丢不重，运行正常结束', async () => {
  setActivePinia(createPinia())
  const ts = '2026-10-16T00:00:00Z'
  vi.mocked(streamAgentChat).mockImplementation(async (_body, options) => {
    options?.onEvent?.({
      type: 'run.started',
      id: 1,
      runId: 'run1',
      ts,
      conversationId: 'c',
      messageId: 'm',
      model: 'x',
      mode: 'AUTO',
    })
    options?.onEvent?.({ type: 'text.delta', id: 2, runId: 'run1', ts, text: '前半段' })
    throw new TypeError('network error')
  })
  vi.mocked(resumeAgentRun).mockImplementation(async (_runId, _last, options) => {
    options?.onEvent?.({ type: 'text.delta', id: 3, runId: 'run1', ts, text: '后半段' })
    options?.onEvent?.({
      type: 'run.completed',
      id: 4,
      runId: 'run1',
      ts,
      messageId: 'm',
      status: 'COMPLETED',
      durationMs: 1,
    })
    return 'run1'
  })
  const stream = useAgentStream()
  await expect(stream.start({ content: '你好' })).resolves.toBe('run1')
  expect(resumeAgentRun).toHaveBeenCalledWith('run1', 2, expect.anything())
  const run = useAgentRunStore().getRun('run1')
  expect(run?.text).toBe('前半段后半段')
  expect(run?.status).toBe('COMPLETED')
  expect(stream.streaming.value).toBe(false)
})
