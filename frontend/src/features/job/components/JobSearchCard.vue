<script setup lang="ts">
import { formatDisplay, formatDate, formatDateTime, language, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { useBoard } from '@/shared/composables/useBoard'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
import { RouterLink } from 'vue-router'
import type { ChatJobSearch } from '@/shared/types/jobs'
import type { JobCardItem } from '@/shared/types/cards'

const props = defineProps<{ payload: ChatJobSearch | JobCardItem }>()
const board = useBoard()
const search = computed(() =>
  props.payload &&
  'schemaVersion' in props.payload &&
  props.payload.criteria &&
  props.payload.meta &&
  Array.isArray(props.payload.items) &&
  Array.isArray(props.payload.meta.skipped) &&
  Array.isArray(props.payload.meta.degradedSites)
    ? props.payload
    : null,
)
const legacy = computed(() =>
  props.payload && 'id' in props.payload && Array.isArray(props.payload.cities)
    ? props.payload
    : null,
)
const more = computed(() => ({
  path: '/jobs',
  query: {
    q: search.value?.criteria.query || '',
    site: search.value?.criteria.site || '',
    nature: search.value?.criteria.nature || 'all',
    location: search.value?.criteria.location || '',
  },
}))
const natureLabel = (value: string) =>
  ({ campus: uiText("Graduate"), intern: uiText("Internship"), social: uiText("Experienced hire"), all: uiText("All types") })[value.toLowerCase()] ||
  uiText("Company recruitment")
</script>

<template>
  <section v-if="search" class="panel form-stack search-card" :aria-label="uiText('Job search results')">
    <div class="section-heading">
      <h3>{{ uiText("Jobs found") }}</h3>
      <JobSourceBadge
        :source="search.meta.source"
        :snapshot-date="search.meta.snapshotDate"
        :cached="search.meta.cached"
      />
    </div>
    <p class="subtle">
      {{ search.criteria.query }} · {{ search.criteria.location || uiText("Any city") }} ·
      {{ natureLabel(search.criteria.nature)
      }}<span v-if="search.criteria.site"> · {{ search.criteria.site }}</span>
    </p>
    <p class="subtle"> {{ uiText("Queried {0} · {1} returned, {2} displayed. This is not the total number of open jobs.", [new Date(search.fetchedAt).toLocaleString(language), search.returnedCount, search.items.length]) }} </p>
    <p v-if="search.meta.partial" role="status" class="helper-note">
      {{ search.meta.timedOut ? uiText("Query timed out. Partial results are shown.") : uiText("Some sources are unavailable. Available results are shown.") }}
    </p>
    <p v-if="board.error.value" role="alert">{{ formatDisplay(board.error.value) }}</p>
    <p v-if="board.query.isError.value" role="alert"> {{ uiText("Could not load board.") }}<button class="btn" @click="board.query.refetch()">{{ uiText("Retry") }}</button>
    </p>
    <p v-if="search.meta.source === 'LOCAL_SNAPSHOT'" class="subtle"> {{ uiText("Manually curated sample snapshot, not live data. Check the company website for current openings.") }} </p>
    <p v-if="search.meta.skipped.length" class="helper-note" role="status"> {{ uiText("Some companies are unavailable: {0}. They are excluded from these results.", [search.meta.skipped.map((s) => s.site).join('、')]) }} </p>
    <p v-if="search.meta.degradedSites.length" class="helper-note">
      {{ uiText("{0} used fallback filtering and may not cover all jobs.", [search.meta.degradedSites.join('、')]) }} </p>
    <p v-if="search.meta.truncated" class="helper-note"> {{ uiText("Upstream results were truncated. Narrow your keywords or choose a company.") }} </p>
    <p v-if="!search.items.length" role="status"> {{ uiText("No jobs found in this query. Adjust keywords, company or recruitment type. Empty results do not mean there are no open jobs.") }} </p>
    <article v-for="job in search.items" :key="job.id" class="search-item">
      <h4>{{ job.title }}</h4>
      <JobSourceBadge
        :source="job.source || search.meta.source"
        :snapshot-date="job.snapshotDate || search.meta.snapshotDate"
        :cached="job.retrievalSource === 'LOCAL_DB' || search.meta.cached"
      />
      <p v-if="job.retrievalSource === 'LOCAL_DB'" class="subtle"> {{ uiText("Local record · {0}", [(job.storedAt && formatDateTime(job.storedAt)) || uiText("Sync time not provided")]) }}
      </p>
      <p>
        {{ job.company || job.site }} · {{ job.locations || uiText("Location not provided") }} ·
        {{ natureLabel(job.nature) }}
      </p>
      <p class="subtle">
        {{ search.meta.source === 'LOCAL_SNAPSHOT' ? uiText("Snapshot date") : uiText("Company update time") }}：{{
          search.meta.source === 'LOCAL_SNAPSHOT'
            ? search.meta.snapshotDate
            : (job.updatedAt && formatDate(job.updatedAt)) || uiText("Not provided #2")
        }}
      </p>
      <RouterLink :to="'/jobs/' + encodeURIComponent(job.id)" class="btn"
        >{{ uiText("View details and select a resume to match") }}</RouterLink
      >
      <button
        v-if="job.id.startsWith('job_')"
        class="btn"
        :disabled="
          board.saved(job.id) ||
          board.busy.value ||
          board.query.isPending.value ||
          board.query.isError.value
        "
        @click="board.favorite(job.id)"
      >
        {{ board.saved(job.id) ? uiText("On your board") : uiText("Add to board") }}
      </button>
    </article>
    <div class="card-footer">
      <RouterLink :to="more" class="btn">{{ uiText("Search again with these filters") }}</RouterLink>
    </div>
    <p class="subtle">{{ uiText("A new search may return different results. Check the company website for availability.") }}</p>
  </section>
  <section v-else-if="legacy" class="panel form-stack search-card">
    <h3>{{ legacy.title }}</h3>
    <p>{{ legacy.company }} · {{ legacy.cities.join('、') }}</p>
    <RouterLink :to="'/jobs/' + encodeURIComponent(legacy.id)" class="btn">{{ uiText("View job details") }}</RouterLink>
  </section>
  <p v-else role="alert">{{ uiText("Job card data is unavailable. Please search again.") }}</p>
</template>

<style scoped>
.search-card {
  min-width: 0;
  overflow-wrap: anywhere;
}
.search-item {
  padding-block: 14px;
  border-top: 1px solid var(--border);
  display: grid;
  gap: 8px;
}
.search-item .btn {
  justify-self: start;
  white-space: normal;
}
.section-heading {
  flex-wrap: wrap;
  gap: 8px;
}
</style>
