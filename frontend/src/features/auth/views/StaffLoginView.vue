<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

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
const error = localizedText()

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.staffLogin(email.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/staff'
    router.push(redirect)
  } catch (ex) {
    error.value = () =>
      ex instanceof Error ? ex.message : uiText('Sign-in failed. Please try again later.')
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
        <span class="brand-caption">{{ uiText('OfferPilot') }}</span>
      </div>
      <div class="auth-panel">
        <header class="panel-head">
          <h1>{{ uiText('Staff sign-in #3') }}</h1>
          <p class="panel-sub">{{ uiText('Staff only. Manage user account access.') }}</p>
        </header>

        <form class="auth-form" :aria-busy="loading" @submit.prevent="submit">
          <label class="field">
            <span class="field-label">{{ uiText('Staff email') }}</span>
            <input
              v-model="email"
              :disabled="loading"
              type="email"
              name="email"
              autocomplete="email"
              required
              placeholder="staff@company.com"
            />
          </label>

          <label class="field">
            <span class="field-label">{{ uiText('Password') }}</span>
            <span class="field-control">
              <input
                v-model="password"
                :disabled="loading"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                required
                :placeholder="uiText('Enter your password')"
              />
              <button
                type="button"
                class="eye-toggle"
                :disabled="loading"
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
            {{ loading ? uiText('Signing in…') : uiText('Open dashboard') }}
          </button>
        </form>

        <footer class="panel-foot">
          <p class="subtle">
            {{
              uiText(
                'Staff accounts are created by administrators. Self-registration is unavailable.',
              )
            }}
          </p>
          <RouterLink to="/login" class="secondary-link">
            {{ uiText('Back to user sign-in') }}
          </RouterLink>
        </footer>
      </div>
    </div>
  </section>
</template>
<style src="../styles/auth.css" scoped></style>
