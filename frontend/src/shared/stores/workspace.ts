import { onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import type { Job, Workspace, Stage } from '@/shared/types/workspace'
import {
  getStoredGuest,
  getStoredToken,
  guestAvailable,
  loginRequired,
  readStoredUser,
  sessionRevision,
} from '@/shared/utils/authSession'
import { questions } from '@/shared/utils/interviewQuestions'

export { questions } from '@/shared/utils/interviewQuestions'

const LEGACY_KEY = 'offerpilot-workspace-v1'
const MIGRATION_OWNER_KEY = 'offerpilot-workspace-migration-v1'

// Only used when a JWT has no readable user summary; never expose the token in a storage key.
function tokenFingerprint(token: string): string {
  let hash = 0xcbf29ce484222325n
  for (const character of token) {
    hash = BigInt.asUintN(64, (hash ^ BigInt(character.charCodeAt(0))) * 0x100000001b3n)
  }
  return hash.toString(16).padStart(16, '0')
}

function workspaceBinding(): { identity: string; key: string } {
  const token = getStoredToken()
  const guest = !loginRequired() && guestAvailable() ? getStoredGuest() : null
  let userId = token ? readStoredUser()?.id : null
  if (token && userId) {
    // Cross-tab writes can leave the previous summary beside a new JWT until USER_KEY arrives.
    try {
      const part = token.split('.')[1]
      const payload: unknown = part
        ? JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/')))
        : null
      if (isRecord(payload) && typeof payload.sub === 'string' && payload.sub !== userId)
        userId = null
    } catch {
      // Opaque credentials still use the stored summary; server-side authentication is unchanged.
    }
  }
  const identity = token || guest || 'anonymous'
  const suffix = token
    ? userId
      ? `user:${encodeURIComponent(userId)}`
      : `token:${tokenFingerprint(token)}`
    : guest
      ? `guest:${encodeURIComponent(guest)}`
      : 'anonymous'
  return { identity, key: `${LEGACY_KEY}:${suffix}` }
}

export const stages: Stage[] = ['收藏', '已投递', '面试中', 'Offer', '已结束']
export const jobs: Job[] = [
  {
    id: 'j1',
    title: '前端开发工程师',
    company: '星河科技',
    city: '杭州',
    salary: '15–25K · 14薪',
    category: '研发',
    tags: ['Vue 3', 'TypeScript', '校招'],
    description:
      '参与企业协作产品的前端开发，建设可复用组件，优化页面性能。熟悉 JavaScript、CSS 与现代前端框架，有个人项目或实习经历。',
    color: '#3f67eb',
  },
  {
    id: 'j2',
    title: 'Java 后端开发工程师',
    company: '云帆网络',
    city: '上海',
    salary: '18–28K · 14薪',
    category: '研发',
    tags: ['Java', 'Spring Boot', '校招'],
    description:
      '参与核心业务服务开发与接口设计，关注数据库、缓存和服务稳定性。熟悉 Java 基础、Spring Boot、SQL 与常见数据结构。',
    color: '#117e78',
  },
  {
    id: 'j3',
    title: 'AI 应用工程师',
    company: '原点智能',
    city: '北京',
    salary: '20–30K · 13薪',
    category: '研发',
    tags: ['Python', 'RAG', 'Agent'],
    description:
      '将大模型能力融入实际产品，建设检索增强与工具调用链路。需要良好的工程能力，对评测、可观测性与用户体验有兴趣。',
    color: '#7658bc',
  },
  {
    id: 'j4',
    title: '产品经理（校招生）',
    company: '未来工场',
    city: '深圳',
    salary: '12–20K · 14薪',
    category: '产品',
    tags: ['用户研究', '需求分析', '校招'],
    description: '参与用户访谈、需求分析与产品设计，协同研发推动需求交付，结合数据改进产品体验。',
    color: '#ca7040',
  },
  {
    id: 'j5',
    title: '数据分析师',
    company: '望山数据',
    city: '上海',
    salary: '14–22K · 13薪',
    category: '数据',
    tags: ['SQL', 'Python', '可视化'],
    description:
      '围绕业务问题建立分析框架，完成数据清洗、指标设计与可视化，用清晰的洞察支持团队决策。',
    color: '#3484a5',
  },
  {
    id: 'j6',
    title: '用户体验设计师',
    company: '一页设计',
    city: '杭州',
    salary: '12–20K · 13薪',
    category: '设计',
    tags: ['Figma', '交互设计', '作品集'],
    description: '负责数字产品的交互与视觉体验，参与设计系统建设，通过原型与用户测试验证方案。',
    color: '#b35685',
  },
]
export const learningTasks = [
  '梳理目标岗位的核心技能',
  '整理一个项目的 STAR 故事',
  '完成一次基础知识复盘',
  '打磨简历中的项目成果',
  '完成一场文字模拟面试',
  '复盘本周投递并调整计划',
]
export function createWorkspace(): Workspace {
  return {
    version: 1,
    name: '求职者',
    target: '前端开发工程师',
    city: '杭州',
    skills: 'JavaScript、Vue、TypeScript',
    interests: ['技术研发'],
    resumes: [],
    applications: [],
    interviews: [],
    notes: [],
    tasks: [],
    notices: [],
  }
}
export const useWorkspace = defineStore('workspace', () => {
  const storageError = ref('')
  const state = ref<Workspace>(createWorkspace())
  let boundIdentity: string | undefined
  let boundKey = ''
  let stopPersistence: (() => void) | undefined
  onScopeDispose(() => stopPersistence?.())
  watch(
    sessionRevision,
    () => {
      try {
        const binding = workspaceBinding()
        if (binding.identity === boundIdentity && binding.key === boundKey) return
        stopPersistence?.()
        boundIdentity = binding.identity
        boundKey = binding.key
        state.value = createWorkspace()
        storageError.value = ''

        let raw = localStorage.getItem(boundKey)
        const legacy = localStorage.getItem(LEGACY_KEY)
        if (legacy !== null) {
          // Claim even corrupt legacy data or an existing destination, so later identities
          // cannot inherit it. Keep the original payload intact until a successful copy.
          let owner = localStorage.getItem(MIGRATION_OWNER_KEY)
          if (owner === null) {
            localStorage.setItem(MIGRATION_OWNER_KEY, boundKey)
            owner = boundKey
          }
          if (owner === boundKey && raw === null) {
            const parsed: unknown = JSON.parse(legacy)
            if (isWorkspace(parsed)) {
              localStorage.setItem(boundKey, legacy)
              localStorage.removeItem(LEGACY_KEY)
              raw = legacy
            } else {
              storageError.value = '本地数据格式不兼容，已打开临时工作区；原数据未覆盖。'
            }
          }
        }
        if (raw !== null) {
          const parsed: unknown = JSON.parse(raw)
          if (isWorkspace(parsed)) state.value = parsed
          else storageError.value = '本地数据格式不兼容，已打开临时工作区；原数据未覆盖。'
        }
      } catch {
        stopPersistence?.()
        state.value = createWorkspace()
        storageError.value = '无法读取本地数据，本次修改可能无法在刷新后保留。'
      }

      const blocked = Boolean(storageError.value)
      const key = boundKey
      stopPersistence = watch(
        state,
        (value) => {
          if (blocked) return
          try {
            localStorage.setItem(key, JSON.stringify(value))
            storageError.value = ''
          } catch {
            storageError.value = '浏览器存储空间不足或不可用，请导出重要内容。'
          }
        },
        { deep: true },
      )
    },
    { immediate: true, flush: 'sync' },
  )
  function notify(text: string) {
    state.value.notices.unshift({
      id: crypto.randomUUID(),
      text,
      date: new Date().toLocaleString('zh-CN'),
      read: false,
    })
  }
  function jobFor(jobId: string) {
    return (
      state.value.applications.find((a) => a.jobId === jobId)?.jobSnapshot ??
      jobs.find((j) => j.id === jobId)
    )
  }
  // Existing applications remain a readable/exportable archive. Active board writes use the API.
  return { state, storageError, notify, jobFor }
})
function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object'
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === 'string')
}
function records(value: unknown, predicate: (v: Record<string, unknown>) => boolean): boolean {
  return Array.isArray(value) && value.every((v) => isRecord(v) && predicate(v))
}
export function isWorkspace(v: unknown): v is Workspace {
  if (!isRecord(v) || v.version !== 1) return false
  return (
    ['name', 'target', 'city', 'skills'].every((k) => typeof v[k] === 'string') &&
    strings(v.interests) &&
    strings(v.tasks) &&
    records(
      v.resumes,
      (r) =>
        ['id', 'title', 'content', 'updated'].every((k) => typeof r[k] === 'string') &&
        records(r.versions, (x) => typeof x.content === 'string' && typeof x.date === 'string'),
    ) &&
    records(
      v.applications,
      (a) =>
        ['id', 'jobId', 'note', 'date'].every((k) => typeof a[k] === 'string') &&
        stages.includes(a.stage as Stage) &&
        (a.jobSnapshot === undefined ||
          (isRecord(a.jobSnapshot) &&
            ['id', 'title', 'company', 'city', 'salary', 'category', 'description', 'color'].every(
              (key) => typeof (a.jobSnapshot as Record<string, unknown>)[key] === 'string',
            ) &&
            strings(a.jobSnapshot.tags))),
    ) &&
    records(
      v.interviews,
      (i) =>
        ['id', 'role', 'kind', 'date'].every((k) => typeof i[k] === 'string') &&
        strings(i.answers) &&
        Number.isInteger(i.question) &&
        Number(i.question) >= 0 &&
        Number(i.question) < questions.length &&
        typeof i.completed === 'boolean',
    ) &&
    records(v.notes, (n) =>
      ['id', 'title', 'content', 'category'].every((k) => typeof n[k] === 'string'),
    ) &&
    records(
      v.notices,
      (n) =>
        ['id', 'text', 'date'].every((k) => typeof n[k] === 'string') &&
        typeof n.read === 'boolean',
    )
  )
}
