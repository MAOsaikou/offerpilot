<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import GrowthEvidenceChips from './GrowthEvidenceChips.vue'
import type { RoadmapPayload } from '@/shared/types/cards'
const props = defineProps<{ payload: RoadmapPayload; interactive?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ adoptGrowth: [taskTitles: string[]] }>()
const selected = ref<string[]>([])
watch(
  () => props.payload,
  () => {
    selected.value = []
  },
  { immediate: true },
)
const hasItems = computed(() => props.payload.weeks.some((week) => week.items.length > 0))
</script>

<template>
  <section class="min-w-0 rounded-xl border border-border bg-card p-4" aria-label="学习路线图">
    <h3 class="font-semibold">四周学习路线图</h3>
    <p class="mt-2 text-sm text-muted-foreground">
      按自己的节奏调整投入时间。路线图仅为建议；勾选已完成任务并批准同步后才会记录完成状态，保留已有完成项。
    </p>
    <p v-if="!hasItems" class="mt-3 text-sm" role="status">暂无学习任务，请补充职业目标后重试。</p>
    <ol class="mt-3 space-y-4">
      <li v-for="week in payload.weeks" :key="week.index" class="min-w-0">
        <h4 class="break-words font-medium">第 {{ week.index }} 周 · {{ week.theme }}</h4>
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
                :aria-label="`确认已完成：${item.title}`"
                class="mt-1 accent-primary"
              />
              <div class="min-w-0 flex-1">
                <p class="break-words text-sm font-medium">
                  {{ item.title }}
                  <span v-if="item.done" class="text-xs text-muted-foreground">（已完成）</span>
                </p>
                <p class="mt-1 text-xs text-muted-foreground">
                  预计 {{ item.hours }} 小时<span v-if="item.dueAt"> · 截止 {{ item.dueAt }}</span>
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
    <ul v-if="payload.milestones.length" class="mt-3 space-y-1 text-sm" aria-label="里程碑">
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
    >
      同步到成长任务
    </button>
  </section>
</template>
