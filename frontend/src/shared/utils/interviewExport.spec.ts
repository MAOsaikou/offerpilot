import { beforeEach, expect, it, vi } from 'vitest'
import { downloadText } from './download'
import { exportInterview } from './interviewExport'

vi.mock('./download', () => ({ downloadText: vi.fn() }))
vi.mock('@/shared/api/client', () => ({
  apiUrl: (path: string) => '/api/v1' + path,
  fetchWithIdentity: vi.fn(),
  authenticatedHeaders: vi.fn(async (headers) => ({ ...headers, Authorization: 'Bearer fixture' })),
  assertResponseIdentity: vi.fn(),
}))

import { fetchWithIdentity } from '@/shared/api/client'

beforeEach(() => {
  vi.mocked(downloadText).mockReset()
  vi.mocked(fetchWithIdentity).mockReset()
})

it('downloads server recap package when available', async () => {
  vi.mocked(fetchWithIdentity).mockResolvedValue(
    new Response('# 面试复盘包\n\nSTAR', {
      status: 200,
      headers: { 'Content-Type': 'text/markdown' },
    }),
  )
  await exportInterview('后端', [{ questionIndex: 0, questionText: 'q', answerText: 'a' }], 'iv_1')
  expect(fetchWithIdentity).toHaveBeenCalledWith('/api/v1/interviews/iv_1/recap.md', {
    headers: { Accept: 'text/markdown', Authorization: 'Bearer fixture' },
    signal: undefined,
  })
  expect(downloadText).toHaveBeenCalledWith(
    '后端-面试复盘包.md',
    expect.stringContaining('面试复盘包'),
  )
})

it('reports server export failure without silently downloading an incomplete recap; legacy stays local', async () => {
  vi.mocked(fetchWithIdentity).mockRejectedValue(new Error('offline'))
  await expect(
    exportInterview(
      '后端',
      [{ questionIndex: 0, questionText: '题目', answerText: '回答' }],
      'iv_1',
    ),
  ).rejects.toThrow('offline')
  expect(downloadText).not.toHaveBeenCalled()
  await exportInterview('后端', [{ questionIndex: 0, questionText: '题目', answerText: '回答' }])
  expect(downloadText).toHaveBeenCalledWith(
    '后端-面试复盘.md',
    expect.stringContaining('## 题目\n\n回答'),
  )
})
