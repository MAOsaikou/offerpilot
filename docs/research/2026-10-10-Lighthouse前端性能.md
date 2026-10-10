# Lighthouse 前端性能（2026-10-10）

## 范围

- 代码：`main@33f6e36`，`pnpm run build` 产物 `frontend/dist`。
- 服务：本地静态服务器 `serve@14 -s dist`（gzip、SPA 回退），无后端，接近 `deploy/Caddyfile` 的 `encode zstd gzip` 静态分发；不含真实网络、CDN 与 TLS。
- 页面：只测公开的 `/login`（含体验账号入口）。登录后页面依赖后端与数据，本轮未测，不能外推。
- 工具：Lighthouse 12.8.2，Chrome 154 headless，`--preset=desktop`（模拟节流），连续 3 次。

## 结果（desktop 预设，3 次）

| 次 | Performance | Accessibility | Best Practices | FCP | LCP | TBT | CLS | Speed Index | TTI | 传输体积 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 99 | 100 | 100 | 0.4 s | 0.5 s | 80 ms | 0 | 0.4 s | 0.6 s | 168 KiB |
| 2 | 99 | 100 | 100 | 0.4 s | 0.5 s | 80 ms | 0 | 0.4 s | 0.6 s | 168 KiB |
| 3 | 99 | 100 | 100 | 0.4 s | 0.5 s | 90 ms | 0 | 0.4 s | 0.6 s | 168 KiB |

## 移动端

Lighthouse 默认 mobile 预设在本机 headless 环境 5 次均报 `NO_FCP`（同环境下一个纯静态 hello 页面 mobile 预设可正常出分；关闭 UA/屏幕模拟或节流单独排查也未恢复），因此**没有 Lighthouse 移动端分数**，不得引用。

旁证（非 Lighthouse 分数）：用 Puppeteer 在同一构建上做 412×823、DPR 1.75、触摸、移动 UA、CPU 4× 降速、150 ms RTT / 1.6 Mbps 下载的真实节流加载，浏览器自身 Paint Timing 报 `first-contentful-paint` ≈ 1.39 s（单次），页面文字正常渲染。这说明移动端能正常绘制，`NO_FCP` 是本机测量环境问题；具体移动端分数需在真实浏览器 DevTools 中补测。

## 可引用口径

“登录页 Lighthouse（desktop 预设，本地静态 gzip 服务，3 次）Performance 99 / Accessibility 100 / Best Practices 100，LCP 0.5 s、CLS 0。” 必须同时写明只测登录页、无真实网络、无移动端分数。
