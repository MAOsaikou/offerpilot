# JB-06 截图视觉解析评测

样本是**由仓库种子 JD 渲染的截图，不是真实用户截图，也不是实拍招聘站截图**。
唯一事实来源是 `backend/offerpilot-job/src/main/resources/jobs/snapshot.json`：该资源明确为人工整理的示例岗位快照，不代表公司当前在招，不宣称是公开抓取的真实岗位。脚本从现有列表按等距索引固定抽取 20 条，涵盖多个公司/岗位/招聘类型；不编写新 JD。标注直接复制种子字段，manifest 记录来源 SHA-256、标注与图片顺序。

## 生成与桩验证（仓库根目录）

```bash
python3 backend/eval/job-screenshot/evaluate.py generate --samples /tmp/job-screenshot
python3 -m unittest discover -s backend/eval/job-screenshot -p 'test_*.py'
python3 backend/eval/job-screenshot/evaluate.py evaluate --samples /tmp/job-screenshot --stub --output /tmp/job-screenshot-stub.json
```

使用 Python 标准库 + Pillow（box 已有 Pillow），默认中文字体 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`，可通过 `SCREENSHOT_FONT` 指向其他 CJK 字体。无 Pillow 时自动使用 `render.mjs` 与已有 Playwright（默认 `/tmp/pw/node_modules/playwright`，可用 `PLAYWRIGHT_MODULE` 覆盖）；不自动安装依赖。招聘 App 卡片 7 条、网页详情 7 条、长图 6 条；长图分成 2/3 张有重叠的连续截图，Pillow 路径轮换 PNG/JPG/WebP，浏览器回退使用 PNG。

`--stub` 直接返回标注，仅验证文件/评分/报告流程；JSON 的 `mode=stub-annotations-not-live`、`passesLiveGate=null`。桩分数无论高低都不能证明视觉模型准确率。独立 unittest 另验证真实客户端 multipart → 有序 fileIds → 后台任务轮询 → 草稿读取协议及错误计零。

## 本地后端实测（由任务负责人执行）

启动本地后端、PostgreSQL、私有对象存储；在同一后端登录或建立专用测试身份。把 access token 放到当前 shell 的 `OFFERPILOT_ACCESS_TOKEN` 环境变量，不写入文件或命令参数。配置 `VISION_ALT` 的供应商地址、API Key、模型名及 `supports-vision=true`，并确保 FLASH 摘录档位可用、配额足够；每条通常消耗视觉转写 + FLASH 摘录 + SC-01 分类调用。本轮不会自动执行 live。

```bash
python3 backend/eval/job-screenshot/evaluate.py evaluate \
  --samples /tmp/job-screenshot \
  --base-url http://127.0.0.1:8081/api/v1 \
  --output /tmp/job-screenshot-live.json
```

串行上传每条的 1–3 张图片到 `POST /jobs/import/screenshots`，提交 `POST /generation-tasks`（`JOB_IMPORT/current/input.fileIds`），查询状态并读取 `/generation-tasks/{id}/result`。不调用岗位保存接口，不生成正式岗位。原图与任务留在测试身份下，按现有账号数据生命周期清理；脚本不自动删除。轮询超时默认 300 秒，可用 `--timeout` 修改；超时不代表后台任务取消，先查询任务再重跑，避免重复费用。输出包含每条原始解析字段、分项指标、失败原因和汇总，不包含 token。

## 评分口径（固定后再跑实测）

- 职位名、公司：NFKC、大小写、空白归一后精确匹配；公司为空而模型猜出公司计错。
- 城市：按逗号/顿号/分号/空白拆分为集合，规范化后精确匹配，顺序无关；多出城市计错。
- 职责、要求：忽略行首条目序号和空白后按完整行精确匹配；分别报告逐行 precision、recall、F1，重复行只算一次。新增/编造行降低 precision，遗漏行降低 recall，不做语义近似匹配。
- 主准确率：每条上述 5 项（title/company/locations/description-F1/requirement-F1）等权平均，再在全部 20 条上取宏平均。部门、招聘类型单独报告，不混入主分。现有 JobImportParser 没有独立薪资字段，本次保持同一字段结构。
- 上传失败、模型失败、任务失败和超时的样本全部主项计 0，不能只统计成功样本。live `macroAccuracy >= 0.85` 才 `passesLiveGate=true`，未达标退出码 1。报告失败数、各版式逐条结果，避免平均值掩盖失败。

此基准检验干净种子渲染件；不代表拍照、模糊/裁切、小字、复杂页面或真实用户截图质量。视觉 transcript 自身可能误识别，文本原文校验只能限制二阶段摘录，不能证明 OCR 文字真实可见。须人工抽查原图与结果，不能仅凭结构单测宣称达标。live 结果由负责人跑完后再更新 docs，结果文件不进入版本库（尤其不提交 `docs/eval-results/`）。

## 前端协议与双视口检查

生成样本并完成 `cd frontend && pnpm build` 后，在允许本地端口和 Chromium 的环境运行：

```bash
SCREENSHOT_SAMPLES=/tmp/job-screenshot SCREENSHOT_DIR=/tmp/job-screenshot-ui \
  node frontend/e2e/job-screenshot.mjs
```

脚本加载真实生产构建，以隔离 HTTP 桩在1280px/390px覆盖空态、超量错误、预览删除、上传进度、后台任务离页、刷新恢复原图/表单、核对后保存，并断言只发一次任务请求、无整页溢出及页面异常。`E2E_BASE_URL` 可指向已启动的前端，默认自行启动6196端口。它同样不验证 live 模型准确率。本沙箱执行因监听127.0.0.1:6196返回 EPERM 而阻塞，尚未产生通过报告或视口截图。
