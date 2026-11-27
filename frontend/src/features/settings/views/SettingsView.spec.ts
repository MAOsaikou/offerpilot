import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import SettingsView from './SettingsView.vue'
import { getCareerProfile, saveCareerProfile, type CareerProfile } from '@/shared/api/career'
import { useWorkspace } from '@/shared/stores/workspace'

vi.mock('@/shared/api/career', () => ({
  getCareerProfile: vi.fn(),
  saveCareerProfile: vi.fn(),
  saveCareerTasks: vi.fn(),
}))
vi.mock('../components/AccountPreferences.vue', () => ({
  default: { template: '<div data-account-preferences />' },
}))
vi.mock('@/shared/components/GitHubEvidencePanel.vue', () => ({
  default: { template: '<div data-github-evidence />' },
}))
vi.mock('../components/ResumeModelSettings.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/PrivacySettings.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/MemorySettings.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/DeleteMyData.vue', () => ({ default: { template: '<div />' } }))

const profile: CareerProfile = {
  target: '后端开发工程师',
  city: '上海',
  skills: 'Java',
  interests: [],
  major: '',
  courses: '',
  valuesText: '',
  assessment: null,
  completedTasks: [],
  updatedAt: '2026-11-27T00:00:00Z',
}
let app: App | undefined
let root: HTMLDivElement | undefined

afterEach(() => {
  app?.unmount()
  root?.remove()
  localStorage.clear()
})

it('edits the server career target instead of the browser workspace default', async () => {
  let finish!: (value: CareerProfile) => void
  vi.mocked(getCareerProfile).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  vi.mocked(saveCareerProfile).mockResolvedValue({
    ...profile,
    target: '数据工程师',
    updatedAt: '2026-11-27T01:00:00Z',
  })
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings/:tab?', component: SettingsView },
      { path: '/:pathMatch(.*)*', component: { render: () => null } },
    ],
  })
  await router.push('/settings')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(pinia)
    .use(router)
  app.mount(root)
  const workspace = useWorkspace(pinia)
  workspace.state.target = '前端开发工程师'
  await nextTick()
  const input = root.querySelector<HTMLInputElement>('input[maxlength="100"]')!
  expect(root.textContent).toContain('正在读取职业档案')
  expect(input.value).not.toBe('前端开发工程师')
  finish(profile)
  await vi.waitFor(() => expect(input.value).toBe('后端开发工程师'))
  expect(root.textContent).toContain('与职业探索和成长路线相同')
  input.value = '数据工程师'
  input.dispatchEvent(new Event('input'))
  await vi.waitFor(() => expect(saveCareerProfile).toHaveBeenCalled())
  expect(saveCareerProfile).toHaveBeenCalledWith(expect.objectContaining({ target: '数据工程师' }))
  expect(workspace.state.target).toBe('前端开发工程师')
})
