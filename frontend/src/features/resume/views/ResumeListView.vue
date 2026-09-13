<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const importing = ref(false)
async function create(
  content = '# 个人信息\n姓名：\n邮箱：\n求职方向：\n\n# 教育经历\n学校 / 专业 / 毕业时间\n\n# 项目经历\n项目名称：\n我的职责：\n采取的行动：\n实际成果：\n\n# 专业技能\n',
  title = '我的求职简历',
) {
  const id = crypto.randomUUID()
  workspace.state.resumes.unshift({
    id,
    title,
    content,
    updated: new Date().toLocaleString('zh-CN'),
    versions: [],
  })
  workspace.notify('创建了简历：' + title)
  await router.push('/resumes/' + id)
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  error.value = ''
  if (!/\.(txt|md)$/i.test(file.name) || file.size > 1024 * 1024) {
    error.value = '请选择 1MB 以内的 TXT 或 Markdown 文件。PDF / DOCX 解析需要后端支持。'
    input.value = ''
    return
  }
  importing.value = true
  try {
    await create(await file.text(), file.name.replace(/\.[^.]+$/, ''))
  } catch {
    error.value = '文件读取失败，请重新选择。'
  } finally {
    importing.value = false
    input.value = ''
  }
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="让每一段经历，有力表达"
      eyebrow="RESUME STUDIO"
      description="整理真实经历，为不同的机会准备不同的简历。"
      ><button class="btn" :disabled="importing" @click="fileInput?.click()">
        {{ importing ? '读取中…' : '导入文本' }}</button
      ><button class="btn primary" @click="create()">＋ 新建简历</button></PageHeading
    ><input
      ref="fileInput"
      type="file"
      accept=".txt,.md"
      hidden
      aria-label="导入简历文本"
      @change="importFile"
    />
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <div class="helper-note">
      本地编辑 · 自动保存草稿 · 手动保存版本 · 导出 Markdown / 打印
      PDF。文件保留在此浏览器，不会上传。
    </div>
    <div v-if="workspace.state.resumes.length" class="card-grid">
      <RouterLink
        v-for="resume in workspace.state.resumes"
        :key="resume.id"
        :to="'/resumes/' + resume.id"
        class="panel job-card"
        ><div class="card-top">
          <span class="stat-icon"><AppIcon name="resumes" /></span
          ><span class="tag">{{ resume.versions.length }} 个版本</span>
        </div>
        <h2>{{ resume.title }}</h2>
        <p class="subtle">{{ resume.content.slice(0, 100) }}…</p>
        <div class="card-footer">
          <span class="subtle">{{ resume.updated }}</span
          ><span>编辑 →</span>
        </div></RouterLink
      >
    </div>
    <div v-else class="panel empty-state">
      <AppIcon name="resumes" :size="44" />
      <h2>你的故事，值得一份好简历</h2>
      <p>从结构化提纲开始，或导入已有的 TXT / Markdown 内容。先把经历写下来，再逐步打磨。</p>
      <button class="btn primary" @click="create()">创建第一份简历 →</button>
    </div>
  </section>
</template>
