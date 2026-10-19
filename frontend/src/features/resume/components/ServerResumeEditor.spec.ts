import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ServerResumeEditor from './ServerResumeEditor.vue'
import {
  deleteResume,
  exportResumePdf,
  getResume,
  listResumeVersions,
  restoreResumeVersion,
  saveResume,
} from '@/shared/api/resumes'
import type { ResumeVersion, ServerResume } from '@/shared/types/resume'
import { ApiError } from '@/shared/api/client'

vi.mock('@/shared/api/resumes', () => ({
  deleteResume: vi.fn(),
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
let router: ReturnType<typeof createRouter>
beforeEach(async () => {
  vi.resetAllMocks()
  vi.mocked(getResume).mockResolvedValue(fixture())
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resumes', component: { render: () => null } },
      { path: '/resumes/:id', component: ServerResumeEditor, props: true },
    ],
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

async function loadLists() {
  const loaded = fixture('v2')
  const structured = loaded.currentVersion!.structured
  structured.education = ['A', 'B'].map((name) => ({
    school: `学校${name}`,
    degree: '',
    major: '',
    start: '',
    end: '',
    gpa: '',
  }))
  structured.experience = ['A', 'B'].map((name) => ({
    org: `公司${name}`,
    title: '',
    start: '',
    end: '',
    bullets: [`成果${name}`],
  }))
  structured.projects = ['A', 'B'].map((name) => ({
    name: `项目${name}`,
    role: '',
    start: '',
    end: '',
    tech: ['Vue'],
    bullets: [`成果${name}`],
  }))
  structured.skills = ['A', 'B'].map((name) => ({ category: `技能${name}`, items: ['Java'] }))
  structured.certificates = ['证书A', '证书B']
  cache.setQueryData(['resume', 'rs1'], loaded)
  await nextTick()
  return loaded
}

it('所有列表的排序进入保存 JSON，新版本成为后续基准且版本记录读取最新树', async () => {
  const loaded = await loadLists()
  const original = loaded.currentVersion!.structured
  const reordered = {
    ...original,
    education: [...original.education].reverse(),
    experience: [...original.experience].reverse(),
    projects: [...original.projects].reverse(),
    skills: [...original.skills].reverse(),
    certificates: [...original.certificates].reverse(),
  }
  for (const section of root.querySelectorAll('.resume-section, .certificate-section')) {
    section.querySelector<HTMLButtonElement>('[data-action="down"]')!.click()
  }
  await nextTick()
  expect(button('保存新版本').disabled).toBe(false)
  expect(saveResume).not.toHaveBeenCalled()
  const updated: ServerResume = {
    ...loaded,
    currentVersionId: 'v3',
    currentVersion: {
      ...loaded.currentVersion!,
      id: 'v3',
      versionNo: 3,
      parentVersionId: 'v2',
      source: 'MANUAL_EDIT',
      structured: reordered,
    },
  }
  vi.mocked(saveResume).mockResolvedValue(updated)
  button('保存新版本').click()
  await vi.waitFor(() => expect(button('保存新版本').disabled).toBe(true))
  expect(saveResume).toHaveBeenCalledWith('rs1', 'v2', reordered)
  expect(cache.getQueryData<ServerResume>(['resume', 'rs1'])?.currentVersionId).toBe('v3')
  expect(root.querySelector<HTMLInputElement>('.resume-section input')?.value).toBe('学校B')
  expect(root.querySelector<HTMLInputElement>('.certificate-section input')?.value).toBe('证书B')
  expect(root.querySelector('.resume-save')?.textContent).toContain('已保存为新版本')
  vi.mocked(listResumeVersions).mockResolvedValue([loaded.currentVersion!, updated.currentVersion!])
  button('版本记录').click()
  await vi.waitFor(() => expect(root.querySelectorAll('.version-tree li')).toHaveLength(2))
  const current = root.querySelectorAll('.version-tree li')[1]!
  expect(current.textContent).toContain('当前')
  expect(current.textContent).toContain('手动编辑')
  expect(current.textContent).toContain('基于版本 2')
  button('内容编辑').click()
  await nextTick()
  await edit('再次编辑')
  vi.mocked(saveResume).mockResolvedValue({
    ...updated,
    currentVersionId: 'v4',
    currentVersion: {
      ...updated.currentVersion!,
      id: 'v4',
      versionNo: 4,
      parentVersionId: 'v3',
      structured: { ...reordered, basics: { ...reordered.basics, name: '再次编辑' } },
    },
  })
  button('保存新版本').click()
  await vi.waitFor(() =>
    expect(cache.getQueryData<ServerResume>(['resume', 'rs1'])?.currentVersionId).toBe('v4'),
  )
  expect(saveResume).toHaveBeenCalledTimes(2)
  expect(vi.mocked(saveResume).mock.calls[1]?.[1]).toBe('v3')
})

it('排序遇到409保留原基准和草稿，重新加载需要确认并采用服务端顺序', async () => {
  const loaded = await loadLists()
  root.querySelector<HTMLButtonElement>('.resume-section [data-action="down"]')!.click()
  await nextTick()
  const server = {
    ...loaded,
    currentVersionId: 'v3',
    currentVersion: { ...loaded.currentVersion!, id: 'v3' },
  }
  cache.setQueryData(['resume', 'rs1'], server)
  vi.mocked(saveResume).mockRejectedValue(
    new ApiError('简历已更新，请重新加载后保存', { code: 30403, status: 409 }),
  )
  button('保存新版本').click()
  await vi.waitFor(() => expect(root.querySelector('.resume-save [role="alert"]')).not.toBeNull())
  expect(vi.mocked(saveResume).mock.calls[0]?.[1]).toBe('v2')
  expect(vi.mocked(saveResume).mock.calls[0]?.[2].education.map((e) => e.school)).toEqual([
    '学校B',
    '学校A',
  ])
  expect(root.querySelector<HTMLInputElement>('.resume-section input')?.value).toBe('学校B')
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  const reads = vi.mocked(getResume).mock.calls.length
  button('重新加载').click()
  await nextTick()
  expect(getResume).toHaveBeenCalledTimes(reads)
  expect(root.querySelector<HTMLInputElement>('.resume-section input')?.value).toBe('学校B')
  vi.mocked(getResume).mockResolvedValue(server)
  button('重新加载').click()
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('.resume-section input')?.value).toBe('学校A'),
  )
  expect(button('保存新版本').disabled).toBe(true)
  expect(root.querySelector('.resume-save [role="alert"]')).toBeNull()
  confirm.mockRestore()
})
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
  expect(vi.mocked(exportResumePdf)).toHaveBeenCalledWith('rs1', 'classic')
  expect(saveResume).not.toHaveBeenCalled()
  finish()
  await vi.waitFor(() => expect(button('导出 PDF').disabled).toBe(false))
  expect(root.querySelector<HTMLInputElement>('.resume-basics input')?.value).toBe('未保存的修改')
  const select = root.querySelector<HTMLSelectElement>('select[aria-label="PDF 模板"]')!
  select.value = 'compact'
  select.dispatchEvent(new Event('change'))
  vi.mocked(exportResumePdf).mockResolvedValueOnce()
  button('导出 PDF').click()
  await vi.waitFor(() =>
    expect(vi.mocked(exportResumePdf)).toHaveBeenLastCalledWith('rs1', 'compact'),
  )
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

it('删除需要确认，取消不调用接口；确认后即使有未保存修改也直接回到简历中心', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  await edit('未保存的修改')
  button('删除简历').click()
  await nextTick()
  expect(deleteResume).not.toHaveBeenCalled()
  vi.mocked(deleteResume).mockResolvedValue({ deleted: true })
  button('删除简历').click()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/resumes'))
  expect(deleteResume).toHaveBeenCalledWith('rs1')
  // Only the delete confirmation was shown; the unsaved-changes guard did not ask again.
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(confirm.mock.calls[1]?.[0]).toContain('无法恢复')
  confirm.mockRestore()
})
