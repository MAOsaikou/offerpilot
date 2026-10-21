<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ApplicationDrawer from '../components/ApplicationDrawer.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useBoard } from '@/shared/composables/useBoard'
import {
  APPLICATION_DRAG_TYPE,
  applicationStageLabels as applicationStages,
  type ApplicationStatus,
} from '@/shared/types/board'
import ApplicationCard from '../components/ApplicationCard.vue'
import PendingAgentWrites from '@/shared/components/PendingAgentWrites.vue'
const board = useBoard()
const route = useRoute()
const router = useRouter()
const selected = computed(() =>
  typeof route.query.application === 'string' ? route.query.application : '',
)
const dropTarget = ref<ApplicationStatus | ''>('')
function dragOver(event: DragEvent, status: ApplicationStatus) {
  if (!event.dataTransfer?.types.includes(APPLICATION_DRAG_TYPE) || board.busy.value) return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'move'
  dropTarget.value = status
}
function dragLeave(event: DragEvent, status: ApplicationStatus) {
  const next = event.relatedTarget as Node | null
  if (dropTarget.value === status && !(event.currentTarget as HTMLElement).contains(next))
    dropTarget.value = ''
}
function drop(event: DragEvent, status: ApplicationStatus) {
  dropTarget.value = ''
  const id = event.dataTransfer?.getData(APPLICATION_DRAG_TYPE)
  if (!id) return
  event.preventDefault()
  void board.move(id, status)
}
function closeDetail() {
  const query = { ...route.query }
  delete query.application
  void router.replace({ query })
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('Track every opportunity')"
      :description="uiText('Manage your job search at your own pace.')"
    >
      <RouterLink to="/jobs" class="btn primary">{{ uiText('＋ Find jobs') }}</RouterLink>
    </PageHeading>
    <div class="helper-note">
      {{
        uiText(
          '{0} opportunities · Drag cards between columns or select a stage on a card. Open an opportunity for materials, history and next steps. Changing a stage does not submit a real application.',
          [board.applications.value.length],
        )
      }}
    </div>
    <PendingAgentWrites scope="board" />
    <p v-if="board.query.isPending.value" class="panel" role="status">
      {{ uiText('Loading board…') }}
    </p>
    <div v-else-if="board.query.isError.value" class="error-banner" role="alert">
      {{ formatDisplay(board.query.error.value?.message) }}
      <button class="btn" @click="board.query.refetch()">{{ uiText('Retry loading') }}</button>
    </div>
    <div v-else-if="!board.applications.value.length" class="panel empty-state">
      <h2>{{ uiText('Add your first opportunity') }}</h2>
      <p>
        {{ uiText('Save a job to track your application, interview and offer progress here.') }}
      </p>
      <RouterLink to="/jobs" class="btn primary">{{ uiText('Find jobs') }}</RouterLink>
    </div>
    <ApplicationDrawer v-if="selected" :id="selected" :key="selected" @close="closeDetail" />
    <div
      v-if="
        !board.query.isPending.value &&
        !board.query.isError.value &&
        board.applications.value.length
      "
      class="kanban"
    >
      <section
        v-for="(label, status) in applicationStages"
        :key="status"
        class="kanban-column"
        :class="{ 'drop-target': dropTarget === status }"
        :data-stage="status"
        @dragover="dragOver($event, status)"
        @dragleave="dragLeave($event, status)"
        @drop="drop($event, status)"
      >
        <h2>
          {{ label }}
          <span>{{
            formatDisplay(board.applications.value.filter((a) => a.status === status).length)
          }}</span>
        </h2>
        <ApplicationCard
          v-for="app in board.applications.value.filter((a) => a.status === status)"
          :key="app.id"
          :application="app"
        />
        <p v-if="!board.applications.value.some((a) => a.status === status)" class="subtle">
          {{ uiText('No opportunities yet') }}
        </p>
      </section>
    </div>
    <p v-if="board.error.value" class="error-banner" role="alert">
      {{
        uiText(
          'Could not update stage: {0}. The card has returned to its original column. Please retry.',
          [board.error.value],
        )
      }}
    </p>
  </section>
</template>
<style scoped>
.kanban-column.drop-target {
  outline: 2px dashed var(--color-primary, #4f46e5);
  outline-offset: -4px;
  background: color-mix(in srgb, var(--color-primary, #4f46e5) 6%, transparent);
}
</style>
