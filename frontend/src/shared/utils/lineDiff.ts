import { translate as uiText } from '@/shared/utils/language'
export type DiffLine = { kind: 'same' | 'add' | 'del'; text: string }

/** Line diff by longest common subsequence. Inputs beyond the cap are shown as a whole replacement. */
export function diffLines(before: string, after: string, cap = 1500): DiffLine[] {
  const a = before.split('\n')
  const b = after.split('\n')
  if (a.length > cap || b.length > cap)
    return [
      ...a.map((text) => ({ kind: 'del' as const, text })),
      ...b.map((text) => ({ kind: 'add' as const, text })),
    ]
  // lcs[i][j] = LCS length of a[i..] and b[j..]
  const lcs = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      lcs[i]![j] =
        a[i] === b[j] ? lcs[i + 1]![j + 1]! + 1 : Math.max(lcs[i + 1]![j]!, lcs[i]![j + 1]!)
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i]! })
      i++
      j++
    } else if (lcs[i + 1]![j]! >= lcs[i]![j + 1]!) out.push({ kind: 'del', text: a[i++]! })
    else out.push({ kind: 'add', text: b[j++]! })
  }
  while (i < a.length) out.push({ kind: 'del', text: a[i++]! })
  while (j < b.length) out.push({ kind: 'add', text: b[j++]! })
  return out
}

const sectionLabels = (): Record<string, string> => ({
  education: uiText("Education section"),
  experience: uiText("Experience section"),
  projects: uiText("Projects"),
  skills: uiText("Skills"),
})
/**
 * Stable, field-labelled text of a structured resume for comparison. Saved plain text keeps the
 * uploaded document's layout for the first version, so diffing it against later versions would show
 * layout noise instead of real edits.
 */
export function resumeLines(structure: {
  basics?: Record<string, string | string[]>
  summary?: string
  certificates?: string[]
  [section: string]: unknown
}): string {
  const lines: string[] = []
  const value = (v: unknown) => (Array.isArray(v) ? v.join('、') : String(v ?? ''))
  const labels: Record<string, string> = {
    name: uiText("Name"),
    phone: uiText("Phone"),
    email: uiText("Email"),
    city: uiText("City"),
    links: uiText("Links"),
  }
  for (const [key, label] of Object.entries(labels)) {
    const v = value(structure.basics?.[key])
    if (v) lines.push(`${label}：${v}`)
  }
  if (structure.summary) lines.push(uiText('Self-evaluation: {0}', [structure.summary]))
  for (const [section, label] of Object.entries(sectionLabels())) {
    const entries = (structure[section] as Record<string, unknown>[] | undefined) ?? []
    entries.forEach((entry, index) => {
      for (const [field, v] of Object.entries(entry)) {
        if (Array.isArray(v))
          v.forEach((item) => item && lines.push(`${label}${index + 1} · ${field}：${item}`))
        else if (v) lines.push(`${label}${index + 1} · ${field}：${value(v)}`)
      }
    })
  }
  for (const cert of structure.certificates ?? []) if (cert) lines.push(uiText('Certificate: {0}', [cert]))
  return lines.join('\n')
}
