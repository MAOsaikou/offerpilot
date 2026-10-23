# MediaPipe 本地资源

在仓库根目录执行（先安装 frontend 依赖，需要 Node.js ≥22）：

```sh
pnpm -C frontend mediapipe:fetch
# 强制重新复制 SDK 并下载模型：
pnpm -C frontend mediapipe:fetch --force
```

在 `frontend/` 下可直接执行 `pnpm mediapipe:fetch`。脚本从已安装的
`@mediapipe/tasks-vision@0.10.32` 复制 SDK 和整个 wasm 目录，只从 Google 官方地址下载
Face Landmarker 与 Pose Landmarker Lite 的 float16/1 模型。首次准备需要网络，之后本地
摄像头验收无需 CDN；已存在且非空的文件会跳过，失败返回非零退出码。

```text
public/mediapipe/
├── README.md
├── vision_bundle.mjs
├── wasm/
│   ├── vision_wasm_internal.js
│   ├── vision_wasm_internal.wasm
│   ├── vision_wasm_nosimd_internal.js
│   └── vision_wasm_nosimd_internal.wasm
├── face_landmarker.task
└── pose_landmarker_lite.task
```

`.env.example` 中的 `VITE_MEDIAPIPE_ASSETS_URL=/mediapipe` 对应此目录，Vite 开发服务
直接提供这些文件，构建时复制至 dist。修改资源根时须保持相同布局，并在构建前准备资产。
Worker 优先配置的资源根，缺失或初始化失败后仍回退 jsDelivr 0.10.32 SDK/wasm 与
Google 官方模型；两处都失败则释放摄像头并跳过分析。真摄像头与浏览器 wasm 运行仍待人工验收。

此目录除 README 外均由 `.gitignore` 忽略。**勿提交模型、wasm、SDK 或下载临时文件**；
升级时须同步脚本、Worker 与 package.json 中的固定版本。
