import { setLanguage } from '@/shared/utils/language'
import { createApp, nextTick, reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import McpTools from './McpTools.vue'
import { listMcpServers, refreshMcpServer } from '@/shared/api/mcp'
import type { ToolCallView } from '@/shared/stores/agentRun'
vi.mock('@/shared/api/mcp', async (original) => ({
  ...(await original<typeof import('@/shared/api/mcp')>()),
  listMcpServers: vi.fn(),
  refreshMcpServer: vi.fn(),
}))
let app: ReturnType<typeof createApp>
let host: HTMLDivElement
const flush = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.clearAllMocks()
  setLanguage('zh-CN')
})
it('shows discovered tools, unconfigured providers and live run status changes', async () => {
  vi.mocked(listMcpServers).mockResolvedValue([
    {
      provider: 'jobs',
      name: 'jobs-mcp',
      added: true,
      enabled: true,
      configured: true,
      status: 'READY',
      refreshedAt: null,
      tools: [{ name: 'search_jobs', description: '岗位搜索', inputSchema: {}, callable: true }],
    },
    {
      provider: 'github',
      name: 'GitHub MCP',
      added: false,
      enabled: false,
      configured: false,
      status: 'NOT_CONFIGURED',
      refreshedAt: null,
      tools: [],
    },
  ])
  const props = reactive<{ toolCalls: ToolCallView[] }>({
    toolCalls: [
      {
        id: 'call',
        name: 'search_jobs',
        source: 'MCP',
        serverName: 'jobs-mcp',
        status: 'RUNNING',
        access: 'READ',
        args: {},
      },
    ],
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/integrations/mcp', component: { template: '<div />' } }],
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(McpTools, props).use(router)
  app.mount(host)
  await flush()
  expect(host.textContent).toContain('search_jobs · 调用中')
  expect(host.textContent).toContain('GitHub MCP · 未配置')
  expect(host.querySelector('a')?.getAttribute('href')).toBe('/integrations/mcp')
  setLanguage('en-US')
  await nextTick()
  expect(host.textContent).toContain('Jobs MCP')
  expect(host.textContent).toContain('search_jobs · Calling')
  setLanguage('zh-CN')
  props.toolCalls[0]!.status = 'FAILED'
  await nextTick()
  expect(host.textContent).toContain('search_jobs · 失败')
  props.toolCalls[0]!.status = 'OK'
  await nextTick()
  expect(host.textContent).toContain('search_jobs · 成功')
})

it('discovers tools for enabled connections on first load and supports offline retry', async () => {
  const initial = {
    provider: 'jobs' as const,
    name: 'jobs-mcp',
    added: true,
    enabled: true,
    configured: true,
    status: 'NOT_REFRESHED',
    refreshedAt: null,
    tools: [],
  }
  vi.mocked(listMcpServers)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce([initial])
  vi.mocked(refreshMcpServer).mockResolvedValue({
    ...initial,
    status: 'READY',
    tools: [{ name: 'list_sites', description: '站点', inputSchema: {}, callable: true }],
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/integrations/mcp', component: { template: '<div />' } }],
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(McpTools, { toolCalls: [] }).use(router)
  app.mount(host)
  await flush()
  expect(host.textContent).toContain('工具列表加载失败')
  host.querySelector('button')!.click()
  await vi.waitFor(() => expect(host.textContent).toContain('list_sites · 空闲'))
  expect(refreshMcpServer).toHaveBeenCalledWith('jobs')
})
