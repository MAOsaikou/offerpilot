import { createApp, nextTick, type App } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import PendingAgentWrites from './PendingAgentWrites.vue'
import { decidePendingAgentApproval, listPendingAgentApprovals } from '@/shared/api/agentApprovals'
vi.mock('@/shared/api/agentApprovals', async (load) => ({
  ...(await load<typeof import('@/shared/api/agentApprovals')>()),
  listPendingAgentApprovals: vi.fn(),
  decidePendingAgentApproval: vi.fn(),
}))

let app: App | undefined
afterEach(() => {
  app?.unmount()
  vi.useRealTimers()
})

it('lists non-board agent writes on the workbench and reports what an approval wrote', async () => {
  let pending = [
    {
      id: 'ap_patch',
      runId: 'run_1',
      action: 'applyPatches',
      args: {},
      expiresAt: new Date(Date.now() + 4 * 60_000).toISOString(),
      createdAt: new Date().toISOString(),
      company: null,
      title: null,
      currentStatus: null,
      summary: '采纳 2 条简历修改建议，生成新版本（原版本保留）',
    },
  ]
  vi.mocked(listPendingAgentApprovals).mockImplementation(async () => structuredClone(pending))
  vi.mocked(decidePendingAgentApproval).mockImplementation(async () => {
    pending = []
    return {
      decision: 'APPROVE',
      applied: true,
      summary: '已采纳简历修改，生成新版本 V3，原版本保留可查看。',
    }
  })
  const root = document.createElement('div')
  app = createApp(PendingAgentWrites).use(VueQueryPlugin, { queryClient: new QueryClient() })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('采纳 2 条简历修改建议'))
  expect(root.textContent).toContain('4 分钟内有效')
  ;[...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === '批准')!.click()
  await vi.waitFor(() => expect(root.textContent).toContain('生成新版本 V3'))
  expect(decidePendingAgentApproval).toHaveBeenCalledWith('ap_patch', 'APPROVE')
  expect(root.textContent).not.toContain('采纳 2 条简历修改建议')
})

it('ticks to automatic rejection without posting and supports detached edited approval', async () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  vi.mocked(listPendingAgentApprovals).mockResolvedValue([
    {
      id: 'edit',
      runId: 'r',
      action: 'startInterview',
      args: { role: '前端', kind: '项目与经历' },
      editableFields: ['role'],
      expiresAt: new Date(Date.now() + 2000).toISOString(),
      createdAt: '',
      company: null,
      title: null,
      currentStatus: null,
      summary: '开始面试',
    },
  ])
  vi.mocked(decidePendingAgentApproval).mockClear().mockRejectedValue(new Error('离线，请重试'))
  const root = document.createElement('div')
  app = createApp(PendingAgentWrites).use(VueQueryPlugin, {
    queryClient: new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }),
  })
  app.mount(root)
  await vi.advanceTimersByTimeAsync(1)
  await nextTick()
  const input = root.querySelector('textarea')!
  input.value = '后端'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector('button')!.click()
  await vi.advanceTimersByTimeAsync(1)
  expect(decidePendingAgentApproval).toHaveBeenCalledWith('edit', 'APPROVE', { role: '后端' })
  expect(root.textContent).toContain('离线，请重试')
  await vi.advanceTimersByTimeAsync(2000)
  await nextTick()
  expect(root.textContent).toContain('已超时自动拒绝')
  expect([...root.querySelectorAll('button')].every((button) => button.disabled)).toBe(true)
  root.querySelector('button')!.click()
  expect(decidePendingAgentApproval).toHaveBeenCalledTimes(1)
})

it('shows the detached rejection alternative returned by the server', async () => {
  let pending = [
    {
      id: 'reject',
      runId: 'r',
      action: 'startInterview',
      args: {},
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      createdAt: '',
      company: null,
      title: null,
      currentStatus: null,
      summary: '开始面试',
    },
  ]
  vi.mocked(listPendingAgentApprovals).mockImplementation(async () => pending)
  vi.mocked(decidePendingAgentApproval).mockImplementation(async () => {
    pending = []
    return {
      decision: 'REJECT',
      applied: false,
      summary: '本次未执行。可以先练习答案，再决定是否保存。',
    }
  })
  const root = document.createElement('div')
  app = createApp(PendingAgentWrites).use(VueQueryPlugin, { queryClient: new QueryClient() })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('开始面试'))
  ;[...root.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '拒绝')!
    .click()
  await vi.waitFor(() => expect(root.textContent).toContain('可以先练习答案'))
  expect(decidePendingAgentApproval).toHaveBeenLastCalledWith('reject', 'REJECT')
})
