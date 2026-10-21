<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import GrowthEvidenceChips from './GrowthEvidenceChips.vue'
import type { SkillGapPayload } from '@/shared/types/cards'
defineProps<{ payload: SkillGapPayload }>()
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
    <p v-if="!payload.skills.length" class="mt-3 text-sm" role="status">
      {{ uiText('No core skill data yet. Review the target job requirements first.') }}
    </p>
    <ul v-else class="mt-3 space-y-3">
      <li
        v-for="skill in payload.skills"
        :key="skill.name"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="break-words font-medium">{{ skill.name }}</h4>
          <span v-if="skill.learnFirst" class="rounded bg-primary/10 px-2 text-xs text-primary">{{
            uiText('Priority gaps')
          }}</span>
          <span class="text-xs text-muted-foreground">{{
            uiText('Evidence level {0} / 5 · Reference requirement {1} · Gap {2}', [
              skill.current,
              skill.required,
              skill.gap,
            ])
          }}</span>
        </div>
        <progress
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
