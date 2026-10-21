<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import GenerationProgressDock from '@/shared/components/GenerationProgressDock.vue'
import BrandMark from '@/shared/components/BrandMark.vue'
import DemoBanner from '@/shared/components/DemoBanner.vue'
import PageBoundary from './PageBoundary.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { useAuthStore } from '@/shared/stores/auth'
import { useNotifications } from '@/shared/stores/notifications'
import { translate as t } from '@/shared/utils/language'
const route = useRoute()
const router = useRouter()
const workspace = useWorkspace()
const auth = useAuthStore()
const mobileOpen = ref(false)
const notices = useNotifications()
const unread = computed(() => notices.unread)
watch(
  () => [auth.identityKey, route.fullPath],
  () => void notices.refresh(),
  { immediate: true },
)
const noticeTimer = setInterval(() => {
  if (navigator.onLine) void notices.refresh()
}, 60000)
onBeforeUnmount(() => clearInterval(noticeTimer))
const displayName = computed(() => auth.user?.nickname || workspace.state.name)

function onLogout() {
  auth.logout()
  router.push({ name: 'login' })
}
const items = computed(() => [
  { name: 'dashboard', label: t('工作台', 'Dashboard'), path: '/' },
  { name: 'chat', label: t('AI 对话', 'AI chat'), path: '/chat' },
  { name: 'discover', label: t('职业探索', 'Career discovery'), path: '/discover' },
  { name: 'resumes', label: t('简历', 'Resumes'), path: '/resumes' },
  { name: 'jobs', label: t('岗位', 'Jobs'), path: '/jobs' },
  { name: 'board', label: t('求职看板', 'Applications'), path: '/board' },
  { name: 'interviews', label: t('模拟面试', 'Interviews'), path: '/interviews' },
  { name: 'career', label: t('成长路线', 'Growth plan'), path: '/career' },
  { name: 'kb', label: t('内置知识库', 'Knowledge base'), path: '/kb/builtin' },
  { name: 'kb-notes', label: t('我的资料', 'My materials'), path: '/kb' },
])
function active(path: string) {
  return path === '/'
    ? route.path === '/'
    : path === '/kb'
      ? route.path === '/kb'
      : route.path.startsWith(path)
}
watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false
  },
)
</script>
<template>
  <div class="app-shell">
    <button
      v-if="mobileOpen"
      class="nav-backdrop"
      aria-label="关闭导航"
      @click="mobileOpen = false"
    />
    <aside class="sidebar" :class="{ 'is-open': mobileOpen }">
      <RouterLink to="/" class="brand" aria-label="OfferPilot 职航，回到工作台">
        <span class="brand-mark"><BrandMark /></span>
        <span><span class="brand-word">OfferPilot</span><small>职航</small></span>
      </RouterLink>
      <RouterLink to="/chat" class="sidebar-new"
        ><AppIcon name="plus" :size="16" />{{ t('新对话', 'New chat') }}</RouterLink
      >
      <nav class="nav-list" aria-label="主导航">
        <template v-for="(item, index) in items" :key="item.name">
          <span v-if="index === 2" class="nav-divider" aria-hidden="true" />
          <RouterLink
            :to="item.path"
            class="nav-link"
            :class="{ active: active(item.path) }"
            :aria-current="active(item.path) ? 'page' : undefined"
            ><AppIcon :name="item.name === 'kb-notes' ? 'kb' : item.name" :size="18" />{{
              item.label
            }}</RouterLink
          >
        </template>
      </nav>
      <div class="sidebar-foot">
        <p class="storage-note">
          草稿保存在当前浏览器；求职看板、面试练习、上传的简历、对话和匹配报告保存在服务端。岗位来自企业官网。
        </p>
        <RouterLink
          to="/notifications"
          class="nav-link"
          :class="{ active: active('/notifications') }"
          ><AppIcon name="bell" :size="18" />{{ t('通知', 'Notifications')
          }}<span v-if="unread" class="nav-count num">{{ unread }}</span></RouterLink
        >
        <RouterLink to="/settings" class="profile-link"
          ><span class="avatar">{{ displayName.slice(0, 1) }}</span
          ><span
            >{{ displayName
            }}<small>{{
              auth.isLoggedIn ? t('已登录', 'Signed in') : t('访客体验', 'Guest')
            }}</small></span
          ><AppIcon name="settings" :size="18"
        /></RouterLink>
        <button class="nav-link" type="button" @click="onLogout">
          <AppIcon name="logout" :size="18" />{{ t('退出登录', 'Sign out') }}
        </button>
      </div>
    </aside>
    <div class="app-body">
      <header class="mobile-bar">
        <button
          class="icon-button"
          aria-label="打开导航"
          :aria-expanded="mobileOpen"
          @click="mobileOpen = !mobileOpen"
        >
          <AppIcon name="menu" />
        </button>
        <strong>{{ route.meta.title }}</strong>
        <RouterLink to="/notifications" class="icon-button" aria-label="通知"
          ><AppIcon name="bell" /><i v-if="unread" class="unread-dot"
        /></RouterLink>
      </header>
      <main id="main-content" class="workspace-content">
        <DemoBanner />
        <p v-if="workspace.storageError" class="error-banner" role="alert">
          {{ workspace.storageError }}
        </p>
        <RouterView v-slot="{ Component, route: pageRoute }">
          <!-- Keep enter motion, but remove the old page without waiting on CSS. -->
          <Transition name="page" @leave="(_, done) => done()">
            <PageBoundary
              v-if="Component"
              :key="pageRoute.name ?? pageRoute.path"
              :reset-key="pageRoute.fullPath"
            >
              <component :is="Component" />
            </PageBoundary>
          </Transition>
        </RouterView>
      </main>
    </div>
    <GenerationProgressDock />
  </div>
</template>
