import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import CareerRecommendationCard from './CareerRecommendationCard.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import type { CareerRecommendationPayload } from '@/shared/types/cards'

const payload: CareerRecommendationPayload = {
  hollandCode: 'IRC',
  interestsUsed: ['技术研发', '数据分析'],
  recommendations: [
    {
      occupationId: 'occ1',
      name: '软件工程师',
      family: '技术',
      hollandCode: 'IRC',
      similarity: 1,
      reasons: ['共同兴趣 IRC', '位置加权相似度 100%'],
    },
    {
      occupationId: 'occ2',
      name: '数据分析师',
      family: '数据',
      hollandCode: 'ICR',
      similarity: 0.75,
      reasons: ['共同兴趣 IRC', '位置加权相似度 75%'],
    },
  ],
}
let app: App | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})

function mount(props = {}, renderer = false) {
  const adopt = vi.fn()
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp({
    render: () =>
      renderer
        ? h(CardRenderer, {
            cardType: 'career_recommendation',
            payload,
            interactive: true,
            onAdoptTarget: adopt,
            ...props,
          })
        : h(CareerRecommendationCard, {
            payload,
            interactive: true,
            onAdoptTarget: adopt,
            ...props,
          }),
  })
  app.mount(root)
  return { root, adopt }
}

describe('CareerRecommendationCard', () => {
  it('renders ranked names, reasons and selection for the chat approval flow', () => {
    const { root, adopt } = mount()
    expect(root.textContent).toContain('技术研发、数据分析')
    expect(root.textContent).toContain('1. 软件工程师')
    expect(root.textContent).toContain('2. 数据分析师')
    expect(root.textContent).toContain('共同兴趣 IRC')
    expect(root.textContent).toContain('仍需批准')
    root.querySelectorAll('button')[1]!.click()
    expect(adopt).toHaveBeenCalledExactlyOnceWith({ occupationId: 'occ2', name: '数据分析师' })
  })

  it('forwards the registered asynchronous card selection through CardRenderer', async () => {
    const { root, adopt } = mount({}, true)
    await vi.waitFor(() => expect(root.querySelectorAll('button')).toHaveLength(2))
    await nextTick()
    root.querySelector('button')!.click()
    expect(adopt).toHaveBeenCalledExactlyOnceWith({ occupationId: 'occ1', name: '软件工程师' })
  })

  it('blocks selections while chat is busy and hides actions outside interactive chat', () => {
    const { root, adopt } = mount({ disabled: true })
    expect(root.querySelector('button')!.disabled).toBe(true)
    root.querySelector('button')!.click()
    expect(adopt).not.toHaveBeenCalled()
  })

  it('renders an empty result and readonly cards without actions', () => {
    const { root } = mount({ interactive: false, payload: { ...payload, recommendations: [] } })
    expect(root.textContent).toContain('暂无匹配职业')
    expect(root.querySelector('button')).toBeNull()
  })
})
