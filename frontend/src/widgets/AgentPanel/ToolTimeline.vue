<script setup lang="ts">
import { toolArguments } from '@/shared/utils/toolPresentation'
import { cn } from '@/shared/utils/cn'
import type { ToolCallView } from '@/shared/stores/agentRun'

defineProps<{
  toolCalls: ToolCallView[]
}>()
// AG-04 source column: local tools vs. an MCP server (raw args and ids stay hidden by design).
const sourceLabel = (source: string) =>
  source === 'local' ? '本地工具' : source ? `MCP · ${source}` : ''
</script>

<template>
  <section>
    <h3 class="mb-2 text-xs font-semibold text-muted-foreground">工具与专家调用</h3>

    <p v-if="toolCalls.length === 0" class="text-sm text-muted-foreground">暂无</p>

    <ul v-else class="space-y-1.5">
      <li v-for="call in toolCalls" :key="call.id">
        <details class="group rounded-xl border border-border/60 px-3 py-2">
          <summary class="flex cursor-pointer items-center gap-2 text-xs">
            <span
              class="size-1.5 shrink-0 rounded-full"
              :class="
                cn(
                  call.status === 'RUNNING' && 'live-dot',
                  call.status === 'OK' && 'bg-success',
                  call.status === 'FAILED' && 'bg-danger',
                  call.status === 'CANCELLED' && 'bg-muted-foreground/40',
                )
              "
            />
            <span class="min-w-0 break-all font-mono" :title="call.name">{{ call.name }}</span>
            <span v-if="call.name.startsWith('placeholder.')" class="shrink-0 text-muted-foreground"
              >占位</span
            >
            <span class="sr-only">{{ call.status }}</span>
            <span class="ml-auto shrink-0 num text-muted-foreground">
              {{
                call.durationMs !== undefined
                  ? (call.durationMs / 1000).toFixed(1) + 's'
                  : call.status === 'RUNNING'
                    ? '…'
                    : '—'
              }}
            </span>
          </summary>

          <div class="mt-2 space-y-1.5 border-t border-border/60 pt-2">
            <p class="text-xs text-muted-foreground">
              {{ call.access === 'READ' ? '只读分析' : '需批准的写入操作' }}
              <template v-if="sourceLabel(call.source)"> · {{ sourceLabel(call.source) }}</template>
            </p>
            <p v-if="call.status !== 'RUNNING'" class="text-xs leading-relaxed">
              {{
                call.status === 'OK'
                  ? '已执行，结果见本轮回复与卡片。'
                  : call.status === 'CANCELLED'
                    ? '已停止，未继续执行。'
                    : '未执行成功，原因见本轮回复或错误提示。'
              }}
            </p>
            <dl class="space-y-1 text-xs">
              <div v-for="(row, index) in toolArguments(call.args)" :key="index">
                <dt class="text-muted-foreground">{{ row.label }}</dt>
                <dd class="whitespace-pre-wrap break-words">{{ row.value }}</dd>
              </div>
            </dl>
          </div>
        </details>
      </li>
    </ul>
  </section>
</template>
