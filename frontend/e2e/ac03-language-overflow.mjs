// Run after installing dependencies: node frontend/e2e/ac03-language-overflow.mjs
// Uses real Chromium/layout with deterministic API fixtures; no backend or model calls.
// Optional: E2E_BASE_URL, PLAYWRIGHT_MODULE, CHROMIUM_PATH, SCREENSHOT_DIR.
import assert from 'node:assert/strict'
import process from 'node:process'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const require = createRequire(import.meta.url)
const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright/test',
)
const frontend = fileURLToPath(new URL('..', import.meta.url))
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:6193'
const output = process.env.SCREENSHOT_DIR || '/tmp/ac03-browser'
const java = async (name) =>
  readFile(
    new URL(
      `../../backend/offerpilot-career/src/main/java/com/offerpilot/career/application/${name}.java`,
      import.meta.url,
    ),
    'utf8',
  )
const strings = (source) => [...source.matchAll(/"([^"]+)"/g)].map((match) => match[1])
const baseQuestions = strings(
  (await java('CareerAssessmentService')).split('QUESTIONS = List.of(')[1].split(');')[0],
)
const prompts = strings(
  (await java('CareerQuestionnaire')).split('String[][] prompts = {')[1].split('};')[0],
)
const questions = prompts.map((text, i) => ({
  id: `${'RIASEC'[Math.floor(i / 5)]}${(i % 5) + 1}`,
  axis: 'RIASEC'[Math.floor(i / 5)],
  text,
}))
const families = new Set()
for (const file of ['occupations', 'occupations-onet']) {
  const catalog = JSON.parse(
    await readFile(
      new URL(
        `../../backend/offerpilot-bootstrap/src/main/resources/seed/${file}.json`,
        import.meta.url,
      ),
      'utf8',
    ),
  )
  catalog.occupations.forEach((occupation) => families.add(occupation.family))
}
assert.equal(baseQuestions.length, 7)
assert.equal(questions.length, 30)
const occupation = {
  id: 'nurse',
  name: '护士',
  family: '医疗健康',
  description: '核验交接',
  hollandCode: 'SIC',
  riasec: { S: 0.9, I: 0.7, C: 0.6 },
  coreSkills: [],
  salaryRange: null,
  salaryNotice: '暂无数据',
  growthPath: null,
  growthPathSource: null,
  skillsSource: 'onet',
  onetCode: '29-1141.00',
  source: 'onet',
  sourceUrl: null,
  license: 'CC BY 4.0',
  attribution: 'O*NET',
  notice: null,
}
let server, browser
const results = []
try {
  await mkdir(output, { recursive: true })
  if (!process.env.E2E_BASE_URL) {
    server = spawn(
      process.execPath,
      ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '6193', '--strictPort'],
      {
        cwd: frontend,
        stdio: 'inherit',
        env: { ...process.env, VITE_ENABLE_MOCK: 'false' },
      },
    )
    let ready = false
    for (let i = 0; i < 80; i++) {
      if (server.exitCode !== null) throw new Error(`Vite exited ${server.exitCode}`)
      try {
        ready = (await fetch(base)).ok
      } catch {
        /* Wait for Vite startup. */
      }
      if (ready) break
      await delay(250)
    }
    assert.ok(ready, 'Vite did not start within 20 seconds')
  }
  browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox'],
    ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}),
  })
  for (const language of ['zh-CN', 'en-US']) {
    for (const width of [1280, 390]) {
      const context = await browser.newContext({
        locale: language,
        viewport: { width, height: 844 },
      })
      // Each context starts with only a guest credential; no profile or workspace state.
      await context.addInitScript(() =>
        localStorage.setItem('offerpilot-guest-v1', 'ac03-fixture-guest'),
      )
      const page = await context.newPage()
      const errors = [],
        writes = [],
        answers = {}
      let selectedFamily
      page.on('pageerror', (error) => errors.push(error.message))
      const questionnaire = () => ({
        questions,
        answers,
        revision: writes.length,
        completed: false,
        scores: {},
        code: '',
      })
      await page.route('**/api/v1/**', async (route) => {
        const request = route.request(),
          url = new URL(request.url()),
          path = url.pathname.replace('/api/v1', '')
        let data = []
        if (path === '/preferences')
          data = {
            defaultModelTier: 'FLASH',
            theme: 'light',
            language,
            piiMasking: true,
            saved: true,
          }
        else if (path === '/career/profile')
          data = {
            target: '',
            city: '',
            skills: '',
            interests: [],
            major: '',
            courses: '',
            valuesText: '',
            assessment: null,
            completedTasks: [],
            updatedAt: null,
          }
        else if (path === '/career/assessment')
          data = {
            questions: baseQuestions,
            answers: baseQuestions.map(() => ''),
            inference: null,
            confirmed: null,
            confirmedAt: null,
          }
        else if (path.startsWith('/career/questionnaire/answers/')) {
          const id = decodeURIComponent(path.split('/').at(-1))
          const { value } = request.postDataJSON()
          assert.ok(questions.some((question) => question.id === id))
          writes.push({ id, value })
          answers[id] = value
          data = questionnaire()
        } else if (path === '/career/questionnaire') data = questionnaire()
        else if (path === '/career/recommendations' || path === '/career/target-plan') data = null
        else if (path === '/notifications/unread-count') data = 0
        else if (path === '/star-stories') data = { items: [], limit: 30 }
        else if (path === '/resumes') data = { items: [], total: 0, page: 1, size: 20 }
        else if (path === '/resumes/github')
          data = {
            username: '',
            tokenConfigured: false,
            status: 'NOT_CONFIGURED',
            notice: '请填写公开 GitHub 用户名，Token 可选。',
            fetchedAt: null,
            expiresAt: null,
            stale: false,
            snapshot: null,
          }
        else if (path === '/mcp/servers')
          data = [
            {
              provider: 'search',
              name: '搜索',
              added: false,
              enabled: false,
              configured: false,
              status: 'NOT_CONFIGURED',
              tools: [],
              refreshedAt: null,
            },
          ]
        else if (path === '/knowledge/builtin/facets')
          data = {
            families: [...families],
            industries: [],
            roles: [],
            counts: {},
            occupations: 1,
            directOnetOccupations: 1,
          }
        else if (path === '/knowledge/builtin/occupations') {
          selectedFamily = url.searchParams.get('family')
          data = { items: [occupation], total: 1, page: 1, size: 20 }
        }
        await route.fulfill({ json: { data } })
      })
      const go = async (path) => {
        await page.goto(base + path)
        await expect(page.locator('html')).toHaveAttribute('lang', language)
        await expect(page.locator('.page-stack')).toBeVisible()
        await page.evaluate(() => document.fonts.ready)
      }
      const layout = async (state) => {
        const geometry = await page.evaluate(() => ({
          overflow:
            Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
          offenders: [...document.querySelectorAll('main *')]
            .filter((element) => element.getBoundingClientRect().right > innerWidth + 1)
            .map((element) => ({
              tag: element.tagName,
              classes: element.className,
              text: element.textContent.slice(0, 100),
            }))
            .slice(0, 12),
        }))
        assert.equal(
          geometry.overflow,
          0,
          `${language}/${width}/${state}: ${JSON.stringify(geometry)}`,
        )
        results.push({ language, width, state, overflow: geometry.overflow })
        await page.screenshot({
          path: `${output}/${language}-${width}-${state}.png`,
          fullPage: true,
        })
      }
      await go('/discover')
      const current = page.locator('.assessment .ask.current')
      const questionnaireCard = page.locator('[aria-labelledby="questionnaire-title"]')
      await expect(current).toBeVisible()
      await expect(questionnaireCard.locator('h3')).toBeVisible()
      await layout('discover-initial')
      for (const question of baseQuestions) {
        const text = await current.innerText()
        if (language === 'en-US') assert.doesNotMatch(text, /\p{Script=Han}/u)
        else assert.equal(text, question)
        await page
          .locator('.assessment')
          .getByRole('button', {
            name: language === 'en-US' ? 'Skip this question' : '跳过这题',
            exact: true,
          })
          .click()
      }
      for (const question of questions) {
        const heading = questionnaireCard.locator('h3')
        const count = writes.length
        await expect(heading).toBeVisible()
        const text = await heading.innerText()
        if (language === 'en-US') assert.doesNotMatch(text, /\p{Script=Han}/u)
        else assert.ok(text.includes(question.text))
        await questionnaireCard
          .getByRole('button', {
            name: language === 'en-US' ? 'Strongly like' : '非常喜欢',
            exact: true,
          })
          .click()
        await expect.poll(() => writes.length).toBe(count + 1)
        assert.deepEqual(writes.at(-1), { id: question.id, value: 5 })
        await expect(
          questionnaireCard.getByRole('button', {
            name: language === 'en-US' ? 'Previous question' : '上一题',
            exact: true,
          }),
        ).toBeEnabled()
      }
      await layout('discover-complete')
      await go('/interviews')
      await expect(page.locator('input[type="checkbox"]')).toBeVisible()
      await layout('interviews-initial')
      await page.locator('input[type="checkbox"]').check()
      await layout('interviews-custom-mix')
      await go('/settings')
      await expect(page.locator('input[maxlength="40"]')).toHaveValue(
        language === 'en-US' ? 'Job seeker' : '求职者',
      )
      await expect(page.locator('#github-username-help')).toHaveText(
        language === 'en-US'
          ? 'Enter your public GitHub username. A token is optional.'
          : '请填写公开 GitHub 用户名，Token 可选。',
      )
      await go('/integrations/mcp')
      await expect(page.locator('article h2')).toContainText(
        language === 'en-US' ? 'Search MCP' : '搜索 MCP',
      )
      await go('/kb/builtin')
      const filter = page.getByLabel(language === 'en-US' ? 'Career category' : '职业分类', {
        exact: true,
      })
      await expect(filter.locator('option')).toHaveCount(families.size + 1)
      if (language === 'en-US') assert.doesNotMatch(await filter.innerText(), /\p{Script=Han}/u)
      await filter.selectOption('医疗健康')
      await expect.poll(() => selectedFamily).toBe('医疗健康')
      await expect(page.getByText('护士', { exact: true })).toBeVisible()
      assert.deepEqual(errors, [])
      await context.close()
    }
  }
  console.log(JSON.stringify(results, null, 2))
} finally {
  await browser?.close()
  server?.kill('SIGTERM')
}
