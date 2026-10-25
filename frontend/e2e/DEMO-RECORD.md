# 利索演示录屏

一条命令导出约 45–90 秒的前端演示视频。脚本自带 API 夹具，不需要后端或实时模型。

```bash
node frontend/e2e/demo-slick-record.mjs
```

也可以在 `frontend/` 里运行 `pnpm demo:record`。

## 需要什么

- Node.js 22+ 和已安装的前端依赖（`pnpm install --dir frontend`）
- Chrome 或 Chromium。脚本会依次查看 `CHROME_BIN`、`CHROMIUM_PATH`、`/opt/google/chrome/chrome`、`/usr/bin/google-chrome`、`/usr/bin/chromium`
- 外置 Playwright，默认 `/tmp/pw/node_modules/playwright/test`。不要把它加进前端依赖：

```bash
mkdir -p /tmp/pw
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --prefix /tmp/pw playwright
# 录屏需要 Playwright 自带的 ffmpeg（系统 ffmpeg 不能代替这一步）
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npx --prefix /tmp/pw playwright install ffmpeg
```

系统里的 ffmpeg 只用于可选的 mp4。没有它、或 webm 无法封装进 mp4 时，脚本仍以 webm 成功结束。

## 输出

默认文件：

`/tmp/offerpilot-demo-slick/offerpilot-demo-slick.webm`

需要留在工作区时：

```bash
DEMO_VIDEO_DIR=/workspace/docs-eval-local/demo-slick-record node frontend/e2e/demo-slick-record.mjs
```

视频不提交进仓库。

| 变量 | 作用 |
| --- | --- |
| `DEMO_VIDEO_DIR` | 输出目录，默认 `/tmp/offerpilot-demo-slick` |
| `E2E_BASE_URL` | 已运行的前端；未设置时脚本在 `127.0.0.1:6208` 拉起 Vite |
| `PLAYWRIGHT_MODULE` | Playwright 的 `test` 入口 |
| `CHROME_BIN` / `CHROMIUM_PATH` | 浏览器可执行文件 |
| `DEMO_SKIP_BOARD=1` | 跳过末尾的 `/board` |
| `DEMO_PACE` | 停顿倍数，默认 `1`。小于 1 会更快，大于 1 会更慢 |

## 画面顺序

1. `/login`：体验账号一键登录
2. `/`：工作台问候、今日动作和旅程
3. `/chat`：欢迎语，点选建议并发送，夹具立即返回一条回复
4. `/discover`：方向卡片；点「设为探索目标」
5. `/career`：职业推荐卡片上的「成长路径」（当前产品把 `growthPath` 放在这里，不在探索页的静态方向卡片上）
6. `/resumes/resume_demo`：已解析简历
7. `/interviews/interview_demo`：已完成报告与评分雷达
8. `/board`：求职看板（可用 `DEMO_SKIP_BOARD=1` 关掉）

视口和录像尺寸都是 1280×800。页面上的指针是脚本注入的圆点，方便剪辑时看见点击。
