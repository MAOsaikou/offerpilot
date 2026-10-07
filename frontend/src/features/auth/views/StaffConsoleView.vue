<script setup lang="ts">
import { ref } from 'vue'
import { verifyToken, getStoredToken, clearToken, type VerifyResult } from '../api'

const info = ref<VerifyResult | null>(null)
const error = ref('')

// 页面打开时用已保存的令牌做一次解析与比对校验
if (!getStoredToken()) {
  error.value = '尚未登录，请先到 /staff/login 登录'
} else {
  verifyToken()
    .then((data) => (info.value = data))
    .catch((ex) => (error.value = ex instanceof Error ? ex.message : '校验失败'))
}

function logout() {
  clearToken()
  info.value = null
  error.value = '已退出登录'
}
</script>
<template>
  <section class="mx-auto flex min-h-screen max-w-lg flex-col justify-center gap-4">
    <h1 class="text-xl font-semibold">客服工作台</h1>
    <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    <template v-if="info">
      <p class="text-sm">
        当前客服：{{ info.nickname }}（{{ info.userId }}），角色：{{ info.roles.join(', ') }}
      </p>
      <p class="text-sm">
        令牌有效期至：{{ info.expiresAt ? new Date(info.expiresAt).toLocaleString() : '未知' }}
      </p>
      <button class="btn" type="button" @click="logout">退出登录</button>
    </template>
  </section>
</template>
