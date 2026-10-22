import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import CardRenderer from './CardRenderer.vue'
import { cardRegistry, resolveCardComponent } from './registry'
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
it.each([
  'career_recommendation',
  'job_card',
  'job_match',
  'resume_diff',
  'skill_radar',
  'roadmap',
  'interview_turn_score',
  'interview_report',
  'campus_calendar',
])('%s 注册实际组件', async (type) => {
  const load = cardRegistry[type as keyof typeof cardRegistry]
  expect(load).toBeTypeOf('function')
  expect(await load!()).toBeDefined()
})
