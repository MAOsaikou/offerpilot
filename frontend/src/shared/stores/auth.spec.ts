import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from './auth'
import { registerGuards } from '@/app/router/guards'
import { queryClient } from '@/app/providers/query'
import { apiRequest } from '@/shared/api/client'
import { streamAgentChat } from '@/shared/api/agent'
import { createApp, defineComponent, nextTick, onUnmounted, ref } from 'vue'
import App from '@/app/App.vue'
import { identitySignal } from '@/shared/utils/authSession'
import { optimizeResume } from '@/shared/api/optimizations'
import { listStaffUsers, setUserStatus } from '@/shared/api/auth'
import { authenticatedHeaders, fetchWithIdentity } from '@/shared/api/client'

function jwt(id: string, expires = Date.now() / 1000 + 43200): string {
  return `e30.${btoa(JSON.stringify({ sub: id, exp: expires }))}.signature`
}
function loginResponse(id: string, roles = ['USER']): Response {
  return new Response(
    JSON.stringify({
      data: {
        accessToken: jwt(id),
        expiresIn: 43200,
        user: { id, email: `${id}@example.com`, nickname: id, roles },
      },
    }),
  )
}
function router() {
  const component = { template: '<div />' }
  const result = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component },
      { path: '/resumes', component },
      { path: '/login', name: 'login', component, meta: { public: true } },
      { path: '/staff/login', name: 'staff-login', component, meta: { public: true } },
      { path: '/staff', component, meta: { staff: true } },
    ],
  })
  registerGuards(result)
  return result
}
let pinia: Pinia
beforeEach(() => {
  localStorage.clear()
  queryClient.clear()
  pinia = createPinia()
  setActivePinia(pinia)
})
afterEach(() => {
  disposePinia(pinia)
  queryClient.clear()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it.each([
  ['request', 'old-guest'],
  ['request', null],
  ['enterGuest', 'old-guest'],
  ['enterGuest', null],
] as const)('等待访客锁期间登录，排队的 %s 不使用旧访客或覆盖新账号：%s', async (method, guest) => {
  if (guest) localStorage.setItem('offerpilot-guest-v1', guest)
  let release!: () => void
  const request = vi.fn(
    (_name: string, callback: () => Promise<unknown>) =>
      new Promise<unknown>((resolve) => {
        release = () => resolve(callback())
      }),
  )
  vi.stubGlobal('navigator', { locks: { request } })
  const fetch = vi.fn().mockResolvedValue(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  const pending = (method === 'request' ? apiRequest('/resumes') : auth.enterGuest()).catch(
    (error) => error,
  )
  expect(request).toHaveBeenCalledOnce()
  await auth.login('B@example.com', 'password')
  release()
  expect(await pending).toBeInstanceOf(Error)
  expect(auth.user?.id).toBe('B')
  expect(auth.isLoggedIn).toBe(true)
  expect(fetch).toHaveBeenCalledOnce()
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe(guest)
})

it('取到请求头后切换账号，不发出旧凭据的写请求', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  const headers = await authenticatedHeaders()
  await auth.login('B@example.com', 'password')
  await expect(
    fetchWithIdentity('/api/v1/resumes', { method: 'POST', headers }),
  ).rejects.toMatchObject({ code: 20001 })
  expect(fetch).toHaveBeenCalledTimes(2)
})

it.each(['list', 'status'] as const)(
  '客服 %s 请求的迟到正文在身份切换后不能交付',
  async (method) => {
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
      .mockResolvedValueOnce(loginResponse('A', ['STAFF']))
      .mockResolvedValueOnce(response)
      .mockResolvedValueOnce(loginResponse('B'))
    vi.stubGlobal('fetch', fetch)
    const auth = useAuthStore()
    await auth.staffLogin('A@example.com', 'password')
    const pending = (
      method === 'list' ? listStaffUsers() : setUserStatus('user', 'DISABLED')
    ).catch((error) => error)
    await vi.waitFor(() => expect(response.bodyUsed).toBe(true))
    await auth.login('B@example.com', 'password')
    body.enqueue(new TextEncoder().encode(JSON.stringify({ data: ['A-private'] })))
    body.close()
    expect(await pending).toMatchObject({ code: 20001 })
    expect(auth.user?.id).toBe('B')
  },
)

it('客服接口当前 401 使原账号失效且不重放请求', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('staff', ['STAFF']))
    .mockResolvedValueOnce(new Response(JSON.stringify({ code: 20002 }), { status: 401 }))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.staffLogin('staff@example.com', 'password')
  await expect(listStaffUsers()).rejects.toMatchObject({ code: 20002 })
  expect(auth.isAuthenticated).toBe(false)
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('访客体验只在明确选择后创建，恢复时不创建替代身份', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ data: { accessToken: 'guest-created' } })))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  expect(fetch).not.toHaveBeenCalled()
  expect(await auth.enterGuest()).toBe(true)
  expect(auth.isAuthenticated).toBe(true)
  auth.logout()
  expect(auth.isAuthenticated).toBe(false)
  expect(await auth.enterGuest()).toBe(true)
  expect(fetch).toHaveBeenCalledOnce()
})

it.each([null, 'existing-guest'])(
  '其他标签在注销清除 JWT 时请求，不创建或回退访客：%s',
  async (guest) => {
    localStorage.setItem('offerpilot-access-token', jwt('A'))
    if (guest) localStorage.setItem('offerpilot-guest-v1', guest)
    const auth = useAuthStore()
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ data: { accessToken: 'unexpected-guest' } })),
      )
    vi.stubGlobal('fetch', fetch)
    const pending: Promise<unknown>[] = []
    const removeItem = Storage.prototype.removeItem
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      removeItem.call(this, key)
      if (key === 'offerpilot-access-token' && pending.length === 0) {
        // Another tab can react between separate localStorage writes.
        window.dispatchEvent(new StorageEvent('storage', { key }))
        pending.push(apiRequest('/conversations').catch((error: unknown) => error))
      }
    })
    try {
      auth.logout()
      const responses = await Promise.all(pending)
      expect(pending).toHaveLength(1)
      expect(fetch).not.toHaveBeenCalled()
      expect(responses[0]).toMatchObject({ code: 20002 })
      expect(localStorage.getItem('offerpilot-guest-v1')).toBe(guest)
    } finally {
      remove.mockRestore()
    }
  },
)

it('当前访客 401 保留访问凭据，不允许创建新身份替代历史', async () => {
  localStorage.setItem('offerpilot-guest-v1', 'expired-guest')
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 20002 }), { status: 401 })),
  )
  const auth = useAuthStore()
  await expect(apiRequest('/conversations')).rejects.toMatchObject({ code: 20002 })
  expect(auth.isAuthenticated).toBe(false)
  expect(auth.hasStoredGuest).toBe(true)
  expect(await auth.enterGuest()).toBe(false)
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe('expired-guest')
})

it('令牌达到 exp 时主动进入重新认证状态', async () => {
  vi.useFakeTimers()
  const expires = Math.floor(Date.now() / 1000) + 2
  localStorage.setItem('offerpilot-access-token', jwt('A', expires))
  const auth = useAuthStore()
  expect(auth.isLoggedIn).toBe(true)
  await vi.advanceTimersByTimeAsync(2100)
  expect(auth.isLoggedIn).toBe(false)
  expect(auth.requiresLogin).toBe(true)
})

it('已有访客可直接恢复简历页面，访问登录页也可选择账号登录', async () => {
  localStorage.setItem('offerpilot-guest-v1', 'existing-guest')
  const routes = router()
  await routes.push('/resumes')
  expect(routes.currentRoute.value.path).toBe('/resumes')
  await routes.push('/login')
  expect(routes.currentRoute.value.name).toBe('login')
})
it('过期登录令牌允许重新登录且不静默切换到已有访客', async () => {
  localStorage.setItem('offerpilot-access-token', jwt('A', 1))
  localStorage.setItem('offerpilot-guest-v1', 'existing-guest')
  const routes = router()
  await routes.push('/login')
  expect(routes.currentRoute.value.name).toBe('login')
  expect(useAuthStore().isAuthenticated).toBe(false)
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  await expect(apiRequest('/conversations')).rejects.toMatchObject({ code: 20002 })
  expect(fetch).not.toHaveBeenCalled()
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe('existing-guest')
})
it('切换账号清除简历缓存，B 必须读取自己的数据', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  queryClient.setQueryData(['resumes', 1], [{ id: 'A-private' }])
  auth.logout()
  await auth.login('B@example.com', 'password')
  const readB = vi.fn().mockResolvedValue([{ id: 'B-private' }])
  expect(await queryClient.fetchQuery({ queryKey: ['resumes', 1], queryFn: readB })).toEqual([
    { id: 'B-private' },
  ])
  expect(readB).toHaveBeenCalledOnce()
})
it('身份切换取消旧查询，迟到结果不能重填缓存', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(loginResponse('A')).mockResolvedValueOnce(loginResponse('B')),
  )
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  let finish = (_: string[]) => {}
  const pending = queryClient
    .fetchQuery({
      queryKey: ['resumes'],
      queryFn: () =>
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
    })
    .catch(() => null)
  auth.logout()
  await auth.login('B@example.com', 'password')
  queryClient.setQueryData(['resumes'], ['B-private'])
  finish(['A-private'])
  await pending
  expect(queryClient.getQueryData(['resumes'])).toEqual(['B-private'])
})
it('客服登录与注销同步守卫使用的认证状态', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(loginResponse('staff', ['STAFF'])))
  const auth = useAuthStore()
  const routes = router()
  await routes.push('/staff/login')
  await auth.staffLogin('staff@example.com', 'password')
  await routes.push('/staff')
  expect(routes.currentRoute.value.path).toBe('/staff')
  auth.logout()
  await routes.push('/staff/login')
  await routes.push('/staff')
  expect(routes.currentRoute.value.name).toBe('staff-login')
})
it('普通用户不能进入客服工作台', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(loginResponse('user')))
  await useAuthStore().login('user@example.com', 'password')
  const routes = router()
  await routes.push('/staff')
  expect(routes.currentRoute.value.name).toBe('staff-login')
})
it('当前 JWT 的 401 进入重新认证，不重放请求或替换访客', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 20002, message: '令牌过期' }), { status: 401 }),
    )
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  localStorage.setItem('offerpilot-guest-v1', 'existing-guest')
  await expect(apiRequest('/conversations', { method: 'POST', body: '{}' })).rejects.toMatchObject({
    code: 20002,
  })
  expect(auth.isAuthenticated).toBe(false)
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(localStorage.getItem('offerpilot-guest-v1')).toBe('existing-guest')
})
it('旧账号迟到的 401 不会注销新账号', async () => {
  let finish = (_: Response) => {}
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValueOnce(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  const pending = apiRequest('/conversations').catch(() => null)
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  await auth.login('B@example.com', 'password')
  finish(new Response(JSON.stringify({ code: 20002 }), { status: 401 }))
  await pending
  expect(auth.user?.id).toBe('B')
  expect(auth.isAuthenticated).toBe(true)
})

it('响应头已返回后切换账号，旧 JSON 正文不能进入新页面', async () => {
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
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(response)
    .mockResolvedValueOnce(loginResponse('B'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  const pending = apiRequest('/resumes').catch((error) => error)
  await vi.waitFor(() => expect(response.bodyUsed).toBe(true))
  const oldSignal = fetch.mock.calls[1]?.[1]?.signal as AbortSignal
  await auth.login('B@example.com', 'password')
  expect(oldSignal.aborted).toBe(true)
  body.enqueue(new TextEncoder().encode(JSON.stringify({ data: ['A-private'] })))
  body.close()
  expect(await pending).toMatchObject({ code: 20001 })
  expect(auth.user?.id).toBe('B')
})

it.each(['agent', 'optimization'])(
  '已连接的 %s SSE 在账号切换后丢弃旧帧并关闭读取器',
  async (kind) => {
    let body!: ReadableStreamDefaultController<Uint8Array>
    const cancel = vi.fn()
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          body = controller
        },
        cancel,
      }),
    )
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(loginResponse('A'))
        .mockResolvedValueOnce(response)
        .mockResolvedValueOnce(loginResponse('B')),
    )
    const auth = useAuthStore()
    await auth.login('A@example.com', 'password')
    const onEvent = vi.fn()
    const pending = (
      kind === 'agent'
        ? streamAgentChat({ content: 'hello' }, { onEvent })
        : optimizeResume('r', 'v', 'request', 'JD', new AbortController().signal, onEvent)
    ).catch((error) => error)
    await vi.waitFor(() => expect(response.body?.locked).toBe(true))
    body.enqueue(
      new TextEncoder().encode(
        'data: {"type":"run.started","runId":"A-run","reportId":"A-report"}\n\n',
      ),
    )
    await vi.waitFor(() => expect(onEvent).toHaveBeenCalledOnce())
    await auth.login('B@example.com', 'password')
    body.enqueue(
      new TextEncoder().encode(
        'data: {"type":"text.delta","runId":"A-run","delta":"A-private"}\n\n',
      ),
    )
    expect(await pending).toMatchObject({ code: 20001 })
    expect(onEvent).toHaveBeenCalledOnce()
    expect(cancel).toHaveBeenCalledOnce()
  },
)

it.each(['login', 'register', 'staffLogin', 'enterGuest'] as const)(
  '迟到的 %s 认证结果不会覆盖另一标签刚登录的新账号',
  async (method) => {
    let body!: ReadableStreamDefaultController<Uint8Array>
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          body = controller
        },
      }),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(response).mockResolvedValueOnce(loginResponse('B')),
    )
    const auth = useAuthStore()
    const pending = (
      method === 'enterGuest' ? auth.enterGuest() : auth[method]('A@example.com', 'password')
    ).catch((error) => error)
    await vi.waitFor(() => expect(response.bodyUsed).toBe(true))
    await auth.login('B@example.com', 'password')
    body.enqueue(
      new TextEncoder().encode(
        method === 'enterGuest'
          ? JSON.stringify({ data: { accessToken: 'guest-old' } })
          : await loginResponse('A').text(),
      ),
    )
    body.close()
    expect(await pending).toMatchObject({ code: 20001 })
    expect(auth.user?.id).toBe('B')
    expect(auth.isLoggedIn).toBe(true)
    expect(localStorage.getItem('offerpilot-guest-v1')).toBeNull()
  },
)

it('其他标签直接切换账号时销毁页面中的旧私有状态', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(loginResponse('A')))
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  const disposed = vi.fn()
  const component = defineComponent({
    setup() {
      const privateText = ref(`${auth.user?.id}-private`)
      onUnmounted(disposed)
      return { privateText }
    },
    template: '<div>{{ privateText }}</div>',
  })
  const routes = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }],
  })
  await routes.push('/')
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(App).use(pinia).use(routes)
  app.mount(root)
  try {
    expect(root.textContent).toContain('A-private')
    localStorage.setItem('offerpilot-access-token', jwt('B'))
    localStorage.setItem(
      'offerpilot-auth-user',
      JSON.stringify({ id: 'B', email: 'B@example.com', nickname: 'B', roles: ['USER'] }),
    )
    window.dispatchEvent(new StorageEvent('storage', { key: 'offerpilot-access-token' }))
    await nextTick()
    await vi.waitFor(() => expect(root.textContent).toContain('B-private'))
    const newSession = identitySignal()
    let finish!: (value: string[]) => void
    const newQuery = queryClient.fetchQuery({
      queryKey: ['resumes'],
      queryFn: () =>
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
    })
    window.dispatchEvent(new StorageEvent('storage', { key: 'offerpilot-auth-user' }))
    window.dispatchEvent(new StorageEvent('storage', { key: 'offerpilot-login-required' }))
    expect(identitySignal()).toBe(newSession)
    expect(newSession.aborted).toBe(false)
    finish(['B-private'])
    expect(await newQuery).toEqual(['B-private'])
    expect(queryClient.getQueryData(['resumes'])).toEqual(['B-private'])
    expect(root.textContent).not.toContain('A-private')
    expect(disposed).toHaveBeenCalledOnce()
  } finally {
    app.unmount()
    root.remove()
  }
})

it('体验登录复用身份切换并清除原账号查询缓存', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(loginResponse('demo'))
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  queryClient.setQueryData(['resumes'], ['A-private'])
  await auth.demoLogin()
  expect(auth.user?.id).toBe('demo')
  expect(auth.isStaff).toBe(false)
  expect(queryClient.getQueryData(['resumes'])).toBeUndefined()
  expect(fetch.mock.calls[1]![0]).toContain('/auth/demo-login')
})

it('体验账号未启用时保留当前身份并交付 403 错误', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(loginResponse('A'))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 20007, message: '体验账号未启用' }), { status: 403 }),
    )
  vi.stubGlobal('fetch', fetch)
  const auth = useAuthStore()
  await auth.login('A@example.com', 'password')
  await expect(auth.demoLogin()).rejects.toMatchObject({ code: 20007, message: '体验账号未启用' })
  expect(auth.user?.id).toBe('A')
})
