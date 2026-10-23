<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  Chart,
  RadarController,
  RadialLinearScale,
  PointElement,
  LineElement,
  Filler,
} from 'chart.js'
import type { InterviewReportPayload } from '@/shared/types/cards'
import { formatDisplay, language, translate as uiText } from '@/shared/utils/language'

Chart.register(RadarController, RadialLinearScale, PointElement, LineElement, Filler)

const props = defineProps<{ dimensions?: InterviewReportPayload['dimensions'] | null }>()
const canvas = ref<HTMLCanvasElement>()
let chart: Chart<'radar', Array<number | null>> | undefined
const axes = computed(() =>
  (
    [
      { key: 'professional', label: uiText('Domain knowledge') },
      { key: 'expression', label: uiText('Communication') },
      { key: 'logic', label: uiText('Logic') },
      { key: 'jobFit', label: uiText('Job matching') },
    ] as const
  ).map(({ key, label }) => {
    const value = props.dimensions?.[key]
    const valid = typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100
    return { key, label, score: valid ? value : null, invalid: value !== null && !valid }
  }),
)
const hasScores = computed(() => axes.value.some((axis) => axis.score !== null))
const invalid = computed(() => axes.value.some((axis) => axis.invalid))

watch(
  [axes, language, canvas],
  () => {
    chart?.destroy()
    chart = undefined
    if (!canvas.value || !hasScores.value) return
    chart = new Chart(canvas.value, {
      type: 'radar',
      data: {
        labels: axes.value.map((axis) => axis.label),
        datasets: [
          {
            label: uiText('Interview score radar'),
            data: axes.value.map((axis) => axis.score),
            borderColor: '#2563eb',
            backgroundColor: '#2563eb18',
            spanGaps: false,
          },
        ],
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
  { flush: 'post' },
)
onBeforeUnmount(() => chart?.destroy())
</script>

<template>
  <section class="min-w-0 space-y-3" :aria-label="uiText('Interview score radar')">
    <h4 class="font-medium">{{ uiText('Interview score radar') }}</h4>
    <p v-if="invalid" role="alert">
      {{ uiText('Some dimension scores are unavailable. Only valid scores are shown.') }}
    </p>
    <p v-else-if="!hasScores" role="status">
      {{ uiText('No scored dimensions yet. The radar will appear when scores are available.') }}
    </p>
    <p v-if="hasScores" class="text-xs text-muted-foreground">
      {{ uiText('Scores range from 0 to 100. Unscored dimensions remain gaps in the radar.') }}
    </p>
    <div v-if="hasScores" class="radar-container">
      <canvas
        ref="canvas"
        role="img"
        :aria-label="
          uiText(
            'Interview score radar with four dimensions. Full values appear in the table below.',
          )
        "
      />
    </div>
    <table class="w-full border-collapse text-left">
      <caption class="pb-2 text-left font-medium">
        {{
          uiText('Score radar values')
        }}
      </caption>
      <thead>
        <tr>
          <th scope="col" class="border-b border-border p-2">{{ uiText('Dimension') }}</th>
          <th scope="col" class="border-b border-border p-2">{{ uiText('Score (0–100)') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="axis in axes" :key="axis.key">
          <th scope="row" class="border-b border-border p-2 font-normal">{{ axis.label }}</th>
          <td class="border-b border-border p-2">
            {{ axis.score === null ? uiText('Not scored') : formatDisplay(axis.score) }}
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.radar-container {
  position: relative;
  width: 100%;
  height: 300px;
}
</style>
