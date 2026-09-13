<script setup lang="ts">
import { computed, ref } from 'vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
const workspace = useWorkspace()
const search = ref('')
const title = ref('')
const content = ref('')
const category = ref('面试复盘')
const editing = ref<string | null>(null)
const message = ref('')
const filtered = computed(() =>
  workspace.state.notes.filter((n) =>
    (n.title + ' ' + n.content + ' ' + n.category)
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
)
function save() {
  if (!title.value.trim() || !content.value.trim()) return
  const existing = workspace.state.notes.find((n) => n.id === editing.value)
  if (existing) {
    existing.title = title.value.trim()
    existing.content = content.value.trim()
    existing.category = category.value
  } else {
    workspace.state.notes.unshift({
      id: crypto.randomUUID(),
      title: title.value.trim(),
      content: content.value.trim(),
      category: category.value,
    })
  }
  workspace.notify('保存了知识笔记：' + title.value.trim())
  message.value = '笔记已保存在本机'
  reset()
}
function reset() {
  editing.value = null
  title.value = ''
  content.value = ''
}
function edit(id: string) {
  const note = workspace.state.notes.find((n) => n.id === id)
  if (!note) return
  editing.value = id
  title.value = note.title
  content.value = note.content
  category.value = note.category
  message.value = ''
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="把经验，存成下一次的底气"
      eyebrow="PERSONAL KNOWLEDGE"
      description="记录知识、项目亮点和面试复盘，让成长有迹可循。"
    />
    <div class="helper-note">这里是本地知识笔记；内容未上传，也尚未接入后端 RAG 检索。</div>
    <p v-if="message" class="success-banner" role="status">{{ message }}</p>
    <div class="split-content">
      <section class="panel form-stack">
        <input
          v-model="search"
          class="input"
          aria-label="搜索知识笔记"
          placeholder="搜索标题、内容或分类…"
        />
        <div v-if="!filtered.length" class="empty-state">
          <AppIcon name="kb" :size="40" />
          <h2>{{ search ? '没有匹配的笔记' : '留住你的第一个知识片段' }}</h2>
          <p>把刚解决的问题、项目经验或面试心得记录下来。</p>
        </div>
        <article v-for="note in filtered" :key="note.id" class="answer-card">
          <div class="section-heading">
            <h3>{{ note.title }}</h3>
            <span class="tag">{{ note.category }}</span>
          </div>
          <p class="prose-content">{{ note.content }}</p>
          <div class="card-footer">
            <button class="btn text" @click="edit(note.id)">编辑笔记</button
            ><button class="btn" @click="downloadText(note.title + '.md', note.content)">
              导出
            </button>
          </div>
        </article>
      </section>
      <form class="panel form-stack" style="align-self: start" @submit.prevent="save">
        <h2>{{ editing ? '编辑笔记' : '新建笔记' }}</h2>
        <label class="field"
          >标题<input
            v-model="title"
            required
            maxlength="120"
            placeholder="给这个知识片段起个名字" /></label
        ><label class="field"
          >分类<select v-model="category">
            <option>面试复盘</option>
            <option>技术积累</option>
            <option>项目经历</option>
            <option>求职心得</option>
          </select></label
        ><label class="field"
          >内容<textarea
            v-model="content"
            required
            rows="9"
            maxlength="30000"
            placeholder="写下你想记住的内容…"
          /></label
        ><button class="btn primary">保存笔记</button
        ><button v-if="editing" type="button" class="btn" @click="reset">取消编辑</button>
      </form>
    </div>
  </section>
</template>
