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

第一次准备运行包：在仓库根目录执行 `powershell -NoProfile -ExecutionPolicy Bypass -File deploy/windows/prepare.ps1`。它使用现有 Node/pnpm 构建，并将 Nginx 下载到 D 盘；不安装到 C 盘，不删除旧文件。依赖缺失时先在 frontend 执行 `pnpm install --frozen-lockfile`。后端预览需要已有 bootstrap JAR；缺少时在 backend 运行 `mvn -q -B -pl offerpilot-bootstrap -am package`。

## 当前能力

工作台、职业偏好、简历文本导入/编辑/版本/导出、示例岗位筛选/收藏、看板阶段/备注、固定题库面试/复盘、成长任务、知识笔记、通知和主题均可在本地使用。业务数据保存在当前浏览器，不是服务器数据；清理浏览器数据前可从设置导出 JSON 备份。

AI 对话调用真实 `/api/v1/agent/chat`，支持 DeepSeek 单轮流式回复、模式/档位、错误重试、停止接收与执行面板。现有后端尚未实现 Agent 编排、认证、PDF/DOCX 解析、业务持久化或 RAG；页面明确标注这些边界。

联调后端使用 dev profile，正常连接 PostgreSQL/Redis 并运行 Flyway 迁移。统一入口管理后端在 8081；Nginx 代理无需修改。若 8081 被占用，脚本保留现有服务。

## 验证

前端 lint、typecheck、23 项 Vitest 测试和生产 build 已通过。2026-09-14 后端打包与本机模拟 DeepSeek HTTP 集成测试通过；真实数据库和真实模型验收仍需启动 Docker 依赖并填写 Key。此前 Nginx 的回显验证不代表当前 DeepSeek 与数据库已经完成实机验证。

界面使用 Sites 前端技能的设计与布局指导，保留 Vue 项目结构。未执行浏览器截图或交互自动化，因此响应式与视觉效果仍可在浏览器中进一步验收。
