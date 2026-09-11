<script setup lang="ts">
import { computed } from 'vue'
import { usePageCurtain } from '@/shared/composables/usePageCurtain'

const PANEL_COUNT = 8

const { phase } = usePageCurtain()
const panels = Array.from({ length: PANEL_COUNT }, (_, index) => index)

const curtainClass = computed(() => (phase.value === 'idle' ? '' : `stage-curtain--${phase.value}`))
</script>

<template>
  <div class="stage-curtain" :class="curtainClass" aria-hidden="true">
    <span
      v-for="index in panels"
      :key="index"
      class="stage-curtain__panel"
      :style="{ '--panel-index': index }"
    />
  </div>
</template>
