<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const route = useRoute()
const workspace = useWorkspace()
const mobileOpen = ref(false)
const unread = computed(() => workspace.state.notices.filter((n) => !n.read).length)
const items = [
  { name: 'dashboard', label: '工作台', path: '/' },
  { name: 'chat', label: 'AI 对话', path: '/chat' },
  { name: 'discover', label: '职业探索', path: '/discover' },
  { name: 'resumes', label: '简历中心', path: '/resumes' },
  { name: 'jobs', label: '发现岗位', path: '/jobs' },
  { name: 'board', label: '求职看板', path: '/board' },
  { name: 'interviews', label: '模拟面试', path: '/interviews' },
  { name: 'career', label: '成长路线', path: '/career' },
  { name: 'kb', label: '我的知识库', path: '/kb' },
]
function active(path: string) {
  return path === '/' ? route.path === '/' : route.path.startsWith(path)
}
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
      <RouterLink to="/" class="brand" @click="mobileOpen = false"
        ><span class="brand-mark"><AppIcon name="spark" :size="25" /></span
        ><span>OfferPilot<small>职航 · CAREER COPILOT</small></span></RouterLink
      >
      <RouterLink to="/chat" class="sidebar-new" @click="mobileOpen = false"
        ><AppIcon name="plus" /> 开始新对话 <span>↗</span></RouterLink
      >
      <p class="nav-caption">我的工作空间</p>
      <nav aria-label="主导航">
        <RouterLink
          v-for="item in items"
          :key="item.name"
          :to="item.path"
          :class="{ active: active(item.path) }"
          :aria-current="active(item.path) ? 'page' : undefined"
          @click="mobileOpen = false"
          ><AppIcon :name="item.name" />{{ item.label
          }}<span v-if="item.name === 'chat'" class="nav-ai">AI</span></RouterLink
        >
      </nav>
      <div class="sidebar-foot">
        <div class="journey-tip">
          <AppIcon name="discover" /><strong>每一步，都更接近理想</strong>
          <p>把目标拆成行动，让成长看得见。</p>
          <RouterLink to="/career" @click="mobileOpen = false"
            >查看成长路线 <span>→</span></RouterLink
          >
        </div>
        <RouterLink to="/settings" class="profile-link" @click="mobileOpen = false"
          ><span class="avatar">{{ workspace.state.name.slice(0, 1) }}</span
          ><span>{{ workspace.state.name }}<small>本地工作区</small></span
          ><AppIcon name="settings"
        /></RouterLink>
      </div>
    </aside>
    <div class="app-body">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="打开导航"
            :aria-expanded="mobileOpen"
            @click="mobileOpen = !mobileOpen"
          >
            <AppIcon name="menu" /></button
          ><span>我的空间</span><span class="divider">/</span
          ><strong>{{ route.meta.title }}</strong>
        </div>
        <div class="topbar-actions">
          <span class="local-badge"><i /> 本地体验</span
          ><RouterLink to="/settings/connection" class="connection-link">联调设置</RouterLink
          ><RouterLink to="/notifications" class="icon-button notification-link" aria-label="通知"
            ><AppIcon name="bell" /><i v-if="unread" /></RouterLink
          ><span class="avatar small">{{ workspace.state.name.slice(0, 1) }}</span>
        </div>
      </header>
      <div class="workspace-notice">
        业务数据仅保存在此浏览器 · 岗位为示例 · AI 对话连接真实后端
      </div>
      <p v-if="workspace.storageError" class="error-banner" role="alert">
        {{ workspace.storageError }}
      </p>
      <main id="main-content" class="workspace-content"><RouterView /></main>
    </div>
  </div>
</template>
