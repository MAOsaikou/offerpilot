<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RouterLink } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import BrandMark from '@/shared/components/BrandMark.vue'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.staffLogin(email.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/staff'
    router.push(redirect)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <section class="auth-page">
    <div class="auth-layout staff-layout">
      <div class="brand-row">
        <BrandMark :size="32" />
        <span class="brand-name">OfferPilot</span>
        <span class="brand-caption">职航</span>
      </div>
      <div class="auth-panel">
        <header class="panel-head">
          <h1>登录客服端</h1>
          <p class="panel-sub">仅限客服人员登录，管理用户端账号的启用与禁用。</p>
        </header>

        <form class="auth-form" :aria-busy="loading" novalidate @submit.prevent="submit">
          <label class="field">
            <span class="field-label">客服邮箱</span>
            <input
              v-model="email"
              type="email"
              name="email"
              autocomplete="email"
              required
              placeholder="staff@company.com"
            />
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <span class="field-control">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                required
                placeholder="请输入密码"
              />
              <button
                type="button"
                class="eye-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <AppIcon :name="showPassword ? 'eye-off' : 'eye'" :size="18" />
              </button>
            </span>
          </label>

          <p v-if="error" class="error-banner" role="alert">{{ error }}</p>

          <button class="btn primary submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true" />
            {{ loading ? '登录中…' : '进入工作台' }}
          </button>
        </form>

        <footer class="panel-foot">
          <p class="subtle">客服账号由管理员开通，不支持自行注册。</p>
          <RouterLink to="/login" class="secondary-link"> 返回用户端登录 </RouterLink>
        </footer>
      </div>
    </div>
  </section>
</template>
<style src="../styles/auth.css" scoped></style>
