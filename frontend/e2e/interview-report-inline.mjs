// Run: node frontend/e2e/interview-report-inline.mjs
// Uses an external Playwright installation; no project dependency or live model is required.
import process from 'node:process'
import { mkdir } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright/test',
)
const frontend = fileURLToPath(new URL('..', import.meta.url))
const output =
  process.env.SCREENSHOT_DIR || '/workspace/docs-eval-local/iv-report-inline-2026-10-20'
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:6198'
const report = {
  id: 'iv_inline',
  overall: 76,
  dimensions: { professional: 80, expression: 76, logic: 72, jobFit: 76 },
  turns: [
    {
      question: '如何复盘一次活动？',
      answerSummary: '我负责整理用户反馈，并和同学一起复盘活动流程。',
      score: 76,
      star: { situation: true, task: true, action: true, result: false },
      feedback: '行动具体，尚未说明如何验证活动效果。',
      betterAnswerOutline: [],
      source: 'model',
      evaluation: {
        source: 'model',
        score: 76,
        rationale: '能说明自己的贡献，需要补充真实验证结果。',
        improvedAnswer:
          '活动结束后，我负责整理用户反馈，并和同学一起复盘活动流程。\n结果：【补充真实验证结果】',
        dimensions: Object.fromEntries(
          ['professional', 'expression', 'logic', 'jobFit'].map((key) => [
            key,
            {
              score: key === 'professional' ? 80 : key === 'logic' ? 72 : 76,
              rationale: '职责清晰，结果证据待补充。',
              evidence: '我负责整理用户反馈',
            },
          ]),
        ),
      },
    },
    {
      question: '如何判断内容是否满足用户需求？',
      answerSummary: '',
      score: null,
      star: { situation: false, task: false, action: false, result: false },
      feedback: '本题未作答，不评分。',
      betterAnswerOutline: [],
      source: 'unavailable',
    },
    {
      question: '如何确定下一次活动的优先级？',
      answerSummary: '',
      score: null,
      star: { situation: false, task: false, action: false, result: false },
      feedback: '本题未作答，不评分。',
      betterAnswerOutline: [],
      source: 'unavailable',
    },
  ],
  strengths: ['能清楚说明自己的贡献'],
  weaknesses: ['活动效果验证不足'],
  advice: ['用真实反馈或结果证据重答第一题'],
  recommendedLearning: [{ topic: '活动效果验证', reason: '第1题缺少验证', estimatedHours: 1 }],
  note: '模型评分0–100；已评分1/3题；未作答不计分。确定性接口夹具，仅验证交互。',
}
let server, browser
try {
  await mkdir(output, { recursive: true })
  if (!process.env.E2E_BASE_URL) {
    server = spawn(
      process.execPath,
      ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '6198', '--strictPort'],
      {
        cwd: frontend,
        env: { ...process.env, VITE_ENABLE_MOCK: 'false' },
        stdio: 'inherit',
      },
    )
    let ready = false
    for (let i = 0; i < 80; i++) {
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
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
  for (const [name, viewport] of [
    ['desktop', { width: 1280, height: 800 }],
    ['mobile', { width: 390, height: 844 }],
  ]) {
    const context = await browser.newContext({ viewport })
    await context.addInitScript(() =>
      localStorage.setItem('offerpilot-guest-v1', 'deterministic-e2e-guest'),
    )
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    const session = {
      id: 'iv_inline',
      role: '新媒体运营',
      kind: '综合',
      questionIndex: 0,
      completed: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      generationSource: 'model',
      options: {
        difficulty: 'STANDARD',
        mode: 'TEXT',
        durationMinutes: 30,
        questionCount: 3,
        feedbackMode: 'END',
      },
      answers: report.turns.map((turn, questionIndex) => ({
        questionIndex,
        questionText: turn.question,
        answerText: turn.answerSummary,
        questionType: 'CASE',
        difficulty: 'STANDARD',
        questionSource: 'model',
      })),
    }
    let tasks = [],
      starts = 0,
      state = 'running',
      saved = false
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request(),
        url = new URL(request.url()),
        path = url.pathname.replace('/api/v1', '')
      const ok = (data) => route.fulfill({ json: { code: 0, data, traceId: 'e2e-fixture' } })
      if (path === '/generation-tasks' && request.method() === 'POST') {
        const body = request.postDataJSON()
        expect(body.kind).toBe('INTERVIEW_REPORT')
        expect(body.entityId).toBe(session.id)
        starts++
        tasks.unshift({
          id: `gen_${starts}`,
          kind: body.kind,
          entityId: session.id,
          status: 'RUNNING',
          title: '面试整体报告',
          href: `/interviews/iv_inline?taskId=gen_${starts}`,
          stage: '正在整理逐题评价与改进建议',
          resultId: null,
          error: null,
          createdAt: `2026-10-20T10:00:0${starts}Z`,
          updatedAt: new Date().toISOString(),
          startedAt: new Date().toISOString(),
          finishedAt: null,
        })
        return ok(tasks[0])
      }
      if (path === '/generation-tasks') {
        if (tasks[0]) {
          tasks[0].status =
            state === 'failed' ? 'FAILED' : state === 'done' ? 'SUCCEEDED' : 'RUNNING'
          tasks[0].error = state === 'failed' ? '报告生成中断，请重试；回答已保存。' : null
          if (state === 'done') {
            tasks[0].stage = '结果已保存'
            saved = true
          }
        }
        const kind = url.searchParams.get('kind'),
          entity = url.searchParams.get('entityId')
        return ok(
          tasks.filter((t) => (!kind || t.kind === kind) && (!entity || t.entityId === entity)),
        )
      }
      if (path.startsWith('/generation-tasks/'))
        return ok(path.endsWith('/result') ? report : tasks.find((t) => path.endsWith(t.id)))
      if (path === '/interviews/iv_inline/report')
        return ok(saved ? report : { ...report, overall: null, turns: [], recommendedLearning: [] })
      if (path === '/interviews/iv_inline') {
        if (request.method() === 'PATCH') Object.assign(session, request.postDataJSON())
        return ok(session)
      }
      if (path === '/interviews') return ok([session])
      if (path === '/resumes') return ok({ items: [], total: 0, page: 1, size: 20 })
      if (path.startsWith('/star-stories'))
        return ok(path.includes('candidates') ? [] : { items: [], limit: 30 })
      if (path === '/applications') return ok([])
      if (path === '/career/learning-plans/from-interview/iv_inline') return ok({ id: 'lp_inline' })
      // Shell-only endpoints have no effect on interview/report generation.
      if (path === '/auth/quota' || path === '/auth/me') return ok({})
      // Unrelated shell endpoints (preferences, notifications, ...) get an empty success.
      console.warn(`stub default for ${request.method()} ${path}`)
      return ok(request.method() === 'GET' ? null : {})
    })
    const panel = page.getByRole('region', { name: '面试报告与学习计划' })
    async function shot(stateName) {
      await page.evaluate(() => document.fonts.ready)
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true)
      await page.screenshot({ path: `${output}/${name}-${stateName}.png`, fullPage: true })
    }
    await page.goto(`${base}/interviews/iv_inline`)
    await expect(page.getByRole('button', { name: '结束并生成报告', exact: true })).toBeVisible()
    await shot('interview')
    await page.getByRole('button', { name: '结束并生成报告', exact: true }).click()
    await expect(panel.getByRole('heading', { name: '正在生成面试报告' })).toBeVisible()
    await expect(panel.getByText('面试评分与复盘')).toHaveCount(0)
    await expect.poll(() => starts).toBe(1)
    await shot('progress')
    // Reload during generation proves durable task restoration without submitting another task.
    await page.reload()
    await expect(panel.getByRole('heading', { name: '正在生成面试报告' })).toBeVisible()
    expect(starts).toBe(1)
    state = 'failed'
    await expect(panel.getByRole('heading', { name: '报告生成失败' })).toBeVisible({
      timeout: 20000,
    })
    await shot('failed-retry')
    state = 'running'
    await panel.getByRole('button', { name: '重试生成报告' }).click()
    await expect(panel.getByRole('heading', { name: '正在生成面试报告' })).toBeVisible()
    await expect.poll(() => starts).toBe(2)
    await shot('retry-progress')
    state = 'done'
    await expect(panel.getByRole('heading', { name: '面试评分与复盘' })).toBeVisible({
      timeout: 20000,
    })
    await expect(page.getByRole('complementary', { name: '后台生成进度' })).toContainText('已完成')
    await expect(panel.getByText('活动效果验证不足', { exact: true })).toBeVisible()
    await expect(panel.getByText('总分', { exact: true })).toBeVisible()
    await expect(panel.getByText('本题未作答，不评分。', { exact: false }).first()).toBeVisible()
    await shot('report')
    await panel.getByRole('button', { name: '一键生成学习计划' }).click()
    await expect(panel.getByRole('link', { name: '查看学习计划' })).toHaveAttribute(
      'href',
      '/career?plan=lp_inline',
    )
    await page.getByRole('link', { name: '返回练习列表', exact: true }).click()
    await expect(page.getByRole('link', { name: '查看报告', exact: true })).toBeVisible()
    await shot('history')
    await page.getByRole('link', { name: '查看报告', exact: true }).click()
    await expect(panel.getByRole('heading', { name: '面试评分与复盘' })).toBeVisible()
    await expect(page.locator('textarea')).toHaveCount(0)
    expect(starts).toBe(2)
    await shot('history-report')
    expect(errors).toEqual([])
    await context.close()
  }
  process.stdout.write(`Desktop/mobile report lifecycle passed; screenshots: ${output}\n`)
} finally {
  await browser?.close()
  server?.kill()
}
