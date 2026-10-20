// Deterministic UI/protocol verification; generate seed screenshots first (eval README).
import process from 'node:process'
import { createRequire } from 'node:module'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
const require = createRequire(import.meta.url)
const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright/test',
)
const sampleDir = process.env.SCREENSHOT_SAMPLES || '/tmp/jb06-samples'
const manifest = JSON.parse(await readFile(`${sampleDir}/manifest.json`, 'utf8'))
const sample = manifest.samples[0]
const image = await readFile(`${sampleDir}/${sample.images[0]}`)
const output = process.env.SCREENSHOT_DIR || '/tmp/jb06-browser'
const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:6196'
let server, browser
try {
  await mkdir(output, { recursive: true })
  if (!process.env.E2E_BASE_URL) {
    server = spawn(
      process.execPath,
      [
        'node_modules/vite/bin/vite.js',
        'preview',
        '--host',
        '127.0.0.1',
        '--port',
        '6196',
        '--strictPort',
      ],
      {
        cwd: fileURLToPath(new URL('..', import.meta.url)),
        stdio: 'inherit',
      },
    )
    let ready = false
    for (let i = 0; i < 60; i++) {
      if (server.exitCode !== null) throw new Error(`Vite exited ${server.exitCode}`)
      try {
        ready = (await fetch(base)).ok
      } catch {
        /* startup */
      }
      if (ready) break
      await delay(250)
    }
    if (!ready) throw new Error('Vite startup timeout')
  }
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
  const reports = []
  for (const width of [1280, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 844 } })
    await context.addInitScript(() =>
      localStorage.setItem('offerpilot-guest-v1', 'screenshot-ui-stub'),
    )
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    let task,
      starts = 0,
      uploads = 0,
      saves = 0
    const result = {
      draft: { ...sample.expected, skills: [], parser: 'model' },
      sourceKind: 'screenshot',
      url: '',
      fileIds: ['file_1', 'file_2'],
      securityNotices: [],
    }
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname.replace('/api/v1', '')
      const ok = (data) => route.fulfill({ json: { code: 0, data, traceId: 'screenshot-fixture' } })
      if (path === '/jobs/import/screenshots' && request.method() === 'POST') {
        uploads++
        await delay(150)
        return ok({
          fileId: `file_${uploads}`,
          name: 'page.png',
          mime: 'image/png',
          size: image.length,
        })
      }
      if (path.startsWith('/jobs/import/screenshots/'))
        return route.fulfill({ body: image, contentType: 'image/png' })
      if (path === '/generation-tasks' && request.method() === 'POST') {
        starts++
        expect(request.postDataJSON().input).toEqual({ fileIds: ['file_1', 'file_2'] })
        task = {
          id: 'gen_screenshot',
          kind: 'JOB_IMPORT',
          entityId: 'current',
          status: 'RUNNING',
          title: '岗位导入解析',
          href: '/jobs?taskId=gen_screenshot',
          stage: '正在视觉转写',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        }
        return ok(task)
      }
      if (path === '/generation-tasks') return ok(task ? [task] : [])
      if (path.startsWith('/generation-tasks/')) return ok(path.endsWith('/result') ? result : task)
      if (path === '/jobs/import') {
        saves++
        return ok({ id: 'job_reviewed', ...request.postDataJSON() })
      }
      if (path === '/jobs/sites') return ok({ sites: [] })
      if (path === '/jobs/recommendations') return ok({ items: [], date: '2026-10-21' })
      if (path === '/jobs/job_reviewed')
        return ok({
          id: 'job_reviewed',
          ...result.draft,
          skills: [],
          url: '',
          site: 'imported',
          source: 'USER_IMPORT',
        })
      if (path === '/resumes') return ok({ items: [], total: 0, page: 1, size: 20 })
      if (path === '/auth/me' || path === '/auth/quota') return ok({})
      return ok([])
    })
    const noOverflow = async () =>
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      )
    await page.goto(`${base}/jobs`)
    await page.getByRole('button', { name: '粘贴 JD 或网址 / 截图' }).click()
    await page.getByRole('radio', { name: '截图', exact: true }).check()
    await expect(page.getByText('尚未选择截图', { exact: false })).toBeVisible()
    await noOverflow()
    const input = page.getByLabel('选择岗位截图', { exact: true })
    await input.setInputFiles(
      Array.from({ length: 4 }, (_, i) => ({
        name: `p${i}.png`,
        mimeType: 'image/png',
        buffer: image,
      })),
    )
    await expect(page.getByRole('alert')).toContainText('最多选择')
    await input.setInputFiles([{ name: 'first.png', mimeType: 'image/png', buffer: image }])
    await page.getByRole('button', { name: '删除截图 1', exact: true }).click()
    await expect(page.getByText('尚未选择截图', { exact: false })).toBeVisible()
    await input.setInputFiles(
      [1, 2].map((i) => ({ name: `page${i}.png`, mimeType: 'image/png', buffer: image })),
    )
    await page.screenshot({ path: `${output}/${width}-selected.png`, fullPage: true })
    await page.getByRole('button', { name: '解析岗位', exact: true }).click()
    await expect(page.getByLabel('截图上传进度')).toBeVisible()
    await expect(page.locator('.background-task-status [role="status"]')).toContainText(
      '正在视觉转写',
    )
    await noOverflow()
    await page.screenshot({ path: `${output}/${width}-running.png`, fullPage: true })
    await page.locator('a[href="/resumes"]').first().click()
    task.status = 'SUCCEEDED'
    task.stage = '结果已保存'
    await page.goto(`${base}/jobs?taskId=gen_screenshot`)
    await expect(page.getByText('已从截图逐行转写并摘录字段。', { exact: false })).toBeVisible()
    await expect(page.getByAltText('核对截图 1', { exact: true })).toBeVisible()
    await expect(page.getByAltText('核对截图 2', { exact: true })).toBeVisible()
    await noOverflow()
    await page.reload()
    await expect(page.locator('input[maxlength="100"]').first()).toHaveValue(sample.expected.title)
    expect(starts).toBe(1)
    expect(uploads).toBe(2)
    expect(saves).toBe(0)
    await page.screenshot({ path: `${output}/${width}-restored.png`, fullPage: true })
    await page.getByRole('button', { name: '保存并查看匹配', exact: true }).click()
    await expect(page).toHaveURL(/\/jobs\/job_reviewed/)
    expect(saves).toBe(1)
    expect(errors).toEqual([])
    reports.push({ width, starts, uploads, saves, errors, overflow: false })
    await context.close()
  }
  await writeFile(
    `${output}/report.json`,
    JSON.stringify({ mode: 'protocol-stub-not-live', reports }, null, 2),
  )
} finally {
  await browser?.close()
  server?.kill()
}
