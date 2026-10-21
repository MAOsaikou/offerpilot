<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

defineProps<{
  title: string
  description?: string
  /** 对应的设计规范 / 接口文档位置，便于对照实现 */
  spec?: string
}>()
</script>

<template>
  <section class="mx-auto max-w-3xl px-2 py-8">
    <header class="space-y-1.5">
      <h1 class="text-2xl font-semibold">{{ title }}</h1>
      <p v-if="description" class="text-sm leading-relaxed text-muted-foreground">
        {{ description }}
      </p>
    </header>

    <div class="mt-6 rounded-[14px] border border-dashed border-input p-10 text-center">
      <p class="text-sm text-muted-foreground">{{ uiText('This page is not available yet') }}</p>
      <p v-if="spec" class="mt-1.5 text-xs text-muted-foreground">{{ spec }}</p>
    </div>
  </section>
</template>
