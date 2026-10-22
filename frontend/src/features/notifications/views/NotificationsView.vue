<script setup lang="ts">
import { computed } from 'vue'

import { localizedText, language, translate as uiText } from '@/shared/utils/language'

import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import {
  getNotifications,
  readAllNotifications,
  readNotification,
  type Notification,
} from '@/shared/api/notifications'
import { useNotifications } from '@/shared/stores/notifications'
import { useAuthStore } from '@/shared/stores/auth'
const notices = useNotifications()
const auth = useAuthStore()
const items = ref<Notification[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = localizedText()
const online = ref(navigator.onLine)
const labels = computed(() => ({
  DDL: uiText('Deadline reminder'),
  NEW_JOB: uiText('New job'),
  INTERVIEW_PLAN: uiText('Interview plan'),
  CAREER_TARGET: uiText('Goal coordination'),
  PROFILE_UPDATE: uiText('Profile suggestion'),
}))
let generation = 0
let reader = 0
async function load() {
  const current = generation,
    revision = ++reader
  loading.value = true
  error.value = ''
  try {
    const result = await getNotifications(page.value)
    if (current !== generation || revision !== reader) return
    items.value = result.items
    total.value = result.total
    notices.setCount(result.unread)
  } catch (cause) {
    if (current === generation && revision === reader)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not load notifications')
  } finally {
    if (current === generation && revision === reader) loading.value = false
  }
}
function changePage(delta: number) {
  page.value += delta
  void load()
}
async function mark(id?: string) {
  if (busy.value) return
  const current = generation
  busy.value = true
  error.value = ''
  try {
    await (id ? readNotification(id) : readAllNotifications())
    if (current === generation) await load()
  } catch (cause) {
    if (current === generation)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not mark as read. Please retry.')
  } finally {
    if (current === generation) busy.value = false
  }
}
function connection() {
  online.value = navigator.onLine
  if (online.value) void load()
}
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
watch(
  () => auth.identityKey,
  () => {
    generation++
    items.value = []
    total.value = 0
    page.value = 1
    busy.value = false
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  generation++
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('Notification center')"
      :description="uiText('{0} unread · Server notifications', [notices.unread])"
    >
      <button class="btn" :disabled="!notices.unread || busy || loading || !online" @click="mark()">
        {{ uiText('Mark all as read') }}
      </button>
    </PageHeading>
    <p v-if="!online" role="status">
      {{ uiText('You are offline. Reconnect to load and mark notifications.') }}
    </p>
    <p v-if="loading" role="status">{{ uiText('Loading notifications…') }}</p>
    <div v-if="error" role="alert">
      {{ error }}
      <button class="btn" :disabled="loading || !online" @click="load">
        {{ uiText('Reload') }}
      </button>
    </div>
    <section class="panel">
      <div v-if="!loading && !error && !items.length" class="empty-state">
        <AppIcon name="bell" :size="40" />
        <h2>{{ uiText('No notifications yet') }}</h2>
        <p>
          {{
            uiText(
              'Job recommendations, board deadlines and interview plan updates will appear here.',
            )
          }}
        </p>
      </div>
      <article
        v-for="notice in items"
        :key="notice.id"
        class="notice-item"
        :class="{ 'is-read': notice.readAt }"
      >
        <div class="notice-content">
          <span class="tag">{{ labels[notice.kind] }}</span>
          <h2>{{ notice.title }}</h2>
          <p>{{ notice.body }}</p>
          <time class="subtle" :datetime="notice.createdAt">{{
            new Date(notice.createdAt).toLocaleString(language)
          }}</time>
          <RouterLink :to="notice.link" class="btn text">{{ uiText('View details') }}</RouterLink>
        </div>
        <button
          v-if="!notice.readAt"
          class="btn text"
          :disabled="busy || loading || !online"
          @click="mark(notice.id)"
        >
          {{ uiText('Mark as read') }}</button
        ><span v-else class="tag">{{ uiText('Read') }}</span>
      </article>
      <nav v-if="total > 20" :aria-label="uiText('Notification pagination')" class="notice-pages">
        <button
          class="btn"
          :disabled="page === 1 || loading || busy || !online"
          @click="changePage(-1)"
        >
          {{ uiText('Previous page') }}
        </button>
        <span>{{ uiText('Page {0} · {1} entries in total', [page, total]) }}</span>
        <button
          class="btn"
          :disabled="page * 20 >= total || loading || busy || !online"
          @click="changePage(1)"
        >
          {{ uiText('Next page') }}
        </button>
      </nav>
    </section>
  </section>
</template>
<style scoped>
.notice-content {
  min-width: 0;
  overflow-wrap: anywhere;
}
.notice-pages {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
@media (max-width: 600px) {
  .notice-item {
    flex-wrap: wrap;
  }
}
</style>
