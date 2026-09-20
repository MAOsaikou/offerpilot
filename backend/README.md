# 后端本地启动（DeepSeek + PostgreSQL）

当前实现包括持久化多轮文字聊天和简历上传解析。聊天链路：浏览器访客身份 → 会话 CRUD → `/api/v1/agent/chat` → Spring AI DeepSeek → SSE；支持停止、刷新恢复、分页与用户隔离。简历链路：`/api/v1/resumes` → MinIO 原件存储 → 后台解析 → PostgreSQL 结构化版本，可查询状态、失败重试与手动编辑。启动时自动执行 V1–V7 迁移。专家编排、聊天附件、RAG、长期记忆与工具调用仍未接入。集成测试需要 Docker，使用隔离 PostgreSQL/MinIO 和本地模型协议桩。

## 1. 准备环境

需要 JDK 17、Maven 3.9+、Docker Desktop。Windows 安装并打开 [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)，选择 Linux 容器 / WSL 2，等待引擎运行，再开 PowerShell 验证。MinIO 使用官方 Quay 镜像（Docker Hub 上的旧仓库已归档）：

```powershell
java -version
mvn -version
docker version
docker compose version
```

本机已有 Java 和 Maven。当前 Maven Wrapper 在 Windows 上存在空数组错误，本轮使用已安装的 `mvn` 构建；不要执行父聚合工程的 `spring-boot:run`，它不是启动模块。

## 2. 填 DeepSeek Key

编辑 `backend/.env.local`（本轮已创建占位模板；新克隆时从 `backend/.env.example` 复制），将 `sk-not-configured` 替换为真实 Key：

```dotenv
DEEPSEEK_API_KEY=你的真实Key
DEEPSEEK_BASE_URL=https://api.deepseek.com
LLM_MODEL_CHEAP=deepseek-flash
DEEPSEEK_MODEL_PRO=deepseek-v4-pro
POSTGRES_HOST=localhost
POSTGRES_DB=offerpilot
POSTGRES_USER=offerpilot
POSTGRES_PASSWORD=change-me-postgres
REDIS_HOST=localhost
REDIS_PASSWORD=你的Redis密码
```

Key 来自 [DeepSeek 控制台](https://platform.deepseek.com/api_keys)。文件已被 Git 忽略；不加引号，不加 `export`。也可以设置同名进程环境变量，它的优先级更高。模型名可按 [DeepSeek 官方 API](https://api-docs.deepseek.com/) 的账户可用模型修改。

默认 `dev` profile 从仓库根目录或 `backend` 工作目录读取此文件。Key 只在后端使用，不要放进前端 `.env` / `VITE_*`。修改后重启 Java 进程。根目录 `.env` 是完整 Docker 部署的配置，和本机 Java 使用的 `backend/.env.local` 区分。

开发环境 FLASH 和 CHEAP 都映射到 DeepSeek Flash，PRO 映射到 DeepSeek Pro；文字聊天和文本简历解析可只配置 DeepSeek。图文简历默认共用 DEEPSEEK_API_KEY，模型名为 deepseek-flash；VISION_ALT 保留为视觉路由档位，默认也使用 DeepSeek。要改用百炼，显式设置 LLM_VISION_PROVIDER=bailian、LLM_MODEL_VISION_ALT 和 BAILIAN_API_KEY / BAILIAN_BASE_URL。旧环境中曾填写的视觉模型名需同步调整，不能把百炼模型名交给 DeepSeek。模式下拉框目前不代表已接入不同专家。

简历上传还需要 MinIO：backend/.env.local 中的 MINIO_ENDPOINT / MINIO_ROOT_USER / MINIO_ROOT_PASSWORD / MINIO_BUCKET 必须与容器一致（见 .env.example）。上传支持 PDF、DOCX、PNG、JPG、UTF-8 TXT/Markdown，单文件≤10MB、PDF≤10页；DOCX 支持正文、页眉、页脚和 PNG/JPEG 嵌入图片（最多20张）；其他嵌入媒体需导出 PDF。PDF 每页同时提供文本与图像以避免混合内容遗漏。原有前端本地草稿仍保留，不会自动上传。

## 3. 启动依赖

在仓库根目录执行：

```powershell
docker compose --env-file backend/.env.local -f deploy/docker-compose.yml up -d postgres redis minio
docker compose --env-file backend/.env.local -f deploy/docker-compose.yml ps
```

等待 postgres、redis 显示 `healthy`。首次需要下载镜像。端口为 PostgreSQL `5432`、Redis `6379`、MinIO `9000`（控制台 `9001`）。Redis 要求使用 `REDIS_PASSWORD` 认证，且宿主机端口仅绑定 `127.0.0.1`。聊天不调用 jobs-mcp，暂时不需要启动它。

PostgreSQL 镜像已包含 pgvector。Java 启动自动执行 Flyway V1–V7，创建用户、会话、消息、文件元数据、简历与版本等表；无需手工运行 SQL。既有数据库只执行尚未应用的迁移，聊天和简历数据均按访客身份隔离。

如果之前已经建立数据库卷，修改 `POSTGRES_PASSWORD` 不会重置库内密码，应填写现有密码；不要删除数据库卷来解决连接问题。

## 4. 启动后端

双击根目录 `OfferPilot.cmd`，选择「1 启动全部」自动启动依赖、构建后端、启动前后端；日常改代码或 Key 后选择「5 重建并重启后端」。仅启动后端可执行 `OfferPilot.cmd -Action start-backend`。也可手动执行：

```powershell
cd backend
mvn -q -B -pl offerpilot-bootstrap -am package
java -jar offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

保持窗口打开。健康检查：`http://localhost:8081/actuator/health`，预期 `{"status":"UP"}`；接口文档：`http://localhost:8081/swagger-ui.html`。健康检查覆盖数据库 / Redis，不代表模型 Key 有效。本机调试尚未接入 JWT，不应直接作为公网服务发布。

RAG 尚未实现，dev 只关闭 Embedding 与 Spring AI VectorStore 自动装配，不关闭 PostgreSQL、Flyway、JPA 或 Redis，也不需要百炼 Key。

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
$body = '{"content":"我是软件工程大三学生，帮我安排今天的求职准备","options":{"modelTier":"CHEAP"}}'
$file = Join-Path $env:TEMP 'offerpilot-chat.json'
[IO.File]::WriteAllText($file, $body, (New-Object System.Text.UTF8Encoding($false)))
curl.exe -N http://localhost:8081/api/v1/agent/chat -H "Content-Type: application/json" -H "Authorization: Bearer $token" --data-binary "@$file"
```

在 `cmd.exe` 里可以把 token 存进环境变量后直接用原命令：

```bat
set TOKEN=上一步返回的 accessToken
curl.exe -N http://localhost:8081/api/v1/agent/chat -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" --data-raw "{\"content\":\"Hello OfferPilot\",\"options\":{\"modelTier\":\"CHEAP\"}}"
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

服务器配置四个可选择档位：FLASH、CHEAP、PRO、VISION_ALT。每个档位都可独立设置供应商、型号和图片能力，页面显示实际供应商/型号。未配置 Key 或地址的选项不可选；“已配置”只代表配置齐全，不代表联网或额度验证通过。新增或更改供应商配置后重启后端并刷新页面。

例如配置自定义 OpenAI 兼容服务：在 backend/.env.local 设置 LLM_PROVIDER_FLASH=custom、LLM_MODEL_FLASH=实际模型名、LLM_VISION_FLASH=true/false，并填写 CUSTOM_LLM_BASE_URL 与 CUSTOM_LLM_API_KEY；请求路径默认 /v1/chat/completions，可用 CUSTOM_LLM_COMPLETIONS_PATH 修改。不要将 /v1 同时填进 Base URL 和请求路径。百炼使用 provider=bailian 及 BAILIAN_API_KEY / BAILIAN_BASE_URL；DeepSeek 使用 provider=deepseek 及 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL。PRO 在 dev 中的模型名使用 DEEPSEEK_MODEL_PRO 兼容旧配置，也支持 LLM_MODEL_PRO 覆盖。Key 只留在后端环境，网页不会读写密钥或任意接口地址。

Flyway V7 新增当前访客的模型偏好及任务路由快照，V1–V6 不改写。
