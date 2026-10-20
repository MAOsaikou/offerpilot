import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import MessageUsage from './MessageUsage.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('无单价显示未配置，有日志金额才显示成本', () => {
  view = mountForTest(MessageUsage, {
    usage: {
      model: 'actual',
      promptTokens: 12,
      completionTokens: 3,
      durationMs: 150,
      costEstimate: null,
      fallbackUsed: false,
    },
  })
  expect(view.root.textContent).toContain('actual')
  expect(view.root.textContent).toContain('12 / 3')
  expect(view.root.textContent).toContain('150 ms')
  expect(view.root.textContent).toContain('未配置')
  view.unmount()
  view = mountForTest(MessageUsage, {
    usage: {
      model: 'actual',
      promptTokens: 1,
      completionTokens: 0,
      costEstimate: 0.001,
      durationMs: 100,
      fallbackUsed: false,
    },
  })
  expect(view.root.textContent).toContain('¥0.001000')
  view.unmount()
  view = mountForTest(MessageUsage, { model: 'requested-only' })
  expect(view.root.textContent).toContain('请求模型（无调用日志）')
  expect(view.root.textContent).toContain('未记录')
})
