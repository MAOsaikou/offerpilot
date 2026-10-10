import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { matchJob, searchJobs } from './jobs'
import { officialJobUrl } from '@/shared/utils/jobs'
beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'jobs-owner'))
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('search preserves snapshot metadata and sends filters, cancellation and owner to the backend', async () => {
  const meta = {
    source: 'LOCAL_SNAPSHOT',
    snapshotDate: '2026-10-10',
    fallbackReason: 'LIVE_UNAVAILABLE',
  }
  const fetch = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ data: { items: [], meta } })))
  vi.stubGlobal('fetch', fetch)
  const controller = new AbortController()
  const signal = controller.signal
  const result = await searchJobs(
    { query: 'Java & SQL', site: 'bytedance', nature: 'campus', location: '上海' },
    signal,
  )
  expect(result.meta).toEqual(meta)
  const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit]
  expect(url).toContain('/jobs?')
  expect(new URL(url, 'http://localhost').searchParams.get('query')).toBe('Java & SQL')
  expect(init.signal?.aborted).toBe(false)
  controller.abort()
  expect(init.signal?.aborted).toBe(true)
  expect((init.headers as Record<string, string>).Authorization).toBe('Bearer jobs-owner')
})
it('matching sends the selected saved version and does not silently retry failures', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ message: '原文依据校验失败' }), { status: 422 }),
    )
  vi.stubGlobal('fetch', fetch)
  await expect(matchJob('job_1', 'rs1', 'v2', 'request-123456789')).rejects.toThrow('原文依据')
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(JSON.parse(fetch.mock.calls[0]![1].body)).toMatchObject({
    resumeVersionId: 'v2',
    requestId: 'request-123456789',
  })
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
      expect(JSON.parse(fetch.mock.calls[0]![1].body).modelTier).toBe(tier)
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
