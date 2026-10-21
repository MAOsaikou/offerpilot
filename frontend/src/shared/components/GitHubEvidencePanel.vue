<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  getGitHubEvidence,
  saveGitHubSettings,
  refreshGitHubEvidence,
  deleteGitHubSettings,
  adoptGitHubProject,
  type GitHubEvidence,
  type GitHubProject,
} from '@/shared/api/github-evidence'
import type { ServerResume } from '@/shared/types/resume'
const props = defineProps<{
  resumeId?: string
  baseVersionId?: string
  adoptionDisabled?: boolean
}>()
const emit = defineEmits<{ adopted: [resume: ServerResume]; adoptionBusy: [value: boolean] }>()
const data = ref<GitHubEvidence>(),
  username = ref(''),
  token = ref(''),
  removeToken = ref(false)
const busy = ref(false),
  error = ref(''),
  message = ref(''),
  selected = ref<GitHubProject>(),
  confirmed = ref(false)
let controller = new AbortController()
const settingsDirty = computed(
  () =>
    username.value.trim() !== (data.value?.username ?? '') ||
    Boolean(token.value) ||
    removeToken.value,
)
watch([username, token, removeToken], () => {
  selected.value = undefined
  confirmed.value = false
})
async function run(action: (signal: AbortSignal) => Promise<void>) {
  if (busy.value) return
  const request = controller
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action(request.signal)
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '操作失败，请重试'
  } finally {
    if (!request.signal.aborted) busy.value = false
  }
}
function load() {
  return run(async (signal) => {
    const result = await getGitHubEvidence(signal)
    if (signal.aborted) return
    data.value = result
    username.value = result.username
  })
}
watch(
  sessionRevision,
  () => {
    controller.abort()
    controller = new AbortController()
    data.value = undefined
    token.value = ''
    username.value = ''
    selected.value = undefined
    confirmed.value = false
    busy.value = false
    error.value = ''
    message.value = ''
    removeToken.value = false
    void load()
  },
  { immediate: true },
)
watch(
  () => props.baseVersionId,
  () => {
    selected.value = undefined
    confirmed.value = false
  },
)
onBeforeUnmount(() => {
  token.value = ''
  controller.abort()
})
function save() {
  return run(async (signal) => {
    const request = saveGitHubSettings(
      {
        username: username.value.trim(),
        token: token.value || undefined,
        removeToken: removeToken.value,
      },
      signal,
    )
    token.value = ''
    const result = await request
    if (signal.aborted) return
    data.value = result
    removeToken.value = false
    selected.value = undefined
    message.value = '设置已保存，请点击抓取公开仓库。'
  })
}
function refresh(force: boolean) {
  return run(async (signal) => {
    const result = await refreshGitHubEvidence(force, signal)
    if (signal.aborted) return
    data.value = result
    selected.value = undefined
    confirmed.value = false
  })
}
function disconnect() {
  return run(async (signal) => {
    await deleteGitHubSettings(signal)
    if (signal.aborted) return
    data.value = undefined
    username.value = ''
    token.value = ''
    selected.value = undefined
    removeToken.value = false
    message.value = '已移除 GitHub 设置、凭据与缓存。'
  })
}
function preview(project: GitHubProject) {
  selected.value = project
  confirmed.value = false
}
function adopt() {
  if (
    !props.resumeId ||
    !props.baseVersionId ||
    props.adoptionDisabled ||
    settingsDirty.value ||
    !confirmed.value ||
    !selected.value ||
    !data.value?.snapshot
  )
    return
  const body = {
    resumeId: props.resumeId,
    baseVersionId: props.baseVersionId,
    snapshotId: data.value.snapshot.id,
    repository: selected.value.name,
    confirmed: true,
  }
  return run(async (signal) => {
    emit('adoptionBusy', true)
    try {
      const result = await adoptGitHubProject(body, signal)
      if (signal.aborted) return
      emit('adopted', result)
      selected.value = undefined
      confirmed.value = false
      message.value = '项目建议已存为简历新版本，请在编辑器中补充个人贡献。'
    } finally {
      emit('adoptionBusy', false)
    }
  })
}
</script>
<template>
  <section class="panel form-stack min-w-0" aria-label="GitHub 技能实证">
    <h2>GitHub 技能实证</h2>
    <p class="helper-note">
      只读取公开仓库。README 亮点会发送给 FLASH 模型；仓库归属不证明你的个人贡献，请采纳前核对。
    </p>
    <p v-if="busy" role="status">正在处理 GitHub 请求…</p>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }} <button class="btn" :disabled="busy" @click="load">重新读取</button>
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <form class="form-stack" @submit.prevent="save">
      <fieldset :disabled="busy" class="form-stack min-w-0">
        <label class="field"
          >公开 GitHub 用户名<input v-model="username" maxlength="39" required autocomplete="off"
        /></label>
        <label class="field"
          >可选 Token（{{ data?.tokenConfigured ? '已配置，不回显；留空保留' : '未配置；可留空' }}）
          <input
            v-model="token"
            type="password"
            maxlength="255"
            autocomplete="new-password"
            :disabled="removeToken"
          />
        </label>
        <label
          ><input v-model="removeToken" type="checkbox" @change="token = ''" />移除已保存
          Token</label
        >
        <p class="helper-note">
          Token 加密存储，不保存在浏览器。建议仅授予公开仓库读取权限；更换用户名会移除旧 Token。
        </p>
        <button class="btn" type="submit" :disabled="!username.trim()">保存 GitHub 设置</button>
      </fieldset>
    </form>
    <div class="flex flex-wrap gap-2">
      <button
        class="btn"
        :disabled="busy || settingsDirty || !data?.username"
        @click="refresh(false)"
      >
        抓取公开仓库（优先缓存）
      </button>
      <button
        class="btn"
        :disabled="busy || settingsDirty || !data?.username"
        @click="refresh(true)"
      >
        强制刷新
      </button>
      <button class="btn" :disabled="busy || !data?.username" @click="disconnect">
        移除 GitHub 连接
      </button>
    </div>
    <p v-if="settingsDirty" class="helper-note">请先保存 GitHub 设置，再抓取或采纳项目建议。</p>
    <p v-if="data" class="helper-note" role="status">{{ data.notice }}</p>
    <template v-if="data?.snapshot">
      <p>
        抓取时间：{{ data.fetchedAt }} · 有效至：{{ data.expiresAt }}
        <strong v-if="data.stale">历史缓存，需刷新</strong>
      </p>
      <p>
        {{ data.snapshot.repositories.length }} 个公开仓库 · Star {{ data.snapshot.stars }} ·
        近90天本人提交 {{ data.snapshot.commits90d }}（达到分页上限时为下界）
      </p>
      <p v-if="data.snapshot.repositoriesTruncated">
        本次仅统计已完整抓取的 {{ data.snapshot.repositories.length }} 个仓库，未覆盖全部仓库。
      </p>
      <p class="break-words">
        语言分布（字节）：{{
          Object.entries(data.snapshot.languages)
            .map(([name, bytes]) => `${name} ${bytes}`)
            .join(' · ') || '暂无语言数据'
        }}
      </p>
      <p v-if="!data.snapshot.repositories.length">
        {{
          data.snapshot.repositoriesTruncated
            ? '本次未完整抓取到仓库，请稍后重试或配置 Token。'
            : '该用户暂无公开仓库。'
        }}
      </p>
      <article
        v-for="project in data.snapshot.repositories"
        :key="project.name"
        class="form-stack min-w-0 rounded-lg border border-border p-3"
      >
        <h3 class="break-words">
          <a :href="project.url" target="_blank" rel="noopener noreferrer">{{ project.name }}</a>
          <span v-if="project.fork">（fork）</span>
        </h3>
        <p>
          Star {{ project.stars }} · 近90天本人提交 {{ project.commitsTruncated ? '≥' : ''
          }}{{ project.commits90d }}
        </p>
        <p class="break-words">
          {{
            Object.entries(project.languages)
              .map(([name, bytes]) => `${name} ${bytes}字节`)
              .join(' · ') || '暂无语言数据'
          }}
        </p>
        <ul v-if="project.highlights.length">
          <li v-for="quote in project.highlights" :key="quote" class="break-words">{{ quote }}</li>
        </ul>
        <p v-else>暂无可用 README 亮点；不补造项目描述。</p>
        <button
          v-if="resumeId && project.highlights.length"
          class="btn"
          :disabled="busy || settingsDirty || data.stale || adoptionDisabled"
          @click="preview(project)"
        >
          作为简历项目建议
        </button>
      </article>
      <p v-if="resumeId && adoptionDisabled" class="helper-note">
        请先保存或撤销简历草稿，再采纳项目建议。
      </p>
      <section v-if="selected" class="form-stack" aria-label="确认 GitHub 项目建议">
        <h3>核对后新增项目：{{ selected.name }}</h3>
        <p>项目公开说明（个人贡献需自行补充）：{{ selected.url }}</p>
        <ul>
          <li v-for="quote in selected.highlights" :key="quote" class="break-words">{{ quote }}</li>
        </ul>
        <label
          ><input
            v-model="confirmed"
            type="checkbox"
          />我已核对原文与项目关联；这些是项目说明，我会补充自己的贡献，不将仓库指标当作个人成果。</label
        >
        <div class="flex flex-wrap gap-2">
          <button
            class="btn primary"
            :disabled="busy || settingsDirty || !confirmed || adoptionDisabled"
            @click="adopt"
          >
            确认添加并保存新版本
          </button>
          <button class="btn" :disabled="busy" @click="selected = undefined">取消</button>
        </div>
      </section>
    </template>
  </section>
</template>
