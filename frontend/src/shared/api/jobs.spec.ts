import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { matchJob, searchJobs } from './jobs'
import { officialJobUrl } from '@/shared/utils/jobs'
beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'jobs-owner'))
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('search passes filters, cancellation and owner to the backend, never directly to MCP', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { items: [] } })))
  vi.stubGlobal('fetch', fetch)
  const controller = new AbortController()
  const signal = controller.signal
  await searchJobs(
    { query: 'Java & SQL', site: 'bytedance', nature: 'campus', location: '上海' },
    signal,
  )
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
