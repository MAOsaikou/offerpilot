<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

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
const error = localizedText()
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
    error.value = () =>
      cause instanceof Error ? cause.message : uiText('Could not load model configuration')
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
    error.value = () =>
      cause instanceof Error ? cause.message : uiText('Could not save. Please retry.')
  } finally {
    saving.value = false
  }
}
function optionLabel(model: ResumeModelOption) {
  return `${model.label} · ${model.provider} / ${model.model}${model.configured ? '' : uiText('(Unconfigured)')}`
}
onMounted(load)
</script>

<template>
  <section class="panel form-stack model-settings" aria-labelledby="resume-model-heading">
    <h2 id="resume-model-heading">{{ uiText('Resume parsing models') }}</h2>
    <p class="subtle">
      {{ uiText('Choose models for text and images, or share a model that supports images.') }}
    </p>
    <p v-if="loading" role="status">{{ uiText('Loading model configuration…') }}</p>
    <template v-else-if="settings">
      <fieldset :disabled="saving" class="form-stack model-fields">
        <label class="field"
          >{{ uiText('Text resume model') }}
          <select v-model="textTier" data-testid="text-model">
            <option v-if="!textModel" :value="textTier" disabled>
              {{ uiText('The previous model was removed. Select another.') }}
            </option>
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
        <label class="settings-toggle">
          <input
            v-model="shared"
            type="checkbox"
            :disabled="!textModel?.configured || !textModel?.supportsVision"
          />
          <span>{{ uiText('Use this model for text and images') }}</span>
        </label>
        <p v-if="textModel && !textModel.supportsVision" class="subtle">
          {{
            uiText(
              'The current text model is not marked as image-capable. Choose a separate vision model.',
            )
          }}
        </p>
        <label v-if="!shared" class="field"
          >{{ uiText('Image and PDF model') }}
          <select v-model="visionTier" data-testid="vision-model">
            <option v-if="!visionModel" :value="visionTier" disabled>
              {{ uiText('The previous model was removed. Select another.') }}
            </option>
            <option
              v-for="model in models"
              :key="model.id"
              :value="model.id"
              :disabled="!model.configured || !model.supportsVision"
            >
              {{ optionLabel(model) }}{{ model.supportsVision ? '' : uiText('(Text only)') }}
            </option>
          </select>
        </label>
      </fieldset>
      <p class="helper-note">
        {{
          uiText(
            'Applies only to future uploads and retries. Queued or active parsing keeps its original selection. Chat models are unaffected. PDFs and DOCX files with images use the vision model.',
          )
        }}
      </p>
      <p v-if="!valid" class="error-banner" role="status">
        {{
          uiText(
            'This selection is unavailable. Choose a configured text model and an image-capable model.',
          )
        }}
      </p>
      <button class="btn primary" :disabled="!valid || !dirty || saving" @click="save">
        {{ saving ? uiText('Saving…') : uiText('Save model selection') }}
      </button>
      <p v-if="dirty && !saving" class="subtle">
        {{ uiText('You have unsaved model selections.') }}
      </p>
      <p v-if="saved" class="success-banner" role="status">
        {{ uiText('Saved. Applies to the next upload or retry.') }}
      </p>
    </template>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <button v-if="!settings && !loading" class="btn" @click="load">
      {{ uiText('Reload #2') }}
    </button>
    <p class="subtle">
      {{
        uiText(
          'The model list comes from the backend. Configured does not mean connectivity has been verified. API keys stay on the server and are never read or displayed here.',
        )
      }}
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
</style>
