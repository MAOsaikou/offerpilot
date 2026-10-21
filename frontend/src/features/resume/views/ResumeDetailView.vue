<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { PII_WARNING } from '@/shared/api/privacy-settings'
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useWorkspace } from '@/shared/stores/workspace'
import LocalResumeEditor from '../components/LocalResumeEditor.vue'
import ServerResumeEditor from '../components/ServerResumeEditor.vue'
const route = useRoute()
const workspace = useWorkspace()
const id = computed(() => String(route.params.id))
const local = computed(() => workspace.state.resumes.some((resume) => resume.id === id.value))
</script>
<template>
  <p v-if="route.query.piiWarning === '1'" class="helper-note" role="status">
    {{ uiText(PII_WARNING) }} <RouterLink to="/settings/privacy">{{ uiText("Privacy settings") }}</RouterLink>
  </p>
  <LocalResumeEditor v-if="local" :key="'local-' + id" />
  <ServerResumeEditor v-else :id="id" :key="id">
    <template #optimization-card="card"><slot name="optimization-card" v-bind="card" /></template>
  </ServerResumeEditor>
</template>
