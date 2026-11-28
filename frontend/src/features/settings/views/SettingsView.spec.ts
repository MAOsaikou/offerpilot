import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import SettingsView from './SettingsView.vue'
import { getCareerProfile, saveCareerProfile, type CareerProfile } from '@/shared/api/career'
import { useWorkspace } from '@/shared/stores/workspace'
import { setLanguage } from '@/shared/utils/language'
import fs from 'node:fs'
import path from 'node:path'

const cssPath = [
  path.resolve(process.cwd(), 'src/shared/styles/workspace.css'),
  path.resolve(process.cwd(), 'frontend/src/shared/styles/workspace.css'),
].find(fs.existsSync)
const workspaceStyles = cssPath ? fs.readFileSync(cssPath, 'utf-8') : ''

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
  setLanguage('zh-CN')
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

it('keeps all four settings tabs in a single row without wrapping into three-plus-one', async () => {
  // Ensure stylesheet contracts lock out flex-wrap and enforce horizontal scroll on narrow screens
  expect(workspaceStyles).toMatch(/\.tabs\s*\{[^}]*flex-wrap:\s*nowrap/)
  expect(workspaceStyles).toMatch(/\.tabs\s*\{[^}]*overflow-x:\s*auto/)
  expect(workspaceStyles).toMatch(/\.tabs\s*\{[^}]*max-width:\s*100%/)
  expect(workspaceStyles).not.toMatch(/\.tabs\s*\{[^}]*flex-wrap:\s*wrap/)
  expect(workspaceStyles).toMatch(/\.tabs button\s*\{[^}]*white-space:\s*nowrap/)
  expect(workspaceStyles).toMatch(/\.tabs button\s*\{[^}]*flex-shrink:\s*0/)

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
  await nextTick()

  const tabs = root.querySelector<HTMLDivElement>('.tabs')
  expect(tabs).not.toBeNull()
  const buttons = [...tabs!.querySelectorAll<HTMLButtonElement>('button')]
  expect(buttons).toHaveLength(4)
  expect(buttons.map((b) => b.textContent?.trim())).toEqual([
    '个人偏好',
    '模型选择',
    '连接与联调',
    '数据与隐私',
  ])

  // The 4th tab "数据与隐私" (which previously dropped to the second line) is in the same tabs bar and clickable
  const privacyButton = buttons.find((b) => b.textContent?.trim() === '数据与隐私')!
  privacyButton.click()
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/settings/privacy'))
  expect(privacyButton.classList.contains('selected')).toBe(true)
  expect(privacyButton.getAttribute('aria-pressed')).toBe('true')
})

it('protects the last tab from border-radius corner clipping at max scroll by ensuring end padding matches or exceeds border-radius', async () => {
  // Stylesheet contract: the horizontal/end padding of .tabs must be at least equal to its border-radius
  // so the last character ("私" in "数据与隐私") does not get clipped by the rounded corner when scrolled to the end
  const tabsRule = workspaceStyles.match(/\.tabs\s*\{([^}]+)\}/)?.[1] ?? ''
  const borderRadiusMatch = tabsRule.match(/border-radius:\s*(\d+)px/)
  expect(borderRadiusMatch).not.toBeNull()
  const borderRadius = parseInt(borderRadiusMatch![1], 10)

  const paddingShorthand = tabsRule.match(/padding:\s*(\d+)px\s+(\d+)px/)
  const paddingInline = tabsRule.match(/padding-(?:inline|right|inline-end):\s*(\d+)px/)
  const endPadding = paddingShorthand
    ? parseInt(paddingShorthand[2], 10)
    : paddingInline
      ? parseInt(paddingInline[1], 10)
      : 0

  expect(endPadding).toBeGreaterThanOrEqual(borderRadius)
  expect(endPadding).toBeGreaterThanOrEqual(10)

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
  await nextTick()

  const tabs = root.querySelector<HTMLDivElement>('.tabs')
  expect(tabs).not.toBeNull()
  const buttons = [...tabs!.querySelectorAll<HTMLButtonElement>('button')]
  const lastButton = buttons[buttons.length - 1]
  expect(lastButton?.textContent?.trim()).toBe('数据与隐私')

  // When scrolled to the maximum scrollLeft, the remaining distance from the button's right edge
  // to the tabs container right boundary is determined by endPadding.
  // Because endPadding >= borderRadius, the last button and its text are completely clear of the corner curve.
  expect(endPadding).toBeGreaterThanOrEqual(10)
})
