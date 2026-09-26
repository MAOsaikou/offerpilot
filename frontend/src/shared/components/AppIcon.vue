<script setup lang="ts">
withDefaults(defineProps<{ name?: string; size?: number }>(), { name: 'spark', size: 20 })
const paths: Record<string, string> = {
  dashboard: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  chat: 'M21 11a8 8 0 0 1-8 8H7l-4 3V5a2 2 0 0 1 2-2h8a8 8 0 0 1 8 8Z M7 8h9 M7 12h6',
  discover: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M16 8l-3 5-5 3 3-5Z',
  resumes: 'M14 2H5v20h14V7Z M14 2v6h5 M8 12h8 M8 16h6',
  jobs: 'M3 7h18v14H3z M8 7V3h8v4 M3 12c6 3 12 3 18 0 M12 11v4',
  board: 'M3 3h18v18H3z M9 3v18 M15 3v18 M5 7h2 M11 11h2 M17 7h2',
  interviews:
    'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3 M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8',
  career: 'M3 21h18 M5 17v-4 M12 17V8 M19 17V3 M3 9l6-4 5 1 7-5',
  kb: 'M12 5C8 2 4 3 2 4v16c4-2 7-1 10 1 3-2 6-3 10-1V4c-4-2-7-1-10 1Zm0 0v16',
  settings:
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
  bell: 'M5 17h14l-2-3V9a5 5 0 0 0-10 0v5Z M10 21h4',
  arrow: 'M4 12h16 M14 6l6 6-6 6',
  plus: 'M12 4v16 M4 12h16',
  search: 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6',
  check: 'M4 12l5 5L20 6',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  spark: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3Z',
  send: 'M12 19V5 M6 11l6-6 6 6',
  stop: 'M7 7h10v10H7z',
  history: 'M3 12a9 9 0 1 0 3-6.7 M3 4v4h4 M12 7v5l3 2',
  panel: 'M3 4h18v16H3z M15 4v16',
  more: 'M5 12h.01 M12 12h.01 M19 12h.01',
}
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.spark" />
  </svg>
</template>
