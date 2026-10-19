vi.mock('@/shared/components/BackgroundTaskStatus.vue', () => ({
  default: { template: '<div />' },
}))
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { listImportedJobs, parseJobImport, saveJobImport } from '@/shared/api/jobs'
import type { RealJob } from '@/shared/types/jobs'
import JobImportPanel from './JobImportPanel.vue'

vi.mock('@/shared/api/jobs', () => ({
  listImportedJobs: vi.fn(),
  parseJobImport: vi.fn(),
  saveJobImport: vi.fn(),
}))

let app: App | undefined
let root: HTMLDivElement
beforeEach(() => {
  vi.clearAllMocks()
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
