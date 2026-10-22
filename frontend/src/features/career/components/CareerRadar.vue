<script setup lang="ts">
import { computed } from 'vue'

import { language, translate as uiText } from '@/shared/utils/language'

import { onBeforeUnmount, ref, watch, nextTick } from 'vue'
import {
  Chart,
  RadarController,
  RadialLinearScale,
  PointElement,
  LineElement,
  Filler,
  Legend,
} from 'chart.js'
import type { CareerComparison, RiasecLetter } from '@/shared/api/career'
Chart.register(RadarController, RadialLinearScale, PointElement, LineElement, Filler, Legend)
const props = defineProps<{ comparison: CareerComparison }>()
const canvas = ref<HTMLCanvasElement>()
let chart: Chart<'radar'> | undefined
const labels = computed<Record<RiasecLetter, string>>(() => ({
  R: uiText('Realistic'),
  I: uiText('Investigative'),
  A: uiText('Artistic'),
  S: uiText('Social'),
  E: uiText('Enterprising'),
  C: uiText('Conventional'),
}))
const colors = ['#2563eb', '#b45309', '#7c3aed']
watch(
  () => [props.comparison, labels.value] as const,
  async ([comparison]) => {
    await nextTick()
    chart?.destroy()
    if (!canvas.value) return
    chart = new Chart(canvas.value, {
      type: 'radar',
      data: {
        labels: comparison.axes.map((axis) => labels.value[axis]),
        datasets: comparison.occupations.map((occupation, index) => ({
          label: occupation.name,
          data: comparison.axes.map((axis) => occupation.riasec[axis] * 100),
          borderColor: colors[index],
          backgroundColor: colors[index] + '18',
          borderDash: index === 1 ? [6, 3] : index === 2 ? [2, 3] : [],
          pointStyle: index === 1 ? 'rect' : index === 2 ? 'triangle' : 'circle',
        })),
      },
      options: {
        locale: language.value,
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: { r: { min: 0, max: 100, ticks: { stepSize: 20 } } },
      },
    })
  },
  { immediate: true, deep: true },
)
onBeforeUnmount(() => chart?.destroy())
</script>
<template>
  <div class="radar-container">
    <canvas
      ref="canvas"
      role="img"
      :aria-label="
        uiText(
          'Career interest radar with six dimensions. Full values appear in the comparison table below.',
        )
      "
    />
  </div>
</template>
<style scoped>
.radar-container {
  position: relative;
  width: 100%;
  height: 360px;
}
</style>
