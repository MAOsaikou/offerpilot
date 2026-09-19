import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ServerResumeEditor from './ServerResumeEditor.vue'
import { getResume, saveResume } from '@/shared/api/resumes'
import type { ServerResume } from '@/shared/types/resume'

vi.mock('@/shared/api/resumes', () => ({
  getResume: vi.fn(),
  saveResume: vi.fn(),
  renameResume: vi.fn(),
  retryResume: vi.fn(),
  listResumeVersions: vi.fn(),
}))
function fixture(version = 'v1', name = 'Original'): ServerResume {
  return {
    id: 'rs1',
    title: 'Resume',
    parseStatus: 'READY',
    parseProgress: 100,
    parseError: null,
    currentVersionId: version,
    fileName: 'resume.txt',
    createdAt: '',
    updatedAt: '',
    currentVersion: {
      id: version,
      versionNo: version === 'v1' ? 1 : 2,
      parentVersionId: null,
      source: 'UPLOAD',
      structured: {
        basics: { name, phone: '', email: '', city: '', links: [] },
        education: [],
        experience: [],
        projects: [],
        skills: [],
        certificates: [],
        summary: '',
      },
      plainText: name,
      parseMeta: {},
      createdAt: '',
    },
  }
}
let app: App
let root: HTMLDivElement
let cache: QueryClient
beforeEach(async () => {
  vi.resetAllMocks()
  vi.mocked(getResume).mockResolvedValue(fixture())
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/resumes/:id', component: ServerResumeEditor, props: true }],
  })
  await router.push('/resumes/rs1')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('.resume-basics input')).not.toBeNull())
})
afterEach(() => {
  app.unmount()
  cache.clear()
  root.remove()
})
async function edit(value: string) {
  const input = root.querySelector<HTMLInputElement>('.resume-basics input')!
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
function button(text: string) {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
}
it('保留未保存草稿及其基准版本，后台更新和409不会覆盖用户编辑', async () => {
  await edit('My unsaved changes')
  cache.setQueryData(['resume', 'rs1'], fixture('v2', 'Other tab'))
  await nextTick()
  expect(root.querySelector<HTMLInputElement>('.resume-basics input')?.value).toBe(
    'My unsaved changes',
  )
  vi.mocked(saveResume).mockRejectedValue(new Error('简历已更新，请重新加载后保存'))
  root
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await vi.waitFor(() =>
    expect(root.querySelector('.resume-save [role="alert"]')?.textContent).toContain('简历已更新'),
  )
  expect(vi.mocked(saveResume).mock.calls[0]?.[1]).toBe('v1')
  expect(root.querySelector<HTMLInputElement>('.resume-basics input')?.value).toBe(
    'My unsaved changes',
  )
})
it('保存期间禁止编辑和载入历史，成功后同步新版本并显示就近反馈', async () => {
  await edit('Saved changes')
  let finish!: (value: ServerResume) => void
  vi.mocked(saveResume).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  root
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await nextTick()
  expect(root.querySelector<HTMLFieldSetElement>('fieldset')?.disabled).toBe(true)
  expect(button('版本记录').disabled).toBe(true)
  finish(fixture('v2', 'Saved changes'))
  await vi.waitFor(() =>
    expect(root.querySelector('.resume-save')?.textContent).toContain('已保存为新版本'),
  )
  expect(button('保存新版本').disabled).toBe(true)
  expect(root.querySelector<HTMLInputElement>('.resume-basics input')?.value).toBe('Saved changes')
})
