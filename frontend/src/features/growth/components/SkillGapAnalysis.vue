<script setup lang="ts">
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
  error = ref('')
let controller = new AbortController()
const labels = { POSSESSED: '已具备', PARTIAL: '部分', MISSING: '缺失' }
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
    error.value = '部分目标未能加载，请重试；仍可分析已保存目标职业。'
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
    if (!signal.aborted) error.value = e instanceof Error ? e.message : '分析失败，请重试'
  } finally {
    if (!signal.aborted) busy.value = false
  }
}
</script>
<template>
  <section class="panel form-stack min-w-0" aria-label="技能差距分析">
    <h2>目标技能差距</h2>
    <p>对照最近保存的可用简历、画像与 GitHub 实证。缺失表示材料未体现。</p>
    <p v-if="loading" role="status">正在读取目标…</p>
    <label class="field"
      >目标职业或岗位
      <select v-model="target" :disabled="busy || loading">
        <option value="">使用画像中已保存的目标职业</option>
        <optgroup label="职业库">
          <option v-for="item in occupations" :key="item.occupationId" :value="item.occupationId">
            {{ item.name }}
          </option>
        </optgroup>
        <optgroup label="收藏或导入岗位">
          <option v-for="job in jobs" :key="job.id" :value="'job:' + job.id">
            {{ job.title }}
          </option>
        </optgroup>
      </select>
    </label>
    <button class="btn primary" :disabled="busy || loading" @click="analyze">
      {{ busy ? '正在分析…' : '分析技能差距' }}
    </button>
    <p v-if="error" role="alert">
      {{ error }}
      <button class="btn" :disabled="busy || loading" @click="load">重试目标列表</button>
    </p>
    <template v-if="report">
      <h3 class="break-words">{{ report.targetTitle }}</h3>
      <p class="helper-note">{{ report.notice }}</p>
      <p v-if="!report.skills.length" role="status">暂无可识别的目标技能。</p>
      <article
        v-for="skill in report.skills"
        :key="skill.name"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <h4 class="break-words">{{ skill.name }} · {{ labels[skill.level] }}</h4>
        <ul aria-label="每项依据来源">
          <li v-for="(source, index) in skill.sources" :key="index" class="break-words">
            {{ source.label }}：{{ source.excerpt
            }}<span v-if="source.fetchedAt">（抓取于 {{ source.fetchedAt }}）</span>
          </li>
        </ul>
      </article>
    </template>
  </section>
</template>
