<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { resumeVersionSource, type ResumeVersion } from '@/shared/types/resume'
import { diffLines } from '@/shared/utils/lineDiff'

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
  if (typeof restored === 'number') return `回滚自版本 ${restored}`
  const parent = no(v.parentVersionId)
  return parent ? `基于版本 ${parent}` : ''
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
  return a && b && a.id !== b.id ? diffLines(a.plainText, b.plainText) : []
})
const changed = computed(() => diff.value.filter((line) => line.kind !== 'same').length)
function compareWith(v: ResumeVersion) {
  left.value = v.id
  right.value = props.currentVersionId ?? right.value
}
</script>
<template>
  <ol class="version-tree" aria-label="版本树">
    <li
      v-for="{ version, depth } in rows"
      :key="version.id"
      class="list-row"
      :style="{ paddingInlineStart: `${depth * 1.5}rem` }"
    >
      <div>
        <h3>
          版本 {{ version.versionNo }}
          <span v-if="version.id === currentVersionId" class="tag">当前</span>
        </h3>
        <small
          >{{ new Date(version.createdAt).toLocaleString('zh-CN') }} ·
          {{ resumeVersionSource[version.source] ?? '其他来源'
          }}<template v-if="origin(version)"> · {{ origin(version) }}</template></small
        >
      </div>
      <div class="resume-actions">
        <button class="btn" type="button" @click="emit('view', version)">查看</button>
        <button
          v-if="version.id !== currentVersionId"
          class="btn"
          type="button"
          @click="compareWith(version)"
        >
          与当前对比
        </button>
        <button class="btn" type="button" :disabled="busy" @click="emit('load', version)">
          载入编辑
        </button>
        <button
          v-if="version.id !== currentVersionId"
          class="btn"
          type="button"
          :disabled="busy"
          @click="emit('restore', version)"
        >
          回滚到此版本
        </button>
      </div>
    </li>
  </ol>
  <section v-if="versions.length > 1" class="form-stack version-compare" aria-label="版本对比">
    <h2>版本对比</h2>
    <div class="compare-pickers">
      <label
        >旧版本
        <select v-model="left">
          <option v-for="v in versions" :key="v.id" :value="v.id">版本 {{ v.versionNo }}</option>
        </select></label
      >
      <label
        >新版本
        <select v-model="right">
          <option v-for="v in versions" :key="v.id" :value="v.id">版本 {{ v.versionNo }}</option>
        </select></label
      >
    </div>
    <p v-if="left === right" class="subtle">请选择两个不同的版本。</p>
    <p v-else-if="!changed" class="subtle">两个版本的文本内容相同。</p>
    <template v-else>
      <p class="subtle">{{ changed }} 行有变化（红色为旧版本删除，绿色为新版本新增）。</p>
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
