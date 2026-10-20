<script setup lang="ts">
import { computed } from 'vue'
import type { SkillRadarPayload } from '@/shared/types/cards'
const props = defineProps<{ payload: SkillRadarPayload }>()
const valid = computed(
  () =>
    Array.isArray(props.payload?.axes) &&
    props.payload.axes.every(
      (a) =>
        typeof a.name === 'string' &&
        Number.isFinite(a.current) &&
        Number.isFinite(a.required) &&
        a.current >= 0 &&
        a.current <= 5 &&
        a.required >= 0 &&
        a.required <= 5,
    ),
)
function points(required = false) {
  return props.payload.axes
    .map((a, i, axes) => {
      const angle = (i * 2 * Math.PI) / axes.length - Math.PI / 2
      const radius = (required ? a.required : a.current) * 18
      return `${110 + Math.cos(angle) * radius},${110 + Math.sin(angle) * radius}`
    })
    .join(' ')
}
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" aria-label="技能雷达">
    <h3>技能雷达</h3>
    <p v-if="!valid" role="alert">技能雷达数据不可用，请重新分析。</p>
    <p v-else-if="!payload.axes.length" role="status">暂无技能证据，请先补充目标与材料。</p>
    <template v-else>
      <p>当前材料证据与参考要求（0–5级），不代表能力认证。</p>
      <svg
        v-if="payload.axes.length >= 3"
        viewBox="0 0 220 220"
        class="mx-auto w-full max-w-64"
        role="img"
        aria-label="技能证据与参考要求雷达图"
      >
        <circle cx="110" cy="110" r="90" fill="none" stroke="currentColor" opacity=".2" />
        <polygon :points="points(true)" fill="none" stroke="currentColor" stroke-dasharray="4" />
        <polygon :points="points()" fill="currentColor" fill-opacity=".15" stroke="currentColor" />
      </svg>
      <ul>
        <li v-for="axis in payload.axes" :key="axis.name">
          {{ axis.name }}：证据 {{ axis.current }} / 要求 {{ axis.required }}
        </li>
      </ul>
    </template>
  </section>
</template>
