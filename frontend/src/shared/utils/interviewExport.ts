import { translate as uiText } from '@/shared/utils/language'
import type { InterviewAnswer } from '@/shared/types/interview'
import { downloadText } from './download'
import {
  apiUrl,
  authenticatedHeaders,
  assertResponseIdentity,
  fetchWithIdentity,
  toApiError,
} from '@/shared/api/client'

function localMarkdown(answers: InterviewAnswer[]) {
  return answers
    .map((answer) => '## ' + answer.questionText + '\n\n' + (answer.answerText || uiText("Unanswered")))
    .join('\n\n')
}

/** Server sessions export authenticated recaps; only legacy sessions use local Q&A. */
export async function exportInterview(
  role: string,
  answers: InterviewAnswer[],
  sessionId?: string,
  signal?: AbortSignal,
) {
  if (sessionId) {
    const response = await fetchWithIdentity(
      apiUrl(`/interviews/${encodeURIComponent(sessionId)}/recap.md`),
      {
        headers: await authenticatedHeaders({ Accept: 'text/markdown' }),
        signal,
      },
    )
    if (!response.ok) throw await toApiError(response)
    const markdown = await response.text()
    assertResponseIdentity(response)
    signal?.throwIfAborted()
    downloadText(role + uiText("-interview-review-package.md"), markdown)
    return
  }
  downloadText(role + uiText("-interview-review.md"), localMarkdown(answers))
}
