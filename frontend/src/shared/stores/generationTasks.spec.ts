import { createPinia, setActivePinia, disposePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useGenerationTasks } from './generationTasks'
import {
  recentGenerationTasks,
  startGenerationTask,
  generationResult,
  cancelBackgroundTask,
  type BackgroundTask,
} from '@/shared/api/generationTasks'
import { saveGuest } from '@/shared/utils/authSession'
vi.mock('@/shared/api/generationTasks', async (original) => ({
  ...(await original<typeof import('@/shared/api/generationTasks')>()),
  recentGenerationTasks: vi.fn(),
  startGenerationTask: vi.fn(),
  generationResult: vi.fn(),
  cancelBackgroundTask: vi.fn(),
}))
const task = (status: BackgroundTask['status'] = 'RUNNING'): BackgroundTask => ({
  id: 'gen_fixture',
  kind: 'JOB_IMPORT',
  entityId: 'current',
  status,
  title: '岗位导入解析',
  href: '/jobs?taskId=gen_fixture',
  stage: status === 'SUCCEEDED' ? '结果已保存' : '正在解析',
  resultId: null,
  error: null,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
  startedAt: null,
  finishedAt: null,
})
let pinia: ReturnType<typeof createPinia>
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.mocked(recentGenerationTasks).mockResolvedValue([])
})
afterEach(() => {
  disposePinia(pinia)
  vi.useRealTimers()
})
it('rehydrates, backs off polling, notifies on completion and stops when no task is active', async () => {
  vi.mocked(recentGenerationTasks)
    .mockResolvedValueOnce([task()])
    .mockResolvedValueOnce([task('SUCCEEDED')])
  const store = useGenerationTasks()
  await store.refresh()
  expect(store.active).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(2600)
  expect(store.active).toHaveLength(0)
  expect(store.notice).toContain('已完成')
  await vi.advanceTimersByTimeAsync(120000)
  expect(recentGenerationTasks).toHaveBeenCalledTimes(2)
})
it('keeps accepted work after a page observer aborts and uses explicit cancel', async () => {
  const store = useGenerationTasks()
  vi.mocked(startGenerationTask).mockResolvedValue(task())
  vi.mocked(recentGenerationTasks).mockResolvedValue([task('CANCELLED')])
  const page = new AbortController()
  const promise = store.run('JOB_IMPORT', 'current', {}, 'request-fixture-0001', page.signal)
  const rejected = expect(promise).rejects.toThrow('观察已结束')
  await Promise.resolve()
  page.abort()
  await rejected
  expect(store.active).toHaveLength(1)
  expect(cancelBackgroundTask).not.toHaveBeenCalled()
  await store.cancel('gen_fixture')
  expect(cancelBackgroundTask).toHaveBeenCalledWith('gen_fixture')
  expect(store.active).toHaveLength(0)
})
it('returns persisted result and rejects old-owner pending replies after identity change', async () => {
  const store = useGenerationTasks()
  vi.mocked(startGenerationTask).mockResolvedValue(task('SUCCEEDED'))
  vi.mocked(generationResult).mockResolvedValue({ draft: 'saved' })
  expect(await store.run('JOB_IMPORT', 'current', {}, 'request-fixture-0002')).toEqual({
    draft: 'saved',
  })
  let finish!: (rows: BackgroundTask[]) => void
  vi.mocked(recentGenerationTasks).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const pending = store.refresh()
  saveGuest('other-fixture-owner')
  finish([task()])
  await pending
  expect(store.tasks).toEqual([])
  const calls = vi.mocked(recentGenerationTasks).mock.calls.length
  await vi.advanceTimersByTimeAsync(60000)
  expect(recentGenerationTasks).toHaveBeenCalledTimes(calls)
})
it('restores entity history and retries temporary poll failure', async () => {
  const store = useGenerationTasks()
  vi.mocked(recentGenerationTasks)
    .mockResolvedValueOnce([task()])
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce([task('SUCCEEDED')])
  await store.restore('JOB_IMPORT', 'current')
  await vi.advanceTimersByTimeAsync(2000)
  expect(store.error).toBe('offline')
  expect(store.active).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(4000)
  expect(store.active).toHaveLength(0)
  expect(store.error).toBe('')
  vi.mocked(recentGenerationTasks).mockResolvedValueOnce([task()])
  await store.restore('JOB_IMPORT', 'current')
  expect(store.active).toHaveLength(0)
})
