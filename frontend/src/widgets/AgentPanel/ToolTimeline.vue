<script setup lang="ts">
import { formatNumber, formatDisplay, translate as uiText } from '@/shared/utils/language'

import { ref } from 'vue'
import { toolArguments } from '@/shared/utils/toolPresentation'
import { cn } from '@/shared/utils/cn'
import type { ToolCallView } from '@/shared/stores/agentRun'

defineProps<{
  toolCalls: ToolCallView[]
}>()
const sourceLabel = (call: ToolCallView) =>
  ['local', 'LOCAL'].includes(call.source)
    ? uiText("Local tool")
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
    <h3 class="mb-2 text-xs font-semibold text-muted-foreground">{{ uiText("Tool and specialist calls") }}</h3>

    <p v-if="toolCalls.length === 0" class="text-sm text-muted-foreground">{{ uiText("None yet") }}</p>

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
              >{{ uiText("Placeholder") }}</span
            >
            <span class="text-muted-foreground">{{ sourceLabel(call) }}</span>
            <span class="sr-only">{{ call.status }}</span>
            <span class="ml-auto shrink-0 num text-muted-foreground">
              {{
                call.durationMs !== undefined
                  ? formatNumber((call.durationMs / 1000), { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + 's'
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
              {{ call.access === 'READ' ? uiText("Read-only analysis") : uiText("Write action requiring approval") }}
              <template v-if="sourceLabel(call)"> · {{ sourceLabel(call) }}</template>
            </p>
            <p v-if="call.status !== 'RUNNING'" class="text-xs leading-relaxed">
              {{
                call.status === 'OK'
                  ? uiText("Executed. See the response and cards for results.")
                  : call.status === 'CANCELLED'
                    ? uiText("Stopped. No further execution.")
                    : uiText("Execution failed. See the response or error for details.")
              }}
            </p>
            <dl class="space-y-1 text-xs">
              <div v-for="(row, index) in argumentSummary(call.args)" :key="index">
                <dt class="text-muted-foreground">{{ row.label }}</dt>
                <dd class="whitespace-pre-wrap break-words">{{ formatDisplay(row.value) }}</dd>
              </div>
            </dl>
            <details @toggle="toggleJson(call.id, $event)">
              <summary class="cursor-pointer text-xs">{{ uiText("View full JSON (redacted)") }}</summary>
              <pre
                v-if="jsonOpen.has(call.id)"
                class="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs"
                >{{ fullJson(call) }}</pre>
              <p v-if="call.truncated" class="text-xs text-muted-foreground"> {{ uiText("Summary or JSON exceeded the size limit and was truncated.") }} </p>
            </details>
          </div>
        </details>
      </li>
    </ul>
  </section>
</template>
