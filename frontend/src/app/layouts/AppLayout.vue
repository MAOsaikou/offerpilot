<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import BrandMark from '@/shared/components/BrandMark.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const route = useRoute()
const workspace = useWorkspace()
const mobileOpen = ref(false)
const unread = computed(() => workspace.state.notices.filter((n) => !n.read).length)
const items = [
  { name: 'dashboard', label: '工作台', path: '/' },
  { name: 'chat', label: 'AI 对话', path: '/chat' },
  { name: 'discover', label: '职业探索', path: '/discover' },
  { name: 'resumes', label: '简历', path: '/resumes' },
  { name: 'jobs', label: '岗位', path: '/jobs' },
  { name: 'board', label: '求职看板', path: '/board' },
  { name: 'interviews', label: '模拟面试', path: '/interviews' },
  { name: 'career', label: '成长路线', path: '/career' },
  { name: 'kb', label: '知识库', path: '/kb' },
]
function active(path: string) {
  return path === '/' ? route.path === '/' : route.path.startsWith(path)
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
        ><AppIcon name="plus" :size="16" />新对话</RouterLink
      >
      <nav class="nav-list" aria-label="主导航">
        <template v-for="(item, index) in items" :key="item.name">
          <span v-if="index === 2" class="nav-divider" aria-hidden="true" />
          <RouterLink
            :to="item.path"
            class="nav-link"
            :class="{ active: active(item.path) }"
            :aria-current="active(item.path) ? 'page' : undefined"
            ><AppIcon :name="item.name" :size="18" />{{ item.label }}</RouterLink
          >
        </template>
      </nav>
      <div class="sidebar-foot">
        <p class="storage-note">
          草稿和看板保存在这个浏览器里。上传的简历、对话和匹配报告保存在后端，岗位来自企业官网。
        </p>
        <RouterLink
          to="/notifications"
          class="nav-link"
          :class="{ active: active('/notifications') }"
          ><AppIcon name="bell" :size="18" />通知<span v-if="unread" class="nav-count num">{{
            unread
          }}</span></RouterLink
        >
        <RouterLink to="/settings" class="profile-link"
          ><span class="avatar">{{ workspace.state.name.slice(0, 1) }}</span
          ><span>{{ workspace.state.name }}<small>本地工作区</small></span
          ><AppIcon name="settings" :size="18"
        /></RouterLink>
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
        <p v-if="workspace.storageError" class="error-banner" role="alert">
          {{ workspace.storageError }}
        </p>
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>
