<script setup lang="ts">
import type { SkillGapPayload } from '@/shared/types/cards'
defineProps<{ payload: SkillGapPayload }>()
</script>

<template>
  <section class="min-w-0 rounded-xl border border-border bg-card p-4" aria-label="技能差距">
    <h3 class="break-words font-semibold">技能差距 · {{ payload.target.title }}</h3>
    <p class="mt-2 break-words text-sm text-muted-foreground">{{ payload.summary }}</p>
    <p v-if="!payload.skills.length" class="mt-3 text-sm" role="status">
      暂无核心技能数据，请先梳理目标岗位要求。
    </p>
    <ul v-else class="mt-3 space-y-3">
      <li
        v-for="skill in payload.skills"
        :key="skill.name"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="break-words font-medium">{{ skill.name }}</h4>
          <span v-if="skill.learnFirst" class="rounded bg-primary/10 px-2 text-xs text-primary"
            >优先补齐</span
          >
          <span class="text-xs text-muted-foreground"
            >证据等级 {{ skill.current }} / 5 · 参考要求 {{ skill.required }} · 差距
            {{ skill.gap }}</span
          >
        </div>
        <progress
          class="mt-2 w-full accent-primary"
          :value="skill.current"
          :max="5"
          :aria-label="`${skill.name}证据等级`"
        />
        <ul v-if="skill.evidence.length" class="mt-1 space-y-1 text-sm text-muted-foreground">
          <li v-for="evidence in skill.evidence" :key="evidence" class="break-words">
            {{ evidence }}
          </li>
        </ul>
        <p v-else class="mt-1 text-sm text-muted-foreground">
          尚无已保存技能证据，不代表没有能力。
        </p>
      </li>
    </ul>
    <ul
      v-if="payload.citations.length"
      class="mt-3 text-xs text-muted-foreground"
      aria-label="参考资料"
    >
      <li v-for="citation in payload.citations" :key="citation.docId">{{ citation.title }}</li>
    </ul>
  </section>
</template>
