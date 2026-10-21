<script setup lang="ts">
import { language, translate as uiText } from '@/shared/utils/language'

import { computed, ref, watch } from 'vue'
import { resumeVersionSource, type ResumeVersion } from '@/shared/types/resume'
import { diffLines, resumeLines } from '@/shared/utils/lineDiff'

const props = defineProps<{
  versions: ResumeVersion[]
  currentVersionId: string | null
  busy: boolean
}>()
const emit = defineEmits<{
  view: [ResumeVersion]
  load: [ResumeVersion]
  restore: [ResumeVersion]
}>()

const byId = computed(() => new Map(props.versions.map((v) => [v.id, v])))
/** Depth-first order with indentation, so branches (e.g. a rewrite of an older version) stay visible. */
const rows = computed(() => {
  const children = new Map<string | null, ResumeVersion[]>()
  for (const v of props.versions) {
    const parent = v.parentVersionId && byId.value.has(v.parentVersionId) ? v.parentVersionId : null
    children.set(parent, [...(children.get(parent) ?? []), v])
  }
  const out: { version: ResumeVersion; depth: number }[] = []
  const walk = (parent: string | null, depth: number) => {
    const list = (children.get(parent) ?? []).sort((x, y) => x.versionNo - y.versionNo)
    // A single child continues the same line; only real branches (several children) indent.
    const childDepth = parent !== null && list.length > 1 ? depth + 1 : depth
    for (const v of list) {
      out.push({ version: v, depth: childDepth })
      walk(v.id, childDepth)
    }
  }
  walk(null, 0)
  return out
})
const no = (id: string | null | undefined) => (id ? byId.value.get(id)?.versionNo : undefined)
function origin(v: ResumeVersion) {
  const restored = v.parseMeta?.restoredFromNo
  if (typeof restored === 'number') return uiText('Restored from version {0}', [restored])
  const parent = no(v.parentVersionId)
  return parent ? uiText('Based on version {0}', [parent]) : ''
}

const left = ref('')
const right = ref('')
watch(
  () => props.versions,
  (list) => {
    const current = list.find((v) => v.id === props.currentVersionId) ?? list[list.length - 1]
    right.value = current?.id ?? ''
    left.value =
      (current && byId.value.has(current.parentVersionId ?? '') ? current.parentVersionId : '') ??
      ''
  },
  { immediate: true },
)
const diff = computed(() => {
  const a = byId.value.get(left.value)
  const b = byId.value.get(right.value)
  return a && b && a.id !== b.id
    ? diffLines(resumeLines(a.structured as never), resumeLines(b.structured as never))
    : []
})
const changed = computed(() => diff.value.filter((line) => line.kind !== 'same').length)
function compareWith(v: ResumeVersion) {
  left.value = v.id
  right.value = props.currentVersionId ?? right.value
}
</script>
<template>
  <ol class="version-tree" :aria-label="uiText('Version tree')">
    <li
      v-for="{ version, depth } in rows"
      :key="version.id"
      class="list-row"
      :style="{ paddingInlineStart: `${depth * 1.5}rem` }"
    >
      <div>
        <h3>
          {{ uiText('Version {0}', [version.versionNo]) }}
          <span v-if="version.id === currentVersionId" class="tag">{{ uiText('Current') }}</span>
        </h3>
        <small
          >{{ new Date(version.createdAt).toLocaleString(language) }} ·
          {{ resumeVersionSource[version.source] ?? uiText('Other source')
          }}<template v-if="origin(version)"> · {{ origin(version) }}</template></small
        >
      </div>
      <div class="resume-actions">
        <button class="btn" type="button" @click="emit('view', version)">
          {{ uiText('View') }}
        </button>
        <button
          v-if="version.id !== currentVersionId"
          class="btn"
          type="button"
          @click="compareWith(version)"
        >
          {{ uiText('Compare with current') }}
        </button>
        <button class="btn" type="button" :disabled="busy" @click="emit('load', version)">
          {{ uiText('Load for editing') }}
        </button>
        <button
          v-if="version.id !== currentVersionId"
          class="btn"
          type="button"
          :disabled="busy"
          @click="emit('restore', version)"
        >
          {{ uiText('Restore this version') }}
        </button>
      </div>
    </li>
  </ol>
  <section
    v-if="versions.length > 1"
    class="form-stack version-compare"
    :aria-label="uiText('Version comparison')"
  >
    <h2>{{ uiText('Version comparison') }}</h2>
    <div class="compare-pickers">
      <label
        >{{ uiText('Older version') }}
        <select v-model="left">
          <option v-for="v in versions" :key="v.id" :value="v.id">
            {{ uiText('Version {0}', [v.versionNo]) }}
          </option>
        </select></label
      >
      <label
        >{{ uiText('Newer version') }}
        <select v-model="right">
          <option v-for="v in versions" :key="v.id" :value="v.id">
            {{ uiText('Version {0}', [v.versionNo]) }}
          </option>
        </select></label
      >
    </div>
    <p v-if="left === right" class="subtle">{{ uiText('Select two different versions.') }}</p>
    <p v-else-if="!changed" class="subtle">
      {{ uiText('These versions have identical content.') }}
    </p>
    <template v-else>
      <p class="subtle">
        {{
          uiText(
            '{0} changed lines (red: removed from older version; green: added in newer version).',
            [changed],
          )
        }}
      </p>
      <pre class="resume-text diff"><span
        v-for="(line, index) in diff"
        :key="index"
        :class="line.kind"
      >{{ line.kind === 'add' ? '+ ' : line.kind === 'del' ? '- ' : '  ' }}{{ line.text }}
</span></pre>
    </template>
  </section>
</template>
<style scoped>
.version-tree {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.compare-pickers {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
}
.diff span {
  display: block;
}
.diff .add {
  background: color-mix(in srgb, #16a34a 14%, transparent);
}
.diff .del {
  background: color-mix(in srgb, #dc2626 14%, transparent);
  text-decoration: line-through;
}
</style>
