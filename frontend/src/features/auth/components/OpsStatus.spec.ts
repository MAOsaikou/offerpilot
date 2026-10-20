import { createApp } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import OpsStatus from './OpsStatus.vue'
import { getCleanupReceipts, getOpsStatus } from '@/shared/api/ops'

vi.mock('@/shared/api/ops', () => ({ getOpsStatus: vi.fn(), getCleanupReceipts: vi.fn() }))
const cleanups: (() => void)[] = []
afterEach(() => {
  cleanups.splice(0).forEach((fn) => fn())
  vi.resetAllMocks()
})
const flush = async () => {
  for (let i = 0; i < 8; i++) await Promise.resolve()
}
function mount() {
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(OpsStatus)
  app.mount(root)
  cleanups.push(() => {
    app.unmount()
    root.remove()
  })
  return root
}
it('加载状态、用量和脱敏回执，失败可重试', async () => {
  vi.mocked(getOpsStatus)
    .mockRejectedValueOnce(new Error('暂时离线'))
    .mockResolvedValue({
      state: 'DEGRADED',
      uptimeSeconds: 120,
      http: { requests: 25, errors: 5, errorRate: 0.2 },
      usage: { calls: 2, failed: 1, promptTokens: 100, completionTokens: 20, unreported: 0 },
      alerts: ['HTTP_ERROR_RATE'],
      moderationHits: { SECRET: 2 },
      externalPushEnabled: false,
      checkedAt: '2026-10-20',
    })
  vi.mocked(getCleanupReceipts).mockResolvedValue([
    {
      id: 'cleanup_fixture',
      kind: 'OBJECT',
      attempts: 2,
      next_attempt_at: 'tomorrow',
      created_at: 'today',
    },
  ])
  const root = mount()
  expect(getOpsStatus).not.toHaveBeenCalled()
  root.querySelector('button')!.click()
  await flush()
  expect(root.textContent).toContain('暂时离线')
  root.querySelector('button')!.click()
  root.querySelector('button')!.click()
  expect(getOpsStatus).toHaveBeenCalledTimes(2)
  await flush()
  expect(root.textContent).toContain('20.0%')
  expect(root.textContent).toContain('120 tokens')
  expect(root.textContent).toContain('HTTP_ERROR_RATE')
  expect(root.textContent).toContain('SECRET 2')
  expect(root.textContent).toContain('cleanup_fixture')
  expect(root.textContent).toContain('外部推送关闭')
})
it('空回执与用量不可用可见，卸载丢弃迟到响应', async () => {
  vi.mocked(getOpsStatus).mockResolvedValue({
    state: 'UP',
    uptimeSeconds: 1,
    http: { requests: 0, errors: 0, errorRate: 0 },
    usage: null,
    alerts: [],
    moderationHits: {},
    externalPushEnabled: false,
    checkedAt: 'now',
  })
  vi.mocked(getCleanupReceipts).mockResolvedValue([])
  const root = mount()
  root.querySelector('button')!.click()
  await flush()
  expect(root.textContent).toContain('没有待处理清理回执')
  expect(root.textContent).toContain('模型用量暂不可用')
  let finish!: (value: Awaited<ReturnType<typeof getOpsStatus>>) => void
  vi.mocked(getOpsStatus).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  root.querySelector('button')!.click()
  cleanups.splice(0).forEach((fn) => fn())
  finish({
    state: 'LATE',
    uptimeSeconds: 1,
    http: { requests: 0, errors: 0, errorRate: 0 },
    usage: null,
    alerts: [],
    moderationHits: {},
    externalPushEnabled: false,
    checkedAt: 'now',
  })
  await flush()
  expect(root.textContent).toBe('')
})
