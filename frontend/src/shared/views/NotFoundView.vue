<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="flex min-h-screen flex-col items-center justify-center gap-5 px-4">
    <p class="text-gradient rise-in font-mono text-6xl font-semibold">404</p>
    <p class="rise-in text-sm text-muted-foreground" style="animation-delay: 80ms">
      页面不存在或已被移动
    </p>
    <RouterLink
      :to="{ name: 'dashboard' }"
      class="rise-in rounded-xl bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-lg shadow-primary/25 transition-transform duration-300 hover:-translate-y-0.5"
      style="animation-delay: 160ms"
    >
      返回工作台
    </RouterLink>
  </div>
</template>
