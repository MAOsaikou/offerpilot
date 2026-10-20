import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { BuiltinKind } from '@/shared/api/builtin-knowledge'

/** Navigation state only; catalog data belongs to TanStack Query. No personal data persisted. */
export const useBuiltinFilters = defineStore('builtin-knowledge-filters', () => {
  const tab = ref<BuiltinKind | 'OCCUPATION'>('OCCUPATION')
  const q = ref(''),
    family = ref(''),
    holland = ref(''),
    industry = ref(''),
    role = ref('')
  const questionType = ref(''),
    difficulty = ref(''),
    page = ref(1)
  return { tab, q, family, holland, industry, role, questionType, difficulty, page }
})
