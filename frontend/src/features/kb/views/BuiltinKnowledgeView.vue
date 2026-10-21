<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  getBuiltinFacets,
  listBuiltinOccupations,
  getBuiltinOccupation,
  listBuiltinEntries,
  searchBuiltinKnowledge,
  builtinKinds,
  questionTypes,
  difficulties,
  type BuiltinKind,
} from '@/shared/api/builtin-knowledge'
import { useBuiltinFilters } from '../stores/builtinFilters'
const route = useRoute(),
  filters = useBuiltinFilters()
const queryDraft = ref(filters.q)
const tabs = computed(() => ({ OCCUPATION: uiText('Career entry'), ...builtinKinds }))
const id = computed(() => String(route.params.occupationId || ''))
const criteria = computed(() => ({
  q: filters.q,
  page: filters.page,
  size: 20,
  family: filters.family,
  holland: filters.holland,
}))
const entryCriteria = computed(() => ({
  q: filters.q,
  page: filters.page,
  size: 20,
  kind: filters.tab,
  industry: filters.tab === 'CAMPUS' ? '' : filters.industry,
  role: filters.tab === 'REPORT' || filters.tab === 'CAMPUS' ? '' : filters.role,
  questionType: filters.tab === 'QUESTION' ? filters.questionType : '',
  difficulty: filters.tab === 'QUESTION' ? filters.difficulty : '',
}))
const facets = useQuery({
  queryKey: computed(() => ['builtin-facets', sessionRevision.value]),
  queryFn: ({ signal }) => getBuiltinFacets(signal),
  retry: false,
})
const occupations = useQuery({
  queryKey: computed(() => ['builtin-occupations', sessionRevision.value, criteria.value]),
  queryFn: ({ signal }) => listBuiltinOccupations(criteria.value, signal),
  enabled: computed(() => !id.value && filters.tab === 'OCCUPATION'),
  retry: false,
})
const entries = useQuery({
  queryKey: computed(() => ['builtin-entries', sessionRevision.value, entryCriteria.value]),
  queryFn: ({ signal }) => listBuiltinEntries(entryCriteria.value, signal),
  enabled: computed(() => !id.value && filters.tab !== 'OCCUPATION'),
  retry: false,
})
const detail = useQuery({
  queryKey: computed(() => ['builtin-occupation', sessionRevision.value, id.value]),
  queryFn: ({ signal }) => getBuiltinOccupation(id.value, signal),
  enabled: computed(() => !!id.value),
  retry: false,
})
const referenceText = ref('')
const references = useQuery({
  queryKey: computed(() => [
    'builtin-reference-search',
    sessionRevision.value,
    filters.tab,
    referenceText.value,
  ]),
  queryFn: ({ signal }) =>
    searchBuiltinKnowledge(referenceText.value, filters.tab as BuiltinKind, signal),
  enabled: computed(
    () =>
      !id.value &&
      filters.tab !== 'OCCUPATION' &&
      filters.tab !== 'REPORT' &&
      !!referenceText.value,
  ),
  retry: false,
})
const active = computed(() => (filters.tab === 'OCCUPATION' ? occupations : entries))
const total = computed(() => active.value.data.value?.total ?? 0)
const busy = computed(() => active.value.isPending.value)
watch(
  () => [
    filters.tab,
    filters.family,
    filters.holland,
    filters.industry,
    filters.role,
    filters.questionType,
    filters.difficulty,
  ],
  () => {
    filters.page = 1
    referenceText.value = ''
  },
)
function submit() {
  const same = filters.q === queryDraft.value.trim() && filters.page === 1
  filters.q = queryDraft.value.trim()
  filters.page = 1
  if (same) void active.value.refetch()
}
</script>
<template>
  <section class="page-stack builtin-catalog">
    <PageHeading
      :title="uiText('Built-in knowledge')"
      :description="
        uiText(
          'Explore careers, understand skill requirements and practice general questions. Sources and missing data are disclosed for each item.',
        )
      "
    >
      <RouterLink class="btn" to="/kb">{{ uiText('My resources and notes') }}</RouterLink>
    </PageHeading>
    <p v-if="facets.data.value" class="subtle" role="status">
      {{
        uiText(
          '{0} direct O*NET careers · {1} catalog IDs (including Chinese mappings) · {2} curated general questions',
          [
            facets.data.value.directOnetOccupations,
            facets.data.value.occupations,
            facets.data.value.counts.QUESTION ?? 0,
          ],
        )
      }}
    </p>
    <div v-if="facets.isError.value" role="alert" class="error-banner">
      {{ uiText('Could not load categories.')
      }}<button class="btn" @click="facets.refetch()">{{ uiText('Retry categories') }}</button>
    </div>
    <template v-if="id">
      <RouterLink to="/kb/builtin" class="btn">{{ uiText('Back to career catalog') }}</RouterLink>
      <p v-if="detail.isPending.value" role="status">
        {{
          detail.fetchStatus.value === 'paused'
            ? uiText('You are offline. Reconnect and retry.')
            : uiText('Loading career details…')
        }}
      </p>
      <div v-else-if="detail.isError.value" role="alert" class="error-banner">
        {{
          uiText(
            'Could not load career details. The entry may not exist or the connection may be unavailable.',
          )
        }}<button class="btn" @click="detail.refetch()">{{ uiText('Retry details') }}</button>
      </div>
      <article v-else-if="detail.data.value" class="panel form-stack">
        <h2>{{ formatDisplay(detail.data.value.name) }}</h2>
        <p>{{ formatDisplay(detail.data.value.family) }}</p>
        <p class="catalog-copy">{{ formatDisplay(detail.data.value.description) }}</p>
        <h3>
          {{
            uiText('RIASEC interest code: {0}', [
              detail.data.value.hollandCode ?? uiText('No data available'),
            ])
          }}
        </h3>
        <dl class="catalog-grid">
          <div v-for="(value, axis) in detail.data.value.riasec" :key="axis">
            <dt>{{ axis }}</dt>
            <dd>{{ value }}</dd>
          </div>
        </dl>
        <p v-if="!detail.data.value.riasec">{{ uiText('No interest scores available') }}</p>
        <h3>{{ uiText('Core skills') }}</h3>
        <p v-if="detail.data.value.skillsSource === 'curated'" class="subtle">
          {{
            uiText('Curated editorial suggestions, not official O*NET technical skill standards')
          }}
        </p>
        <ul v-if="detail.data.value.coreSkills?.length">
          <li v-for="skill in detail.data.value.coreSkills" :key="skill.name">
            {{ skill.name }} · {{ formatDisplay(skill.level) }}
          </li>
        </ul>
        <p v-else>{{ uiText('No skill data available') }}</p>
        <h3>{{ uiText('Salary') }}</h3>
        <p>{{ formatDisplay(detail.data.value.salaryNotice) }}</p>
        <pre v-if="detail.data.value.salaryRange" class="catalog-copy">{{
          formatDisplay(JSON.stringify(detail.data.value.salaryRange, null, 2))
        }}</pre>
        <h3>{{ uiText('Career path') }}</h3>
        <p>
          {{
            detail.data.value.growthPath ??
            uiText('No data: no reliable career path information yet')
          }}
        </p>
        <p v-if="detail.data.value.growthPathSource === 'curated'" class="subtle">
          {{ uiText('Curated editorial guidance, not a guarantee of actual advancement') }}
        </p>
        <p>{{ formatDisplay(detail.data.value.notice) }}</p>
        <p>
          {{
            uiText('Source: {0} · O*NET-SOC {1} · {2}', [
              detail.data.value.source,
              detail.data.value.onetCode,
              detail.data.value.license,
            ])
          }}
        </p>
        <a
          v-if="detail.data.value.sourceUrl"
          :href="detail.data.value.sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
          >{{ uiText('View original O*NET career') }}</a
        >
        <p class="subtle catalog-copy">{{ formatDisplay(detail.data.value.attribution) }}</p>
      </article>
    </template>
    <template v-else>
      <nav class="catalog-tabs" :aria-label="uiText('Built-in knowledge categories')">
        <button
          v-for="(label, key) in tabs"
          :key="key"
          class="btn"
          :aria-pressed="filters.tab === key"
          @click="filters.tab = key"
        >
          {{ label }}
        </button>
      </nav>
      <p v-if="filters.tab === 'QUESTION'" class="subtle">
        {{
          uiText(
            'source=curated: general question patterns and job scenarios organized with AI assistance. Not real candidate interview reports; questions have not each been reviewed by experts.',
          )
        }}
      </p>
      <p v-if="filters.tab === 'REPORT'" class="subtle">
        {{
          uiText(
            'Reliable reports are planned. This section currently has structure and navigation only, with no claims about industry size, salary or growth.',
          )
        }}
      </p>
      <form class="panel form-stack" @submit.prevent="submit">
        <label class="field"
          >{{ uiText('Search')
          }}<input
            v-model="queryDraft"
            maxlength="2000"
            :placeholder="uiText('Careers, skills or practice topics')"
        /></label>
        <div v-if="filters.tab === 'OCCUPATION'" class="catalog-grid">
          <label class="field"
            >{{ uiText('Career category')
            }}<select v-model="filters.family">
              <option value="">{{ uiText('All categories') }}</option>
              <option v-for="family in facets.data.value?.families" :key="family">
                {{ family }}
              </option>
            </select></label
          >
          <label class="field"
            >{{ uiText('Interest code prefix')
            }}<input
              v-model="filters.holland"
              maxlength="3"
              pattern="[RIASECriasec]{0,3}"
              :placeholder="uiText('For example: I or IRC')"
          /></label>
        </div>
        <div v-else-if="filters.tab !== 'CAMPUS'" class="catalog-grid">
          <label class="field"
            >{{ uiText('Industry')
            }}<select v-model="filters.industry">
              <option value="">{{ uiText('All industries') }}</option>
              <option v-for="industry in facets.data.value?.industries" :key="industry">
                {{ industry }}
              </option>
            </select></label
          >
          <label v-if="filters.tab !== 'REPORT'" class="field"
            >{{ uiText('Jobs')
            }}<select v-model="filters.role">
              <option value="">{{ uiText('All jobs') }}</option>
              <option v-for="role in facets.data.value?.roles" :key="role">{{ role }}</option>
            </select></label
          >
          <template v-if="filters.tab === 'QUESTION'">
            <label class="field"
              >{{ uiText('Question type')
              }}<select v-model="filters.questionType">
                <option value="">{{ uiText('All question types') }}</option>
                <option v-for="(label, key) in questionTypes" :key="key" :value="key">
                  {{ label }}
                </option>
              </select></label
            >
            <label class="field"
              >{{ uiText('Difficulty')
              }}<select v-model="filters.difficulty">
                <option value="">{{ uiText('All difficulty levels') }}</option>
                <option v-for="(label, key) in difficulties" :key="key" :value="key">
                  {{ label }}
                </option>
              </select></label
            >
          </template>
        </div>
        <div class="catalog-tabs">
          <button class="btn primary" type="submit">{{ uiText('Search catalog') }}</button
          ><button
            v-if="filters.tab !== 'OCCUPATION' && filters.tab !== 'REPORT'"
            class="btn"
            type="button"
            :disabled="!queryDraft.trim()"
            @click="referenceText = queryDraft.trim()"
          >
            {{ uiText('Search references') }}
          </button>
        </div>
      </form>
      <p v-if="busy" role="status">
        {{
          active.fetchStatus.value === 'paused'
            ? uiText('You are offline. Reconnect and retry.')
            : uiText('Loading catalog…')
        }}
      </p>
      <div v-else-if="active.isError.value" class="error-banner" role="alert">
        {{ uiText('Could not load. Check your sign-in status and connection.')
        }}<button class="btn" @click="active.refetch()">{{ uiText('Retry catalog') }}</button>
      </div>
      <template v-else>
        <p role="status">{{ uiText('{0} entries · Page {1}', [total, filters.page]) }}</p>
        <p v-if="!total">{{ uiText('No matching entries. Adjust your filters.') }}</p>
        <div v-if="filters.tab === 'OCCUPATION'" class="catalog-cards">
          <article
            v-for="item in occupations.data.value?.items"
            :key="item.id"
            class="panel form-stack min-w-0"
          >
            <h2>
              <RouterLink :to="`/kb/builtin/${encodeURIComponent(item.id)}`">{{
                item.name
              }}</RouterLink>
            </h2>
            <p>{{ item.family }} · {{ item.hollandCode }}</p>
            <p class="catalog-copy">{{ item.description }}</p>
            <p>{{ item.salaryNotice }}</p>
            <p class="subtle">{{ item.source }} · {{ item.license }}</p>
          </article>
        </div>
        <article
          v-for="item in filters.tab === 'OCCUPATION' ? [] : entries.data.value?.items"
          :key="item.id"
          class="panel form-stack min-w-0"
        >
          <h2>{{ item.title }}</h2>
          <p>
            {{ item.industry }} {{ item.role }}
            <span v-if="item.questionType">· {{ questionTypes[item.questionType] }}</span>
            <span v-if="item.difficulty">· {{ difficulties[item.difficulty] }}</span>
          </p>
          <p v-if="item.status === 'PLANNED'" class="subtle">
            {{ uiText('Planned · No report data yet') }}
          </p>
          <p class="catalog-copy">{{ item.content }}</p>
          <details v-if="item.payload.answerGuide">
            <summary>{{ uiText('Practice approach (not a model answer)') }}</summary>
            <ul>
              <li v-for="guide in item.payload.answerGuide" :key="guide">{{ guide }}</li>
            </ul>
          </details>
          <p v-if="item.payload.plannedSections">
            {{ uiText('Planned section: {0}', [item.payload.plannedSections.join('、')]) }}
          </p>
          <p class="subtle catalog-copy">
            {{ item.source }} · {{ item.license }} · {{ item.notice }}
          </p>
        </article>
        <nav class="catalog-tabs" :aria-label="uiText('Pagination')">
          <button class="btn" :disabled="filters.page <= 1" @click="filters.page--">
            {{ uiText('Previous page') }}</button
          ><button class="btn" :disabled="filters.page * 20 >= total" @click="filters.page++">
            {{ uiText('Next page') }}
          </button>
        </nav>
      </template>
      <aside
        v-if="referenceText && filters.tab !== 'OCCUPATION' && filters.tab !== 'REPORT'"
        class="panel form-stack"
        :aria-label="uiText('Reference search')"
      >
        <h2>{{ uiText('References: {0}', [referenceText]) }}</h2>
        <p v-if="references.isPending.value" role="status">
          {{
            references.fetchStatus.value === 'paused'
              ? uiText('You are offline. Search when connected.')
              : uiText('Searching…')
          }}
        </p>
        <p v-else-if="references.isError.value" role="alert">
          {{ uiText('Reference search failed.')
          }}<button class="btn" @click="references.refetch()">{{ uiText('Retry search') }}</button>
        </p>
        <template v-else
          ><p>{{ formatDisplay(references.data.value?.notice) }}</p>
          <p v-if="!references.data.value?.items.length">{{ uiText('No reference results') }}</p>
          <article v-for="hit in references.data.value?.items" :key="hit.id">
            <h3>{{ hit.title }}</h3>
            <p class="catalog-copy">{{ hit.excerpt }}</p>
            <p class="subtle">
              {{ uiText('Citation ID: {0} · {1} · {2}', [hit.id, hit.source, hit.notice]) }}
            </p>
          </article></template
        >
      </aside>
    </template>
  </section>
</template>
<style scoped>
.catalog-grid,
.catalog-cards {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
}
.catalog-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.catalog-tabs button[aria-pressed='true'] {
  outline: 2px solid var(--color-primary, #315c55);
  outline-offset: 2px;
}
.builtin-catalog {
  min-width: 0;
}
.catalog-copy {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.builtin-catalog input,
.builtin-catalog select {
  max-width: 100%;
  min-width: 0;
}
.builtin-catalog button,
.builtin-catalog summary {
  min-height: 44px;
}
</style>
