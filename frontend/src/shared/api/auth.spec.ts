import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { demoLogin } from './auth'

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

it('体验登录发送无正文 POST 并读取标准 LoginResult', async () => {
  const result = {
    accessToken: 'demo-jwt',
    expiresIn: 43200,
    user: { id: 'demo', email: 'demo@offerpilot.local', nickname: '体验账号', roles: ['USER'] },
  }
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: result })))
  vi.stubGlobal('fetch', fetch)
  expect(await demoLogin()).toEqual(result)
  expect(fetch.mock.calls[0]![0]).toContain('/api/v1/auth/demo-login')
  expect(fetch.mock.calls[0]![1]).toMatchObject({ method: 'POST' })
  expect(fetch.mock.calls[0]![1].body).toBeUndefined()
})

it('体验登录交付后端禁用错误', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 20007, message: '体验账号未启用' }), { status: 403 }),
      ),
  )
  await expect(demoLogin()).rejects.toMatchObject({ code: 20007, message: '体验账号未启用' })
})

it('删除只发送确认词，使用当前凭据且保留服务端错误', async () => {
  const { deleteMyData } = await import('./auth')
  const { saveGuest } = await import('@/shared/utils/authSession')
  saveGuest('deletion-guest')
  const fetch = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ code: 0, data: null }), { status: 200 }))
  vi.stubGlobal('fetch', fetch)
  expect(await deleteMyData('DELETE')).toBeNull()
  expect(fetch.mock.calls[0]![0]).toContain('/api/v1/users/me/data')
  expect(fetch.mock.calls[0]![1]).toMatchObject({
    method: 'DELETE',
    body: '{"confirm":"DELETE"}',
    headers: { Authorization: 'Bearer deletion-guest' },
  })
  fetch.mockResolvedValue(
    new Response(JSON.stringify({ code: 20007, message: '共享体验账号不能删除' }), { status: 403 }),
  )
  await expect(deleteMyData('DELETE')).rejects.toMatchObject({ code: 20007 })
})
