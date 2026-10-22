import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './InterviewTurnScoreCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('单题面试评分按真实载荷展示', () => {
  view = mountForTest(Card, { payload: { turnIndex: 0, score: null, brief: '尚未评分' } })
  expect(view.root.textContent).toContain('未评分')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('单题面试评分损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
