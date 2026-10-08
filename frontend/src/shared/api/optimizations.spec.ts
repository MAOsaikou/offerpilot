import { afterEach, expect, it, vi } from 'vitest'
import { optimizeResume } from './optimizations'
vi.mock('./client', () => ({
  apiUrl: (path: string) => path,
  authenticatedHeaders: async () => ({}),
  toApiError: async () => new Error('接口失败'),
  fetchWithIdentity: (...args: Parameters<typeof fetch>) => fetch(...args),
  assertResponseIdentity: vi.fn(),
}))
afterEach(() => vi.unstubAllGlobals())
function response(events: object[]) {
  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(
          new TextEncoder().encode(events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join('')),
        )
        controller.close()
      },
    }),
  )
}
it('绑定准确版本与JD，持久化完成事件后才交付建议', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    response([
      { type: 'run.started', reportId: 'o1' },
      { type: 'ui.card', cardType: 'resume_diff', payload: { reportId: 'o1' } },
      { type: 'run.completed', status: 'COMPLETED' },
    ]),
  )
  vi.stubGlobal('fetch', fetcher)
  const started = vi.fn()
  expect(
    (
      await optimizeResume(
        'r',
        'v2',
        'request-id',
        '接口开发',
        new AbortController().signal,
        started,
      )
    ).reportId,
  ).toBe('o1')
  expect(started).toHaveBeenCalledWith('o1')
  expect(fetcher.mock.calls[0]?.[0]).toBe('/resumes/r/versions/v2/optimize')
  expect(JSON.parse(fetcher.mock.calls[0]?.[1].body)).toEqual({
    requestId: 'request-id',
    jobDescription: '接口开发',
  })
})
it('收到卡片后断流仍失败，服务端失败不自动重试', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      response([{ type: 'ui.card', cardType: 'resume_diff', payload: { reportId: 'o1' } }]),
    )
    .mockResolvedValueOnce(response([{ type: 'error', message: '新增数字不允许' }]))
  vi.stubGlobal('fetch', fetcher)
  await expect(
    optimizeResume('r', 'v', 'id', 'JD', new AbortController().signal, vi.fn()),
  ).rejects.toThrow('连接中断')
  await expect(
    optimizeResume('r', 'v', 'id', 'JD', new AbortController().signal, vi.fn()),
  ).rejects.toThrow('新增数字')
  expect(fetcher).toHaveBeenCalledTimes(2)
})
