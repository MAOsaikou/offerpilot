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

vi.mock('./generationTasks', () => ({ runGeneration: vi.fn() }))
it('matching hands the selected model, version and idempotency key to durable background work', async () => {
  const { runGeneration } = await import('./generationTasks')
  vi.mocked(runGeneration).mockResolvedValue({ id: 'persisted-match' })
  expect(await matchJob('job_1', 'rs1', 'v2', 'request-123456789', 'PRO')).toEqual({
    id: 'persisted-match',
  })
  expect(runGeneration).toHaveBeenCalledWith(
    'JOB_MATCH',
    'job_1',
    { resumeId: 'rs1', versionId: 'v2', modelTier: 'PRO' },
    'request-123456789',
  )
})

it('uploads private screenshot multipart and forwards ordered file IDs to V41', async () => {
  const { uploadJobScreenshot, parseJobImport, readJobScreenshot, validateJobScreenshot } =
    await import('./jobs')
  const file = new File(['image'], 'page.webp', { type: 'image/webp' })
  expect(validateJobScreenshot(file)).toBeUndefined()
  expect(validateJobScreenshot(new File([], 'empty.png'))).toContain('为空')
  expect(validateJobScreenshot(new File(['x'], 'page.svg'))).toContain('PNG')
  expect(
    validateJobScreenshot(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.png')),
  ).toContain('5MB')
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: { fileId: 'file_page' } })))
    .mockResolvedValueOnce(new Response('image', { headers: { 'Content-Type': 'image/webp' } }))
  vi.stubGlobal('fetch', fetcher)
  expect(await uploadJobScreenshot(file)).toEqual({ fileId: 'file_page' })
  expect(fetcher.mock.calls[0]?.[0]).toContain('/jobs/import/screenshots')
  const init = fetcher.mock.calls[0]?.[1] as RequestInit
  expect(init.body).toBeInstanceOf(FormData)
  expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jobs-owner')
  expect(new Headers(init.headers).get('Content-Type')).toBeNull()
  await readJobScreenshot('file_page')
  expect(fetcher.mock.calls[1]?.[0]).toContain('/jobs/import/screenshots/file_page')
  const { runGeneration } = await import('./generationTasks')
  await parseJobImport({ fileIds: ['file_2', 'file_1'] })
  expect(runGeneration).toHaveBeenCalledWith('JOB_IMPORT', 'current', {
    fileIds: ['file_2', 'file_1'],
  })
})
