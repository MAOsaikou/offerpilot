import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, type App } from 'vue'
import SkillGapCard from './SkillGapCard.vue'
import RoadmapCard from './RoadmapCard.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import type { SkillGapPayload, RoadmapPayload } from '@/shared/types/cards'

const gap: SkillGapPayload = {
  id: 'gap',
  target: { type: 'OCCUPATION', id: 'occ', title: '软件工程师' },
  skills: [
    {
      name: 'Java',
      required: 4,
      current: 2,
      gap: 2,
      evidence: ['档案提及：Java'],
      learnFirst: true,
    },
    { name: 'SQL', required: 4, current: 0, gap: 4, evidence: [] },
  ],
  summary: '规则参考，未写入任务',
  citations: [],
}
const roadmap: RoadmapPayload = {
  weeks: [
    {
      index: 1,
      theme: '补齐 SQL',
      items: [
        {
          id: 'a',
          title: '梳理目标岗位的核心技能',
          hours: 2,
          resources: ['岗位职责'],
          dueAt: null,
          done: false,
        },
        {
          id: 'b',
          title: '完成一次基础知识复盘',
          hours: 4,
          resources: ['官方文档'],
          dueAt: null,
          done: true,
        },
      ],
    },
  ],
  milestones: [{ title: '整理项目证据', at: '第2周末' }],
}
let app: App | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})
function mount(type: 'skill_gap' | 'roadmap', renderer = false, props = {}) {
  const adopt = vi.fn()
  const root = document.createElement('div')
  document.body.append(root)
  const payload = type === 'skill_gap' ? gap : roadmap
  app = createApp({
    render: () =>
      renderer
        ? h(CardRenderer, {
            cardType: type,
            payload,
            interactive: true,
            onAdoptGrowth: adopt,
            ...props,
          })
        : type === 'skill_gap'
          ? h(SkillGapCard, { payload: gap })
          : h(RoadmapCard, { payload: roadmap, interactive: true, onAdoptGrowth: adopt, ...props }),
  })
  app.mount(root)
  return { root, adopt }
}
describe('growth cards', () => {
  it('renders evidence, priorities, bars and honest missing evidence', () => {
    const { root } = mount('skill_gap')
    expect(root.textContent).toContain('软件工程师')
    expect(root.textContent).toContain('优先补齐')
    expect(root.textContent).toContain('档案提及：Java')
    expect(root.textContent).toContain('不代表没有能力')
    expect(root.querySelectorAll('progress')).toHaveLength(2)
  })
  it('renders weeks, resources, completion and milestones and emits only user selected completed titles', () => {
    const { root, adopt } = mount('roadmap')
    expect(root.textContent).toContain('第 1 周 · 补齐 SQL')
    expect(root.textContent).toContain('官方文档')
    expect(root.textContent).toContain('已完成')
    expect(root.textContent).toContain('第2周末')
    expect(root.querySelector('button')!.disabled).toBe(true)
    root.querySelector('input')!.click()
    return vi.waitFor(() => {
      expect(root.querySelector('button')!.disabled).toBe(false)
      root.querySelector('button')!.click()
      expect(adopt).toHaveBeenCalledExactlyOnceWith(['梳理目标岗位的核心技能'])
    })
  })
  it.each(['skill_gap', 'roadmap'] as const)(
    'loads %s through the registry without JSON fallback',
    async (type) => {
      const { root, adopt } = mount(type, true)
      await vi.waitFor(() =>
        expect(root.textContent).toContain(type === 'skill_gap' ? '技能差距' : '四周学习路线图'),
      )
      expect(root.textContent).not.toContain('"weeks"')
      if (type === 'roadmap') {
        root.querySelector('input')!.click()
        await vi.waitFor(() => expect(root.querySelector('button')!.disabled).toBe(false))
        root.querySelector('button')!.click()
        expect(adopt).toHaveBeenCalledExactlyOnceWith(['梳理目标岗位的核心技能'])
      }
    },
  )
  it('blocks edits while chat is busy', () => {
    const { root, adopt } = mount('roadmap', false, { disabled: true })
    expect(root.querySelector('input')!.disabled).toBe(true)
    expect(root.querySelector('button')!.disabled).toBe(true)
    root.querySelector('button')!.click()
    expect(adopt).not.toHaveBeenCalled()
  })
  it('hides actions in readonly history and explains empty data', () => {
    const { root } = mount('roadmap', false, {
      interactive: false,
      payload: { weeks: [], milestones: [] },
    })
    expect(root.querySelector('button')).toBeNull()
    expect(root.querySelector('input')).toBeNull()
    expect(root.textContent).toContain('暂无学习任务')
  })
})
