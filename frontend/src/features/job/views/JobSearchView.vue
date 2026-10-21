<script setup lang="ts">
import { formatDisplay, formatDate, language, translate as uiText } from '@/shared/utils/language'

import { ref, watch, computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
import JobImportPanel from '../components/JobImportPanel.vue'
import DailyJobFeed from '../components/DailyJobFeed.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import { listJobSites, searchJobs } from '@/shared/api/jobs'
import { useBoard } from '@/shared/composables/useBoard'
const route = useRoute(),
  router = useRouter(),
  board = useBoard()
const query = ref(String(route.query.q || '')),
  site = ref(String(route.query.site || ''))
const nature = ref(String(route.query.nature || 'all')),
  location = ref(String(route.query.location || ''))
const criteria = computed(() => ({
  query: String(route.query.q || ''),
  site: String(route.query.site || ''),
  nature: String(route.query.nature || 'all'),
  location: String(route.query.location || ''),
}))
const sites = useQuery({
  queryKey: ['job-sites'],
  queryFn: ({ signal }) => listJobSites(signal),
  retry: false,
  staleTime: 600000,
})
const search = useQuery({
  queryKey: computed(() => ['jobs', criteria.value]),
  queryFn: ({ signal }) => searchJobs(criteria.value, signal),
  enabled: computed(() => !!criteria.value.query.trim()),
  retry: false,
  staleTime: 600000,
})
watch(criteria, (value) => {
  query.value = value.query
  site.value = value.site
  nature.value = value.nature
  location.value = value.location
})
const selectedSite = computed(() => sites.data.value?.sites.find((s) => s.id === site.value))
const unsupported = computed(
  () =>
    !!selectedSite.value?.supportedNatures.length &&
    nature.value !== 'all' &&
    !selectedSite.value.supportedNatures.includes(nature.value),
)
function submit() {
  if (query.value.trim() && !unsupported.value) {
    const next = {
      q: query.value.trim(),
      site: site.value,
      nature: nature.value,
      location: location.value.trim(),
    }
    if (
      JSON.stringify(criteria.value) ===
      JSON.stringify({
        query: next.q,
        site: next.site,
        nature: next.nature,
        location: next.location,
      })
    )
      void search.refetch()
    else void router.push({ path: '/jobs', query: next })
  }
}
const saved = (id: string) => board.saved(id)
const name = (id: string) => sites.data.value?.sites.find((s) => s.id === id)?.name || id
</script>
<template>
  <section class="page-stack">
    <p v-if="board.query.isPending.value" role="status">
      {{ uiText('Loading application board…') }}
    </p>
    <div v-if="board.query.isError.value" class="error-banner" role="alert">
      {{ uiText('Could not load saved jobs: {0}', [board.query.error.value?.message]) }}
      <button class="btn" @click="board.query.refetch()">{{ uiText('Retry loading') }}</button>
    </div>
    <p v-if="board.error.value" class="error-banner" role="alert">
      {{ uiText('Could not save job: {0}. Please retry.', [board.error.value]) }}
    </p>
    <PageHeading
      :title="uiText('Find your next opportunity')"
      :description="
        uiText(
          'Search jobs in natural language, combining local records with company website results.',
        )
      "
      ><RouterLink to="/board" class="btn">{{
        uiText('Application board')
      }}</RouterLink></PageHeading
    >
    <form class="panel form-stack" @submit.prevent="submit">
      <div class="job-filters">
        <label class="field"
          >{{ uiText('Natural language or job keywords')
          }}<input
            v-model="query"
            required
            maxlength="100"
            :placeholder="uiText('For example: Java internships at Tencent in Shanghai')"
        /></label>
        <label class="field"
          >{{ uiText('Company #2')
          }}<select v-model="site">
            <option value="">{{ uiText('Major companies') }}</option>
            <option v-for="item in sites.data.value?.sites" :key="item.id" :value="item.id">
              {{ item.name }}
            </option>
          </select></label
        >
        <label class="field"
          >{{ uiText('Recruitment type')
          }}<select v-model="nature">
            <option value="campus">{{ uiText('Graduate') }}</option>
            <option value="intern">{{ uiText('Internship') }}</option>
            <option value="social">{{ uiText('Experienced hire') }}</option>
            <option value="all">{{ uiText('All') }}</option>
          </select></label
        >
        <label class="field"
          >{{ uiText('City')
          }}<input v-model="location" maxlength="60" :placeholder="uiText('Any city')"
        /></label>
      </div>
      <p v-if="unsupported" class="error-banner" role="alert">
        {{
          uiText(
            'This company does not list support for this recruitment type. Choose another type or company.',
          )
        }}
      </p>
      <p v-if="sites.isError.value" class="subtle">
        {{ uiText('The company list is unavailable. You can still search major companies.')
        }}<button type="button" class="btn" @click="sites.refetch()">
          {{ uiText('Retry company list') }}
        </button>
      </p>
      <div class="card-footer">
        <span class="subtle">{{
          uiText('Up to 30 results. Filtering support varies by company API.')
        }}</span
        ><button
          class="btn primary"
          :disabled="!query.trim() || unsupported || search.isFetching.value"
        >
          {{ search.isFetching.value ? uiText('Searching jobs…') : uiText('Search jobs') }}
        </button>
      </div>
    </form>
    <JobImportPanel />
    <DailyJobFeed v-if="!criteria.query" />
    <div v-if="!criteria.query" class="panel empty-state">
      <h2>{{ uiText('Describe the job you want') }}</h2>
      <p>
        {{
          uiText(
            'Include a company, city, recruitment type and keywords. Explicit filters take priority. Match results with a parsed resume.',
          )
        }}
      </p>
    </div>
    <div v-else-if="search.isFetching.value" class="panel" role="status">
      {{
        uiText(
          'Parsing and merging jobs. Available results will be shown if the time budget is exceeded…',
        )
      }}
    </div>
    <div v-else-if="search.isError.value" class="error-banner" role="alert">
      <p>{{ formatDisplay(search.error.value?.message) }}</p>
      <button class="btn" @click="search.refetch()">{{ uiText('Search again') }}</button>
    </div>
    <template v-else-if="search.data.value">
      <p v-if="search.data.value.meta.partial" class="helper-note" role="status">
        {{
          uiText('{0} You can search again later.', [
            search.data.value.meta.timedOut
              ? uiText('Query timed out. Partial results are shown.')
              : uiText('Some sources are unavailable. Available results have been returned.'),
          ])
        }}
      </p>
      <p v-if="search.data.value.criteria" class="subtle">
        {{
          uiText('Parsed: {0} · {1} · {2} · {3} ({4})', [
            search.data.value.criteria.company ||
              search.data.value.criteria.site ||
              uiText('Any company'),
            search.data.value.criteria.location || uiText('Any city'),
            {
              campus: uiText('Graduate'),
              intern: uiText('Internship'),
              social: uiText('Experienced hire'),
              all: uiText('All types'),
            }[search.data.value.criteria.nature] || uiText('All types'),
            search.data.value.criteria.query || uiText('Any keywords'),
            search.data.value.criteria.parser === 'FLASH'
              ? uiText('AI parsing')
              : uiText('Rule-based parsing'),
          ])
        }}
      </p>
      <div class="section-heading">
        <span>{{ uiText('{0} jobs returned', [search.data.value.items.length]) }}</span
        ><JobSourceBadge
          :source="search.data.value.meta.source"
          :snapshot-date="search.data.value.meta.snapshotDate"
          :cached="search.data.value.meta.cached"
        />
      </div>
      <p v-if="search.data.value.meta.source === 'LOCAL_SNAPSHOT'" class="subtle">
        {{
          uiText(
            'Manually curated sample snapshot, not live data. Check the company website for current openings.',
          )
        }}
      </p>
      <p class="subtle">
        {{
          uiText('Queried: {0} · Coverage: {1}', [
            new Date(search.data.value.fetchedAt).toLocaleString(language),
            search.data.value.meta.sites.map(name).join('、'),
          ])
        }}
      </p>
      <div v-if="search.data.value.meta.skipped.length" class="helper-note" role="status">
        {{
          uiText('Some companies returned no results: {0}. They are excluded here. Retry later.', [
            search.data.value.meta.skipped.map((s) => name(s.site)).join('、'),
          ])
        }}
      </div>
      <p v-if="search.data.value.meta.degradedSites.length" class="helper-note">
        {{
          uiText(
            '{0} filtered keywords using the company-provided list, which may not include all jobs.',
            [search.data.value.meta.degradedSites.map(name).join('、')],
          )
        }}
      </p>
      <p v-if="search.data.value.meta.truncated" class="helper-note">
        {{ uiText('Results exceed the display limit. Narrow your keywords or choose a company.') }}
      </p>
      <div v-if="!search.data.value.items.length" class="panel empty-state">
        <h2>{{ uiText('No matching jobs in this query') }}</h2>
        <p>
          {{
            uiText(
              'Try shorter keywords, another company or another recruitment type. Empty results do not mean there are no open jobs.',
            )
          }}
        </p>
      </div>
      <div v-else class="card-grid">
        <article v-for="job in search.data.value.items" :key="job.id" class="panel job-card">
          <div class="section-heading">
            <span>{{ job.company }}</span
            ><span class="tag">{{
              job.nature === 'campus'
                ? uiText('Graduate')
                : job.nature === 'intern'
                  ? uiText('Internship')
                  : uiText('Company recruitment')
            }}</span>
          </div>
          <RouterLink :to="'/jobs/' + job.id"
            ><h2>{{ job.title }}</h2></RouterLink
          >
          <JobSourceBadge
            :source="job.source"
            :snapshot-date="job.snapshotDate"
            :cached="job.retrievalSource === 'LOCAL_DB'"
          />
          <p class="subtle">
            {{
              job.retrievalSources?.includes('LOCAL_DB') &&
              job.retrievalSources?.includes('JOBS_MCP')
                ? uiText('Local records + MCP')
                : job.retrievalSource === 'LOCAL_DB'
                  ? uiText('Local records')
                  : uiText('Job source')
            }}
          </p>
          <p class="subtle">
            {{ job.locations || uiText('Location not provided') }} ·
            {{ job.category || uiText('Category not provided') }}
          </p>
          <p class="subtle">
            {{
              job.source === 'LOCAL_SNAPSHOT'
                ? uiText('Snapshot date')
                : uiText('Company update time')
            }}：{{
              job.source === 'LOCAL_SNAPSHOT'
                ? formatDate(job.snapshotDate)
                : (job.updatedAt && formatDate(job.updatedAt)) || uiText('Not provided #2')
            }}
          </p>
          <p v-if="job.storedAt" class="subtle">
            {{ uiText('Local sync: {0}', [new Date(job.storedAt).toLocaleString(language)]) }}
          </p>
          <div class="card-footer">
            <RouterLink :to="'/jobs/' + job.id" class="btn">{{
              uiText('View details and match')
            }}</RouterLink
            ><button
              class="btn"
              :disabled="
                saved(job.id) ||
                board.busy.value ||
                board.query.isPending.value ||
                board.query.isError.value
              "
              @click="board.favorite(job.id)"
            >
              {{ saved(job.id) ? uiText('Saved') : uiText('Save job') }}
            </button>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>
<style scoped>
.job-filters {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 16px;
}
.job-card {
  min-width: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 760px) {
  .job-filters {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 440px) {
  .job-filters {
    grid-template-columns: 1fr;
  }
}
</style>
