/** Drop persisted internal ids from material-suggestion sentences. */
export function hideInternalIds(text: string) {
  return text
    .replace(/简历版本\s+[A-Za-z]{1,16}_[0-9A-Za-z]{8,}\s*/g, '这份简历')
    .replace(/GitHub 快照\s+[A-Za-z0-9_-]{6,}[，,]?\s*/g, 'GitHub 快照：')
    .replace(/\b[A-Za-z]{1,16}_[0-9A-Za-z]{10,}\b/g, '')
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/\s+([，。；])/g, '$1')
    .trim()
}
