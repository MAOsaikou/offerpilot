# OfferPilot Agent Guide

## First steps

1. Inspect the relevant code and preserve unrelated working-tree changes.
2. Update the source-of-truth document before changing an interface, database migration, or prompt contract:
   - HTTP, SSE, card schema: `docs/05-接口文档.md`
   - Schema or migration: `docs/06-数据库设计.md`
   - Agent, model, MCP, RAG, or safety behavior: `docs/04-AI技术方案.md`
   - Frontend structure or UX behavior: `docs/07-前端设计规范.md`
   - Deployment or environment behavior: `docs/09-部署与运维.md`

## Backend: modular monolith

`backend/` is a Maven reactor. It builds one deployable Spring Boot application and several domain modules:

- `offerpilot-bootstrap`: the only `@SpringBootApplication`; owns runtime configuration, Flyway migrations, and prompt resources.
- `offerpilot-platform`: technical adapters and cross-cutting concerns such as common errors, IDs, web configuration, model gateway, and MCP integration.
- `offerpilot-agent`: orchestration, events, approvals, and conversations.
- `offerpilot-identity`, `offerpilot-resume`, `offerpilot-job`, `offerpilot-career`, `offerpilot-interview`: business-domain modules.

Keep business rules inside their owning domain module. Expose a small application-level interface for cross-module use; callers must not depend directly on another module's controller, repository, or persistence entity. Keep `offerpilot-platform` free of business rules. Do not add another Spring Boot startup class unless a documented deployment decision requires a genuinely independent process.

Use these commands from `backend/`:

```powershell
mvn -q -B test
mvn -q -B -pl offerpilot-bootstrap -am package
mvn -pl offerpilot-bootstrap -am spring-boot:run
```

## Frontend

Respect the dependency direction `app → widgets → features → shared`. A feature must not import another feature. Keep server contracts in `shared/api` and `shared/types`; render server-driven cards through `widgets/CardRenderer`.

Use these commands from `frontend/` when relevant:

```powershell
pnpm lint
pnpm typecheck
pnpm test -- --run
pnpm build
```

## Change discipline

- Keep API, database, and prompt changes synchronized with their documentation in the same change.
- Add a new Flyway migration; never rewrite an already shared migration.
- Preserve structured Agent events and card contracts as backward-compatible unless both producer and consumer change together.
- Keep secrets in environment variables and out of tracked files.
- Prefer focused changes and tests for the affected module. Do not reset, clean, or delete unrelated work to obtain a clean tree.
