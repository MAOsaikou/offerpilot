<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RouterLink } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useAuthStore } from '@/shared/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')

const trust = [
  { icon: 'shield', label: '账号管理' },
  { icon: 'zap', label: '禁用即时生效' },
  { icon: 'check', label: '操作留痕' },
]

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
  <section class="staff-login">
    <!-- 背景视觉层：冷色极光 -->
    <div class="bg-fx" aria-hidden="true">
      <div class="orb orb-a"></div>
      <div class="orb orb-b"></div>
      <div class="orb orb-c"></div>
      <div class="grid-overlay"></div>
    </div>

    <div class="stage">
      <!-- 卡片 -->
      <div class="panel">
        <div class="panel-strip" aria-hidden="true"></div>

        <div class="crest" style="--i: 0">
          <AppIcon name="headset" :size="26" />
        </div>

        <header class="panel-head" style="--i: 1">
          <span class="badge">客服控制中心</span>
          <h1>OfferPilot <span class="grad-text">客服端</span></h1>
          <p class="panel-sub">仅限客服人员登录，管理用户端账号的启用与禁用。</p>
        </header>

        <form class="staff-form" style="--i: 2" novalidate @submit.prevent="submit">
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

          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true" />
            {{ loading ? '登录中…' : '进入工作台' }}
            <AppIcon v-if="!loading" name="arrow" :size="17" class="submit-arrow" />
          </button>
        </form>

        <ul class="trust-row" style="--i: 3">
          <li v-for="t in trust" :key="t.label">
            <AppIcon :name="t.icon" :size="14" />
            <span>{{ t.label }}</span>
          </li>
        </ul>

        <footer class="staff-foot" style="--i: 4">
          <RouterLink to="/login" class="user-entry">
            <AppIcon name="arrow" :size="14" class="back-arrow" />
            返回用户端登录
          </RouterLink>
        </footer>
      </div>

      <!-- 装饰性悬浮元素 -->
      <div class="deco deco-ring" aria-hidden="true"></div>
      <div class="deco deco-dot-a" aria-hidden="true"></div>
      <div class="deco deco-dot-b" aria-hidden="true"></div>
    </div>
  </section>
</template>
<style scoped>
.staff-login {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: var(--background);
}

/* ===== 背景视觉层（冷色调，与用户端区分） ===== */
.bg-fx {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(95px);
  opacity: 0.35;
}
.orb-a {
  width: 560px;
  height: 560px;
  top: -180px;
  left: -120px;
  background: radial-gradient(circle, #6d5bd0, transparent 68%);
  animation: drift-a 24s var(--ease-out, ease) infinite alternate;
}
.orb-b {
  width: 460px;
  height: 460px;
  bottom: -160px;
  right: -120px;
  background: radial-gradient(circle, var(--primary), transparent 68%);
  animation: drift-b 28s var(--ease-out, ease) infinite alternate;
}
.orb-c {
  width: 260px;
  height: 260px;
  top: 30%;
  right: 24%;
  background: radial-gradient(circle, #2f9e8f, transparent 70%);
  opacity: 0.2;
  animation: drift-c 32s var(--ease-out, ease) infinite alternate;
}
@keyframes drift-a {
  from { transform: translate(0, 0) scale(1); }
  to { transform: translate(70px, 50px) scale(1.1); }
}
@keyframes drift-b {
  from { transform: translate(0, 0) scale(1); }
  to { transform: translate(-70px, -60px) scale(1.12); }
}
@keyframes drift-c {
  from { transform: translate(0, 0); }
  to { transform: translate(-40px, 60px); }
}
.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--border) 1px, transparent 1px),
    linear-gradient(90deg, var(--border) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: radial-gradient(ellipse 80% 75% at 50% 45%, black 0%, transparent 70%);
  opacity: 0.32;
}

/* ===== 舞台 ===== */
.stage {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
}
.panel {
  position: relative;
  width: 100%;
  max-width: 25rem;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 34px 30px 24px;
  border: 1px solid color-mix(in srgb, var(--border) 70%, transparent);
  border-radius: calc(var(--radius) + 6px);
  background: color-mix(in srgb, var(--card) 90%, transparent);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px -28px color-mix(in srgb, #6d5bd0 40%, transparent),
    0 12px 40px -18px rgb(21 33 59 / 0.25);
}
.panel-strip {
  position: absolute;
  inset: 0 0 auto;
  height: 3px;
  background: linear-gradient(90deg, #6d5bd0, var(--primary), #2f9e8f);
}
.panel > * {
  animation: fade-up 560ms var(--ease-out) both;
  animation-delay: calc(var(--i, 0) * 100ms);
}
@keyframes fade-up {
  from { opacity: 0; transform: translateY(14px); }
}

/* 顶部徽章圆环 */
.crest {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border-radius: 16px;
  background: linear-gradient(135deg, #6d5bd0, var(--primary));
  color: #fff;
  box-shadow: 0 10px 22px -8px color-mix(in srgb, #6d5bd0 60%, transparent);
}

.panel-head {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.badge {
  align-self: flex-start;
  padding: 3px 11px;
  border-radius: 999px;
  background: color-mix(in srgb, #6d5bd0 13%, transparent);
  color: #6d5bd0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
}
.panel-head h1 {
  margin: 2px 0 0;
  font-family: var(--app-font-display);
  font-size: 26px;
  line-height: 1.2;
}
.grad-text {
  background: linear-gradient(120deg, #6d5bd0, var(--primary));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.panel-sub {
  margin: 0;
  font-size: 13.5px;
  color: var(--muted-foreground);
}

/* 表单 */
.staff-form {
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

/* 提交按钮：紫→玫红渐变 */
.submit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: var(--radius-control);
  background: linear-gradient(120deg, #6d5bd0 0%, var(--primary) 90%);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.05em;
  cursor: pointer;
  box-shadow: 0 10px 24px -10px color-mix(in srgb, #6d5bd0 70%, transparent);
  transition: transform 160ms var(--ease-out), box-shadow 160ms var(--ease-out), filter 160ms ease;
}
.submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.05);
  box-shadow: 0 14px 30px -10px color-mix(in srgb, #6d5bd0 75%, transparent);
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
  border: 2px solid color-mix(in srgb, #fff 40%, transparent);
  border-top-color: #fff;
  animation: spin 700ms linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 特性条 */
.trust-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 12px 4px 0;
  border-top: 1px solid var(--border);
  list-style: none;
}
.trust-row li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--muted-foreground);
}
.trust-row li :deep(svg) {
  color: #6d5bd0;
}

.staff-foot {
  display: flex;
  justify-content: center;
}
.user-entry {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--muted-foreground);
  text-decoration: none;
  transition: color 160ms ease;
}
.user-entry:hover {
  color: var(--primary);
}
.back-arrow {
  transform: rotate(180deg);
}

/* ===== 装饰元素 ===== */
.deco {
  position: absolute;
  pointer-events: none;
  border-radius: 50%;
}
.deco-ring {
  width: 220px;
  height: 220px;
  top: 9%;
  right: 12%;
  border: 1.5px dashed color-mix(in srgb, #6d5bd0 45%, transparent);
  animation: spin-slow 40s linear infinite;
}
@keyframes spin-slow {
  to { transform: rotate(360deg); }
}
.deco-dot-a {
  width: 14px;
  height: 14px;
  top: 24%;
  left: 18%;
  background: var(--primary);
  opacity: 0.5;
  animation: pulse 3.2s ease-in-out infinite;
}
.deco-dot-b {
  width: 9px;
  height: 9px;
  bottom: 18%;
  right: 22%;
  background: #6d5bd0;
  opacity: 0.5;
  animation: pulse 3.2s ease-in-out 1.4s infinite;
}
@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 0.35; }
  50% { transform: scale(1.5); opacity: 0.75; }
}

/* ===== 响应式与暗色 ===== */
@media (max-width: 640px) {
  .deco { display: none; }
}
:global(.dark) .orb { opacity: 0.28; }
:global(.dark) .panel {
  background: color-mix(in srgb, var(--card) 80%, transparent);
  box-shadow:
    0 24px 70px -28px color-mix(in srgb, #6d5bd0 30%, transparent),
    0 12px 40px -18px rgb(0 0 0 / 0.5);
}
:global(.dark) .grid-overlay { opacity: 0.14; }
:global(.dark) .badge { color: #a493f5; }
</style>
