# Logged-in production performance harness

All fixtures are synthetic, read-only Node HTTP responses (`fixtures.mjs`). The
app is the ordinary production build; no env flag, mock import, injected script,
MSW worker, or backend is used by the measured application. The existing dev MSW
SSE scenario is insufficient for logged-in REST pages. This external fixture
server needs no Docker/Postgres and can never be included in the browser bundle.
It binds loopback, gzips static files/API responses, applies the repository's
static cache policy, and adds a fixed 100 ms fixture response delay. Unknown APIs
fail rather than returning misleading empty success data.

From repo root (Node 22+, pnpm, Chrome):

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend run build
npm install --prefix /tmp/offerpilot-perf-tools lighthouse@12.8.2 puppeteer-core@24
node tools/perf/measure.mjs frontend/dist /tmp/offerpilot-perf-results
node tools/perf/inspect.mjs frontend/dist /tmp/offerpilot-perf-visual
node tools/perf/summarize.mjs /tmp/offerpilot-perf-baseline /tmp/offerpilot-perf-after
```

`CHROME_PATH` overrides Chrome's binary; `PERF_TOOLS` overrides the separate
Node tooling directory. `PERF_ROUTE=chat PERF_RUNS=1` limits exploratory runs.
Use a **new output directory** for each phase; completed runs in a directory
resume automatically. Do not run before/after simultaneously, or run checks/builds
while scoring. The harness closes all its browsers and servers even on failure.

Each phase: six data-filled routes, default mobile and desktop presets, three
runs each. Each run launches a fresh isolated browser. Local storage contains
only an unsigned synthetic JWT with a fixed future expiry and a fictional USER;
it is not a real credential. Lighthouse keeps that auth storage and clears the
HTTP cache itself. Production auth guards, route chunks, API calls, fonts,
Markdown, card registry and layout all run normally. The viewport inspection
after scoring verifies the fixtures rendered and the three chat card wrappers
exist; a separate visual run checks 390px/1280px, screenshots and page errors, plus delayed-request skeletons.
Neither inspection is included in the Lighthouse score. Preserve the JSON
reports (`*-1.json` etc.), `runs.json`, render verification, and `chunks.json`.
JS transferred is the sum of Lighthouse network `Script.transferSize`, including
HTTP headers, entry, route and fetched lazy/card chunks; it is not just entry JS.

No real backend latency, SSE streaming, AI calls, auth validation, CDN/TLS,
real mobile hardware, interaction INP, or long-history scrolling is measured.
Scores are evidence for these fixture loads only.
