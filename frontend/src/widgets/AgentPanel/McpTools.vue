<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  listMcpServers,
  refreshMcpServer,
  mcpProviderName,
  mcpStatus,
  type McpConnection,
} from '@/shared/api/mcp'
import type { ToolCallView } from '@/shared/stores/agentRun'
import { identitySignal } from '@/shared/utils/authSession'
const props = defineProps<{ toolCalls: ToolCallView[] }>()
const session = identitySignal()
const servers = ref<McpConnection[]>([])
const loading = ref(false)
const error = localizedText()
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
      } else
        error.value = () =>
          uiText('Some tools could not be discovered. Check connections and retry.')
    }
  } catch {
    if (!session.aborted) error.value = () => uiText('Could not load tool list. Please retry.')
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
    ? {
        RUNNING: uiText('Calling'),
        OK: uiText('Succeeded'),
        FAILED: uiText('Failed'),
        CANCELLED: uiText('Canceled'),
      }[call.status]
    : uiText('Idle')
}
onMounted(load)
</script>
<template>
  <section class="space-y-2 text-xs" :aria-label="uiText('Available MCP tools')">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="font-semibold text-muted-foreground">{{ uiText('Available MCP tools') }}</h3>
      <RouterLink to="/integrations/mcp" class="text-primary">{{
        uiText('Manage connections')
      }}</RouterLink>
    </div>
    <p v-if="loading" role="status">{{ uiText('Loading… #2') }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="text-primary" :disabled="loading" @click="load">
      {{ uiText('Reload tool list') }}
    </button>
    <p v-if="!loading && !error && !servers.length">{{ uiText('No connections') }}</p>
    <div
      v-for="server in servers"
      :key="server.provider"
      class="rounded-xl border border-border/60 p-2"
    >
      <p>{{ mcpProviderName(server) }} · {{ mcpStatus(server.status) }}</p>
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
        {{
          server.status === 'READY'
            ? uiText('No available tools')
            : uiText('Configure, enable or refresh tools on the management page')
        }}
      </p>
    </div>
  </section>
</template>
