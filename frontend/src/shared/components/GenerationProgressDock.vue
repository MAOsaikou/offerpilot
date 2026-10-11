<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { cancelGenerationTask, listGenerationTasks } from '@/shared/api/generationTasks'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const open = ref(true)
const queryClient = useQueryClient()
const query = useQuery({
  queryKey: computed(() => ['generation-tasks', auth.identityKey]),
  queryFn: ({ signal }) => listGenerationTasks(signal),
  enabled: computed(() => Boolean(auth.identityKey)),
  refetchInterval: (q) => (q.state.data?.length ? 3000 : 8000),
  retry: false,
})
const tasks = computed(() => query.data.value ?? [])
const kindLabel: Record<string, string> = {
  JOB_MATCH: '匹配',
  RESUME_OPTIMIZE: '改写',
  RESUME_DIAGNOSIS: '诊断',
}
const cancellingId = ref<string | null>(null)
const cancelError = ref('')
const cancelMutation = useMutation({
  mutationFn: ({ kind, id }: { kind: string; id: string }) => cancelGenerationTask(kind, id),
  onMutate: ({ id }) => {
    cancellingId.value = id
    cancelError.value = ''
  },
  onSuccess: async () => {
    await Promise.all(
      ['generation-tasks', 'job-matches', 'diagnoses', 'optimizations', 'applications'].map((key) =>
        queryClient.invalidateQueries({ queryKey: [key] }),
      ),
    )
  },
  onError: (error: unknown) => {
    cancelError.value = error instanceof Error ? error.message : '取消失败，请稍后重试'
  },
  onSettled: () => {
    cancellingId.value = null
  },
})

function cancelTask(kind: string, id: string) {
  if (cancellingId.value) return
  cancelMutation.mutate({ kind, id })
}
</script>
<template>
  <aside
    v-if="tasks.length"
    class="generation-dock"
    :class="{ 'is-collapsed': !open }"
    aria-label="后台生成进度"
  >
    <header class="generation-dock__head">
      <strong>后台进行中 · {{ tasks.length }}</strong>
      <button type="button" class="btn" @click="open = !open">
        {{ open ? '收起' : '展开' }}
      </button>
    </header>
    <p v-if="cancelError" class="generation-dock__error" role="alert">{{ cancelError }}</p>
    <ul v-if="open" class="generation-dock__list">
      <li v-for="task in tasks" :key="task.id">
        <div class="generation-dock__row">
          <RouterLink :to="task.href">
            <span class="tag">{{ kindLabel[task.kind] || task.kind }}</span>
            {{ task.title }}
            <span v-if="task.modelTier" class="subtle"> · {{ task.modelTier }}</span>
          </RouterLink>
          <button
            type="button"
            class="btn generation-dock__cancel"
            :disabled="cancellingId === task.id"
            @click="cancelTask(task.kind, task.id)"
          >
            {{ cancellingId === task.id ? '取消中…' : '取消' }}
          </button>
        </div>
        <p class="subtle">匹配离开页面后继续；诊断和改写离开分析页会停止。可取消后重新发起。</p>
      </li>
    </ul>
  </aside>
</template>
<style scoped>
.generation-dock {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 40;
  width: min(320px, calc(100vw - 32px));
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 12px;
  background: var(--panel, #fff);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  padding: 12px;
}
.generation-dock__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.generation-dock__list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.generation-dock__list a {
  font-weight: 600;
}
.generation-dock__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.generation-dock__cancel {
  flex-shrink: 0;
  font-size: 12px;
}
.generation-dock__error {
  margin: 8px 0 0;
  color: var(--danger, #b91c1c);
  font-size: 12px;
}
</style>
