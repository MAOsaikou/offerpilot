<script setup lang="ts">
import { localizedText, language, translate as uiText } from '@/shared/utils/language'

import { onMounted, ref } from 'vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import {
  listMcpServers,
  addMcpServer,
  toggleMcpServer,
  refreshMcpServer,
  callMcpTool,
  mcpStatus,
  type McpConnection,
} from '@/shared/api/mcp'
import { identitySignal } from '@/shared/utils/authSession'
const session = identitySignal()
const servers = ref<McpConnection[]>([])
const busy = ref(false)
const error = localizedText()
const notice = ref('')
const selected = ref('')
const argumentsText = ref('{}')
const preview = ref('')
const callStatus = localizedText()
async function load() {
  busy.value = true
  error.value = ''
  try {
    const value = await listMcpServers()
    session.throwIfAborted()
    servers.value = value
  } catch {
    if (!session.aborted) error.value = () => (uiText("Could not load MCP connections. Check your connection and retry."))
  } finally {
    busy.value = false
  }
}
async function change(server: McpConnection, action: 'add' | 'toggle' | 'refresh') {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const value =
      action === 'add'
        ? await addMcpServer(server.provider)
        : action === 'toggle'
          ? await toggleMcpServer(server.provider, !server.enabled)
          : await refreshMcpServer(server.provider)
    session.throwIfAborted()
    servers.value = servers.value.map((item) => (item.provider === value.provider ? value : item))
    selected.value = ''
    preview.value = ''
    callStatus.value = ''
    notice.value = `${value.name}：${mcpStatus(value.status)}`
  } catch {
    if (!session.aborted) error.value = () => (uiText("Operation failed. Settings were not confirmed saved. Please retry."))
  } finally {
    busy.value = false
  }
}
function selectTool(provider: string, name: string) {
  selected.value = `${provider}:${name}`
  argumentsText.value = '{}'
  preview.value = ''
  callStatus.value = ''
}
async function call() {
  if (busy.value || !selected.value) return
  error.value = ''
  preview.value = ''
  let args: Record<string, unknown>
  try {
    const value: unknown = JSON.parse(argumentsText.value)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error()
    args = value as Record<string, unknown>
  } catch {
    error.value = () => (uiText("Parameters must be a JSON object."))
    return
  }
  const [provider, tool] = selected.value.split(':')
  if (!provider || !tool) return
  busy.value = true
  callStatus.value = () => (uiText("Calling"))
  try {
    const value = await callMcpTool(provider, tool, args)
    session.throwIfAborted()
    preview.value = value.preview
    callStatus.value = () => (uiText("Call succeeded"))
  } catch {
    if (!session.aborted) {
      error.value = () => (uiText("Tool call failed. Check the connection and parameters, then retry."))
      callStatus.value = () => (uiText("Call failed"))
    }
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('MCP connections')"
      :description="uiText('Add registered services and manage your connections and read-only tools.')"
    />
    <p class="helper-note"> {{ uiText("Service addresses and secrets are configured on the server. GitHub appears unconfigured without a token. Secrets are not collected or displayed in the browser.") }} </p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button class="btn" :disabled="busy" @click="load">{{ busy ? uiText("Processing…") : uiText("Reload #2") }}</button>
    <p v-if="!busy && !servers.length && !error">{{ uiText("No connections yet.") }}</p>
    <article v-for="server in servers" :key="server.provider" class="panel form-stack">
      <h2>{{ server.name }} · {{ mcpStatus(server.status) }}</h2>
      <p v-if="server.refreshedAt" class="subtle"> {{ uiText("Last refreshed: {0}", [new Date(server.refreshedAt).toLocaleString(language)]) }}
      </p>
      <div class="filter-bar">
        <button v-if="!server.added" class="btn" :disabled="busy" @click="change(server, 'add')"> {{ uiText("Add connection") }} </button>
        <button v-else class="btn" :disabled="busy" @click="change(server, 'toggle')">
          {{ server.enabled ? uiText("Disable #2") : uiText("Enable") }}
        </button>
        <button
          class="btn"
          :disabled="busy || !server.added || !server.enabled || !server.configured"
          @click="change(server, 'refresh')"
        > {{ uiText("Refresh tools") }} </button>
      </div>
      <p v-if="!server.configured" class="subtle">{{ uiText("Ask your deployment administrator to configure this service.") }}</p>
      <p v-else-if="!server.tools.length" class="subtle">
        {{ server.status === 'READY' ? uiText("The service returned no tools.") : uiText("Enable and refresh to discover tools.") }}
      </p>
      <ul class="form-stack">
        <li v-for="tool in server.tools" :key="tool.name" class="min-w-0 break-words">
          <strong>{{ tool.name }}</strong> ·
          {{ tool.callable ? uiText("Read-only calls allowed") : uiText("Display only; calls unavailable") }}
          <p class="subtle">{{ tool.description }}</p>
          <details>
            <summary>{{ uiText("Parameter schema") }}</summary>
            <pre class="overflow-auto whitespace-pre-wrap break-all text-xs">{{
              JSON.stringify(tool.inputSchema, null, 2)
            }}</pre>
          </details>
          <button
            v-if="tool.callable"
            class="btn"
            :disabled="busy"
            @click="selectTool(server.provider, tool.name)"
          > {{ uiText("Select tool") }} </button>
        </li>
      </ul>
    </article>
    <form v-if="selected" class="panel form-stack" @submit.prevent="call">
      <h2>{{ uiText("Read-only call · {0}", [selected]) }}</h2>
      <label class="field"
        >{{ uiText("JSON parameters") }}<textarea
          v-model="argumentsText"
          rows="6"
          maxlength="20000"
          :disabled="busy"
          spellcheck="false"
        />
      </label>
      <p class="helper-note"> {{ uiText("Only the content entered here is sent. GitHub tools may return private repository content visible to the authorized account.") }} </p>
      <button class="btn primary" :disabled="busy">{{ busy ? uiText("Calling…") : uiText("Call tool") }}</button>
      <p role="status">{{ callStatus }}</p>
      <pre v-if="preview" class="max-h-96 overflow-auto whitespace-pre-wrap break-all text-xs">{{
        preview
      }}</pre>
    </form>
  </section>
</template>
