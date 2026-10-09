<script setup lang="ts">
import ChatView from '@/features/chat/views/ChatView.vue'
import AgentPanel from '@/widgets/AgentPanel/AgentPanel.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
</script>
<template>
  <ChatView>
    <template #agent="{ run }"><AgentPanel :run="run" /></template>
    <template #cards="{ cards, adoptTarget, adoptGrowth, applyPatches, disabled }">
      <CardRenderer
        v-for="card in cards"
        :key="card.cardId"
        :card-type="card.cardType"
        :payload="card.payload"
        :interactive="['career_recommendation', 'roadmap', 'resume_diff'].includes(card.cardType)"
        :disabled="disabled"
        apply-label="审批采纳所选建议"
        @apply="applyPatches($event, card.payload)"
        @adopt-growth="adoptGrowth"
        @adopt-target="adoptTarget"
      />
    </template>
  </ChatView>
</template>
