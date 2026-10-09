# Windows 前端与联调

运行包位置：`D:\OfferPilot\nginx-1.31.5\`。Nginx 来自 [官方 Windows 下载](https://nginx.org/en/download.html)，控制命令参考 [Windows 文档](https://nginx.org/en/docs/windows.html)。项目源码仍在 E 盘，未改变已有开发工具位置。

## 直接使用

- 根目录只保留一个 [OfferPilot.cmd](../../OfferPilot.cmd)，双击进入彩色中文菜单。
- 首次使用：先安装并打开 Docker Desktop，菜单选「8 配置 DeepSeek」，填好 Key 后选「1 启动全部」。自动启动 PostgreSQL/Redis/MinIO、构建测试后端、启动后端、更新启动前端。访问 http://localhost:8080 。
- 日常操作：「2 停止前后端」「3 查看运行状态」「4 更新前端」「5 重建并重启后端」「6 仅启动前端」。停止只操作本项目管理的进程，数据库容器保持运行；退出菜单也不停止服务。
- 「7 打包前端」在 `D:\OfferPilot\releases\` 生成 ZIP；分发包内的启动/停止脚本仍独立可用，后端不包含在包内。
- 「9 查看后端日志」打开 `D:\OfferPilot\backend-preview.log`。配置入口只打开文件，不打印 Key。
- 旧 7 个脚本逐个移到 `deploy/windows/legacy/`，转发到统一入口对应动作，没有删除。旧根目录快捷方式请改为指向 `OfferPilot.cmd`。
- 命令行也可使用 `OfferPilot.cmd -Action start-backend`、`-Action stop-frontend` 等动作；`-DryRun` 只显示执行步骤，不启动、停止或构建服务。

后端构建在 `backend/target/preview-builds/<唯一标识>/backend/` 的独立源码副本执行 Maven Wrapper `package`（包含测试），不运行 `clean`、不删除已有文件，也不因 JAR 锁停止 Java。副本包含模块 POM、源码、Wrapper 和迁移测试夹具，不复制本机环境凭据。构建失败保留原后端；成功后核对受管进程并停止，确认退出后从新副本的 JAR 启动。启动仍以原项目目录为工作目录，读取原 `backend/.env.local`。构建副本和失败日志保留，脚本不自动清理。

进程记录仍位于 `D:\OfferPilot\backend-preview.json`，新增项目根目录和 JAR 路径；停止须同时匹配 PID、启动时间、Java 可执行路径、项目根目录和实际 `-jar` 参数。旧记录只接受当前项目原始 target 中的精确 JAR 路径。其他项目副本、无法验证的进程及已有的非受管 8081 服务均保留，不按名称片段查找或强停 Java。停止失败会阻止新版启动。

第一次准备运行包：在仓库根目录执行 `powershell -NoProfile -ExecutionPolicy Bypass -File deploy/windows/prepare.ps1`。它使用现有 Node/pnpm 构建，并将 Nginx 下载到 D 盘；不安装到 C 盘，不删除旧文件。依赖缺失时先在 frontend 执行 `pnpm install --frozen-lockfile`。后端预览需要已有 bootstrap JAR；缺少时在 backend 运行 `mvn -q -B -pl offerpilot-bootstrap -am package`。

## 当前能力

已提供邮箱密码注册、JWT 用户登录和客服登录。真实岗位搜索与详情、岗位收藏、看板阶段与跟进备注、上传简历的原件/解析结果/版本、对话历史及岗位匹配报告和资料快照均由服务端保存。注册用户可在其他浏览器用邮箱密码登录同一账号；访客使用浏览器保存的令牌，暂不支持跨设备找回。

本地简历草稿与文本导出、旧看板归档、职业偏好、固定题库练习/复盘、成长任务、知识笔记、通知和主题仍保存在当前浏览器。清理浏览器数据前可从设置导出本地工作区 JSON 备份；该备份不包含服务端数据、登录凭据或主题偏好。

AI 对话调用真实 `/api/v1/agent/chat`，支持 DeepSeek 多轮流式回复、模式/档位、停止接收、执行面板与会话历史保存。后端已提供基础 Agent 编排、岗位搜索/匹配、简历诊断/定向改写，以及 PDF/DOCX 等格式的上传解析；职业探索、面试和成长教练的真实业务能力、审批流程与 RAG 仍为规划，知识笔记尚未接入 RAG。

联调后端使用 dev profile，正常连接 PostgreSQL/Redis 并运行 Flyway 迁移。统一入口管理后端在 8081；Nginx 代理无需修改。若 8081 被占用，脚本保留现有服务。

## 验证

部署生命周期回归可运行 `powershell -NoProfile -ExecutionPolicy Bypass -File deploy/windows/tests/backend-lifecycle.Tests.ps1`。测试截获服务进程操作，覆盖受管归属、其他项目副本、旧记录、PID 复用、端口占用、停止失败、原生构建失败以及 DryRun；只在忽略的 `backend/target/deployment-tests/` 生成构建夹具，不停止真实服务。

前端 lint、typecheck、23 项 Vitest 测试和生产 build 已通过。2026-09-14 后端打包与本机模拟 DeepSeek HTTP 集成测试通过；真实数据库和真实模型验收仍需启动 Docker 依赖并填写 Key。此前 Nginx 的回显验证不代表当前 DeepSeek 与数据库已经完成实机验证。

界面使用 Sites 前端技能的设计与布局指导，保留 Vue 项目结构。未执行浏览器截图或交互自动化，因此响应式与视觉效果仍可在浏览器中进一步验收。
