import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ServerResumeEditor from './ServerResumeEditor.vue'
import {
  exportResumePdf,
  getResume,
  listResumeVersions,
  restoreResumeVersion,
  saveResume,
} from '@/shared/api/resumes'
import type { ResumeVersion, ServerResume } from '@/shared/types/resume'

vi.mock('@/shared/api/resumes', () => ({
  exportResumePdf: vi.fn(),
  getResume: vi.fn(),
  saveResume: vi.fn(),
  renameResume: vi.fn(),
  retryResume: vi.fn(),
  listResumeVersions: vi.fn(),
  restoreResumeVersion: vi.fn(),
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
it('PDF 导出使用保存版本，不提交未保存草稿，并显示下载状态', async () => {
  vi.mocked(exportResumePdf).mockRejectedValueOnce(new Error('PDF 导出缺少可用中文字体'))
  button('导出 PDF').click()
  await vi.waitFor(() =>
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('中文字体'),
  )
  expect(button('导出 PDF').disabled).toBe(false)
  await edit('未保存的修改')
  let finish!: () => void
  vi.mocked(exportResumePdf).mockReturnValue(
    new Promise<void>((resolve) => {
      finish = resolve
    }),
  )
  button('导出 PDF').click()
  await nextTick()
  expect(button('导出中…').disabled).toBe(true)
  expect(vi.mocked(exportResumePdf)).toHaveBeenCalledWith('rs1')
  expect(saveResume).not.toHaveBeenCalled()
  finish()
  await vi.waitFor(() => expect(button('导出 PDF').disabled).toBe(false))
  expect(root.querySelector<HTMLInputElement>('.resume-basics input')?.value).toBe('未保存的修改')
})

it('版本树显示分支、对比文本差异，回滚创建新的当前版本而不改写历史', async () => {
  const base = fixture().currentVersion!
  const v = (
    id: string,
    versionNo: number,
    parentVersionId: string | null,
    plainText: string,
    extra: Partial<ResumeVersion> = {},
  ): ResumeVersion => ({ ...base, id, versionNo, parentVersionId, plainText, ...extra })
  const versions = [
    v('v1', 1, null, '张三\nJava'),
    v('v2', 2, 'v1', '张三\nJava\nSpring', {
      source: 'MANUAL_EDIT',
      structured: { ...base.structured, skills: [{ name: 'Spring' }] },
    }),
    v('v3', 3, 'v1', '张三\nGo', { source: 'OPTIMIZED' }),
  ]
  vi.mocked(getResume).mockResolvedValue({ ...fixture('v2'), currentVersion: versions[1]! })
  await cache.refetchQueries({ queryKey: ['resume', 'rs1'] })
  vi.mocked(listResumeVersions).mockResolvedValue(versions)
  button('版本记录').click()
  await vi.waitFor(() => expect(root.querySelectorAll('.version-tree li')).toHaveLength(3))
  // v2 and v3 both branch from v1, so they are indented under it.
  const rows = [...root.querySelectorAll<HTMLElement>('.version-tree li')]
  expect(rows.map((r) => r.style.paddingInlineStart)).toEqual(['0rem', '1.5rem', '1.5rem'])
  expect(rows[2]!.textContent).toContain('基于版本 1')
  // Default comparison: current version against its parent.
  expect(root.querySelector('.diff .add')?.textContent).toContain('Spring')
  const restored = v('v4', 4, 'v2', '张三\nGo', {
    source: 'MANUAL_EDIT',
    parseMeta: { restoredFrom: 'v3', restoredFromNo: 3 },
  })
  vi.mocked(restoreResumeVersion).mockResolvedValue({ ...fixture('v4'), currentVersion: restored })
  vi.mocked(listResumeVersions).mockResolvedValue([...versions, restored])
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  ;[...rows[2]!.querySelectorAll('button')]
    .find((b) => b.textContent?.trim() === '回滚到此版本')!
    .click()
  await vi.waitFor(() => expect(root.querySelectorAll('.version-tree li')).toHaveLength(4))
  expect(restoreResumeVersion).toHaveBeenCalledWith('rs1', 'v3', 'v2')
  expect(root.textContent).toContain('回滚自版本 3')
})
