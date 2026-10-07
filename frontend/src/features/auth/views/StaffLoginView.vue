<script setup lang="ts">
import { ref } from 'vue'
import { staffLogin, type LoginResult } from '../api'

const email = ref('staff@offerpilot.local')
const password = ref('staff123456')
const loading = ref(false)
const error = ref('')
const result = ref<LoginResult | null>(null)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    result.value = await staffLogin(email.value, password.value)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <section class="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4">
    <h1 class="text-xl font-semibold">客服端登录</h1>
    <form class="flex flex-col gap-3" @submit.prevent="submit">
      <input v-model="email" type="email" required placeholder="客服邮箱" />
      <input v-model="password" type="password" required placeholder="密码" />
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
      <button class="btn primary" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>
    <p v-if="result" class="text-sm text-green-700">
      客服 {{ result.user.nickname }} 登录成功（12 小时令牌已保存）
    </p>
  </section>
</template>
