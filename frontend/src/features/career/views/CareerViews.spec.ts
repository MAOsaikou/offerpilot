/* eslint-disable vue/one-component-per-file -- isolated route fixtures */
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import CareerView from './CareerView.vue'
import DiscoverView from '@/features/discovery/views/DiscoverView.vue'
import { learningTasks } from '@/shared/stores/workspace'
import type { CareerProfile, CareerProfileFields } from '@/shared/api/career'
import { saveGuest } from '@/shared/utils/authSession'

const initial = (): CareerProfile => ({
  target: '服务端目标',
  city: '上海',
  skills: 'SQL',
  interests: ['技术研发'],
  completedTasks: [learningTasks[0]!],
  updatedAt: null,
})
let profiles: Record<string, CareerProfile>
let profileWrites: CareerProfileFields[]
let taskWrites: string[][]
let failSave = false
let holdTasks: Promise<void> | undefined
const ownerOf = (request: Request) => request.headers.get('Authorization')!.slice(7)
const server = setupServer(
  http.get('*/api/v1/career/profile', ({ request }) =>
    HttpResponse.json({ data: profiles[ownerOf(request)] }),
  ),
  http.put('*/api/v1/career/profile', async ({ request }) => {
    const body = (await request.json()) as CareerProfileFields
    profileWrites.push(body)
    if (failSave)
      return HttpResponse.json({ code: 50000, message: '保存暂不可用' }, { status: 503 })
    Object.assign(profiles[ownerOf(request)]!, body, { updatedAt: '2026-10-09T00:00:00Z' })
    return HttpResponse.json({ data: profiles[ownerOf(request)] })
  }),
  http.put('*/api/v1/career/tasks', async ({ request }) => {
    const body = (await request.json()) as { completed: string[] }
    taskWrites.push(body.completed)
    await holdTasks
    profiles[ownerOf(request)]!.completedTasks = body.completed
    return HttpResponse.json({ data: profiles[ownerOf(request)] })
  }),
)
let app: App
let root: HTMLDivElement
let router: Router
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  localStorage.clear()
  saveGuest('career-owner')
  profiles = {
    'career-owner': initial(),
    'other-owner': { ...initial(), target: '另一用户目标', completedTasks: [] },
  }
  profileWrites = []
  taskWrites = []
  failSave = false
  holdTasks = undefined
})
afterEach(() => {
  app?.unmount()
  root?.remove()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())

async function mount(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/career', component: CareerView },
      { path: '/discover', component: DiscoverView },
      { path: '/chat', component: { render: () => h('div', 'chat') } },
      { path: '/kb', component: { render: () => h('div') } },
    ],
  })
  await router.push(path)
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(createPinia())
    .use(router)
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('已与服务器同步'))
}

async function edit(selector: string, value: string) {
  const input = root.querySelector<HTMLInputElement>(selector)!
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
function clickButton(text: string) {
  const button = [...root.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === text,
  )!
  button.click()
}

it('loads the server profile, debounces text, saves interest and target, then restores the growth view', async () => {
  await mount('/discover')
  expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')!.value).toBe('服务端目标')
  await edit('input[maxlength="100"]', '编辑中')
  await edit('input[maxlength="100"]', '数据分析师')
  expect(profileWrites).toHaveLength(0)
  await vi.waitFor(() => expect(profiles['career-owner']!.target).toBe('数据分析师'))
  expect(profileWrites).toHaveLength(1)
  root.querySelectorAll<HTMLInputElement>('.chip input')[2]!.click()
  await vi.waitFor(() => expect(profiles['career-owner']!.interests).toContain('数据分析'))
  clickButton('设为探索目标')
  await vi.waitFor(() => expect(profiles['career-owner']!.target).toBe('软件开发工程师'))
  await router.push('/career')
  await vi.waitFor(() => expect(root.textContent).toContain('软件开发工程师'))
  const chat = root.querySelector<HTMLAnchorElement>('a[href*="mode=COACH"]')!
  expect(decodeURIComponent(chat.href)).toContain('软件开发工程师')
  expect(decodeURIComponent(chat.href)).toContain('SQL')
})

it('flushes a pending text edit when navigating to the career page', async () => {
  await mount('/discover')
  await edit('input[maxlength="100"]', '离开前的新目标')
  await router.push('/career')
  await vi.waitFor(() => expect(root.textContent).toContain('离开前的新目标'))
  expect(profileWrites).toHaveLength(1)
})

it('keeps optimistic edits on save failure and retries the same profile', async () => {
  await mount('/discover')
  failSave = true
  clickButton('设为探索目标')
  await vi.waitFor(() =>
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('保存暂不可用'),
  )
  expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')!.value).toBe(
    '软件开发工程师',
  )
  expect(profiles['career-owner']!.target).toBe('服务端目标')
  failSave = false
  clickButton('重试')
  await vi.waitFor(() => expect(root.querySelector('[role="alert"]')).toBeNull())
  expect(profiles['career-owner']!.target).toBe('软件开发工程师')
})

it('serializes rapid checklist edits and restores the completed set after navigation', async () => {
  await mount('/career')
  let release!: () => void
  holdTasks = new Promise<void>((resolve) => {
    release = resolve
  })
  root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1]!.click()
  await vi.waitFor(() => expect(taskWrites).toHaveLength(1))
  root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[2]!.click()
  await nextTick()
  expect(root.querySelectorAll('input:checked')).toHaveLength(3)
  expect(taskWrites).toHaveLength(1)
  release()
  await vi.waitFor(() => expect(profiles['career-owner']!.completedTasks).toHaveLength(3))
  expect(taskWrites[1]).toEqual(learningTasks.slice(0, 3))
  await router.push('/discover')
  await router.push('/career')
  await vi.waitFor(() => expect(root.querySelectorAll('input:checked')).toHaveLength(3))
})

it('clears pending old-owner edits on identity switch and loads the new owner', async () => {
  await mount('/discover')
  await edit('input[maxlength="100"]', '旧用户未保存草稿')
  saveGuest('other-owner')
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')?.value).toBe(
      '另一用户目标',
    ),
  )
  expect(profileWrites).toHaveLength(0)
  expect(profiles['career-owner']!.target).toBe('服务端目标')
  await router.push('/career')
  await vi.waitFor(() => expect(root.textContent).toContain('另一用户目标'))
  expect(root.querySelectorAll('input:checked')).toHaveLength(0)
})

it('shows a load error and disables writes until retry succeeds', async () => {
  server.use(
    http.get('*/api/v1/career/profile', () =>
      HttpResponse.json({ code: 50000, message: '读取暂不可用' }, { status: 503 }),
    ),
  )
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/discover', component: DiscoverView }],
  })
  await router.push('/discover')
  root = document.createElement('div')
  app = createApp({ render: () => h(RouterView) })
    .use(createPinia())
    .use(router)
  app.mount(root)
  await vi.waitFor(() =>
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('读取暂不可用'),
  )
  expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')!.disabled).toBe(true)
  server.resetHandlers()
  clickButton('重试')
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')!.disabled).toBe(false),
  )
  expect(profileWrites).toHaveLength(0)
})
