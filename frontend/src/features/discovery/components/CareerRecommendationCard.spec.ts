import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './CareerRecommendationCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('职业推荐按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      hollandCode: 'IA',
      interestsUsed: ['设计'],
      recommendations: [
        {
          occupationId: 'o',
          name: '产品设计师',
          family: '设计',
          hollandCode: 'IA',
          similarity: 0.5,
          reasons: ['兴趣契合'],
        },
      ],
    },
  })
  expect(view.root.textContent).toContain('产品设计师')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('职业推荐空态可见', () => {
  view = mountForTest(Card, {
    payload: { hollandCode: '', interestsUsed: [], recommendations: [] },
  })
  expect(view.root.textContent).toContain('暂无匹配职业')
})
it('职业推荐损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
