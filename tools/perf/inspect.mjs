import { createRequire } from "node:module";
import { resolve } from "node:path";
import { writeFile, mkdir } from "node:fs/promises";
import { serve } from "./server.mjs";
import { user, token, routes } from "./fixtures.mjs";
const require = createRequire(
  resolve(
    process.env.PERF_TOOLS || "/tmp/offerpilot-perf-tools",
    "package.json",
  ),
);
const { default: puppeteer } = await import(require.resolve("puppeteer-core"));
const output = process.argv[3] || "/tmp/offerpilot-perf-visual";
await mkdir(output, { recursive: true });
const server = await serve(process.argv[2] || "frontend/dist", 4179);
const browser = await puppeteer.launch({
  executablePath: "/opt/google/chrome/chrome",
  headless: true,
  args: ["--no-sandbox", "--disable-dev-shm-usage"],
});
try {
  for (const width of [390, 1280]) {
    const page = await browser.newPage();
    await page.setViewport({
      width,
      height: 844,
      isMobile: width === 390,
      hasTouch: width === 390,
    });
    await page.goto("http://127.0.0.1:4179/favicon.svg");
    await page.evaluate(
      ({ user, token }) => {
        localStorage.clear();
        localStorage.setItem("offerpilot-access-token", token);
        localStorage.setItem("offerpilot-auth-user", JSON.stringify(user));
        localStorage.setItem("op-theme", "light");
      },
      { user, token },
    );
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    for (const route of routes) {
      await page.goto(`http://127.0.0.1:4179${route.path}`, {
        waitUntil: "networkidle0",
      });
      await page.waitForSelector(route.ready);
      const state = await page.evaluate(() => ({
        path: location.pathname,
        overflow: document.documentElement.scrollWidth > innerWidth,
        boxes: [
          ...document.querySelectorAll(
            ".route, .card-grid > *, .page-stack > h2",
          ),
        ].map((e) => ({
          text: e.innerText,
          width: e.getBoundingClientRect().width,
          height: e.getBoundingClientRect().height,
          top: e.getBoundingClientRect().top,
        })),
      }));
      await page.screenshot({
        path: `${output}/${route.name}-${width}.png`,
        fullPage: true,
      });
      await writeFile(
        `${output}/${route.name}-${width}.json`,
        JSON.stringify({ ...state, errors }, null, 2),
      );
      if (state.overflow || errors.length)
        throw new Error(
          JSON.stringify({ route: route.name, width, state, errors }),
        );
      console.log(`${route.name} ${width}: no overflow/pageerror`);
    }
    // Inspect pending UI under an extra request delay, independently of scores.
    await page.setRequestInterception(true);
    page.on("request", async (request) => {
      if (/\/api\/v1\/(applications|resumes)(?:\?|$)/.test(request.url()))
        await new Promise((r) => setTimeout(r, 1000));
      if (!request.isInterceptResolutionHandled()) await request.continue();
    });
    for (const [name, path, selector] of [
      ["dashboard", "/", ".route.is-loading"],
      ["resume", "/resumes", ".resume-skeleton"],
    ]) {
      await page.goto(`http://127.0.0.1:4179${path}`, {
        waitUntil: "domcontentloaded",
      });
      await page.waitForSelector(selector);
      await new Promise((r) => setTimeout(r, 250));
      await page.screenshot({
        path: `${output}/${name}-loading-${width}.png`,
        fullPage: true,
      });
      await page.waitForFunction(
        (selector) => !document.querySelector(selector),
        {},
        selector,
      );
      console.log(`${name} loading ${width}: skeleton resolves`);
    }
    await page.close();
  }
} finally {
  await browser.close();
  await new Promise((r) => server.close(r));
}
