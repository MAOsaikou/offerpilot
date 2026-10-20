// Pillow fallback: use the existing external Playwright installation, without npm install.
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '/tmp/pw/node_modules/playwright')
const manifestPath = process.argv[2]
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
try {
  for (const [index, sample] of manifest.samples.entries()) {
    const width = sample.layout === 'web-detail' ? 1200 : sample.layout === 'app-card' ? 780 : 680
    const page = await browser.newPage({ viewport: { width, height: 900 }, deviceScaleFactor: 1 })
    await page.setContent(`<style>*{box-sizing:border-box}body{margin:0;background:#f4f6fa;color:#192d3e;font:28px/1.6 'Noto Sans CJK SC',sans-serif;padding:32px}main{background:white;border-radius:20px;padding:32px;border-top:6px solid #176960;white-space:pre-wrap;overflow-wrap:anywhere}.web-detail{border-radius:0;border-color:#365a9c;padding:60px}.long-image{padding:16px}</style><main class="${sample.layout}"></main>`)
    await page.locator('main').evaluate((element, text) => { element.textContent = text }, sample.text)
    await page.evaluate(() => document.fonts.ready)
    const height = await page.evaluate(() => document.documentElement.scrollHeight)
    await page.setViewportSize({ width, height })
    const count = sample.layout === 'long-image' ? 2 + index % 2 : 1
    for (let part = 0; part < count; part++) {
      const top = part ? Math.max(0, Math.floor(part * height / count) - 50) : 0
      const bottom = part === count - 1 ? height : Math.floor((part + 1) * height / count)
      const name = `${String(index + 1).padStart(2, '0')}-${sample.layout}-${part + 1}.png`
      await page.screenshot({ path: path.join(path.dirname(manifestPath), name), clip: { x: 0, y: top, width, height: bottom - top } })
      sample.images.push(name)
    }
    await page.close()
  }
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2))
} finally { await browser.close() }
