import { nextTick } from 'vue'
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
const roadmap = (done = false) => ({
  weeks: [
    {
      index: 1,
      theme: '练习',
      items: [{ id: 't', title: '复习基础', hours: 2, resources: [], dueAt: null, done }],
    },
  ],
  milestones: [],
})

it('enables sync only after a task is selected and explains the approval step', async () => {
  const adopted: string[][] = []
  view = mountForTest(Card, {
    payload: roadmap(),
    interactive: true,
    onAdoptGrowth: (titles: string[]) => adopted.push(titles),
  })
  const button = [...view.root.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === '同步到成长任务',
  )!
  expect(button.disabled).toBe(true)
  expect(view.root.textContent).toContain('请先勾选已完成的任务。')
  view.root.querySelector('input')!.click()
  await nextTick()
  expect(button.disabled).toBe(false)
  expect(view.root.textContent).toContain('执行面板批准或拒绝')
  button.click()
  expect(adopted).toEqual([['复习基础']])
})

it('uses the caller reason when the chat cannot sync yet, and a different reason when every task is done', () => {
  view = mountForTest(Card, {
    payload: roadmap(),
    interactive: true,
    disabled: true,
    disabledReason: '对话还在准备，请稍候再同步。',
  })
  expect(view.root.textContent).toContain('对话还在准备，请稍候再同步。')
  expect(view.root.textContent).not.toContain('请先勾选已完成的任务。')
  view.unmount()
  view = mountForTest(Card, { payload: roadmap(true), interactive: true })
  expect(view.root.textContent).toContain('这些任务都已记为完成')
  expect(
    [...view.root.querySelectorAll('button')].find(
      (item) => item.textContent?.trim() === '同步到成长任务',
    )!.disabled,
  ).toBe(true)
})

it('路线图损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
