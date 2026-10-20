import { defineAsyncComponent, type Component } from 'vue'
import CardFallback from './CardFallback.vue'
import CardSkeleton from './CardSkeleton.vue'
import type { CardType } from '@/shared/types/cards'

/**
 * cardType → 组件（docs/07 §9）。
 *
 * 已接入的业务卡片在此注册；未知类型安全降级为纯文本。
 */
export type CardLoader = () => Promise<Component>

export const cardRegistry: Partial<Record<CardType, CardLoader>> = {
  knowledge_note_citations: () => import('@/features/kb/components/KnowledgeNoteCitationsCard.vue'),
  skill_radar: () => import('@/features/growth/components/SkillRadarCard.vue'),
  campus_calendar: () => import('@/features/job/components/CampusCalendarCard.vue'),
  interview_turn_score: () => import('@/features/interview/components/InterviewTurnScoreCard.vue'),
  skill_gap: () => import('@/features/growth/components/SkillGapCard.vue'),
  roadmap: () => import('@/features/growth/components/RoadmapCard.vue'),
  star_story_citations: () => import('@/features/interview/components/StarStoryCitationsCard.vue'),
  interview_report: () => import('@/features/interview/components/InterviewReportCard.vue'),
  interviewer_state: () => import('@/features/interview/components/InterviewerStateCard.vue'),
  career_recommendation: () =>
    import('@/features/discovery/components/CareerRecommendationCard.vue'),
  job_card: () => import('@/features/job/components/JobSearchCard.vue'),
  resume_diagnosis: () => import('@/features/resume/components/DiagnosisCard.vue'),
  job_match: () => import('@/features/job/components/MatchReport.vue'),
  job_eval_report: () => import('@/features/job/components/JobEvalReport.vue'),
  job_evidence_match: () => import('@/features/job/components/EvidenceMatchReport.vue'),
  resume_diff: () => import('@/features/resume/components/PatchDiffViewer.vue'),
  board_update: () => import('@/features/board/components/BoardUpdateCard.vue'),
}

/** 缓存异步组件，避免每次渲染都重新创建导致重复挂载 */
const asyncCache = new Map<string, Component>()

export function resolveCardComponent(cardType: string): Component | null {
  const cached = asyncCache.get(cardType)
  if (cached) return cached

  const loader = Object.hasOwn(cardRegistry, cardType)
    ? cardRegistry[cardType as CardType]
    : undefined
  if (!loader) return null

  const component = defineAsyncComponent({
    loader,
    loadingComponent: CardSkeleton,
    errorComponent: CardFallback,
    delay: 140,
  })
  asyncCache.set(cardType, component)
  return component
}
