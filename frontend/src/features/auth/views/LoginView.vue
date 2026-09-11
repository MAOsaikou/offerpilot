<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const email = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)

async function onSubmit(): Promise<void> {
  error.value = null
  submitting.value = true
  try {
    // TODO(auth): 接入 POST /auth/login，见 docs/05 §2
    await router.push({ name: 'dashboard' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <header class="space-y-1">
      <h1 class="text-lg font-semibold text-foreground">登录 OfferPilot</h1>
      <p class="text-sm text-muted-foreground">用校园邮箱注册，或一键体验</p>
    </header>

    <form class="space-y-4" @submit.prevent="onSubmit">
      <div class="space-y-1.5">
        <label for="email" class="text-sm font-medium text-foreground">邮箱</label>
        <input
          id="email"
          v-model="email"
          type="email"
          required
          autocomplete="email"
          placeholder="student@example.edu.cn"
          class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
        />
      </div>

      <div class="space-y-1.5">
        <label for="password" class="text-sm font-medium text-foreground">密码</label>
        <input
          id="password"
          v-model="password"
          type="password"
          required
          autocomplete="current-password"
          placeholder="••••••••"
          class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
        />
      </div>

      <p v-if="error" class="text-sm text-danger">{{ error }}</p>

      <button
        type="submit"
        :disabled="submitting"
        class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-60"
      >
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>

    <p class="text-center text-xs text-muted-foreground">
      体验账号与注册流程见 docs/05 §2（待接入）
    </p>
  </div>
</template>
