<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink } from 'vue-router'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import { useAuthStore } from '@/shared/stores/auth'
import { isActiveTask, cancelGenerationTask } from '@/shared/api/generationTasks'
const auth = useAuthStore()
const store = useGenerationTasks()
const open = ref(false)
const cancellingId = ref('')
const cancelError = ref('')
// Completion notices auto-collapse so the dock never sits on top of fresh results.
const NOTICE_TTL_MS = 6000
let noticeTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => store.notice,
  (text) => {
    clearTimeout(noticeTimer)
    if (text) noticeTimer = setTimeout(() => (store.notice = ''), NOTICE_TTL_MS)
  },
  { immediate: true },
)
const compact = computed(() => !open.value && !store.notice && store.active.length === 0)
watch(
  () => auth.identityKey,
  () => {
    store.reset()
    if (auth.isAuthenticated) void store.refresh()
  },
  { immediate: true },
)
function refreshVisible() {
  if (auth.isAuthenticated && !document.hidden) void store.refresh()
}
onMounted(() => {
  window.addEventListener('focus', refreshVisible)
  window.addEventListener('online', refreshVisible)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshVisible)
  window.removeEventListener('online', refreshVisible)
  clearTimeout(noticeTimer)
})
async function cancelTask(id: string, kind: string, entity?: string) {
  cancellingId.value = id
  cancelError.value = ''
  try {
    if (entity) await store.cancel(id)
    else {
      await cancelGenerationTask(kind, id)
      await store.refresh()
    }
  } catch (cause) {
    cancelError.value = cause instanceof Error ? cause.message : '取消失败，请重试'
  } finally {
    cancellingId.value = ''
  }
}
</script>
<template>
  <aside
    class="generation-dock"
    :class="{ 'generation-dock--compact': compact }"
    aria-label="后台生成进度"
  >
    <p v-if="store.notice" role="status" aria-live="polite">
      {{ store.notice }}
      <button class="btn" aria-label="关闭任务通知" @click="store.notice = ''">关闭</button>
    </p>
    <header class="generation-dock__head">
      <strong>{{ compact ? '后台任务' : `后台任务 · ${store.active.length} 进行中` }}</strong>
      <button type="button" class="btn" :aria-expanded="open" @click="open = !open">
        {{ open ? '收起' : '展开' }}
      </button>
    </header>
    <template v-if="open">
      <p v-if="store.error || cancelError" class="generation-dock__error" role="alert">
        {{ cancelError || store.error }}
      </p>
      <button class="btn" @click="store.refresh">刷新任务</button>
      <p v-if="!store.tasks.length">暂无后台任务</p>
      <ul class="generation-dock__list">
        <li v-for="task in store.tasks" :key="task.id">
          <div class="generation-dock__row">
            <RouterLink :to="task.href">{{ task.title }}</RouterLink>
            <button
              v-if="isActiveTask(task)"
              class="btn"
              :disabled="cancellingId === task.id"
              @click="cancelTask(task.id, task.kind, task.entityId)"
            >
              取消
            </button>
          </div>
          <p class="subtle">{{ task.error || task.stage || '正在执行' }}</p>
        </li>
      </ul>
    </template>
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

<style scoped>
.generation-dock--compact {
  width: auto;
  padding: 4px 6px 4px 12px;
  border-radius: 999px;
  opacity: 0.85;
  font-size: 12px;
}
@media (max-width: 720px) {
  .generation-dock {
    right: 8px;
    bottom: 8px;
    width: min(320px, calc(100vw - 16px));
  }
  .generation-dock--compact {
    width: auto;
  }
}
.generation-dock__list {
  max-height: min(55vh, 480px);
  overflow-y: auto;
  overflow-wrap: anywhere;
}
</style>
