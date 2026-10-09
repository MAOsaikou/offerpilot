import { createPinia, setActivePinia } from 'pinia'
import { afterEach, expect, it, vi } from 'vitest'
import { streamAgentChat } from '@/shared/api/agent'
import { queryClient } from '@/shared/api/queryClient'
import { useAgentStream } from './useAgentStream'
import { useAgentRunStore } from '@/shared/stores/agentRun'

vi.mock('@/shared/api/agent', () => ({ streamAgentChat: vi.fn(), cancelAgentRun: vi.fn() }))
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
