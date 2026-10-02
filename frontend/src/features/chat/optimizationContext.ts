import type { ChatMessageView } from './types'

/** Reuse a visible saved suggestion as discussion context; explicit resource selection wins. */
export function optimizationContext(messages: ChatMessageView[], selected: Record<string, string>) {
  if (selected.optimizationReportId || selected.diagnosisReportId) return selected
  for (const message of [...messages].reverse()) {
    for (const card of [...(message.cards || [])].reverse()) {
      if (card.cardType !== 'resume_diff' || !card.payload || typeof card.payload !== 'object')
        continue
      const value = card.payload as Record<string, unknown>
      const keys = ['resumeId', 'resumeVersionId', 'reportId'] as const
      if (
        !keys.every(
          (key) =>
            typeof value[key] === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value[key] as string),
        )
      )
        continue
      if (selected.resumeId && selected.resumeId !== value.resumeId) continue
      if (selected.resumeVersionId && selected.resumeVersionId !== value.resumeVersionId) continue
      return {
        ...selected,
        resumeId: value.resumeId as string,
        resumeVersionId: value.resumeVersionId as string,
        optimizationReportId: value.reportId as string,
      }
    }
  }
  return selected
}
