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

const features = [
  { icon: 'chat', label: 'AI 求职对话' },
  { icon: 'resumes', label: '简历打磨' },
  { icon: 'jobs', label: '岗位匹配' },
  { icon: 'interviews', label: '模拟面试' },
]

const floats = [
  { icon: 'trending-up', title: '简历评分', value: '92', cls: 'fc-1' },
  { icon: 'spark', title: '新匹配岗位', value: '+12', cls: 'fc-2' },
  { icon: 'check', title: '模拟面试通过', value: 'Offer +1', cls: 'fc-3' },
]

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
    <!-- 背景视觉层：极光渐变 + 网格 -->
    <div class="bg-fx" aria-hidden="true">
      <div class="orb orb-a"></div>
      <div class="orb orb-b"></div>
      <div class="orb orb-c"></div>
      <div class="grid-overlay"></div>
    </div>

    <div class="login-inner">
      <!-- 左侧：品牌展示 -->
      <div class="showcase">
        <div class="brand-row" style="--i: 0">
          <BrandMark :size="40" />
          <span class="brand-name">OfferPilot</span>
        </div>

        <h1 class="hero-title" style="--i: 1">
          让每一次投递<br />
          <span class="grad-text">都有迹可循</span>
        </h1>
        <p class="hero-sub" style="--i: 2">
          从了解自己到拿到 Offer，AI 全程陪你打磨简历、匹配岗位、演练面试。
        </p>

        <ul class="feature-chips" style="--i: 3">
          <li v-for="f in features" :key="f.label">
            <AppIcon :name="f.icon" :size="15" />
            <span>{{ f.label }}</span>
          </li>
        </ul>

        <svg
          class="login-route"
          viewBox="0 0 470 300"
          fill="none"
          aria-hidden="true"
          style="--i: 4"
        >
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

        <!-- 悬浮玻璃卡片 -->
        <div
          v-for="card in floats"
          :key="card.title"
          class="float-card"
          :class="card.cls"
          aria-hidden="true"
        >
          <span class="fc-icon"><AppIcon :name="card.icon" :size="16" /></span>
          <span class="fc-text">{{ card.title }}</span>
          <b class="fc-value">{{ card.value }}</b>
        </div>
      </div>

      <!-- 右侧：玻璃登录卡片 -->
      <div class="panel">
        <header class="panel-head">
          <span class="panel-badge">
            {{ mode === 'register' ? '新用户注册' : '欢迎回来' }}
          </span>
          <h2>{{ mode === 'register' ? '创建你的账号' : '登录 OfferPilot' }}</h2>
          <p class="panel-sub">
            {{
              mode === 'register'
                ? '只需一个邮箱和密码，马上开始你的求职旅程。'
                : '从了解自己，到找到下一站。'
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

          <button class="submit-btn" type="submit" :disabled="loading">
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
            <AppIcon v-if="!loading" name="arrow" :size="17" class="submit-arrow" />
          </button>
        </form>

        <p class="mode-switch">
          <template v-if="mode === 'register'">已有账号？</template>
          <template v-else>还没有账号？</template>
          <button type="button" class="mode-link" :disabled="loading" @click="switchMode">
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
          <RouterLink to="/staff/login" class="staff-entry">
            <AppIcon name="headset" :size="14" />
            客服人员入口
          </RouterLink>
        </footer>
      </div>
    </div>
  </section>
</template>
<style scoped>
/* ===== 页面骨架 ===== */
.login {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: var(--background);
}
.login-inner {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  max-width: 76rem;
  margin-inline: auto;
  padding: 48px clamp(20px, 4vw, 56px);
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  align-items: center;
  gap: clamp(32px, 5vw, 72px);
}

/* ===== 背景视觉层 ===== */
.bg-fx {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.4;
}
.orb-a {
  width: 520px;
  height: 520px;
  top: -160px;
  right: -80px;
  background: radial-gradient(circle, var(--primary), transparent 68%);
  animation: drift-a 22s var(--ease-out, ease) infinite alternate;
}
.orb-b {
  width: 420px;
  height: 420px;
  bottom: -140px;
  left: -100px;
  background: radial-gradient(circle, #6d5bd0, transparent 68%);
  animation: drift-b 26s var(--ease-out, ease) infinite alternate;
}
.orb-c {
  width: 300px;
  height: 300px;
  top: 38%;
  left: 42%;
  background: radial-gradient(circle, #e8a23d, transparent 70%);
  opacity: 0.22;
  animation: drift-c 30s var(--ease-out, ease) infinite alternate;
}
@keyframes drift-a {
  from { transform: translate(0, 0) scale(1); }
  to { transform: translate(-70px, 60px) scale(1.12); }
}
@keyframes drift-b {
  from { transform: translate(0, 0) scale(1); }
  to { transform: translate(80px, -50px) scale(1.08); }
}
@keyframes drift-c {
  from { transform: translate(0, 0); }
  to { transform: translate(-50px, -70px); }
}
.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--border) 1px, transparent 1px),
    linear-gradient(90deg, var(--border) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 90% 80% at 50% 40%, black 0%, transparent 72%);
  opacity: 0.35;
}

/* ===== 入场动画 ===== */
.showcase > *,
.panel > * {
  animation: fade-up 560ms var(--ease-out) both;
  animation-delay: calc(var(--i, 0) * 90ms);
}
@keyframes fade-up {
  from { opacity: 0; transform: translateY(14px); }
}

/* ===== 左侧品牌展示 ===== */
.showcase {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-name {
  font-family: var(--app-font-display);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.01em;
}
.hero-title {
  margin: 8px 0 0;
  font-family: var(--app-font-display);
  font-size: clamp(34px, 4.6vw, 52px);
  line-height: 1.14;
  letter-spacing: -0.01em;
}
.grad-text {
  background: linear-gradient(120deg, var(--primary) 20%, #d0619f 55%, #6d5bd0 95%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.hero-sub {
  margin: 0;
  max-width: 34rem;
  font-size: 15.5px;
  line-height: 1.75;
  color: var(--muted-foreground);
}

.feature-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
}
.feature-chips li {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--card);
  color: var(--muted-foreground);
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 2px 8px -4px rgb(21 33 59 / 0.12);
}
.feature-chips li :deep(svg) {
  color: var(--primary);
}

/* 路线动效插画 */
.login-route {
  width: min(100%, 440px);
  height: auto;
  overflow: visible;
  margin-top: 6px;
}
.login-route-line {
  stroke: var(--primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-dasharray: 1;
  filter: drop-shadow(0 4px 10px color-mix(in srgb, var(--primary) 45%, transparent));
  animation: draw 900ms var(--ease-out) 400ms both;
}
@keyframes draw {
  from { stroke-dashoffset: 1; }
}
.login-stop {
  animation: rise 240ms var(--ease-out) both;
  animation-delay: calc(500ms + var(--i) * 180ms);
}
@keyframes rise {
  from { opacity: 0; transform: translateY(6px); }
}
.login-stop circle {
  fill: var(--card);
  stroke: var(--foreground);
  stroke-width: 2;
}
.login-stop circle.is-end {
  fill: var(--primary);
  stroke: var(--primary);
}
.login-stop text {
  fill: var(--muted-foreground);
  font-size: 13px;
  font-family: var(--app-font-sans);
}

/* 悬浮玻璃卡片 */
.float-card {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: color-mix(in srgb, var(--card) 82%, transparent);
  backdrop-filter: blur(12px);
  box-shadow: var(--shadow-float);
  font-size: 13px;
  animation: fade-up 560ms var(--ease-out) both, bob 5s ease-in-out infinite alternate;
  animation-delay: calc(700ms + var(--i, 0) * 90ms), calc(1200ms + var(--i) * 600ms);
}
@keyframes bob {
  from { margin-top: 0; }
  to { margin-top: -9px; }
}
.fc-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--primary) 13%, transparent);
  color: var(--primary);
}
.fc-text {
  color: var(--muted-foreground);
}
.fc-value {
  font-family: var(--app-font-display);
  font-size: 15px;
}
.fc-1 { top: 26%; right: 6%; }
.fc-2 { top: 52%; right: 0; }
.fc-3 { bottom: 8%; right: 12%; }

/* ===== 右侧玻璃登录卡片 ===== */
.panel {
  position: relative;
  width: 100%;
  max-width: 27rem;
  justify-self: end;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 34px 30px 26px;
  border: 1px solid color-mix(in srgb, var(--border) 70%, transparent);
  border-radius: calc(var(--radius) + 6px);
  background: color-mix(in srgb, var(--card) 88%, transparent);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px -28px color-mix(in srgb, var(--primary) 32%, transparent),
    0 12px 40px -18px rgb(21 33 59 / 0.25);
}
.panel::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 3px;
  border-radius: 999px 999px 0 0;
  background: linear-gradient(90deg, var(--primary), #6d5bd0, #e8a23d);
}
.panel-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.panel-badge {
  align-self: flex-start;
  padding: 4px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 11%, transparent);
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
}
.panel-head h2 {
  margin: 2px 0 0;
  font-family: var(--app-font-display);
  font-size: 25px;
  line-height: 1.25;
}
.panel-sub {
  margin: 0;
  font-size: 13.5px;
  color: var(--muted-foreground);
}

/* 表单 */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  padding: 11px 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: color-mix(in srgb, var(--card) 60%, var(--background));
  color: var(--foreground);
  font-size: 14px;
  transition: border-color 160ms var(--ease-out), box-shadow 160ms var(--ease-out);
}
.field-control input {
  padding-right: 44px;
}
.field input::placeholder {
  color: var(--muted-foreground);
  opacity: 0.65;
}
.field input:focus {
  outline: none;
  border-color: var(--ring);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 20%, transparent);
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

/* 渐变提交按钮 */
.submit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: var(--radius-control);
  background: linear-gradient(120deg, var(--primary) 0%, #d0478e 55%, #a04ecf 110%);
  color: var(--primary-foreground);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.06em;
  cursor: pointer;
  box-shadow: 0 10px 24px -10px color-mix(in srgb, var(--primary) 65%, transparent);
  transition: transform 160ms var(--ease-out), box-shadow 160ms var(--ease-out), filter 160ms ease;
}
.submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.05);
  box-shadow: 0 14px 30px -10px color-mix(in srgb, var(--primary) 70%, transparent);
}
.submit-btn:active:not(:disabled) {
  transform: translateY(0);
}
.submit-btn:disabled {
  opacity: 0.75;
  cursor: default;
}
.submit-arrow {
  transition: transform 160ms var(--ease-out);
}
.submit-btn:hover .submit-arrow {
  transform: translateX(3px);
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
  to { transform: rotate(360deg); }
}

/* 底部切换与入口 */
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
  font-weight: 700;
  cursor: pointer;
}
.mode-link:hover {
  text-decoration: underline;
}
.panel-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--border);
  padding-top: 14px;
}
.staff-entry {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--muted-foreground);
  text-decoration: none;
  transition: color 160ms ease;
}
.staff-entry:hover {
  color: var(--primary);
}

/* ===== 响应式 ===== */
@media (max-width: 900px) {
  .login-inner {
    grid-template-columns: 1fr;
    padding-block: 40px 56px;
    gap: 36px;
  }
  .panel {
    justify-self: stretch;
    max-width: none;
  }
  .float-card {
    display: none;
  }
  .login-route {
    max-width: 380px;
    margin-inline: auto;
  }
}

/* 暗色主题微调 */
:global(.dark) .orb { opacity: 0.3; }
:global(.dark) .panel {
  background: color-mix(in srgb, var(--card) 78%, transparent);
  box-shadow:
    0 24px 70px -28px color-mix(in srgb, var(--primary) 26%, transparent),
    0 12px 40px -18px rgb(0 0 0 / 0.5);
}
:global(.dark) .grid-overlay { opacity: 0.16; }
</style>
