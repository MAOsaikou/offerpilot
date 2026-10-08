<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.staffLogin(email.value.trim(), password.value)
    await router.push('/staff')
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <section class="staff-login mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4">
    <h1 class="text-xl font-semibold">客服端登录</h1>
    <form class="flex flex-col gap-3" @submit.prevent="submit">
      <label
        >客服邮箱<input
          v-model="email"
          type="email"
          autocomplete="username"
          required
          placeholder="客服邮箱"
      /></label>
      <label
        >密码<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          placeholder="密码"
      /></label>
      <p v-if="error" class="text-sm text-red-600" role="alert">{{ error }}</p>
      <button class="btn primary" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>
    <RouterLink to="/login">返回用户登录</RouterLink>
  </section>
</template>
<style scoped>
.staff-login {
  padding: 24px;
}
label {
  display: grid;
  gap: 8px;
}
input {
  width: 100%;
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
}
</style>
