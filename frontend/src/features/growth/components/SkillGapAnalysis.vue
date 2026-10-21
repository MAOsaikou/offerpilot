<script setup lang="ts">
import { computed } from 'vue'

import {
  formatDateTime,
  formatDisplay,
  localizedText,
  translate as uiText,
} from '@/shared/utils/language'

import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getCareerCatalog } from '@/shared/api/career'
import { getSkillGap, type SkillGapReport } from '@/shared/api/skill-gap'
import { listApplications } from '@/shared/api/applications'
import { listImportedJobs } from '@/shared/api/jobs'
import { sessionRevision } from '@/shared/utils/authSession'
const route = useRoute()
const target = ref(''),
  occupations = ref<Array<{ occupationId: string; name: string }>>([])
const jobs = ref<Array<{ id: string; title: string }>>([])
const report = ref<SkillGapReport>(),
  busy = ref(false),
  loading = ref(false),
  error = localizedText()
let controller = new AbortController()
const labels = computed(() => ({
  POSSESSED: uiText('Demonstrated'),
  PARTIAL: uiText('Partial'),
  MISSING: uiText('Missing'),
}))
async function load() {
  const signal = controller.signal
  loading.value = true
  error.value = ''
  const results = await Promise.allSettled([
    getCareerCatalog(signal),
    listApplications(signal),
    listImportedJobs(signal),
  ])
  if (signal.aborted) return
  const [catalog, applications, imported] = results
  if (catalog.status === 'fulfilled') occupations.value = catalog.value
  const saved = new Map<string, { id: string; title: string }>()
  if (applications.status === 'fulfilled')
    for (const item of applications.value)
      saved.set(item.jobId, { id: item.jobId, title: item.job.title })
  if (imported.status === 'fulfilled')
    for (const item of imported.value) saved.set(item.id, { id: item.id, title: item.title })
  jobs.value = [...saved.values()]
  if (typeof route.query.jobId === 'string') target.value = 'job:' + route.query.jobId
  if (results.some((r) => r.status === 'rejected'))
    error.value = () =>
      uiText(
        'Some targets could not be loaded. Please retry. You can still analyze your saved target career.',
      )
  loading.value = false
}
watch(
  sessionRevision,
  () => {
    controller.abort()
    controller = new AbortController()
    target.value = ''
    report.value = undefined
    occupations.value = []
    jobs.value = []
    busy.value = false
    void load()
  },
  { immediate: true },
)
watch(target, () => {
  report.value = undefined
})
onBeforeUnmount(() => controller.abort())
async function analyze() {
  if (busy.value) return
  const signal = controller.signal,
    selected = target.value
  busy.value = true
  error.value = ''
  report.value = undefined
  try {
    const result = await getSkillGap(
      selected.startsWith('job:')
        ? { jobId: selected.slice(4) }
        : selected
          ? { occupationId: selected }
          : {},
      signal,
    )
    if (!signal.aborted && target.value === selected) report.value = result
  } catch (e) {
    if (!signal.aborted)
      error.value = () =>
        e instanceof Error ? e.message : uiText('Analysis failed. Please retry.')
  } finally {
    if (!signal.aborted) busy.value = false
  }
}
</script>
<template>
  <section class="panel form-stack min-w-0" :aria-label="uiText('Skill gap analysis')">
    <h2>{{ uiText('Target skill gaps') }}</h2>
    <p>
      {{
        uiText(
          'Compare your latest saved usable resume, profile and GitHub evidence. Missing means the materials do not demonstrate the skill.',
        )
      }}
    </p>
    <p v-if="loading" role="status">{{ uiText('Loading targets…') }}</p>
    <label class="field"
      >{{ uiText('Target career or job') }}
      <select v-model="target" :disabled="busy || loading">
        <option value="">{{ uiText('Use the target career saved in my profile') }}</option>
        <optgroup :label="uiText('Career catalog')">
          <option v-for="item in occupations" :key="item.occupationId" :value="item.occupationId">
            {{ item.name }}
          </option>
        </optgroup>
        <optgroup :label="uiText('Saved or imported jobs')">
          <option v-for="job in jobs" :key="job.id" :value="'job:' + job.id">
            {{ job.title }}
          </option>
        </optgroup>
      </select>
    </label>
    <button class="btn primary" :disabled="busy || loading" @click="analyze">
      {{ busy ? uiText('Analyzing…') : uiText('Analyze skill gaps') }}
    </button>
    <p v-if="error" role="alert">
      {{ error }}
      <button class="btn" :disabled="busy || loading" @click="load">
        {{ uiText('Retry target list') }}
      </button>
    </p>
    <template v-if="report">
      <h3 class="break-words">{{ report.targetTitle }}</h3>
      <p class="helper-note">{{ report.notice }}</p>
      <p v-if="!report.skills.length" role="status">
        {{ uiText('No identifiable target skills yet.') }}
      </p>
      <article
        v-for="skill in report.skills"
        :key="skill.name"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <h4 class="break-words">{{ skill.name }} · {{ formatDisplay(labels[skill.level]) }}</h4>
        <ul :aria-label="uiText('Evidence for each item')">
          <li v-for="(source, index) in skill.sources" :key="index" class="break-words">
            {{ source.label }}：{{ source.excerpt
            }}<span v-if="source.fetchedAt">{{
              uiText('(Fetched {0})', [formatDateTime(source.fetchedAt)])
            }}</span>
          </li>
        </ul>
      </article>
    </template>
  </section>
</template>
