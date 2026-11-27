<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed, onMounted, ref } from 'vue'
import { coverageLabel } from '../skillCoverage'
import { alignCurrent, levelFor, liveSkillReport, loadLiveSkillLevels } from '../skillEvidenceAlign'
import GrowthEvidenceChips from './GrowthEvidenceChips.vue'
import type { SkillGapReport } from '@/shared/api/skill-gap'
import type { SkillGapPayload } from '@/shared/types/cards'
const props = defineProps<{ payload: SkillGapPayload }>()
const live = ref<SkillGapReport | null | undefined>(liveSkillReport())
const aligning = computed(() => live.value === undefined)
void loadLiveSkillLevels().then((report) => {
  live.value = report
})
onMounted(() => {
  if (live.value === undefined && liveSkillReport() !== undefined) live.value = liveSkillReport()
})
const skills = computed(() =>
  props.payload.skills.map((skill) => {
    const current = alignCurrent(skill.current, levelFor(live.value, skill.name))
    return { ...skill, current, gap: Math.max(0, skill.required - current) }
  }),
)
</script>

<template>
  <section
    class="min-w-0 rounded-xl border border-border bg-card p-4"
    :aria-label="uiText('Skill gaps')"
  >
    <h3 class="break-words font-semibold">
      {{ uiText('Skill gaps · {0}', [payload.target.title]) }}
    </h3>
    <p class="mt-2 break-words text-sm text-muted-foreground">{{ payload.summary }}</p>
    <p v-if="!skills.length" class="mt-3 text-sm" role="status">
      {{ uiText('No core skill data yet. Review the target job requirements first.') }}
    </p>
    <ul v-else class="mt-3 space-y-3">
      <li
        v-for="skill in skills"
        :key="skill.name"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="break-words font-medium">{{ skill.name }}</h4>
          <span v-if="skill.learnFirst" class="rounded bg-primary/10 px-2 text-xs text-primary">{{
            uiText('Priority gaps')
          }}</span>
          <span v-if="aligning" class="text-xs text-muted-foreground">{{
            uiText('{0}: Checking the current career analysis', [skill.name])
          }}</span>
          <span v-else class="text-xs text-muted-foreground">{{
            uiText('{0} · Evidence level {1} / 5 · Reference requirement {2} · Gap {3}', [
              coverageLabel(skill.current),
              skill.current,
              skill.required,
              skill.gap,
            ])
          }}</span>
        </div>
        <progress
          v-if="!aligning"
          class="mt-2 w-full accent-primary"
          :value="skill.current"
          :max="5"
          :aria-label="uiText('{0} evidence level', [skill.name])"
        />
        <GrowthEvidenceChips
          v-if="skill.evidenceSources?.length"
          :sources="skill.evidenceSources"
        />
        <ul v-else-if="skill.evidence.length" class="mt-1 space-y-1 text-sm text-muted-foreground">
          <li v-for="evidence in skill.evidence" :key="evidence" class="break-words">
            {{ evidence }}
          </li>
        </ul>
        <p v-else class="mt-1 text-sm text-muted-foreground">
          {{ uiText('No saved skill evidence yet. This does not mean you lack the ability.') }}
        </p>
      </li>
    </ul>
    <ul
      v-if="payload.citations.length"
      class="mt-3 text-xs text-muted-foreground"
      :aria-label="uiText('References')"
    >
      <li v-for="citation in payload.citations" :key="citation.docId">{{ citation.title }}</li>
    </ul>
  </section>
</template>
