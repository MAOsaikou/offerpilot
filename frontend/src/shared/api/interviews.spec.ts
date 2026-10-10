import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  createInterview,
  getInterview,
  listInterviews,
  saveInterviewAnswer,
  updateInterview,
} from './interviews'
import { ApiError } from './client'

beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'interview-owner'))
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

it('uses authenticated CRUD, encoded ids, empty drafts and only provided progress fields', async () => {
  const fetcher = vi
    .fn()
    .mockImplementation(async () => new Response(JSON.stringify({ data: { id: 'iv_1' } })))
  vi.stubGlobal('fetch', fetcher)
  await listInterviews(new AbortController().signal)
  expect(fetcher.mock.calls[0]![0]).toMatch(/\/interviews$/)
  expect(fetcher.mock.calls[0]![1].headers.Authorization).toBe('Bearer interview-owner')
  expect(fetcher.mock.calls[0]![1].signal).toBeInstanceOf(AbortSignal)
  expect(await createInterview({ role: '工程师', kind: '职业动机' })).toEqual({ id: 'iv_1' })
  expect(fetcher.mock.calls[1]![1]).toMatchObject({
    method: 'POST',
    body: JSON.stringify({ role: '工程师', kind: '职业动机' }),
  })
  await getInterview('iv/1')
  expect(fetcher.mock.calls[2]![0]).toMatch(/\/interviews\/iv%2F1$/)
  await saveInterviewAnswer('iv/1', 0, '')
  expect(fetcher.mock.calls[3]![0]).toMatch(/\/interviews\/iv%2F1\/answers\/0$/)
  expect(fetcher.mock.calls[3]![1]).toMatchObject({ method: 'PUT', body: '{"answerText":""}' })
  await updateInterview('iv/1', { questionIndex: 4 })
  expect(fetcher.mock.calls[4]![1]).toMatchObject({ method: 'PATCH', body: '{"questionIndex":4}' })
  await updateInterview('iv/1', { completed: true })
  expect(fetcher.mock.calls[5]![1].body).toBe('{"completed":true}')
})
it('preserves 404 and never retries failed writes', async () => {
  const fetcher = vi
    .fn()
    .mockImplementation(
      async () =>
        new Response(JSON.stringify({ code: 30701, message: '练习不存在' }), { status: 404 }),
    )
  vi.stubGlobal('fetch', fetcher)
  await expect(getInterview('missing')).rejects.toMatchObject({ code: 30701, status: 404 })
  await expect(saveInterviewAnswer('missing', 0, '草稿')).rejects.toBeInstanceOf(ApiError)
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('forwards cancellation', async () => {
  const controller = new AbortController()
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(async (_url, init: RequestInit) => {
      init.signal?.throwIfAborted()
      return new Response(JSON.stringify({ data: [] }))
    }),
  )
  controller.abort()
  await expect(listInterviews(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
})
