<script setup lang="ts">
import type { CareerCardDetails } from '@/shared/api/career'
defineProps<{ details?: CareerCardDetails | null; match?: number | null }>()
</script>
<template>
  <div class="form-stack break-words" aria-label="职业方向资料">
    <p>
      兴趣匹配度：{{
        match == null ? '未提供' : Math.round(match * 100) + '%'
      }}（兴趣相似度，非录用概率）
    </p>
    <p>为什么适合你（用户信息）：</p>
    <ul v-if="details?.evidence?.length">
      <li v-for="(evidence, index) in details.evidence" :key="index">
        「{{ evidence.value }}」 · {{ evidence.source }}/{{ evidence.field }}
      </li>
    </ul>
    <p v-if="(details?.evidence?.length ?? 0) < 2">
      用户信息不足两条，请补充画像；缺失依据未提供。
    </p>
    <dl>
      <dt>典型岗位</dt>
      <dd>{{ details?.typicalTitles?.join('、') || '未提供' }}</dd>
      <dt>核心技能</dt>
      <dd>{{ details?.coreSkills?.join('、') || '未提供' }}</dd>
      <dt>成长路径</dt>
      <dd>{{ details?.growthPath || '未提供' }}</dd>
      <dt>薪资区间</dt>
      <dd>{{ details?.salaryRange || '未提供' }}</dd>
      <dt>代表企业</dt>
      <dd>{{ details?.companies?.join('、') || '未提供' }}</dd>
      <dt>可能的顾虑</dt>
      <dd>{{ details?.concerns || '未提供' }}</dd>
      <dt>职业库来源</dt>
      <dd>{{ details?.sourceOccupationId || '未提供' }} · {{ details?.source || '未提供' }}</dd>
    </dl>
  </div>
</template>
<style scoped>
dl {
  display: grid;
  grid-template-columns: minmax(5em, auto) minmax(0, 1fr);
  gap: 6px 12px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
