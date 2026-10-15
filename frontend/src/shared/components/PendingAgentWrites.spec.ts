import { createApp, type App } from 'vue'
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
afterEach(() => app?.unmount())

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
