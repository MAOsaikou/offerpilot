# 演示运维脚本

全部从已配置好的宿主机运行；公网步骤见 [部署文档](../../docs/09-部署与运维.md)。

## 发布前配置检查

Node.js 22+与Docker Compose 2.24.4+；从仓库根执行：

```bash
node deploy/scripts/preflight.mjs --env .env
node --test deploy/scripts/preflight.test.mjs
```

preflight只渲染Compose，不启动/停止服务，不访问模型、写数据或打印凭据。它拒绝占位域名/密钥、S3Mock、未固定摘要的存储镜像、公开数据端口、共享体验或缺少额度，以及存储公网签名地址不一致。通过只代表配置检查；真实镜像、DNS/TLS、完整旅程和数据库/对象配套恢复仍要验收。当前生产模板保留占位值，直接检查该模板应失败。

使用其他忽略的env文件时，preflight内部将OFFERPILOT_ENV_FILE设为该绝对路径，使后端env_file与Compose插值一致；真正启动及后续运维也要设置同一变量：

```bash
export OFFERPILOT_ENV_FILE=/opt/offerpilot/.env
docker compose --env-file "$OFFERPILOT_ENV_FILE" -f deploy/docker-compose.yml -f deploy/docker-compose.storage-auth.example.yml --profile full up -d --build
```

根.env默认用法无需此变量。不要将 `docker compose config` 的完整展开输出写进报告或分享；可能包含凭据。默认本地S3Mock与认证覆盖使用不同卷，不能直接互换数据格式。

## 认证存储与只读代理验收

先按09配置认证MinIO，设置STORAGE_DOMAIN并将其DNS指向部署主机；MINIO_PUBLIC_ENDPOINT填对应https地址。覆盖文件挂载Caddyfile.storage-auth，只允许GET/HEAD并保留S3签名的Host/路径/查询，其他方法405；默认本地配置不挂载。当前应用鉴权下载API保持不变。脚本需要准备好的内部S3地址、外部代理地址、访问凭据和专用测试桶：

```bash
# 在受控shell环境注入 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD，不放进命令历史或脚本正文。
export STORAGE_INTERNAL_URL=http://127.0.0.1:9000
export STORAGE_PUBLIC_URL=https://storage.your-domain.tld
export MINIO_BUCKET=offerpilot-verification
node deploy/scripts/storage-smoke.mjs
```

脚本使用标准AWS SigV4，区域us-east-1，仅支持此MinIO模板的path-style端点。不存在的专用桶会创建；写入UUID测试对象，核对正确凭据读写、错误密钥403、匿名403、签名代理200且字节一致、公开PUT/DELETE405；finally只删除这一个已上传对象，不删除桶、其他对象或文件。HTTP(S)请求最多30秒，HTTPS验证默认开启，不接受URL里的凭据或路径/查询。用于外部云服务时应采用其区域/官方SDK与对应策略验收。

## 数据库备份

```bash
bash deploy/scripts/backup.sh
```

脚本读取容器实际POSTGRES_USER/POSTGRES_DB，不依赖宿主机默认库名。每次新建唯一备份目录，成功后将单个 `database.sql.gz.partial` 改名为 `database.sql.gz`；dump/gzip失败非零退出且保留.partial，不冒充完整备份。保留所有旧文件，无自动保留期或批量清理。只备份PostgreSQL，对象仍需存储服务配套快照。非默认数据库/用户的真实备份→新库恢复、30天旧备份保留已在独立临时PostgreSQL验证。

## 健康探针

```bash
/opt/offerpilot/deploy/scripts/healthcheck.sh https://demo.example.com
# 也可使用 PUBLIC_URL 环境变量。HTTP 失败、超时、空响应或非 UP 均非零退出。
```

探针保留 TLS 验证，连接超时 10 秒、整次最多 30 秒，只输出健康结论。适合 cron 的退出码监控；Uptime Kuma 可直接监控同一 URL 的 `/actuator/health`，要求 HTTP 200 且 JSON `status=UP`。`/healthz` 只证明 Caddy 存活，不代替应用探针。

```cron
# 每五分钟；PUBLIC_URL 不带 /api/v1。cron 本身不自动发送告警，另接退出码告警。
*/5 * * * * /opt/offerpilot/deploy/scripts/healthcheck.sh https://demo.example.com >> /var/log/offerpilot-health.log 2>&1
```

## 重置：应用内定时与宿主机 cron 二选一

推荐 `DEMO_RESET_CRON="0 0 4 * * *"`，Spring 六字段，按后端时区执行（默认容器 UTC）。宿主机 cron 是五字段、使用宿主机时区；选择它时设置 `DEMO_RESET_CRON=-`，避免重复重置。

`reset-demo.sh` 要求 ACTIVE STAFF JWT。由受控登录流程在运行前刷新 `/etc/offerpilot/reset.env`（权限 600，含 `export STAFF_TOKEN=...`、`export API_BASE_URL=https://demo.example.com/api/v1`）；JWT 当前 12 小时有效，不能把一次登录 token 当永久 cron 凭据。不得把密码/token 写进 crontab、仓库或日志。脚本无自动 STAFF 登录或 JWT 续期，过期时非零退出。

```cron
# /etc/offerpilot/reset.env 必须在本次执行前刷新；避免 shell 调试模式。
0 4 * * * /bin/bash -c '. /etc/offerpilot/reset.env && exec /bin/bash /opt/offerpilot/deploy/scripts/reset-demo.sh' >> /var/log/offerpilot-reset.log 2>&1
```

共享演示数据会丢失，禁止录入真实个人信息。`backup.sh` 仅备份 PostgreSQL；还要使用认证存储自己的快照/备份方法配套备份对象、验证恢复，并为 cron 日志设置宿主机 logrotate。备份命令也须使用同一项目名、环境文件和存储覆盖，避免操作另一套栈。
