<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/shared/stores/auth'
import {
  getProfileSuggestions,
  decideProfileSuggestion,
  type ProfileSuggestion,
} from '@/shared/api/careerUpdates'
import type { CareerProfile } from '@/shared/api/career'
const props = defineProps<{ beforeConfirm: () => Promise<void> }>()
const emit = defineEmits<{ confirmed: [profile: CareerProfile]; busy: [value: boolean] }>()
const auth = useAuthStore()
const items = ref<ProfileSuggestion[]>([]),
  selected = ref<Record<string, string[]>>({})
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  online = ref(navigator.onLine)
const labels = { RESUME: '简历技能', GITHUB: 'GitHub 实证', INTERVIEW: '面试报告' }
const statuses = { PENDING: '待确认', CONFIRMED: '已确认', REJECTED: '已忽略', STALE: '来源已变化' }
let generation = 0
async function load() {
  const current = generation
  loading.value = true
  error.value = ''
  try {
    const data = await getProfileSuggestions()
    if (current !== generation) return
    items.value = data
    selected.value = Object.fromEntries(data.map((item) => [item.id, []]))
  } catch (cause) {
    if (current === generation)
      error.value = cause instanceof Error ? cause.message : '画像建议读取失败'
  } finally {
    if (current === generation) loading.value = false
  }
}
async function decide(item: ProfileSuggestion, accept: boolean) {
  if (busy.value) return
  const current = generation
  busy.value = true
  emit('busy', true)
  error.value = ''
  try {
    await props.beforeConfirm()
    if (current !== generation) return
    const profile = await decideProfileSuggestion(item.id, accept, selected.value[item.id] ?? [])
    if (current !== generation) return
    if (accept) emit('confirmed', profile)
    await load()
  } catch (cause) {
    if (current === generation)
      error.value = cause instanceof Error ? cause.message : '确认失败，请重试'
  } finally {
    if (current === generation) {
      busy.value = false
      emit('busy', false)
    }
  }
}
function connection() {
  online.value = navigator.onLine
}
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
watch(
  () => auth.identityKey,
  () => {
    generation++
    items.value = []
    selected.value = {}
    busy.value = false
    emit('busy', false)
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  generation++
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
</script>
<template>
  <section id="profile-updates" class="panel form-stack" aria-label="画像更新建议">
    <h2>材料带来的画像更新建议</h2>
    <p>
      自动整理已有材料，确认后才加入画像。请勾选你认可的技能；仓库语言和面试评价不能证明熟练程度。
    </p>
    <p v-if="!online" role="status">当前离线，恢复连接后可读取和确认建议。</p>
    <button class="btn" :disabled="loading || busy || !online" @click="load">刷新材料建议</button>
    <p v-if="loading" role="status">正在读取材料建议…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !error && !items.length">
      暂无材料建议。上传并保存简历、完成面试报告或刷新 GitHub 实证后再来查看。
    </p>
    <article v-for="item in items" :key="item.id" class="form-stack profile-suggestion">
      <h3>{{ labels[item.sourceType] }} · {{ statuses[item.status] }}</h3>
      <p class="subtle">
        来源：{{ item.sourceId }} · {{ new Date(item.createdAt).toLocaleString() }}
      </p>
      <ul>
        <li v-for="fact in item.facts" :key="fact">{{ fact }}</li>
      </ul>
      <template v-if="item.status === 'PENDING'">
        <label v-for="skill in item.skills" :key="skill"
          ><input
            v-model="selected[item.id]"
            type="checkbox"
            :value="skill"
            :disabled="busy || !online"
          />
          加入画像技能：{{ skill }}</label
        >
        <div class="actions">
          <button class="btn primary" :disabled="busy || !online" @click="decide(item, true)">
            确认材料依据与所选技能</button
          ><button class="btn" :disabled="busy || !online" @click="decide(item, false)">
            忽略这次建议
          </button>
        </div>
      </template>
    </article>
  </section>
</template>
<style scoped>
.profile-suggestion {
  border-top: 1px solid var(--color-border);
  padding-top: 16px;
  overflow-wrap: anywhere;
  min-width: 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
input[type='checkbox'] {
  width: 18px;
  height: 18px;
  min-height: 18px;
}
</style>
