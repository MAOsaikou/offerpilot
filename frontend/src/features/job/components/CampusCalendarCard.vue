<script setup lang="ts">
import { computed } from 'vue'
import type { CampusCalendarPayload } from '@/shared/types/cards'
import { safeExternalUrl } from '@/shared/utils/safeExternalUrl'
const props = defineProps<{ payload: CampusCalendarPayload }>()
const valid = computed(
  () =>
    Array.isArray(props.payload?.items) &&
    props.payload.items.every(
      (i) =>
        typeof i.company === 'string' &&
        Array.isArray(i.positions) &&
        typeof i.deadline === 'string' &&
        Number.isFinite(Date.parse(i.deadline)),
    ),
)
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" aria-label="招聘日历">
    <h3>招聘截止日历</h3>
    <p>仅展示本次查询中明确提供的截止日期，请以来源公告为准。</p>
    <p v-if="!valid" role="alert">日历数据不可用，请重新查询。</p>
    <p v-else-if="!payload.items.length" role="status">本次结果没有明确截止日期。</p>
    <ol v-else class="space-y-3">
      <li v-for="(item, index) in payload.items" :key="index">
        <strong>{{ item.company }}</strong> · {{ item.batch }}
        <p>{{ item.positions.join('、') }}</p>
        <time :datetime="item.deadline">{{ item.deadline }}</time>
        <a
          v-if="safeExternalUrl(item.url)"
          :href="safeExternalUrl(item.url)!"
          target="_blank"
          rel="noopener noreferrer"
          class="btn"
          >查看来源公告</a
        >
      </li>
    </ol>
  </section>
</template>
