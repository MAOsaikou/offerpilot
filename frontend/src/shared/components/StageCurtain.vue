<script setup lang="ts">
import { computed } from 'vue'
import { usePageCurtain } from '@/shared/composables/usePageCurtain'

const { phase } = usePageCurtain()
const curtainClass = computed(() => (phase.value === 'idle' ? '' : `stage-curtain--${phase.value}`))
</script>

<template>
  <!-- 整屏一层柔雾：淡入把页面虚化，路由换完之后再淡出 -->
  <div class="stage-curtain" :class="curtainClass" aria-hidden="true"></div>
</template>
