export type InterviewKind = '项目与经历' | '表达与结构' | '职业动机'

export interface InterviewSummary {
  id: string
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
}

export interface InterviewSession extends InterviewSummary {
  answers: InterviewAnswer[]
}
