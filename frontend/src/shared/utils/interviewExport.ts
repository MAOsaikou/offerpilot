import type { InterviewAnswer } from '@/shared/types/interview'
import { downloadText } from './download'
import { apiUrl, fetchWithIdentity } from '@/shared/api/client'

function localMarkdown(role: string, answers: InterviewAnswer[]) {
  return answers
    .map((answer) => '## ' + answer.questionText + '\n\n' + (answer.answerText || '未作答'))
    .join('\n\n')
}

/** Prefer server recap package (STAR + dimensions); fall back to local Q&A for legacy/offline. */
export async function exportInterview(
  role: string,
  answers: InterviewAnswer[],
  sessionId?: string,
) {
  if (sessionId) {
    try {
      const response = await fetchWithIdentity(apiUrl(`/interviews/${sessionId}/recap.md`), {
        headers: { Accept: 'text/markdown' },
      })
      if (response.ok) {
        const markdown = await response.text()
        downloadText(role + '-面试复盘包.md', markdown)
        return
      }
    } catch {
      /* fall through to local export */
    }
  }
  downloadText(role + '-面试复盘.md', localMarkdown(role, answers))
}
