import { expect, it } from 'vitest'
import { alignCurrent, levelFor } from './skillEvidenceAlign'
import type { SkillGapReport } from '@/shared/api/skill-gap'

const report: SkillGapReport = {
  targetType: 'OCCUPATION',
  targetId: 'occ',
  targetTitle: '后端',
  resumeVersionId: 'v1',
  notice: '',
  skills: [
    {
      name: 'Java / Go / Python 等后端语言',
      level: 'PARTIAL',
      sources: [],
    },
  ],
}

it('raises a zero card to the career grade without lowering a stronger listing', () => {
  expect(alignCurrent(0, 'PARTIAL')).toBe(1)
  expect(alignCurrent(0, 'POSSESSED')).toBe(5)
  expect(alignCurrent(3, 'MISSING')).toBe(3)
  expect(alignCurrent(3, 'PARTIAL')).toBe(3)
  expect(levelFor(report, 'Java / Go / Python 等后端语言')).toBe('PARTIAL')
})
