<script setup lang="ts">
import { computed } from 'vue'

import { localizedText, translate as uiText } from '@/shared/utils/language'

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
const error = localizedText()
const demoLoading = ref(false)

const features = computed(() => [
  { icon: 'chat', label: uiText('AI career chat') },
  { icon: 'resumes', label: uiText('Resume preparation') },
  { icon: 'jobs', label: uiText('Job matching') },
  { icon: 'board', label: uiText('Application tracking') },
])

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
    else
      error.value = () =>
        uiText(
          'Your guest session has expired. Access credentials are retained. Contact an administrator to recover your history.',
        )
  } catch (ex) {
    error.value = () =>
      ex instanceof Error ? ex.message : uiText('Could not start guest mode. Please retry.')
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
    error.value = () =>
      ex instanceof Error ? ex.message : uiText('Could not open the demo account. Please retry.')
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
    error.value = () =>
      ex instanceof Error ? ex.message : uiText('Something went wrong. Please try again later.')
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
          <span class="brand-caption">{{ uiText('OfferPilot') }}</span>
        </div>

        <h1 class="hero-title">
          {{ uiText('Keep track of') }}<br />
          {{ uiText('every application') }}
        </h1>
        <p class="hero-sub">
          {{
            uiText('Organize resumes, find suitable jobs and track each step of your job search.')
          }}
        </p>

        <ul class="feature-list">
          <li v-for="f in features" :key="f.label">
            <AppIcon :name="f.icon" :size="18" />
            <span>{{ f.label }}</span>
          </li>
        </ul>
      </div>

      <div class="auth-panel">
        <header class="panel-head">
          <h2>
            {{
              mode === 'register' ? uiText('Create your account') : uiText('Sign in to OfferPilot')
            }}
          </h2>
          <p class="panel-sub">
            {{
              mode === 'register'
                ? uiText('Start your job search with just an email and password.')
                : uiText('Get to know yourself and find your next opportunity.')
            }}
          </p>
        </header>

        <p v-if="route.query.deleted === '1'" class="success-banner" role="status">
          {{
            uiText(
              "Your account and server data have been deleted. Failed file cleanup will retry automatically. This browser's workspace has been cleared.",
            )
          }}
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
            {{ demoLoading ? uiText('Opening demo account…') : uiText('Try the demo account') }}
          </button>
          <p class="subtle">
            {{
              uiText(
                'Explore a fictional job search with prepared resumes, applications and interview records. This public demo is temporary. Please do not enter personal information.',
              )
            }}
          </p>
        </div>

        <form class="auth-form" :aria-busy="loading" novalidate @submit.prevent="submit">
          <label class="field">
            <span class="field-label">{{ uiText('Email') }}</span>
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
            <span class="field-label">{{
              mode === 'register' ? uiText('Create password') : uiText('Password')
            }}</span>
            <span class="field-control">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
                required
                :minlength="mode === 'register' ? 6 : undefined"
                :placeholder="
                  mode === 'register'
                    ? uiText('At least 6 characters')
                    : uiText('Enter your password')
                "
              />
              <button
                type="button"
                class="eye-toggle"
                :aria-label="showPassword ? uiText('Hide password') : uiText('Show password')"
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
                  ? uiText('Creating account…')
                  : uiText('Signing in…')
                : mode === 'register'
                  ? uiText('Create account')
                  : uiText('Sign in')
            }}
          </button>
        </form>

        <p class="mode-switch">
          <template v-if="mode === 'register'">{{ uiText('Already have an account?') }}</template>
          <template v-else>{{ uiText('New to OfferPilot?') }}</template>
          <button type="button" class="text-control" :disabled="loading" @click="switchMode">
            {{ mode === 'register' ? uiText('Sign in instead') : uiText('Create an account') }}
          </button>
        </p>

        <footer class="panel-foot">
          <p v-if="auth.requiresLogin" class="subtle" role="status">
            {{
              uiText(
                'Your session has ended or expired. Please sign in again. Guest data is retained.',
              )
            }}
          </p>
          <button
            v-if="auth.hasGuest"
            type="button"
            class="btn"
            :disabled="loading"
            @click="continueGuest"
          >
            {{ uiText('Resume guest session') }}
          </button>
          <button
            v-else-if="!auth.hasStoredGuest"
            type="button"
            class="btn"
            :disabled="loading"
            @click="continueGuest"
          >
            {{ uiText('Continue as guest') }}
          </button>
          <p v-else class="subtle" role="status">
            {{
              uiText(
                'Your guest session has expired. Access credentials are retained. Contact an administrator to recover your history.',
              )
            }}
          </p>
          <p class="subtle">
            {{
              uiText(
                'Guest access lasts 30 days. Creating an account does not migrate guest data. Clearing browser data permanently removes guest access credentials.',
              )
            }}
          </p>
          <RouterLink to="/staff/login" class="secondary-link">
            <AppIcon name="headset" :size="16" /> {{ uiText('Staff sign-in #2') }}
          </RouterLink>
        </footer>
      </div>
    </div>
  </section>
</template>
<style src="../styles/auth.css" scoped></style>
