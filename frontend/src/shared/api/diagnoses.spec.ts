import { afterEach, expect, it, vi } from 'vitest'
import { analyzeResume } from './diagnoses'
vi.mock('./client', () => ({
  apiUrl: (path: string) => path,
  authenticatedHeaders: async () => ({ Authorization: 'Bearer fixture' }),
  toApiError: async () => new Error('接口失败'),
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
    { status: 200 },
  )
}
it('绑定版本发送幂等标识，只有收到完成事件才交付报告', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    response([
      { type: 'run.started', reportId: 'diag1' },
      {
        type: 'ui.card',
        cardType: 'resume_diagnosis',
        payload: { schemaVersion: 2, reportId: 'diag1' },
      },
      { type: 'run.completed', status: 'COMPLETED' },
    ]),
  )
  vi.stubGlobal('fetch', fetcher)
  const started = vi.fn()
  expect(
    (await analyzeResume('rs1', 'v2', 'request-id', new AbortController().signal, started))
      .reportId,
  ).toBe('diag1')
  expect(started).toHaveBeenCalledWith('diag1')
  expect(fetcher.mock.calls[0]?.[0]).toBe('/resumes/rs1/versions/v2/analyze')
  expect(JSON.parse(fetcher.mock.calls[0]?.[1].body)).toEqual({ requestId: 'request-id' })
})
it('断流和服务端错误不能被当作成功报告', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        response([
          { type: 'ui.card', cardType: 'resume_diagnosis', payload: { reportId: 'diag1' } },
        ]),
      )
      .mockResolvedValueOnce(response([{ type: 'error', message: '引文无效' }])),
  )
  await expect(
    analyzeResume('r', 'v', 'id', new AbortController().signal, vi.fn()),
  ).rejects.toThrow('连接中断')
  await expect(
    analyzeResume('r', 'v', 'id', new AbortController().signal, vi.fn()),
  ).rejects.toThrow('引文无效')
})
