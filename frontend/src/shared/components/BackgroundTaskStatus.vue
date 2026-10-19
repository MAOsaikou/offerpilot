<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import { isActiveTask, type GenerationKind } from '@/shared/api/generationTasks'
const props = defineProps<{ kind: GenerationKind; entityId: string }>()
const emit = defineEmits<{ completed: [id: string] }>()
const store = useGenerationTasks()
const route = useRoute()
const router = useRouter()
const selectedId = computed(() =>
  typeof route.query.taskId === 'string' ? route.query.taskId : undefined,
)
const loadError = ref('')
const task = computed(() =>
  selectedId.value
    ? store.tasks.find(
        (t) => t.id === selectedId.value && t.kind === props.kind && t.entityId === props.entityId,
      )
    : store.latest(props.kind, props.entityId),
)
async function load() {
  loadError.value = ''
  try {
    await store.restore(props.kind, props.entityId, selectedId.value)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '后台任务读取失败'
  }
}
watch(() => [props.kind, props.entityId, selectedId.value], load, { immediate: true })
watch(
  () => (task.value?.status === 'SUCCEEDED' ? task.value.id : ''),
  (id) => {
    if (id) emit('completed', id)
  },
  { immediate: true },
)
// A new run replaces the previous result deep link before the user navigates or reloads.
watch(
  () => store.startedTaskId,
  async (id) => {
    const started = store.tasks.find(
      (t) => t.id === id && t.kind === props.kind && t.entityId === props.entityId,
    )
    if (!started) return
    const query: LocationQueryRaw = { ...route.query, taskId: id }
    if (props.kind === 'JOB_MATCH') delete query.matchId
    if (props.kind === 'RESUME_OPTIMIZE') delete query.optimizationReportId
    if (props.kind === 'CAREER_RECOMMENDATION') delete query.revision
    try {
      await router.replace({ query })
    } catch (cause) {
      loadError.value = cause instanceof Error ? cause.message : '任务链接更新失败'
    }
  },
)
// Result reads are kept in the feature, so restoring progress never performs a new generation.
</script>
<template>
  <div class="background-task-status">
    <p v-if="loadError || store.error" role="alert">
      {{ loadError || store.error }} <button class="btn" @click="load">重新读取任务</button>
    </p>
    <p v-if="task && isActiveTask(task)" role="status" aria-live="polite">
      <progress :aria-label="task.title" /> {{ task.stage }}。可以离开页面，后台会继续。
      <button
        class="btn"
        @click="store.cancel(task.id).catch((cause) => (loadError = cause.message))"
      >
        取消任务
      </button>
    </p>
    <p v-else-if="task?.status === 'FAILED'" role="alert">{{ task.error }}</p>
    <p v-else-if="task?.status === 'CANCELLED'" role="status">{{ task.stage }}，可重新发起。</p>
  </div>
</template>
<style scoped>
.background-task-status {
  overflow-wrap: anywhere;
}
progress {
  width: 64px;
  max-width: 100%;
  height: 8px;
}
</style>
