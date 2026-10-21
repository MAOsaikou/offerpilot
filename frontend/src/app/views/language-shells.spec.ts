import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import DashboardView from '@/features/dashboard/views/DashboardView.vue'
import SettingsView from '@/features/settings/views/SettingsView.vue'
import { setLanguage } from '@/shared/utils/language'
import { useWorkspace } from '@/shared/stores/workspace'

vi.mock('@/shared/api/resumes', () => ({
  listResumes: vi.fn(async () => ({ items: [], total: 0 })),
}))
vi.mock('@/shared/api/applications', () => ({ listApplications: vi.fn(async () => []) }))
vi.mock('@/shared/components/PendingAgentWrites.vue', () => ({ default: { render: () => null } }))
vi.mock('@/shared/components/GitHubEvidencePanel.vue', () => ({ default: { render: () => null } }))
vi.mock('@/features/settings/components/AccountPreferences.vue', () => ({
  default: { render: () => null },
}))
vi.mock('@/features/settings/components/ResumeModelSettings.vue', () => ({
  default: { template: '<div data-model-settings />' },
}))
vi.mock('@/features/settings/components/PrivacySettings.vue', () => ({
  default: { render: () => null },
}))
vi.mock('@/features/settings/components/MemorySettings.vue', () => ({
  default: { render: () => null },
}))
vi.mock('@/features/settings/components/DeleteMyData.vue', () => ({
  default: { render: () => null },
}))

let app: App | undefined, root: HTMLDivElement, cache: QueryClient
async function mount(path: string) {
  const pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: DashboardView },
      { path: '/settings/:tab?', component: SettingsView },
      { path: '/:pathMatch(.*)*', component: { render: () => null } },
    ],
  })
  await router.push(path)
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(pinia)
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await nextTick()
  return { router, workspace: useWorkspace(pinia) }
}
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
  setLanguage('zh-CN')
})

it('updates dashboard suggestions, legacy stage labels and date without changing user data', async () => {
  const { workspace } = await mount('/')
  workspace.state.name = '用户原名'
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('Hello, 用户原名')
  expect(root.textContent).toContain('Explore career direction')
  expect(root.textContent).toContain('Interviewing')
  expect(root.querySelector('.date-label')?.textContent).toBe(
    new Date().toLocaleDateString('en-US', { month: 'long', day: 'numeric', weekday: 'long' }),
  )
  setLanguage('zh-CN')
  await nextTick()
  expect(root.textContent).toContain('你好，用户原名')
  expect(root.textContent).toContain('梳理职业方向')
})

it('keeps settings tabs navigable after switching language in place', async () => {
  const { router } = await mount('/settings')
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('Make this workspace yours')
  expect(root.textContent).toContain('Basic information')
  const models = [...root.querySelectorAll('.tabs button')].find(
    (button) => button.textContent?.trim() === 'Model selection',
  )!
  ;(models as HTMLButtonElement).click()
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/settings/models'))
  expect(root.querySelector('[data-model-settings]')).not.toBeNull()
  setLanguage('zh-CN')
  await nextTick()
  expect(root.querySelector('.tabs .selected')?.textContent?.trim()).toBe('模型选择')
})
