<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import MarkdownView from '@/shared/components/MarkdownView.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
const workspace = useWorkspace()
const route = useRoute()
const resume = computed(() => workspace.state.resumes.find((r) => r.id === route.params.id))
const tab = ref('编辑')
const message = ref('')
function saveVersion() {
  if (!resume.value) return
  resume.value.versions.unshift({
    content: resume.value.content,
    date: new Date().toLocaleString('zh-CN'),
  })
  resume.value.updated = new Date().toLocaleString('zh-CN')
  message.value = '已保存当前版本'
  workspace.notify('保存了简历版本：' + resume.value.title)
}
function touched() {
  if (resume.value) resume.value.updated = new Date().toLocaleString('zh-CN')
  message.value = ''
}
function restore(content: string) {
  if (!resume.value) return
  saveVersion()
  resume.value.content = content
  message.value = '已恢复所选版本，恢复前内容也已保存。'
  tab.value = '编辑'
}
function printResume() {
  window.print()
}
</script>
<template>
  <section v-if="resume" class="page-stack">
    <PageHeading :title="resume.title" description="每一次修改，都让你的优势更清晰。"
      ><RouterLink to="/resumes" class="btn">返回</RouterLink
      ><button class="btn" @click="downloadText(resume.title + '.md', resume.content)">
        导出 Markdown</button
      ><button class="btn primary" @click="printResume">打印 / PDF</button></PageHeading
    >
    <div class="split-content">
      <section class="panel form-stack no-print">
        <div class="tabs">
          <button
            v-for="t in ['编辑', '版本']"
            :key="t"
            :class="{ selected: tab === t }"
            @click="tab = t"
          >
            {{ t }}
          </button>
        </div>
        <p v-if="message" class="success-banner" role="status">{{ message }}</p>
        <template v-if="tab === '编辑'"
          ><label class="field"
            >简历名称<input v-model="resume.title" maxlength="100" @input="touched" /></label
          ><label class="field"
            >正文（Markdown）<textarea
              v-model="resume.content"
              rows="20"
              maxlength="100000"
              @input="touched"
            />
          </label>
          <div class="card-footer">
            <span class="subtle">草稿自动保存在本机</span
            ><button class="btn primary" @click="saveVersion">保存版本</button>
          </div>
          <RouterLink
            :to="{
              path: '/chat',
              query: { mode: 'RESUME', prompt: '请指导我如何优化简历的项目经历表达，不编造事实。' },
            }"
            class="btn"
            >向 AI 请教简历写法</RouterLink
          ></template
        ><template v-else
          ><p v-if="!resume.versions.length" class="subtle">
            还没有版本。编辑后点击「保存版本」留下一份快照。
          </p>
          <div v-for="(version, index) in resume.versions" :key="index" class="list-row">
            <div>
              <h3>版本 {{ resume.versions.length - index }}</h3>
              <small>{{ version.date }}</small>
            </div>
            <button class="btn" @click="restore(version.content)">恢复</button>
          </div></template
        >
      </section>
      <article class="panel print-resume">
        <h1>{{ resume.title }}</h1>
        <MarkdownView :text="resume.content" />
      </article>
    </div>
  </section>
  <div v-else class="panel empty-state">
    <h2>未找到这份简历</h2>
    <RouterLink to="/resumes" class="btn">返回简历中心</RouterLink>
  </div>
</template>
