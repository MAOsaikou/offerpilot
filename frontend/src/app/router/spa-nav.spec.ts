import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type RouteRecordRaw, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AppRoot from '@/app/App.vue'
import { routes } from './index'
import { registerGuards } from './guards'
import { useAuthStore } from '@/shared/stores/auth'
import { saveGuest, saveLogin } from '@/shared/utils/authSession'

// Keep the production route tree, lazy layouts, RouterViews and Transitions.
// Only page bodies are replaced so this regression needs no API servers.
function lightRoutes(records: RouteRecordRaw[]): RouteRecordRaw[] {
  return records.map((record) => {
    if (record.children) return { ...record, children: lightRoutes(record.children) }
    if (!('component' in record)) return record
    const page = defineComponent({
      setup() {
        return () => {
          if (failRender && crashedPages.has(String(record.name)))
            throw new Error('page render failed')
          return h('section', { class: 'page-stack', 'data-page': record.name }, record.meta?.title)
        }
      },
    })
    return {
      path: record.path,
      name: record.name,
      meta: record.meta,
      component: () => Promise.resolve(page),
    }
  })
}

const crashedPages = new Set<string>()
let failRender = false
let app: App | undefined
let root: HTMLDivElement
let router: Router
let pinia: ReturnType<typeof createPinia>

beforeEach(() => {
  crashedPages.clear()
  failRender = false
  localStorage.clear()
  saveGuest('guest-nav-fixture')
  pinia = createPinia()
  setActivePinia(pinia)
  router = createRouter({ history: createMemoryHistory(), routes: lightRoutes(routes) })
  registerGuards(router)
  root = document.createElement('div')
  document.body.append(root)
})

afterEach(() => {
  app?.unmount()
  app = undefined
  root?.remove()
  pinia._s.forEach((store) => store.$dispose())
  vi.restoreAllMocks()
})

async function mount(path = '/') {
  await router.push(path)
  app = createApp(AppRoot).use(pinia).use(router)
  app.mount(root)
  await nextTick()
}

async function expectPage(name: string) {
  await vi.waitFor(() => {
    const main = root.querySelector('#main-content')
    const page = main?.querySelector<HTMLElement>(`[data-page="${name}"]`)
    expect(page).not.toBeNull()
    expect(page?.textContent?.trim()).toBeTruthy()
    expect(page?.style.display).not.toBe('none')
    expect(page?.closest('.page-leave-to')).toBeNull()
  })
  expect(document.title).toBe(`${router.currentRoute.value.meta.title} · OfferPilot`)
}

it('renders every main page on repeated client navigation with real transitions', async () => {
  await mount()
  expect(useAuthStore().isAuthenticated).toBe(true)
  await expectPage('dashboard')
  for (const path of ['/chat', '/resumes', '/jobs', '/board', '/', '/chat', '/']) {
    await router.push(path)
    await expectPage(String(router.currentRoute.value.name))
  }
})

it('mounts the next page even before the previous leave transition finishes', async () => {
  await mount()
  await expectPage('dashboard')
  const style = document.createElement('style')
  style.textContent = '.page-leave-active { transition: opacity 30s; transition-duration: 30s; }'
  document.head.append(style)
  try {
    await router.push('/chat')
    await nextTick()
    // No transitionend is dispatched; a slow leave must not block the matched page.
    expect(root.querySelector('#main-content [data-page="chat"]')?.textContent).toBe('对话')
    expect(root.querySelector('#main-content [data-page="dashboard"]')).toBeNull()
    await router.push('/resumes')
    await router.push('/jobs')
    await router.push('/board')
    await router.push('/')
    await expectPage('dashboard')
  } finally {
    style.remove()
  }
})

it('shows a recoverable page crash, retries it, and allows navigation away', async () => {
  crashedPages.add('chat')
  failRender = true
  await mount('/chat')
  await vi.waitFor(() => {
    expect(root.querySelector('#main-content [role="alert"]')?.textContent).toContain(
      '页面暂时无法显示',
    )
  })
  expect(root.querySelector('.nav-list')).not.toBeNull()
  const retry = () =>
    root.querySelector<HTMLButtonElement>('#main-content [role="alert"] button')!.click()
  retry()
  await nextTick()
  expect(root.querySelector('#main-content [role="alert"]')).not.toBeNull()
  failRender = false
  retry()
  await expectPage('chat')
  await router.push('/resumes')
  await expectPage('resumes')
  failRender = true
  await router.push('/chat')
  await vi.waitFor(() => expect(root.querySelector('#main-content [role="alert"]')).not.toBeNull())
  await router.push('/jobs')
  await expectPage('jobs')
  await router.push('/')
  await expectPage('dashboard')
})

it('recovers a failed page on navigation within the same named route', async () => {
  crashedPages.add('chat')
  failRender = true
  await mount('/chat/old-conversation')
  await vi.waitFor(() => expect(root.querySelector('#main-content [role="alert"]')).not.toBeNull())
  failRender = false
  await router.push('/chat/new-conversation')
  await expectPage('chat')
})

it('closes the mobile sidebar after navigation and keeps the active link accessible', async () => {
  await mount()
  root.querySelector<HTMLButtonElement>('[aria-label="打开导航"]')!.click()
  await nextTick()
  expect(root.querySelector('.sidebar.is-open')).not.toBeNull()
  root.querySelector<HTMLAnchorElement>('.nav-list a[href="/chat"]')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('chat'))
  await expectPage('chat')
  expect(root.querySelector('.sidebar.is-open')).toBeNull()
  expect(root.querySelector('.nav-backdrop')).toBeNull()
  expect(root.querySelector('[aria-label="打开导航"]')?.getAttribute('aria-expanded')).toBe('false')
  expect(root.querySelector('.nav-list a[aria-current="page"]')?.getAttribute('href')).toBe('/chat')
})

it('preserves the logout guard and remounts pages when identity changes', async () => {
  await mount()
  await expectPage('dashboard')
  const oldShell = root.querySelector('.app-shell')
  saveLogin({
    accessToken: `e30.${btoa(JSON.stringify({ sub: 'nav-user', exp: Date.now() / 1000 + 43200 }))}.fixture`,
    expiresIn: 43200,
    user: { id: 'nav-user', email: 'nav@example.com', nickname: '导航测试', roles: ['USER'] },
  })
  await nextTick()
  await expectPage('dashboard')
  await vi.waitFor(() => expect(root.querySelector('.app-shell')).not.toBe(oldShell))
  await router.push('/board')
  await expectPage('board')
  useAuthStore().logout()
  await router.push('/jobs')
  await vi.waitFor(() => expect(root.querySelector('[data-page="login"]')).not.toBeNull())
  expect(router.currentRoute.value.query.redirect).toBe('/jobs')
  expect(root.querySelector('#main-content')).toBeNull()
  expect(document.title).toBe('登录 · OfferPilot')
  saveGuest('guest-return-fixture')
  await router.push('/')
  await expectPage('dashboard')
})
