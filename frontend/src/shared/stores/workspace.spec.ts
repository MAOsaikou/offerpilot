import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { nextTick } from 'vue'
import { createWorkspace, isWorkspace, useWorkspace } from './workspace'
import {
  ACCESS_TOKEN_KEY,
  GUEST_TOKEN_KEY,
  clearLogin,
  invalidateCredential,
  resumeGuest,
  saveGuest,
  saveLogin,
} from '@/shared/utils/authSession'

const LEGACY_KEY = 'offerpilot-workspace-v1'
const ANONYMOUS_KEY = `${LEGACY_KEY}:anonymous`
const userKey = (id: string) => `${LEGACY_KEY}:user:${encodeURIComponent(id)}`
const guestKey = (credential: string) => `${LEGACY_KEY}:guest:${encodeURIComponent(credential)}`
function login(id: string, token = `token-${id}`) {
  saveLogin({
    accessToken: token,
    expiresIn: 43200,
    user: { id, email: `${id}@example.com`, nickname: id, roles: ['USER'] },
  })
}
let pinia: Pinia
function reloadStore() {
  disposePinia(pinia)
  pinia = createPinia()
  setActivePinia(pinia)
  return useWorkspace()
}

describe('本地工作区', () => {
  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
  })
  afterEach(() => {
    disposePinia(pinia)
    vi.restoreAllMocks()
  })
  it('旧本地收藏仍作为归档保留，不被服务端看板替换或删除', async () => {
    const workspace = useWorkspace()
    workspace.state.applications.push({
      id: 'old',
      jobId: 'j1',
      stage: '收藏',
      note: '',
      date: '2026-10-03',
    })
    expect(workspace.state.applications).toHaveLength(1)
    const app = workspace.state.applications[0]!
    app.stage = '面试中'
    app.note = '准备项目复盘'
    await nextTick()
    expect(reloadStore().state.applications[0]).toMatchObject({
      stage: '面试中',
      note: '准备项目复盘',
    })
  })
  it('拒绝损坏的嵌套存储，不覆盖原始内容', async () => {
    const raw = JSON.stringify({
      ...createWorkspace(),
      interviews: [{ id: 'broken', answers: null }],
    })
    localStorage.setItem(ANONYMOUS_KEY, raw)
    const workspace = useWorkspace()
    expect(workspace.storageError).not.toBe('')
    workspace.state.name = '测试'
    await nextTick()
    expect(localStorage.getItem(ANONYMOUS_KEY)).toBe(raw)
  })
  it('真实岗位收藏携带快照，刷新后看板仍有标题且保留旧示例收藏', async () => {
    const store = useWorkspace()
    store.state.applications.push({
      id: 'old',
      jobId: 'j1',
      stage: '收藏',
      note: '',
      date: '2026-10-03',
    })
    store.state.applications.push({
      id: 'old-real',
      jobId: 'job_real',
      stage: '收藏',
      note: '',
      date: '2026-10-03',
      jobSnapshot: {
        id: 'job_real',
        title: '实际岗位',
        company: '企业',
        city: '上海',
        salary: '',
        category: '',
        tags: [],
        description: '',
        color: '',
      },
    })
    await nextTick()
    expect(reloadStore().jobFor('job_real')?.title).toBe('实际岗位')
    expect(useWorkspace().jobFor('j1')?.title).toBeTruthy()
  })
  it.each(['user', 'guest'] as const)('%s A 写入 → 注销 → B 隔离 → A 恢复', async (kind) => {
    const activate = kind === 'user' ? login : saveGuest
    const key = kind === 'user' ? userKey : guestKey
    activate('A')
    const workspace = useWorkspace()
    workspace.state.name = 'A 的草稿'
    workspace.state.notes.push({
      id: 'A-note',
      title: '私有笔记',
      content: 'A 内容',
      category: '复盘',
    })
    workspace.state.tasks.push('A 学习任务')
    workspace.state.resumes.push({
      id: 'A-resume',
      title: '简历',
      content: 'A 简历',
      updated: '今天',
      versions: [],
    })
    workspace.state.interviews.push({
      id: 'A-interview',
      role: '开发',
      kind: '练习',
      date: '今天',
      answers: ['A 回答'],
      question: 0,
      completed: false,
    })
    await nextTick()
    const savedA = localStorage.getItem(key('A'))
    expect(JSON.parse(savedA!)).toEqual(workspace.state)

    clearLogin()
    expect(workspace.state).toEqual(createWorkspace())
    activate('B')
    expect(useWorkspace()).toBe(workspace)
    expect(workspace.state).toEqual(createWorkspace())
    expect(workspace.storageError).toBe('')
    workspace.state.name = 'B 的草稿'
    await nextTick()
    expect(localStorage.getItem(key('A'))).toBe(savedA)
    expect(JSON.parse(localStorage.getItem(key('B'))!).name).toBe('B 的草稿')

    activate('A')
    expect(workspace.state).toEqual(JSON.parse(savedA!))
    activate('B')
    expect(workspace.state.name).toBe('B 的草稿')
  })
  it('访客注销后不回退保留的凭据，显式恢复才重载工作区；失效后卸载', async () => {
    saveGuest('guest-A')
    const workspace = useWorkspace()
    workspace.state.name = '访客私有草稿'
    await nextTick()
    clearLogin()
    expect(workspace.state).toEqual(createWorkspace())
    expect(localStorage.getItem(GUEST_TOKEN_KEY)).toBe('guest-A')
    expect(resumeGuest()).toBe(true)
    expect(workspace.state.name).toBe('访客私有草稿')
    invalidateCredential('guest-A')
    expect(workspace.state).toEqual(createWorkspace())
    expect(resumeGuest()).toBe(false)
  })
  it('跨标签存储事件同步卸载旧账号，停止排队写入并加载新账号', async () => {
    login('A')
    const workspace = useWorkspace()
    workspace.state.name = 'A 私有草稿'
    await nextTick()
    const savedA = localStorage.getItem(userKey('A'))
    localStorage.setItem(userKey('B'), JSON.stringify({ ...createWorkspace(), name: 'B 已有草稿' }))
    workspace.state.name = 'A 排队修改'
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-B')
    localStorage.setItem('offerpilot-auth-user', JSON.stringify({ id: 'B', roles: ['USER'] }))
    window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY }))
    expect(workspace.state.name).toBe('B 已有草稿')
    await nextTick()
    expect(localStorage.getItem(userKey('A'))).toBe(savedA)
    expect(JSON.parse(localStorage.getItem(userKey('B'))!).name).toBe('B 已有草稿')
    invalidateCredential('token-B')
    expect(workspace.state).toEqual(createWorkspace())
  })
  it('首次旧工作区只迁移到当时身份，重建 store 后第二身份也不继承', () => {
    saveGuest('A')
    const raw = JSON.stringify({ ...createWorkspace(), name: '旧草稿', tasks: ['旧任务'] })
    localStorage.setItem(LEGACY_KEY, raw)
    const workspace = useWorkspace()
    expect(workspace.state.name).toBe('旧草稿')
    expect(localStorage.getItem(guestKey('A'))).toBe(raw)
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull()
    saveGuest('B')
    expect(reloadStore().state).toEqual(createWorkspace())
    saveGuest('A')
    expect(useWorkspace().state.name).toBe('旧草稿')
  })
  it('跨标签先收到新 JWT、稍后收到用户摘要时，不重载旧账号的草稿', async () => {
    const jwt = (id: string) => `e30.${btoa(JSON.stringify({ sub: id }))}.signature`
    login('A', jwt('A'))
    const workspace = useWorkspace()
    workspace.state.name = 'A 私有草稿'
    await nextTick()
    const savedA = localStorage.getItem(userKey('A'))
    localStorage.setItem(userKey('B'), JSON.stringify({ ...createWorkspace(), name: 'B 已有草稿' }))
    localStorage.setItem(ACCESS_TOKEN_KEY, jwt('B'))
    window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY }))
    expect(workspace.state).toEqual(createWorkspace())
    localStorage.setItem('offerpilot-auth-user', JSON.stringify({ id: 'B', roles: ['USER'] }))
    window.dispatchEvent(new StorageEvent('storage', { key: 'offerpilot-auth-user' }))
    expect(workspace.state.name).toBe('B 已有草稿')
    await nextTick()
    expect(localStorage.getItem(userKey('A'))).toBe(savedA)
  })
  it.each(['invalid JSON', JSON.stringify({ ...createWorkspace(), notes: [null] })])(
    '损坏旧数据阻止首次身份写入，但不影响后来身份或被后来身份继承：%s',
    async (raw) => {
      login('A')
      localStorage.setItem(LEGACY_KEY, raw)
      const workspace = useWorkspace()
      expect(workspace.storageError).not.toBe('')
      workspace.state.name = '不能覆盖'
      await nextTick()
      expect(localStorage.getItem(userKey('A'))).toBeNull()
      expect(localStorage.getItem(LEGACY_KEY)).toBe(raw)
      login('B')
      const reloaded = reloadStore()
      expect(reloaded.state).toEqual(createWorkspace())
      expect(reloaded.storageError).toBe('')
      reloaded.state.name = 'B 可保存'
      await nextTick()
      expect(JSON.parse(localStorage.getItem(userKey('B'))!).name).toBe('B 可保存')
      login('A')
      expect(reloaded.storageError).not.toBe('')
      expect(localStorage.getItem(LEGACY_KEY)).toBe(raw)
    },
  )
  it.each([true, false])('损坏旧数据不会覆盖已有身份键，即使目标也损坏：%s', async (valid) => {
    login('A')
    const existing = valid ? JSON.stringify({ ...createWorkspace(), name: '已有草稿' }) : '{broken'
    const legacy = JSON.stringify({ ...createWorkspace(), notes: [null] })
    localStorage.setItem(userKey('A'), existing)
    localStorage.setItem(LEGACY_KEY, legacy)
    const workspace = useWorkspace()
    expect(workspace.state.name).toBe(valid ? '已有草稿' : '求职者')
    expect(Boolean(workspace.storageError)).toBe(!valid)
    await nextTick()
    expect(localStorage.getItem(userKey('A'))).toBe(existing)
    login('B')
    expect(reloadStore().state).toEqual(createWorkspace())
    expect(localStorage.getItem(LEGACY_KEY)).toBe(legacy)
  })
  it('迁移写入失败保留旧数据与首次归属，其他身份不重试迁移', () => {
    login('A')
    const raw = JSON.stringify({ ...createWorkspace(), name: '旧草稿' })
    localStorage.setItem(LEGACY_KEY, raw)
    const setItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      if (key === userKey('A')) throw new Error('quota exceeded')
      setItem.call(this, key, value)
    })
    const workspace = useWorkspace()
    expect(workspace.storageError).not.toBe('')
    expect(localStorage.getItem(LEGACY_KEY)).toBe(raw)
    login('B')
    expect(workspace.state).toEqual(createWorkspace())
    expect(workspace.storageError).toBe('')
    vi.restoreAllMocks()
    login('A')
    expect(workspace.state.name).toBe('旧草稿')
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull()
  })
  it('账号重新登录沿用用户 ID；键编码特殊字符且不含 JWT', async () => {
    const id = '用户:/A'
    login(id, 'secret-jwt-1')
    const workspace = useWorkspace()
    workspace.state.name = '同一账号'
    await nextTick()
    clearLogin()
    login(id, 'secret-jwt-2')
    expect(workspace.state.name).toBe('同一账号')
    expect(localStorage.getItem(userKey(id))).not.toBeNull()
    expect(Object.keys(localStorage).some((key) => key.includes('secret-jwt'))).toBe(false)
  })
  it('缺少用户摘要时使用稳定令牌指纹，刷新恢复且不同令牌隔离', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'private-JWT-A')
    const workspace = useWorkspace()
    workspace.state.name = 'A 私有草稿'
    await nextTick()
    const keys = Object.keys(localStorage).filter((key) => key.startsWith(`${LEGACY_KEY}:`))
    expect(keys).toHaveLength(1)
    expect(keys[0]).toMatch(/^offerpilot-workspace-v1:token:[0-9a-f]{16}$/)
    expect(reloadStore().state.name).toBe('A 私有草稿')
    localStorage.setItem(ACCESS_TOKEN_KEY, 'private-JWT-B')
    window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY }))
    expect(useWorkspace().state).toEqual(createWorkspace())
  })
  it('不接受超出题库范围的面试位置或非字符串任务', () => {
    expect(isWorkspace(createWorkspace())).toBe(true)
    expect(isWorkspace({ ...createWorkspace(), tasks: [null] })).toBe(false)
    expect(
      isWorkspace({
        ...createWorkspace(),
        interviews: [
          {
            id: 'i1',
            role: '开发',
            kind: '练习',
            date: '今天',
            answers: [],
            question: 8,
            completed: false,
          },
        ],
      }),
    ).toBe(false)
  })
})
