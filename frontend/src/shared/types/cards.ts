import type { CareerCardDetails } from '@/shared/api/career'
/**
 * 生成式 UI 卡片 payload 契约。
 * 与 docs/05-接口文档.md §18 对应；payload 是公共输入，卡片可选交互属性由宿主提供。
 */

export type CardType =
  | 'career_profile_confirm'
  | 'career_recommendation'
  | 'career_compare'
  | 'resume_diagnosis'
  | 'resume_diff'
  | 'job_card'
  | 'job_match'
  | 'job_evidence_match'
  | 'job_eval_report'
  | 'campus_calendar'
  | 'board_update'
  | 'interviewer_state'
  | 'interview_turn_score'
  | 'interview_report'
  | 'star_story_candidates'
  | 'star_story_citations'
  | 'knowledge_note_citations'
  | 'star_story'
  | 'skill_gap'
  | 'skill_radar'
  | 'roadmap'
  | 'reminder'

/* ---------- 职业探索 ---------- */

export type HollandCode = 'R' | 'I' | 'A' | 'S' | 'E' | 'C'

export interface HollandInference {
  codes: HollandCode[]
  scores: Record<HollandCode, number>
  confidence: number
}

export interface TraitInference {
  name: string
  confidence: number
  /** 证据来源消息 id */
  evidence: string
}

export interface CareerProfileConfirmPayload {
  holland: HollandInference
  traits: TraitInference[]
  values: string[]
  constraints?: Record<string, unknown>
  actions: Array<'confirm' | 'edit'>
}

export interface CareerRecommendationPayload {
  ordering?: 'interest' | 'constraints'
  revisionId?: string
  elapsedMs?: number
  hollandCode: string
  interestsUsed: string[]
  recommendations: Array<{
    occupationId: string
    name: string
    family: string | null
    hollandCode: string
    similarity: number
    rankingScore?: number
    rankDelta?: number
    changeReason?: string
    details?: CareerCardDetails
    reasons: string[]
    citations?: Array<{ occupationId: string; title: string; excerpt: string }>
  }>
}

export interface CareerTargetSelection {
  occupationId: string
  name: string
}

export interface CareerComparePayload {
  occupations: Array<{ id: string; title: string }>
  dimensions: Array<{ name: string; values: number[] }>
}

/* ---------- 简历 ---------- */

export interface ResumeDiagnosisIssue {
  /** 结构化简历字段路径，如 /projects/0/bullets/1 */
  path: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH'
  message: string
  suggestion: string
}

export type ResumeDiagnosisPayload =
  LegacyResumeDiagnosisPayload | import('./diagnosis').ResumeDiagnosisReport
export interface LegacyResumeDiagnosisPayload {
  overall: number
  dimensions: {
    completeness: number
    quantification: number
    keywords: number
    expression: number
    formatting: number
  }
  issues: ResumeDiagnosisIssue[]
}

export interface ResumePatch {
  id: string
  op: 'add' | 'remove' | 'replace'
  path: string
  from?: string
  value: string
  reason: string
  jdEvidence?: string
  needsUserConfirmation?: boolean
  confirmationHint?: string
}

export interface ResumeDiffPayload {
  batchId: string
  resumeVersionId: string
  patches: ResumePatch[]
  stats: { added: number; replaced: number; removed: number }
}

/* ---------- 岗位 ---------- */

export type JobNature = 'CAMPUS' | 'INTERN' | 'SOCIAL'
export type JobSource = 'LIVE' | 'LOCAL'

export interface JobCardItem {
  id: string
  source: JobSource
  site: string
  company: string
  title: string
  category: string
  nature: JobNature
  cities: string[]
  department?: string
  updatedAt: string
  deadline: string | null
  url: string
  summary: string
  matchScore: number | null
}

export interface JobMatchPayload {
  id: string
  jobId: string
  resumeVersionId: string
  score: number
  dimensions: { skills: number; experience: number; education: number; keywords: number }
  matched: Array<{ requirement: string; evidence: string; strength: 'STRONG' | 'MEDIUM' | 'WEAK' }>
  gaps: Array<{ requirement: string; severity: 'LOW' | 'MEDIUM' | 'HIGH'; suggestion: string }>
  advice: string[]
  citations: Array<{ docId: string; title: string }>
}

export interface CampusCalendarPayload {
  items: Array<{
    company: string
    batch: string
    positions: string[]
    deadline: string
    url: string
    daysLeft?: number
  }>
}

/* ---------- 看板 ---------- */

export type ApplicationStatus =
  'TODO' | 'APPLIED' | 'WRITTEN_TEST' | 'INTERVIEW' | 'OFFER' | 'CLOSED'

export interface BoardUpdatePayload {
  application: {
    id: string
    company: string
    title: string
    status: ApplicationStatus
    nextAction?: string
    nextActionAt: string | null
  }
  change: 'CREATED' | 'UPDATED'
}

/* ---------- 面试 ---------- */

export type InterviewerState = 'LISTENING' | 'THINKING' | 'ASKING' | 'CLOSING'

export interface InterviewerStatePayload {
  state: InterviewerState
  questionIndex: number
  total: number
  sessionId?: string
  question?: string
  note?: string
}

export interface InterviewTurnScorePayload {
  turnIndex: number
  score: number | null
  star: { situation: boolean; task: boolean; action: boolean; result: boolean }
  brief: string
}

export interface InterviewReportPayload {
  id: string
  note?: string
  overall: number | null
  dimensions: {
    professional: number | null
    expression: number | null
    logic: number | null
    jobFit: number | null
    presence?: number
  }
  turns: Array<{
    question: string
    answerSummary: string
    score: number | null
    star: { situation: boolean; task: boolean; action: boolean; result: boolean }
    feedback: string
    evaluation?: import('./interview').InterviewEvaluation | null
    improvedAnswer?: string | null
    source?: string
    betterAnswerOutline: string[]
  }>
  strengths: string[]
  weaknesses: string[]
  advice: string[]
  presence?: {
    eyeContactRatio: number
    smileRate: number
    headStability: number
    note: string
  }
  recommendedLearning: Array<{ topic: string; reason: string; estimatedHours: number }>
}

export interface KnowledgeNoteCitationsPayload {
  count: number
  items: Array<{
    noteId: string
    title: string
    category: string
    excerpt: string
    reference: string
  }>
}

export interface StarStoryCitationsPayload {
  count: number
  items: Array<{ id: string; title: string; role: string; starCount: number; excerpt: string }>
}

/* ---------- 职业规划 ---------- */

export interface GrowthEvidence {
  type: 'JOB_MATCH' | 'INTERVIEW' | 'OCCUPATION' | 'RESUME'
  id: string | null
  label: string
  excerpt: string
  jobId?: string | null
}

export interface SkillGapPayload {
  id: string
  target: { type: 'JOB' | 'OCCUPATION'; id: string; title: string }
  evidenceAvailability?: { matchAvailable: boolean; interviewAvailable: boolean }
  skills: Array<{
    name: string
    required: number
    current: number
    gap: number
    evidence: string[]
    id?: string
    evidenceSources?: GrowthEvidence[]
    learnFirst?: boolean
  }>
  summary: string
  citations: Array<{ docId: string; title: string }>
}

export interface SkillRadarPayload {
  axes: Array<{ name: string; current: number; required: number }>
}

export interface RoadmapPayload {
  weeks: Array<{
    index: number
    theme: string
    gapIds?: string[]
    evidenceSources?: GrowthEvidence[]
    items: Array<{
      id: string
      title: string
      hours: number
      resources: string[]
      dueAt: string | null
      done: boolean
    }>
  }>
  milestones: Array<{ title: string; at: string }>
}

/* ---------- 提醒 ---------- */

export interface ReminderPayload {
  title: string
  dueAt: string
  relatedType: 'JOB' | 'APPLICATION' | 'INTERVIEW' | 'ROADMAP'
  relatedId: string
}

/** cardType → payload 类型，用于 CardRenderer 注册表做类型约束 */
export interface CardPayloadMap {
  career_profile_confirm: CareerProfileConfirmPayload
  career_recommendation: CareerRecommendationPayload
  career_compare: CareerComparePayload
  resume_diagnosis: ResumeDiagnosisPayload
  resume_diff: ResumeDiffPayload
  job_card: JobCardItem | import('./jobs').ChatJobSearch
  job_match:
    JobMatchPayload | Omit<import('./jobs').EvidenceMatch, 'jobSnapshot' | 'resumeSnapshot'>
  campus_calendar: CampusCalendarPayload
  board_update: BoardUpdatePayload
  interviewer_state: InterviewerStatePayload
  interview_turn_score: InterviewTurnScorePayload
  interview_report: InterviewReportPayload
  star_story_citations: StarStoryCitationsPayload
  knowledge_note_citations: KnowledgeNoteCitationsPayload
  skill_gap: SkillGapPayload
  skill_radar: SkillRadarPayload
  roadmap: RoadmapPayload
  reminder: ReminderPayload
}
