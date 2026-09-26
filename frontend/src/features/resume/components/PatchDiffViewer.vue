<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { cn } from '@/shared/utils/cn'
import { asPayload } from '@/shared/utils/payload'
import type { ResumeDiffPayload, ResumePatch } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown }>()

const data = computed(() => asPayload<ResumeDiffPayload>(props.payload, 'patches'))

/** 标记为「需你确认」的补丁默认不勾选，避免示例数字被当成真实数据采纳 */
const dropped = ref(new Set<string>())

watch(
  () => data.value?.patches,
  (patches) => {
    const next = new Set<string>()
    for (const patch of patches ?? []) {
      if (patch.needsUserConfirmation) next.add(patch.id)
    }
    dropped.value = next
  },
  { immediate: true },
)

const acceptedCount = computed(() => {
  const total = data.value?.patches.length ?? 0
  return total - dropped.value.size
})

function toggle(id: string): void {
  const next = new Set(dropped.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  dropped.value = next
}

function isKept(patch: ResumePatch): boolean {
  return !dropped.value.has(patch.id)
}
</script>

<template>
  <article v-if="data" class="surface overflow-hidden rounded-[14px]">
    <header class="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border/60 px-4 py-3">
      <h3 class="text-sm font-medium">简历改写建议</h3>
      <span class="font-mono text-xs text-muted-foreground">
        +{{ data.stats.added }} ~{{ data.stats.replaced }} -{{ data.stats.removed }}
      </span>
      <span class="ml-auto font-mono text-xs text-muted-foreground">{{ data.batchId }}</span>
    </header>

    <ul class="divide-y divide-border/60">
      <li v-for="patch in data.patches" :key="patch.id" class="px-4 py-3">
        <div class="flex items-center gap-2">
          <span class="font-mono text-xs text-muted-foreground">{{ patch.path }}</span>
          <button
            type="button"
            class="ml-auto rounded-lg px-2.5 py-1 text-xs transition-colors"
            :class="
              cn(
                isKept(patch)
                  ? 'bg-primary/15 text-primary'
                  : 'text-muted-foreground hover:bg-foreground/5',
              )
            "
            @click="toggle(patch.id)"
          >
            {{ isKept(patch) ? '采纳' : '已跳过' }}
          </button>
        </div>

        <p
          v-if="patch.from"
          class="mt-2 rounded-lg bg-danger/10 px-2.5 py-1.5 text-xs leading-relaxed text-muted-foreground line-through"
        >
          {{ patch.from }}
        </p>
        <p class="mt-1.5 rounded-lg bg-success/10 px-2.5 py-1.5 text-xs leading-relaxed">
          {{ patch.value }}
        </p>

        <p class="mt-2 text-xs leading-relaxed text-muted-foreground">
          <span class="text-foreground">理由：</span>{{ patch.reason }}
        </p>
        <p v-if="patch.jdEvidence" class="mt-1 text-xs leading-relaxed text-muted-foreground">
          <span class="text-foreground">JD 依据：</span>{{ patch.jdEvidence }}
        </p>
        <p
          v-if="patch.confirmationHint"
          class="mt-1 rounded-lg bg-warning/10 px-2.5 py-1.5 text-xs leading-relaxed text-warning"
        >
          {{ patch.confirmationHint }}
        </p>
      </li>
    </ul>

    <footer
      class="flex flex-wrap items-center gap-2 border-t border-border/60 px-4 py-3 text-xs text-muted-foreground"
    >
      <span>已采纳 {{ acceptedCount }} / {{ data.patches.length }} 条</span>
      <span class="ml-auto">采纳后调用 apply-patches 生成新版本 —— 接口待接入</span>
    </footer>
  </article>
</template>
