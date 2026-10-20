import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './MatchReport.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('匹配按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      jobId: 'j',
      resumeVersionId: 'v',
      score: 50,
      matched: [],
      gaps: [],
      advice: ['补充原文证据'],
      citations: [],
    },
  })
  expect(view.root.textContent).toContain('补充原文证据')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('匹配空态可见', () => {
  view = mountForTest(Card, {
    payload: {
      id: 'r',
      jobId: 'j',
      resumeVersionId: 'v',
      score: 0,
      matched: [],
      gaps: [],
      advice: [],
      citations: [],
    },
  })
  expect(view.root.textContent).toContain('0')
})
it('匹配损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
