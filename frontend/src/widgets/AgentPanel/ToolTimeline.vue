<script setup lang="ts">
import { cn } from '@/shared/utils/cn'
import type { ToolCallView } from '@/shared/stores/agentRun'

defineProps<{
  toolCalls: ToolCallView[]
}>()
</script>

<template>
  <section>
    <h3 class="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
      工具调用
    </h3>

    <p v-if="toolCalls.length === 0" class="text-sm text-muted-foreground">暂无</p>

    <ul v-else class="space-y-1.5">
      <li v-for="call in toolCalls" :key="call.id">
        <details class="group rounded-xl border border-border/60 px-3 py-2">
          <summary class="flex cursor-pointer items-center gap-2 text-xs">
            <span
              class="size-1.5 shrink-0 rounded-full"
              :class="
                cn(
                  call.status === 'RUNNING' && 'bg-primary pulse-dot',
                  call.status === 'OK' && 'bg-success',
                  call.status === 'FAILED' && 'bg-danger',
                )
              "
            />
            <span class="truncate font-mono">{{ call.name }}</span>
            <span class="ml-auto shrink-0 font-mono text-muted-foreground">
              {{ call.durationMs ? (call.durationMs / 1000).toFixed(1) + 's' : '…' }}
            </span>
          </summary>

          <div class="mt-2 space-y-1.5 border-t border-border/60 pt-2">
            <p class="font-mono text-xs text-muted-foreground">
              source={{ call.source }} · access={{ call.access }}
            </p>
            <p v-if="call.resultPreview" class="text-xs leading-relaxed">
              {{ call.resultPreview }}
            </p>
            <pre
              class="max-h-40 overflow-auto rounded-lg bg-foreground/5 p-2 font-mono text-xs leading-relaxed"
              >{{ JSON.stringify(call.args, null, 2) }}</pre
            >
          </div>
        </details>
      </li>
    </ul>
  </section>
</template>
