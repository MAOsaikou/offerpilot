import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ResumeModelSettings from './ResumeModelSettings.vue'
import {
  getResumeModelSettings,
  saveResumeModelSettings,
  type ResumeModelSettings as Settings,
} from '@/shared/api/resume-model-settings'

vi.mock('@/shared/api/resume-model-settings', () => ({
  getResumeModelSettings: vi.fn(),
  saveResumeModelSettings: vi.fn(),
}))
let app: App | undefined
let host: HTMLDivElement
const fixture = (): Settings => ({
  models: [
    {
      id: 'CHEAP',
      label: '常用',
      provider: 'deepseek',
      model: 'model-a',
      configured: true,
      supportsVision: true,
    },
    {
      id: 'FLASH',
      label: '快捷',
      provider: 'custom',
      model: 'model-b',
      configured: true,
      supportsVision: true,
    },
    {
      id: 'PRO',
      label: '高质量',
      provider: 'custom',
      model: 'text-only',
      configured: true,
      supportsVision: false,
    },
    {
      id: 'VISION_ALT',
      label: '视觉',
      provider: 'bailian',
      model: 'unconfigured',
      configured: false,
      supportsVision: true,
    },
  ],
  selection: { textTier: 'CHEAP', visionTier: 'CHEAP' },
})
async function settle() {
  for (let i = 0; i < 5; i++) await nextTick()
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(ResumeModelSettings)
  app.mount(host)
  await settle()
}
function saveButton() {
  return [...host.querySelectorAll('button')].find((button) =>
    /保存/.test(button.textContent ?? ''),
  )!
}
async function select(testId: string, value: string) {
  const element = host.querySelector<HTMLSelectElement>(`[data-testid="${testId}"]`)!
  element.value = value
  element.dispatchEvent(new Event('change'))
  await settle()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getResumeModelSettings).mockResolvedValue(fixture())
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

it('共用模型时一并保存文字和视觉选择，未配置模型不可选', async () => {
  vi.mocked(saveResumeModelSettings).mockImplementation(async (selection) => ({
    ...fixture(),
    selection,
  }))
  await mount()
  expect(
    host.querySelector('select[data-testid="text-model"] option[value="VISION_ALT"]'),
  ).toBeNull()
  expect(saveButton().disabled).toBe(true)
  await select('text-model', 'FLASH')
  saveButton().click()
  await settle()
  expect(saveResumeModelSettings).toHaveBeenCalledWith({ textTier: 'FLASH', visionTier: 'FLASH' })
  expect(host.textContent).toContain('已保存')
  expect(saveButton().disabled).toBe(true)
})
it('文字模型不支持图片时解除共用，并保留原视觉选择', async () => {
  await mount()
  await select('text-model', 'PRO')
  expect(host.querySelector<HTMLInputElement>('input[type="checkbox"]')?.checked).toBe(false)
  expect(host.querySelector<HTMLSelectElement>('[data-testid="vision-model"]')?.value).toBe('CHEAP')
  expect(
    host.querySelector<HTMLOptionElement>('[data-testid="vision-model"] option[value="PRO"]')
      ?.disabled,
  ).toBe(true)
})
it('保存失败保留选择并允许重试，保存中不能重复提交', async () => {
  let rejectSave: (error: Error) => void = () => {}
  vi.mocked(saveResumeModelSettings).mockReturnValue(
    new Promise((_, reject) => {
      rejectSave = reject
    }),
  )
  await mount()
  await select('text-model', 'FLASH')
  saveButton().click()
  await settle()
  expect(saveButton().disabled).toBe(true)
  expect(host.querySelector('fieldset')?.disabled).toBe(true)
  rejectSave(new Error('连接已断开'))
  await settle()
  expect(host.textContent).toContain('连接已断开')
  expect(host.querySelector<HTMLSelectElement>('[data-testid="text-model"]')?.value).toBe('FLASH')
  expect(saveButton().disabled).toBe(false)
})
it('读取失败显示重试，模型被移除时要求重新选择', async () => {
  vi.mocked(getResumeModelSettings).mockRejectedValueOnce(new Error('服务不可用'))
  await mount()
  expect(host.textContent).toContain('服务不可用')
  vi.mocked(getResumeModelSettings).mockResolvedValue({
    ...fixture(),
    selection: { textTier: 'removed', visionTier: 'CHEAP' },
  })
  host.querySelector('button')?.click()
  await settle()
  expect(host.textContent).toContain('原模型已移除')
  expect(saveButton().disabled).toBe(true)
})
