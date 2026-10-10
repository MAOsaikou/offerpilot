<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useBoard } from '@/shared/composables/useBoard'
import {
  applicationStages,
  type ApplicationStatus,
  type ServerApplication,
} from '@/shared/types/board'
const props = defineProps<{ application: ServerApplication }>()
const board = useBoard()
const router = useRouter()
function open(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('a,button,input,textarea,select,label')) return
  void router.push({ path: '/board', query: { application: props.application.id } })
}
const note = ref(props.application.note)
watch(
  () => props.application.note,
  (value, previous) => {
    if (note.value === previous) note.value = value
  },
)
async function changeStage(event: Event) {
  const select = event.target as HTMLSelectElement
  const status = select.value as ApplicationStatus
  select.value = props.application.status
  await board.update(props.application.id, { status })
}
async function saveNote() {
  await board.update(props.application.id, { note: note.value })
}
</script>
<template>
  <article class="application-card" :aria-busy="board.busy.value" @click="open">
    <p>{{ application.job.company }}</p>
    <RouterLink :to="{ path: '/board', query: { application: application.id } }"
      ><h3>{{ application.job.title }}</h3></RouterLink
    >
    <p>
      {{ application.job.locations || '地点未提供' }} · {{ application.createdAt.slice(0, 10) }}
    </p>
    <p v-if="application.nextAction" class="subtle">
      下一步：{{ application.nextAction
      }}<span v-if="application.nextActionAt"> · {{ application.nextActionAt }}</span>
    </p>
    <label class="field"
      >当前阶段
      <select
        :value="application.status"
        :disabled="board.busy.value || note !== application.note"
        @change="changeStage"
      >
        <option v-for="(label, status) in applicationStages" :key="status" :value="status">
          {{ label }}
        </option>
      </select>
    </label>
    <label class="field"
      >跟进备注
      <textarea
        v-model="note"
        rows="2"
        maxlength="2000"
        :disabled="board.busy.value"
        placeholder="记录下一步、面试时间…"
      />
    </label>
    <button class="btn" :disabled="board.busy.value || note === application.note" @click="saveNote">
      {{ board.busy.value ? '正在保存…' : '保存备注' }}
    </button>
    <p v-if="note !== application.note" class="subtle">备注尚未保存，请保存后再移动阶段。</p>
    <p v-if="board.error.value" class="error-banner" role="alert">
      {{ board.error.value }}，请重试。
    </p>
  </article>
</template>
