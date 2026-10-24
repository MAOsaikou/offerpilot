<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink } from 'vue-router'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import { useAuthStore } from '@/shared/stores/auth'
import { isActiveTask, cancelGenerationTask } from '@/shared/api/generationTasks'
const auth = useAuthStore()
const store = useGenerationTasks()
const open = ref(false)
const cancellingId = ref('')
const cancelError = localizedText()
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
    cancelError.value = () =>
      cause instanceof Error ? cause.message : uiText('Could not cancel. Please retry.')
  } finally {
    cancellingId.value = ''
  }
}
</script>
<template>
  <aside
    class="generation-dock"
    :class="{ 'generation-dock--compact': compact }"
    :aria-label="uiText('Background generation progress')"
  >
    <p v-if="store.notice" role="status" aria-live="polite">
      {{ store.notice }}
      <button
        class="btn"
        :aria-label="uiText('Close task notifications')"
        @click="store.notice = ''"
      >
        {{ uiText('Dismiss') }}
      </button>
    </p>
    <header class="generation-dock__head">
      <strong>{{
        compact
          ? uiText('Background tasks')
          : uiText('Background tasks · {0} active', [store.active.length])
      }}</strong>
      <button type="button" class="btn" :aria-expanded="open" @click="open = !open">
        {{ open ? uiText('Collapse') : uiText('Expand') }}
      </button>
    </header>
    <template v-if="open">
      <p v-if="store.error || cancelError" class="generation-dock__error" role="alert">
        {{ cancelError || store.error }}
      </p>
      <button class="btn" @click="store.refresh">{{ uiText('Refresh tasks') }}</button>
      <p v-if="!store.tasks.length">{{ uiText('No background tasks') }}</p>
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
              {{ uiText('Cancel') }}
            </button>
          </div>
          <p class="subtle">{{ task.error || task.stage || uiText('Running') }}</p>
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
  background: var(--card);
  color: var(--card-foreground);
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
  overflow-wrap: anywhere;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  padding: 12px;
}
.generation-dock__head,
.generation-dock > [role='status'] {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.generation-dock .btn {
  flex-shrink: 0;
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
.generation-dock__row a {
  min-width: 0;
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
  overflow-wrap: anywhere;
}
</style>
