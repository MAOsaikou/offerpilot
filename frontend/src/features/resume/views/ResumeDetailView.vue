<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspace } from '@/shared/stores/workspace'
import LocalResumeEditor from '../components/LocalResumeEditor.vue'
import ServerResumeEditor from '../components/ServerResumeEditor.vue'
const route = useRoute()
const workspace = useWorkspace()
const id = computed(() => String(route.params.id))
const local = computed(() => workspace.state.resumes.some((resume) => resume.id === id.value))
</script>
<template>
  <LocalResumeEditor v-if="local" :key="'local-' + id" />
  <ServerResumeEditor v-else :id="id" :key="id" />
</template>
