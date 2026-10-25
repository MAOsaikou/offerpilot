// Run: node frontend/e2e/demo-slick-record.mjs
// Deterministic API fixtures and Playwright recordVideo. No backend or live model.
import process from 'node:process'
import { spawn, spawnSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { mkdir, copyFile, readdir, stat, unlink } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const playwrightModule = process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright/test'
const { chromium } = require(playwrightModule)
const frontend = fileURLToPath(new URL('..', import.meta.url))
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:6208'
const outDir = process.env.DEMO_VIDEO_DIR || '/tmp/offerpilot-demo-slick'
const target = join(outDir, 'offerpilot-demo-slick.webm')
const mp4 = join(outDir, 'offerpilot-demo-slick.mp4')
const pace = Number(process.env.DEMO_PACE || '1')
const skipBoard = process.env.DEMO_SKIP_BOARD === '1'
const now = '2026-10-25T09:00:00Z'
const dueToday = new Intl.DateTimeFormat('sv-SE').format(new Date())
const user = {
  id: 'demo-slick',
  email: 'demo@example.test',
  nickname: '林同学',
  roles: ['USER'],
  isDemo: true,
}
const growthPath = '运营专员 → 产品运营 → 高级产品运营 → 运营负责人'
const replyText =
  '可以从你已经做过的活动复盘开始：先写清场景和你的动作，再用转化率说明结果。这样职业方向和简历项目会对得上。'

const workspaceSeed = {
  version: 1,
  name: '林同学',
  target: '产品运营',
  city: '上海',
  skills: '用户研究、数据分析、内容运营',
  interests: ['产品策划', '数据分析'],
  resumes: [],
  applications: [],
  interviews: [
    {
      id: 'local_iv',
      role: '产品运营',
      kind: '综合',
      date: '2026-10-20',
      answers: ['我负责活动报名流程，并根据问卷把转化率提高了 18%。'],
      question: 0,
      completed: true,
    },
  ],
  notes: [],
  tasks: ['梳理目标岗位的核心技能', '整理一个项目的 STAR 故事'],
  notices: [],
}

const profile = {
  target: '产品运营',
  city: '上海',
  skills: '用户研究、数据分析、内容运营',
  interests: ['产品策划', '数据分析'],
  major: '信息管理与信息系统',
  courses: '用户研究\n数据分析',
  valuesText: '持续成长\n把用户反馈做成可执行的改进',
  evidence: [],
  assessment: {
    riasec: { R: 20, I: 55, A: 40, S: 70, E: 85, C: 35 },
    code: 'ESC',
    traits: [{ name: '组织活动', confidence: 0.8, evidence: '策划校园活动并复盘转化' }],
    constraints: { city: '上海', salary: '8–15K/月', overtime: '尽量避免' },
  },
  completedTasks: ['梳理目标岗位的核心技能', '整理一个项目的 STAR 故事'],
  updatedAt: now,
}

const structured = {
  basics: {
    name: '林同学',
    title: '产品运营实习生',
    phone: '',
    email: 'demo@example.test',
    city: '上海',
    links: [],
  },
  education: [
    {
      school: '上海大学',
      degree: '本科',
      major: '信息管理与信息系统',
      start: '2022.09',
      end: '2026.06',
      gpa: '',
    },
  ],
  experience: [
    {
      org: '校园创新中心',
      title: '活动运营实习生',
      start: '2025.06',
      end: '2025.09',
      bullets: [
        '策划 3 场用户活动，累计触达 600+ 人',
        '根据问卷反馈改报名流程，转化率提升 18%',
      ],
    },
  ],
  projects: [
    {
      name: '校园活动内容增长',
      role: '活动运营',
      start: '2025.06',
      end: '2025.09',
      tech: ['问卷', '数据复盘'],
      bullets: ['从用户反馈到报名流程改进，形成一次可展示的活动复盘。'],
    },
  ],
  skills: [{ category: '运营', items: ['用户研究', '数据分析', '内容运营'] }],
  certificates: ['大学英语六级'],
  summary: '关注用户需求与内容增长，擅长用数据复盘活动效果。',
}
const resumeVersion = {
  id: 'version_demo',
  versionNo: 2,
  parentVersionId: null,
  source: 'MANUAL_EDIT',
  structured,
  plainText: structured.summary,
  parseMeta: {},
  createdAt: now,
}
const resume = {
  id: 'resume_demo',
  title: '产品运营实习简历',
  parseStatus: 'READY',
  parseProgress: 100,
  parseError: null,
  currentVersionId: resumeVersion.id,
  currentVersion: resumeVersion,
  fileName: '林同学-简历.pdf',
  createdAt: now,
  updatedAt: now,
}
const session = {
  id: 'interview_demo',
  role: '产品运营',
  kind: '综合',
  questionIndex: 0,
  completed: true,
  createdAt: now,
  updatedAt: now,
  generationSource: 'model',
  options: {
    difficulty: 'STANDARD',
    mode: 'TEXT',
    durationMinutes: 30,
    questionCount: 1,
    feedbackMode: 'END',
  },
  answers: [
    {
      questionIndex: 0,
      questionText: '请介绍一次你推动活动增长的经历。',
      answerText: '我通过用户调研优化活动报名流程，最终转化率提升 18%。',
      questionType: 'CASE',
      difficulty: 'STANDARD',
      questionSource: 'model',
    },
  ],
}
const report = {
  id: 'interview_demo',
  overall: 82,
  dimensions: { professional: 84, expression: 80, logic: 82, jobFit: 82 },
  turns: [
    {
      question: '请介绍一次你推动活动增长的经历。',
      answerSummary: '我通过用户调研优化活动报名流程，最终转化率提升 18%。',
      score: 84,
      star: { situation: true, task: true, action: true, result: true },
      feedback: '结构清晰，结果有数据支撑。',
      betterAnswerOutline: [],
      source: 'model',
      evaluation: {
        source: 'model',
        score: 84,
        rationale: '行动具体，结果可量化。',
        improvedAnswer: '先说明背景，再展开行动与结果。',
        dimensions: {},
      },
    },
  ],
  strengths: ['用户意识强', '结果表达具体'],
  weaknesses: ['可补充跨团队协作细节'],
  advice: ['下一步补充协作过程与决策依据'],
  recommendedLearning: [{ topic: '活动复盘表达', reason: '把协作过程写进结果', estimatedHours: 1 }],
  note: '演示用确定性接口夹具，评分不是实时模型结果。',
}
const occupations = [
  {
    occupationId: 'product-ops',
    name: '产品运营',
    family: '产品与设计',
    riasec: { R: 0.2, I: 0.65, A: 0.55, S: 0.6, E: 0.7, C: 0.45 },
    coreSkills: [{ name: '用户研究' }, { name: '数据分析' }, { name: '内容运营' }],
    salaryRange: { campus: '8–15K/月', source: '演示数据' },
    growthPath,
    source: '演示资料',
    match: 0.86,
  },
  {
    occupationId: 'data-analyst',
    name: '数据分析师',
    family: '数据与洞察',
    riasec: { R: 0.15, I: 0.8, A: 0.35, S: 0.4, E: 0.45, C: 0.7 },
    coreSkills: [{ name: 'SQL' }, { name: '指标设计' }],
    salaryRange: { campus: '9–16K/月', source: '演示数据' },
    growthPath: '数据分析实习生 → 数据分析师 → 高级数据分析师',
    source: '演示资料',
    match: 0.74,
  },
]
const revision = {
  id: 'revision_demo',
  parentId: null,
  constraints: { city: '上海', minSalaryK: null, avoidOvertime: false, research: false },
  items: occupations.map((occupation, index) => ({
    occupation,
    baseScore: occupation.match,
    score: occupation.match,
    rank: index + 1,
    rankDelta: 0,
    changeReason: index === 0 ? '与你的活动经历和兴趣契合' : '能承接活动数据复盘',
    details: {
      evidence: [
        { field: '活动运营', value: '转化率提升 18%', source: '简历经历' },
        { field: '兴趣', value: '产品策划、数据分析', source: '职业画像' },
      ],
      typicalTitles: [occupation.name],
      coreSkills: occupation.coreSkills.map((skill) => skill.name),
      growthPath: occupation.growthPath,
      salaryRange: occupation.salaryRange.campus,
      companies: ['创新型互联网团队'],
      concerns: '持续积累可展示的项目复盘',
      sourceOccupationId: occupation.name,
      source: '演示目录',
    },
  })),
  elapsedMs: 18,
  explanationFallback: false,
  createdAt: now,
}
const plan = {
  revision: 'plan_demo',
  target: '产品运营',
  city: '上海',
  status: 'CONFIRMED',
  notice: '演示用确定性建议。',
  result: {
    jobs: {
      items: [{ id: 'job_ops', title: '产品运营实习生', company: '星河同学', source: 'demo' }],
      meta: { partial: false },
    },
    skillGap: {
      skills: [{ name: '数据分析', level: 'PARTIAL' }],
      notice: '活动数据复盘还可以再具体。',
    },
    learning: {
      summary: '用一次活动复盘把转化率讲清楚。',
      roadmap: {
        weeks: [
          {
            index: 1,
            theme: '把结果写具体',
            items: [{ id: 'w1', title: '补一页活动复盘', hours: 3 }],
          },
        ],
      },
    },
  },
}

function job(fields) {
  return {
    externalId: fields.id,
    site: 'demo',
    locations: '上海',
    category: '产品',
    nature: '实习',
    department: '用户增长',
    updatedAt: now,
    url: `https://example.test/jobs/${fields.id}`,
    source: 'demo',
    description: '演示岗位，不是真实招聘。',
    requirement: '关注用户反馈，并能把结果说清楚。',
    detailFetchedAt: now,
    skills: ['用户研究', '数据分析'],
    ...fields,
  }
}
const applications = [
  {
    id: 'app_ops',
    jobId: 'job_ops',
    status: 'INTERVIEW',
    note: '准备用活动复盘讲转化率。',
    nextAction: '整理 3 分钟活动复盘',
    nextActionAt: dueToday,
    createdAt: now,
    updatedAt: now,
    job: job({ id: 'job_ops', company: '星河同学', title: '产品运营实习生' }),
  },
  {
    id: 'app_data',
    jobId: 'job_data',
    status: 'APPLIED',
    note: '',
    nextAction: '补一版项目数据说明',
    nextActionAt: null,
    createdAt: now,
    updatedAt: now,
    job: job({ id: 'job_data', company: '望山数据', title: '运营数据分析实习' }),
  },
  {
    id: 'app_saved',
    jobId: 'job_saved',
    status: 'TODO',
    note: '',
    nextAction: '',
    nextActionAt: null,
    createdAt: now,
    updatedAt: now,
    job: job({ id: 'job_saved', company: '一页设计', title: '用户研究助理' }),
  },
]

const questionnaireAnswers = Object.fromEntries(
  Array.from({ length: 30 }, (_, index) => [`q${index + 1}`, 4]),
)
let conversations = []
let chatMessages = []
let server
let browser
let context

function token() {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url')
  const payload = Buffer.from(
    JSON.stringify({ sub: user.id, exp: 4_102_444_800 }),
  ).toString('base64url')
  return `${header}.${payload}.unsigned`
}
function loginResult() {
  return { accessToken: token(), expiresIn: 3600, user }
}
function profileBody() {
  return { ...profile, updatedAt: now }
}
function ok(data) {
  return { code: 0, message: 'ok', data, traceId: 'demo-slick' }
}
function sse(events) {
  return events
    .map((event, index) => {
      const payload = { id: index + 1, ts: now, runId: 'run_demo', ...event }
      return `id: ${index + 1}\ndata: ${JSON.stringify(payload)}\n\n`
    })
    .join('')
}
function message(id, role, text) {
  return {
    id,
    conversationId: 'conv_demo',
    role,
    parts: [{ type: 'text', text }],
    plainText: text,
    runId: 'run_demo',
    model: role === 'ASSISTANT' ? 'deepseek-flash' : '',
    status: 'COMPLETED',
    createdAt: now,
    usage: null,
  }
}
function fixture(method, path, url, rawBody) {
  let body = null
  if (rawBody) {
    try {
      body = JSON.parse(rawBody)
    } catch {
      body = null
    }
  }
  if (path === '/auth/demo-login' || path === '/auth/refresh') return loginResult()
  if (path === '/auth/me' || path === '/auth/verify')
    return { valid: true, userId: user.id, roles: user.roles, nickname: user.nickname, user }
  if (path === '/auth/quota' || path === '/auth/logout') return null
  if (path === '/preferences')
    return { defaultModelTier: 'FLASH', theme: 'light', language: 'zh-CN', piiMasking: true, saved: true }
  if (path === '/notifications/unread-count') return 0
  if (path === '/generation-tasks' || path === '/agent/approvals/pending') return []
  if (path === '/applications') return applications
  if (path === '/jobs/imported') return []
  if (path.startsWith('/applications/'))
    return {
      application: applications.find((item) => path.endsWith(item.id)) ?? applications[0],
      latestMatch: null,
      resumeOptimizations: [],
      interviews: [],
      starStories: [],
      timeline: [],
      linkageNote: '',
    }
  if (path === '/career/profile' || path === '/career/tasks') {
    if (method === 'PUT' && body) {
      if (path.endsWith('/tasks')) profile.completedTasks = body.completed ?? profile.completedTasks
      else Object.assign(profile, body)
    }
    return profileBody()
  }
  if (path === '/career/assessment')
    return {
      questions: ['你更享受哪类任务？'],
      answers: ['把想法做成用户能参加的活动。'],
      inference: null,
      confirmed: profile.assessment,
      confirmedAt: now,
    }
  if (path === '/career/questionnaire')
    return {
      questions: [],
      answers: questionnaireAnswers,
      revision: 1,
      completed: true,
      scores: { E: 4, S: 4, C: 3 },
      code: 'ESC',
    }
  if (path === '/career/occupations')
    return occupations.map(({ occupationId, name }) => ({ occupationId, name }))
  if (path === '/career/recommendations' || path === '/career/recommendations/revision_demo')
    return revision
  if (path === '/career/target-plan') return plan
  if (path === '/career/profile-suggestions') return []
  if (path === '/career/learning-plans') return []
  if (path.startsWith('/career/learning-plans/updates/')) return []
  if (path === '/resumes') return { items: [resume], total: 1, page: 1, size: 20 }
  if (path === '/resumes/resume_demo') return resume
  if (path === '/resumes/resume_demo/versions') return [resumeVersion]
  if (path === '/resumes/resume_demo/diagnoses' || path === '/resumes/resume_demo/optimizations')
    return []
  if (path === '/interviews') return [session]
  if (path === '/interviews/interview_demo') return session
  if (path === '/interviews/interview_demo/report') return report
  if (path === '/star-stories') return { items: [], limit: 30 }
  if (path.startsWith('/star-stories/candidates'))
    return [
      {
        questionIndex: 0,
        questionText: session.answers[0].questionText,
        title: '活动报名转化',
        body: session.answers[0].answerText,
        role: '产品运营',
        starCount: 4,
        situation: true,
        task: true,
        action: true,
        result: true,
        alreadySaved: false,
      },
    ]
  if (path === '/conversations' && method === 'GET')
    return { items: conversations, page: 1, size: 20, total: conversations.length }
  if (path === '/conversations' && method === 'POST') {
    const created = {
      id: 'conv_demo',
      title: '活动增长怎么讲',
      mode: body?.mode || 'AUTO',
      pinned: false,
      createdAt: now,
      updatedAt: now,
    }
    conversations = [created]
    chatMessages = []
    return created
  }
  if (path === '/conversations/conv_demo') return conversations[0]
  if (path.startsWith('/conversations/conv_demo/messages'))
    return { items: chatMessages, hasMore: false }
  if (path === '/agent/chat') return { sse: true, content: body?.content || '' }
  if (path === '/privacy-settings/check') return { detected: false, warning: null }
  if (path === '/resumes/github')
    return { username: '', tokenConfigured: false, status: 'NOT_CONFIGURED', notice: '', snapshot: null }
  console.warn(`fixture safe-empty: ${method} ${path}${url.search}`)
  if (method !== 'GET') return {}
  if (path.endsWith('/messages') || path === '/conversations')
    return { items: [], page: 1, size: 20, total: 0, hasMore: false }
  return null
}

function chromePath() {
  const candidates = [
    process.env.CHROME_BIN,
    process.env.CHROMIUM_PATH,
    '/opt/google/chrome/chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ].filter(Boolean)
  return candidates.find((candidate) => existsSync(candidate))
}
function hold(ms) {
  const scale = Number.isFinite(pace) && pace > 0 ? pace : 1
  return delay(Math.round(ms * scale))
}
function remux() {
  const ffmpeg = spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' })
  if (ffmpeg.error || ffmpeg.status !== 0) {
    console.log('ffmpeg not available; kept webm only')
    return
  }
  const copy = spawnSync('ffmpeg', ['-y', '-i', target, '-c', 'copy', mp4], { stdio: 'ignore' })
  if (copy.status === 0) {
    console.log(`MP4 ${mp4} (remux)`)
    return
  }
  const transcode = spawnSync(
    'ffmpeg',
    [
      '-y',
      '-i',
      target,
      '-c:v',
      'libx264',
      '-pix_fmt',
      'yuv420p',
      '-movflags',
      '+faststart',
      '-an',
      mp4,
    ],
    { stdio: 'ignore' },
  )
  if (transcode.status === 0) console.log(`MP4 ${mp4} (transcode)`)
  else console.warn('ffmpeg could not create mp4; webm is still valid')
}

try {
  await mkdir(outDir, { recursive: true })
  const stale = await readdir(outDir).catch(() => [])
  await Promise.all(
    stale
      .filter((name) => name === 'failure.png' || /^[0-9a-f]{32}\.webm$/.test(name))
      .map((name) => unlink(join(outDir, name)).catch(() => undefined)),
  )
  await Promise.all([unlink(target), unlink(mp4)].map((pending) => pending.catch(() => undefined)))
  if (!process.env.E2E_BASE_URL) {
    server = spawn(
      process.execPath,
      ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '6208', '--strictPort'],
      { cwd: frontend, env: { ...process.env, VITE_ENABLE_MOCK: 'false' }, stdio: 'inherit' },
    )
    let ready = false
    for (let attempt = 0; attempt < 80; attempt += 1) {
      if (server.exitCode !== null) throw new Error(`Vite exited ${server.exitCode}`)
      try {
        ready = (await fetch(base)).ok
      } catch {
        /* Wait for Vite. */
      }
      if (ready) break
      await delay(250)
    }
    if (!ready) throw new Error('Vite did not start within 20s')
  }
  const executablePath = chromePath()
  browser = await chromium.launch({
    executablePath,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: { dir: outDir, size: { width: 1280, height: 800 } },
    locale: 'zh-CN',
  })
  await context.addInitScript((seed) => {
    const key = `offerpilot-workspace-v1:user:${encodeURIComponent('demo-slick')}`
    localStorage.setItem(key, JSON.stringify(seed))
    const cursor = document.createElement('div')
    cursor.id = 'demo-record-cursor'
    cursor.setAttribute('aria-hidden', 'true')
    Object.assign(cursor.style, {
      position: 'fixed',
      zIndex: '2147483647',
      width: '18px',
      height: '18px',
      marginLeft: '-9px',
      marginTop: '-9px',
      borderRadius: '999px',
      border: '2px solid #fff',
      background: 'rgba(47, 84, 235, 0.9)',
      boxShadow: '0 1px 4px rgba(15, 23, 42, 0.35)',
      pointerEvents: 'none',
      left: '64px',
      top: '64px',
    })
    const mount = () => document.documentElement.append(cursor)
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount)
    else mount()
    window.addEventListener(
      'mousemove',
      (event) => {
        cursor.style.left = `${event.clientX}px`
        cursor.style.top = `${event.clientY}px`
      },
      true,
    )
  }, workspaceSeed)
  const page = await context.newPage()
  const recording = page.video()
  const pageErrors = []
  page.on('pageerror', (error) => {
    pageErrors.push(error.message)
    console.error('pageerror:', error.message)
  })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/\/api\/v1(?=\/|$)/, '') || '/'
    const data = fixture(request.method(), path, url, request.postData())
    if (data && data.sse) {
      const content = String(data.content)
      chatMessages = [message('msg_user', 'USER', content), message('msg_assistant', 'ASSISTANT', replyText)]
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream; charset=utf-8',
        body: sse([
          {
            type: 'run.started',
            conversationId: 'conv_demo',
            messageId: 'msg_assistant',
            model: 'deepseek-flash',
            mode: 'AUTO',
          },
          { type: 'text.delta', text: replyText },
          { type: 'run.completed', messageId: 'msg_assistant', status: 'COMPLETED', durationMs: 640 },
        ]),
      })
      return
    }
    await route.fulfill({ json: ok(data) })
  })

  async function show(locator) {
    const handle = await locator.first().elementHandle()
    if (!handle) throw new Error('Missing element to reveal')
    await page.evaluate((element) => element.scrollIntoView({ behavior: 'smooth', block: 'center' }), handle)
    await handle.dispose()
    await hold(900)
  }
  async function press(locator) {
    const box = await locator.first().boundingBox()
    if (box) {
      await page.mouse.move(box.x + Math.min(box.width / 2, 180), box.y + box.height / 2, { steps: 18 })
      await hold(280)
    }
    await locator.first().click()
  }
  async function beat(label, action) {
    try {
      await action()
    } catch (error) {
      await page.screenshot({ path: join(outDir, 'failure.png'), fullPage: true }).catch(() => undefined)
      const text = await page.locator('body').innerText().catch(() => '')
      throw new Error(`${label}\n${page.url()}\n${text.slice(0, 900)}\n${error instanceof Error ? error.message : error}`)
    }
  }

  await beat('login', async () => {
    await page.goto(`${base}/login`)
    const demo = page.getByRole('button', { name: '体验账号一键登录' })
    await demo.waitFor({ timeout: 15000 })
    await hold(3000)
    await press(demo)
    await page.waitForURL(`${base}/`)
  })
  await beat('dashboard', async () => {
    await page.getByRole('heading', { name: '你好，林同学' }).waitFor()
    await page.getByText('整理 3 分钟活动复盘').waitFor()
    await hold(3400)
    await press(page.getByRole('button', { name: '梳理职业方向' }))
    await hold(1600)
  })
  await beat('chat', async () => {
    await page.goto(`${base}/chat`)
    await page.getByRole('heading', { name: '下一步，我们一起想。' }).waitFor()
    await hold(2400)
    await press(page.getByRole('button', { name: '帮我梳理职业兴趣和方向' }))
    const composer = page.getByRole('textbox', { name: '对话内容' })
    await composer.waitFor()
    await hold(1400)
    await press(page.getByRole('button', { name: '发送' }))
    await page.getByText('再用转化率说明结果').waitFor({ timeout: 15000 })
    await hold(4600)
  })
  await beat('discover', async () => {
    await page.goto(`${base}/discover`)
    const cards = page.getByRole('heading', { name: '从这些方向开始了解' })
    await cards.waitFor()
    await show(cards)
    await hold(2200)
    const product = page.locator('.direction-card', { hasText: '产品与设计' })
    await press(product.getByRole('button', { name: '设为探索目标' }))
    await product.getByRole('button', { name: '当前目标' }).waitFor()
    await hold(2200)
  })
  await beat('career growth path', async () => {
    await page.goto(`${base}/career`)
    const pathText = page.getByText(growthPath).first()
    await pathText.waitFor({ timeout: 15000 })
    await show(pathText)
    await page.getByRole('heading', { name: '职业推荐与对比' }).waitFor()
    await hold(4600)
  })
  await beat('resume', async () => {
    await page.goto(`${base}/resumes/resume_demo`)
    await page.getByRole('heading', { name: '产品运营实习简历' }).waitFor()
    await page.locator('.resume-basics input').first().waitFor()
    const hasOrg = await page.locator('input, textarea').evaluateAll((fields) =>
      fields.some((field) => 'value' in field && String(field.value).includes('校园创新中心')),
    )
    if (!hasOrg) throw new Error('Resume experience did not render')
    await hold(1800)
    await page.evaluate(() => {
      const field = [...document.querySelectorAll('input')].find((input) =>
        input.value.includes('校园创新中心'),
      )
      field?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
    await hold(3200)
  })
  await beat('interview report', async () => {
    await page.goto(`${base}/interviews/interview_demo`)
    await page.getByRole('heading', { name: '面试评分与复盘' }).waitFor({ timeout: 15000 })
    const radar = page.getByRole('region', { name: '面试评分雷达' })
    await radar.waitFor()
    await show(radar.locator('canvas'))
    await hold(4600)
  })
  if (!skipBoard) {
    await beat('board', async () => {
      await page.goto(`${base}/board`)
      await page.getByRole('heading', { name: '每一个机会，都有迹可循' }).waitFor()
      await page.getByText('星河同学').first().waitFor()
      await hold(3400)
    })
  }
  if (pageErrors.length) throw new Error(`Page errors: ${pageErrors.join(' | ')}`)
  await context.close()
  context = null
  const recorded = await recording.path()
  if (recorded !== target) {
    await copyFile(recorded, target)
    await unlink(recorded).catch(() => undefined)
  }
  const size = (await stat(target)).size
  if (size < 20_000) throw new Error(`Video is too small (${size} bytes): ${target}`)
  console.log(`WEBM ${target} (${size} bytes)`)
  remux()
} finally {
  await context?.close()
  await browser?.close()
  if (server && server.exitCode === null) {
    server.kill('SIGTERM')
    await new Promise((resolve) => server.once('exit', resolve))
  }
}
