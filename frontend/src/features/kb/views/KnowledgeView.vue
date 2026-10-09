<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  createKnowledgeNote,
  deleteKnowledgeNote,
  getKnowledgeNote,
  importKnowledgeNotes,
  listKnowledgeNotes,
  noteCategories,
  searchKnowledgeNotes,
  updateKnowledgeNote,
  type NoteCategory,
} from '@/shared/api/knowledge'

const workspace = useWorkspace()
const cache = useQueryClient()
const search = ref('')
const queryText = ref('')
const page = ref(1)
const title = ref('')
const content = ref('')
const category = ref<NoteCategory>('面试复盘')
const editing = ref<string | null>(null)
const deleting = ref<string | null>(null)
const message = ref('')
const error = ref('')
const busy = ref(false)
const confirmingImport = ref(false)
let debounce: ReturnType<typeof setTimeout> | undefined
onScopeDispose(() => clearTimeout(debounce))
watch(search, (text) => {
  clearTimeout(debounce)
  debounce = setTimeout(() => {
    queryText.value = text.trim()
    page.value = 1
  }, 250)
})
const notes = useQuery({
  queryKey: computed(() => ['knowledge-notes', sessionRevision.value, queryText.value, page.value]),
  queryFn: async ({ signal }) => {
    if (queryText.value) {
      const result = await searchKnowledgeNotes(queryText.value, 50, signal)
      return {
        items: result.items.map((hit) => ({ ...hit, content: hit.excerpt })),
        total: result.items.length,
        vectorAvailable: result.vectorAvailable,
      }
    }
    const result = await listKnowledgeNotes(page.value, 20, signal)
    return { items: result.items, total: result.total, vectorAvailable: undefined }
  },
})
const localNotes = computed(() => workspace.state.notes)
const pageCount = computed(() => Math.max(1, Math.ceil((notes.data.value?.total ?? 0) / 20)))
function reset() {
  editing.value = null
  title.value = ''
  content.value = ''
  category.value = '面试复盘'
}
watch(sessionRevision, () => {
  reset()
  search.value = ''
  queryText.value = ''
  page.value = 1
  message.value = ''
  error.value = ''
  busy.value = false
  deleting.value = null
  confirmingImport.value = false
})
async function refresh() {
  await cache.invalidateQueries({ queryKey: ['knowledge-notes'] })
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  const revision = sessionRevision.value
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (cause) {
    if (revision === sessionRevision.value)
      error.value = cause instanceof Error ? cause.message : '操作失败，请检查网络后重试。'
  } finally {
    if (revision === sessionRevision.value) busy.value = false
  }
}
async function save() {
  if (!title.value.trim() || !content.value.trim()) return
  const revision = sessionRevision.value
  const draft = {
    title: title.value.trim(),
    content: content.value.trim(),
    category: category.value,
  }
  await perform(async () => {
    if (editing.value) await updateKnowledgeNote(editing.value, draft)
    else await createKnowledgeNote(draft)
    if (revision !== sessionRevision.value) return
    message.value = '笔记已保存到当前身份的知识库'
    reset()
    await refresh()
  })
}
async function edit(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    const note = await getKnowledgeNote(id)
    if (revision !== sessionRevision.value) return
    editing.value = id
    title.value = note.title
    content.value = note.content
    category.value = note.category
  })
}
async function exportNote(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    const note = await getKnowledgeNote(id)
    if (revision === sessionRevision.value) downloadText(note.title + '.md', note.content)
  })
}
async function remove(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    await deleteKnowledgeNote(id)
    if (revision !== sessionRevision.value) return
    deleting.value = null
    if (editing.value === id) reset()
    message.value = '笔记已删除'
    if (page.value > 1 && notes.data.value?.items.length === 1) page.value--
    await refresh()
  })
}
async function importLocal() {
  const revision = sessionRevision.value
  // Freeze the original payload. Keep newly added/changed local notes, even during a retry.
  const snapshot = localNotes.value.map((note) => ({ ...note }))
  await perform(async () => {
    if (snapshot.some((note) => !noteCategories.includes(note.category as NoteCategory)))
      throw new Error('本地笔记包含未知分类，请先导出备份并检查原数据。')
    for (let offset = 0; offset < snapshot.length; offset += 50) {
      if (revision !== sessionRevision.value) return
      const batch = snapshot.slice(offset, offset + 50)
      const result = await importKnowledgeNotes(
        batch.map((note) => ({
          sourceId: note.id,
          note: {
            title: note.title,
            content: note.content,
            category: note.category as NoteCategory,
          },
        })),
      )
      if (revision !== sessionRevision.value) return
      if (result.imported + result.alreadyImported !== batch.length)
        throw new Error('服务器未确认全部笔记，本地副本已保留，请重试。')
      workspace.state.notes = workspace.state.notes.filter(
        (note) =>
          !batch.some(
            (saved) =>
              saved.id === note.id &&
              saved.title === note.title &&
              saved.content === note.content &&
              saved.category === note.category,
          ),
      )
      await refresh()
    }
    if (revision !== sessionRevision.value) return
    confirmingImport.value = false
    message.value = '已导入服务器确认接收的笔记；重复导入不会覆盖已有笔记'
    await refresh()
  })
}
function exportLocal() {
  downloadText('本地知识笔记备份.json', JSON.stringify(localNotes.value, null, 2))
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="把经验，存成下一次的底气"
      description="记录知识、项目亮点和面试复盘，让成长有迹可循。"
    />
    <div class="helper-note">
      笔记保存在当前身份的知识库，可供面试与成长指导引用。跨设备请登录同一账号；访客笔记属于原访客身份。
    </div>
    <section v-if="localNotes.length" class="panel form-stack" aria-label="导入本地笔记">
      <p>发现 {{ localNotes.length }} 条本地旧笔记，尚未导入当前身份的知识库。</p>
      <p v-if="confirmingImport">
        确认将这些笔记上传至当前身份？服务器确认接收后才清除对应本地副本。你可以先导出备份。
      </p>
      <div class="card-footer">
        <button class="btn" :disabled="busy" @click="exportLocal">导出本地备份</button>
        <button
          v-if="!confirmingImport"
          class="btn primary"
          :disabled="busy"
          @click="confirmingImport = true"
        >
          导入本地笔记
        </button>
        <template v-else>
          <button class="btn primary" :disabled="busy" @click="importLocal">确认上传并导入</button>
          <button class="btn" :disabled="busy" @click="confirmingImport = false">暂不导入</button>
        </template>
      </div>
    </section>
    <p v-if="workspace.storageError" class="error-banner" role="alert">
      {{ workspace.storageError }}
    </p>
    <p v-if="message" class="success-banner" role="status">{{ message }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }} 未确认成功的本地内容会保留。</p>
    <div class="split-content">
      <section
        class="panel form-stack"
        aria-label="知识笔记列表"
        :aria-busy="notes.isFetching.value"
      >
        <input
          v-model="search"
          class="input"
          aria-label="搜索知识笔记"
          maxlength="2000"
          placeholder="搜索标题、内容或分类…"
        />
        <p v-if="notes.isPending.value" role="status">正在读取知识笔记…</p>
        <div v-else-if="notes.isError.value" class="error-banner" role="alert">
          <p>{{ notes.error.value?.message || '无法读取笔记，请检查网络后重试。' }}</p>
          <button class="btn" @click="notes.refetch()">重新读取</button>
        </div>
        <template v-else>
          <p v-if="queryText" class="helper-note" role="status">
            {{
              notes.data.value?.vectorAvailable ? '全文与语义检索' : '全文检索'
            }}，显示最相关的最多 50 条笔记。结果为原文摘录。
          </p>
          <div v-if="!notes.data.value?.items.length" class="empty-state">
            <AppIcon name="kb" :size="40" />
            <h2>{{ queryText ? '没有匹配的笔记' : '留住你的第一个知识片段' }}</h2>
            <p>把刚解决的问题、项目经验或面试心得记录下来。</p>
          </div>
          <article v-for="note in notes.data.value?.items" :key="note.id" class="answer-card">
            <div class="section-heading">
              <h3 class="break-words">{{ note.title }}</h3>
              <span class="tag">{{ note.category }}</span>
            </div>
            <p class="prose-content break-words">{{ note.content }}</p>
            <div class="card-footer">
              <button class="btn text" :disabled="busy" @click="edit(note.id)">编辑笔记</button>
              <button class="btn" :disabled="busy" @click="exportNote(note.id)">导出</button>
              <button class="btn text" :disabled="busy" @click="deleting = note.id">
                删除笔记
              </button>
            </div>
            <div
              v-if="deleting === note.id"
              class="form-stack"
              role="group"
              aria-label="确认删除笔记"
            >
              <p>确认删除「{{ note.title }}」？删除后无法恢复，可以先导出。</p>
              <div class="card-footer">
                <button class="btn" :disabled="busy" @click="remove(note.id)">确认删除</button>
                <button class="btn" :disabled="busy" @click="deleting = null">保留笔记</button>
              </div>
            </div>
          </article>
          <nav v-if="!queryText && pageCount > 1" class="card-footer" aria-label="笔记分页">
            <button class="btn" :disabled="page <= 1 || notes.isFetching.value" @click="page--">
              上一页
            </button>
            <span>第 {{ page }} / {{ pageCount }} 页 · {{ notes.data.value?.total }} 条</span>
            <button
              class="btn"
              :disabled="page >= pageCount || notes.isFetching.value"
              @click="page++"
            >
              下一页
            </button>
          </nav>
        </template>
      </section>
      <form class="panel form-stack" style="align-self: start" @submit.prevent="save">
        <h2>{{ editing ? '编辑笔记' : '新建笔记' }}</h2>
        <label class="field"
          >标题<input
            v-model="title"
            :disabled="busy"
            required
            maxlength="120"
            placeholder="给这个知识片段起个名字"
        /></label>
        <label class="field"
          >分类<select v-model="category" :disabled="busy">
            <option v-for="item in noteCategories" :key="item">{{ item }}</option>
          </select></label
        >
        <label class="field"
          >内容<textarea
            v-model="content"
            :disabled="busy"
            required
            rows="9"
            maxlength="30000"
            placeholder="写下你想记住的内容…"
          />
        </label>
        <button class="btn primary" :disabled="busy">{{ busy ? '正在处理…' : '保存笔记' }}</button>
        <button v-if="editing" type="button" class="btn" :disabled="busy" @click="reset">
          取消编辑
        </button>
      </form>
    </div>
  </section>
</template>
