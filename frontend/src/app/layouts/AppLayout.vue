<script setup lang="ts">
import { translateLabel, translate as uiText } from '@/shared/utils/language'

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
const displayName = computed(() => auth.user?.nickname || workspace.displayName)

function onLogout() {
  auth.logout()
  router.push({ name: 'login' })
}
const items = computed(() => [
  { name: 'dashboard', label: uiText('Dashboard'), path: '/' },
  { name: 'chat', label: uiText('AI chat'), path: '/chat' },
  { name: 'discover', label: uiText('Career discovery'), path: '/discover' },
  { name: 'resumes', label: uiText('Resume'), path: '/resumes' },
  { name: 'jobs', label: uiText('Jobs'), path: '/jobs' },
  { name: 'board', label: uiText('Application board'), path: '/board' },
  { name: 'interviews', label: uiText('Mock interview'), path: '/interviews' },
  { name: 'career', label: uiText('Career growth'), path: '/career' },
  { name: 'kb', label: uiText('Built-in knowledge'), path: '/kb/builtin' },
  { name: 'kb-notes', label: uiText('My resources'), path: '/kb' },
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
      :aria-label="uiText('Close navigation')"
      @click="mobileOpen = false"
    />
    <aside class="sidebar" :class="{ 'is-open': mobileOpen }">
      <RouterLink to="/" class="brand" :aria-label="uiText('OfferPilot, back to dashboard')">
        <span class="brand-mark"><BrandMark /></span>
        <span
          ><span class="brand-word">OfferPilot</span><small>{{ uiText('OfferPilot') }}</small></span
        >
      </RouterLink>
      <RouterLink to="/chat" class="sidebar-new"
        ><AppIcon name="plus" :size="16" />{{ uiText('New chat') }}</RouterLink
      >
      <nav class="nav-list" :aria-label="uiText('Main navigation')">
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
          {{
            uiText(
              'Drafts stay in this browser. Applications, interviews, uploaded resumes, conversations and match reports are saved on the server. Jobs come from company websites.',
            )
          }}
        </p>
        <RouterLink
          to="/notifications"
          class="nav-link"
          :class="{ active: active('/notifications') }"
          ><AppIcon name="bell" :size="18" />{{ uiText('Notifications')
          }}<span v-if="unread" class="nav-count num">{{ unread }}</span></RouterLink
        >
        <RouterLink to="/settings" class="profile-link"
          ><span class="avatar">{{ displayName.slice(0, 1) }}</span
          ><span
            >{{ displayName
            }}<small>{{
              auth.isLoggedIn ? uiText('Signed in') : uiText('Guest mode')
            }}</small></span
          ><AppIcon name="settings" :size="18"
        /></RouterLink>
        <button class="nav-link" type="button" @click="onLogout">
          <AppIcon name="logout" :size="18" />{{ uiText('Sign out') }}
        </button>
      </div>
    </aside>
    <div class="app-body">
      <header class="mobile-bar">
        <button
          class="icon-button"
          :aria-label="uiText('Open navigation')"
          :aria-expanded="mobileOpen"
          @click="mobileOpen = !mobileOpen"
        >
          <AppIcon name="menu" />
        </button>
        <strong>{{ route.meta.title && translateLabel(route.meta.title) }}</strong>
        <RouterLink to="/notifications" class="icon-button" :aria-label="uiText('Notifications')"
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
