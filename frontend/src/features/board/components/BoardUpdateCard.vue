<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { asPayload } from '@/shared/utils/payload'
import type { ApplicationStatus, BoardUpdatePayload } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown }>()

const data = computed(() => asPayload<BoardUpdatePayload>(props.payload, 'application'))

const statusLabel: Record<ApplicationStatus, string> = {
  TODO: '待投递',
  APPLIED: '已投递',
  WRITTEN_TEST: '笔试',
  INTERVIEW: '面试',
  OFFER: 'Offer',
  CLOSED: '已结束',
}
</script>

<template>
  <article v-if="data" class="surface flex flex-wrap items-center gap-3 rounded-[14px] px-4 py-3">
    <span
      class="flex size-9 shrink-0 items-center justify-center rounded-[8px] bg-muted text-sm text-foreground"
    >
      {{ data.change === 'CREATED' ? '+' : '↻' }}
    </span>

    <div class="min-w-0 flex-1">
      <p class="text-sm">
        {{ data.change === 'CREATED' ? '已加入求职看板' : '看板已更新' }}
      </p>
      <p class="mt-0.5 truncate text-xs text-muted-foreground">
        {{ data.application.company }} · {{ data.application.title }}
      </p>
      <p v-if="data.application.nextAction" class="mt-1 text-xs text-muted-foreground break-words">
        下一步：{{ data.application.nextAction
        }}<span v-if="data.application.nextActionAt"> · {{ data.application.nextActionAt }}</span>
      </p>
    </div>

    <span class="shrink-0 rounded-full bg-foreground/8 px-2.5 py-1 text-xs">
      {{ statusLabel[data.application.status] }}
    </span>

    <RouterLink
      :to="{ name: 'board', query: { application: data.application.id } }"
      class="shrink-0 text-xs text-primary transition-opacity hover:opacity-80"
    >
      去看板
    </RouterLink>
  </article>
</template>
