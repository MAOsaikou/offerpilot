import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './CampusCalendarCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('招聘日历按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      items: [
        {
          company: '公司A',
          batch: '春招',
          positions: ['研发'],
          deadline: '2026-12-01',
          url: 'javascript:alert(1)',
        },
      ],
    },
  })
  expect(view.root.textContent).toContain('公司A')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('招聘日历空态可见', () => {
  view = mountForTest(Card, { payload: { items: [] } })
  expect(view.root.textContent).toContain('没有明确截止日期')
})
it('招聘日历损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
