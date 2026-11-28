import { expect, it } from 'vitest'
import { tidyReportNote } from './interviewReportNote'

it('removes the unfinished model-summary suffix and keeps the scoring note', () => {
  expect(tidyReportNote('已评分 3/3；规则文本评分。；模型总结')).toBe('已评分 3/3；规则文本评分。')
  expect(tidyReportNote('Rule scoring;模型总结')).toBe('Rule scoring')
  expect(tidyReportNote('已评分 3/3')).toBe('已评分 3/3')
})
