<script setup lang="ts">
import ResumeDetailView from '@/features/resume/views/ResumeDetailView.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
</script>
<template>
  <ResumeDetailView>
    <template #optimization-card="card">
      <CardRenderer
        :key="card.report.reportId"
        card-type="resume_diff"
        :payload="card.report"
        :interactive="card.interactive"
        :disabled="card.disabled"
        :applied-version-id="card.appliedVersionId"
        @apply="card.apply"
      />
    </template>
  </ResumeDetailView>
</template>
