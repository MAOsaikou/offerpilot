import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, type App } from 'vue'
import InterviewReportCard from './InterviewReportCard.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import type { InterviewReportPayload } from '@/shared/types/cards'

const payload: InterviewReportPayload = {
  id: 'iv_1',
  overall: 65,
  dimensions: { professional: 70, expression: 80, logic: 60, jobFit: 50 },
  turns: [
    {
      question: '分享项目',
      answerSummary: '负责接口',
      score: 65,
      star: { situation: true, task: true, action: false, result: false },
      feedback: '补充行动和结果',
      betterAnswerOutline: ['A：具体步骤', 'R：真实结果'],
    },
  ],
  strengths: ['职责明确'],
  weaknesses: ['缺少结果'],
  advice: ['补充验证方式'],
  recommendedLearning: [{ topic: '结果叙述', reason: '缺少结果线索', estimatedHours: 1 }],
}
let app: App | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})
function mount(renderer = false, value: unknown = payload) {
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp({
    render: () =>
      h(
        renderer ? CardRenderer : InterviewReportCard,
        renderer ? { cardType: 'interview_report', payload: value } : { payload: value },
      ),
  })
  app.mount(root)
  return root
}
describe('InterviewReportCard', () => {
  it('shows named dimensions, STAR flags, evidence feedback and improved outline without presence metrics', () => {
    const root = mount()
    expect(root.textContent).toContain('65 / 100')
    expect([...root.querySelectorAll('progress')].map((p) => p.getAttribute('aria-label'))).toEqual(
      ['专业', '表达', '逻辑', '岗位匹配'],
    )
    expect(root.textContent).toContain('✓ S 背景')
    expect(root.textContent).toContain('○ A 行动')
    expect(root.textContent).toContain('补充行动和结果')
    expect(root.textContent).toContain('R：真实结果')
    expect(root.textContent).toContain('端侧表现分析未启用')
    expect(root.querySelector('button')).toBeNull()
  })
  it('renders through the asynchronous registry', async () => {
    const root = mount(true)
    await vi.waitFor(() => expect(root.querySelectorAll('progress')).toHaveLength(4))
    expect(root.textContent).toContain('面试 STAR 评分卡')
  })
  it('handles unavailable payload and empty turns', () => {
    const root = mount(false, null)
    expect(root.textContent).toContain('数据不可用')
    app?.unmount()
    expect(mount(false, { ...payload, turns: [] }).textContent).toContain('暂无回答')
  })
})
