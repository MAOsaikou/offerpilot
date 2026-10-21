<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  confirmInterviewPlanUpdate,
  previewInterviewPlanUpdates,
  type LearningPlanUpdate,
} from '@/shared/api/learningPlans'
const props = defineProps<{ reportId: string }>()
const online = ref(navigator.onLine)
const controller = new AbortController()
function connection() {
  online.value = navigator.onLine
}
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
onBeforeUnmount(() => {
  controller.abort()
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
const cache = useQueryClient()
const proposals = useQuery({
  queryKey: computed(() => ['interview-plan-updates', props.reportId]),
  queryFn: ({ signal }) => previewInterviewPlanUpdates(props.reportId, signal),
  retry: false,
})
const busy = ref('')
const error = ref('')
const applied = ref<string[]>([])
async function confirm(proposal: LearningPlanUpdate) {
  if (busy.value || !online.value) return
  busy.value = proposal.planId
  error.value = ''
  try {
    await confirmInterviewPlanUpdate(proposal, controller.signal)
    if (controller.signal.aborted) return
    applied.value = [...applied.value, proposal.planId]
    void cache.invalidateQueries({ queryKey: ['learning-plans'] })
  } catch (cause) {
    if (!controller.signal.aborted) error.value = (cause as Error).message
  } finally {
    busy.value = ''
  }
}
</script>
<template>
  <section class="form-stack plan-updates" :aria-label="uiText('Plan update suggestions')">
    <h3>{{ uiText("Plan update suggestions") }}</h3>
    <p class="helper-note"> {{ uiText("Add tasks from this report's gaps to an existing plan for the same target job. Changes require confirmation. New tasks start incomplete.") }} </p>
    <p v-if="!online" role="status">{{ uiText("You are offline. Reconnect to reload and confirm suggestions.") }}</p>
    <p v-if="proposals.isPending.value" role="status">{{ uiText("Finding learning plans for the same target job…") }}</p>
    <div v-else-if="proposals.isError.value" role="alert">
      <p>{{ formatDisplay(proposals.error.value?.message) }}</p>
      <button class="btn" :disabled="!online" @click="proposals.refetch()">{{ uiText("Retry update suggestions") }}</button>
    </div>
    <p v-else-if="!proposals.data.value?.length" role="status"> {{ uiText("No matching plan can be updated: none may exist, there may be no new gaps, or these suggestions may already have been accepted.") }} </p>
    <article v-for="proposal in proposals.data.value" :key="proposal.planId" class="form-stack">
      <RouterLink :to="{ path: '/career', query: { plan: proposal.planId } }"> {{ uiText("View existing plan · {0}", [proposal.targetRole]) }}
      </RouterLink>
      <template v-if="!applied.includes(proposal.planId)">
        <ul>
          <template v-for="week in proposal.additions" :key="week.index">
            <li v-for="item in week.items" :key="item.id"> {{ uiText("Week {0}: {1} · {2} hours", [week.index, item.title, item.hours]) }} <p v-for="resource in item.resources" :key="resource" class="helper-note">
                {{ resource }}
              </p>
            </li>
          </template>
        </ul>
        <button class="btn primary" :disabled="Boolean(busy) || !online" @click="confirm(proposal)">
          {{ busy === proposal.planId ? uiText("Updating…") : uiText("Confirm update to this plan") }}
        </button>
      </template>
      <p v-else role="status">{{ uiText("Plan updated with new tasks to study. Existing tasks and completion records are retained.") }}</p>
    </article>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button class="btn" :disabled="Boolean(busy) || !online" @click="proposals.refetch()"> {{ uiText("Reload update suggestions") }} </button>
    </div>
  </section>
</template>
<style scoped>
.plan-updates {
  min-width: 0;
  overflow-wrap: anywhere;
}
button {
  white-space: normal;
}
</style>
