import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, authenticatedHeaders } from './client'

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())
describe('访客身份', () => {
  it('首次并行请求只建立一次身份，后续刷新使用保存的凭据', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ data: { accessToken: 'private-token' } })))
    vi.stubGlobal('fetch', fetch)
    const [a, b] = await Promise.all([authenticatedHeaders(), authenticatedHeaders()])
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(a.Authorization).toBe('Bearer private-token')
    expect(b.Authorization).toBe(a.Authorization)
    expect((await authenticatedHeaders()).Authorization).toBe(a.Authorization)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('401 不静默替换身份，也不重发业务请求', async () => {
    localStorage.setItem('offerpilot-guest-v1', 'expired-token')
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 20002, message: '身份失效' }), { status: 401 }),
      )
    vi.stubGlobal('fetch', fetch)
    await expect(apiRequest('/conversations')).rejects.toThrow('身份失效')
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('offerpilot-guest-v1')).toBe('expired-token')
  })
})
