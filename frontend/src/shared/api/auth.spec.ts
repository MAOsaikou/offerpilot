import { afterEach, beforeEach, expect, it, vi } from 'vitest'

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

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
