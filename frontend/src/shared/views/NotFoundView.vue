<script setup lang="ts">
import { RouterLink } from 'vue-router'
import BrandMark from '@/shared/components/BrandMark.vue'
</script>

<template>
  <div class="flex min-h-screen flex-col items-start justify-center gap-4 px-8 sm:px-24">
    <BrandMark :size="40" />
    <p class="num text-7xl leading-none">404</p>
    <h1 class="text-2xl font-semibold">这段航线不存在</h1>
    <p class="text-muted-foreground">页面可能已被移动，或链接地址有误。</p>
    <RouterLink :to="{ name: 'dashboard' }" class="btn primary mt-2">返回工作台</RouterLink>
  </div>
</template>
