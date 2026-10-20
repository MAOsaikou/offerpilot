<script setup lang="ts">
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
const error = ref('')
const notice = ref('')
const selected = ref('')
const argumentsText = ref('{}')
const preview = ref('')
const callStatus = ref('')
async function load() {
  busy.value = true
  error.value = ''
  try {
    const value = await listMcpServers()
    session.throwIfAborted()
    servers.value = value
  } catch {
    if (!session.aborted) error.value = '无法加载 MCP 连接，请检查网络后重试。'
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
    if (!session.aborted) error.value = '操作失败，设置未确认保存，请重试。'
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
    error.value = '参数须为 JSON 对象。'
    return
  }
  const [provider, tool] = selected.value.split(':')
  if (!provider || !tool) return
  busy.value = true
  callStatus.value = '调用中'
  try {
    const value = await callMcpTool(provider, tool, args)
    session.throwIfAborted()
    preview.value = value.preview
    callStatus.value = '调用成功'
  } catch {
    if (!session.aborted) {
      error.value = '工具调用失败，请检查连接和参数后重试。'
      callStatus.value = '调用失败'
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
      title="MCP 工具连接"
      description="添加部署已登记的服务，管理当前用户的连接与只读工具。"
    />
    <p class="helper-note">
      服务地址与密钥由服务器环境配置。GitHub 未配置 token 时显示未配置；不会在浏览器收集或展示密钥。
    </p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button class="btn" :disabled="busy" @click="load">{{ busy ? '处理中…' : '重新加载' }}</button>
    <p v-if="!busy && !servers.length && !error">暂无连接。</p>
    <article v-for="server in servers" :key="server.provider" class="panel form-stack">
      <h2>{{ server.name }} · {{ mcpStatus(server.status) }}</h2>
      <p v-if="server.refreshedAt" class="subtle">
        上次刷新：{{ new Date(server.refreshedAt).toLocaleString() }}
      </p>
      <div class="filter-bar">
        <button v-if="!server.added" class="btn" :disabled="busy" @click="change(server, 'add')">
          添加连接
        </button>
        <button v-else class="btn" :disabled="busy" @click="change(server, 'toggle')">
          {{ server.enabled ? '停用' : '启用' }}
        </button>
        <button
          class="btn"
          :disabled="busy || !server.added || !server.enabled || !server.configured"
          @click="change(server, 'refresh')"
        >
          刷新工具
        </button>
      </div>
      <p v-if="!server.configured" class="subtle">请联系部署管理员配置此服务。</p>
      <p v-else-if="!server.tools.length" class="subtle">
        {{ server.status === 'READY' ? '服务未返回工具。' : '启用后刷新以发现工具。' }}
      </p>
      <ul class="form-stack">
        <li v-for="tool in server.tools" :key="tool.name" class="min-w-0 break-words">
          <strong>{{ tool.name }}</strong> ·
          {{ tool.callable ? '允许只读调用' : '仅展示，未开放调用' }}
          <p class="subtle">{{ tool.description }}</p>
          <details>
            <summary>参数格式</summary>
            <pre class="overflow-auto whitespace-pre-wrap break-all text-xs">{{
              JSON.stringify(tool.inputSchema, null, 2)
            }}</pre>
          </details>
          <button
            v-if="tool.callable"
            class="btn"
            :disabled="busy"
            @click="selectTool(server.provider, tool.name)"
          >
            选择工具
          </button>
        </li>
      </ul>
    </article>
    <form v-if="selected" class="panel form-stack" @submit.prevent="call">
      <h2>只读调用 · {{ selected }}</h2>
      <label class="field"
        >JSON 参数<textarea
          v-model="argumentsText"
          rows="6"
          maxlength="20000"
          :disabled="busy"
          spellcheck="false"
        />
      </label>
      <p class="helper-note">
        只发送你在此输入的内容；GitHub 工具可能返回已授权账号可见的私有仓库内容。
      </p>
      <button class="btn primary" :disabled="busy">{{ busy ? '调用中…' : '调用工具' }}</button>
      <p role="status">{{ callStatus }}</p>
      <pre v-if="preview" class="max-h-96 overflow-auto whitespace-pre-wrap break-all text-xs">{{
        preview
      }}</pre>
    </form>
  </section>
</template>
