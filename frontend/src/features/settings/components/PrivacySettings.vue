<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { onMounted, ref } from 'vue'
import { getPrivacySettings, savePrivacySettings } from '@/shared/api/privacy-settings'
const enabled = ref(true)
const loaded = ref(false)
const busy = ref(false)
const error = localizedText()
const status = localizedText()
async function load() {
  busy.value = true
  error.value = ''
  try {
    enabled.value = (await getPrivacySettings()).piiMasking
    loaded.value = true
  } catch {
    error.value = () => uiText('Could not load privacy settings. Please retry.')
  } finally {
    busy.value = false
  }
}
async function save(event: Event) {
  const next = (event.target as HTMLInputElement).checked
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    enabled.value = (await savePrivacySettings(next)).piiMasking
    status.value = () => uiText('Privacy settings saved.')
  } catch {
    ;(event.target as HTMLInputElement).checked = enabled.value
    error.value = () => uiText('Save failed. Settings are unchanged. Please retry.')
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="form-stack">
    <label class="settings-toggle"
      ><input type="checkbox" :checked="enabled" :disabled="busy || !loaded" @change="save" />
      <span>{{
        uiText('Mask personal data before sending to models (on by default)')
      }}</span></label
    >
    <p class="subtle">
      {{
        uiText(
          'Mainland Chinese mobile numbers, valid 18-digit national ID numbers, emails and street addresses with house numbers are replaced with placeholders before model use and restored in replies. Originals remain on the server. Names, places without house numbers and image pixels are not masked.',
        )
      }}
    </p>
    <p v-if="loaded && !enabled" class="helper-note">
      {{
        uiText(
          'When off, this text may be sent unchanged to models. Detected contact details, ID numbers or home addresses trigger a risk notice.',
        )
      }}
    </p>
    <p v-if="busy" role="status">{{ uiText('Syncing privacy settings…') }}</p>
    <p v-if="status" class="success-banner" role="status">{{ status }}</p>
    <p v-if="error" role="alert" class="error-banner">
      {{ error }} <button v-if="!loaded" class="btn" @click="load">{{ uiText('Retry') }}</button>
    </p>
  </div>
</template>
