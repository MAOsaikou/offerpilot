<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { listImportedJobs, parseJobImport, saveJobImport } from '@/shared/api/jobs'
import type { JobImportFields, JobImportParse } from '@/shared/types/jobs'

const router = useRouter()
const queryClient = useQueryClient()
const open = ref(false)
const mode = ref<'text' | 'url'>('text')
const text = ref('')
const url = ref('')
const parsing = ref(false)
const saving = ref(false)
const error = ref('')
const parsed = ref<JobImportParse | null>(null)
const form = ref<JobImportFields | null>(null)

const imported = useQuery({
  queryKey: ['jobs-imported'],
  queryFn: ({ signal }) => listImportedJobs(signal),
  retry: false,
})
const canParse = computed(() =>
  mode.value === 'text'
    ? text.value.trim().length >= 30
    : /^https?:\/\/\S+$/i.test(url.value.trim()),
)
const canSave = computed(
  () =>
    !!form.value?.title.trim() &&
    !!(form.value.description.trim() || form.value.requirement.trim()) &&
    !saving.value,
)

async function parse() {
  if (!canParse.value || parsing.value) return
  parsing.value = true
  error.value = ''
  try {
    const result = await parseJobImport(
      mode.value === 'text' ? { text: text.value.trim() } : { url: url.value.trim() },
    )
    parsed.value = result
    const { parser: _parser, ...fields } = result.draft
    form.value = { ...fields, skills: [...fields.skills], url: result.url }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    parsing.value = false
  }
}

async function save() {
  if (!form.value || !canSave.value) return
  saving.value = true
  error.value = ''
  try {
    const job = await saveJobImport(form.value)
    void queryClient.invalidateQueries({ queryKey: ['jobs-imported'] })
    await router.push('/jobs/' + job.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

function restart() {
  parsed.value = null
  form.value = null
  error.value = ''
}
</script>

<template>
  <section class="panel form-stack job-import" aria-labelledby="job-import-title">
    <div class="section-heading">
      <h2 id="job-import-title">导入岗位</h2>
      <button type="button" class="btn" :aria-expanded="open" @click="open = !open">
        {{ open ? '收起' : '粘贴 JD 或网址' }}
      </button>
    </div>
    <p class="subtle">
      在其他招聘站看到的岗位，搜索里没有？粘贴岗位描述或填写网址。核对无误后保存，就能像搜索结果一样生成匹配报告、加入看板。导入的岗位只有你自己可见。
    </p>
    <template v-if="open">
      <div v-if="!form" class="form-stack">
        <div class="mode-switch" role="radiogroup" aria-label="导入方式">
          <label><input v-model="mode" type="radio" value="text" /> 粘贴文本</label>
          <label><input v-model="mode" type="radio" value="url" /> 岗位网址</label>
        </div>
        <label v-if="mode === 'text'" class="field"
          >岗位描述<textarea
            v-model="text"
            rows="8"
            maxlength="20000"
            placeholder="从招聘页复制岗位名称、职责、要求等内容（至少 30 字）"
          />
        </label>
        <template v-else>
          <label class="field"
            >岗位网址<input
              v-model="url"
              type="url"
              maxlength="2000"
              placeholder="https://jobs.example.com/position/123"
          /></label>
          <p class="subtle">
            只能读取公开网页。需要登录或依靠浏览器脚本渲染的招聘站读不到正文，这种情况请改用粘贴文本。
          </p>
        </template>
        <div class="card-footer">
          <button
            type="button"
            class="btn primary"
            :disabled="!canParse || parsing"
            :aria-busy="parsing"
            @click="parse"
          >
            {{ parsing ? '正在解析…' : '解析岗位' }}
          </button>
        </div>
      </div>
      <form v-else class="form-stack" @submit.prevent="save">
        <p class="helper-note" role="status">
          {{
            parsed?.draft.parser === 'model'
              ? 'AI 已从原文摘录下列字段，原文中找不到的内容都已丢弃。请核对后保存。'
              : 'AI 暂不可用，下列内容是按「职责 / 要求」等标题拆分的原文。请核对后保存。'
          }}
        </p>
        <p v-if="parsed?.securityNotices?.length" class="security-note" role="alert">
          原文里有疑似写给 AI 的指令（{{
            parsed.securityNotices.join('、')
          }}）。系统只把它当作岗位文本，不会执行；请核对字段后再保存。
        </p>
        <div class="import-grid">
          <label class="field"
            >岗位名称<input v-model="form.title" required maxlength="100"
          /></label>
          <label class="field"
            >公司<input v-model="form.company" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >城市<input v-model="form.locations" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >部门<input v-model="form.department" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >招聘类型<select v-model="form.nature">
              <option value="campus">校招</option>
              <option value="intern">实习</option>
              <option value="social">社招</option>
            </select></label
          >
        </div>
        <label class="field"
          >岗位职责<textarea v-model="form.description" rows="6" maxlength="30000" />
        </label>
        <label class="field"
          >任职要求<textarea v-model="form.requirement" rows="6" maxlength="30000" />
        </label>
        <p v-if="form.skills.length" class="subtle">
          原文提到的技能：<span v-for="skill in form.skills" :key="skill" class="tag">{{
            skill
          }}</span>
        </p>
        <p v-if="form.url" class="subtle">来源网址：{{ form.url }}</p>
        <p v-if="!form.description.trim() && !form.requirement.trim()" class="error-banner">
          职责和要求至少保留一项，匹配分析需要依据。
        </p>
        <div class="card-footer">
          <button type="button" class="btn" :disabled="saving" @click="restart">重新导入</button>
          <button class="btn primary" :disabled="!canSave" :aria-busy="saving">
            {{ saving ? '正在保存…' : '保存并查看匹配' }}
          </button>
        </div>
      </form>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    </template>
    <div v-if="imported.data.value?.length" class="imported-list">
      <h3>我导入的岗位</h3>
      <ul>
        <li v-for="job in imported.data.value" :key="job.id">
          <RouterLink :to="'/jobs/' + job.id">{{ job.title }}</RouterLink>
          <span class="subtle"
            >{{ job.company || '公司未写明' }} · {{ job.locations || '城市未写明' }}</span
          >
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.job-import {
  overflow-wrap: anywhere;
}
.mode-switch {
  display: flex;
  gap: 16px;
}
.security-note {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid var(--warning);
  color: var(--warning);
}
.import-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.imported-list ul {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
}
.imported-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}
.tag + .tag {
  margin-left: 6px;
}
</style>
