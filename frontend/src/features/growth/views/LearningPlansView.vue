<script setup lang="ts">
import { formatDisplay, formatDate, translate as uiText } from '@/shared/utils/language'

import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import RoadmapCard from '../components/RoadmapCard.vue'
import { listLearningPlans, exportLearningCalendar } from '@/shared/api/learningPlans'
const route = useRoute()
const plans = useQuery({
  queryKey: ['learning-plans'],
  queryFn: ({ signal }) => listLearningPlans(signal),
  retry: false,
})
const busy = ref(''),
  error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())
async function download(id: string) {
  if (busy.value) return
  busy.value = id
  error.value = ''
  try {
    await exportLearningCalendar(id, controller.signal)
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}
</script>
<template>
  <section class="panel form-stack" :aria-label="uiText('Interview learning plans')">
    <h2>{{ uiText('Turn interview insights into weekly action') }}</h2>
    <p v-if="plans.isPending.value" role="status">{{ uiText('Loading learning plans…') }}</p>
    <p v-else-if="plans.isError.value" role="alert">
      {{ formatDisplay(plans.error.value?.message) }}
      <button class="btn" @click="plans.refetch()">{{ uiText('Retry plans') }}</button>
    </p>
    <p v-else-if="!plans.data.value?.length">
      {{
        uiText(
          'No interview learning plans yet. Complete an interview, then generate a plan from its review.',
        )
      }}
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <article
      v-for="plan in plans.data.value"
      :id="'plan-' + plan.id"
      :key="plan.id"
      class="form-stack"
      :aria-label="
        route.query.plan === plan.id
          ? uiText('Newly generated learning plan')
          : uiText('Saved learning plan')
      "
    >
      <p>
        {{ uiText('Start date: {0} ·', [formatDate(plan.startDate)]) }}
        <RouterLink :to="'/interviews/' + plan.reportId">{{
          uiText('Source interview report')
        }}</RouterLink>
      </p>
      <RoadmapCard :payload="plan.roadmap" :start-date="plan.startDate" />
      <p class="helper-note">
        {{
          uiText(
            'Weekly tasks follow the start date, defaulting to 19:00 Shanghai time. Download and import into your calendar, then adjust times as needed.',
          )
        }}
      </p>
      <button class="btn" :disabled="Boolean(busy)" @click="download(plan.id)">
        {{ busy === plan.id ? uiText('Exporting…') : uiText('Add to calendar (download .ics)') }}
      </button>
    </article>
  </section>
</template>
