<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
import { API_BASE } from '@/shared/api/client'
import DeleteMyData from '../components/DeleteMyData.vue'
import PrivacySettings from '../components/PrivacySettings.vue'
import MemorySettings from '../components/MemorySettings.vue'
import { sessionRevision } from '@/shared/utils/authSession'
import ResumeModelSettings from '../components/ResumeModelSettings.vue'
import AccountPreferences from '../components/AccountPreferences.vue'
import GitHubEvidencePanel from '@/shared/components/GitHubEvidencePanel.vue'
const workspace = useWorkspace()
const route = useRoute()
const router = useRouter()
const tab = computed(() =>
  route.params.tab === 'connection'
    ? uiText('Connections and diagnostics')
    : route.params.tab === 'models'
      ? uiText('Model selection')
      : route.params.tab === 'privacy'
        ? uiText('Data and privacy')
        : uiText('Personal preferences'),
)
function selectTab(value: string) {
  const suffix =
    value === uiText('Model selection')
      ? '/models'
      : value === uiText('Connections and diagnostics')
        ? '/connection'
        : value === uiText('Data and privacy')
          ? '/privacy'
          : ''
  void router.replace('/settings' + suffix)
}
const checking = ref(false)
const result = localizedText()
const ok = ref(false)
async function check() {
  checking.value = true
  result.value = ''
  ok.value = false
  try {
    const response = await fetch('/actuator/health', {
      signal: AbortSignal.timeout(6000),
      cache: 'no-store',
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    const data: unknown = await response.json()
    ok.value = !!data && typeof data === 'object' && 'status' in data && data.status === 'UP'
    result.value = () =>
      ok.value
        ? uiText('Backend health check passed. Open AI chat to test streaming.')
        : uiText(
            'The backend responded, but its health is not UP. Check backend logs and dependencies.',
          )
  } catch (error) {
    result.value = () =>
      uiText('Could not verify backend connection:') +
      (error instanceof Error ? error.message : uiText('Request failed')) +
      uiText('. Check that the backend is running on port 8081.')
  } finally {
    checking.value = false
  }
}
function exportWorkspace() {
  downloadText(
    uiText('OfferPilot-local-workspace.json'),
    JSON.stringify(workspace.state, null, 2),
    'application/json',
  )
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('Make this workspace yours')"
      :description="
        uiText('Manage display preferences, choose parsing models and check connectivity.')
      "
    />
    <div class="tabs">
      <button
        v-for="t in [
          uiText('Personal preferences'),
          uiText('Model selection'),
          uiText('Connections and diagnostics'),
          uiText('Data and privacy'),
        ]"
        :key="t"
        :class="{ selected: tab === t }"
        @click="selectTab(t)"
      >
        {{ t }}
      </button>
    </div>
    <section
      v-if="tab === uiText('Personal preferences')"
      class="panel form-stack"
      style="max-width: 800px"
    >
      <h2>{{ uiText('Basic information') }}</h2>
      <div class="form-grid">
        <label class="field"
          >{{ uiText('What should we call you?')
          }}<input v-model="workspace.displayName" maxlength="40" /></label
        ><label class="field"
          >{{ uiText('Target career') }}<input v-model="workspace.state.target" maxlength="100"
        /></label>
      </div>
      <AccountPreferences />
      <GitHubEvidencePanel :key="sessionRevision" />
    </section>
    <ResumeModelSettings v-else-if="tab === uiText('Model selection')" />
    <section v-else-if="tab === uiText('Connections and diagnostics')" class="split-content">
      <div class="panel form-stack">
        <h2>{{ uiText('Backend connection') }}</h2>
        <RouterLink to="/integrations/mcp" class="btn">{{
          uiText('Manage MCP connections')
        }}</RouterLink>
        <p class="subtle">
          {{ uiText('API endpoint:') }}<code>{{ API_BASE }}</code
          ><br />{{
            uiText('Current chat: {0}', [
              uiText('Real backend (Mock can be explicitly enabled during development)'),
            ])
          }}<br />{{ uiText('Nginx endpoint: http://localhost:8080') }}<br />{{
            uiText('Default backend: http://127.0.0.1:8081')
          }}
        </p>
        <p class="helper-note">
          {{
            uiText(
              'The backend supports multi-turn DeepSeek streaming, conversation history and basic agent orchestration. Some specialist features and RAG are still pending. A successful health check confirms service health only; send a message to verify model connectivity.',
            )
          }}
        </p>
        <button class="btn primary" :disabled="checking" @click="check">
          {{ checking ? uiText('Checking…') : uiText('Check backend connection') }}
        </button>
        <p v-if="result" :class="ok ? 'success-banner' : 'error-banner'" role="status">
          {{ result }}
        </p>
        <div class="filter-bar">
          <RouterLink to="/chat" class="btn">{{ uiText('Test streaming chat') }}</RouterLink
          ><a href="/swagger-ui.html" target="_blank" rel="noopener" class="btn">{{
            uiText('API documentation')
          }}</a>
        </div>
      </div>
      <aside class="panel form-stack">
        <h2>{{ uiText('Diagnostic steps') }}</h2>
        <p class="subtle">
          {{ uiText('1. Start the database and backend.') }}<br />{{
            uiText("2. Run the project's frontend startup script.")
          }}<br />{{ uiText('3. Check connectivity, then send a message.') }}<br />{{
            uiText("4. The execution panel shows this run's status and server events.")
          }}
        </p>
        <p class="helper-note">
          {{
            uiText(
              'Connection checks and chat use the real backend. Registration, sign-in, the board, interviews, resumes and job matching are connected to server storage. Local drafts, older practice archives, notes and display preferences retained in the browser do not sync automatically.',
            )
          }}
        </p>
      </aside>
    </section>
    <section v-else class="panel form-stack" style="max-width: 800px">
      <h2>{{ uiText('Data and privacy') }}</h2>
      <PrivacySettings />
      <MemorySettings :key="sessionRevision" />
      <h3>{{ uiText('Where your content is saved') }}</h3>
      <p class="subtle">
        {{
          uiText(
            'Local resume drafts, older board archives, older practice answers and unimported notes stay in this browser. Long-term memories, career profiles, growth tasks, imported notes and vectors, new interviews, saved jobs, application stages and follow-up notes, uploaded resume originals, parsed results, versions and AI conversations are saved on the backend. Match reports and their source snapshots are also saved there. Relevant resume content is sent to the configured model when you upload for parsing or explicitly request matching. Guests use browser-stored tokens to access server data; clearing site data, changing browsers or token expiry prevents automatic recovery. Guest access cannot currently be recovered across devices. Registered users can sign in with email and password in another browser to access the same server data.',
          )
        }}
      </p>
      <button class="btn" @click="exportWorkspace">
        {{ uiText('Export local workspace backup') }}
      </button>
      <p class="helper-note">
        {{
          uiText(
            'Registration and sign-in are available. Guest access cannot be recovered across devices. This exports the local workspace only, excluding server interviews, board, resumes, conversations, match reports and sign-in credentials. Model secrets are configured on the server; never enter them in the frontend.',
          )
        }}
      </p>
      <DeleteMyData />
    </section>
  </section>
</template>
