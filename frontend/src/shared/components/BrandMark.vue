<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 30 })
</script>
<template>
  <!-- 字标：起点空心、终点实心，中间是一段品红航线 -->
  <svg :width="size" :height="size" viewBox="0 0 30 30" fill="none" aria-hidden="true">
    <path
      d="M6 23c6 0 6-15 17-15"
      stroke="var(--primary)"
      stroke-width="2.4"
      stroke-linecap="round"
    />
    <circle
      cx="6"
      cy="23"
      r="3"
      fill="var(--rail, var(--background))"
      stroke="currentColor"
      stroke-width="2"
    />
    <circle cx="23.5" cy="8" r="3.5" fill="currentColor" />
  </svg>
</template>
