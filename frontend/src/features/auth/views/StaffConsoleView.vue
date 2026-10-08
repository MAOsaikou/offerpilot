<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listStaffUsers, setUserStatus, verifyToken, type StaffUserRow } from '@/shared/api/auth'
import { useAuthStore } from '@/shared/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const staffName = ref('')
const users = ref<StaffUserRow[]>([])
const loading = ref(false)
const togglingId = ref('')
const error = ref('')
const notice = ref('')
const keyword = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return users.value
  return users.value.filter(
    (u) => u.email.toLowerCase().includes(kw) || u.nickname.toLowerCase().includes(kw),
  )
})

const activeCount = computed(() => users.value.filter((u) => u.status === 'ACTIVE').length)
const disabledCount = computed(() => users.value.filter((u) => u.status === 'DISABLED').length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    users.value = await listStaffUsers()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载账号列表失败'
    if (isAuthError(error.value)) logout()
  } finally {
    loading.value = false
  }
}

async function toggle(row: StaffUserRow) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (togglingId.value) return
  togglingId.value = row.id
  error.value = ''
  notice.value = ''
  try {
    const updated = await setUserStatus(row.id, next)
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u))
    notice.value = `已${next === 'DISABLED' ? '禁用' : '启用'}账号 ${updated.nickname}`
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '操作失败，请稍后重试'
    if (isAuthError(error.value)) logout()
  } finally {
    togglingId.value = ''
  }
}

function isAuthError(message: string): boolean {
  return /未登录|令牌无效|令牌过期|客服账号权限/.test(message)
}

function logout() {
  auth.logout()
  router.push('/staff/login')
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

onMounted(async () => {
  // 先用已存令牌确认客服身份，再加载账号列表
  try {
    const info = await verifyToken()
    if (!info.roles.includes('STAFF')) {
      error.value = '当前登录的不是客服账号'
      logout()
      return
    }
    staffName.value = info.nickname || info.userId
    await load()
  } catch {
    logout()
  }
})
</script>
<template>
  <section class="console">
    <header class="console-head">
      <div>
        <h1>账号管理</h1>
        <p class="subtle">管理所有用户端账号：查看、启用、禁用。</p>
      </div>
      <div class="head-right">
        <span v-if="staffName" class="staff-chip">{{ staffName }}</span>
        <button class="btn ghost" type="button" @click="logout">退出登录</button>
      </div>
    </header>

    <div class="toolbar">
      <input
        v-model="keyword"
        class="search"
        type="search"
        placeholder="搜索邮箱或昵称…"
        aria-label="搜索账号"
      />
      <span class="stat">共 {{ users.length }} 个账号</span>
      <span class="stat ok">启用 {{ activeCount }}</span>
      <span class="stat muted">禁用 {{ disabledCount }}</span>
      <button class="btn ghost refresh" type="button" :disabled="loading" @click="load">
        {{ loading ? '加载中…' : '刷新' }}
      </button>
    </div>

    <p v-if="notice" class="notice-banner" role="status">{{ notice }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>

    <div class="table-wrap">
      <table class="user-table">
        <thead>
          <tr>
            <th>邮箱</th>
            <th>昵称</th>
            <th>角色</th>
            <th>状态</th>
            <th>注册时间</th>
            <th class="col-action">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading && !users.length">
            <td colspan="6" class="empty">正在加载账号列表…</td>
          </tr>
          <tr v-else-if="!filtered.length">
            <td colspan="6" class="empty">{{ keyword ? '没有匹配的账号' : '还没有任何账号' }}</td>
          </tr>
          <tr v-for="row in filtered" :key="row.id">
            <td class="cell-email">{{ row.email }}</td>
            <td>{{ row.nickname }}</td>
            <td>
              <span v-for="role in row.roles" :key="role" class="role-tag">{{ role }}</span>
            </td>
            <td>
              <span class="status-pill" :class="row.status === 'ACTIVE' ? 'is-active' : 'is-disabled'">
                {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="cell-time">{{ formatTime(row.createdAt) }}</td>
            <td class="col-action">
              <button
                class="btn toggle"
                :class="row.status === 'ACTIVE' ? 'danger' : 'primary'"
                type="button"
                :disabled="togglingId === row.id"
                @click="toggle(row)"
              >
                {{ togglingId === row.id ? '处理中…' : row.status === 'ACTIVE' ? '禁用' : '启用' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
<style scoped>
.console {
  width: 100%;
  max-width: 64rem;
  margin-inline: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 32px 24px 48px;
  min-height: 100vh;
}
.console-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.console-head h1 {
  margin: 0 0 4px;
  font-family: var(--app-font-display);
  font-size: 24px;
}
.console-head .subtle {
  margin: 0;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.staff-chip {
  padding: 4px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  color: var(--primary);
  font-size: 13px;
  font-weight: 600;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.search {
  flex: 1 1 220px;
  max-width: 320px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--card);
  color: var(--foreground);
  font-size: 13px;
}
.search:focus {
  outline: none;
  border-color: var(--ring);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 22%, transparent);
}
.stat {
  font-size: 13px;
  color: var(--muted-foreground);
}
.stat.ok {
  color: var(--primary);
  font-weight: 600;
}
.refresh {
  margin-left: auto;
}

.notice-banner {
  margin: 0;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent);
  border-radius: var(--radius-control);
  background: color-mix(in srgb, var(--primary) 8%, transparent);
  color: var(--primary);
  font-size: 13px;
}

.table-wrap {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  overflow-x: auto;
}
.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.user-table th {
  text-align: left;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  color: var(--muted-foreground);
  font-weight: 600;
  white-space: nowrap;
}
.user-table td {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}
.user-table tbody tr:last-child td {
  border-bottom: 0;
}
.user-table tbody tr:hover {
  background: var(--muted);
}
.cell-email {
  font-weight: 600;
}
.cell-time {
  white-space: nowrap;
  color: var(--muted-foreground);
}
.empty {
  text-align: center;
  color: var(--muted-foreground);
  padding: 40px 14px;
}
.role-tag {
  display: inline-block;
  margin-right: 4px;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--muted);
  font-size: 12px;
  color: var(--muted-foreground);
}
.status-pill {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.status-pill.is-active {
  background: color-mix(in srgb, var(--primary) 14%, transparent);
  color: var(--primary);
}
.status-pill.is-disabled {
  background: var(--muted);
  color: var(--muted-foreground);
}
.col-action {
  text-align: right;
  white-space: nowrap;
}
.toggle {
  min-width: 64px;
}
.toggle.danger {
  color: var(--destructive, #c53030);
  border-color: color-mix(in srgb, var(--destructive, #c53030) 45%, transparent);
}
.toggle.danger:hover {
  background: color-mix(in srgb, var(--destructive, #c53030) 10%, transparent);
}
</style>
