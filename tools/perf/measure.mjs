import { createRequire } from "node:module";
import { mkdir, writeFile, readdir, readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import { resolve } from "node:path";
import { serve } from "./server.mjs";
import { user, token, routes } from "./fixtures.mjs";

// Tooling installed separately; no lockfile/dependency changes to the app.
const require = createRequire(
  resolve(
    process.env.PERF_TOOLS || "/tmp/offerpilot-perf-tools",
    "package.json",
  ),
);
const { default: puppeteer } = await import(require.resolve("puppeteer-core"));
const { default: lighthouse } = await import(require.resolve("lighthouse"));
const { default: desktop } = await import(
  require.resolve("lighthouse/core/config/desktop-config.js")
);
const dist = resolve(process.argv[2] || "frontend/dist");
const output = resolve(process.argv[3] || "/tmp/offerpilot-perf-results");
await mkdir(output, { recursive: true });
const server = await serve(dist);
const results = JSON.parse(
  await readFile(`${output}/runs.json`, "utf8").catch(() => "[]"),
);
try {
  const chunkReport = await Promise.all(
    (await readdir(`${dist}/assets`))
      .filter((f) => /\.(js|css|woff2)$/.test(f))
      .map(async (name) => {
        const data = await readFile(`${dist}/assets/${name}`);
        if (
          name.endsWith(".js") &&
          /perf-user|conv_perf|run_mock_01|\[MSW\]/.test(data.toString())
        ) {
          throw new Error(`Mock code leaked into production: ${name}`);
        }
        return { name, bytes: data.length, gzipBytes: gzipSync(data).length };
      }),
  );
  await writeFile(
    `${output}/chunks.json`,
    JSON.stringify(chunkReport, null, 2),
  );
  for (const route of routes.filter(
    (r) => !process.env.PERF_ROUTE || r.name === process.env.PERF_ROUTE,
  )) {
    for (const preset of ["mobile", "desktop"])
      for (let run = 1; run <= Number(process.env.PERF_RUNS || 3); run++) {
        if (
          results.some(
            (r) =>
              r.route === route.name && r.preset === preset && r.run === run,
          )
        )
          continue;
        const browser = await puppeteer.launch({
          executablePath:
            process.env.CHROME_PATH || "/opt/google/chrome/chrome",
          headless: true,
          args: [
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--remote-debugging-port=0",
          ],
        });
        try {
          const page = (await browser.pages())[0];
          const url = `http://127.0.0.1:4178${route.path}`;
          // Seed only auth on the origin. Lighthouse navigates with a cold HTTP cache.
          await page.goto("http://127.0.0.1:4178/favicon.svg");
          await page.evaluate(
            ({ user, token }) => {
              localStorage.clear();
              localStorage.setItem("offerpilot-access-token", token);
              localStorage.setItem(
                "offerpilot-auth-user",
                JSON.stringify(user),
              );
              localStorage.setItem("op-theme", "light");
            },
            { user, token },
          );
          const report = await lighthouse(
            url,
            {
              port: Number(new URL(browser.wsEndpoint()).port),
              output: "json",
              logLevel: "error",
              onlyCategories: [
                "performance",
                "accessibility",
                "best-practices",
              ],
              disableStorageReset: true,
            },
            preset === "desktop" ? desktop : undefined,
          );
          const lhr = report.lhr;
          await writeFile(
            `${output}/${route.name}-${preset}-${run}.json`,
            JSON.stringify(lhr),
          );
          if (lhr.runtimeError)
            throw new Error(JSON.stringify(lhr.runtimeError));
          const audit = (key) => lhr.audits[key].numericValue;
          const jsBytes = lhr.audits["network-requests"].details.items
            .filter((i) => i.resourceType === "Script")
            .reduce((sum, i) => sum + i.transferSize, 0);
          const row = {
            route: route.name,
            preset,
            run,
            perf: lhr.categories.performance.score * 100,
            a11y: lhr.categories.accessibility.score * 100,
            bp: lhr.categories["best-practices"].score * 100,
            lcp: audit("largest-contentful-paint"),
            tbt: audit("total-blocking-time"),
            cls: audit("cumulative-layout-shift"),
            jsBytes,
          };
          console.log(JSON.stringify(row));
          // Verify that scored navigation really rendered fixtures, not login/error/empty page.
          // Lighthouse closes its target; inspect the same URL separately after scoring.
          const measuredPage = await browser.newPage();
          await measuredPage.goto(url, { waitUntil: "networkidle0" });
          await measuredPage.waitForSelector(route.ready, { timeout: 10000 });
          const state = await measuredPage.evaluate(() => ({
            path: location.pathname,
            text: document.querySelector("main")?.innerText,
            cards: document.querySelectorAll(".card-reveal").length,
            overflow: document.documentElement.scrollWidth > innerWidth,
          }));
          if (
            state.path !== new URL(url).pathname ||
            !state.text ||
            (route.name === "chat" && state.cards !== 3)
          )
            throw new Error(`Wrong page: ${JSON.stringify(state)}`);
          await writeFile(
            `${output}/${route.name}-${preset}-${run}-render.json`,
            JSON.stringify(state, null, 2),
          );
          results.push(row);
        } finally {
          await browser.close();
        }
        await writeFile(
          `${output}/runs.json`,
          JSON.stringify(results, null, 2),
        );
      }
  }
} finally {
  await new Promise((r) => server.close(r));
}
