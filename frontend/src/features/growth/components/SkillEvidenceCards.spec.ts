import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getSkillGap } from '@/shared/api/skill-gap'
import { mountForTest } from '@/shared/utils/mountForTest'
import { resetLiveSkillLevels } from '../skillEvidenceAlign'
import Radar from './SkillRadarCard.vue'
import Gap from './SkillGapCard.vue'

vi.mock('@/shared/api/skill-gap', () => ({
  getSkillGap: vi.fn(),
}))

const views: Array<() => void> = []
beforeEach(() => {
  resetLiveSkillLevels()
  vi.mocked(getSkillGap)
    .mockReset()
    .mockResolvedValue({
      targetType: 'OCCUPATION',
      targetId: 'occ',
      targetTitle: '后端开发工程师',
      resumeVersionId: 'v1',
      notice: '',
      skills: [{ name: 'Java / Go / Python 等后端语言', level: 'PARTIAL', sources: [] }],
    })
})
afterEach(() => {
  views.splice(0).forEach((cleanup) => cleanup())
})

it('holds the career grade instead of painting a stale missing score', async () => {
  let resolveGap: (report: Awaited<ReturnType<typeof getSkillGap>>) => void = () => {}
  vi.mocked(getSkillGap).mockReturnValue(
    new Promise((resolve) => {
      resolveGap = resolve
    }),
  )
  const radar = mountForTest(Radar, {
    payload: {
      axes: [
        { name: 'Java / Go / Python 等后端语言', current: 0, required: 5 },
        { name: 'Redis', current: 0, required: 4 },
      ],
    },
  })
  views.push(radar.unmount)
  expect(radar.root.textContent).toContain('正在对照当前职业分析')
  expect(radar.root.textContent).not.toContain('缺失')
  expect(radar.root.textContent).not.toContain('证据 0')
  resolveGap({
    targetType: 'OCCUPATION',
    targetId: 'occ',
    targetTitle: '后端开发工程师',
    resumeVersionId: 'v1',
    notice: '',
    skills: [{ name: 'Java / Go / Python 等后端语言', level: 'PARTIAL', sources: [] }],
  })
  await vi.waitFor(() =>
    expect(radar.root.textContent).toContain(
      'Java / Go / Python 等后端语言：部分 · 证据 1 / 要求 5',
    ),
  )
  expect(radar.root.textContent).toContain('Redis：缺失 · 证据 0 / 要求 4')
  expect(radar.root.textContent).not.toContain('Java / Go / Python 等后端语言：缺失')

  const gap = mountForTest(Gap, {
    payload: {
      id: 'gaps',
      target: { type: 'OCCUPATION', id: 'occ', title: '后端' },
      summary: '差距',
      citations: [],
      skills: [
        {
          id: 'java',
          name: 'Java / Go / Python 等后端语言',
          required: 5,
          current: 0,
          gap: 5,
          evidence: [],
          learnFirst: true,
        },
      ],
    },
  })
  views.push(gap.unmount)
  await vi.waitFor(() => expect(gap.root.textContent).toContain('部分'))
  expect(gap.root.textContent).toContain('1 / 5')
  expect(gap.root.textContent).not.toContain('缺失')
})
