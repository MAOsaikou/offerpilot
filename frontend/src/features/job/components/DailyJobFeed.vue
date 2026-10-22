<script setup lang="ts">
import { formatDisplay, formatDate, translate as uiText } from '@/shared/utils/language'

import { useQuery } from '@tanstack/vue-query'
import { RouterLink } from 'vue-router'
import { getJobRecommendations } from '@/shared/api/jobs'
import { useBoard } from '@/shared/composables/useBoard'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
const board = useBoard()
const feed = useQuery({
  queryKey: ['job-recommendations'],
  queryFn: ({ signal }) => getJobRecommendations(signal),
  retry: false,
  staleTime: 60000,
})
</script>
<template>
  <section class="panel form-stack" :aria-label="uiText('Daily job recommendations')">
    <div class="section-heading">
      <h2>{{ uiText("Today's recommendations") }}</h2>
      <button class="btn" :disabled="feed.isFetching.value" @click="feed.refetch()">
        {{ uiText('Refresh recommendations') }}
      </button>
    </div>
    <p class="subtle">
      {{
        uiText(
          'Ranks real local job records using your profile, target career and latest saved resume. Cached daily and refreshed when your materials change. Scores reflect rule-based relevance.',
        )
      }}
    </p>
    <p v-if="feed.isPending.value" role="status">{{ uiText('Loading recommendations…') }}</p>
    <div v-else-if="feed.isError.value" class="error-banner" role="alert">
      {{ formatDisplay(feed.error.value?.message)
      }}<button class="btn" @click="feed.refetch()">{{ uiText('Retry') }}</button>
    </div>
    <p v-else-if="!feed.data.value?.items.length" class="helper-note">
      {{
        uiText(
          'No real jobs to recommend yet. Complete your profile, save a resume or search for jobs to build up records.',
        )
      }}
    </p>
    <template v-else>
      <p class="subtle">
        {{ formatDisplay(formatDate(feed.data.value.date)) }} ·
        {{ feed.data.value.cached ? uiText("Today's cache") : uiText('Updated') }}
      </p>
      <article v-for="item in feed.data.value.items" :key="item.jobId" class="recommendation">
        <RouterLink :to="'/jobs/' + item.jobId"
          ><h3>{{ item.job.title }}</h3></RouterLink
        >
        <p>
          {{
            uiText('{0} · {1} · Relevance {2}', [
              item.job.company || uiText('Company not provided'),
              item.job.locations || uiText('City not provided'),
              item.score,
            ])
          }}
        </p>
        <JobSourceBadge :source="item.job.source" :cached="true" />
        <p class="subtle">
          {{
            uiText('Job updated: {0}', [
              (item.job.updatedAt && formatDate(item.job.updatedAt)) || uiText('Not provided #2'),
            ])
          }}
        </p>
        <ul>
          <li v-for="reason in item.reasons" :key="reason">{{ reason }}</li>
        </ul>
        <button
          class="btn"
          :disabled="
            board.saved(item.jobId) ||
            board.busy.value ||
            board.query.isPending.value ||
            board.query.isError.value
          "
          @click="board.favorite(item.jobId)"
        >
          {{ board.saved(item.jobId) ? uiText('On your board') : uiText('Add to board') }}
        </button>
      </article>
    </template>
    <p v-if="board.query.isError.value" role="alert">
      {{ uiText('Could not load board.')
      }}<button class="btn" @click="board.query.refetch()">{{ uiText('Retry board') }}</button>
    </p>
    <p v-if="board.error.value" class="error-banner" role="alert">
      {{ formatDisplay(board.error.value) }}
    </p>
  </section>
</template>
<style scoped>
.recommendation {
  min-width: 0;
  overflow-wrap: anywhere;
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.section-heading {
  flex-wrap: wrap;
}
</style>
