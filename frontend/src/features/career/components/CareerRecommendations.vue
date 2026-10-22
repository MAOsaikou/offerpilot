<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

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
import CareerCardFacts from '@/shared/components/CareerCardFacts.vue'
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
const comparisonError = localizedText()
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
const message = (cause: unknown) =>
  cause instanceof Error ? cause.message : uiText('Request failed. Please retry.')
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
    comparisonError.value = () => uiText('Select 2–3 different careers')
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
    prompt: uiText('Reorder recommendations using my constraints and save a new revision.'),
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
    <h2 id="recommend-title">{{ uiText('Career recommendations and comparison') }}</h2>
    <p class="subtle">
      {{
        uiText(
          'Based on your career profile and the catalog. Add constraints to save a new revision that can be restored from its link.',
        )
      }}
    </p>
    <p v-if="!online" role="status">
      {{ uiText('You are offline. Retry when connected. Unsaved constraints stay on this page.') }}
    </p>
    <p v-if="loading" role="status">{{ uiText('Loading recommendations…') }}</p>
    <div v-if="error" role="alert">
      {{ error }} <button class="btn" @click="load">{{ uiText('Reload') }}</button>
    </div>
    <p v-if="!loading && !revision">
      {{ uiText('No recommendation revisions yet. Complete your')
      }}<RouterLink to="/discover">{{ uiText('career profile') }}</RouterLink
      >{{ uiText(' before generating recommendations.') }}
    </p>
    <button class="btn" :disabled="loading || busy || !online" @click="save(false)">
      {{
        revision
          ? uiText('Recommend using latest profile')
          : uiText('Generate career recommendations')
      }}
    </button>
    <template v-if="revision">
      <form class="form-stack" @submit.prevent="save(true)">
        <div class="form-grid">
          <label class="field"
            >{{ uiText('Preferred city')
            }}<input v-model="constraints.city" maxlength="100" :disabled="busy"
          /></label>
          <label class="field"
            >{{ uiText('Minimum monthly salary (CNY thousands)')
            }}<input
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
          {{ uiText('Avoid overtime') }}</label
        >
        <label
          ><input v-model="constraints.research" type="checkbox" :disabled="busy" />
          {{ uiText('Prefer research (weighted by investigative interest)') }}</label
        >
        <p class="helper-note">
          {{
            uiText(
              'The catalog lacks city coverage and working hours; both remain unverified. Salary comparisons use sourced ranges only. The built-in catalog currently lacks reliable salary data, so missing values do not affect ranking.',
            )
          }}
        </p>
        <button class="btn primary" :disabled="busy || !online">
          {{ busy ? uiText('Saving…') : uiText('Save constraints and reorder') }}
        </button>
        <RouterLink v-if="!busy" :to="chatLink" class="btn">{{
          uiText('Confirm reordering in chat')
        }}</RouterLink>
      </form>
      <p role="status">
        {{ uiText('Server ranking time: {0} ms', [revision.elapsedMs])
        }}<span v-if="revision.explanationFallback">
          {{ uiText('· Using a short rule-based explanation within budget') }}</span
        >
      </p>
      <RouterLink
        v-if="revision.parentId"
        :to="{ query: { ...route.query, revision: revision.parentId } }"
        >{{ uiText('View previous revision') }}</RouterLink
      >
      <ol class="form-stack">
        <li
          v-for="item in revision.items"
          :key="item.occupation.occupationId"
          class="recommend-row"
        >
          <strong>{{ formatDisplay(item.rank) }}. {{ item.occupation.name }}</strong>
          <span v-if="revision.parentId" class="tag" data-rank-delta>{{
            item.rankDelta > 0
              ? uiText('↑ Moved up') + ' ' + item.rankDelta
              : item.rankDelta < 0
                ? uiText('↓ Moved down') + ' ' + Math.abs(item.rankDelta)
                : uiText('Rank unchanged')
          }}</span>
          <p>{{ item.changeReason }}</p>
          <CareerCardFacts :details="item.details" :match="item.occupation.match" />
          <label
            ><input
              type="checkbox"
              :checked="selected.includes(item.occupation.occupationId)"
              :disabled="!selected.includes(item.occupation.occupationId) && selected.length >= 3"
              @change="select(item.occupation.occupationId)"
            />
            {{ uiText('Compare {0}', [item.occupation.name]) }}</label
          >
        </li>
      </ol>
    </template>
    <h3>{{ uiText('Compare 2–3 careers') }}</h3>
    <label class="field"
      >{{ uiText('Add a career from the catalog')
      }}<select v-model="choice" :disabled="loading || selected.length >= 3">
        <option value="">{{ uiText('Please select') }}</option>
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
      {{ uiText('Add to comparison') }}
    </button>
    <div class="selection-list">
      <button v-for="id in selected" :key="id" class="btn" @click="select(id)">
        {{
          uiText('Remove {0}', [
            catalog.find((entry) => entry.occupationId === id)?.name ?? uiText('Selected careers'),
          ])
        }}
      </button>
    </div>
    <p role="status">
      {{ uiText('{0} of 3 careers selected', [selected.length])
      }}<span v-if="comparing">{{ uiText(', loading comparison…') }}</span>
    </p>
    <p v-if="comparisonError" role="alert">
      {{ comparisonError }}
      <button class="btn" @click="loadComparison">{{ uiText('Retry comparison') }}</button>
    </p>
    <template v-if="comparison">
      <p class="subtle">
        {{
          uiText(
            'The radar shows catalog interest dimensions (0–100), not your abilities. See the full values and sources below. Missing data is labeled explicitly.',
          )
        }}
      </p>
      <CareerRadar :comparison="comparison" />
      <div
        class="comparison-table"
        tabindex="0"
        role="region"
        :aria-label="uiText('Career comparison data. Scroll horizontally to view.')"
      >
        <table>
          <caption>
            {{
              uiText('Career comparison: catalog data and current profile interest match')
            }}
          </caption>
          <thead>
            <tr>
              <th scope="col">{{ uiText('Dimension') }}</th>
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
              <th scope="row">{{ uiText('Interest match (r+1)/2') }}</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{
                  occupation.match == null
                    ? uiText('Complete your profile first')
                    : Math.round(occupation.match * 100) + '%'
                }}
              </td>
            </tr>
            <tr>
              <th scope="row">{{ uiText('Graduate salary') }}</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.salaryRange?.campus ?? uiText('No data available') }}
                {{ occupation.salaryRange?.source }}
              </td>
            </tr>
            <tr>
              <th scope="row">{{ uiText('Core skill requirements') }}</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{
                  occupation.coreSkills.map((skill) => skill.name).join('；') ||
                  uiText('No data available')
                }}
              </td>
            </tr>
            <tr>
              <th scope="row">{{ uiText('Career path') }}</th>
              <td v-for="occupation in comparison.occupations" :key="occupation.occupationId">
                {{ occupation.growthPath || uiText('No data available') }}
              </td>
            </tr>
            <tr>
              <th scope="row">{{ uiText('Catalog source') }}</th>
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
