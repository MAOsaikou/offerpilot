// Run: node frontend/e2e/agent-chat-ux.mjs  (external Playwright, fixture API, no live model)
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
const output = process.env.SCREENSHOT_DIR || '/workspace/docs-eval-local/agent-chat-ux-2026-10-20'
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:6199'
const now = '2026-10-20T10:00:00Z'
const conv = {
  id: 'conv_ux',
  title: '新媒体运营求职规划',
  mode: 'AUTO',
  pinned: false,
  createdAt: now,
  updatedAt: now,
}
const card = (cardId, cardType, payload) => ({ type: 'card', cardId, cardType, payload })
const sources = [
  {
    docId: 'occ_media',
    title: '职业资料 · 新媒体运营',
    snippet: '核心技能：内容策划、数据分析、用户运营。',
    score: null,
    collection: 'career',
    index: 1,
  },
  {
    docId: 'kb_star',
    title: '知识笔记 · STAR 复盘',
    snippet: '用结果数据证明活动效果。',
    score: 0.82,
    collection: 'notes',
    index: 2,
  },
]
const messages = [
  {
    id: 'm1',
    conversationId: 'conv_ux',
    role: 'USER',
    parts: [{ type: 'text', text: '我想做新媒体运营，帮我看看差距和岗位' }],
    plainText: '我想做新媒体运营，帮我看看差距和岗位',
    runId: 'run_ux',
    model: '',
    status: 'COMPLETED',
    createdAt: now,
    usage: null,
  },
  {
    id: 'm2',
    conversationId: 'conv_ux',
    role: 'ASSISTANT',
    runId: 'run_ux',
    model: 'deepseek-flash',
    status: 'COMPLETED',
    createdAt: now,
    plainText:
      '新媒体运营的核心是内容策划和数据分析 [1]。你的简历已体现 SQL，但缺少活动效果的数据证明 [2]。下面是技能雷达、岗位和截止日期。',
    usage: {
      source: 'llm_usage_logs',
      model: 'deepseek-flash',
      promptTokens: 1840,
      completionTokens: 512,
      costEstimate: null,
      durationMs: 2310,
      usageReported: true,
      fallbackUsed: false,
    },
    parts: [
      {
        type: 'text',
        text: '新媒体运营的核心是内容策划和数据分析 [1]。你的简历已体现 SQL，但缺少活动效果的数据证明 [2]。下面是技能雷达、岗位和截止日期。',
      },
      { type: 'citation', sources },
      card('c_radar', 'skill_radar', {
        axes: [
          { name: '内容策划', current: 3, required: 4 },
          { name: '数据分析', current: 3, required: 4 },
          { name: '用户运营', current: 2, required: 4 },
          { name: 'SQL', current: 3, required: 3 },
        ],
      }),
      card('c_cal', 'campus_calendar', {
        items: [
          {
            company: '示例传媒（测试夹具）',
            batch: '',
            positions: ['新媒体运营实习生'],
            deadline: '2026-11-15',
            url: 'https://example.com/job/1',
          },
        ],
      }),
      card('c_turn', 'interview_turn_score', {
        turnIndex: 0,
        score: 76,
        star: { situation: true, task: true, action: true, result: false },
        brief: '能说明贡献，缺少效果验证。',
      }),
    ],
  },
]
let server, browser
try {
  await mkdir(output, { recursive: true })
  if (!process.env.E2E_BASE_URL) {
    server = spawn(
      process.execPath,
      ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '6199', '--strictPort'],
      { cwd: frontend, env: { ...process.env, VITE_ENABLE_MOCK: 'false' }, stdio: 'inherit' },
    )
    let ready = false
    for (let i = 0; i < 80 && !ready; i++) {
      try {
        ready = (await fetch(base)).ok
      } catch {
        /* wait for Vite */
      }
      if (!ready) await delay(250)
    }
    if (!ready) throw new Error('Vite did not start')
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
    page.on('pageerror', (e) => errors.push(e.message))
    await page.route('**/api/v1/**', async (route) => {
      const req = route.request(),
        url = new URL(req.url()),
        path = url.pathname.replace('/api/v1', '')
      const ok = (data) => route.fulfill({ json: { code: 0, data, traceId: 'e2e' } })
      if (path === '/conversations') return ok({ items: [conv], page: 1, size: 20, total: 1 })
      if (path === '/conversations/conv_ux') return ok(conv)
      if (path.startsWith('/conversations/conv_ux/messages'))
        return ok({ items: messages, hasMore: false })
      if (path === '/agent/runs/run_ux/workbench') return ok([])
      return ok(req.method() === 'GET' ? null : {})
    })
    const shot = async (s) => {
      await page.evaluate(() => document.fonts.ready)
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true)
      await page.screenshot({ path: `${output}/${name}-${s}.png`, fullPage: true })
    }
    await page.goto(`${base}/chat/conv_ux`)
    await expect(page.getByText('示例传媒（测试夹具）').first()).toBeVisible({ timeout: 15000 })
    await shot('cards-bottom')
    await page.evaluate(() =>
      document.querySelectorAll('*').forEach((el) => {
        if (
          el.scrollHeight > el.clientHeight + 20 &&
          getComputedStyle(el).overflowY.match(/auto|scroll/)
        )
          el.scrollTop = 0
      }),
    )
    await delay(300)
    await shot('reply-citations-usage')
    await expect(page.getByText('评分数据不可用')).toHaveCount(0)
    const cite = page
      .getByRole('button', { name: /1/ })
      .filter({ hasText: /^\[?1\]?$/ })
      .first()
    if (await cite.count()) {
      await cite.click()
      await delay(400)
      await shot('citation-jump')
    }
    console.log(name, 'errors', errors)
    expect(errors).toEqual([])
    await context.close()
  }
  process.stdout.write(`agent chat UX screenshots: ${output}\n`)
} finally {
  await browser?.close()
  server?.kill()
}
