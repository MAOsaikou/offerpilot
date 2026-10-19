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
