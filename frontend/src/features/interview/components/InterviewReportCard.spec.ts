import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './InterviewReportCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('面试报告按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      overall: null,
      dimensions: { professional: null, expression: null, logic: null, jobFit: null },
      turns: [],
      strengths: [],
      weaknesses: [],
      advice: ['继续练习'],
      recommendedLearning: [],
    },
  })
  expect(view.root.textContent).toContain('继续练习')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('面试报告空态可见', () => {
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      overall: null,
      dimensions: { professional: null, expression: null, logic: null, jobFit: null },
      turns: [],
      strengths: [],
      weaknesses: [],
      advice: [],
      recommendedLearning: [],
    },
  })
  expect(view.root.textContent).toContain('暂无回答')
})
it('面试报告损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
