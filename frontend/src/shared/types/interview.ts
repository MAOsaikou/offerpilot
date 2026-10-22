import { translate as uiText } from '@/shared/utils/language'
export type InterviewKind =
  '技术' | '行为' | 'HR' | '综合' | '项目与经历' | '表达与结构' | '职业动机'

export interface InterviewOptions {
  resumeId?: string | null
  versionId?: string | null
  difficulty: 'EASY' | 'STANDARD' | 'HARD'
  mode: 'TEXT' | 'VOICE'
  questionCount?: number
  typeMix?: Partial<Record<QuestionType, number>>
  industry?: string
  feedbackMode?: 'IMMEDIATE' | 'END'
  durationMinutes: number
}

export interface InterviewSummary {
  id: string
  jobId?: string | null
  options?: InterviewOptions | null
  role: string
  kind: InterviewKind
  questionIndex: number
  completed: boolean
  createdAt: string
  updatedAt: string
}

export interface InterviewAnswer {
  questionType?: QuestionType | null
  difficulty?: InterviewOptions['difficulty'] | null
  questionSource?: 'model' | 'rule' | null
  evaluation?: InterviewEvaluation | null
  questionIndex: number
  questionText: string
  answerText: string
  /** IV-02: interviewer follow-up asked from the saved answer; source 'rule' is the offline fallback. */
  followUpQuestion?: string | null
  followUpAnswer?: string | null
  followUpSource?: 'model' | 'rule' | null
}

export interface InterviewSession extends InterviewSummary {
  generationSource?: 'pending' | 'model' | 'rule' | null
  answers: InterviewAnswer[]
}

export type QuestionType = 'BEHAVIORAL' | 'SITUATIONAL' | 'KNOWLEDGE' | 'CASE'
export const questionTypeLabels: Record<QuestionType, string> = {
  get BEHAVIORAL() {
    return uiText('Behavioral')
  },
  get SITUATIONAL() {
    return uiText('Situational')
  },
  get KNOWLEDGE() {
    return uiText('Professional knowledge')
  },
  get CASE() {
    return uiText('Case analysis')
  },
}
export const difficultyLabels = {
  get EASY() {
    return uiText('Beginner')
  },
  get STANDARD() {
    return uiText('Intermediate')
  },
  get HARD() {
    return uiText('Advanced')
  },
}
export interface InterviewEvaluation {
  source: 'model' | 'unavailable'
  score: number | null
  rationale: string
  improvedAnswer: string
  provenance?: string
  dimensions?: Record<
    'professional' | 'expression' | 'logic' | 'jobFit',
    { score: number; rationale: string; evidence: string }
  >
}
