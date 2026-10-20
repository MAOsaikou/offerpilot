<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { listMcpServers, refreshMcpServer, mcpStatus, type McpConnection } from '@/shared/api/mcp'
import type { ToolCallView } from '@/shared/stores/agentRun'
import { identitySignal } from '@/shared/utils/authSession'
const props = defineProps<{ toolCalls: ToolCallView[] }>()
const session = identitySignal()
const servers = ref<McpConnection[]>([])
const loading = ref(false)
const error = ref('')
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listMcpServers()
    session.throwIfAborted()
    servers.value = data
    const discovered = await Promise.allSettled(
      data
        .filter(
          (server) =>
            server.status === 'NOT_REFRESHED' &&
            server.added &&
            server.enabled &&
            server.configured,
        )
        .map((server) => refreshMcpServer(server.provider)),
    )
    session.throwIfAborted()
    for (const result of discovered) {
      if (result.status === 'fulfilled') {
        servers.value = servers.value.map((server) =>
          server.provider === result.value.provider ? result.value : server,
        )
      } else error.value = '部分工具发现失败，请检查连接后重试。'
    }
  } catch {
    if (!session.aborted) error.value = '工具列表加载失败，请重试。'
  } finally {
    loading.value = false
  }
}
function state(server: McpConnection, name: string) {
  const call = [...props.toolCalls]
    .reverse()
    .find(
      (item) =>
        item.name === name &&
        (item.serverName === server.name || item.source === `mcp:${server.name}`),
    )
  return call
    ? { RUNNING: '调用中', OK: '成功', FAILED: '失败', CANCELLED: '已取消' }[call.status]
    : '空闲'
}
onMounted(load)
</script>
<template>
  <section class="space-y-2 text-xs" aria-label="MCP 可用工具">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="font-semibold text-muted-foreground">MCP 可用工具</h3>
      <RouterLink to="/integrations/mcp" class="text-primary">管理连接</RouterLink>
    </div>
    <p v-if="loading" role="status">加载中…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="text-primary" :disabled="loading" @click="load">重新加载工具列表</button>
    <p v-if="!loading && !error && !servers.length">暂无连接</p>
    <div
      v-for="server in servers"
      :key="server.provider"
      class="rounded-xl border border-border/60 p-2"
    >
      <p>{{ server.name }} · {{ mcpStatus(server.status) }}</p>
      <ul v-if="server.tools.length" class="mt-1 space-y-1">
        <li
          v-for="tool in server.tools"
          :key="tool.name"
          class="break-all"
          :title="tool.description"
        >
          {{ tool.name }} · {{ state(server, tool.name) }}
        </li>
      </ul>
      <p v-else class="text-muted-foreground">
        {{ server.status === 'READY' ? '没有可用工具' : '请在管理页配置、启用或刷新工具' }}
      </p>
    </div>
  </section>
</template>
