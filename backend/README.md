# 后端本地启动（DeepSeek + PostgreSQL）

当前实现包括账号/访客认证、持久化聊天与专家编排、简历解析/诊断/岗位定向改写/采纳/PDF、岗位搜索与匹配、投递工作区、面试/STAR/复盘、职业推荐与成长路线、私有知识笔记和长期事实记忆。公开体验默认创建有期限和额度的独立访客沙箱。启动执行 V1–V26 迁移；旧分支迁移先按 [升级说明](../deploy/migrations/README.md) 对齐历史。职业与笔记支持全文及可选向量检索，未配置百炼 embedding 时降级；真实向量效果、真人评分与公网部署仍待验收。默认集成测试使用隔离 PostgreSQL/S3Mock 和本地模型协议桩，不调用真实模型。

## 最新主线真实模型验收

Windows 入口会复制当前后端源码（包括未提交修改）到新临时目录，保留原工作区、旧构建产物及数据；不执行 clean 或删除。先检查所有 live 评测可编译，再按需显式运行：

```powershell
# 在仓库根目录：只编译，零模型调用
.\backend\scripts\live-smoke.ps1
# 付费：仅 FLASH，最多 40 次流式模型入口调用；从进程或忽略的 backend/.env.local 读取 DeepSeek Key
.\backend\scripts\live-smoke.ps1 -RunLive -Tier FLASH -MaxCalls 40
```

真实模型套件使用生产装配、独立 PostgreSQL/Redis/S3Mock、新沙箱和虚构简历，覆盖推荐、匹配、改写→审批→PDF、投递材料→面试→STAR→复盘→教练以及跨访客隔离。岗位 MCP 被桩设为不可用，检查离线回退；不代表实时企业源、真实 embedding、语音或公网验收。S3Mock 不校验凭据，认证存储须另用 [存储验收](../deploy/scripts/README.md) 检查。证据留在临时目录，不打印凭据、不自动清理。

直接运行 Maven 时，`-Plive` 只开放编译和发现；仍需套件开关和真实 Key。`offerpilot.smoke.tier` 默认 FLASH，PRO 或 ALL 必须显式指定；所有七个方法统一遵守筛选。`offerpilot.smoke.max-calls` 默认 40，对本次 JVM 全套调用计数，达到上限后在供应商 IO 前拒绝。该计数不是 token 或账单金额。

2026-10-12 百炼实测：`text-embedding-v4` 返回 1024 维向量，Spring AI 与业务适配器的 16 条短文本也通过（10+6 分片）；完整职业库语义质量仍待评测。dev 可在忽略的 `backend/.env.local` 设置 `BAILIAN_API_KEY`、`SPRING_AI_MODEL_EMBEDDING=openai`、`LLM_MODEL_EMBEDDING=text-embedding-v4`、`LLM_EMBEDDING_DIMENSIONS=1024`，重新构建并重启生效。首次开启会补齐职业库并启动笔记向量任务；仅测少量请求时先设置 `OCCUPATION_EMBED_ENABLED=false`、`NOTES_EMBED_ENABLED=false`。详见[验收记录](../docs/research/2026-10-12-本地验收与发布准备.md)。

## 1. 准备环境

需要 JDK 17、Maven 3.9+、Docker Desktop。Windows 安装并打开 [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)，选择 Linux 容器 / WSL 2，等待引擎运行，再开 PowerShell 验证。本地对象存储使用 Compose 中的 S3Mock；公网使用部署文档中的认证存储覆盖：

```powershell
java -version
mvn -version
docker version
docker compose version
```

优先使用仓库 Maven Wrapper（Windows：`./mvnw.cmd`，Maven 3.9.11），也可使用已安装的 `mvn`；不要执行父聚合工程的 `spring-boot:run`，它不是启动模块。

## 2. 填 DeepSeek Key

先配置认证密钥：JWT_SECRET 必须为至少 64 字节随机串，例如用 `openssl rand -base64 64` 生成后写入忽略的 backend/.env.local。缺失、短值或 change-me 占位值会拒绝启动；不要把生成值提交到仓库。测试使用独立 test profile，不读取本机凭据。V12 会停用未轮换公开密码的旧测试账号并保留数据；用户可自行注册。正式客服首次创建可在后端进程环境（或本机忽略的 `backend/.env.local`）同时设置 `STAFF_BOOTSTRAP_EMAIL` 和 `STAFF_BOOTSTRAP_PASSWORD` 后启动，再用该账号访问前端 `/staff/login`。两项均为空时静默跳过；只配置一项、邮箱/密码不符合注册规则或邮箱已被其他账号使用时记录告警并跳过，不阻止启动、不自动提升角色。仅当 `users` 中不存在任何 STAFF（包括已停用账号）时创建一个 ACTIVE 客服；重复启动不新增账号、不重置密码。邮箱去掉两端空格，昵称取邮箱前缀，密码使用现有 BCrypt(12)，至少 6 位且 UTF-8 最多 72 字节。共享环境首启后移除或留空这两项，真实凭据不得提交到仓库。

当前 V11/V12 在全新迁移库中仍保留一条 DISABLED STAFF 测试账号，因此会触发上述跳过规则；此变更不改动 V11/V12，也不自动清除其角色。解决这条遗留行需要另外确认迁移方案，不能把仅停用账号误认为数据库已无 STAFF。

编辑 `backend/.env.local`（本轮已创建占位模板；新克隆时从 `backend/.env.example` 复制），将 `sk-not-configured` 替换为真实 Key：

```dotenv
DEEPSEEK_API_KEY=你的真实Key
DEEPSEEK_BASE_URL=https://api.deepseek.com
LLM_MODEL_FLASH=deepseek-flash
LLM_MODEL_PRO=deepseek-v4-pro
LLM_THINKING_FLASH=disabled
LLM_THINKING_PRO=enabled
POSTGRES_HOST=localhost
POSTGRES_DB=offerpilot
POSTGRES_USER=offerpilot
POSTGRES_PASSWORD=change-me-postgres
REDIS_HOST=localhost
REDIS_PASSWORD=你的Redis密码
```

Key 来自 [DeepSeek 控制台](https://platform.deepseek.com/api_keys)。文件已被 Git 忽略；不加引号，不加 `export`。也可以设置同名进程环境变量，它的优先级更高。模型名可按 [DeepSeek 官方 API](https://api-docs.deepseek.com/) 的账户可用模型修改。

默认 `dev` profile 从仓库根目录或 `backend` 工作目录读取此文件。Key 只在后端使用，不要放进前端 `.env` / `VITE_*`。修改后重启 Java 进程。根目录 `.env` 是完整 Docker 部署的配置，和本机 Java 使用的 `backend/.env.local` 区分。

开发环境 FLASH「快速」映射到 DeepSeek Flash，PRO「深度」映射到 DeepSeek Pro；文字聊天和文本简历解析可只配置 DeepSeek。图文简历默认共用 DEEPSEEK_API_KEY，模型名为 deepseek-flash；VISION_ALT 保留为视觉路由档位，默认也使用 DeepSeek。要改用百炼，显式设置 LLM_VISION_PROVIDER=bailian、LLM_MODEL_VISION_ALT 和 BAILIAN_API_KEY / BAILIAN_BASE_URL。旧环境中曾填写的视觉模型名需同步调整，不能把百炼模型名交给 DeepSeek。聊天模式已接入对应专家，写操作经审批。

简历上传还需要对象存储：`deploy/docker-compose.yml` 的 `minio` 服务运行 Adobe S3Mock，backend/.env.local 中的 MINIO_ENDPOINT / MINIO_ROOT_USER / MINIO_ROOT_PASSWORD / MINIO_BUCKET 必须与容器一致（见 .env.example）。S3Mock 不校验访问密钥，后端仍用这对密钥签名。上传支持 PDF、DOCX、PNG、JPG、UTF-8 TXT/Markdown，单文件≤10MB、PDF≤10页；DOCX 支持正文、页眉、页脚和 PNG/JPEG 嵌入图片（最多20张）；其他嵌入媒体需导出 PDF。PDF 每页同时提供文本与图像以避免混合内容遗漏。原有前端本地草稿仍保留，不会自动上传。

## 3. 启动依赖

在仓库根目录执行：

```powershell
docker compose --env-file backend/.env.local -f deploy/docker-compose.yml up -d postgres redis minio
docker compose --env-file backend/.env.local -f deploy/docker-compose.yml ps
```

等待 postgres、redis、minio 显示 `healthy`。首次需要下载镜像。端口为 PostgreSQL `5432`、Redis `6379`、对象存储 `127.0.0.1:9000`（无 9001 控制台）。Redis 要求使用 `REDIS_PASSWORD` 认证，且宿主机端口仅绑定 `127.0.0.1`。实时岗位搜索需要 jobs-mcp；使用 LOCAL 快照或不搜索岗位时可暂不启动它。

PostgreSQL 镜像已包含 pgvector。Java 启动自动执行 Flyway V1–V26，创建用户、会话、消息、简历与版本、投递材料及沙箱等表；无需手工运行 SQL。既有数据库只执行尚未应用的迁移，私有数据按当前身份隔离。

如果之前已经建立数据库卷，修改 `POSTGRES_PASSWORD` 不会重置库内密码，应填写现有密码；不要删除数据库卷来解决连接问题。

## 4. 启动后端

双击根目录 `OfferPilot.cmd`，选择「1 启动全部」自动启动依赖、构建后端、启动前后端；日常改代码或 Key 后选择「5 重建并重启后端」。仅启动后端可执行 `OfferPilot.cmd -Action start-backend`。也可手动执行：

```powershell
cd backend
mvn -q -B -pl offerpilot-bootstrap -am package
java -jar offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

保持窗口打开。健康检查：`http://localhost:8081/actuator/health`，预期 `{"status":"UP"}`；接口文档：`http://localhost:8081/swagger-ui.html`。健康检查覆盖数据库 / Redis，不代表模型 Key 有效。当前已接入 12 小时密码登录 JWT 和 30 天访客凭据；公网部署仍需 HTTPS、正式随机密钥及部署规范中的环境配置。

dev 默认关闭 Embedding 与 Spring AI VectorStore 自动装配，保留规则/全文检索，不关闭 PostgreSQL、Flyway、JPA 或 Redis。需要向量检索时按部署文档配置百炼 Key 并显式开启 embedding。

## 5. 前端发送真实消息

新终端进入 `frontend` 执行 `pnpm dev`，访问 `http://localhost:5173/chat`；保持 `VITE_ENABLE_MOCK=false`。Windows Nginx 前端可在 `OfferPilot.cmd` 菜单选择「4 更新前端」或「6 仅启动前端」，访问 `http://localhost:8080/chat`。

发送“我是软件工程大三学生，帮我安排今天的求职准备”。正文应逐步出现，执行面板显示 DeepSeek 模型与运行结束，供应商返回用量时显示 token 数。

也可以从仓库根目录使用 curl（后端运行时）。**`/agent/chat` 需要访客身份**，先领一个 token（30 天有效）：

```powershell
$guest = Invoke-RestMethod -Method Post http://localhost:8081/api/v1/auth/guest
$token = $guest.data.accessToken
```

然后发消息。PowerShell 会破坏 `--data-raw` 里的双引号（后端返回 `10004 JSON 解析失败`），
中文参数也需按 UTF-8 写入文件，所以这里用「写文件 + `--data-binary`」：

```powershell
$body = '{"content":"我是软件工程大三学生，帮我安排今天的求职准备","options":{"modelTier":"FLASH"}}'
$file = Join-Path $env:TEMP 'offerpilot-chat.json'
[IO.File]::WriteAllText($file, $body, (New-Object System.Text.UTF8Encoding($false)))
curl.exe -N http://localhost:8081/api/v1/agent/chat -H "Content-Type: application/json" -H "Authorization: Bearer $token" --data-binary "@$file"
```

在 `cmd.exe` 里可以把 token 存进环境变量后直接用原命令：

```bat
set TOKEN=上一步返回的 accessToken
curl.exe -N http://localhost:8081/api/v1/agent/chat -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" --data-raw "{\"content\":\"Hello OfferPilot\",\"options\":{\"modelTier\":\"FLASH\"}}"
```

成功流：`run.started → text.delta* → usage（若上游提供）→ run.completed`。失败流以 `error(recoverable=false)` 结束，不伪造成功或回复。未填 Key 返回 `40009`；超时为 `40001`；其他模型错误为 `40010`。用量来自模型，费用 `0` 暂表示未估算，不代表免费。

## 6. 常见问题

- `docker` 不存在：安装 Docker Desktop 并重开终端；连接 Docker 引擎失败则先打开 Docker Desktop。
- PostgreSQL 连接失败：确认容器 healthy、5432 无冲突、`.env.local` 密码与现有库一致。本机 Java 的 host 是 `localhost`，不是容器内部服务名 `postgres`。
- Redis 连接失败：确认容器 healthy，并确保容器、后端和数据库客户端使用同一个 `REDIS_PASSWORD`；修改密码后需重新创建 Redis 容器。
- 健康检查不是 UP：检查 PostgreSQL 和 Redis，再看 Java 日志。
- Key 配好仍报 40009：从仓库根目录或 `backend` 启动，确认没有更高优先级环境变量覆盖，并重启后端。
- 40010：检查 Key、账户余额、网络和账户可用模型名。不会把供应商原始错误正文或凭据回传浏览器。
- 10004 JSON 解析失败：多为 Windows PowerShell 破坏了 `--data-raw` 里的双引号，
  改用第 5 节的「写文件 + `--data-binary`」写法，或在 `cmd.exe` 中执行原命令。
- 聊天接口返回空：`/agent/chat` 需要 `Authorization: Bearer <accessToken>`，
  先调 `POST /api/v1/auth/guest` 领访客身份；缺少或过期的 token 会被拒绝。
- 页面仍说“回显”：正在使用旧前端构建；Java 仍回显则运行了旧 JAR。更新构建并只重启对应进程。
- 8081 已占用：先检查已有 Java 进程。本入口管理的进程在 `OfferPilot.cmd` 选择「2 停止前后端」；IDE 或手动启动的进程应在原窗口停止。

## 7. 验证范围

`mvn -q -B -pl offerpilot-bootstrap -am package` 包含流式事件、中文、用量、参数校验、缺 Key、空回复、超时、取消和错误不泄露测试，以及真实 HTTP + Spring AI DeepSeek 适配器集成测试。集成测试使用 `test` profile 和本机模拟供应商，不消耗真实 Key，不替代 dev 环境的数据库及真实 DeepSeek 验收。

## 网页切换简历解析模型

打开「设置 → 模型选择」（/settings/models），选择文字模型和图片/PDF模型；支持图片的模型可以勾选共用。点击保存后立即对当前访客后续上传或失败重试生效，无需重启；不影响聊天、其他访客、已排队或正在解析的任务。简历列表也有快捷入口。

聊天和文字模型只有 FLASH「快速」、PRO「深度」；VISION_ALT 仅用于内部视觉路由。每个档位都可独立设置供应商、型号和图片能力，页面显示实际供应商/型号。未配置 Key 或地址的选项不可选；“已配置”只代表配置齐全，不代表联网或额度验证通过。新增或更改供应商配置后重启后端并刷新页面。

例如配置自定义 OpenAI 兼容服务：在 backend/.env.local 设置 LLM_PROVIDER_FLASH=custom、LLM_MODEL_FLASH=实际模型名、LLM_VISION_FLASH=true/false，并填写 CUSTOM_LLM_BASE_URL 与 CUSTOM_LLM_API_KEY；请求路径默认 /v1/chat/completions，可用 CUSTOM_LLM_COMPLETIONS_PATH 修改。不要将 /v1 同时填进 Base URL 和请求路径。百炼使用 provider=bailian 及 BAILIAN_API_KEY / BAILIAN_BASE_URL；DeepSeek 使用 provider=deepseek 及 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL。PRO 模型名使用 LLM_MODEL_PRO；旧 DEEPSEEK_MODEL_PRO 请迁移到此变量。Key 只留在后端环境，网页不会读写密钥或任意接口地址。

Flyway V7 新增当前访客的模型偏好及任务路由快照，V1–V6 不改写。

旧 CHEAP / ECONOMY / 经济 / BALANCED 档位兼容为 FLASH；未知聊天档位使用服务器默认档位。V18 清理旧偏好和任务列，历史报告正文及模型名保留。深度默认思考开启，输出预算 32768；工具调用轮次关闭思考，以兼容 Spring AI 1.0 的工具循环。`reasoning_content` 不进入聊天正文或结构化 JSON。Embedding 仍接百炼，无 Key 时保留全文检索与规则回退。
