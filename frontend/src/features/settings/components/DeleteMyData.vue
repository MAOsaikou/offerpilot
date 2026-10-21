<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
import { useWorkspace } from '@/shared/stores/workspace'
import { deleteMyData } from '@/shared/api/auth'
import { completeDataDeletion, identitySignal } from '@/shared/utils/authSession'

const auth = useAuthStore()
const workspace = useWorkspace()
const router = useRouter()
const dialog = ref<HTMLDialogElement>()
const input = ref<HTMLInputElement>()
const opener = ref<HTMLButtonElement>()
const opened = ref(false)
const confirm = ref('')
const busy = ref(false)
const error = localizedText()
const restricted = computed(() =>
  auth.user?.isDemo && !auth.user?.demoExpiresAt
    ? uiText(
        'Shared demo accounts cannot delete all data. Staff use a dedicated reset to restore the fictional journey.',
      )
    : auth.isStaff
      ? uiText('Staff accounts cannot use self-service deletion. Contact an administrator.')
      : !auth.isAuthenticated
        ? uiText('Sign in or enter guest mode first.')
        : '',
)
async function open() {
  if (restricted.value) return
  confirm.value = ''
  error.value = ''
  opened.value = true
  await nextTick()
  dialog.value?.showModal()
  input.value?.focus()
}
function close() {
  if (busy.value) return
  dialog.value?.close()
  opened.value = false
  opener.value?.focus()
}
function trap(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('input,button')].filter(
    (element) => !element.hasAttribute('disabled'),
  )
  const first = controls[0]
  const last = controls.at(-1)
  if (!first) {
    event.preventDefault()
    dialog.value.focus()
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
async function remove() {
  if (confirm.value !== 'DELETE' || busy.value || restricted.value) return
  const session = identitySignal()
  busy.value = true
  error.value = ''
  try {
    await deleteMyData(confirm.value)
    session.throwIfAborted()
    workspace.clearIdentityData()
    completeDataDeletion()
    dialog.value?.close()
    opened.value = false
    // Let the global logout watcher run before the explicit result navigation.
    await nextTick()
    await router.replace({ path: '/login', query: { deleted: '1' } })
  } catch (ex) {
    error.value = () =>
      ex instanceof Error ? ex.message : uiText('Deletion failed. Please try again later.')
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <section class="danger-zone form-stack" aria-labelledby="delete-data-title">
    <h2 id="delete-data-title">{{ uiText('Delete all my data') }}</h2>
    <p>
      {{
        uiText(
          'Permanently deletes your account, original uploads and server files, resume versions and AI reports, saved jobs and applications, interview answers, career profile and growth tasks, knowledge notes and vectors, conversations, runs and approvals, preferences and usage counts. All sign-in tokens are invalidated.',
        )
      }}
    </p>
    <p class="subtle">
      {{
        uiText(
          'Also clears local drafts, archives and notes for this identity in this browser. This cannot be undone. Export anything you need first. This cannot delete local copies in other browsers, downloaded files or content already sent to model providers. Failed server file cleanup retries automatically.',
        )
      }}
    </p>
    <p v-if="restricted" class="helper-note">{{ restricted }}</p>
    <button ref="opener" class="btn delete-button" :disabled="!!restricted" @click="open">
      {{ uiText('Delete all my data') }}
    </button>
    <dialog
      ref="dialog"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="delete-confirm-title"
      aria-describedby="delete-confirm-description"
      :aria-busy="busy"
      tabindex="-1"
      @cancel.prevent="close"
      @keydown="trap"
    >
      <form v-if="opened" class="form-stack" @submit.prevent="remove">
        <h2 id="delete-confirm-title">{{ uiText('Permanently delete account and all data?') }}</h2>
        <p id="delete-confirm-description">
          {{
            uiText(
              "All server career data and this browser's local workspace will be deleted. You will be signed out immediately. Your account cannot be recovered.",
            )
          }}
        </p>
        <label class="field" for="delete-confirm-input">
          {{ uiText('Type DELETE to confirm') }}
          <input
            id="delete-confirm-input"
            ref="input"
            v-model="confirm"
            autocomplete="off"
            spellcheck="false"
            :disabled="busy"
          />
        </label>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="busy" role="status">{{ uiText('Deleting. Please wait for the result…') }}</p>
        <div class="delete-actions">
          <button type="button" class="btn" :disabled="busy" @click="close">
            {{ uiText('Cancel') }}
          </button>
          <button class="btn delete-button" :disabled="confirm !== 'DELETE' || busy">
            {{ busy ? uiText('Deleting… #2') : uiText('Permanently delete') }}
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.danger-zone {
  border-top: 1px solid var(--danger);
  padding-top: 24px;
  margin-top: 16px;
}
.delete-button {
  color: var(--danger);
  border-color: var(--danger);
}
dialog {
  width: min(480px, calc(100vw - 32px));
  margin: auto;
  max-height: calc(100dvh - 32px);
  overflow: auto;
  padding: 24px;
  border: 1px solid var(--danger);
  border-radius: 12px;
  background: var(--card);
  color: var(--card-foreground);
}
dialog::backdrop {
  background: rgb(0 0 0 / 50%);
}
.delete-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}
</style>
