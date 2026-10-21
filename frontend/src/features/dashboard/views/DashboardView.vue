<script setup lang="ts">
import { formatDisplay, formatDate, language, translateLabel, translate as uiText } from '@/shared/utils/language'

import { computed, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import AppIcon from '@/shared/components/AppIcon.vue'
import { listResumes } from '@/shared/api/resumes'
import { useWorkspace, learningTasks } from '@/shared/stores/workspace'
import { useBoard } from '@/shared/composables/useBoard'
import { applicationStages } from '@/shared/types/board'
import RouteStrip from '../components/RouteStrip.vue'
import PendingAgentWrites from '@/shared/components/PendingAgentWrites.vue'
const workspace = useWorkspace()
const board = useBoard()
const router = useRouter()
const prompt = ref('')
const resumes = useQuery({
  queryKey: ['resumes', 1],
  queryFn: ({ signal }) => listResumes(1, signal),
})
const dueToday = new Intl.DateTimeFormat('sv-SE').format(new Date())
const dueActions = computed(() =>
  board.applications.value
    .filter(
      (app) =>
        app.status !== 'CLOSED' &&
        app.nextAction?.trim() &&
        app.nextActionAt &&
        app.nextActionAt <= dueToday,
    )
    .sort((a, b) => a.nextActionAt!.localeCompare(b.nextActionAt!) || a.id.localeCompare(b.id))
    .slice(0, 5),
)
const savedJobs = computed(() => board.applications.value.map((a) => a.job).slice(0, 4))
const stops = computed(() =>
  Object.entries(applicationStages).map(([status, stage]) => ({
    stage,
    count: board.applications.value.filter((a) => a.status === status).length,
  })),
)
const resumeCount = computed(() =>
  resumes.isError.value
    ? undefined
    : workspace.state.resumes.length + (resumes.data.value?.total ?? 0),
)
const nextTask = computed(() => learningTasks.find((t) => !workspace.state.tasks.includes(t)))
const progress = computed(() => (workspace.state.tasks.length / learningTasks.length) * 100)
const today = computed(() => new Date().toLocaleDateString(language.value, {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}))
const suggestions = computed(() => [
  { label: uiText("Explore career direction"), text: uiText("Help me find suitable career directions") },
  { label: uiText("Improve project experience"), text: uiText("Help me improve the project experience on my resume") },
  { label: uiText("Prepare for your next interview"), text: uiText("Help me prepare for a technical interview") },
])
const toolkit = computed(() => [
  { to: '/resumes', icon: 'resumes', title: uiText("Resume"), desc: uiText("Upload and parse a resume, or continue a local draft") },
  { to: '/interviews', icon: 'interviews', title: uiText("Mock interview"), desc: uiText("Practice expressing yourself, starting with an introduction") },
  { to: '/kb', icon: 'kb', title: uiText("Knowledge library"), desc: uiText("Keep a record of every reflection") },
])
function send() {
  if (prompt.value.trim())
    void router.push({ path: '/chat', query: { prompt: prompt.value.trim() } })
}
</script>
<template>
  <section class="page-stack dashboard">
    <header class="greeting">
      <h1>{{ uiText("Hello, {0}", [workspace.state.name]) }}</h1>
      <p class="date-label">{{ today }}</p>
    </header>

    <div class="ask-block">
      <form class="ask" @submit.prevent="send">
        <AppIcon name="spark" :size="18" />
        <input
          v-model="prompt"
          maxlength="4000"
          :aria-label="uiText('Tell AI about your career goals')"
          :placeholder="uiText('Ask OfferPilot: what would you like to work on today?')"
          required
        />
        <button class="btn primary" :disabled="!prompt.trim()">{{ uiText("Ask") }}</button>
      </form>
      <div class="chips">
        <button
          v-for="(item, index) in suggestions"
          :key="item.label"
          type="button"
          class="chip stagger-in"
          :style="{ '--i': index + 2 }"
          @click="prompt = item.text"
        >
          {{ item.label }}
        </button>
      </div>
    </div>

    <PendingAgentWrites />
    <section class="route-section">
      <div class="section-heading">
        <h2>{{ uiText("Your journey") }}</h2>
        <RouterLink to="/board">{{ uiText("Open board #2") }}</RouterLink>
      </div>
      <p v-if="board.query.isError.value" class="error-banner" role="alert">
        {{ formatDisplay(board.query.error.value?.message)
        }}<button class="btn" @click="board.query.refetch()">{{ uiText("Retry loading") }}</button>
      </p>
      <RouteStrip v-else :stops="stops" :loading="board.query.isPending.value" />
    </section>

    <section class="panel next-panel">
      <div class="section-heading">
        <h2>{{ uiText("Coming up") }}</h2>
        <RouterLink to="/board">{{ uiText("View all opportunities") }}</RouterLink>
      </div>
      <p v-if="board.query.isPending.value" role="status">{{ uiText("Loading actions…") }}</p>
      <p v-else-if="board.query.isError.value" role="alert">{{ uiText("Could not load actions. Please retry above.") }}</p>
      <template v-else>
        <RouterLink
          v-for="app in dueActions"
          :key="app.id"
          :to="{ path: '/board', query: { application: app.id } }"
          class="next-action due-action"
        >
          <span class="next-dot" aria-hidden="true" />
          <span
            ><strong>{{ app.nextAction }}</strong
            ><small
              >{{ app.job.company }} · {{ app.job.title }} · {{ formatDate(app.nextActionAt!) }}（{{
                app.nextActionAt! < dueToday ? uiText("Overdue") : uiText("Due today")
              }}）</small
            ></span
          >
          <AppIcon name="arrow" :size="18" />
        </RouterLink>
        <p v-if="!dueActions.length" class="subtle"> {{ uiText("No actions are due today. Set a next step and date in application details.") }} </p>
      </template>
    </section>

    <div class="dashboard-grid">
      <section class="panel next-panel">
        <div class="section-heading">
          <h2>{{ uiText("Next step") }}</h2>
          <RouterLink to="/career">{{ uiText("Career growth") }}</RouterLink>
        </div>
        <p class="subtle">{{ uiText("Target career") }}</p>
        <p class="target-title">{{ workspace.state.target || uiText("No goal set yet") }}</p>
        <div class="focus-progress" role="presentation">
          <div :style="{ width: progress + '%' }" />
        </div>
        <p class="subtle">{{ uiText('{0} of {1} growth actions completed. {2} resumes and {3} interview practice sessions.', [workspace.state.tasks.length, learningTasks.length, resumeCount ?? '—', workspace.state.interviews.length]) }}</p>
        <RouterLink to="/career" class="next-action">
          <span class="next-dot" aria-hidden="true" />
          <span>{{ (nextTask && translateLabel(nextTask)) || uiText("This round is complete. Review your progress.") }}</span>
          <AppIcon name="arrow" :size="18" />
        </RouterLink>
        <div class="toolkit">
          <RouterLink v-for="entry in toolkit" :key="entry.to" :to="entry.to" class="list-row"
            ><span class="stat-icon"><AppIcon :name="entry.icon" :size="18" /></span>
            <div>
              <h3>{{ entry.title }}</h3>
              <small>{{ entry.desc }}</small>
            </div>
          </RouterLink>
        </div>
      </section>

      <section class="panel">
        <div class="section-heading">
          <h2>{{ uiText("Saved opportunities") }}</h2>
          <RouterLink to="/jobs">{{ uiText("Search jobs") }}</RouterLink>
        </div>
        <RouterLink
          v-for="(job, index) in savedJobs"
          :key="job.id"
          :to="'/jobs/' + job.id"
          class="job-row stagger-in"
          :style="{ '--i': index }"
          ><span class="company-logo">{{ job.company.slice(0, 1) }}</span>
          <div>
            <h3>{{ job.title }}</h3>
            <p>{{ job.company }}，{{ job.locations }}</p>
          </div>
          <AppIcon name="arrow" :size="16"
        /></RouterLink>
        <div
          v-if="!board.query.isPending.value && !board.query.isError.value && !savedJobs.length"
          class="empty-state"
        >
          <p>{{ uiText("No saved jobs yet. Search real listings from company websites and save one to start tracking it.") }}</p>
          <RouterLink to="/jobs" class="btn">{{ uiText("Search jobs") }}</RouterLink>
        </div>
      </section>
    </div>
  </section>
</template>
<style scoped>
.greeting {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}
.greeting h1 {
  font-size: 40px;
  font-weight: 650;
  letter-spacing: -0.015em;
}
.ask-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 760px;
}
.ask {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 6px 6px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  box-shadow: var(--shadow-float);
  transition:
    border-color var(--dur-fast),
    box-shadow var(--dur-mid);
}
.ask:focus-within {
  border-color: var(--primary);
  box-shadow:
    var(--shadow-float),
    0 0 0 3px color-mix(in srgb, var(--primary) 14%, transparent);
}
.ask > svg {
  flex-shrink: 0;
  color: var(--primary);
}
.ask input {
  flex: 1;
  min-width: 0;
  min-height: 44px;
  border: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  font-size: 16px;
  outline: none;
}
.ask input::placeholder {
  color: var(--muted-foreground);
}
.ask .btn {
  min-height: 44px;
  padding: 0 20px;
}
.route-section {
  padding: 24px 0 8px;
  border-top: 1px solid var(--border);
}
.route-section .section-heading {
  margin-bottom: 20px;
}
.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.next-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.target-title {
  margin-top: -6px;
  font-size: 21px;
  font-weight: 650;
}
.next-action {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-weight: 550;
  transition:
    border-color var(--dur-fast),
    color var(--dur-fast);
}
.due-action {
  overflow-wrap: anywhere;
}
.due-action strong,
.due-action small {
  display: block;
}
.due-action small {
  margin-top: 4px;
  color: var(--muted-foreground);
}
.next-action span:nth-child(2) {
  flex: 1;
}
.next-action > svg {
  color: var(--muted-foreground);
  transition: transform var(--dur-mid) var(--ease-out);
}
.next-action:hover {
  border-color: var(--primary);
}
.next-action:hover > svg {
  color: var(--primary);
  transform: translateX(3px);
}
.next-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--primary);
}
.toolkit {
  margin-top: 8px;
}
@media (max-width: 1000px) {
  .route-section {
    padding: 24px 0 8px;
    border-top: 1px solid var(--border);
  }
  .route-section .section-heading {
    margin-bottom: 20px;
  }
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .greeting {
    flex-direction: column;
    gap: 2px;
  }
  .greeting h1 {
    font-size: 30px;
  }
  .ask {
    padding-left: 12px;
  }
  .ask .btn {
    padding: 0 14px;
  }
}
</style>
