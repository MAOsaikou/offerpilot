import { beforeEach, expect, it, vi } from 'vitest'
import { downloadText } from './download'
import { exportInterview } from './interviewExport'

vi.mock('./download', () => ({ downloadText: vi.fn() }))
vi.mock('@/shared/api/client', () => ({
  apiUrl: (path: string) => '/api/v1' + path,
  fetchWithIdentity: vi.fn(),
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
  expect(fetchWithIdentity).toHaveBeenCalled()
  expect(downloadText).toHaveBeenCalledWith(
    '后端-面试复盘包.md',
    expect.stringContaining('面试复盘包'),
  )
})
