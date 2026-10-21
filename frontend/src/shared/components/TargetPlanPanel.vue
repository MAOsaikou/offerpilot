<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

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
const error = localizedText(),
  loading = ref(false),
  busy = ref(false)
const online = ref(navigator.onLine)
const current = computed(() =>
  plan.value?.target === props.target && plan.value?.city === props.city ? plan.value : null,
)
const levels = computed<Record<string, string>>(() => ({
  POSSESSED: uiText("Materials available"),
  PARTIAL: uiText("Partial materials"),
  MISSING: uiText("Missing materials"),
}))
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
      error.value = () => (cause instanceof Error ? cause.message : uiText("Could not load coordinated suggestions"))
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
      error.value = () => (cause instanceof Error ? cause.message : uiText("Operation failed. Please reload."))
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
  <section v-if="target" class="panel form-stack" :aria-label="uiText('Career goal coordination')">
    <h2>{{ uiText("Next steps for “{0}”", [target]) }}</h2>
    <p v-if="!online" role="status">{{ uiText("You are offline. Reconnect to load and confirm suggestions.") }}</p>
    <p v-if="loading" role="status">{{ uiText("Loading coordinated suggestions…") }}</p>
    <div v-if="error" role="alert">
      {{ error }} <button class="btn" :disabled="loading || !online" @click="load">{{ uiText("Reload") }}</button>
    </div>
    <p v-if="!current && !loading && !error"> {{ uiText("Saving a goal automatically searches jobs, analyzes skill gaps and prepares learning suggestions. Continue with an existing goal below.") }} </p>
    <p v-if="current && ['PENDING', 'RUNNING'].includes(current.status)" role="status"> {{ uiText("Searching jobs and preparing suggestions for your saved goal. You will receive a notification when ready.") }} </p>
    <template v-if="current?.result">
      <p v-if="current.notice" role="status">{{ current.notice }}</p>
      <h3>{{ uiText("Job search results") }}</h3>
      <p v-if="!current.result.jobs?.items.length">{{ uiText("No jobs to display. Adjust keywords to continue searching.") }}</p>
      <p v-if="current.result.jobs?.meta?.partial">{{ uiText("These job search results are incomplete. Retry on the jobs page.") }}</p>
      <ul v-if="current.result.jobs?.items.length">
        <li v-for="job in current.result.jobs.items.slice(0, 5)" :key="job.id">
          <RouterLink :to="'/jobs/' + encodeURIComponent(job.id)"
            >{{ job.title }} · {{ job.company }}</RouterLink
          ><span v-if="job.source === 'LOCAL_SNAPSHOT'">{{ uiText("(Offline historical snapshot)") }}</span>
        </li>
      </ul>
      <h3>{{ uiText("Skill gaps") }}</h3>
      <p v-if="current.result.skillGap">{{ current.result.skillGap.notice }}</p>
      <ul>
        <li v-for="skill in current.result.skillGap?.skills" :key="skill.name">
          {{ skill.name }}：{{ formatDisplay(levels[skill.level] ?? skill.level) }}
        </li>
      </ul>
      <h3>{{ uiText("Suggested learning route") }}</h3>
      <p>{{ current.result.learning?.summary }}</p>
      <ol>
        <li v-for="week in current.result.learning?.roadmap?.weeks" :key="week.index"> {{ uiText("Week {0} · {1}", [week.index, week.theme]) }} <p v-for="item in week.items" :key="item.id">
            {{ uiText("{0} · Suggested time: {1} hours", [item.title, item.hours]) }} </p>
        </li>
      </ol>
      <button
        v-if="current.status === 'SUGGESTED'"
        class="btn primary"
        :disabled="busy || !online"
        @click="decide()"
      > {{ uiText("Confirm adoption of these next steps") }} </button>
      <span v-if="current.status === 'CONFIRMED'" class="tag">{{ uiText("Adoption confirmed") }}</span>
      <button
        v-if="current.status === 'FAILED'"
        class="btn"
        :disabled="busy || !online"
        @click="decide(true)"
      > {{ uiText("Retry coordinated suggestions") }} </button>
      <p class="subtle">{{ uiText("Confirmation records your intent only. Update task completion and applications according to actual progress.") }}</p>
    </template>
    <div class="target-links">
      <RouterLink :to="{ path: '/jobs', query: { q: target, location: city } }" class="btn"
        >{{ uiText("Continue searching target jobs") }}</RouterLink
      >
      <RouterLink to="/career#skill-gap" class="btn">{{ uiText("View skill gaps") }}</RouterLink>
      <RouterLink
        :to="{
          path: '/chat',
          query: { mode: 'COACH', prompt: uiText('Analyze skill gaps and suggest a learning route for my saved target career.') },
        }"
        class="btn"
        >{{ uiText("Discuss learning route with coach") }}</RouterLink
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
