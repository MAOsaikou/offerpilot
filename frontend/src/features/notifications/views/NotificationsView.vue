<script setup lang="ts">
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
const error = ref('')
const online = ref(navigator.onLine)
const labels = {
  DDL: '截止提醒',
  NEW_JOB: '新岗位',
  INTERVIEW_PLAN: '面试计划',
  CAREER_TARGET: '目标联动',
  PROFILE_UPDATE: '画像建议',
}
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
      error.value = cause instanceof Error ? cause.message : '通知读取失败'
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
      error.value = cause instanceof Error ? cause.message : '标记失败，请重试'
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
    <PageHeading title="通知中心" :description="notices.unread + ' 条未读 · 服务端通知'">
      <button class="btn" :disabled="!notices.unread || busy || loading || !online" @click="mark()">
        全部标为已读
      </button>
    </PageHeading>
    <p v-if="!online" role="status">当前离线，恢复连接后可读取和标记通知。</p>
    <p v-if="loading" role="status">正在读取通知…</p>
    <div v-if="error" role="alert">
      {{ error }} <button class="btn" :disabled="loading || !online" @click="load">重新读取</button>
    </div>
    <section class="panel">
      <div v-if="!loading && !error && !items.length" class="empty-state">
        <AppIcon name="bell" :size="40" />
        <h2>暂无通知</h2>
        <p>岗位推荐、看板截止日期与面试计划有新动态时，会在这里提醒。</p>
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
            new Date(notice.createdAt).toLocaleString()
          }}</time>
          <RouterLink :to="notice.link" class="btn text">查看详情</RouterLink>
        </div>
        <button
          v-if="!notice.readAt"
          class="btn text"
          :disabled="busy || loading || !online"
          @click="mark(notice.id)"
        >
          标为已读</button
        ><span v-else class="tag">已读</span>
      </article>
      <nav v-if="total > 20" aria-label="通知分页" class="notice-pages">
        <button
          class="btn"
          :disabled="page === 1 || loading || busy || !online"
          @click="changePage(-1)"
        >
          上一页
        </button>
        <span>第 {{ page }} 页 · 共 {{ total }} 条</span>
        <button
          class="btn"
          :disabled="page * 20 >= total || loading || busy || !online"
          @click="changePage(1)"
        >
          下一页
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
