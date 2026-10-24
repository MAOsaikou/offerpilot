// Real Chromium layout checks with synthetic HTTP fixtures; no backend/model calls.
// Start Vite first. E2E_BASE_URL, PLAYWRIGHT_MODULE, CHROMIUM_PATH, SCREENSHOT_DIR override defaults.
import assert from 'node:assert/strict'
import process from 'node:process'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'

const require = createRequire(import.meta.url)
const { chromium, expect: defaultExpect } = require(
  process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright/test',
)
const expect = defaultExpect.configure({ timeout: 15000 })
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:14224'
const output = process.env.SCREENSHOT_DIR || '/tmp/offerpilot-ui-polish-browser'
const long = 'LongUnbrokenSourceText'.repeat(12)
const now = '2026-10-24T00:00:00Z'
const memory = { id: 'm1', kind: 'LOCATION', fact: long, source: 'MANUAL' }
const candidate = {
  ...memory,
  id: 'c1',
  confidence: 0.85,
  extractor: 'rule',
  sourceConversationId: 'conversation',
  sourceMessageId: 'message',
}
const task = {
  id: 't1',
  kind: 'JOB_IMPORT',
  entityId: 'job1',
  status: 'RUNNING',
  title: long,
  href: '/jobs',
  stage: long,
  createdAt: now,
  updatedAt: now,
}
const note = {
  id: 'n1',
  title: long,
  content: long,
  category: '技术积累',
  indexStatus: 'READY',
  sourceFilename: long + '.txt',
  createdAt: now,
  updatedAt: now,
}
const application = {
  id: 'a1',
  jobId: 'j1',
  status: 'TODO',
  note: long,
  nextAction: long,
  nextActionAt: null,
  createdAt: now,
  updatedAt: now,
  job: { id: 'j1', title: long, company: long, locations: long },
}
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || '/opt/google/chrome/chrome',
  headless: true,
  args: ['--no-sandbox'],
})
const results = []
await mkdir(output, { recursive: true })
try {
  for (const language of ['zh-CN', 'en-US'])
    for (const width of [1280, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 844 } })
      const page = await context.newPage()
      const errors = [],
        unexpected = []
      let state = 'filled',
        completed = false
      let preferences = {
        defaultModelTier: 'FLASH',
        theme: 'light',
        language,
        piiMasking: true,
        saved: true,
      }
      page.on('pageerror', (error) => errors.push(error.message))
      await context.addInitScript(
        ({ nickname }) => {
          // Unsigned synthetic credential used only by the browser fixture, never a real token.
          localStorage.setItem(
            'offerpilot-access-token',
            `fixture.${btoa(JSON.stringify({ exp: 4102444800 }))}.unsigned`,
          )
          localStorage.setItem(
            'offerpilot-auth-user',
            JSON.stringify({ id: 'polish-fixture', roles: ['USER'], nickname }),
          )
        },
        { nickname: long },
      )
      await page.route('**/api/v1/**', async (route) => {
        const request = route.request()
        const path = new URL(request.url()).pathname.replace('/api/v1', '')
        let data
        if (path === '/preferences') {
          if (request.method() === 'PUT')
            preferences = { ...preferences, ...request.postDataJSON() }
          data = preferences
        } else if (path === '/notifications/unread-count') data = 3
        else if (path === '/generation-tasks')
          data = [{ ...task, status: completed ? 'SUCCEEDED' : 'RUNNING' }]
        else if (path === '/agent/approvals/pending') data = []
        else if (path === '/resumes/github')
          data = {
            username: '',
            tokenConfigured: false,
            status: 'NOT_CONFIGURED',
            notice: '',
            snapshot: null,
          }
        else if (path === '/privacy-settings') data = { piiMasking: true }
        else if (path === '/memories')
          data = { items: state === 'empty' ? [] : [memory], memoryEnabled: true, limit: 50 }
        else if (path === '/memories/candidates') data = state === 'empty' ? [] : [candidate]
        else if (path === '/resume-model-settings')
          data = {
            selection: { textTier: 'FLASH', visionTier: 'FLASH' },
            models: [
              {
                id: 'FLASH',
                label: long,
                provider: 'fixture',
                model: long,
                configured: true,
                supportsVision: true,
              },
            ],
          }
        else if (path === '/knowledge/notes')
          data = { items: state === 'empty' ? [] : [note], total: state === 'empty' ? 0 : 1 }
        else if (path === '/applications') data = state === 'empty' ? [] : [application]
        else {
          unexpected.push(path)
          return route.fulfill({
            status: 404,
            json: { message: `Unexpected fixture API: ${path}` },
          })
        }
        if (
          state === 'loading' &&
          [
            '/memories',
            '/memories/candidates',
            '/privacy-settings',
            '/resume-model-settings',
            '/knowledge/notes',
            '/applications',
          ].includes(path)
        ) {
          await new Promise((resolve) => setTimeout(resolve, 900))
        }
        if (
          state === 'error' &&
          [
            '/memories',
            '/privacy-settings',
            '/resume-model-settings',
            '/knowledge/notes',
            '/applications',
          ].includes(path)
        ) {
          return route.fulfill({ status: 400, json: { code: 400, message: long } })
        }
        await route.fulfill({ json: { code: 0, message: 'ok', data } })
      })
      const inspect = async (name) => {
        // Wait for layout/font settling, without depending on background polling becoming idle.
        await page.evaluate(() => document.fonts.ready)
        const layout = await page.evaluate(() => {
          const root = document.documentElement
          const outside = [...document.querySelectorAll('main *, .generation-dock, .mobile-bar')]
            .filter((el) => {
              if (el.closest('.kanban')) return false // Deliberate, locally scrollable five-column board.
              const rect = el.getBoundingClientRect()
              return rect.width && (rect.right > innerWidth + 1 || rect.left < -1)
            })
            .map((el) => ({ tag: el.tagName, cls: el.className }))
          return { width: root.clientWidth, scrollWidth: root.scrollWidth, outside }
        })
        assert.ok(
          layout.scrollWidth <= layout.width + 1,
          `${name}: document overflow ${JSON.stringify(layout)}`,
        )
        assert.deepEqual(layout.outside, [], `${name}: content outside viewport`)
        await page.screenshot({
          path: `${output}/${language}-${width}-${name}.png`,
          fullPage: true,
        })
        results.push({ language, width, name, ...layout })
      }
      for (const path of [
        '/settings',
        '/settings/models',
        '/settings/connection',
        '/settings/privacy',
        '/kb',
        '/board',
      ]) {
        console.log(language, width, state, path)
        await page.goto(base + path)
        await expect(page.locator('main h1'))
          .toBeVisible()
          .catch(async (error) => {
            await page.screenshot({ path: `${output}/failure.png`, fullPage: true })
            console.error({
              url: page.url(),
              errors,
              unexpected,
              body: (await page.locator('body').innerText()).slice(0, 1200),
            })
            throw error
          })
        if (path === '/settings') {
          await expect(page.locator('.form-grid input[type="checkbox"]')).toBeEnabled()
          const checkbox = await page.locator('.form-grid input[type="checkbox"]').boundingBox()
          assert.ok(
            checkbox.width <= 24 && checkbox.height <= 24,
            'Checkbox must keep native control size',
          )
          // Check in-place language switching, including settings tab state.
          await page
            .locator('select')
            .nth(2)
            .selectOption(language === 'en-US' ? 'zh-CN' : 'en-US')
          await expect(page.locator('html')).toHaveAttribute(
            'lang',
            language === 'en-US' ? 'zh-CN' : 'en-US',
          )
          await page.locator('select').nth(2).selectOption(language)
          await expect(page.locator('html')).toHaveAttribute('lang', language)
        }
        if (path === '/settings/privacy') {
          await expect(page.locator('#memory-heading')).toBeVisible()
          await expect(page.locator('li').filter({ hasText: long })).toBeVisible()
          await page
            .getByRole('button', {
              name: language === 'en-US' ? 'Add memory' : '添加记忆',
              exact: true,
            })
            .click()
          await expect(page.locator('select option[value="LOCATION"]')).toHaveText(
            language === 'en-US' ? 'Target city' : '目标城市',
          )
          await inspect('privacy-editor')
          await page
            .getByRole('button', { name: language === 'en-US' ? 'Cancel' : '取消', exact: true })
            .click()
          await page.locator('.danger-zone > button').click()
          await expect(page.locator('dialog')).toBeVisible()
          await inspect('delete-dialog')
          await page
            .locator('dialog')
            .getByRole('button', { name: language === 'en-US' ? 'Cancel' : '取消', exact: true })
            .click()
        }
        if (path === '/kb') await expect(page.locator('.answer-card')).toBeVisible()
        if (path === '/board') {
          await expect(page.locator('.application-card')).toBeVisible()
          const board = page.locator('.kanban')
          assert.ok(
            await board.evaluate((el) => el.scrollWidth > el.clientWidth),
            'Board columns should scroll locally',
          )
          await board.evaluate((el) => (el.scrollLeft = el.scrollWidth))
        }
        await inspect(path.slice(1).replaceAll('/', '-') || 'home')
      }
      // Mobile drawer/desktop rail must keep a long custom name and settings icon inside the rail.
      if (width === 390)
        await page
          .getByRole('button', { name: language === 'en-US' ? 'Open navigation' : '打开导航' })
          .click()
      assert.ok(
        await page.locator('.profile-link').evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
      )
      if (width === 390) await page.locator('.nav-backdrop').click({ position: { x: 380, y: 200 } })
      const dock = page.locator('.generation-dock')
      await dock.locator('header button').click()
      await expect(dock.locator('li')).toHaveCount(1)
      // Completion notice, long title and dark surface stay readable and dismissible.
      completed = true
      await dock
        .getByRole('button', {
          name: language === 'en-US' ? 'Refresh tasks' : '刷新任务',
          exact: true,
        })
        .click()
      await expect(dock.locator('[aria-live]')).toBeVisible()
      await page.evaluate(() => document.documentElement.classList.add('dark'))
      const background = await dock.evaluate((el) => getComputedStyle(el).backgroundColor)
      assert.notEqual(background, 'rgb(255, 255, 255)', 'Dock must use the dark card surface')
      await inspect('task-notice-dark')
      await dock.locator('[aria-live] button').click()
      await expect(dock.locator('[aria-live]')).toHaveCount(0)
      await dock.locator('header button').click()
      for (const mode of ['loading', 'empty', 'error']) {
        state = mode
        for (const path of ['/settings/privacy', '/settings/models', '/kb', '/board']) {
          if (mode === 'empty' && path === '/settings/models') continue
          console.log(language, width, state, path)
          await page.goto(base + path)
          await expect(page.locator('main h1'))
            .toBeVisible()
            .catch(async (error) => {
              await page.screenshot({ path: `${output}/failure.png`, fullPage: true })
              console.error({
                url: page.url(),
                errors,
                unexpected,
                body: (await page.locator('body').innerText()).slice(0, 1200),
              })
              throw error
            })
          const name = path.slice(1).replaceAll('/', '-')
          if (mode === 'loading')
            await expect(page.locator('main [role="status"]').first()).toBeVisible()
          if (mode === 'empty')
            await expect(
              page.locator(path === '/settings/privacy' ? '#memory-heading' : '.empty-state'),
            ).toBeVisible()
          if (mode === 'error')
            await expect(page.locator('main [role="alert"]').first()).toBeVisible()
          await inspect(`${name}-${mode}`)
        }
      }
      assert.deepEqual(errors, [], 'No browser runtime errors')
      assert.deepEqual(unexpected, [], 'All API requests must have explicit fixtures')
      await context.close()
    }
  await writeFile(`${output}/results.json`, JSON.stringify(results, null, 2))
  console.log(`Passed ${results.length} layout checks; screenshots: ${output}`)
} finally {
  await browser.close()
}
