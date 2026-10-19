vi.mock('@/shared/components/BackgroundTaskStatus.vue', () => ({
  default: { template: '<div />' },
}))
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
  major: '软件工程',
  courses: '数据库',
  valuesText: '成长',
  assessment: null,
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
  http.get('*/api/v1/career/recommendations', () => HttpResponse.json({ data: null })),
  http.get('*/api/v1/career/occupations', () => HttpResponse.json({ data: [] })),
  http.get('*/api/v1/career/assessment', () =>
    HttpResponse.json({
      data: {
        questions: ['专业？'],
        answers: [''],
        inference: null,
        confirmed: null,
        confirmedAt: null,
      },
    }),
  ),
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
  expect(profileWrites[0]).toEqual({
    target: '数据分析师',
    city: '上海',
    skills: 'SQL',
    interests: ['技术研发'],
    major: '软件工程',
    courses: '数据库',
    valuesText: '成长',
  })
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

it('edits all three panels, preserves failed drafts, and restores them after navigation', async () => {
  await mount('/discover')
  expect(root.textContent).toContain('专业与课程')
  expect(root.textContent).toContain('兴趣与技能')
  expect(root.textContent).toContain('性格与价值观')
  expect(root.querySelector('a[href="#career-assessment"]')?.textContent).toContain('继续职业测评')
  failSave = true
  await edit('input[name="major"]', '统计学')
  await edit('textarea[name="courses"]', '概率论\n数据挖掘')
  await edit('textarea[name="valuesText"]', '成长\n平衡')
  await vi.waitFor(() => expect(root.textContent).toContain('保存暂不可用'))
  expect(root.querySelector<HTMLTextAreaElement>('textarea[name="courses"]')!.value).toBe(
    '概率论\n数据挖掘',
  )
  failSave = false
  clickButton('重试')
  await vi.waitFor(() => expect(profiles['career-owner']!.major).toBe('统计学'))
  await router.push('/career')
  await router.push('/discover')
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('input[name="major"]')?.value).toBe('统计学'),
  )
  expect(root.querySelector<HTMLTextAreaElement>('textarea[name="valuesText"]')!.value).toBe(
    '成长\n平衡',
  )
})

it('edits the confirmed projection through assessment confirm and refreshes both panels without reloading', async () => {
  profiles['career-owner']!.assessment = {
    riasec: { R: 20, I: 90, A: 10, S: 30, E: 60, C: 40 },
    code: 'IEC',
    traits: [{ name: '好奇', confidence: 0.8, evidence: '喜欢研究' }],
    constraints: { city: '上海', salary: '', overtime: '' },
  }
  let confirms = 0
  server.use(
    http.put('*/api/v1/career/assessment/confirm', async ({ request }) => {
      const body = (await request.json()) as {
        riasec: Record<'R' | 'I' | 'A' | 'S' | 'E' | 'C', number>
        traits: string[]
        city: string
        salary: string
        overtime: string
      }
      confirms++
      if (confirms === 1)
        return HttpResponse.json({ code: 50000, message: '确认暂不可用' }, { status: 503 })
      const confirmed = {
        riasec: body.riasec,
        code: 'SIC',
        traits: body.traits.map((name) => ({ name, confidence: 1, evidence: '' })),
        constraints: { city: body.city, salary: body.salary, overtime: body.overtime },
      }
      profiles['career-owner']!.assessment = confirmed
      return HttpResponse.json({
        data: { questions: [], answers: [], inference: null, confirmed, confirmedAt: 'now' },
      })
    }),
  )
  await mount('/discover')
  expect(root.textContent).toContain('喜欢研究')
  clickButton('编辑性格与约束')
  await nextTick()
  await edit('input[name="riasec-S"]', '95')
  root
    .querySelector<HTMLFormElement>('.personality-panel form')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('确认暂不可用'))
  expect(root.querySelector<HTMLInputElement>('input[name="riasec-S"]')!.value).toBe('95')
  root
    .querySelector<HTMLFormElement>('.personality-panel form')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('SIC'))
  expect(root.querySelector('input[name="riasec-S"]')).toBeNull()
  expect(profileWrites).toHaveLength(0)
})

it('merges a confirmed city with course edits made while confirmation is in flight', async () => {
  profiles['career-owner']!.assessment = {
    riasec: { R: 20, I: 90, A: 10, S: 30, E: 60, C: 40 },
    code: 'IEC',
    traits: [],
    constraints: { city: '杭州', salary: '', overtime: '' },
  }
  let release!: () => void
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  let started = false
  server.use(
    http.put('*/api/v1/career/assessment/confirm', async () => {
      started = true
      await held
      const confirmed = profiles['career-owner']!.assessment!
      profiles['career-owner']!.city = confirmed.constraints.city
      return HttpResponse.json({
        data: { questions: [], answers: [], inference: null, confirmed, confirmedAt: 'now' },
      })
    }),
  )
  await mount('/discover')
  clickButton('编辑性格与约束')
  await nextTick()
  root
    .querySelector<HTMLFormElement>('.personality-panel form')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(started).toBe(true))
  await edit('textarea[name="courses"]', '确认期间补充的课程')
  release()
  await vi.waitFor(() => expect(profiles['career-owner']!.courses).toBe('确认期间补充的课程'))
  expect(profiles['career-owner']!.city).toBe('杭州')
  expect(profileWrites.at(-1)?.city).toBe('杭州')
})
