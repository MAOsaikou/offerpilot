<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from './AppIcon.vue'
import DiagnosisEvidenceList from './DiagnosisEvidenceList.vue'
import type { ResumeDiagnosisReport } from '@/shared/types/diagnosis'

const props = defineProps<{ report: ResumeDiagnosisReport }>()
type Finding = ResumeDiagnosisReport['findings'][number]
type Priority = Finding['priority']
const filter = ref<'ALL' | Priority>('ALL')
const priorities: { id: Priority; label: string }[] = [
  { id: 'HIGH', label: '优先处理' },
  { id: 'MEDIUM', label: '建议改进' },
  { id: 'LOW', label: '可选优化' },
]
const priority = { HIGH: '优先处理', MEDIUM: '建议改进', LOW: '可选优化' }
const order = { HIGH: 0, MEDIUM: 1, LOW: 2 }
const rules: Record<string, string> = {
  CONTRIBUTION: '个人贡献',
  EVIDENCE: '经历依据',
  RESULT: '成果与验证',
  CLARITY: '表达清晰',
  CONSISTENCY: '内容一致',
}
const sorted = computed(() =>
  [...props.report.findings]
    .sort((a, b) => order[a.priority] - order[b.priority])
    .map((finding, index) => ({ ...finding, number: index + 1 })),
)
const visible = computed(() =>
  sorted.value.filter((finding) => filter.value === 'ALL' || finding.priority === filter.value),
)
const counts = computed(() => ({
  HIGH: props.report.findings.filter((finding) => finding.priority === 'HIGH').length,
  MEDIUM: props.report.findings.filter((finding) => finding.priority === 'MEDIUM').length,
  LOW: props.report.findings.filter((finding) => finding.priority === 'LOW').length,
}))
const questions = computed(() =>
  props.report.findings.reduce((count, finding) => count + finding.questions.length, 0),
)
watch(
  () => props.report.reportId,
  () => {
    filter.value = 'ALL'
  },
)
function discussion(finding?: Finding) {
  return {
    path: '/chat',
    query: {
      mode: 'RESUME',
      resumeId: props.report.resumeId,
      resumeVersionId: props.report.versionId,
      diagnosisReportId: props.report.reportId,
      prompt: finding
        ? `请结合这份诊断报告，帮我落实「${rules[finding.ruleId] || finding.ruleId}」这条建议（${finding.findingId}）。建议内容：\n${finding.suggestion}\n请先问清缺失信息，再给出基于真实经历的修改方案。`
        : '请结合这份简历诊断，帮我逐条梳理可以怎样改进。先从优先处理的建议开始，不要编造经历或成果。',
    },
  }
}
</script>
<template>
  <article :key="report.reportId" class="diagnosis-report">
    <header class="report-overview">
      <div class="overview-top">
        <span class="completed-label"><AppIcon name="check" :size="16" /> 诊断完成</span>
        <span class="report-meta"
          >版本 {{ report.versionNo }} ·
          {{ new Date(report.createdAt).toLocaleString('zh-CN') }}</span
        >
      </div>
      <h2>你的简历改进清单</h2>
      <p class="overview-description">
        <template v-if="report.findings.length"
          >发现 {{ report.findings.length }} 项可改进的内容，从最值得投入的地方开始。</template
        >
        <template v-else>本次未发现明确的内容问题，可结合目标岗位继续核对。</template>
      </p>
      <dl class="report-metrics">
        <div class="metric priority-metric">
          <dt><AppIcon name="discover" :size="18" />优先处理</dt>
          <dd>{{ counts.HIGH }}<span>项建议</span></dd>
        </div>
        <div class="metric strength-metric">
          <dt><AppIcon name="check" :size="18" />值得保留</dt>
          <dd>{{ report.strengths.length }}<span>处亮点</span></dd>
        </div>
        <div class="metric question-metric">
          <dt><AppIcon name="chat" :size="18" />待补充信息</dt>
          <dd>{{ questions }}<span>个问题</span></dd>
        </div>
      </dl>
      <details class="report-summary">
        <summary>查看完整诊断总览</summary>
        <p>{{ report.summary }}</p>
      </details>
    </header>

    <div class="report-layout">
      <section class="improvements" aria-label="改进建议">
        <div class="section-title">
          <AppIcon name="resumes" />
          <h3>从这里开始改</h3>
          <span>{{ report.findings.length }} 项建议</span>
        </div>
        <div
          v-if="report.findings.length"
          class="priority-filters"
          role="group"
          aria-label="按优先级筛选建议"
        >
          <button type="button" :aria-pressed="filter === 'ALL'" @click="filter = 'ALL'">
            全部 <span>{{ report.findings.length }}</span>
          </button>
          <button
            v-for="item in priorities"
            :key="item.id"
            type="button"
            :aria-pressed="filter === item.id"
            @click="filter = item.id"
          >
            {{ item.label }} <span>{{ counts[item.id] }}</span>
          </button>
        </div>
        <p class="filter-status" role="status">
          {{
            report.findings.length
              ? `显示 ${visible.length} 项建议，按优先级排列`
              : '本次没有需要明确指出的内容问题。'
          }}
        </p>
        <div class="finding-list">
          <article
            v-for="finding in visible"
            :key="finding.findingId"
            class="finding-card"
            :class="`priority-${finding.priority.toLowerCase()}`"
          >
            <header class="finding-heading">
              <span class="finding-number">{{ String(finding.number).padStart(2, '0') }}</span>
              <h4>{{ rules[finding.ruleId] || finding.ruleId }}</h4>
              <span class="priority-badge">{{ priority[finding.priority] }}</span>
            </header>
            <div class="suggestion">
              <span class="field-label">{{
                finding.kind === 'NEEDS_CLARIFICATION' ? '先确认，再完善' : '可以这样改'
              }}</span>
              <p>{{ finding.suggestion }}</p>
            </div>
            <div v-if="finding.questions.length" class="questions">
              <h5><AppIcon name="chat" :size="16" />需要你补充</h5>
              <ul>
                <li v-for="question in finding.questions" :key="question">{{ question }}</li>
              </ul>
            </div>
            <details class="finding-evidence">
              <summary>为什么这样建议 · 原文依据</summary>
              <p class="finding-reason">{{ finding.reason }}</p>
              <DiagnosisEvidenceList :evidence="finding.evidence" />
            </details>
            <RouterLink class="finding-action" :to="discussion(finding)"
              ><AppIcon name="chat" :size="16" />帮我落实这条建议<AppIcon name="arrow" :size="16"
            /></RouterLink>
          </article>
          <div v-if="report.findings.length && !visible.length" class="empty-findings">
            <AppIcon name="check" :size="24" />
            <p>本次没有「{{ priority[filter as Priority] }}」建议。</p>
            <button class="btn" type="button" @click="filter = 'ALL'">查看全部建议</button>
          </div>
        </div>
      </section>

      <aside class="report-aside" aria-label="亮点与核对信息">
        <section v-if="report.checks.length" class="basics-card">
          <div class="section-title">
            <AppIcon name="resumes" />
            <h3>先核对基础信息</h3>
          </div>
          <ul>
            <li v-for="check in report.checks" :key="check.path">{{ check.message }}</li>
          </ul>
        </section>
        <section v-if="report.strengths.length" class="strengths-card">
          <div class="section-title">
            <AppIcon name="check" />
            <h3>这些亮点，继续保留</h3>
          </div>
          <p class="aside-description">修改时，别丢掉已有的好内容。</p>
          <div v-for="(strength, index) in report.strengths" :key="index" class="strength-item">
            <p>{{ strength.message }}</p>
            <details class="strength-evidence">
              <summary>原文依据 · {{ strength.evidence.length }} 处</summary>
              <DiagnosisEvidenceList :evidence="strength.evidence" />
            </details>
          </div>
        </section>
        <section class="discussion-card">
          <AppIcon name="spark" :size="24" />
          <h3>把建议变成下一步</h3>
          <p>带着这份报告继续讨论，从真实经历中补充细节，逐条完善表达。</p>
          <RouterLink class="btn primary" :to="discussion()"
            >带着报告继续讨论<AppIcon name="arrow" :size="16"
          /></RouterLink>
          <small>进入对话后确认并发送，不会自动修改简历。</small>
        </section>
      </aside>
    </div>
    <footer class="report-footnote">
      <details>
        <summary>诊断范围与生成信息</summary>
        <p v-for="limit in report.limitations" :key="limit">{{ limit }}</p>
        <p>分析模型：{{ report.model }} · 简历版本 {{ report.versionNo }}</p>
      </details>
    </footer>
  </article>
</template>
<style scoped>
.diagnosis-report {
  min-width: 0;
  overflow-wrap: anywhere;
  container-type: inline-size;
}
.report-overview {
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--primary) 3%, var(--card));
}
.overview-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.completed-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--success);
  font-size: 13px;
  font-weight: 600;
}
.report-meta,
.overview-description,
.aside-description,
.filter-status {
  color: var(--muted-foreground);
  font-size: 13px;
}
.report-overview h2 {
  font-size: 24px;
  line-height: 1.4;
  font-weight: 650;
  margin-bottom: 8px;
}
.overview-description {
  font-size: 14px;
}
.report-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 24px 0 16px;
}
.metric {
  padding: 16px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
}
.metric dt {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--muted-foreground);
}
.metric dd {
  margin: 8px 0 0;
  font-size: 32px;
  font-weight: 650;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.metric dd span {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--muted-foreground);
}
.priority-metric dd,
.priority-metric svg {
  color: var(--primary);
}
.strength-metric dd,
.strength-metric svg {
  color: var(--success);
}
.question-metric dd,
.question-metric svg {
  color: var(--warning);
}
summary {
  cursor: pointer;
  padding: 10px 0;
  font-size: 13px;
  color: var(--muted-foreground);
}
summary:hover {
  color: var(--primary);
}
.report-summary {
  border-top: 1px solid var(--border);
}
.report-summary p {
  padding-top: 8px;
  font-size: 14px;
  line-height: 1.85;
  max-width: 85ch;
  white-space: pre-line;
}
.report-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.9fr) minmax(240px, 1fr);
  gap: 24px;
  margin-top: 28px;
  align-items: start;
}
.improvements,
.report-aside {
  min-width: 0;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.section-title h3 {
  font-size: 16px;
  font-weight: 600;
}
.section-title > span {
  font-size: 12px;
  color: var(--muted-foreground);
  margin-left: auto;
}
.priority-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.priority-filters button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--card);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.priority-filters button[aria-pressed='true'] {
  background: var(--primary-tint);
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}
.priority-filters button:hover {
  border-color: var(--primary);
}
.priority-filters span {
  font-variant-numeric: tabular-nums;
}
.filter-status {
  margin: 12px 0;
  font-size: 12px;
}
.finding-list,
.report-aside {
  display: grid;
  gap: 16px;
}
.finding-card {
  --priority-color: var(--muted-foreground);
  border: 1px solid var(--border);
  border-left: 3px solid var(--priority-color);
  border-radius: var(--radius-control);
  padding: 20px;
  background: var(--card);
}
.priority-high {
  --priority-color: var(--primary);
}
.priority-medium {
  --priority-color: var(--warning);
}
.finding-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.finding-number {
  font-size: 15px;
  font-weight: 650;
  color: var(--priority-color);
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
}
.finding-heading h4 {
  font-size: 16px;
  font-weight: 600;
}
.priority-badge {
  font-size: 11px;
  padding: 2px 8px;
  margin-left: auto;
  border-radius: var(--radius-tag);
  color: var(--priority-color);
  background: color-mix(in srgb, var(--priority-color) 9%, var(--card));
}
.field-label {
  color: var(--muted-foreground);
  font-size: 12px;
}
.suggestion p {
  margin-top: 4px;
  font-size: 15px;
  line-height: 1.85;
  white-space: pre-line;
}
.questions {
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: var(--radius-control);
  background: var(--background);
}
.questions h5 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}
.questions ul,
.basics-card ul {
  list-style: disc;
  padding-left: 18px;
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.8;
}
.questions li + li,
.basics-card li + li {
  margin-top: 8px;
}
.finding-evidence {
  margin-top: 16px;
  border-top: 1px solid var(--border);
}
.finding-reason {
  margin: 4px 0 12px;
  font-size: 14px;
  line-height: 1.8;
}
.finding-action {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  margin-top: 8px;
  color: var(--primary);
  font-size: 13px;
  font-weight: 550;
}
.finding-action:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
}
.strengths-card,
.basics-card,
.discussion-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  padding: 20px;
}
.strengths-card {
  background: color-mix(in srgb, var(--success) 3%, var(--card));
}
.strengths-card .section-title > svg {
  color: var(--success);
}
.strength-item {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}
.strength-item p {
  font-size: 14px;
  line-height: 1.8;
}
.basics-card {
  background: color-mix(in srgb, var(--warning) 5%, var(--card));
}
.basics-card .section-title > svg {
  color: var(--warning);
}
.discussion-card {
  background: var(--background);
}
.discussion-card > svg {
  color: var(--primary);
  margin-bottom: 12px;
}
.discussion-card h3 {
  font-size: 16px;
  font-weight: 600;
}
.discussion-card p {
  font-size: 14px;
  line-height: 1.8;
  margin: 8px 0 16px;
}
.discussion-card .btn {
  width: 100%;
  white-space: normal;
  padding: 10px;
}
.discussion-card small {
  display: block;
  margin-top: 12px;
  font-size: 12px;
  color: var(--muted-foreground);
}
.empty-findings {
  padding: 24px;
  display: grid;
  justify-items: start;
  gap: 12px;
  background: var(--background);
  border-radius: var(--radius-control);
}
.empty-findings > svg {
  color: var(--success);
}
.report-footnote {
  border-top: 1px solid var(--border);
  margin-top: 24px;
  padding-top: 8px;
  font-size: 12px;
  color: var(--muted-foreground);
}
.report-footnote p {
  margin-top: 8px;
}
@container (max-width: 760px) {
  .report-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
@container (max-width: 440px) {
  .report-overview,
  .finding-card,
  .strengths-card,
  .basics-card,
  .discussion-card {
    padding: 16px;
  }
  .report-overview h2 {
    font-size: 21px;
  }
  .report-metrics {
    gap: 6px;
    margin-top: 16px;
  }
  .metric {
    padding: 10px 8px;
  }
  .metric dt {
    font-size: 11px;
    gap: 4px;
  }
  .metric dt svg {
    display: none;
  }
  .metric dd {
    font-size: 26px;
  }
  .metric dd span {
    display: block;
    margin: 6px 0 0;
    font-size: 11px;
  }
  .report-meta {
    font-size: 11px;
  }
}
</style>
