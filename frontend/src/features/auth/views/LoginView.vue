<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RouterLink } from 'vue-router'
import BrandMark from '@/shared/components/BrandMark.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const mode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const demoLoading = ref(false)

const features = [
  { icon: 'chat', label: 'AI 求职对话' },
  { icon: 'resumes', label: '简历整理' },
  { icon: 'jobs', label: '岗位匹配' },
  { icon: 'board', label: '投递看板' },
]

function returnToWorkspace() {
  const redirect = route.query.redirect
  void router.push(
    typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
      ? redirect
      : '/',
  )
}

async function continueGuest() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    if (await auth.enterGuest()) returnToWorkspace()
    else error.value = '原访客身份已失效，访问凭据仍保留。请联系管理员恢复历史数据。'
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '无法进入访客体验，请重试'
  } finally {
    loading.value = false
  }
}

async function enterDemo() {
  if (loading.value) return
  loading.value = true
  demoLoading.value = true
  error.value = ''
  try {
    await auth.demoLogin()
    returnToWorkspace()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '无法进入体验账号，请重试'
  } finally {
    loading.value = false
    demoLoading.value = false
  }
}

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    if (mode.value === 'register') {
      await auth.register(email.value.trim(), password.value)
    } else {
      await auth.login(email.value.trim(), password.value)
    }
    returnToWorkspace()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '操作失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}
</script>
<template>
  <section class="auth-page">
    <div class="auth-layout">
      <div class="auth-intro">
        <div class="brand-row">
          <BrandMark :size="32" />
          <span class="brand-name">OfferPilot</span>
          <span class="brand-caption">职航</span>
        </div>

        <h1 class="hero-title">
          让每一次投递<br />
          都有迹可循
        </h1>
        <p class="hero-sub">整理简历、找到合适的岗位，记录每一步求职进展。</p>

        <ul class="feature-list">
          <li v-for="f in features" :key="f.label">
            <AppIcon :name="f.icon" :size="18" />
            <span>{{ f.label }}</span>
          </li>
        </ul>
      </div>

      <div class="auth-panel">
        <header class="panel-head">
          <h2>{{ mode === 'register' ? '创建你的账号' : '登录 OfferPilot' }}</h2>
          <p class="panel-sub">
            {{
              mode === 'register'
                ? '只需一个邮箱和密码，马上开始你的求职旅程。'
                : '从了解自己，到找到下一站。'
            }}
          </p>
        </header>

        <p v-if="route.query.deleted === '1'" class="success-banner" role="status">
          账号和服务端数据已删除，文件清理失败时会自动重试；当前浏览器工作区已清除。
        </p>
        <div v-if="mode === 'login'" class="demo-entry">
          <button
            type="button"
            class="btn primary submit-btn"
            :disabled="loading"
            :aria-busy="demoLoading"
            @click="enterDemo"
          >
            <span v-if="demoLoading" class="spinner" aria-hidden="true" />
            {{ demoLoading ? '正在进入体验账号…' : '体验账号一键登录' }}
          </button>
          <p class="subtle">
            虚构求职旅程已备好简历、投递与面试记录。公网体验沙箱限时保留，请勿填写个人信息。
          </p>
        </div>

        <form class="auth-form" :aria-busy="loading" novalidate @submit.prevent="submit">
          <label class="field">
            <span class="field-label">邮箱</span>
            <input
              v-model="email"
              type="email"
              name="email"
              autocomplete="email"
              required
              placeholder="you@example.edu.cn"
            />
          </label>

          <label class="field">
            <span class="field-label">{{ mode === 'register' ? '设置密码' : '密码' }}</span>
            <span class="field-control">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
                required
                :minlength="mode === 'register' ? 6 : undefined"
                :placeholder="mode === 'register' ? '至少 6 位' : '请输入密码'"
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
            {{
              loading && !demoLoading
                ? mode === 'register'
                  ? '注册中…'
                  : '登录中…'
                : mode === 'register'
                  ? '注册'
                  : '登录'
            }}
          </button>
        </form>

        <p class="mode-switch">
          <template v-if="mode === 'register'">已有账号？</template>
          <template v-else>还没有账号？</template>
          <button type="button" class="text-control" :disabled="loading" @click="switchMode">
            {{ mode === 'register' ? '直接登录' : '立即注册' }}
          </button>
        </p>

        <footer class="panel-foot">
          <p v-if="auth.requiresLogin" class="subtle" role="status">
            登录已结束或身份失效，请重新登录。原访客数据仍保留。
          </p>
          <button
            v-if="auth.hasGuest"
            type="button"
            class="btn"
            :disabled="loading"
            @click="continueGuest"
          >
            继续使用原访客身份
          </button>
          <button
            v-else-if="!auth.hasStoredGuest"
            type="button"
            class="btn"
            :disabled="loading"
            @click="continueGuest"
          >
            以访客身份体验
          </button>
          <p v-else class="subtle" role="status">
            原访客身份已失效，访问凭据仍保留。请联系管理员恢复历史数据。
          </p>
          <p class="subtle">
            访客身份有效期为 30
            天。注册账号不会自动迁移访客数据；清除浏览器数据后无法找回访客访问凭据。
          </p>
          <RouterLink to="/staff/login" class="secondary-link">
            <AppIcon name="headset" :size="16" />
            客服人员入口
          </RouterLink>
        </footer>
      </div>
    </div>
  </section>
</template>
<style src="../styles/auth.css" scoped></style>
