<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getPreferences, savePreferences, type DefaultTier } from '@/shared/api/preferences'
import { getStoredTheme, setTheme, type ThemeMode } from '@/shared/utils/theme'
import { language, setLanguage, translate as t, type Language } from '@/shared/utils/language'

const theme = ref<ThemeMode>(getStoredTheme())
const defaultTier = ref<DefaultTier>('FLASH')
const selectedLanguage = ref<Language>(language.value)
const piiMasking = ref(true)
const status = ref('')
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
    if (alive)
      status.value = t('账号偏好读取失败，请重试。', 'Could not load preferences. Please retry.')
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
    status.value = t(
      '已保存到账号，换浏览器登录同一账号也会生效。',
      'Saved to your account. Applies when you sign in on another browser.',
    )
  } catch {
    if (!alive) return
    loaded.value = false
    status.value = t(
      '保存结果未确认；请重新读取后重试。',
      'Save failed. Reload preferences to confirm their current values.',
    )
  } finally {
    if (alive) busy.value = false
  }
}
</script>
<template>
  <fieldset class="form-grid" :disabled="busy || !loaded" style="border: 0; padding: 0; margin: 0">
    <label class="field"
      >{{ t('外观主题', 'Theme')
      }}<select v-model="theme" @change="save">
        <option value="light">{{ t('明亮', 'Light') }}</option>
        <option value="dark">{{ t('深色', 'Dark') }}</option>
        <option value="system">{{ t('跟随系统', 'System') }}</option>
      </select></label
    >
    <label class="field"
      >{{ t('新对话默认档位', 'Default model for new chats')
      }}<select v-model="defaultTier" @change="save">
        <option value="FLASH">{{ t('快速（DeepSeek Flash）', 'Fast (DeepSeek Flash)') }}</option>
        <option value="PRO">{{ t('深度（DeepSeek Pro）', 'Deep (DeepSeek Pro)') }}</option>
      </select></label
    >
    <label class="field"
      >{{ t('语言', 'Language')
      }}<select v-model="selectedLanguage" @change="save">
        <option value="zh-CN">简体中文</option>
        <option value="en-US">English</option>
      </select></label
    >
    <label class="field"
      ><span>{{ t('发送模型前脱敏', 'Mask personal data before sending to models') }}</span>
      <input v-model="piiMasking" type="checkbox" @change="save" />
    </label>
  </fieldset>
  <p class="subtle">
    {{
      t(
        '语言用于导航、偏好设置与后续 AI 对话；其他业务页面暂保留中文，原文引用保持原样。',
        'Language applies to navigation, preferences and subsequent AI replies. Other pages remain in Chinese; citations keep their original text.',
      )
    }}
  </p>
  <p v-if="!piiMasking && loaded" class="helper-note">
    {{
      t(
        '关闭后，联系方式等文本可能原样发送给模型。图片像素和姓名不在脱敏范围内。',
        'When disabled, contact details may be sent as plain text. Image pixels and names are outside masking coverage.',
      )
    }}
  </p>
  <p class="subtle" role="status">
    {{
      busy
        ? t('正在同步…', 'Syncing…')
        : status || t('修改即时生效并保存到账号。', 'Changes apply immediately after saving.')
    }}
  </p>
  <button v-if="!loaded && !busy" class="btn" @click="load">{{ t('重新读取', 'Reload') }}</button>
</template>
