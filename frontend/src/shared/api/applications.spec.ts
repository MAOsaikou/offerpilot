import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { favoriteJob, listApplications, updateApplication } from './applications'
beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'board-test-owner'))
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})
it('uses authenticated server endpoints and sends only changed fields', async () => {
  const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ data: [] })))
  vi.stubGlobal('fetch', fetcher)
  await listApplications()
  expect(fetcher.mock.calls[0]![0]).toMatch(/\/applications$/)
  expect(fetcher.mock.calls[0]![1].headers.Authorization).toBe('Bearer board-test-owner')
  await favoriteJob('job_real')
  expect(fetcher.mock.calls[1]![1]).toMatchObject({
    method: 'POST',
    body: JSON.stringify({ jobId: 'job_real' }),
  })
  await updateApplication('app/1', { note: '' })
  expect(fetcher.mock.calls[2]![0]).toMatch(/\/applications\/app%2F1$/)
  expect(fetcher.mock.calls[2]![1]).toMatchObject({ method: 'PATCH', body: '{"note":""}' })
})
