import { expect, it } from 'vitest'
import { optimizationContext } from './optimizationContext'
import type { ChatMessageView } from './types'
const message = (id: string, payload: unknown): ChatMessageView => ({
  id,
  role: 'assistant',
  text: '',
  cards: [{ cardId: id, cardType: 'resume_diff', payload }],
})
const first = message('m1', { resumeId: 'r1', resumeVersionId: 'v1', reportId: 'o1' })
const latest = message('m2', { resumeId: 'r1', resumeVersionId: 'v1', reportId: 'o2' })
it('后续讨论关联最新可见保存建议，并保留岗位上下文', () => {
  expect(
    optimizationContext([first, latest], { jobId: 'j1', resumeId: 'r1', resumeVersionId: 'v1' }),
  ).toEqual({ jobId: 'j1', resumeId: 'r1', resumeVersionId: 'v1', optimizationReportId: 'o2' })
  expect(optimizationContext([latest], {})).toEqual({
    resumeId: 'r1',
    resumeVersionId: 'v1',
    optimizationReportId: 'o2',
  })
})
it('不替换明确报告、不混入另一份简历或版本、不信任旧卡片缺失的标识', () => {
  const explicit = { optimizationReportId: 'selected' }
  expect(optimizationContext([latest], explicit)).toBe(explicit)
  const diagnosis = { diagnosisReportId: 'selected-diagnosis' }
  expect(optimizationContext([latest], diagnosis)).toBe(diagnosis)
  expect(optimizationContext([latest], { resumeId: 'other' })).toEqual({ resumeId: 'other' })
  expect(optimizationContext([latest], { resumeVersionId: 'other' })).toEqual({
    resumeVersionId: 'other',
  })
  expect(
    optimizationContext(
      [
        message('bad', { resumeId: '../x', resumeVersionId: 'v', reportId: 'o' }),
        message('legacy', { patches: [] }),
      ],
      {},
    ),
  ).toEqual({})
})
