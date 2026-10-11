<script setup lang="ts">
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
const error = ref('')
const restricted = computed(() =>
  auth.user?.isDemo && !auth.user?.demoExpiresAt
    ? '共享体验账号不能删除全部数据，由客服使用专用重置入口恢复虚构旅程。'
    : auth.isStaff
      ? '客服账号不能自助删除，请通过管理员处理。'
      : !auth.isAuthenticated
        ? '请先登录或进入访客体验。'
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
    error.value = ex instanceof Error ? ex.message : '删除失败，请稍后重试'
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <section class="danger-zone form-stack" aria-labelledby="delete-data-title">
    <h2 id="delete-data-title">删除我的全部数据</h2>
    <p>
      永久删除账号、上传原件与服务端文件、简历版本和 AI
      报告、岗位收藏与投递记录、面试回答、职业档案与成长任务、知识笔记与向量、对话、运行与审批、偏好及用量计数，并使所有登录令牌失效。
    </p>
    <p class="subtle">
      同时清除当前浏览器此身份的本地草稿、归档和笔记。操作无法撤销，请先导出需要保留的内容。其他浏览器的本地副本、已下载文件以及已发送给模型供应商的内容无法通过此操作删除。服务端文件清理失败会自动重试。
    </p>
    <p v-if="restricted" class="helper-note">{{ restricted }}</p>
    <button ref="opener" class="btn delete-button" :disabled="!!restricted" @click="open">
      删除我的全部数据
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
        <h2 id="delete-confirm-title">永久删除账号和全部数据？</h2>
        <p id="delete-confirm-description">
          所有服务端求职数据及此浏览器的本地工作区将被删除，你会立即退出。账号无法恢复。
        </p>
        <label class="field" for="delete-confirm-input">
          请输入 DELETE 确认
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
        <p v-if="busy" role="status">正在删除，请等待结果…</p>
        <div class="delete-actions">
          <button type="button" class="btn" :disabled="busy" @click="close">取消</button>
          <button class="btn delete-button" :disabled="confirm !== 'DELETE' || busy">
            {{ busy ? '正在删除…' : '永久删除' }}
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
