<script setup lang="ts">
import { formatDisplay, language, translate as uiText } from '@/shared/utils/language'

import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from './AppIcon.vue'
import DiagnosisEvidenceList from './DiagnosisEvidenceList.vue'
import type { ResumeDiagnosisReport } from '@/shared/types/diagnosis'

const props = defineProps<{ report: ResumeDiagnosisReport }>()
type Finding = ResumeDiagnosisReport['findings'][number]
type Priority = Finding['priority']
const filter = ref<'ALL' | Priority>('ALL')
const priorities = computed<{ id: Priority; label: string }[]>(() => [
  { id: 'HIGH', label: uiText("High priority") },
  { id: 'MEDIUM', label: uiText("Recommended improvement") },
  { id: 'LOW', label: uiText("Optional improvement") },
])
const priority = computed(() => ({ HIGH: uiText("High priority"), MEDIUM: uiText("Recommended improvement"), LOW: uiText("Optional improvement") }))
const order = { HIGH: 0, MEDIUM: 1, LOW: 2 }
const rules = computed<Record<string, string>>(() => ({
  CONTRIBUTION: uiText("Personal contribution"),
  EVIDENCE: uiText("Experience evidence"),
  RESULT: uiText("Results and verification"),
  CLARITY: uiText("Clarity"),
  CONSISTENCY: uiText("Consistency"),
}))
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
        ? uiText("Help me act on the diagnosis suggestion “{0}” ({1}). Suggestion: {2}. Ask about missing information first, then propose edits based on real experience.", [rules.value[finding.ruleId] || finding.ruleId, finding.findingId, finding.suggestion])
        : uiText("Help me work through this resume diagnosis, starting with high-priority suggestions. Do not invent experience or results."),
    },
  }
}
</script>
<template>
  <article :key="report.reportId" class="diagnosis-report">
    <header class="report-overview">
      <div class="overview-top">
        <span class="completed-label"><AppIcon name="check" :size="16" /> {{ uiText("Diagnosis complete") }}</span>
        <span class="report-meta"
          >{{ uiText("Version {0} · {1} #2", [report.versionNo, new Date(report.createdAt).toLocaleString(language)]) }}</span
        >
      </div>
      <h2>{{ uiText("Your resume improvement checklist") }}</h2>
      <p class="overview-description">
        <template v-if="report.findings.length"
          >{{ uiText("Found {0} opportunities to improve. Start where your effort will matter most.", [report.findings.length]) }}</template
        >
        <template v-else>{{ uiText("No clear content issues found. Continue reviewing against your target role.") }}</template>
      </p>
      <dl class="report-metrics">
        <div class="metric priority-metric">
          <dt><AppIcon name="discover" :size="18" />{{ uiText("High priority") }}</dt>
          <dd>{{ counts.HIGH }}<span>{{ uiText("suggestions") }}</span></dd>
        </div>
        <div class="metric strength-metric">
          <dt><AppIcon name="check" :size="18" />{{ uiText("Worth keeping") }}</dt>
          <dd>{{ formatDisplay(report.strengths.length) }}<span>{{ uiText("strengths") }}</span></dd>
        </div>
        <div class="metric question-metric">
          <dt><AppIcon name="chat" :size="18" />{{ uiText("Missing information") }}</dt>
          <dd>{{ questions }}<span>{{ uiText("questions") }}</span></dd>
        </div>
      </dl>
      <details class="report-summary">
        <summary>{{ uiText("View full diagnosis overview") }}</summary>
        <p>{{ report.summary }}</p>
      </details>
    </header>

    <div class="report-layout">
      <section class="improvements" :aria-label="uiText('Suggestions for improvement')">
        <div class="section-title">
          <AppIcon name="resumes" />
          <h3>{{ uiText("Start improving here") }}</h3>
          <span>{{ uiText("{0} suggestions #2", [report.findings.length]) }}</span>
        </div>
        <div
          v-if="report.findings.length"
          class="priority-filters"
          role="group"
          :aria-label="uiText('Filter suggestions by priority')"
        >
          <button type="button" :aria-pressed="filter === 'ALL'" @click="filter = 'ALL'"> {{ uiText("All") }} <span>{{ formatDisplay(report.findings.length) }}</span>
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
              ? uiText("Showing {0} suggestions in priority order", [visible.length])
              : uiText("No clear content issues to highlight this time.")
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
                finding.kind === 'NEEDS_CLARIFICATION' ? uiText("Confirm first, then improve") : uiText("Suggested edit")
              }}</span>
              <p>{{ finding.suggestion }}</p>
            </div>
            <div v-if="finding.questions.length" class="questions">
              <h5><AppIcon name="chat" :size="16" />{{ uiText("Details needed from you") }}</h5>
              <ul>
                <li v-for="question in finding.questions" :key="question">{{ question }}</li>
              </ul>
            </div>
            <details class="finding-evidence">
              <summary>{{ uiText("Why this is suggested · Original evidence") }}</summary>
              <p class="finding-reason">{{ finding.reason }}</p>
              <DiagnosisEvidenceList :evidence="finding.evidence" />
            </details>
            <RouterLink class="finding-action" :to="discussion(finding)"
              ><AppIcon name="chat" :size="16" />{{ uiText("Help me apply this suggestion") }}<AppIcon name="arrow" :size="16"
            /></RouterLink>
          </article>
          <div v-if="report.findings.length && !visible.length" class="empty-findings">
            <AppIcon name="check" :size="24" />
            <p>{{ uiText("No “{0}” suggestions this time.", [priority[filter as Priority]]) }}</p>
            <button class="btn" type="button" @click="filter = 'ALL'">{{ uiText("View all suggestions") }}</button>
          </div>
        </div>
      </section>

      <aside class="report-aside" :aria-label="uiText('Strengths and details to verify')">
        <section v-if="report.checks.length" class="basics-card">
          <div class="section-title">
            <AppIcon name="resumes" />
            <h3>{{ uiText("Check basic information first") }}</h3>
          </div>
          <ul>
            <li v-for="check in report.checks" :key="check.path">{{ check.message }}</li>
          </ul>
        </section>
        <section v-if="report.strengths.length" class="strengths-card">
          <div class="section-title">
            <AppIcon name="check" />
            <h3>{{ uiText("Keep these strengths") }}</h3>
          </div>
          <p class="aside-description">{{ uiText("Preserve what already works as you edit.") }}</p>
          <div v-for="(strength, index) in report.strengths" :key="index" class="strength-item">
            <p>{{ strength.message }}</p>
            <details class="strength-evidence">
              <summary>{{ uiText("Original evidence · {0} excerpts", [strength.evidence.length]) }}</summary>
              <DiagnosisEvidenceList :evidence="strength.evidence" />
            </details>
          </div>
        </section>
        <section class="discussion-card">
          <AppIcon name="spark" :size="24" />
          <h3>{{ uiText("Turn suggestions into a next step") }}</h3>
          <p>{{ uiText("Discuss this report, add details from real experience and improve each description.") }}</p>
          <RouterLink class="btn primary" :to="discussion()"
            >{{ uiText("Discuss this report") }}<AppIcon name="arrow" :size="16"
          /></RouterLink>
          <small>{{ uiText("Review and send in chat. Your resume is not changed automatically.") }}</small>
        </section>
      </aside>
    </div>
    <footer class="report-footnote">
      <details>
        <summary>{{ uiText("Diagnosis scope and generation details") }}</summary>
        <p v-for="limit in report.limitations" :key="limit">{{ limit }}</p>
        <p>{{ uiText("Analysis model: {0} · Resume version {1}", [report.model, report.versionNo]) }}</p>
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
