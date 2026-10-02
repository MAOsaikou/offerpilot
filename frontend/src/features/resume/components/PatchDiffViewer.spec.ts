import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import PatchDiffViewer from './PatchDiffViewer.vue'
import type { ResumeDiffPayload } from '@/shared/types/cards'

const payload: ResumeDiffPayload = {
  batchId: 'b1',
  resumeVersionId: 'v1',
  stats: { added: 0, replaced: 2, removed: 0 },
  patches: [
    {
      id: 'p1',
      op: 'replace',
      path: '/summary',
      from: '参与开发',
      value: '参与接口开发',
      reason: '明确职责',
    },
    {
      id: 'p2',
      op: 'replace',
      path: '/projects/0/bullets/0',
      from: '协作测试',
      value: '协作完成接口测试',
      reason: '对齐岗位',
      needsUserConfirmation: true,
      confirmationHint: '核对贡献范围',
    },
  ],
}
let app: App, root: HTMLDivElement
afterEach(() => {
  app?.unmount()
  root?.remove()
})
function mount(interactive = true) {
  const apply = vi.fn()
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(PatchDiffViewer, { payload, interactive, onApply: apply }) })
  app.mount(root)
  return apply
}
const save = () =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('保存新版本'))!
async function select(n: number) {
  root.querySelector<HTMLButtonElement>(`[aria-label="采纳建议 ${n}"]`)!.click()
  await nextTick()
}
it('默认不采纳；只提交明确选择、编辑和事实确认的内容', async () => {
  const apply = mount()
  expect(save().disabled).toBe(true)
  await select(1)
  await select(2)
  expect(save().disabled).toBe(true)
  const input = root.querySelector<HTMLTextAreaElement>('[aria-label="编辑建议 2"]')!
  input.value = '与团队协作完成接口测试'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector<HTMLInputElement>('input[type=checkbox]')!.click()
  await nextTick()
  save().click()
  expect(apply).toHaveBeenCalledWith({
    batchId: 'b1',
    accepted: ['p1'],
    edited: { p2: { value: input.value } },
    confirmed: ['p2'],
  })
  input.value = '修改后的贡献描述'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  expect(save().disabled).toBe(true)
})
it('取消选择不提交该条；原文相同不能创建无变化版本', async () => {
  const apply = mount()
  await select(1)
  await select(2)
  await select(2)
  const input = root.querySelector<HTMLTextAreaElement>('textarea')!
  input.value = '参与开发'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  expect(save().disabled).toBe(true)
  input.value = '参与接口实现'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  save().click()
  expect(apply).toHaveBeenCalledWith({
    batchId: 'b1',
    accepted: [],
    edited: { p1: { value: input.value } },
    confirmed: [],
  })
})
it('聊天和旧卡片默认只读，没有保存入口', () => {
  mount(false)
  expect(root.querySelector('button')).toBeNull()
  expect(root.textContent).toContain('仅查看建议')
})
