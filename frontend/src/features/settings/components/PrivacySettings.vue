<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getPrivacySettings, savePrivacySettings } from '@/shared/api/privacy-settings'
const enabled = ref(true)
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const status = ref('')
async function load() {
  busy.value = true
  error.value = ''
  try {
    enabled.value = (await getPrivacySettings()).piiMasking
    loaded.value = true
  } catch {
    error.value = '隐私设置加载失败，请重试。'
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
    status.value = '隐私设置已保存。'
  } catch {
    ;(event.target as HTMLInputElement).checked = enabled.value
    error.value = '保存失败，设置未更改，请重试。'
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="form-stack">
    <label
      ><input type="checkbox" :checked="enabled" :disabled="busy || !loaded" @change="save" />
      发送模型前脱敏（默认开启）</label
    >
    <p class="subtle">
      文本中的中国大陆手机号、有效校验码的18位身份证号和邮箱将替换为占位符；回复回显时还原。原件和服务端数据仍保存原文。姓名、地址及图片像素暂不遮盖。
    </p>
    <p v-if="loaded && !enabled" class="helper-note">
      关闭后，相关文本可能原样发送给模型；检测到联系方式或身份证号时会提示风险。
    </p>
    <p v-if="busy" role="status">正在同步隐私设置…</p>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="error" role="alert" class="error-banner">
      {{ error }} <button v-if="!loaded" class="btn" @click="load">重试</button>
    </p>
  </div>
</template>
