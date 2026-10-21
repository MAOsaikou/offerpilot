import { translate as uiText } from '@/shared/utils/language'
import {
  applicationStageLabels as applicationStages,
  type ApplicationStatus,
} from '@/shared/types/board'

// Resource identifiers remain in transport arguments, never in visible review text.
export function toolArguments(args: unknown): Array<{ label: string; value: string }> {
  if (!args || typeof args !== 'object') return []
  const outer = args as Record<string, unknown>
  const data =
    outer.inputs && typeof outer.inputs === 'object'
      ? (outer.inputs as Record<string, unknown>)
      : outer
  const fields: Record<string, string> = {
    name: uiText('Target career #2'),
    city: uiText('Preferred city'),
    minSalaryK: uiText('Minimum monthly salary (CNY thousands)'),
    avoidOvertime: uiText('Avoid overtime'),
    research: uiText('Prefer research'),
    role: uiText('Target job'),
    kind: uiText('Practice focus'),
    status: uiText('Stage'),
    note: uiText('Notes'),
    nextAction: uiText('Next action'),
    nextActionAt: uiText('Action date'),
    answer: uiText('Answer'),
    request: uiText('Request'),
    keyword: uiText('Keywords'),
    query: uiText('Keywords'),
    location: uiText('Work location'),
    nature: uiText('Job type'),
    site: uiText('Company website'),
    questionIndex: uiText('Question index (starts at 0)'),
    nextQuestion: uiText('Move to next question'),
    taskTitles: uiText('Confirm completed tasks'),
  }
  const rows = Object.entries(fields).flatMap(([key, label]) => {
    const raw = data[key]
    if (raw === undefined || raw === null) return []
    const value =
      key === 'status'
        ? (applicationStages[raw as ApplicationStatus] ?? String(raw))
        : Array.isArray(raw)
          ? raw.join('、')
          : typeof raw === 'boolean'
            ? raw
              ? uiText('Yes')
              : uiText('No')
            : String(raw)
    return [{ label, value: value || uiText('Clear') }]
  })
  const accepted = Array.isArray(data.accepted) ? data.accepted.length : 0
  const edited = data.edited && typeof data.edited === 'object' ? Object.values(data.edited) : []
  if (accepted || edited.length) {
    rows.push({
      label: uiText('Accept suggestion'),
      value: uiText('{0} original suggestions, {1} edited suggestions', [accepted, edited.length]),
    })
    for (const edit of edited) {
      if (edit && typeof edit === 'object' && 'value' in edit)
        rows.push({ label: uiText('Edited content'), value: String(edit.value) })
    }
  }
  return rows
}
