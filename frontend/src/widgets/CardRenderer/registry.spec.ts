import { describe, expect, it } from 'vitest'
import { cardRegistry, resolveCardComponent } from './registry'

describe('cardRegistry', () => {
  it('已实现的卡片类型都注册了组件', () => {
    for (const type of [
      'interview_report',
      'interviewer_state',
      'job_match',
      'job_evidence_match',
      'resume_diff',
      'board_update',
      'career_recommendation',
    ] as const) {
      expect(cardRegistry[type]).toBeTypeOf('function')
      expect(resolveCardComponent(type)).not.toBeNull()
    }
  })

  it('未注册的类型返回 null，交给 CardFallback 兜底', () => {
    expect(resolveCardComponent('not_a_real_card')).toBeNull()
  })

  it('同一个类型返回同一个异步组件实例，避免重复挂载', () => {
    expect(resolveCardComponent('job_match')).toBe(resolveCardComponent('job_match'))
  })
})
