import { createApp, h, nextTick, ref, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import type { ResumeEntry } from '@/shared/types/resume'
import ResumeSectionEditor from './ResumeSectionEditor.vue'

let app: App
let root: HTMLDivElement
afterEach(() => {
  app?.unmount()
  root?.remove()
})
function mount(initial: ResumeEntry[], title = '教育经历') {
  const entries = ref(initial)
  const disabled = ref(false)
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({
    render: () =>
      h(ResumeSectionEditor, {
        title,
        fields: [{ key: 'name', label: '名称' }],
        disabled: disabled.value,
        modelValue: entries.value,
        'onUpdate:modelValue': (value) => (entries.value = value),
      }),
  })
  app.mount(root)
  return { entries, disabled }
}
function control(row: number, action: string) {
  return root
    .querySelectorAll('fieldset')
    [row]!.querySelector<HTMLButtonElement>(`[data-action="${action}"]`)!
}
function drag(row: number, type: string) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  const transfer = new Map<string, string>()
  Object.defineProperty(event, 'dataTransfer', {
    value: { setData: (key: string, value: string) => transfer.set(key, value) },
  })
  const target =
    type === 'dragstart' ? control(row, 'drag') : root.querySelectorAll('fieldset')[row]!
  target.dispatchEvent(event)
  return event
}

it('按钮排序更新模型、保留内容并将焦点跟随条目，后续编辑与删除作用于新位置', async () => {
  const { entries } = mount([{ name: 'A' }, { name: 'B' }, { name: 'C' }])
  const original = entries.value
  expect(control(0, 'up').disabled).toBe(true)
  expect(control(2, 'down').disabled).toBe(true)
  control(0, 'down').click()
  await nextTick()
  expect(entries.value.map((entry) => entry.name)).toEqual(['B', 'A', 'C'])
  expect(entries.value).not.toBe(original)
  expect(original.map((entry) => entry.name)).toEqual(['A', 'B', 'C'])
  expect(entries.value[1]).toBe(original[0])
  expect(document.activeElement).toBe(control(1, 'down'))
  control(1, 'up').click()
  await nextTick()
  expect(entries.value.map((entry) => entry.name)).toEqual(['A', 'B', 'C'])
  expect(root.querySelector('[role="status"]')?.textContent).toContain('第 2 项已移至第 1 项')
  const input = root.querySelectorAll('input')[0]!
  input.value = 'Edited A'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(entries.value[0]?.name).toBe('Edited A')
  root.querySelectorAll('fieldset')[1]!.querySelector<HTMLButtonElement>(':scope > button')!.click()
  await nextTick()
  expect(entries.value.map((entry) => entry.name)).toEqual(['Edited A', 'C'])
})

it('拖拽到目标位置后更新模型，完成或取消后拒绝无来源拖放', async () => {
  const { entries } = mount([{ name: 'A' }, { name: 'B' }, { name: 'C' }])
  expect(drag(2, 'drop').defaultPrevented).toBe(false)
  drag(0, 'dragstart')
  expect(drag(2, 'dragover').defaultPrevented).toBe(true)
  await nextTick()
  expect(root.querySelectorAll('.drop-target')).toHaveLength(1)
  drag(2, 'drop')
  await nextTick()
  expect(entries.value.map((entry) => entry.name)).toEqual(['B', 'C', 'A'])
  expect(root.querySelector('.drop-target')).toBeNull()
  expect(document.activeElement).toBe(control(2, 'drag'))
  expect(drag(0, 'drop').defaultPrevented).toBe(false)
  drag(2, 'dragstart')
  drag(0, 'drop')
  await nextTick()
  expect(entries.value.map((entry) => entry.name)).toEqual(['A', 'B', 'C'])
  drag(0, 'dragstart')
  control(0, 'drag').dispatchEvent(new Event('dragend', { bubbles: true }))
  expect(drag(2, 'drop').defaultPrevented).toBe(false)
})

it('保存禁用和源数组变化都会取消在途拖拽，禁用时按钮不能修改模型', async () => {
  const { entries, disabled } = mount([{ name: 'A' }, { name: 'B' }])
  drag(0, 'dragstart')
  entries.value = [{ name: 'Reloaded A' }, { name: 'Reloaded B' }]
  await nextTick()
  drag(1, 'drop')
  expect(entries.value.map((entry) => entry.name)).toEqual(['Reloaded A', 'Reloaded B'])
  drag(0, 'dragstart')
  disabled.value = true
  await nextTick()
  control(0, 'down').click()
  expect(drag(0, 'dragstart').defaultPrevented).toBe(true)
  drag(1, 'drop')
  expect(control(0, 'drag').draggable).toBe(false)
  expect(entries.value.map((entry) => entry.name)).toEqual(['Reloaded A', 'Reloaded B'])
  disabled.value = false
  await nextTick()
  expect(drag(1, 'drop').defaultPrevented).toBe(false)
})

it('空节可添加，单条不可排序，移除后恢复空状态', async () => {
  const { entries } = mount([])
  expect(root.textContent).toContain('尚未填写教育经历')
  root.querySelector<HTMLButtonElement>('button')!.click()
  await nextTick()
  expect(entries.value).toEqual([{ name: '' }])
  expect(control(0, 'up').disabled).toBe(true)
  expect(control(0, 'down').disabled).toBe(true)
  expect(control(0, 'drag').draggable).toBe(false)
  root.querySelector<HTMLButtonElement>('fieldset > button')!.click()
  await nextTick()
  expect(entries.value).toEqual([])
  expect(root.textContent).toContain('尚未填写教育经历')
})
