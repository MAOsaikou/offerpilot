<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { cn } from '@/shared/utils/cn'

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
  <div class="flex min-h-screen bg-background text-foreground">
    <aside class="hidden w-56 shrink-0 flex-col border-r border-border bg-card px-3 py-4 lg:flex">
      <div class="px-2 pb-4 text-lg font-semibold">{{ appName }}</div>

      <nav class="flex flex-1 flex-col gap-1">
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          :class="
            cn(
              'rounded-lg px-3 py-2 text-sm transition-colors hover:bg-muted',
              currentName === item.name
                ? 'bg-muted font-medium text-primary'
                : 'text-muted-foreground',
            )
          "
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <RouterLink
        :to="{ name: 'settings' }"
        class="rounded-lg px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-muted"
      >
        设置
      </RouterLink>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <header class="flex h-14 items-center justify-between border-b border-border px-6">
        <h1 class="text-sm font-medium">{{ route.meta.title ?? appName }}</h1>
        <RouterLink
          :to="{ name: 'notifications' }"
          class="text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          通知
        </RouterLink>
      </header>

      <main class="min-w-0 flex-1">
        <RouterView />
      </main>
    </div>
  </div>
</template>
