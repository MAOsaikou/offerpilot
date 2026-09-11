<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  /** 对应的设计规范 / 接口文档位置，便于对照实现 */
  spec?: string
}>()
</script>

<template>
  <section class="mx-auto max-w-3xl px-6 py-10">
    <h1 class="text-2xl font-semibold text-foreground">{{ title }}</h1>
    <p v-if="description" class="mt-2 text-sm text-muted-foreground">{{ description }}</p>

    <div class="mt-6 rounded-xl border border-dashed border-border bg-card p-8 text-center">
      <p class="text-sm text-muted-foreground">该页面尚未实现</p>
      <p v-if="spec" class="mt-1 font-mono text-xs text-muted-foreground">{{ spec }}</p>
    </div>
  </section>
</template>
