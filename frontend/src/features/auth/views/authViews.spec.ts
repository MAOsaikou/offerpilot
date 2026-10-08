import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
import { queryClient } from '@/shared/api/queryClient'
import LoginView from './LoginView.vue'
import StaffConsoleView from './StaffConsoleView.vue'

let app: App | undefined
let root: HTMLDivElement
let pinia: Pinia
beforeEach(() => {
  localStorage.clear()
  queryClient.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  root = document.createElement('div')
  document.body.append(root)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  root.remove()
  disposePinia(pinia)
  queryClient.clear()
  vi.unstubAllGlobals()
})
function loginResponse(id: string, roles = ['USER']) {
  return new Response(
    JSON.stringify({
      data: {
        accessToken: `e30.${btoa(JSON.stringify({ sub: id, exp: Date.now() / 1000 + 43200 }))}.signature`,
        expiresIn: 43200,
        user: { id, email: `${id}@example.com`, nickname: id, roles },
      },
    }),
  )
}
async function mount(path = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/staff', component: StaffConsoleView },
      { path: '/staff/login', component: { template: '<div>staff login</div>' } },
      { path: '/resumes', component: { template: '<div>resume workspace</div>' } },
      { path: '/', component: { template: '<div>workspace</div>' } },
    ],
  })
  await router.push(path)
  app = createApp({ render: () => h(RouterView) })
    .use(pinia)
    .use(router)
  app.mount(root)
  await nextTick()
  return router
}
function button(text: string) {
  const result = [...root.querySelectorAll('button')].find((item) =>
    item.textContent?.includes(text),
  )
  expect(result, `应提供 ${text} 按钮`).toBeDefined()
  return result!
}

it('首次访客入口明确创建身份并回到原业务页面', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ data: { accessToken: 'guest-new' } })))
  vi.stubGlobal('fetch', fetch)
  const router = await mount('/login?redirect=/resumes')
  expect(fetch).not.toHaveBeenCalled()
  button('以访客身份体验').click()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/resumes'))
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe('guest-new')
  expect(fetch).toHaveBeenCalledOnce()
})

it('注销后可明确恢复原访客，且不创建替代身份', async () => {
  localStorage.setItem('offerpilot-guest-v1', 'guest-existing')
  const auth = useAuthStore()
  auth.logout()
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const router = await mount()
  expect(root.textContent).toContain('原访客数据仍保留')
  expect(root.textContent).toContain('注册账号不会自动迁移访客数据')
  button('继续使用原访客身份').click()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  expect(auth.isAuthenticated).toBe(true)
  expect(fetch).not.toHaveBeenCalled()
})

it('失效访客保留凭据并显示恢复限制，不提供新建替代入口', async () => {
  localStorage.setItem('offerpilot-guest-v1', 'guest-invalid')
  localStorage.setItem('offerpilot-invalid-guest', 'guest-invalid')
  localStorage.setItem('offerpilot-login-required', 'true')
  vi.stubGlobal('fetch', vi.fn())
  await mount()
  expect(root.textContent).toContain('原访客身份已失效')
  expect(root.textContent).toContain('请联系管理员恢复历史数据')
  expect(root.textContent).not.toContain('以访客身份体验')
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe('guest-invalid')
})

it('旧客服身份验证完成后不注销随后登录的新账号', async () => {
  let body!: ReadableStreamDefaultController<Uint8Array>
  const response = new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        body = controller
      },
    }),
  )
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('staff', ['STAFF']))
    .mockResolvedValueOnce(response)
    .mockResolvedValueOnce(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.staffLogin('staff@example.com', 'password')
  await mount('/staff')
  await vi.waitFor(() => expect(response.bodyUsed).toBe(true))
  await auth.login('B@example.com', 'password')
  body.enqueue(
    new TextEncoder().encode(JSON.stringify({ data: { roles: ['STAFF'], nickname: 'old' } })),
  )
  body.close()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  expect(auth.isLoggedIn).toBe(true)
  expect(auth.user?.id).toBe('B')
})

it('客服验证网络失败显示错误并允许重试，不注销账号', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(loginResponse('staff', ['STAFF']))
      .mockRejectedValueOnce(new TypeError('网络暂时不可用')),
  )
  const auth = useAuthStore()
  await auth.staffLogin('staff@example.com', 'password')
  await mount('/staff')
  await vi.waitFor(() => expect(root.textContent).toContain('网络暂时不可用'))
  expect(auth.isLoggedIn).toBe(true)
})

it('新凭据已写入但 storage 事件尚未送达时，旧客服错误不清除新账号', async () => {
  let finish!: (response: Response) => void
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('staff', ['STAFF']))
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.staffLogin('staff@example.com', 'password')
  await mount('/staff')
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  const result = await loginResponse('B').json()
  localStorage.setItem('offerpilot-access-token', result.data.accessToken)
  localStorage.setItem('offerpilot-auth-user', JSON.stringify(result.data.user))
  finish(
    new Response(JSON.stringify({ code: 20004, message: '需要客服账号权限' }), { status: 403 }),
  )
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(localStorage.getItem('offerpilot-access-token')).toBe(result.data.accessToken)
  window.dispatchEvent(new StorageEvent('storage', { key: 'offerpilot-access-token' }))
  expect(auth.user?.id).toBe('B')
})
