<script setup lang="ts">
import MessageUsage from '@/shared/components/MessageUsage.vue'
import type { UsageView } from '@/shared/stores/agentRun'
defineProps<{ usage: UsageView | null; model: string | null }>()
</script>
<template><MessageUsage :usage="usage" :model="model" /></template>
