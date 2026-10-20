import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import GitHubEvidencePanel from './GitHubEvidencePanel.vue'
import * as api from '@/shared/api/github-evidence'
import { sessionRevision } from '@/shared/utils/authSession'
import type { ServerResume } from '@/shared/types/resume'
vi.mock('@/shared/api/github-evidence', () => ({
  getGitHubEvidence: vi.fn(),
  saveGitHubSettings: vi.fn(),
  refreshGitHubEvidence: vi.fn(),
  deleteGitHubSettings: vi.fn(),
  adoptGitHubProject: vi.fn(),
}))
const fixture = (): api.GitHubEvidence => ({
  username: 'fixture',
  tokenConfigured: true,
  status: 'READY',
  notice: '近90天仅默认分支，语言不证明个人贡献',
  fetchedAt: '2026-10-20T00:00:00Z',
  expiresAt: '2026-10-20T06:00:00Z',
  stale: false,
  snapshot: {
    id: 'snap',
    since: '2026-07-22T00:00:00Z',
    repositoriesTruncated: true,
    languages: { Java: 120 },
    stars: 5,
    commits90d: 100,
    repositories: [
      {
        name: 'fixture/project',
        url: 'https://github.com/fixture/project',
        fork: false,
        stars: 5,
        languages: { Java: 120 },
        commits90d: 100,
        commitsTruncated: true,
        highlights: ['Java API service.'],
      },
    ],
  },
})
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!
const input = (value: string, selector: string) => {
  const el = host.querySelector<HTMLInputElement>(selector)!
  el.value = value
  el.dispatchEvent(new Event('input'))
}
function mount(extra = {}) {
  app = createApp(GitHubEvidencePanel, { resumeId: 'rs', baseVersionId: 'v1', ...extra })
  app.mount(host)
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(api.getGitHubEvidence).mockResolvedValue(fixture())
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
it('never fills a saved token and clears submitted secret immediately, then can remove credentials', async () => {
  mount()
  await flush()
  expect(host.querySelector<HTMLInputElement>('input[type=password]')!.value).toBe('')
  expect(host.textContent).toContain('已配置，不回显')
  input('fictional_only', 'input[type=password]')
  await flush()
  expect(button('强制刷新').disabled).toBe(true)
  vi.mocked(api.saveGitHubSettings).mockResolvedValue({
    ...fixture(),
    snapshot: null,
    status: 'NOT_FETCHED',
  })
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flush()
  expect(api.saveGitHubSettings).toHaveBeenCalledWith(
    { username: 'fixture', token: 'fictional_only', removeToken: false },
    expect.any(AbortSignal),
  )
  expect(host.innerHTML).not.toContain('fictional_only')
  expect(host.querySelector<HTMLInputElement>('input[type=password]')!.value).toBe('')
  vi.mocked(api.deleteGitHubSettings).mockResolvedValue(null)
  button('移除 GitHub 连接').click()
  await flush()
  expect(api.deleteGitHubSettings).toHaveBeenCalledOnce()
  expect(host.textContent).toContain('已移除 GitHub 设置、凭据与缓存')
})
it('requires preview and explicit confirmation before adopting and emits the saved version', async () => {
  const adopted = vi.fn(),
    busy = vi.fn()
  mount({ onAdopted: adopted, onAdoptionBusy: busy })
  await flush()
  expect(host.textContent).toContain('≥100')
  expect(host.textContent).toContain('2026-10-20T00:00:00Z')
  button('作为简历项目建议').click()
  await flush()
  expect(button('确认添加并保存新版本').disabled).toBe(true)
  expect(api.adoptGitHubProject).not.toHaveBeenCalled()
  const checkbox = host.querySelector<HTMLInputElement>(
    '[aria-label="确认 GitHub 项目建议"] input',
  )!
  checkbox.click()
  await flush()
  vi.mocked(api.adoptGitHubProject).mockResolvedValue({
    id: 'rs',
    currentVersionId: 'v2',
  } as ServerResume)
  button('确认添加并保存新版本').click()
  await flush()
  expect(api.adoptGitHubProject).toHaveBeenCalledWith(
    {
      resumeId: 'rs',
      baseVersionId: 'v1',
      snapshotId: 'snap',
      repository: 'fixture/project',
      confirmed: true,
    },
    expect.any(AbortSignal),
  )
  expect(adopted).toHaveBeenCalledOnce()
  expect(busy.mock.calls).toEqual([[true], [false]])
  expect(host.textContent).toContain('已存为简历新版本')
})
it('shows statistics fallback and stale failure honestly, and prevents adopting stale or dirty results', async () => {
  const stale = fixture()
  stale.stale = true
  stale.status = 'RATE_LIMITED'
  stale.notice = 'GitHub 请求已限流；历史缓存'
  vi.mocked(api.getGitHubEvidence).mockResolvedValue(stale)
  mount()
  await flush()
  expect(host.textContent).toContain('已限流')
  expect(host.textContent).toContain('历史缓存，需刷新')
  expect(button('作为简历项目建议').disabled).toBe(true)
  const statistics = fixture()
  statistics.status = 'STATISTICS_ONLY'
  statistics.notice = '亮点不可用，已降级为仅统计'
  statistics.snapshot!.repositories[0]!.highlights = []
  vi.mocked(api.refreshGitHubEvidence).mockResolvedValue(statistics)
  button('强制刷新').click()
  await flush()
  expect(host.textContent).toContain('已降级为仅统计')
  expect(host.textContent).toContain('暂无可用 README 亮点')
  vi.mocked(api.refreshGitHubEvidence).mockRejectedValue(new Error('离线，请重试'))
  button('强制刷新').click()
  await flush()
  expect(host.textContent).toContain('离线，请重试')
})
it('discards a late response and clears all credential input on identity change', async () => {
  let resolve!: (v: api.GitHubEvidence) => void
  vi.mocked(api.getGitHubEvidence)
    .mockReturnValueOnce(
      new Promise((r) => {
        resolve = r
      }),
    )
    .mockResolvedValueOnce({ ...fixture(), username: 'new-user', snapshot: null })
  mount()
  await flush()
  sessionRevision.value++
  await flush()
  resolve(fixture())
  await flush()
  expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('new-user')
  expect(host.textContent).not.toContain('fixture/project')
})
it('dirty resume disables adoption while keeping statistics readable', async () => {
  mount({ adoptionDisabled: true })
  await flush()
  expect(button('作为简历项目建议').disabled).toBe(true)
  expect(host.textContent).toContain('请先保存或撤销简历草稿')
})
