<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const appName = import.meta.env.VITE_APP_NAME || 'OfferPilot'

interface NavItem {
  name: string
  label: string
}

const navItems: NavItem[] = [
  { name: 'dashboard', label: '工作台' },
  { name: 'chat', label: '对话' },
  { name: 'discover', label: '职业探索' },
  { name: 'resumes', label: '简历' },
  { name: 'jobs', label: '岗位' },
  { name: 'board', label: '看板' },
  { name: 'interviews', label: '模拟面试' },
  { name: 'career', label: '职业规划' },
  { name: 'kb', label: '知识库' },
]

const route = useRoute()
const currentName = computed(() => String(route.name ?? ''))
</script>

<template>
  <div class="flex min-h-screen gap-4 p-4 text-foreground">
    <!-- 悬浮玻璃侧栏 -->
    <aside
      class="glass-panel sticky top-4 hidden h-[calc(100vh-2rem)] w-56 shrink-0 flex-col rounded-2xl px-3 py-4 lg:flex"
    >
      <div class="px-2 pb-5">
        <span class="text-gradient text-base font-semibold">{{ appName }}</span>
      </div>

      <nav class="flex flex-1 flex-col gap-1">
        <RouterLink
          v-for="(item, index) in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          :style="{ animationDelay: `${index * 45}ms` }"
          class="rise-in rounded-xl px-3 py-2 text-sm transition-colors duration-300"
          :class="
            currentName === item.name
              ? 'bg-primary/15 font-medium text-primary'
              : 'text-muted-foreground hover:bg-foreground/5 hover:text-foreground'
          "
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <RouterLink
        :to="{ name: 'settings' }"
        class="rounded-xl px-3 py-2 text-sm text-muted-foreground transition-colors duration-300 hover:bg-foreground/5 hover:text-foreground"
      >
        设置
      </RouterLink>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col gap-4">
      <header
        class="glass-panel sticky top-4 z-10 flex h-14 shrink-0 items-center justify-between rounded-2xl px-5"
      >
        <h1 class="text-sm font-medium">{{ route.meta.title ?? appName }}</h1>

        <div class="flex items-center gap-4">
          <span class="hidden items-center gap-1.5 text-xs text-muted-foreground sm:flex">
            <span class="pulse-dot size-1.5 rounded-full bg-success" />
            就绪
          </span>
          <RouterLink
            :to="{ name: 'notifications' }"
            class="text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            通知
          </RouterLink>
        </div>
      </header>

      <main class="min-w-0 flex-1 pb-4">
        <RouterView />
      </main>
    </div>
  </div>
</template>
