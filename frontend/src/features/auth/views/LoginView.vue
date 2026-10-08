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

const stops = [
  { x: 40, y: 250, label: '了解自己' },
  { x: 170, y: 190, label: '打磨简历' },
  { x: 300, y: 150, label: '投递岗位' },
  { x: 430, y: 60, label: '拿到 Offer' },
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
  <section class="login">
    <div class="login-panel">
      <header class="login-head">
        <BrandMark :size="44" />
        <h1>{{ mode === 'register' ? '创建你的 OfferPilot 账号' : '欢迎来到 OfferPilot' }}</h1>
        <p class="subtle">
          {{
            mode === 'register'
              ? '只需一个邮箱和密码，马上开始你的求职旅程。'
              : '从了解自己，到找到下一站。登录后继续你的求职旅程。'
          }}
        </p>
      </header>

      <form class="login-form" novalidate @submit.prevent="submit">
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

        <button class="btn primary login-submit" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner" aria-hidden="true" />
          {{
            loading
              ? mode === 'register'
                ? '注册中…'
                : '登录中…'
              : mode === 'register'
                ? '注 册'
                : '登 录'
          }}
        </button>
      </form>

      <p class="mode-switch">
        <template v-if="mode === 'register'">已有账号？</template>
        <template v-else>还没有账号？</template>
        <button type="button" class="mode-link" :disabled="loading" @click="switchMode">
          {{ mode === 'register' ? '直接登录' : '立即注册' }}
        </button>
      </p>

      <footer class="login-foot">
        <p v-if="auth.requiresLogin" role="status">
          登录已结束或身份失效，请重新登录。原访客数据仍保留。
        </p>
        <template v-if="auth.hasGuest">
          <button type="button" class="btn" :disabled="loading" @click="continueGuest">
            继续使用原访客身份
          </button>
          <p class="subtle">
            访客身份有效期为 30
            天。注册账号不会自动迁移访客数据；清除浏览器数据后无法找回访客访问凭据。
          </p>
        </template>
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
        <RouterLink to="/staff/login" class="staff-entry">客服人员登录</RouterLink>
      </footer>
    </div>

    <svg class="login-route" viewBox="0 0 470 300" fill="none" aria-hidden="true">
      <path
        class="login-route-line"
        d="M40 250 C 110 250, 120 190, 170 190 S 250 150, 300 150 S 390 60, 430 60"
        pathLength="1"
      />
      <g
        v-for="(stop, index) in stops"
        :key="stop.label"
        class="login-stop"
        :style="{ '--i': index }"
      >
        <circle
          :cx="stop.x"
          :cy="stop.y"
          :r="index === stops.length - 1 ? 7 : 5"
          :class="index === stops.length - 1 ? 'is-end' : ''"
        />
        <text :x="stop.x" :y="stop.y + 28" text-anchor="middle">{{ stop.label }}</text>
      </g>
    </svg>
  </section>
</template>
<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  align-items: center;
  gap: 56px;
}

/* 左侧：卡片式登录面板 */
.login-panel {
  max-width: 26rem;
  width: 100%;
  justify-self: end;
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 32px 28px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  box-shadow: 0 12px 40px -18px rgb(21 33 59 / 0.25);
}
.login-head {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.login-head h1 {
  margin: 4px 0 0;
  font-family: var(--app-font-display);
  font-size: 26px;
  line-height: 1.25;
}
.login-head .subtle {
  margin: 0;
}

/* 表单字段 */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--muted-foreground);
}
.field-control {
  position: relative;
  display: block;
}
.field input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--background);
  color: var(--foreground);
  font-size: 14px;
  transition:
    border-color 160ms var(--ease-out, ease),
    box-shadow 160ms var(--ease-out, ease);
}
.field-control input {
  padding-right: 42px;
}
.field input::placeholder {
  color: var(--muted-foreground);
  opacity: 0.7;
}
.field input:focus {
  outline: none;
  border-color: var(--ring);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 22%, transparent);
}
.eye-toggle {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
}
.eye-toggle:hover {
  color: var(--foreground);
  background: var(--muted);
}

/* 提交按钮与加载动画 */
.login-submit {
  justify-content: center;
  gap: 8px;
  display: inline-flex;
  align-items: center;
  width: 100%;
  padding-block: 11px;
  font-size: 15px;
  letter-spacing: 0.04em;
}
.spinner {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--primary-foreground) 40%, transparent);
  border-top-color: var(--primary-foreground);
  animation: spin 700ms linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 底部入口 */
.mode-switch {
  margin: 0;
  font-size: 13px;
  color: var(--muted-foreground);
  text-align: center;
}
.mode-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.mode-link:hover {
  text-decoration: underline;
}
.login-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.login-foot p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
.staff-entry {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  font-size: 13px;
  color: var(--muted-foreground);
  text-decoration: none;
  transition: color 160ms ease;
}
.staff-entry:hover {
  color: var(--primary);
}

/* 右侧品牌动效插画 */
.login-route {
  width: 100%;
  height: auto;
  overflow: visible;
}
.login-route-line {
  stroke: var(--primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 0;
  animation: draw 900ms var(--ease-out) 150ms both;
}
@keyframes draw {
  from {
    stroke-dashoffset: 1;
  }
}
.login-stop {
  animation: rise 240ms var(--ease-out) both;
  animation-delay: calc(200ms + var(--i) * 180ms);
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
.login-stop circle {
  fill: var(--background);
  stroke: var(--foreground);
  stroke-width: 2;
}
.login-stop circle.is-end {
  fill: var(--foreground);
}
.login-stop text {
  fill: var(--muted-foreground);
  font-size: 13px;
  font-family: var(--app-font-sans);
}

@media (max-width: 760px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login-panel {
    justify-self: stretch;
    max-width: none;
  }
  .login-route {
    max-width: 420px;
    margin-inline: auto;
  }
}
</style>
