<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import {
  confirmCareerAssessment,
  type AssessmentConfirmed,
  type RiasecLetter,
} from '@/shared/api/career'

const props = defineProps<{
  assessment: AssessmentConfirmed | null
  beforeConfirm: () => Promise<void>
}>()
const emit = defineEmits<{ confirmed: [assessment: AssessmentConfirmed] }>()
const dimensions: { key: RiasecLetter; label: string }[] = [
  { key: 'R', label: '实际型' },
  { key: 'I', label: '研究型' },
  { key: 'A', label: '艺术型' },
  { key: 'S', label: '社会型' },
  { key: 'E', label: '企业型' },
  { key: 'C', label: '常规型' },
]
const draft = ref<AssessmentConfirmed | null>(null)
const editing = ref(false)
const busy = ref(false)
const error = ref('')
const newTrait = ref('')
let disposed = false
onBeforeUnmount(() => (disposed = true))
watch(
  () => props.assessment,
  () => {
    editing.value = false
    error.value = ''
  },
  { deep: true },
)
function startEdit() {
  if (!props.assessment) return
  draft.value = JSON.parse(JSON.stringify(props.assessment)) as AssessmentConfirmed
  newTrait.value = ''
  editing.value = true
}
function addTrait() {
  const name = newTrait.value.trim()
  if (!draft.value || !name || draft.value.traits.length >= 8) return
  if (!draft.value.traits.some((t) => t.name === name))
    draft.value.traits.push({ name, confidence: 1, evidence: '' })
  newTrait.value = ''
}
async function save() {
  if (!draft.value || busy.value) return
  addTrait()
  busy.value = true
  error.value = ''
  try {
    await props.beforeConfirm()
    const result = await confirmCareerAssessment({
      riasec: { ...draft.value.riasec },
      traits: draft.value.traits.map((t) => t.name),
      ...draft.value.constraints,
    })
    if (disposed) return
    if (!result.confirmed) throw new Error('确认结果为空，请重试')
    emit('confirmed', result.confirmed)
    editing.value = false
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    if (!disposed) busy.value = false
  }
}
</script>

<template>
  <div v-if="!assessment" class="form-stack">
    <p class="subtle">还没有确认性格与兴趣画像。先聊几个问题，再由你确认或修正结果。</p>
    <a class="btn" href="#career-assessment">开始或继续职业测评</a>
  </div>
  <form v-else-if="editing && draft" class="form-stack" @submit.prevent="save">
    <p class="subtle">六个兴趣维度为 0–100 分，不能全部相同。修改后会重新确认测评画像。</p>
    <div class="score-grid">
      <label v-for="dimension in dimensions" :key="dimension.key" class="field">
        {{ dimension.key }} · {{ dimension.label }}
        <input
          v-model.number="draft.riasec[dimension.key]"
          type="number"
          min="0"
          max="100"
          step="1"
          required
          :disabled="busy"
          :name="`riasec-${dimension.key}`"
        />
      </label>
    </div>
    <div class="chips">
      <button
        v-for="(trait, index) in draft.traits"
        :key="trait.name"
        type="button"
        class="btn"
        :disabled="busy"
        :aria-label="`移除特质${trait.name}`"
        @click="draft.traits.splice(index, 1)"
      >
        {{ trait.name }} ×
      </button>
    </div>
    <div class="trait-input">
      <label class="field"
        >添加特质（最多 8 个）<input
          v-model="newTrait"
          maxlength="12"
          :disabled="busy || draft.traits.length >= 8"
          @keydown.enter.prevent="addTrait"
      /></label>
      <button
        type="button"
        class="btn"
        :disabled="busy || !newTrait.trim() || draft.traits.length >= 8"
        @click="addTrait"
      >
        添加特质
      </button>
    </div>
    <div class="score-grid">
      <label class="field"
        >约束城市<input v-model="draft.constraints.city" maxlength="50" :disabled="busy"
      /></label>
      <label class="field"
        >期望月薪<input v-model="draft.constraints.salary" maxlength="50" :disabled="busy"
      /></label>
      <label class="field"
        >加班接受度<input v-model="draft.constraints.overtime" maxlength="50" :disabled="busy"
      /></label>
    </div>
    <p v-if="error" role="alert" class="error-banner">{{ error }}（草稿已保留）</p>
    <div class="card-footer">
      <button class="btn primary" :disabled="busy">
        {{ busy ? '正在确认…' : '保存性格画像' }}
      </button>
      <button type="button" class="btn" :disabled="busy" @click="editing = false">取消</button>
    </div>
  </form>
  <div v-else class="form-stack">
    <div class="score-grid">
      <label v-for="dimension in dimensions" :key="dimension.key" class="field"
        >{{ dimension.key }} · {{ dimension.label }} · {{ assessment.riasec[dimension.key] }}
        <progress
          :value="assessment.riasec[dimension.key]"
          max="100"
          :aria-label="dimension.label"
        />
      </label>
    </div>
    <ul v-if="assessment.traits.length" class="trait-list">
      <li v-for="trait in assessment.traits" :key="trait.name">
        <strong>{{ trait.name }}</strong>
        <p class="subtle">
          {{ trait.evidence ? `依据：${trait.evidence}` : '自述特质，暂无原话依据' }}
        </p>
      </li>
    </ul>
    <p v-else class="subtle">还没有添加特质，可按自己的感受补充。</p>
    <p class="helper-note">
      现实约束：城市 {{ assessment.constraints.city || '未填写' }} · 月薪
      {{ assessment.constraints.salary || '未填写' }} · 加班
      {{ assessment.constraints.overtime || '未填写' }}
    </p>
    <p class="subtle">对话推断仅供探索参考，不是标准化心理测评；你的修正会用于后续职业推荐。</p>
    <button type="button" class="btn" @click="startEdit">编辑性格与约束</button>
  </div>
</template>

<style scoped>
.score-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.score-grid > *,
.trait-input > * {
  min-width: 0;
}
progress {
  width: 100%;
  accent-color: var(--primary);
}
.trait-list {
  padding-left: 20px;
}
.trait-list li + li {
  margin-top: 12px;
}
.trait-input {
  display: flex;
  gap: 12px;
  align-items: end;
  flex-wrap: wrap;
}
.trait-input .field {
  flex: 1 1 180px;
}
@media (max-width: 700px) {
  .score-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
