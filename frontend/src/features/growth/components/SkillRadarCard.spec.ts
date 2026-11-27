import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './SkillRadarCard.vue'
import { resetLiveSkillLevels } from '../skillEvidenceAlign'

vi.mock('@/shared/api/skill-gap', () => ({
  getSkillGap: vi.fn().mockRejectedValue(new Error('offline')),
}))

beforeEach(() => resetLiveSkillLevels())
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('技能雷达按真实载荷展示', async () => {
  view = mountForTest(Card, { payload: { axes: [{ name: 'Java', current: 2, required: 5 }] } })
  await vi.waitFor(() => expect(view.root.textContent).toContain('Java：部分 · 证据 2 / 要求 5'))
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('技能雷达损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
