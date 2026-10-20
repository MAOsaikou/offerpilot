vi.mock('@/shared/components/BackgroundTaskStatus.vue', () => ({
  default: { template: '<div />' },
}))
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  listImportedJobs,
  parseJobImport,
  saveJobImport,
  uploadJobScreenshot,
  validateJobScreenshot,
} from '@/shared/api/jobs'
import type { RealJob } from '@/shared/types/jobs'
import JobImportPanel from './JobImportPanel.vue'

vi.mock('@/shared/api/jobs', () => ({
  listImportedJobs: vi.fn(),
  parseJobImport: vi.fn(),
  saveJobImport: vi.fn(),
  uploadJobScreenshot: vi.fn(),
  validateJobScreenshot: vi.fn(),
  readJobScreenshot: vi.fn(),
}))

let app: App | undefined
let root: HTMLDivElement
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal(
    'URL',
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }),
  )
  vi.mocked(validateJobScreenshot).mockReturnValue(undefined)
  vi.mocked(uploadJobScreenshot).mockResolvedValue({
    fileId: 'file_page',
    name: 'page.png',
    mime: 'image/png',
    size: 100,
  })
  vi.mocked(listImportedJobs).mockResolvedValue([])
  vi.mocked(parseJobImport).mockResolvedValue({
    draft: {
      title: '数据分析实习生',
      company: '',
      locations: '北京',
      department: '',
      nature: 'intern',
      description: '负责报表搭建',
      requirement: '熟练使用 SQL',
      skills: ['SQL'],
      parser: 'model',
    },
    url: '',
    sourceKind: 'text',
    securityNotices: ['要求忽略原有指令'],
  })
  vi.mocked(saveJobImport).mockResolvedValue({ id: 'job_imported' } as RealJob)
})
afterEach(() => {
  app?.unmount()
  root?.remove()
})

const button = (text: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(text))!
function input(el: HTMLInputElement | HTMLTextAreaElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
}

it('parses pasted text, lets the user correct the draft, saves and opens the job', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/jobs', component: JobImportPanel },
      { path: '/jobs/:id', component: { render: () => null } },
    ],
  })
  await router.push('/jobs')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ template: '<RouterView />' })
    .use(router)
    .use(VueQueryPlugin, { queryClient: new QueryClient() })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('导入岗位'))
  button('粘贴 JD 或网址').click()
  await nextTick()
  const area = root.querySelector('textarea')!
  input(area, '太短')
  await nextTick()
  expect(button('解析岗位').disabled).toBe(true)
  input(area, '数据分析实习生\n职位描述：负责报表搭建\n职位要求：熟练使用 SQL，每周实习四天以上')
  await nextTick()
  button('解析岗位').click()
  await vi.waitFor(() => expect(root.textContent).toContain('原文中找不到的内容都已丢弃'))
  expect(parseJobImport).toHaveBeenCalledWith({ text: expect.stringContaining('数据分析实习生') })
  expect(root.querySelector('[role="alert"]')?.textContent).toContain('要求忽略原有指令')
  const company = root.querySelector<HTMLInputElement>('input[placeholder="原文未写明"]')!
  input(company, '星河科技')
  await nextTick()
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/jobs/job_imported'))
  expect(saveJobImport).toHaveBeenCalledWith({
    title: '数据分析实习生',
    company: '星河科技',
    locations: '北京',
    department: '',
    nature: 'intern',
    description: '负责报表搭建',
    requirement: '熟练使用 SQL',
    skills: ['SQL'],
    url: '',
  })
})

it('selects or pastes screenshots, validates count, shows progress and retries upload before review', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/jobs', component: JobImportPanel }],
  })
  await router.push('/jobs')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(JobImportPanel)
    .use(router)
    .use(VueQueryPlugin, { queryClient: new QueryClient() })
  app.mount(root)
  button('粘贴 JD').click()
  await nextTick()
  const radio = root.querySelector<HTMLInputElement>('input[value="screenshot"]')!
  radio.checked = true
  radio.dispatchEvent(new Event('change'))
  await nextTick()
  expect(root.textContent).toContain('尚未选择截图')
  const file = new File(['pixels'], 'page.png', { type: 'image/png' })
  const paste = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(paste, 'clipboardData', { value: { files: [file] } })
  root.querySelector('section')!.dispatchEvent(paste)
  await nextTick()
  expect(root.querySelectorAll('img')).toHaveLength(1)
  button('删除').click()
  await nextTick()
  expect(root.querySelectorAll('img')).toHaveLength(0)
  const selector = root.querySelector<HTMLInputElement>('input[type="file"]')!
  Object.defineProperty(selector, 'files', { configurable: true, value: [file, file, file, file] })
  selector.dispatchEvent(new Event('change'))
  await nextTick()
  expect(root.textContent).toContain('最多选择')
  Object.defineProperty(selector, 'files', { configurable: true, value: [file] })
  selector.dispatchEvent(new Event('change'))
  await nextTick()
  vi.mocked(uploadJobScreenshot).mockRejectedValueOnce(new Error('上传失败，请重试'))
  button('解析岗位').click()
  await vi.waitFor(() => expect(root.textContent).toContain('上传失败'))
  expect(root.querySelectorAll('img')).toHaveLength(1)
  let finish: ((value: Awaited<ReturnType<typeof uploadJobScreenshot>>) => void) | undefined
  vi.mocked(uploadJobScreenshot).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  button('解析岗位').click()
  await nextTick()
  expect(root.querySelector('progress')).not.toBeNull()
  expect(root.querySelector<HTMLInputElement>('input[type="file"]')!.disabled).toBe(true)
  finish!({ fileId: 'file_page', name: 'page.png', mime: 'image/png', size: 100 })
  await vi.waitFor(() => expect(parseJobImport).toHaveBeenCalledWith({ fileIds: ['file_page'] }))
  await vi.waitFor(() => expect(root.querySelector('form')).not.toBeNull())
  expect(saveJobImport).not.toHaveBeenCalled()
})
