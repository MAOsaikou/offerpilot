import { translate as uiText } from '@/shared/utils/language'
import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  toApiError,
  assertResponseIdentity,
} from './client'
import { downloadText } from '@/shared/utils/download'
import type { RoadmapPayload } from '@/shared/types/cards'
export interface LearningPlan {
  id: string
  reportId: string
  startDate: string
  roadmap: RoadmapPayload
}
export const listLearningPlans = (signal?: AbortSignal) =>
  apiRequest<LearningPlan[]>('/career/learning-plans', { signal })
export const createReportLearningPlan = (reportId: string, signal?: AbortSignal) =>
  apiRequest<LearningPlan>(
    `/career/learning-plans/from-interview/${encodeURIComponent(reportId)}`,
    { method: 'POST', signal },
  )
export async function exportLearningCalendar(id: string, signal?: AbortSignal) {
  const response = await fetchWithIdentity(
    apiUrl(`/career/learning-plans/${encodeURIComponent(id)}/calendar.ics`),
    {
      headers: await authenticatedHeaders({ Accept: 'text/calendar' }),
      signal,
    },
  )
  if (!response.ok) throw await toApiError(response)
  const text = await response.text()
  assertResponseIdentity(response)
  if (!response.headers.get('Content-Type')?.startsWith('text/calendar'))
    throw new Error(uiText("Invalid calendar format. Please retry."))
  downloadText(uiText("learning-plan.ics"), text, 'text/calendar;charset=utf-8')
}

export interface LearningPlanUpdate {
  planId: string
  reportId: string
  targetRole: string
  fingerprint: string
  additions: RoadmapPayload['weeks']
}
export const previewInterviewPlanUpdates = (reportId: string, signal?: AbortSignal) =>
  apiRequest<LearningPlanUpdate[]>(
    `/career/learning-plans/updates/from-interview/${encodeURIComponent(reportId)}`,
    { signal },
  )
export const confirmInterviewPlanUpdate = (proposal: LearningPlanUpdate, signal?: AbortSignal) =>
  apiRequest<LearningPlan>(
    `/career/learning-plans/${encodeURIComponent(proposal.planId)}/updates/from-interview/${encodeURIComponent(proposal.reportId)}/confirm`,
    { method: 'POST', body: JSON.stringify({ fingerprint: proposal.fingerprint }), signal },
  )
