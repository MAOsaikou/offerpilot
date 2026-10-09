<script setup lang="ts">
import ChatView from '@/features/chat/views/ChatView.vue'
import AgentPanel from '@/widgets/AgentPanel/AgentPanel.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
</script>
<template>
  <ChatView>
    <template #agent="{ run }"><AgentPanel :run="run" /></template>
    <template #cards="{ cards, adoptTarget, disabled }">
      <CardRenderer
        v-for="card in cards"
        :key="card.cardId"
        :card-type="card.cardType"
        :payload="card.payload"
        :interactive="card.cardType === 'career_recommendation'"
        :disabled="disabled"
        @adopt-target="adoptTarget"
      />
    </template>
  </ChatView>
</template>
