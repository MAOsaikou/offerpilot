<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { getStoredTheme, setTheme, type ThemeMode } from '@/shared/utils/theme'
import { downloadText } from '@/shared/utils/download'
import { API_BASE } from '@/shared/api/client'
import ResumeModelSettings from '../components/ResumeModelSettings.vue'
const workspace = useWorkspace()
const route = useRoute()
const router = useRouter()
const tab = computed(() =>
  route.params.tab === 'connection'
    ? '连接与联调'
    : route.params.tab === 'models'
      ? '模型选择'
      : route.params.tab === 'privacy'
        ? '数据与隐私'
        : '个人偏好',
)
function selectTab(value: string) {
  const suffix =
    value === '模型选择'
      ? '/models'
      : value === '连接与联调'
        ? '/connection'
        : value === '数据与隐私'
          ? '/privacy'
          : ''
  void router.replace('/settings' + suffix)
}
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
      description="管理显示偏好，选择简历解析模型，检查前后端连接。"
    />
    <div class="tabs">
      <button
        v-for="t in ['个人偏好', '模型选择', '连接与联调', '数据与隐私']"
        :key="t"
        :class="{ selected: tab === t }"
        @click="selectTab(t)"
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
    <ResumeModelSettings v-else-if="tab === '模型选择'" />
    <section v-else-if="tab === '连接与联调'" class="split-content">
      <div class="panel form-stack">
        <h2>后端连接</h2>
        <p class="subtle">
          API 入口：<code>{{ API_BASE }}</code
          ><br />当前对话：{{ '真实后端（开发时可显式开启 Mock）' }}<br />Nginx
          入口：http://localhost:8080<br />默认后端：http://127.0.0.1:8081
        </p>
        <p class="helper-note">
          当前后端支持 DeepSeek 多轮流式聊天、会话历史保存与基础 Agent 编排，部分专家能力和 RAG
          仍待接入。健康检查通过只表示服务健康，模型连接需发送消息验证。
        </p>
        <button class="btn primary" :disabled="checking" @click="check">
          {{ checking ? '正在检查…' : '检查后端连接' }}
        </button>
        <p v-if="result" :class="ok ? 'success-banner' : 'error-banner'" role="status">
          {{ result }}
        </p>
        <div class="filter-bar">
          <RouterLink to="/chat" class="btn">测试流式对话</RouterLink
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
          连接检查和对话均请求真实后端，注册登录、看板、简历和岗位匹配已接入服务端。仅本地草稿、练习、笔记及显示偏好等内容保留在浏览器，不会自动同步。
        </p>
      </aside>
    </section>
    <section v-else class="panel form-stack" style="max-width: 800px">
      <h2>你的内容保存在哪里</h2>
      <p class="subtle">
        本地简历草稿、旧看板归档、练习回答和知识笔记保存在此浏览器。岗位收藏、求职阶段和跟进备注、已上传的简历原件、解析结果、版本和
        AI
        对话历史保存在后端。岗位匹配报告及其资料快照也保存在后端；上传解析和主动发起匹配时，相关简历内容会发送给配置的模型。
        访客通过浏览器保存的令牌访问服务端数据，清除站点数据、更换浏览器或令牌过期后无法自动恢复访客访问；访客身份暂不支持跨设备找回。注册用户通过
        JWT 登录，可在其他浏览器用邮箱和密码重新登录，访问同一账号的服务端数据。
      </p>
      <button class="btn" @click="exportWorkspace">导出本地工作区备份</button>
      <p class="helper-note">
        已提供正式注册与登录；访客身份暂不支持跨设备找回。此按钮仅导出本地工作区，不包含服务端看板、简历、对话和匹配报告，也不包含登录凭据。模型密钥仅在服务器配置，不要输入前端。
      </p>
    </section>
  </section>
</template>
