/** Drop the unfinished narrative suffix persisted on older reports. */
export function tidyReportNote(note: string): string {
  return note
    .replace(/[；;]\s*模型总结\s*$/u, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}
