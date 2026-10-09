# 演示运维脚本

全部从已配置好的宿主机运行；公网步骤见 [部署文档](../../docs/09-部署与运维.md)。

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
