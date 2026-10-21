import { setLanguage } from '@/shared/utils/language'
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import McpManagementView from './McpManagementView.vue'
import {
  listMcpServers,
  addMcpServer,
  toggleMcpServer,
  refreshMcpServer,
  callMcpTool,
  type McpConnection,
} from '@/shared/api/mcp'
vi.mock('@/shared/api/mcp', async (original) => ({
  ...(await original<typeof import('@/shared/api/mcp')>()),
  listMcpServers: vi.fn(),
  addMcpServer: vi.fn(),
  toggleMcpServer: vi.fn(),
  refreshMcpServer: vi.fn(),
  callMcpTool: vi.fn(),
}))
let app: ReturnType<typeof createApp>
let host: HTMLDivElement
const connection = (overrides: Partial<McpConnection> = {}): McpConnection => ({
  provider: 'github',
  name: 'GitHub MCP',
  added: false,
  enabled: false,
  configured: false,
  status: 'NOT_CONFIGURED',
  tools: [],
  refreshedAt: null,
  ...overrides,
})
const flush = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
const button = (label: string) =>
  [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === label)!
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
function mount() {
  app = createApp(McpManagementView)
  app.mount(host)
}
it('shows unconfigured GitHub, persists add and keeps previous state after a failed toggle', async () => {
  vi.mocked(listMcpServers).mockResolvedValue([connection()])
  vi.mocked(addMcpServer).mockResolvedValue(connection({ added: true, enabled: true }))
  vi.mocked(toggleMcpServer).mockRejectedValue(new Error('offline'))
  mount()
  await flush()
  expect(host.textContent).toContain('未配置')
  expect(host.querySelector('input[type=password]')).toBeNull()
  expect(button('刷新工具').disabled).toBe(true)
  button('添加连接').click()
  await flush()
  expect(addMcpServer).toHaveBeenCalledWith('github')
  button('停用').click()
  await flush()
  expect(toggleMcpServer).toHaveBeenCalledWith('github', false)
  expect(host.textContent).toContain('操作失败')
  expect(button('停用')).toBeDefined()
})
it('refreshes real schemas and only calls selected read tools with valid JSON objects', async () => {
  const ready = connection({
    added: true,
    enabled: true,
    configured: true,
    status: 'READY',
    tools: [
      {
        name: 'search_code',
        description: 'Search code',
        inputSchema: { type: 'object' },
        callable: true,
      },
      { name: 'push_files', description: 'Write files', inputSchema: {}, callable: false },
    ],
  })
  vi.mocked(listMcpServers).mockResolvedValue([
    connection({ added: true, enabled: true, configured: true, status: 'NOT_REFRESHED' }),
  ])
  vi.mocked(refreshMcpServer).mockResolvedValue(ready)
  vi.mocked(callMcpTool)
    .mockResolvedValueOnce({ preview: '公开搜索结果' })
    .mockRejectedValueOnce(new Error('offline'))
  mount()
  await flush()
  button('刷新工具').click()
  await flush()
  expect(refreshMcpServer).toHaveBeenCalledWith('github')
  expect(host.querySelectorAll('li button')).toHaveLength(1)
  button('选择工具').click()
  await flush()
  const input = host.querySelector('textarea')!
  const submit = async (text: string) => {
    input.value = text
    input.dispatchEvent(new Event('input'))
    await nextTick()
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await flush()
  }
  await submit('[]')
  expect(callMcpTool).not.toHaveBeenCalled()
  expect(host.textContent).toContain('参数须为 JSON 对象')
  await submit('{"query":"language:java"}')
  expect(callMcpTool).toHaveBeenCalledWith('github', 'search_code', { query: 'language:java' })
  expect(host.textContent).toContain('调用成功')
  expect(host.textContent).toContain('公开搜索结果')
  await submit('{}')
  expect(host.textContent).toContain('调用失败')
  expect(host.textContent).not.toContain('公开搜索结果')
})
it('supports retry after offline loading and an empty response', async () => {
  vi.mocked(listMcpServers).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
  mount()
  await flush()
  expect(host.textContent).toContain('无法加载')
  button('重新加载').click()
  await flush()
  expect(host.textContent).toContain('暂无连接')
})

afterEach(() => setLanguage('zh-CN'))

it('localizes provider display by ID without changing the server name', async () => {
  const search = connection({ provider: 'search', name: '搜索 MCP' })
  vi.mocked(listMcpServers).mockResolvedValue([search])
  mount()
  await flush()
  expect(host.querySelector('h2')?.textContent).toContain('搜索 MCP')
  setLanguage('en-US')
  await nextTick()
  expect(host.querySelector('h2')?.textContent).toContain('Search MCP')
  expect(host.querySelector('h2')?.textContent).not.toContain('搜索')
  expect(search.name).toBe('搜索 MCP')
})
