import { translate as uiText } from '@/shared/utils/language'
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
// Display by stable provider ID; keep server.name intact for tool-event matching.
export const mcpProviderName = (server: Pick<McpConnection, 'provider' | 'name'>) =>
  ({ jobs: uiText('Jobs MCP'), search: uiText('Search MCP'), github: uiText('GitHub MCP') })[
    server.provider
  ] ?? server.name

export const mcpStatus = (status: string) =>
  ({
    READY: uiText('Available'),
    NOT_CONFIGURED: uiText('Unconfigured'),
    NOT_ADDED: uiText('Not added'),
    DISABLED: uiText('Disabled'),
    NOT_REFRESHED: uiText('Refresh needed'),
    UNAVAILABLE: uiText('Connection failed'),
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
