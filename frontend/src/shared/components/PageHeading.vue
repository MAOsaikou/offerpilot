<script setup lang="ts">
defineProps<{ title: string; eyebrow?: string; description?: string }>()
</script>
<template>
  <header class="page-heading">
    <div>
      <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="subtle">{{ description }}</p>
    </div>
    <div class="heading-actions"><slot /></div>
  </header>
</template>
