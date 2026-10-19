<script setup lang="ts">
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
const labels: Record<RiasecLetter, string> = {
  R: '实际型',
  I: '研究型',
  A: '艺术型',
  S: '社会型',
  E: '企业型',
  C: '常规型',
}
const colors = ['#2563eb', '#b45309', '#7c3aed']
watch(
  () => props.comparison,
  async (comparison) => {
    await nextTick()
    chart?.destroy()
    if (!canvas.value) return
    chart = new Chart(canvas.value, {
      type: 'radar',
      data: {
        labels: comparison.axes.map((axis) => labels[axis]),
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
    <canvas ref="canvas" role="img" aria-label="职业六维兴趣雷达图，完整数值见下方对比表" />
  </div>
</template>
<style scoped>
.radar-container {
  position: relative;
  width: 100%;
  height: 360px;
}
</style>
