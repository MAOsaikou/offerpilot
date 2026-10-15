import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import CareerAssessment from './CareerAssessment.vue'
import {
  confirmCareerAssessment,
  getCareerAssessment,
  inferCareerAssessment,
  saveAssessmentAnswer,
  type CareerAssessment as Assessment,
} from '@/shared/api/career'

vi.mock('@/shared/api/career', () => ({
  getCareerAssessment: vi.fn(),
  saveAssessmentAnswer: vi.fn(),
  inferCareerAssessment: vi.fn(),
  confirmCareerAssessment: vi.fn(),
  resetCareerAssessment: vi.fn(),
}))

const questions = ['专业？', '课余？', '成就？', '偏好？', '角色？', '看重？', '条件？']
let server: Assessment
let app: App | undefined
let root: HTMLDivElement
const confirmed = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  server = {
    questions,
    answers: questions.map(() => ''),
    inference: null,
    confirmed: null,
    confirmedAt: null,
  }
  vi.mocked(getCareerAssessment).mockImplementation(async () => structuredClone(server))
  vi.mocked(saveAssessmentAnswer).mockImplementation(async (step, answer) => {
    server.answers[step] = answer
    return structuredClone(server)
  })
  vi.mocked(inferCareerAssessment).mockImplementation(async () => {
    server.inference = {
      riasec: { R: 20, I: 85, A: 10, S: 30, E: 60, C: 25 },
      riasecEvidence: { I: '为了弄懂原理熬夜' },
      traits: [
        { name: '好奇钻研', confidence: 0.8, evidence: '为了弄懂原理熬夜' },
        { name: '主动推动', confidence: 0.6, evidence: '组织了比赛' },
      ],
      constraints: { city: '杭州', salary: '', overtime: '' },
      summary: '爱钻研的组织者',
      source: 'model',
    }
    return structuredClone(server)
  })
  vi.mocked(confirmCareerAssessment).mockImplementation(async (body) => {
    server.confirmed = {
      riasec: { ...body.riasec },
      code: 'IER',
      traits: body.traits.map((name) => ({ name, confidence: 1, evidence: '' })),
      constraints: { city: body.city, salary: body.salary, overtime: body.overtime },
    }
    return structuredClone(server)
  })
})
afterEach(() => {
  app?.unmount()
  root?.remove()
})

async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { render: () => null } }],
  })
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(CareerAssessment, { onConfirmed: confirmed }) }).use(router)
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('专业？'))
}
const button = (text: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(text))!
async function answer(text: string) {
  const area = root.querySelector('textarea')!
  area.value = text
  area.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
}

it('asks one question at a time, infers, lets the user correct and confirms', async () => {
  await mount()
  expect(button('生成我的画像').disabled).toBe(true)
  for (const [index, text] of [
    '计算机大三',
    '为了弄懂原理熬夜',
    '组织了比赛',
    '喜欢钻研',
  ].entries()) {
    await answer(text)
    await vi.waitFor(() => expect(root.textContent).toContain(questions[index + 1]!))
  }
  expect(saveAssessmentAnswer).toHaveBeenCalledTimes(4)
  expect(button('生成我的画像').disabled).toBe(false)
  button('生成我的画像').click()
  await vi.waitFor(() => expect(root.textContent).toContain('当前兴趣码 IES'))
  expect(root.textContent).toContain('依据：「为了弄懂原理熬夜」')
  // Correct the inference: drop a trait, raise E above S, add a self-reported trait.
  root.querySelector<HTMLButtonElement>('[aria-label="删除特质 主动推动"]')!.click()
  const e = root.querySelector<HTMLInputElement>('#riasec-E')!
  e.value = '20'
  e.dispatchEvent(new Event('input'))
  await nextTick()
  expect(root.textContent).toContain('当前兴趣码 ISC')
  const add = root.querySelector<HTMLInputElement>('[aria-label="补充特质"]')!
  add.value = '抗压'
  add.dispatchEvent(new Event('input'))
  await nextTick()
  button('添加').click()
  await nextTick()
  button('确认画像').click()
  await vi.waitFor(() => expect(root.textContent).toContain('画像已确认'))
  expect(confirmCareerAssessment).toHaveBeenCalledWith({
    riasec: { R: 20, I: 85, A: 10, S: 30, E: 20, C: 25 },
    traits: ['好奇钻研', '抗压'],
    city: '杭州',
    salary: '',
    overtime: '',
  })
  expect(confirmed).toHaveBeenCalledWith('杭州')
})
