<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { listGenerationTasks } from '@/shared/api/generationTasks'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const open = ref(true)
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
    <ul v-if="open" class="generation-dock__list">
      <li v-for="task in tasks" :key="task.id">
        <RouterLink :to="task.href">
          <span class="tag">{{ kindLabel[task.kind] || task.kind }}</span>
          {{ task.title }}
          <span v-if="task.modelTier" class="subtle"> · {{ task.modelTier }}</span>
        </RouterLink>
        <p class="subtle">离开页面也会继续，可稍后回来查看结果。</p>
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
</style>
