<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { RouterLink } from 'vue-router'
import BrandMark from '@/shared/components/BrandMark.vue'
</script>

<template>
  <div class="flex min-h-screen flex-col items-start justify-center gap-4 px-8 sm:px-24">
    <BrandMark :size="40" />
    <p class="num text-7xl leading-none">404</p>
    <h1 class="text-2xl font-semibold">{{ uiText('This route does not exist') }}</h1>
    <p class="text-muted-foreground">
      {{ uiText('The page may have moved or the link may be incorrect.') }}
    </p>
    <RouterLink :to="{ name: 'dashboard' }" class="btn primary mt-2">{{
      uiText('Back to dashboard')
    }}</RouterLink>
  </div>
</template>
