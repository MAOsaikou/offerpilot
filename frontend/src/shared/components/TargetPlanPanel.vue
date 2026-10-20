<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
import {
  getTargetPlan,
  confirmTargetPlan,
  retryTargetPlan,
  type TargetPlan,
} from '@/shared/api/careerUpdates'
const props = defineProps<{ enabled: boolean; target: string; city: string }>()
const auth = useAuthStore()
const plan = ref<TargetPlan | null>(null)
const error = ref(''),
  loading = ref(false),
  busy = ref(false)
const online = ref(navigator.onLine)
const current = computed(() =>
  plan.value?.target === props.target && plan.value?.city === props.city ? plan.value : null,
)
const levels: Record<string, string> = {
  POSSESSED: '已具备材料',
  PARTIAL: '部分材料',
  MISSING: '缺少材料',
}
let generation = 0
async function load() {
  if (!props.enabled || loading.value) return
  const revision = generation
  loading.value = true
  error.value = ''
  try {
    const value = await getTargetPlan()
    if (revision === generation) plan.value = value
  } catch (cause) {
    if (revision === generation)
      error.value = cause instanceof Error ? cause.message : '联动读取失败'
  } finally {
    if (revision === generation) loading.value = false
  }
}
async function decide(retry = false) {
  if (!current.value || busy.value) return
  const revision = generation
  busy.value = true
  error.value = ''
  try {
    const value = await (retry
      ? retryTargetPlan(current.value.revision)
      : confirmTargetPlan(current.value.revision))
    if (revision === generation) plan.value = value
  } catch (cause) {
    if (revision === generation)
      error.value = cause instanceof Error ? cause.message : '操作失败，请重新读取'
  } finally {
    if (revision === generation) busy.value = false
  }
}
function connection() {
  online.value = navigator.onLine
  if (online.value) void load()
}
watch(
  () => [auth.identityKey, props.enabled, props.target, props.city],
  () => {
    generation++
    plan.value = null
    loading.value = false
    busy.value = false
    error.value = ''
    void load()
  },
  { immediate: true, flush: 'sync' },
)
const timer = setInterval(() => {
  if (online.value && (!current.value || ['PENDING', 'RUNNING'].includes(current.value.status)))
    void load()
}, 10000)
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
onBeforeUnmount(() => {
  generation++
  clearInterval(timer)
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
</script>
<template>
  <section v-if="target" class="panel form-stack" aria-label="目标职业联动">
    <h2>面向「{{ target }}」的下一步</h2>
    <p v-if="!online" role="status">当前离线，恢复后可读取和确认建议。</p>
    <p v-if="loading" role="status">正在读取联动建议…</p>
    <div v-if="error" role="alert">
      {{ error }} <button class="btn" :disabled="loading || !online" @click="load">重新读取</button>
    </div>
    <p v-if="!current && !loading && !error">
      目标保存后会自动搜索岗位、分析技能差距并整理学习建议。已有目标可通过下方入口继续。
    </p>
    <p v-if="current && ['PENDING', 'RUNNING'].includes(current.status)" role="status">
      正在按已保存目标搜索岗位和整理建议，完成后会发送站内通知。
    </p>
    <template v-if="current?.result">
      <p v-if="current.notice" role="status">{{ current.notice }}</p>
      <h3>岗位搜索结果</h3>
      <p v-if="!current.result.jobs?.items.length">暂无可展示岗位；可调整关键词继续搜索。</p>
      <p v-if="current.result.jobs?.meta?.partial">本次岗位搜索结果不完整，请到岗位页重试。</p>
      <ul v-if="current.result.jobs?.items.length">
        <li v-for="job in current.result.jobs.items.slice(0, 5)" :key="job.id">
          <RouterLink :to="'/jobs/' + encodeURIComponent(job.id)"
            >{{ job.title }} · {{ job.company }}</RouterLink
          ><span v-if="job.source === 'LOCAL_SNAPSHOT'">（离线历史快照）</span>
        </li>
      </ul>
      <h3>技能差距</h3>
      <p v-if="current.result.skillGap">{{ current.result.skillGap.notice }}</p>
      <ul>
        <li v-for="skill in current.result.skillGap?.skills" :key="skill.name">
          {{ skill.name }}：{{ levels[skill.level] ?? skill.level }}
        </li>
      </ul>
      <h3>学习路线建议</h3>
      <p>{{ current.result.learning?.summary }}</p>
      <ol>
        <li v-for="week in current.result.learning?.roadmap?.weeks" :key="week.index">
          第 {{ week.index }} 周 · {{ week.theme }}
          <p v-for="item in week.items" :key="item.id">
            {{ item.title }} · 建议 {{ item.hours }} 小时
          </p>
        </li>
      </ol>
      <button
        v-if="current.status === 'SUGGESTED'"
        class="btn primary"
        :disabled="busy || !online"
        @click="decide()"
      >
        确认采用这份后续建议
      </button>
      <span v-if="current.status === 'CONFIRMED'" class="tag">已确认采用</span>
      <button
        v-if="current.status === 'FAILED'"
        class="btn"
        :disabled="busy || !online"
        @click="decide(true)"
      >
        重试生成联动
      </button>
      <p class="subtle">确认只保存采用意愿；任务完成状态和投递记录需按实际进展填写。</p>
    </template>
    <div class="target-links">
      <RouterLink :to="{ path: '/jobs', query: { q: target, location: city } }" class="btn"
        >继续搜索目标岗位</RouterLink
      >
      <RouterLink to="/career#skill-gap" class="btn">查看技能差距</RouterLink>
      <RouterLink
        :to="{
          path: '/chat',
          query: { mode: 'COACH', prompt: '请按我已保存的目标职业分析技能差距并建议学习路线。' },
        }"
        class="btn"
        >与教练讨论学习路线</RouterLink
      >
    </div>
  </section>
</template>
<style scoped>
section {
  min-width: 0;
  overflow-wrap: anywhere;
}
.target-links {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
</style>
