<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { getJob } from '@/shared/api/jobs'
import { listApplications } from '@/shared/api/applications'
const props = defineProps<{ jobId?: string | null; fallbackTitle: string }>()
const job = useQuery({
  queryKey: computed(() => ['job', props.jobId]),
  queryFn: ({ signal }) => getJob(props.jobId!, signal),
  enabled: computed(() => !!props.jobId),
  retry: false,
})
const applications = useQuery({
  queryKey: ['applications'],
  queryFn: ({ signal }) => listApplications(signal),
  enabled: computed(() => !!props.jobId),
  retry: false,
})
const application = computed(() =>
  applications.data.value?.find((item) => item.jobId === props.jobId),
)
const href = computed(() =>
  application.value
    ? { path: '/board', query: { application: application.value.id } }
    : { path: '/jobs/' + props.jobId },
)
</script>
<template>
  <p v-if="jobId" class="subtle interview-job-context">
    {{ uiText('For:')
    }}<RouterLink :to="href">{{
      formatDisplay(job.data.value?.title || fallbackTitle)
    }}</RouterLink>
    <span v-if="application"> {{ uiText('· Application workspace') }}</span>
  </p>
</template>

<style scoped>
.interview-job-context {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.interview-job-context a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
