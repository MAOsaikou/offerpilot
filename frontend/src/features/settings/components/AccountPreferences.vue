<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getPreferences, savePreferences, type DefaultTier } from '@/shared/api/preferences'
import { getStoredTheme, setTheme, type ThemeMode } from '@/shared/utils/theme'

// AC-03: theme and the default chat tier are saved on the account and follow the user across browsers.
const theme = ref<ThemeMode>(getStoredTheme())
const defaultTier = ref<DefaultTier>('FLASH')
const status = ref('')
onMounted(async () => {
  try {
    const preferences = await getPreferences()
    defaultTier.value = preferences.defaultModelTier
    if (preferences.saved) theme.value = preferences.theme
  } catch {
    status.value = '账号偏好暂时读取失败，当前修改只保存在这个浏览器。'
  }
})
async function save() {
  setTheme(theme.value)
  status.value = ''
  try {
    await savePreferences({ defaultModelTier: defaultTier.value, theme: theme.value })
    status.value = '已保存到账号，换浏览器登录同一账号也会生效。'
  } catch (error) {
    status.value = '保存到账号失败：' + (error instanceof Error ? error.message : '请求失败') + '。'
  }
}
</script>
<template>
  <div class="form-grid">
    <label class="field"
      >外观主题<select v-model="theme" @change="save">
        <option value="light">明亮</option>
        <option value="dark">深色</option>
        <option value="system">跟随系统</option>
      </select></label
    ><label class="field"
      >新对话默认档位<select v-model="defaultTier" @change="save">
        <option value="FLASH">快速（DeepSeek Flash）</option>
        <option value="PRO">深度（DeepSeek Pro）</option>
      </select></label
    >
  </div>
  <p class="subtle" role="status">{{ status || '修改即时生效并保存到账号。' }}</p>
</template>
