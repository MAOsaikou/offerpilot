<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { listResumes, uploadResume } from '@/shared/api/resumes'
import { resumeStatus } from '@/shared/types/resume'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const error = ref('')
const importing = ref(false)
const page = ref(1)
const cache = useQueryClient()
const server = useQuery({
  queryKey: ['resumes', page],
  queryFn: ({ signal }) => listResumes(page.value, signal),
  refetchInterval: (query) =>
    query.state.error
      ? false
      : query.state.data?.items.some((r) => ['QUEUED', 'PARSING'].includes(r.parseStatus))
        ? 2000
        : false,
})
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
  importing.value = true
  try {
    const uploaded = await uploadResume(file)
    void cache.invalidateQueries({ queryKey: ['resumes'] })
    await router.push('/resumes/' + uploaded.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '上传失败，请重试。'
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
      description="整理真实经历，为不同的机会准备不同的简历。"
      ><button class="btn" :disabled="importing" @click="fileInput?.click()">
        {{ importing ? '上传中…' : '上传并解析' }}</button
      ><button class="btn primary" :disabled="importing" @click="create()">
        ＋ 本地草稿
      </button></PageHeading
    ><input
      ref="fileInput"
      type="file"
      accept=".pdf,.docx,.png,.jpg,.jpeg,.txt,.md"
      hidden
      aria-label="上传简历"
      @change="importFile"
    />
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <div class="helper-note">
      支持 PDF、DOCX、PNG、JPG、TXT / Markdown，单文件不超过 10MB，PDF 最多 10 页。
      点击上传后，原文件保存到服务端，内容将发送给配置的模型用于解析。请核对解析结果后保存。
      <RouterLink to="/settings/models">切换解析模型</RouterLink>
    </div>
    <p v-if="server.isPending.value" role="status">正在加载服务端简历…</p>
    <div v-else-if="server.isError.value" class="error-banner" role="alert">
      {{ server.error.value?.message }}
      <button class="btn" @click="server.refetch()">重新加载</button>
    </div>
    <template v-else>
      <h2>
        已上传的简历 <span class="subtle">{{ server.data.value?.total ?? 0 }} 份</span>
      </h2>
      <div v-if="server.data.value?.items.length" class="card-grid">
        <RouterLink
          v-for="item in server.data.value.items"
          :key="item.id"
          :to="'/resumes/' + item.id"
          class="panel job-card"
        >
          <div class="card-top">
            <AppIcon name="resumes" /><span class="tag">{{ resumeStatus[item.parseStatus] }}</span>
          </div>
          <h2>{{ item.title }}</h2>
          <p class="subtle">{{ item.fileName }}</p>
          <p v-if="item.parseError" class="error-banner">{{ item.parseError }}</p>
          <progress
            v-if="item.parseStatus === 'PARSING' || item.parseStatus === 'QUEUED'"
            :value="item.parseProgress"
            max="100"
            aria-label="解析进度"
          />
          <div class="card-footer">
            <span>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</span
            ><span>查看</span>
          </div>
        </RouterLink>
      </div>
      <div v-else class="panel empty-state">
        <h3>上传你的第一份简历</h3>
        <p>解析后可编辑个人信息、教育、项目、实习与技能，并保存为新版本。</p>
        <button class="btn primary" :disabled="importing" @click="fileInput?.click()">
          选择文件
        </button>
      </div>
      <div v-if="(server.data.value?.total ?? 0) > 20" class="card-footer">
        <button class="btn" :disabled="page === 1" @click="page--">上一页</button
        ><span>第 {{ page }} 页</span>
        <button
          class="btn"
          :disabled="page * 20 >= (server.data.value?.total ?? 0)"
          @click="page++"
        >
          下一页
        </button>
      </div>
    </template>
    <h2>本地草稿</h2>
    <p class="subtle">原有草稿保留在此浏览器，未上传。可继续编辑与导出。</p>
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
          ><span>编辑</span>
        </div></RouterLink
      >
    </div>
    <div v-else class="panel empty-state">
      <AppIcon name="resumes" :size="44" />
      <h2>你的故事，值得一份好简历</h2>
      <p>也可以先创建本地草稿，整理经历后再上传解析。</p>
      <button class="btn primary" @click="create()">创建第一份简历</button>
    </div>
  </section>
</template>
