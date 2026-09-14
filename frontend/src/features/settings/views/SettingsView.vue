<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { getStoredTheme, setTheme, type ThemeMode } from '@/shared/utils/theme'
import { downloadText } from '@/shared/utils/download'
import { API_BASE } from '@/shared/api/client'
const workspace = useWorkspace()
const route = useRoute()
const tab = ref(route.params.tab === 'connection' ? '连接与联调' : '个人偏好')
const theme = ref<ThemeMode>(getStoredTheme())
const checking = ref(false)
const result = ref('')
const ok = ref(false)
async function check() {
  checking.value = true
  result.value = ''
  ok.value = false
  try {
    const response = await fetch('/actuator/health', {
      signal: AbortSignal.timeout(6000),
      cache: 'no-store',
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    const data: unknown = await response.json()
    ok.value = !!data && typeof data === 'object' && 'status' in data && data.status === 'UP'
    result.value = ok.value
      ? '后端健康检查通过。可以进入 AI 对话测试 SSE。'
      : '后端已响应，但健康状态不是 UP。请检查后端日志与依赖服务。'
  } catch (error) {
    result.value =
      '无法确认后端连接：' +
      (error instanceof Error ? error.message : '请求失败') +
      '。请确认后端在 8081 端口运行。'
  } finally {
    checking.value = false
  }
}
function exportWorkspace() {
  downloadText(
    'OfferPilot-本地工作区.json',
    JSON.stringify(workspace.state, null, 2),
    'application/json',
  )
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="让工作区，更适合你"
      eyebrow="PREFERENCES & CONNECTION"
      description="管理显示偏好，检查前后端连接。"
    />
    <div class="tabs">
      <button
        v-for="t in ['个人偏好', '连接与联调', '数据与隐私']"
        :key="t"
        :class="{ selected: tab === t }"
        @click="tab = t"
      >
        {{ t }}
      </button>
    </div>
    <section v-if="tab === '个人偏好'" class="panel form-stack" style="max-width: 800px">
      <h2>基本信息</h2>
      <div class="form-grid">
        <label class="field"
          >如何称呼你<input v-model="workspace.state.name" maxlength="40" /></label
        ><label class="field"
          >目标方向<input v-model="workspace.state.target" maxlength="100"
        /></label>
      </div>
      <label class="field"
        >外观主题<select v-model="theme" @change="setTheme(theme)">
          <option value="light">明亮</option>
          <option value="dark">深色</option>
          <option value="system">跟随系统</option>
        </select></label
      >
      <p class="subtle">修改即时生效，偏好保存在当前浏览器。</p>
    </section>
    <section v-else-if="tab === '连接与联调'" class="split-content">
      <div class="panel form-stack">
        <h2>后端连接</h2>
        <p class="subtle">
          API 入口：<code>{{ API_BASE }}</code
          ><br />当前对话：{{ '真实后端（开发时可显式开启 Mock）' }}<br />Nginx
          入口：http://localhost:8080<br />默认后端：http://127.0.0.1:8081
        </p>
        <p class="helper-note">
          当前后端支持 DeepSeek 单轮流式聊天，AI
          编排尚未实现。健康检查通过只表示服务健康，模型连接需发送消息验证。
        </p>
        <button class="btn primary" :disabled="checking" @click="check">
          {{ checking ? '正在检查…' : '检查后端连接' }}
        </button>
        <p v-if="result" :class="ok ? 'success-banner' : 'error-banner'" role="status">
          {{ result }}
        </p>
        <div class="filter-bar">
          <RouterLink to="/chat" class="btn">测试流式对话 →</RouterLink
          ><a href="/swagger-ui.html" target="_blank" rel="noopener" class="btn">接口文档 ↗</a>
        </div>
      </div>
      <aside class="panel form-stack">
        <h2>联调时这样检查</h2>
        <p class="subtle">
          1. 启动数据库与后端。<br />2. 双击项目的「启动前端.cmd」。<br />3.
          检查连接，再发送一条对话。<br />4. 执行面板展示本轮状态与服务端事件。
        </p>
        <p class="helper-note">
          业务页面的本地内容不会自动同步到服务器。账号、业务 API、模型和 RAG
          接入需以后端真实能力为准。
        </p>
      </aside>
    </section>
    <section v-else class="panel form-stack" style="max-width: 800px">
      <h2>你的内容，留在本机</h2>
      <p class="subtle">
        简历、求职进度、练习回答和笔记存储在此浏览器的本地存储中。清除站点数据、更换浏览器或设备后不可自动恢复。AI
        对话发送的文字会提交给配置的后端。
      </p>
      <button class="btn" @click="exportWorkspace">导出本地工作区备份</button>
      <p class="helper-note">
        不要在前端输入模型密钥。后端密钥通过服务器环境变量配置。本版本没有账号、云端同步或长期记忆服务。
      </p>
    </section>
  </section>
</template>
