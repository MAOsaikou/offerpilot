<script setup lang="ts">
import { generationResult } from '@/shared/api/generationTasks'
import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { sessionRevision, identitySignal } from '@/shared/utils/authSession'
import {
  getCareerCatalog,
  getCareerRevision,
  createCareerRecommendations,
  rerankCareers,
  compareCareers,
  type CareerRevision,
  type CareerConstraints,
  type CareerComparison,
} from '@/shared/api/career'
import CareerRadar from './CareerRadar.vue'
const route = useRoute()
const router = useRouter()
const revision = ref<CareerRevision | null>(null)
const catalog = ref<Array<{ occupationId: string; name: string }>>([])
const constraints = ref<CareerConstraints>({
  city: '',
  minSalaryK: null,
  avoidOvertime: false,
  research: false,
})
const loading = ref(true)
const busy = ref(false)
const online = ref(navigator.onLine)
const updateOnline = () => {
  online.value = navigator.onLine
}
onMounted(() => {
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
})
const error = ref('')
const comparisonError = ref('')
const comparison = ref<CareerComparison | null>(null)
const comparing = ref(false)
const choice = ref('')
const selected = computed(() =>
  typeof route.query.compare === 'string' && route.query.compare
    ? route.query.compare.split(',')
    : [],
)
let controller = new AbortController()
let compareController: AbortController | undefined
let generation = 0
let compareGeneration = 0
const message = (cause: unknown) => (cause instanceof Error ? cause.message : '请求失败，请重试')
async function load() {
  const current = ++generation
  controller.abort()
  controller = new AbortController()
  revision.value = null
  catalog.value = []
  constraints.value = { city: '', minSalaryK: null, avoidOvertime: false, research: false }
  error.value = ''
  loading.value = true
  busy.value = false
  try {
    const [saved, entries] = await Promise.all([
      getCareerRevision(
        typeof route.query.revision === 'string' ? route.query.revision : undefined,
        controller.signal,
      ),
      getCareerCatalog(controller.signal),
    ])
    if (current !== generation) return
    revision.value = saved
    catalog.value = entries
    if (saved) constraints.value = { ...saved.constraints }
  } catch (cause) {
    if (current === generation) error.value = message(cause)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function save(rerank: boolean) {
  if (busy.value || loading.value || !navigator.onLine) return
  const current = generation
  busy.value = true
  error.value = ''
  const signal = controller.signal
  try {
    const saved =
      rerank && revision.value
        ? await rerankCareers(revision.value.id, { ...constraints.value }, signal)
        : await createCareerRecommendations()
    if (current !== generation) return
    revision.value = saved
    constraints.value = { ...saved.constraints }
    await router.replace({ query: { ...route.query, revision: saved.id } })
  } catch (cause) {
    if (current === generation) error.value = message(cause)
  } finally {
    if (current === generation) busy.value = false
  }
}
function addChoice() {
  const id = choice.value
  choice.value = ''
  void select(id)
}
async function select(id: string) {
  const ids = selected.value.includes(id)
    ? selected.value.filter((value) => value !== id)
    : selected.value.length < 3
      ? [...selected.value, id]
      : selected.value
  await router.replace({
    query: { ...route.query, compare: ids.length ? ids.join(',') : undefined },
  })
}
async function loadComparison() {
  const current = ++compareGeneration
  compareController?.abort()
  compareController = new AbortController()
  comparison.value = null
  comparisonError.value = ''
  comparing.value = false
  if (!selected.value.length) return
  if (
    selected.value.length < 2 ||
    selected.value.length > 3 ||
    new Set(selected.value).size !== selected.value.length
  ) {
    comparisonError.value = '请选择 2–3 个不同职业'
    return
  }
  comparing.value = true
  try {
    const result = await compareCareers(selected.value, compareController.signal)
    if (current === compareGeneration) comparison.value = result
  } catch (cause) {
    if (current === compareGeneration) comparisonError.value = message(cause)
  } finally {
    if (current === compareGeneration) comparing.value = false
  }
}
watch(
  () => {
    void sessionRevision.value
    return identitySignal()
  },
  () => {
    void load()
    void loadComparison()
  },
  { immediate: true, flush: 'sync' },
)
watch(() => route.query.compare, loadComparison)
watch(
  () => route.query.revision,
  (id) => {
    if (id !== revision.value?.id) void load()
  },
)
onBeforeUnmount(() => {
  generation++
  compareGeneration++
  controller.abort()
  compareController?.abort()
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
})
const chatLink = computed(() => ({
  path: '/chat',
  query: {
    mode: 'CAREER_DISCOVERY',
    careerAction: 'rerankCareers',
    revisionId: revision.value?.id,
    city: constraints.value.city,
    minSalaryK: constraints.value.minSalaryK?.toString(),
    avoidOvertime: String(constraints.value.avoidOvertime),
    research: String(constraints.value.research),
    prompt: '请按我选择的约束重排推荐，并保存为新修订。',
  },
}))
async function restoreTask(id: string) {
  if (busy.value || route.query.revision) return
  const current = generation
  try {
    const saved = await generationResult<CareerRevision>(id)
    if (current === generation) {
      revision.value = saved
      constraints.value = { ...saved.constraints }
      await router.replace({ query: { ...route.query, revision: saved.id } })
    }
  } catch (cause) {
    if (current === generation) error.value = message(cause)
  }
}
</script>
<template>
  <section class="panel form-stack" aria-labelledby="recommend-title">
    <BackgroundTaskStatus
      kind="CAREER_RECOMMENDATION"
      :entity-id="'current'"
      @completed="restoreTask"
    />
    <h2 id="recommend-title">职业推荐与对比</h2>
    <p class="subtle">依据已有职业画像与目录。补充约束后保存新修订，可从链接恢复。</p>
    <p v-if="!online" role="status">当前离线，恢复连接后可重试；未提交的约束保留在本页。</p>
    <p v-if="loading" role="status">正在读取推荐…</p>
    <div v-if="error" role="alert">
      {{ error }} <button class="btn" @click="load">重新读取</button>
    </div>
    <p v-if="!loading && !revision">
      还没有推荐修订，请先完善<RouterLink to="/discover">职业画像</RouterLink>再生成。
    </p>
    <button class="btn" :disabled="loading || busy || !online" @click="save(false)">
      {{ revision ? '按最新画像重新推荐' : '生成职业推荐' }}
    </button>
    <template v-if="revision">
      <form class="form-stack" @submit.prevent="save(true)">
        <div class="form-grid">
          <label class="field"
            >意向城市<input v-model="constraints.city" maxlength="100" :disabled="busy"
          /></label>
          <label class="field"
            >期望月薪下限（k 元）<input
              :value="constraints.minSalaryK"
              type="number"
              min="1"
              max="1000"
              step="1"
              :disabled="busy"
              @input="
                constraints.minSalaryK = ($event.target as HTMLInputElement).value
                  ? Number(($event.target as HTMLInputElement).value)
                  : null
              "
          /></label>
        </div>
        <label
          ><input v-model="constraints.avoidOvertime" type="checkbox" :disabled="busy" />
          不想加班</label
        >
        <label
          ><input v-model="constraints.research" type="checkbox" :disabled="busy" />
          偏研究（按研究型兴趣强度加权）</label
        >
        <p class="helper-note">
          目录没有城市覆盖和工时数据，这两项会记录为待核实。薪资仅按有来源的目录区间比较；当前内置目录暂无可信薪资数据，缺失时不调分。
        </p>
        <button class="btn primary" :disabled="busy || !online">
          {{ busy ? '正在保存…' : '保存约束并重排' }}
        </button>
        <RouterLink v-if="!busy" :to="chatLink" class="btn">在对话中确认重排</RouterLink>
      </form>
      <p role="status">
        服务端重排计算 {{ revision.elapsedMs }} ms<span v-if="revision.explanationFallback">
          · 已使用预算内简短规则解释</span
        >
      </p>
      <RouterLink
        v-if="revision.parentId"
        :to="{ query: { ...route.query, revision: revision.parentId } }"
        >查看上一修订</RouterLink
      >
      <ol class="form-stack">
        <li
          v-for="item in revision.items"
          :key="item.occupation.occupationId"
          class="recommend-row"
        >
          <strong>{{ item.rank }}. {{ item.occupation.name }}</strong>
          <span v-if="revision.parentId" class="tag" data-rank-delta>{{
            item.rankDelta > 0
              ? '↑ 上升 ' + item.rankDelta
              : item.rankDelta < 0
                ? '↓ 下降 ' + Math.abs(item.rankDelta)
                : '名次未变'
          }}</span>
          <p>{{ item.changeReason }}</p>
          <label
            ><input
              type="checkbox"
              :checked="selected.includes(item.occupation.occupationId)"
              :disabled="!selected.includes(item.occupation.occupationId) && selected.length >= 3"
              @change="select(item.occupation.occupationId)"
            />
            对比 {{ item.occupation.name }}</label
          >
        </li>
      </ol>
    </template>
    <h3>选择 2–3 个职业对比</h3>
    <label class="field"
      >添加目录职业<select v-model="choice" :disabled="loading || selected.length >= 3">
        <option value="">请选择</option>
        <option v-for="entry in catalog" :key="entry.occupationId" :value="entry.occupationId">
          {{ entry.name }}
        </option>
      </select></label
    >
    <button
      class="btn"
      :disabled="!choice || selected.length >= 3 || selected.includes(choice)"
      @click="addChoice"
    >
      添加对比
    </button>
    <div class="selection-list">
      <button v-for="id in selected" :key="id" class="btn" @click="select(id)">
        移除 {{ catalog.find((entry) => entry.occupationId === id)?.name ?? '所选职业' }}
      </button>
    </div>
    <p role="status">
      已选 {{ selected.length }} / 3 个职业<span v-if="comparing">，正在读取对比…</span>
    </p>
    <p v-if="comparisonError" role="alert">
      {{ comparisonError }} <button class="btn" @click="loadComparison">重试对比</button>
    </p>
    <template v-if="comparison">
      <p class="subtle">
        雷达为职业目录的六维兴趣强度（0–100），不代表你的能力。完整值与资料如下；暂无数据留空说明。
      </p>
      <CareerRadar :comparison="comparison" />
      <div
        class="comparison-table"
        tabindex="0"
        role="region"
        aria-label="职业对比数据，可横向滚动"
      >
        <table>
          <caption>
            职业对比：目录原始数据与当前画像兴趣匹配
          </caption>
          <thead>
            <tr>
              <th scope="col">维度</th>
              <th
                v-for="occupation in comparison.occupations"
                :key="occupation.occupationId"
                scope="col"
              >
                {{ occupation.name }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="axis in comparison.axes" :key="axis">
              <th scope="row">{{ axis }}</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ Math.round(occupation.riasec[axis] * 100) }}
              </td>
            </tr>
            <tr>
              <th scope="row">兴趣匹配 (r+1)/2</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{
                  occupation.match == null
                    ? '请先完善画像'
                    : Math.round(occupation.match * 100) + '%'
                }}
              </td>
            </tr>
            <tr>
              <th scope="row">校招薪资</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.salaryRange?.campus ?? '暂无数据' }}
                {{ occupation.salaryRange?.source }}
              </td>
            </tr>
            <tr>
              <th scope="row">核心技能要求</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.coreSkills.map((skill) => skill.name).join('；') || '暂无数据' }}
              </td>
            </tr>
            <tr>
              <th scope="row">成长路径</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.growthPath || '暂无数据' }}
              </td>
            </tr>
            <tr>
              <th scope="row">目录来源</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.source }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>
<style scoped>
.recommend-row {
  padding: 12px;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 12px;
  overflow-wrap: anywhere;
}
.selection-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.comparison-table {
  max-width: 100%;
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  padding: 12px;
  border-bottom: 1px solid var(--color-border, #d1d5db);
  min-width: 110px;
  max-width: 320px;
  overflow-wrap: anywhere;
}
input[type='checkbox'] {
  width: 18px;
  height: 18px;
  min-height: 18px;
}
</style>
