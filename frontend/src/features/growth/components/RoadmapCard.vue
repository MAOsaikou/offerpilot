<script setup lang="ts">
import { formatDisplay, formatDate, translate as uiText } from '@/shared/utils/language'

import { computed, ref, watch } from 'vue'
import GrowthEvidenceChips from './GrowthEvidenceChips.vue'
import type { RoadmapPayload } from '@/shared/types/cards'
const props = defineProps<{
  payload: RoadmapPayload
  interactive?: boolean
  disabled?: boolean
  startDate?: string
}>()
const emit = defineEmits<{ adoptGrowth: [taskTitles: string[]] }>()
const selected = ref<string[]>([])
watch(
  () => props.payload,
  () => {
    selected.value = []
  },
  { immediate: true },
)
const hasItems = computed(
  () => valid.value && props.payload.weeks.some((week) => week.items.length > 0),
)
function weekRange(index: number) {
  if (!props.startDate) return ''
  const start = new Date(props.startDate + 'T00:00:00Z')
  start.setUTCDate(start.getUTCDate() + (index - 1) * 7)
  const end = new Date(start)
  end.setUTCDate(end.getUTCDate() + 6)
  return `${formatDate(start, { timeZone: 'UTC' })} — ${formatDate(end, { timeZone: 'UTC' })}`
}
const valid = computed(
  () =>
    Array.isArray(props.payload?.weeks) &&
    props.payload.weeks.every((week) => Array.isArray(week.items)) &&
    Array.isArray(props.payload?.milestones),
)
</script>

<template>
  <section
    v-if="valid"
    class="min-w-0 rounded-xl border border-border bg-card p-4"
    :aria-label="uiText('Learning roadmap')"
  >
    <h3 class="font-semibold">{{ uiText("Four-week learning roadmap") }}</h3>
    <p class="mt-2 text-sm text-muted-foreground"> {{ uiText("Adjust your time commitment at your own pace. This roadmap is a suggestion.") }}<template v-if="interactive"
        >{{ uiText("Completion is recorded only after you select completed tasks and approve syncing. Existing completed items are retained.") }}</template
      >
    </p>
    <p v-if="!hasItems" class="mt-3 text-sm" role="status">{{ uiText("No learning tasks yet. Add a career goal and retry.") }}</p>
    <ol class="mt-3 space-y-4 border-l-2 border-primary pl-4" :aria-label="uiText('Weekly timeline')">
      <li v-for="week in payload.weeks" :key="week.index" class="min-w-0">
        <h4 class="break-words font-medium">{{ uiText("Week {0} · {1}", [week.index, week.theme]) }}</h4>
        <p v-if="startDate" class="mt-1 text-xs text-muted-foreground">
          {{ formatDisplay(weekRange(week.index)) }}
        </p>
        <p v-if="!week.items.length" class="mt-2 text-sm text-muted-foreground"> {{ uiText("Consolidate what you have practiced this week and check milestone progress.") }} </p>
        <GrowthEvidenceChips v-if="week.evidenceSources?.length" :sources="week.evidenceSources" />
        <ul class="mt-2 space-y-2">
          <li
            v-for="item in week.items"
            :key="item.id"
            class="min-w-0 rounded-lg border border-border p-3"
          >
            <div class="flex items-start gap-2">
              <input
                v-if="interactive && !item.done"
                v-model="selected"
                type="checkbox"
                :value="item.title"
                :disabled="disabled"
                :aria-label="uiText('Confirm completion: {0}', [item.title])"
                class="mt-1 accent-primary"
              />
              <div class="min-w-0 flex-1">
                <p class="break-words text-sm font-medium">
                  {{ item.title }}
                  <span v-if="item.done" class="text-xs text-muted-foreground">{{ uiText("(Completed)") }}</span>
                </p>
                <p class="mt-1 text-xs text-muted-foreground"> {{ uiText("Estimated: {0} hours", [item.hours]) }}<span v-if="item.dueAt"> {{ uiText("· Due {0}", [formatDate(item.dueAt)]) }}</span>
                </p>
                <ul class="mt-1 text-xs text-muted-foreground">
                  <li v-for="resource in item.resources" :key="resource" class="break-words">
                    {{ resource }}
                  </li>
                </ul>
              </div>
            </div>
          </li>
        </ul>
      </li>
    </ol>
    <ul v-if="payload.milestones.length" class="mt-3 space-y-1 text-sm" :aria-label="uiText('Milestone')">
      <li v-for="milestone in payload.milestones" :key="milestone.title" class="break-words">
        {{ milestone.at }} · {{ milestone.title }}
      </li>
    </ul>
    <button
      v-if="interactive && hasItems"
      type="button"
      class="mt-4 min-h-11 rounded border border-primary px-3 py-2 text-sm text-primary disabled:opacity-50"
      :disabled="disabled || !selected.length"
      @click="emit('adoptGrowth', [...new Set(selected)])"
    > {{ uiText("Sync to growth tasks") }} </button>
  </section>
  <p v-else role="alert">{{ uiText("Roadmap data is unavailable. Please request it again.") }}</p>
</template>
