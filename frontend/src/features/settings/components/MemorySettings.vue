<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  addMemory,
  clearMemories,
  deleteMemory,
  editMemory,
  listMemories,
  memoryKinds,
  setMemoryEnabled,
  type MemoryDraft,
  type MemoryInventory,
  type UserMemory,
} from '@/shared/api/memories'

const inventory = ref<MemoryInventory>({ items: [], memoryEnabled: true, limit: 50 })
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const status = ref('')
const editingId = ref<string | null>(null)
const formOpen = ref(false)
const draft = ref<MemoryDraft>({ kind: 'OTHER', fact: '' })
const confirming = ref<string | null>(null)
const sourceLabels = { EXPLICIT: '聊天确认', SUGGESTED_ACCEPTED: '建议已确认', MANUAL: '手动管理' }
const points = computed(() => Array.from(draft.value.fact).length)
const valid = computed(() => draft.value.fact.trim().length > 0 && points.value <= 200)
async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    inventory.value = await listMemories()
    loaded.value = true
  } catch {
    error.value = '记忆加载失败，请重试。'
  } finally {
    busy.value = false
  }
}
async function changeEnabled(event: Event) {
  const input = event.target as HTMLInputElement
  const next = input.checked
  await perform(async () => {
    inventory.value.memoryEnabled = (await setMemoryEnabled(next)).memoryEnabled
  }, '记忆设置已保存。')
  input.checked = inventory.value.memoryEnabled
}
async function perform(action: () => Promise<void>, message: string) {
  if (!loaded.value || busy.value) return
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    await action()
    status.value = message
  } catch (cause) {
    error.value =
      cause instanceof Error ? `操作失败：${cause.message}。请重试。` : '操作失败，请重试。'
  } finally {
    busy.value = false
  }
}
function open(memory?: UserMemory) {
  editingId.value = memory?.id ?? null
  draft.value = { kind: memory?.kind ?? 'OTHER', fact: memory?.fact ?? '' }
  formOpen.value = true
  confirming.value = null
}
async function save() {
  if (!valid.value) return
  await perform(async () => {
    const saved = editingId.value
      ? await editMemory(editingId.value, draft.value)
      : await addMemory(draft.value)
    inventory.value.items = [saved, ...inventory.value.items.filter((item) => item.id !== saved.id)]
    formOpen.value = false
  }, '记忆已保存。')
}
function askDelete(id: string) {
  confirming.value = id
  formOpen.value = false
}
async function remove() {
  const id = confirming.value
  if (!id) return
  await perform(
    async () => {
      if (id === 'all') {
        await clearMemories()
        inventory.value.items = []
      } else {
        await deleteMemory(id)
        inventory.value.items = inventory.value.items.filter((item) => item.id !== id)
      }
      confirming.value = null
    },
    id === 'all' ? '全部记忆已清空。' : '记忆已删除。',
  )
}
onMounted(load)
</script>

<template>
  <section class="form-stack" aria-labelledby="memory-heading">
    <h3 id="memory-heading">长期记忆</h3>
    <p class="subtle">
      保存你确认的目标城市、岗位、技能和约束，供新对话参考。聊天只提出建议，确认后才保存；不会从简历、岗位描述或知识笔记自动提取。最多50条，每条200字。
    </p>
    <label>
      <input
        type="checkbox"
        :checked="inventory.memoryEnabled"
        :disabled="busy || !loaded"
        @change="changeEnabled"
      />
      使用长期记忆（默认开启）
    </label>
    <p v-if="loaded && !inventory.memoryEnabled" class="helper-note">
      已关闭：新运行不读取记忆，也不提出保存建议。已有条目仍保留，可手动编辑或删除；关闭前已开始的回复可能仍带有已读取的上下文。
    </p>
    <p class="subtle">
      记忆保存在服务端，送模型前遵循文本脱敏设置。删除记忆不会删除原对话；原对话中的短期上下文仍可能引用它。删除账号数据会同时清除记忆。
    </p>
    <p v-if="busy" role="status">正在同步记忆…</p>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }} <button class="btn" :disabled="busy" @click="load">重新加载</button>
    </p>
    <template v-if="loaded">
      <div class="flex flex-wrap gap-2">
        <button
          class="btn"
          :disabled="busy || inventory.items.length >= inventory.limit"
          @click="open()"
        >
          添加记忆
        </button>
        <button
          class="btn"
          :disabled="busy || inventory.items.length === 0"
          @click="askDelete('all')"
        >
          清空全部记忆
        </button>
        <span class="subtle">{{ inventory.items.length }} / {{ inventory.limit }}</span>
      </div>
      <p v-if="inventory.items.length === 0" class="subtle">
        还没有长期记忆。可以手动添加，或在聊天中说「记住：我的目标城市是杭州」并确认。
      </p>
      <ul v-else class="space-y-3">
        <li v-for="memory in inventory.items" :key="memory.id" class="rounded-lg border p-3">
          <p class="break-words">{{ memory.fact }}</p>
          <p class="subtle">{{ memoryKinds[memory.kind] }} · {{ sourceLabels[memory.source] }}</p>
          <div class="mt-2 flex flex-wrap gap-2">
            <button
              class="btn"
              :disabled="busy"
              :aria-label="`编辑记忆：${memory.fact}`"
              @click="open(memory)"
            >
              编辑
            </button>
            <button
              class="btn"
              :disabled="busy"
              :aria-label="`删除记忆：${memory.fact}`"
              @click="askDelete(memory.id)"
            >
              删除
            </button>
          </div>
        </li>
      </ul>
      <div v-if="confirming" class="rounded-lg border p-3" role="group" aria-label="确认删除记忆">
        <p>
          {{
            confirming === 'all'
              ? '确认清空全部记忆？此操作无法撤销。'
              : '确认删除这条记忆？此操作无法撤销。'
          }}
        </p>
        <button class="btn" :disabled="busy" @click="remove">确认删除</button>
        <button class="btn" :disabled="busy" @click="confirming = null">取消</button>
      </div>
      <form v-if="formOpen" class="form-stack" @submit.prevent="save">
        <fieldset :disabled="busy" class="form-stack min-w-0">
          <legend>{{ editingId ? '编辑记忆' : '添加记忆' }}</legend>
          <label class="field"
            >类型
            <select v-model="draft.kind">
              <option v-for="(label, kind) in memoryKinds" :key="kind" :value="kind">
                {{ label }}
              </option>
            </select>
          </label>
          <label class="field"
            >事实内容（单行，最多200字）<input v-model="draft.fact" type="text"
          /></label>
          <p class="subtle">{{ points }} / 200</p>
          <div class="flex flex-wrap gap-2">
            <button type="submit" class="btn primary" :disabled="!valid">保存记忆</button>
            <button type="button" class="btn" @click="formOpen = false">取消</button>
          </div>
        </fieldset>
      </form>
    </template>
  </section>
</template>
