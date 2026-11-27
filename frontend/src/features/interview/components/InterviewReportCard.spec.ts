import { nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { setLanguage } from '@/shared/utils/language'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './InterviewReportCard.vue'

const chart = vi.hoisted(() => ({ create: vi.fn(), destroy: vi.fn() }))
vi.mock('chart.js', () => ({
  Chart: class {
    static register = vi.fn()
    destroy = chart.destroy
    constructor(...args: unknown[]) {
      chart.create(...args)
    }
  },
  RadarController: {},
  RadialLinearScale: {},
  PointElement: {},
  LineElement: {},
  Filler: {},
}))
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
  view.unmount()
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      overall: 78,
      note: '已评分 3/3；规则文本评分。；模型总结',
      dimensions: { professional: 76, expression: 81, logic: 80, jobFit: 76 },
      turns: [],
      strengths: [],
      weaknesses: [],
      advice: [],
      recommendedLearning: [],
    },
  })
  expect(view.root.textContent).toContain('已评分 3/3；规则文本评分。')
  expect(view.root.textContent).not.toContain('模型总结')
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
  expect(view.root.querySelector('canvas')).toBeNull()
  expect(view.root.querySelector('caption')?.textContent).toContain('评分雷达数值表')
  expect(view.root.querySelector('[aria-label="端侧表现分析"]')).toBeNull()
})

it('embeds the radar alongside progress scores and preserves per-question review', async () => {
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      overall: 75,
      dimensions: { professional: 80, expression: null, logic: 70, jobFit: 75 },
      turns: [
        {
          question: 'Describe your contribution',
          answerSummary: 'I organized the activity',
          score: 75,
          source: 'model',
          feedback: 'Add evidence of results',
          improvedAnswer: 'I organized the activity. [Add real results]',
          betterAnswerOutline: [],
        },
      ],
      strengths: ['Clear contribution'],
      weaknesses: ['Results need evidence'],
      advice: ['Practice with real evidence'],
    },
  })
  await nextTick()
  expect(view.root.querySelector('canvas[role="img"]')).not.toBeNull()
  expect(chart.create.mock.calls.at(-1)![1].data.datasets[0].data).toEqual([80, null, 70, 75])
  expect(view.root.querySelector('table')?.textContent).toContain('未评分')
  expect(view.root.querySelectorAll('progress')).toHaveLength(3)
  expect(view.root.querySelector('progress[aria-label="专业"]')?.getAttribute('value')).toBe('80')
  expect(view.root.textContent).toContain('Add evidence of results')
  expect(view.root.querySelector('details')?.textContent).toContain('[Add real results]')
  expect(view.root.textContent).toContain('Practice with real evidence')
})
it('organizes question feedback, evidence and next steps with working question navigation', async () => {
  const dimension = { score: 68, rationale: '行动清楚', evidence: '我负责活动复盘' }
  const turn = (index: number) => ({
    question: `第${index + 1}题：如何复盘？`,
    answerSummary: '我负责活动复盘',
    score: index === 0 ? 68 : null,
    star: { situation: false, task: true, action: true, result: false },
    feedback: index === 0 ? '请补充验证' : '模型不可用',
    betterAnswerOutline: [],
    source: index === 0 ? 'model' : 'unavailable',
    evaluation:
      index === 0
        ? {
            source: 'model' as const,
            score: 68,
            rationale: '请补充验证',
            improvedAnswer: '结果：【补充真实结果】',
            dimensions: {
              professional: dimension,
              expression: dimension,
              logic: dimension,
              jobFit: dimension,
            },
          }
        : null,
  })
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      overall: 68,
      dimensions: { professional: 68, expression: 68, logic: 68, jobFit: 68 },
      turns: [turn(0), turn(1)],
      strengths: ['行动明确'],
      weaknesses: ['效果验证不足'],
      advice: ['补充真实结果'],
      recommendedLearning: [{ topic: '效果验证', reason: '缺少结果', estimatedHours: 1 }],
    },
  })
  await nextTick()
  expect(view.root.querySelector('.review-facts')?.textContent).toContain('1 / 2')
  expect(view.root.querySelectorAll('.turn-review')).toHaveLength(2)
  expect(view.root.querySelector('.turn-review[open]')?.textContent).toContain('请补充验证')
  expect(view.root.textContent).toContain('来自你回答的依据')
  expect(view.root.textContent).toContain('【补充真实结果】')
  expect(view.root.textContent).toContain('约 1 小时')
  const navigation = view.root.querySelectorAll<HTMLButtonElement>('.question-jump button')
  expect(navigation).toHaveLength(2)
  expect(navigation[0]?.getAttribute('aria-current')).toBe('true')
  navigation[1]?.click()
  await nextTick()
  const turns = view.root.querySelectorAll<HTMLDetailsElement>('.turn-review')
  expect(turns[1]?.open).toBe(true)
  expect(navigation[1]?.getAttribute('aria-current')).toBe('true')
  expect(turns[1]?.textContent).toContain('暂无法评分')
  expect(turns[1]?.textContent).toContain('模型不可用')
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
