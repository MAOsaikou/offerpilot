import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './SkillRadarCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('技能雷达按真实载荷展示', () => {
  view = mountForTest(Card, { payload: { axes: [{ name: 'Java', current: 2, required: 5 }] } })
  expect(view.root.textContent).toContain('Java：证据 2 / 要求 5')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('技能雷达空态可见', () => {
  view = mountForTest(Card, { payload: { axes: [] } })
  expect(view.root.textContent).toContain('暂无技能证据')
})
it('技能雷达损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
