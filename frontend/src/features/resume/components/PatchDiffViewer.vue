<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { asPayload } from '@/shared/utils/payload'
import type { ResumeDiffPayload, ResumePatch } from '@/shared/types/cards'
import type { PatchDecision } from '@/shared/types/optimization'

const props = defineProps<{
  payload: unknown
  interactive?: boolean
  disabled?: boolean
  appliedVersionId?: string | null
}>()
const emit = defineEmits<{ apply: [decision: PatchDecision] }>()

const data = computed(() => asPayload<ResumeDiffPayload>(props.payload, 'patches'))
const reference = computed(() => {
  if (!data.value || !props.payload || typeof props.payload !== 'object') return null
  const payload = props.payload as Record<string, unknown>
  const keys = ['resumeId', 'resumeVersionId', 'reportId'] as const
  if (
    !keys.every(
      (key) =>
        typeof payload[key] === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(payload[key] as string),
    )
  )
    return null
  return {
    path: '/resumes/' + payload.resumeId,
    query: {
      tab: 'optimize',
      resumeVersionId: payload.resumeVersionId as string,
      optimizationReportId: payload.reportId as string,
    },
  }
})

const selected = ref(new Set<string>())
const confirmed = ref(new Set<string>())
const edited = ref<Record<string, string>>({})

watch(
  () => data.value?.patches,
  () => {
    selected.value = new Set()
    confirmed.value = new Set()
    edited.value = {}
  },
  { immediate: true },
)

const acceptedCount = computed(() => selected.value.size)
const locked = computed(() => props.disabled || !!props.appliedVersionId || !props.interactive)
const pendingConfirmation = computed(() =>
  data.value?.patches.some(
    (p) => selected.value.has(p.id) && p.needsUserConfirmation && !confirmed.value.has(p.id),
  ),
)
const noChange = computed(() =>
  data.value?.patches.some(
    (p) => selected.value.has(p.id) && (edited.value[p.id] ?? p.value) === p.from,
  ),
)

function toggle(id: string): void {
  if (locked.value) return
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
  if (!next.has(id)) {
    const confirmations = new Set(confirmed.value)
    confirmations.delete(id)
    confirmed.value = confirmations
  }
}

function isKept(patch: ResumePatch): boolean {
  return selected.value.has(patch.id)
}
function fieldLabel(path: string): string {
  if (path === '/summary') return '个人概述'
  const match = /^\/(experience|projects|skills)\/(\d+)\/(bullets|items)\/(\d+)$/.exec(path)
  if (!match) return '简历内容'
  const section = { experience: '实习与工作', projects: '项目经历', skills: '技能' }[
    match[1] as 'experience' | 'projects' | 'skills'
  ]
  return `${section} ${Number(match[2]) + 1} · 第 ${Number(match[4]) + 1} 条`
}
function confirm(id: string, checked: boolean) {
  const next = new Set(confirmed.value)
  if (checked) next.add(id)
  else next.delete(id)
  confirmed.value = next
}
function edit(id: string, value: string) {
  edited.value[id] = value
  confirm(id, false)
}
function apply() {
  if (
    locked.value ||
    !data.value?.batchId ||
    !acceptedCount.value ||
    pendingConfirmation.value ||
    noChange.value
  )
    return
  const decision: PatchDecision = {
    batchId: data.value.batchId,
    accepted: [],
    edited: {},
    confirmed: [],
  }
  for (const patch of data.value.patches) {
    if (!selected.value.has(patch.id)) continue
    const value = edited.value[patch.id] ?? patch.value
    if (value === patch.value) decision.accepted.push(patch.id)
    else decision.edited[patch.id] = { value }
    if (confirmed.value.has(patch.id)) decision.confirmed.push(patch.id)
  }
  emit('apply', decision)
}
</script>

<template>
  <article v-if="data" class="surface overflow-hidden rounded-[14px]">
    <header class="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border/60 px-4 py-3">
      <h3 class="text-sm font-medium">简历改写建议</h3>
      <span class="text-xs text-muted-foreground">共 {{ data.patches.length }} 条建议</span>
      <span class="ml-auto text-xs text-muted-foreground">核对真实经历后逐条采纳</span>
    </header>

    <p v-if="!data.patches.length" class="px-4 py-3 text-sm" role="status">
      没有需要采纳的改写建议，未创建简历新版本。
    </p>

    <ul class="divide-y divide-border/60">
      <li v-for="(patch, index) in data.patches" :key="patch.id" class="min-w-0 px-4 py-3">
        <div class="flex items-center gap-2">
          <span class="text-xs text-muted-foreground">{{ fieldLabel(patch.path) }}</span>
          <button
            v-if="interactive"
            type="button"
            class="btn ml-auto shrink-0"
            :aria-label="'采纳建议 ' + (index + 1)"
            :aria-pressed="isKept(patch)"
            :disabled="locked || patch.op !== 'replace'"
            @click="toggle(patch.id)"
          >
            {{ isKept(patch) ? '已选择' : '选择采纳' }}
          </button>
        </div>

        <p
          v-if="patch.from !== undefined"
          class="mt-2 rounded-lg bg-danger/10 px-2.5 py-1.5 text-xs leading-relaxed text-muted-foreground line-through"
        >
          {{ patch.from }}
        </p>
        <p class="mt-1.5 rounded-lg bg-success/10 px-2.5 py-1.5 text-xs leading-relaxed">
          {{ patch.value }}
        </p>
        <label v-if="interactive && isKept(patch)" class="field mt-2 text-xs">
          采纳内容（可编辑）
          <textarea
            :aria-label="'编辑建议 ' + (index + 1)"
            :value="edited[patch.id] ?? patch.value"
            :disabled="locked"
            maxlength="5000"
            rows="3"
            @input="edit(patch.id, ($event.target as HTMLTextAreaElement).value)"
          />
        </label>

        <p class="mt-2 text-xs leading-relaxed text-muted-foreground">
          <span class="text-foreground">理由：</span>{{ patch.reason }}
        </p>
        <label
          v-if="interactive && patch.needsUserConfirmation"
          class="mt-2 flex items-start gap-2 text-xs"
        >
          <input
            type="checkbox"
            :checked="confirmed.has(patch.id)"
            :disabled="locked || !isKept(patch)"
            @change="confirm(patch.id, ($event.target as HTMLInputElement).checked)"
          />
          <span>已核对建议 {{ index + 1 }} 的事实与贡献范围</span>
        </label>
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
      <span v-if="appliedVersionId" role="status">这批建议已生成新版本，可在版本记录中查看。</span>
      <template v-else-if="interactive">
        <span>已选择 {{ acceptedCount }} / {{ data.patches.length }} 条</span>
        <span v-if="pendingConfirmation" role="status">请核对并确认标记的建议。</span>
        <span v-if="noChange" role="status">选中内容与原文相同，请修改或取消选择。</span>
        <button
          class="btn primary ml-auto"
          :disabled="locked || !acceptedCount || pendingConfirmation || noChange"
          @click="apply"
        >
          采纳所选并保存新版本
        </button>
      </template>
      <template v-else>
        <span>仅查看建议；可在对应简历的定向改写页核对并采纳。</span>
        <RouterLink v-if="reference" class="btn ml-auto" :to="reference">{{
          data.patches.length ? '核对并采纳建议' : '查看改写记录'
        }}</RouterLink>
      </template>
    </footer>
  </article>
</template>
