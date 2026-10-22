# OfferPilot MCP 客户端接入

后端外部 MCP Server 默认关闭。部署方通过环境变量开启 `MCP_SERVER_ENABLED=true`，并注入 `MCP_SERVER_API_KEY`；不要把真实密钥写入仓库、截图或命令输出。服务使用 Spring AI 1.0 的 SSE transport：`/sse` 建立连接，`/mcp/message` 接收消息，均要求 `X-API-Key`。不能把独立 jobs-mcp 的 Streamable HTTP `/mcp` URL 用作此后端服务地址。

Cursor 配置示例（本地 `.cursor/mcp.json`；环境引用语法需与所用 Cursor 版本核对）：

```json
{
  "mcpServers": {
    "offerpilot": {
      "url": "http://localhost:8081/sse",
      "headers": {
        "X-API-Key": "${env:MCP_SERVER_API_KEY}"
      }
    }
  }
}
```

先在启动 Cursor 的进程环境中提供同一个 `MCP_SERVER_API_KEY`，再启用连接、刷新工具列表；不要将变量展开后的配置提交。如果客户端版本不支持此种环境引用，应通过其安全凭据设置提供请求头。部署到公网时由 HTTPS 入口转发 SSE，并保护该共享服务密钥。

| 工具 | 示例参数 | 行为 |
|---|---|---|
| diagnose_resume | `{"resumeText":"在课程项目中使用 Java 和 Redis","jobDescription":"Java 后端开发"}` | 基于提供文本诊断，不读取用户简历、不保存诊断报告；JD 可省略 |
| search_jobs | `{"query":"Java","nature":"campus","location":"北京","limit":5}` | 搜索公开岗位；可更新公开缓存，离线回退明确标注 |
| match_job | `{"resumeText":"课程项目使用 Java","jobDescription":"要求 Java 开发经验"}` | 基于提供文本匹配，不保存历史 |

旧工具名 `resume.diagnose` / `jobs.search` / `jobs.match` 保留并委托同一实现。诊断/匹配可能调用已配置模型，只读表示不写用户业务数据，并不表示完全无模型调用或计费。服务密钥不授予任何用户的私有记录访问权。

站内 `/integrations/mcp` 管理页管理的是 OfferPilot **作为客户端**连接的 jobs/search/GitHub；它与上面 **作为服务端**暴露工具的开关、鉴权相互独立。当前聊天自动编排仍只使用岗位 MCP，其他连接可在管理页显式只读调用。

本轮仅离线协议/权限测试与前端门禁；真实 Cursor 连接、GitHub token/权限、搜索供应商、jobs-mcp HTTP 及本地 bge-m3 推理未在沙箱实测。不能将此配置示例当作 Cursor 验收已通过的证据。
