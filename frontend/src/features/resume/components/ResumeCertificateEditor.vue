<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import ResumeReorderList from './ResumeReorderList.vue'

const props = defineProps<{ disabled?: boolean }>()
const certificates = defineModel<string[]>({ required: true })
function update(index: number, event: Event) {
  if (props.disabled) return
  const value = (event.target as HTMLInputElement).value
  certificates.value = certificates.value.map((item, i) => (i === index ? value : item))
}
function add() {
  if (props.disabled || certificates.value.length >= 100) return
  certificates.value = [...certificates.value, '']
}
</script>
<template>
  <section class="form-stack certificate-section">
    <div class="section-heading">
      <h2>{{ uiText("Certificates and awards") }}</h2>
      <button
        type="button"
        class="btn"
        :disabled="disabled || certificates.length >= 100"
        @click="add"
      > {{ uiText("＋ Add certificate or award") }} </button>
    </div>
    <p v-if="!certificates.length" class="subtle">{{ uiText("No certificates or awards yet. Add any you have earned.") }}</p>
    <ResumeReorderList v-model="certificates" :title="uiText('Certificates and awards')" :disabled="disabled">
      <template #default="{ entry, index }">
        <label class="field"
          >{{ uiText("Certificate or award name") }}<input :value="entry" maxlength="5000" @input="update(index, $event)"
        /></label>
        <button
          type="button"
          class="btn"
          @click="certificates = certificates.filter((_, i) => i !== index)"
        > {{ uiText("Remove this certificate or award") }} </button>
      </template>
    </ResumeReorderList>
  </section>
</template>
<style scoped>
.certificate-section {
  min-width: 0;
}
.section-heading {
  flex-wrap: wrap;
}
</style>
