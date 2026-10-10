import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { matchJob } from './jobs'
import { officialJobUrl } from '@/shared/utils/jobs'
beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'jobs-owner'))
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('external job links reject script and credential-bearing URLs', () => {
  expect(officialJobUrl('javascript:alert(1)')).toBeUndefined()
  expect(officialJobUrl('https://user:password@example.com')).toBeUndefined()
  expect(officialJobUrl('https://jobs.example.test/1')).toBe('https://jobs.example.test/1')
})

it.each(['FLASH', 'PRO'] as const)(
  'matching uses the %s request budget and sends its tier',
  async (tier) => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {} })))
    vi.stubGlobal('fetch', fetch)
    try {
      await matchJob('job_1', 'rs1', 'v2', 'request-123456789', tier)
      expect(timeout).toHaveBeenCalledWith(tier === 'PRO' ? 210_000 : 90_000)
      expect(JSON.parse(fetch.mock.calls[0]![1].body)).toMatchObject({
        modelTier: tier,
        resumeVersionId: 'v2',
        requestId: 'request-123456789',
      })
    } finally {
      timeout.mockRestore()
    }
  },
)
it('explains a non-JSON gateway timeout without retrying the POST', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('<html>504</html>', { status: 504 }))
  vi.stubGlobal('fetch', fetch)
  await expect(matchJob('job_1', 'rs1', 'v2', 'request-123456789', 'PRO')).rejects.toThrow(
    '网关等待匹配响应超时',
  )
  expect(fetch).toHaveBeenCalledOnce()
})
it('explains the client deadline and preserves server quota errors', async () => {
  const fetch = vi
    .fn()
    .mockRejectedValueOnce(new DOMException('timeout', 'TimeoutError'))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 40012, message: '模型请求额度已用完' }), { status: 429 }),
    )
  vi.stubGlobal('fetch', fetch)
  await expect(matchJob('job_1', 'rs1', 'v2', 'request-123456789', 'PRO')).rejects.toThrow(
    '请先刷新匹配记录',
  )
  await expect(matchJob('job_1', 'rs1', 'v2', 'request-123456789', 'PRO')).rejects.toMatchObject({
    code: 40012,
    status: 429,
  })
  expect(fetch).toHaveBeenCalledTimes(2)
})
