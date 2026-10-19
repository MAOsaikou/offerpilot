<script setup lang="ts">
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
      <h2>证书与奖项</h2>
      <button
        type="button"
        class="btn"
        :disabled="disabled || certificates.length >= 100"
        @click="add"
      >
        ＋ 添加证书与奖项
      </button>
    </div>
    <p v-if="!certificates.length" class="subtle">尚未填写证书与奖项，可按实际情况补充。</p>
    <ResumeReorderList v-model="certificates" title="证书与奖项" :disabled="disabled">
      <template #default="{ entry, index }">
        <label class="field"
          >证书或奖项名称<input :value="entry" maxlength="5000" @input="update(index, $event)"
        /></label>
        <button
          type="button"
          class="btn"
          @click="certificates = certificates.filter((_, i) => i !== index)"
        >
          移除此条证书与奖项
        </button>
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
