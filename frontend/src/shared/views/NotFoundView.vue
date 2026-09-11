<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="flex min-h-screen flex-col items-center justify-center gap-4 bg-background px-4">
    <p class="font-mono text-5xl font-semibold text-primary">404</p>
    <p class="text-sm text-muted-foreground">页面不存在或已被移动</p>
    <RouterLink
      :to="{ name: 'dashboard' }"
      class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground transition-opacity hover:opacity-90"
    >
      返回工作台
    </RouterLink>
  </div>
</template>
