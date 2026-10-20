import { apiRequest } from './client'

export interface McpTool {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  callable: boolean
}
export interface McpConnection {
  provider: 'jobs' | 'search' | 'github'
  name: string
  added: boolean
  enabled: boolean
  configured: boolean
  status: string
  tools: McpTool[]
  refreshedAt: string | null
}
export const mcpStatus = (status: string) =>
  ({
    READY: '可用',
    NOT_CONFIGURED: '未配置',
    NOT_ADDED: '未添加',
    DISABLED: '已停用',
    NOT_REFRESHED: '待刷新',
    UNAVAILABLE: '连接失败',
  })[status] ?? status
export const listMcpServers = () => apiRequest<McpConnection[]>('/mcp/servers')
export const addMcpServer = (provider: string) =>
  apiRequest<McpConnection>('/mcp/servers', {
    method: 'POST',
    body: JSON.stringify({ provider }),
  })
export const toggleMcpServer = (provider: string, enabled: boolean) =>
  apiRequest<McpConnection>(`/mcp/servers/${provider}`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled }),
  })
export const refreshMcpServer = (provider: string) =>
  apiRequest<McpConnection>(`/mcp/servers/${provider}/refresh`, { method: 'POST' })
export const callMcpTool = (provider: string, tool: string, args: Record<string, unknown>) =>
  apiRequest<{ preview: string }>(`/mcp/servers/${provider}/call`, {
    method: 'POST',
    body: JSON.stringify({ tool, arguments: args }),
  })
