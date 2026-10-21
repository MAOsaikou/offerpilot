<script setup lang="ts">
import {
  localizedText,
  formatDateTime,
  translateLabel,
  sourceText,
  translate as uiText,
} from '@/shared/utils/language'

import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import MarkdownView from '@/shared/components/MarkdownView.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
const workspace = useWorkspace()
const route = useRoute()
const resume = computed(() => workspace.state.resumes.find((r) => r.id === route.params.id))
const tab = ref<string>(sourceText('Edit #2'))
const message = localizedText()
function saveVersion() {
  if (!resume.value) return
  resume.value.versions.unshift({
    content: resume.value.content,
    date: new Date().toISOString(),
  })
  resume.value.updated = new Date().toISOString()
  message.value = () => uiText('Current version saved')
  workspace.notify(uiText('Saved resume version:') + resume.value.title)
}
function touched() {
  if (resume.value) resume.value.updated = new Date().toISOString()
  message.value = ''
}
function restore(content: string) {
  if (!resume.value) return
  saveVersion()
  resume.value.content = content
  message.value = () => uiText('Selected version restored. Previous content was also saved.')
  tab.value = sourceText('Edit #2')
}
function printResume() {
  window.print()
}
</script>
<template>
  <section v-if="resume" class="page-stack">
    <PageHeading
      :title="resume.title"
      :description="uiText('Make your strengths clearer with every edit.')"
      ><RouterLink to="/resumes" class="btn">{{ uiText('Back') }}</RouterLink
      ><button class="btn" @click="downloadText(resume.title + '.md', resume.content)">
        {{ uiText('Export Markdown') }}</button
      ><button class="btn primary" @click="printResume">
        {{ uiText('Print / PDF') }}
      </button></PageHeading
    >
    <div class="split-content">
      <section class="panel form-stack no-print">
        <div class="tabs">
          <button
            v-for="t in [sourceText('Edit #2'), sourceText('Versions')]"
            :key="t"
            :class="{ selected: tab === t }"
            @click="tab = t"
          >
            {{ translateLabel(t) }}
          </button>
        </div>
        <p v-if="message" class="success-banner" role="status">{{ message }}</p>
        <template v-if="tab === sourceText('Edit #2')"
          ><label class="field"
            >{{ uiText('Resume name')
            }}<input v-model="resume.title" maxlength="100" @input="touched" /></label
          ><label class="field"
            >{{ uiText('Body (Markdown)')
            }}<textarea v-model="resume.content" rows="20" maxlength="100000" @input="touched" />
          </label>
          <div class="card-footer">
            <span class="subtle">{{ uiText('Drafts save automatically in this browser') }}</span
            ><button class="btn primary" @click="saveVersion">{{ uiText('Saved version') }}</button>
          </div>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'RESUME',
                prompt: uiText(
                  'Help me improve how I describe project experience on my resume without inventing facts.',
                ),
              },
            }"
            class="btn"
            >{{ uiText('Ask AI about resume writing') }}</RouterLink
          ></template
        ><template v-else
          ><p v-if="!resume.versions.length" class="subtle">
            {{ uiText('No versions yet. Edit and click Save version to keep a snapshot.') }}
          </p>
          <div v-for="(version, index) in resume.versions" :key="index" class="list-row">
            <div>
              <h3>{{ uiText('Version {0}', [resume.versions.length - index]) }}</h3>
              <small>{{ formatDateTime(version.date) }}</small>
            </div>
            <button class="btn" @click="restore(version.content)">{{ uiText('Restore') }}</button>
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
    <h2>{{ uiText('Resume not found') }}</h2>
    <RouterLink to="/resumes" class="btn">{{ uiText('Back to resumes') }}</RouterLink>
  </div>
</template>
