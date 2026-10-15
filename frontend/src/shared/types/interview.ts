export type InterviewKind = '项目与经历' | '表达与结构' | '职业动机'

export interface InterviewSummary {
  id: string
  jobId?: string | null
  role: string
  kind: InterviewKind
  questionIndex: number
  completed: boolean
  createdAt: string
  updatedAt: string
}

export interface InterviewAnswer {
  questionIndex: number
  questionText: string
  answerText: string
  /** IV-02: interviewer follow-up asked from the saved answer; source 'rule' is the offline fallback. */
  followUpQuestion?: string | null
  followUpAnswer?: string | null
  followUpSource?: 'model' | 'rule' | null
}

export interface InterviewSession extends InterviewSummary {
  answers: InterviewAnswer[]
}
