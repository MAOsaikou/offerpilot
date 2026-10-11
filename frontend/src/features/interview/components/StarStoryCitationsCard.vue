<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { StarStoryCitationsPayload } from '@/shared/types/cards'
defineProps<{ payload: StarStoryCitationsPayload }>()
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" aria-label="已确认的 STAR 故事引用">
    <h3>已确认的 STAR 故事 · {{ payload.count }} 条</h3>
    <p class="helper-note">仅供回忆与练习参考，不代表本题答案或评分依据。</p>
    <article v-for="item in payload.items" :key="item.id" class="rounded-lg border p-3">
      <h4>{{ item.title }}</h4>
      <p class="subtle">{{ item.role }} · STAR {{ item.starCount }} / 4</p>
      <p class="whitespace-pre-wrap">{{ item.excerpt }}</p>
    </article>
    <RouterLink to="/interviews" class="btn">查看 STAR 故事库</RouterLink>
  </section>
</template>
