# 贡献指南 · OfferPilot

> 与 `docs/08-开发计划与分工.md` §4 保持一致，冲突时以本文档为准。

## 1. 分支模型

| 分支 | 用途 | 说明 |
| --- | --- | --- |
| `main` | 可部署 | 只从 `dev` 合入，受保护，禁止直接 push |
| `dev` | 集成 | 日常 PR 的合入目标 |
| `feat/<scope>-<desc>` | 新功能 | 例：`feat/frontend-chat` |
| `fix/<scope>-<desc>` | 缺陷修复 | 例：`fix/resume-upload` |

- 每周把 `dev` 合回 `main` 并打 tag（`v0.x`）。
- 禁止对 `main` / `dev` 执行 `git push --force`。
- 开工前先 `git fetch origin`，个人分支用 `git rebase origin/dev` 同步主线。

## 2. 提交信息

Conventional Commits：`<type>(<scope>): <subject>`

- type：`feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore` `revert`
- scope：`frontend` `backend` `agent` `chat` `resume` `job` `board` `interview` `career` `kb` `mcp` `deploy` `docs`
- 赛方要求保留真实开发过程，**不得用 squash 覆盖历史**。

## 3. PR 规范

- 每个 PR 至少一名非作者评审。
- 评审者须能复述实现思路（应对答辩「须能清晰解释所有代码逻辑」）。
- CI 必须全绿：前端 `pnpm lint && pnpm typecheck && pnpm test`。
- PR 描述使用 `.github/pull_request_template.md` 模板。

## 4. 文档同步

- 接口变更：先改 `docs/05-接口文档.md`，再实现，PR 附 OpenAPI diff。
- 数据库变更：新增 Flyway 版本，禁止修改已合并的迁移。
- Prompt 变更：必须跑对应评测并在 PR 描述贴指标。

## 5. 前端约定

- 包管理器统一使用 **pnpm**，不允许提交 `package-lock.json` / `yarn.lock`。
- 新增依赖前先确认 `docs/07-前端设计规范.md` 的技术栈，避免引入同类库。
- 目录依赖方向固定：`app → widgets → features → shared`，feature 之间不得互相 import。

## 6. 禁止提交

- `.env`、任何密钥（`gitleaks` 会拦截）
- `node_modules/`、`dist/` 等构建产物
- 大于 5MB 的文件（走 Git LFS 或 MinIO）
