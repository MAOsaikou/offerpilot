import { afterEach, expect, it, vi } from 'vitest'
import { listBuiltinEntries, getBuiltinOccupation } from './builtin-knowledge'
import { apiRequest } from './client'
vi.mock('./client', () => ({ apiRequest: vi.fn() }))
afterEach(() => vi.clearAllMocks())
it('encodes compound filters and ids through shared authenticated client', async () => {
  await listBuiltinEntries({
    kind: 'QUESTION',
    q: '核验 & 交接',
    role: '护士',
    questionType: 'CASE',
    difficulty: 'HARD',
    page: 2,
    size: 20,
  })
  const url = vi.mocked(apiRequest).mock.calls[0]![0]
  const params = new URLSearchParams(url.split('?')[1])
  expect(params.get('q')).toBe('核验 & 交接')
  expect(params.get('role')).toBe('护士')
  expect(params.get('page')).toBe('2')
  await getBuiltinOccupation('id/unsafe?')
  expect(apiRequest).toHaveBeenLastCalledWith('/knowledge/builtin/occupations/id%2Funsafe%3F', {
    signal: undefined,
  })
})
