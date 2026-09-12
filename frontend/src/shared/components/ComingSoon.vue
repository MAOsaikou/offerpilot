<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  /** 对应的设计规范 / 接口文档位置，便于对照实现 */
  spec?: string
}>()
</script>

<template>
  <section class="mx-auto max-w-3xl px-2 py-8">
    <header class="rise-in space-y-1.5">
      <h1 class="text-2xl font-semibold tracking-tight">{{ title }}</h1>
      <p v-if="description" class="text-sm leading-relaxed text-muted-foreground">
        {{ description }}
      </p>
    </header>

    <div
      class="glass-panel rise-in mt-6 rounded-2xl border-dashed p-10 text-center"
      style="animation-delay: 90ms"
    >
      <span class="pulse-dot mx-auto mb-4 block size-2 rounded-full bg-primary" />
      <p class="text-sm text-muted-foreground">该页面尚未实现</p>
      <p v-if="spec" class="mt-1.5 font-mono text-xs text-muted-foreground">{{ spec }}</p>
    </div>
  </section>
</template>
