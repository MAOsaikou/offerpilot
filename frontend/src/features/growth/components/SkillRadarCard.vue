<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed, onMounted, ref } from 'vue'
import { coverageLabel } from '../skillCoverage'
import { alignCurrent, levelFor, liveSkillReport, loadLiveSkillLevels } from '../skillEvidenceAlign'
import type { SkillGapReport } from '@/shared/api/skill-gap'
import type { SkillRadarPayload } from '@/shared/types/cards'
const props = defineProps<{ payload: SkillRadarPayload }>()
const live = ref<SkillGapReport | null | undefined>(liveSkillReport())
const aligning = computed(() => live.value === undefined)
void loadLiveSkillLevels().then((report) => {
  live.value = report
})
onMounted(() => {
  if (live.value === undefined && liveSkillReport() !== undefined) live.value = liveSkillReport()
})
function shown(name: string, current: number) {
  return alignCurrent(current, levelFor(live.value, name))
}
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
      const radius = (required ? a.required : shown(a.name, a.current)) * 18
      return `${110 + Math.cos(angle) * radius},${110 + Math.sin(angle) * radius}`
    })
    .join(' ')
}
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" :aria-label="uiText('Skill radar')">
    <h3>{{ uiText('Skill radar') }}</h3>
    <p v-if="!valid" role="alert">
      {{ uiText('Skill radar data is unavailable. Please analyze again.') }}
    </p>
    <p v-else-if="!payload.axes.length" role="status">
      {{ uiText('No skill evidence yet. Add a goal and supporting materials first.') }}
    </p>
    <template v-else>
      <p>
        {{
          uiText(
            'Material evidence and reference requirements (levels 0–5), not a certification of ability.',
          )
        }}
      </p>
      <p class="subtle">
        {{
          uiText(
            'GitHub and MCP evidence stays out of this chart until you save a connection in settings. An empty chart means there is no saved evidence, not that an integration is connected.',
          )
        }}
      </p>
      <svg
        v-if="payload.axes.length >= 3"
        viewBox="0 0 220 220"
        class="mx-auto w-full max-w-64"
        role="img"
        :aria-label="uiText('Radar of skill evidence and reference requirements')"
      >
        <circle cx="110" cy="110" r="90" fill="none" stroke="currentColor" opacity=".2" />
        <circle cx="110" cy="110" r="60" fill="none" stroke="currentColor" opacity=".1" />
        <circle cx="110" cy="110" r="30" fill="none" stroke="currentColor" opacity=".1" />
        <polygon
          class="radar-required"
          :points="points(true)"
          fill="none"
          stroke="currentColor"
          stroke-dasharray="4"
        />
        <polygon v-if="!aligning" class="radar-evidence" :points="points()" />
      </svg>
      <ul>
        <li v-for="axis in payload.axes" :key="axis.name">
          {{
            aligning
              ? uiText('{0}: Checking the current career analysis', [axis.name])
              : uiText('{0}: {1} · Evidence {2} / Requirement {3}', [
                  axis.name,
                  coverageLabel(shown(axis.name, axis.current)),
                  shown(axis.name, axis.current),
                  axis.required,
                ])
          }}
        </li>
      </ul>
    </template>
  </section>
</template>
