<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  getResumeModelSettings,
  saveResumeModelSettings,
  type ResumeModelSettings,
  type ResumeModelOption,
} from '@/shared/api/resume-model-settings'

const settings = ref<ResumeModelSettings>()
const textTier = ref('')
const visionTier = ref('')
const shared = ref(false)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const saved = ref(false)
const models = computed(() => settings.value?.models ?? [])
const textModels = computed(() => models.value.filter((model) => model.id !== 'VISION_ALT'))
const textModel = computed(() => models.value.find((model) => model.id === textTier.value))
const visionModel = computed(() => models.value.find((model) => model.id === visionTier.value))
const valid = computed(
  () =>
    textModel.value?.configured &&
    visionModel.value?.configured &&
    visionModel.value.supportsVision,
)
const dirty = computed(
  () =>
    !!settings.value &&
    (textTier.value !== settings.value.selection.textTier ||
      visionTier.value !== settings.value.selection.visionTier),
)
function apply(value: ResumeModelSettings) {
  settings.value = value
  textTier.value = value.selection.textTier
  visionTier.value = value.selection.visionTier
  shared.value = textTier.value === visionTier.value
}
watch([textTier, shared], () => {
  if (shared.value && textModel.value?.supportsVision) visionTier.value = textTier.value
  else if (shared.value) shared.value = false
})
watch([textTier, visionTier, shared], () => {
  saved.value = false
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await getResumeModelSettings())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取模型配置'
  } finally {
    loading.value = false
  }
}
async function save() {
  if (!valid.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    apply(await saveResumeModelSettings({ textTier: textTier.value, visionTier: visionTier.value }))
    saved.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}
function optionLabel(model: ResumeModelOption) {
  return `${model.label} · ${model.provider} / ${model.model}${model.configured ? '' : '（未配置）'}`
}
onMounted(load)
</script>

<template>
  <section class="panel form-stack model-settings" aria-labelledby="resume-model-heading">
    <h2 id="resume-model-heading">简历解析模型</h2>
    <p class="subtle">为文字和图片选择合适的模型，也可以共用一个支持图片的模型。</p>
    <p v-if="loading" role="status">正在读取模型配置…</p>
    <template v-else-if="settings">
      <fieldset :disabled="saving" class="form-stack model-fields">
        <label class="field"
          >文字简历模型
          <select v-model="textTier" data-testid="text-model">
            <option v-if="!textModel" :value="textTier" disabled>原模型已移除，请重新选择</option>
            <option
              v-for="model in textModels"
              :key="model.id"
              :value="model.id"
              :disabled="!model.configured"
            >
              {{ optionLabel(model) }}
            </option>
          </select>
        </label>
        <label class="model-toggle">
          <input
            v-model="shared"
            type="checkbox"
            :disabled="!textModel?.configured || !textModel?.supportsVision"
          />
          文字和图片共用此模型
        </label>
        <p v-if="textModel && !textModel.supportsVision" class="subtle">
          当前文字模型未标记为支持图片，请另选视觉模型。
        </p>
        <label v-if="!shared" class="field"
          >图片与 PDF 模型
          <select v-model="visionTier" data-testid="vision-model">
            <option v-if="!visionModel" :value="visionTier" disabled>
              原模型已移除，请重新选择
            </option>
            <option
              v-for="model in models"
              :key="model.id"
              :value="model.id"
              :disabled="!model.configured || !model.supportsVision"
            >
              {{ optionLabel(model) }}{{ model.supportsVision ? '' : '（仅文字）' }}
            </option>
          </select>
        </label>
      </fieldset>
      <p class="helper-note">
        仅影响你后续上传或重试的简历。已经排队或正在解析的任务保持原选择，聊天模型不受影响。PDF
        和含图片的 DOCX 使用视觉模型。
      </p>
      <p v-if="!valid" class="error-banner" role="status">
        当前选择不可用，请选择已配置的文字模型和支持图片的模型。
      </p>
      <button class="btn primary" :disabled="!valid || !dirty || saving" @click="save">
        {{ saving ? '正在保存…' : '保存模型选择' }}
      </button>
      <p v-if="dirty && !saving" class="subtle">有尚未保存的模型选择。</p>
      <p v-if="saved" class="success-banner" role="status">已保存，下一次上传或重试时生效。</p>
    </template>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <button v-if="!settings && !loading" class="btn" @click="load">重新加载</button>
    <p class="subtle">
      模型列表来自后端配置；“已配置”不代表已验证联网可用。API Key
      保留在服务器，页面不会读取或显示密钥。
    </p>
  </section>
</template>

<style scoped>
.model-settings {
  max-width: 800px;
}
.model-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.model-fields select {
  width: 100%;
  min-width: 0;
  text-overflow: ellipsis;
}
.model-toggle {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-toggle input {
  width: auto;
}
</style>
