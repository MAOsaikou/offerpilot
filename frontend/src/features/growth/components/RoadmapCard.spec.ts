import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './RoadmapCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('路线图按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      weeks: [
        {
          index: 1,
          theme: '练习',
          items: [
            { id: 't', title: '复习基础', hours: 2, resources: [], dueAt: null, done: false },
          ],
        },
      ],
      milestones: [],
    },
  })
  expect(view.root.textContent).toContain('复习基础')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('路线图损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
