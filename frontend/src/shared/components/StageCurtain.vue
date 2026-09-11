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
    <!-- 柔雾层：整屏唯一一层 backdrop-filter，把底下的页面虚化 -->
    <span class="stage-curtain__veil" />

    <!-- 轻纱条带：只做很淡的光带，两端羽化，滑过时不留硬边 -->
    <div class="stage-curtain__track">
      <span
        v-for="index in panels"
        :key="index"
        class="stage-curtain__panel"
        :style="{ '--panel-index': index }"
      />
    </div>
  </div>
</template>
