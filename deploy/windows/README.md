# Windows 前端与联调

运行包位置：`D:\OfferPilot\nginx-1.31.5\`。Nginx 来自 [官方 Windows 下载](https://nginx.org/en/download.html)，控制命令参考 [Windows 文档](https://nginx.org/en/docs/windows.html)。项目源码仍在 E 盘，未改变已有开发工具位置。

## 直接使用

- 双击根目录 `启动联调.cmd`：启动前端与独立的后端回显预览，打开 http://localhost:8080 。后台日志位于 `D:\OfferPilot\backend-preview.log`。
- 仅查看前端：双击 `启动前端.cmd`，也可以直接双击 `D:\OfferPilot\nginx-1.31.5\nginx.exe`。
- 修改页面后：双击 `更新前端.cmd`，重新构建并更新运行目录。
- 停止：`停止联调.cmd` 或 `停止前端.cmd`。只操作本项目启动的对应进程。
- 给别人分发：双击根目录 `打包前端.cmd`，会在 `D:\OfferPilot\releases\` 生成带时间戳的 ZIP。对方解压后只需双击包内 `启动前端.cmd`；后端不包含在此包内。

第一次准备运行包：在仓库根目录执行 `powershell -NoProfile -ExecutionPolicy Bypass -File deploy/windows/prepare.ps1`。它使用现有 Node/pnpm 构建，并将 Nginx 下载到 D 盘；不安装到 C 盘，不删除旧文件。依赖缺失时先在 frontend 执行 `pnpm install --frozen-lockfile`。后端预览需要已有 bootstrap JAR；缺少时在 backend 运行 `mvn -q -B -pl offerpilot-bootstrap -am package`。

## 当前能力

工作台、职业偏好、简历文本导入/编辑/版本/导出、示例岗位筛选/收藏、看板阶段/备注、固定题库面试/复盘、成长任务、知识笔记、通知和主题均可在本地使用。业务数据保存在当前浏览器，不是服务器数据；清理浏览器数据前可从设置导出 JSON 备份。

AI 对话调用真实 `/api/v1/agent/chat`，支持 SSE 展示、模式/档位、错误重试、停止接收与执行面板。现有后端仅回显输入，没有真实 Agent 编排、模型回答、认证、PDF/DOCX 解析、业务持久化或 RAG；页面明确标注这些边界。

预览后端使用 test profile 和 Spring Boot 4 正确的自动配置排除项，不连接 PostgreSQL/Redis。不应把它当作生产后端。要连接正式开发后端，停止预览并自行启动后端在 8081；Nginx 代理无需修改。若 8081 被占用，脚本保留现有服务。

## 验证

前端 lint、typecheck、23 项 Vitest 测试和生产 build 已通过。Nginx 配置检查通过；首页与 `/jobs/j1` 等深层路径返回 200，缺失静态资源返回 404；现有后端预览经过 Nginx 的 health 返回 UP，SSE 收到 run.started、text.delta、run.completed。

界面使用 Sites 前端技能的设计与布局指导，保留 Vue 项目结构。未执行浏览器截图或交互自动化，因此响应式与视觉效果仍可在浏览器中进一步验收。
