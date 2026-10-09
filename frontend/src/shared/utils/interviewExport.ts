import type { InterviewAnswer } from '@/shared/types/interview'
import { downloadText } from './download'

export function exportInterview(role: string, answers: InterviewAnswer[]) {
  downloadText(
    role + '-面试复盘.md',
    answers
      .map((answer) => '## ' + answer.questionText + '\n\n' + (answer.answerText || '未作答'))
      .join('\n\n'),
  )
}
