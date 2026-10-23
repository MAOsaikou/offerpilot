import { afterEach, expect, it } from 'vitest'
import { setLanguage } from '@/shared/utils/language'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './InterviewReportCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => {
  view?.unmount()
  setLanguage('zh-CN')
})
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
  expect(view.root.querySelector('[aria-label="端侧表现分析"]')).toBeNull()
})
it('面试报告损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})

it('renders only supplied browser metrics and supports English labels', () => {
  setLanguage('en-US')
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
      presence: {
        eyeContactRatio: 0.8,
        smileRate: 0.2,
        headStability: 0.9,
        postureStability: 0.7,
        note: 'Browser evidence only',
      },
    },
  })
  expect(view.root.textContent).toContain('Video is never uploaded')
  expect(view.root.textContent).toContain('Browser evidence only')
  expect(
    view.root.querySelector('progress[aria-label="Eye contact estimate"]')?.getAttribute('value'),
  ).toBe('0.8')
  expect(
    view.root.querySelector('progress[aria-label="Posture stability"]')?.getAttribute('value'),
  ).toBe('0.7')
})
