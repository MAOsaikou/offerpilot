import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import CardRenderer from './CardRenderer.vue'
import { resolveCardComponent } from './registry'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it.each(['unknown', '__proto__', 'constructor'])('未知类型 %s 安全回退为文本', (type) => {
  view = mountForTest(CardRenderer, {
    cardType: type,
    payload: { text: '<script>alert(1)</script>' },
  })
  expect(view.root.textContent).toContain('暂不支持')
  expect(view.root.textContent).toContain('<script>')
  expect(view.root.querySelector('script')).toBeNull()
  expect(resolveCardComponent(type)).toBeNull()
})
