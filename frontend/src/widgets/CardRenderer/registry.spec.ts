import { describe, expect, it } from 'vitest'
import { resolveCardComponent } from './registry'

describe('cardRegistry', () => {
  it('同一个类型返回同一个异步组件实例，避免重复挂载', () => {
    expect(resolveCardComponent('job_match')).toBe(resolveCardComponent('job_match'))
  })
})
