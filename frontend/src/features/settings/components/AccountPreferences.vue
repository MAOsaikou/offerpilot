<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getPreferences, savePreferences, type DefaultTier } from '@/shared/api/preferences'
import { getStoredTheme, setTheme, type ThemeMode } from '@/shared/utils/theme'
import { language, setLanguage, type Language } from '@/shared/utils/language'

const theme = ref<ThemeMode>(getStoredTheme())
const defaultTier = ref<DefaultTier>('FLASH')
const selectedLanguage = ref<Language>(language.value)
const piiMasking = ref(true)
const status = localizedText()
const loaded = ref(false)
const busy = ref(false)
let alive = true
onBeforeUnmount(() => {
  alive = false
})
async function load() {
  busy.value = true
  status.value = ''
  try {
    const preferences = await getPreferences()
    if (!alive) return
    defaultTier.value = preferences.defaultModelTier
    if (preferences.saved) theme.value = preferences.theme
    selectedLanguage.value = preferences.language
    piiMasking.value = preferences.piiMasking ?? true
    setLanguage(preferences.language)
    loaded.value = true
  } catch {
    if (alive) status.value = () => uiText('Could not load preferences. Please retry.')
  } finally {
    if (alive) busy.value = false
  }
}
onMounted(load)
async function save() {
  if (busy.value || !loaded.value) return
  busy.value = true
  status.value = ''
  try {
    const saved = await savePreferences({
      defaultModelTier: defaultTier.value,
      theme: theme.value,
      language: selectedLanguage.value,
      piiMasking: piiMasking.value,
    })
    if (!alive) return
    setTheme(saved.theme)
    setLanguage(saved.language)
    status.value = () =>
      uiText('Saved to your account. Applies when you sign in on another browser.')
  } catch {
    if (!alive) return
    loaded.value = false
    status.value = () => uiText('Save failed. Reload preferences to confirm their current values.')
  } finally {
    if (alive) busy.value = false
  }
}
</script>
<template>
  <fieldset class="form-grid" :disabled="busy || !loaded" style="border: 0; padding: 0; margin: 0">
    <label class="field"
      >{{ uiText('Theme')
      }}<select v-model="theme" @change="save">
        <option value="light">{{ uiText('Light') }}</option>
        <option value="dark">{{ uiText('Dark') }}</option>
        <option value="system">{{ uiText('System') }}</option>
      </select></label
    >
    <label class="field"
      >{{ uiText('Default model for new chats')
      }}<select v-model="defaultTier" @change="save">
        <option value="FLASH">{{ uiText('Fast (DeepSeek Flash)') }}</option>
        <option value="PRO">{{ uiText('Deep (DeepSeek Pro)') }}</option>
      </select></label
    >
    <label class="field"
      >{{ uiText('Language')
      }}<select v-model="selectedLanguage" @change="save">
        <option value="zh-CN">{{ uiText('Simplified Chinese') }}</option>
        <option value="en-US">English</option>
      </select></label
    >
    <label class="field"
      ><span>{{ uiText('Mask personal data before sending to models') }}</span>
      <input v-model="piiMasking" type="checkbox" @change="save" />
    </label>
  </fieldset>
  <p class="subtle">
    {{
      uiText(
        'Language applies to all interface pages and subsequent AI replies. Source materials, citations and your data keep their original text.',
      )
    }}
  </p>
  <p v-if="!piiMasking && loaded" class="helper-note">
    {{
      uiText(
        'When disabled, contact details may be sent as plain text. Image pixels and names are outside masking coverage.',
      )
    }}
  </p>
  <p class="subtle" role="status">
    {{ busy ? uiText('Syncing…') : status || uiText('Changes apply immediately after saving.') }}
  </p>
  <button v-if="!loaded && !busy" class="btn" @click="load">{{ uiText('Reload') }}</button>
</template>
