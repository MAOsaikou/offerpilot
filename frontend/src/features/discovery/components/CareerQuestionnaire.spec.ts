import { setLanguage } from '@/shared/utils/language'
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import CareerQuestionnaire from './CareerQuestionnaire.vue'
import * as api from '@/shared/api/career'
vi.mock('@/shared/api/career', () => ({
  getQuestionnaire: vi.fn(),
  saveQuestionnaireAnswer: vi.fn(),
  completeQuestionnaire: vi.fn(),
  getCareerProfile: vi.fn(),
}))
let app: App
let root: HTMLDivElement
let state: api.CareerQuestionnaire
const confirmed = vi.fn()
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
function mount() {
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(CareerQuestionnaire, { onConfirmed: confirmed })
  app.mount(root)
}
beforeEach(() => {
  vi.clearAllMocks()
  state = {
    questions: [
      { id: 'R1', axis: 'R', text: '修理小物件' },
      { id: 'I1', axis: 'I', text: '研究事物原理' },
    ],
    answers: { R1: 4 },
    revision: 1,
    completed: false,
    scores: {},
    code: '',
  }
  vi.mocked(api.getQuestionnaire).mockImplementation(async () => structuredClone(state))
  vi.mocked(api.saveQuestionnaireAnswer).mockImplementation(async (id, value) => {
    state.answers[id] = value
    state.revision++
    return structuredClone(state)
  })
})
afterEach(() => {
  app?.unmount()
  root?.remove()
})
it('resumes at first missing card and retains it on save failure before retry', async () => {
  mount()
  await flush()
  expect(root.textContent).toContain('研究事物原理')
  vi.mocked(api.saveQuestionnaireAnswer).mockRejectedValueOnce(new Error('保存失败'))
  button('非常喜欢').click()
  await flush()
  expect(root.querySelector('[role=alert]')?.textContent).toContain('保存失败')
  expect(root.textContent).toContain('研究事物原理')
  button('非常喜欢').click()
  await flush()
  expect(api.saveQuestionnaireAnswer).toHaveBeenLastCalledWith('I1', 5)
  expect(api.completeQuestionnaire).not.toHaveBeenCalled()
  button('上一题').click()
  await flush()
  expect(button('非常喜欢').getAttribute('aria-pressed')).toBe('true')
})
it('explicitly confirms the saved revision then emits refreshed merged profile', async () => {
  state.questions = Array.from({ length: 30 }, (_, i) => ({
    id: `q${i}`,
    axis: 'I' as const,
    text: `活动${i}`,
  }))
  state.answers = Object.fromEntries(state.questions.map((q) => [q.id, 4]))
  state.revision = 30
  const assessment: api.AssessmentConfirmed = {
    riasec: { R: 20, I: 100, A: 75, S: 50, E: 0, C: 0 },
    code: 'IAS',
    traits: [{ name: '细致', confidence: 1, evidence: '' }],
    constraints: { city: '杭州', salary: '12k', overtime: '双休' },
  }
  vi.mocked(api.completeQuestionnaire)
    .mockRejectedValueOnce(new Error('暂时失败'))
    .mockResolvedValue({ ...state, code: 'IAS', completed: true })
  vi.mocked(api.getCareerProfile).mockResolvedValue({
    target: '',
    city: '杭州',
    skills: '',
    interests: [],
    major: '',
    courses: '',
    valuesText: '',
    completedTasks: [],
    updatedAt: null,
    assessment,
  })
  mount()
  await flush()
  button('确认兴趣并写入画像').click()
  await flush()
  expect(root.textContent).toContain('暂时失败')
  expect(confirmed).not.toHaveBeenCalled()
  button('确认兴趣并写入画像').click()
  await flush()
  expect(api.completeQuestionnaire).toHaveBeenLastCalledWith(30)
  expect(confirmed).toHaveBeenCalledWith(assessment)
  expect(root.textContent).toContain('已写入画像，兴趣码 IAS')
})
it('shows load and offline states and prevents offline writes', async () => {
  vi.mocked(api.getQuestionnaire).mockRejectedValueOnce(new Error('读取失败'))
  mount()
  await flush()
  expect(root.textContent).toContain('读取失败')
  button('重新读取').click()
  await flush()
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  window.dispatchEvent(new Event('offline'))
  await flush()
  expect(root.textContent).toContain('当前离线')
  expect(button('非常喜欢').disabled).toBe(true)
  vi.restoreAllMocks()
})

it('shows an undifferentiated result without inventing an interest code', async () => {
  state.completed = true
  state.code = ''
  mount()
  await flush()
  expect(root.textContent).toContain('已写入画像，兴趣码 未分化')
})

afterEach(() => setLanguage('zh-CN'))

it('translates by stable ID on a resumed card and preserves its saved rating', async () => {
  mount()
  await flush()
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('Design a small experiment to test an idea')
  button('Strongly like').click()
  await flush()
  expect(api.saveQuestionnaireAnswer).toHaveBeenLastCalledWith('I1', 5)
  button('Previous question').click()
  await flush()
  expect(button('Strongly like').getAttribute('aria-pressed')).toBe('true')
  setLanguage('zh-CN')
  await nextTick()
  expect(root.textContent).toContain('研究事物原理')
  expect(button('非常喜欢').getAttribute('aria-pressed')).toBe('true')
})
