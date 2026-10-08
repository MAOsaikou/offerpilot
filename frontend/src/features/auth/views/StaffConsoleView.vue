<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { verifyToken } from '@/shared/api/auth'
import type { VerifyResult } from '@/shared/types/auth'
import { useAuthStore } from '@/shared/stores/auth'

const info = ref<VerifyResult | null>(null)
const error = ref('')
const loading = ref(true)
const auth = useAuthStore()
const router = useRouter()

async function verify() {
  loading.value = true
  error.value = ''
  try {
    const data = await verifyToken()
    if (!data.roles.includes('STAFF')) throw new Error('该账号不是客服账号')
    info.value = data
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '校验失败'
  } finally {
    loading.value = false
  }
}
void verify()

function logout() {
  auth.logout()
  info.value = null
  error.value = '已退出登录'
  void router.replace('/staff/login')
}
</script>
<template>
  <section class="mx-auto flex min-h-screen max-w-lg flex-col justify-center gap-4">
    <h1 class="text-xl font-semibold">客服工作台</h1>
    <p v-if="loading" role="status">正在验证客服身份…</p>
    <p v-if="error" class="text-sm text-red-600" role="alert">{{ error }}</p>
    <button v-if="error" class="btn" :disabled="loading" @click="verify">重试验证</button>
    <template v-if="info">
      <p class="text-sm">
        当前客服：{{ info.nickname }}（{{ info.userId }}），角色：{{ info.roles.join(', ') }}
      </p>
      <p class="text-sm">
        令牌有效期至：{{ info.expiresAt ? new Date(info.expiresAt).toLocaleString() : '未知' }}
      </p>
    </template>
    <button class="btn" type="button" @click="logout">退出登录</button>
  </section>
</template>
