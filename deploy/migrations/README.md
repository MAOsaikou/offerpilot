# 两个功能分支合并后的迁移升级

合并后唯一版本为 V1–V15。main 的 V11 认证账号、V12 公开账号停用保持不变；功能分支原 V11 简历建议批次、V12 定向改写、V13 岗位板分别对应 V13/V14/V15，SQL 内容及校验和保持原样。两个功能分支存在继承关系，共有迁移只执行一次。

新数据库、已执行 main V11 或 V12 的数据库正常启动即可，不运行历史对齐脚本。已经执行旧功能分支迁移的数据库按以下流程升级；本次开发仅验证一次性数据库，没有升级任何现有开发或线上库。

1. 停止所有连接该数据库的应用实例，完整备份数据库，并检查 `public.flyway_schema_history`。旧分支 V11/V12/V13 应分别是 `resume_patch_batches`、`resume_optimizations`、`applications`；不是此历史时停止处理。
2. 用 PostgreSQL psql 执行本目录 `align-legacy-feature-history.sql`，开启 `ON_ERROR_STOP=1`。脚本仅接受原成功 SQL 的精确版本、脚本名、描述与校验和，检查已建表；未知、修改过、失败或不匹配的历史会整笔拒绝。它在一个事务中锁住历史表，复制原历史到 `offerpilot_legacy_flyway_history_backup`，再将 11/12/13 映射到 13/14/15，不修改用户、简历、改写或岗位数据。
3. 仅第一次启动设置 `SPRING_FLYWAY_OUT_OF_ORDER=true`，正常运行新后端。Flyway 补执行尚未执行的认证 V11/V12 和其余功能迁移。配置随机 JWT_SECRET；两个公开测试账号将停用，原业务用户与数据保留。
4. 确认最新版本为 15、认证与功能迁移各一条且全部成功后，移除 `SPRING_FLYWAY_OUT_OF_ORDER` 并重启，恢复严格顺序校验。保留原库备份与历史备份表。历史对齐可重复运行，不会覆盖原备份；它不替代数据库完整备份。

从仓库根目录使用现有 Compose PostgreSQL 时，先通过同一份 `.env.local` 确认目标实例，再执行：

```powershell
docker compose --env-file backend/.env.local -f deploy/docker-compose.yml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | Set-Content -Encoding utf8 offerpilot-before-migration.sql
# 核实备份命令成功、备份文件可读，再继续。
Get-Content -Raw -Encoding utf8 deploy/migrations/align-legacy-feature-history.sql | docker compose --env-file backend/.env.local -f deploy/docker-compose.yml exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
```

以上命令会访问所配置的实际数据库，必须由该数据库的负责人按已核实的目标执行。脚本固定支持 `public` schema；自定义 schema、其它分支历史或校验和不一致需要单独处理，不能用关闭校验或任意 repair 掩盖问题。
