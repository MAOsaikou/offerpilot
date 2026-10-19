<script setup lang="ts">
import { ref } from 'vue'
import { toolArguments } from '@/shared/utils/toolPresentation'
import { cn } from '@/shared/utils/cn'
import type { ToolCallView } from '@/shared/stores/agentRun'

defineProps<{
  toolCalls: ToolCallView[]
}>()
const sourceLabel = (call: ToolCallView) =>
  ['local', 'LOCAL'].includes(call.source)
    ? '本地工具'
    : `MCP · ${call.serverName ?? call.source.replace(/^mcp:/, '')}`
const jsonOpen = ref(new Set<string>())
function toggleJson(id: string, event: Event) {
  if ((event.target as HTMLDetailsElement).open) jsonOpen.value.add(id)
  else jsonOpen.value.delete(id)
}
function resultSummary(call: ToolCallView) {
  try {
    return toolArguments(JSON.parse(call.resultPreview ?? ''))
      .map((row) => `${row.label}：${row.value}`)
      .join('；')
  } catch {
    const preview = call.resultPreview ?? ''
    const jsonStart = preview.indexOf('{')
    return jsonStart > 0 ? preview.slice(0, jsonStart).replace(/[：:\s]+$/, '') : preview
  }
}
const argumentSummary = (args: unknown) =>
  toolArguments(args).map((row) => ({
    ...row,
    value: row.value.length > 240 ? row.value.slice(0, 240) + '…' : row.value,
  }))
const fullJson = (call: ToolCallView) =>
  JSON.stringify(
    { args: call.args, result: call.result ?? { summary: call.resultPreview } },
    null,
    2,
  )
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
            <span class="text-muted-foreground">{{ sourceLabel(call) }}</span>
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

          <p v-if="call.resultPreview" class="mt-1 break-words text-xs">
            {{ resultSummary(call) }}
          </p>
          <div class="mt-2 space-y-1.5 border-t border-border/60 pt-2">
            <p class="text-xs text-muted-foreground">
              {{ call.access === 'READ' ? '只读分析' : '需批准的写入操作' }}
              <template v-if="sourceLabel(call)"> · {{ sourceLabel(call) }}</template>
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
              <div v-for="(row, index) in argumentSummary(call.args)" :key="index">
                <dt class="text-muted-foreground">{{ row.label }}</dt>
                <dd class="whitespace-pre-wrap break-words">{{ row.value }}</dd>
              </div>
            </dl>
            <details @toggle="toggleJson(call.id, $event)">
              <summary class="cursor-pointer text-xs">查看完整 JSON（已脱敏）</summary>
              <pre
                v-if="jsonOpen.has(call.id)"
                class="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs"
                >{{ fullJson(call) }}</pre>
              <p v-if="call.truncated" class="text-xs text-muted-foreground">
                摘要或 JSON 达到大小限制，已截断。
              </p>
            </details>
          </div>
        </details>
      </li>
    </ul>
  </section>
</template>
