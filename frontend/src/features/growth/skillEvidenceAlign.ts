import { getSkillGap, type SkillGapReport } from '@/shared/api/skill-gap'

export type EvidenceLevel = SkillGapReport['skills'][number]['level']

let pending: Promise<SkillGapReport | null> | null = null

/** One shared read of the same analysis the career page runs. Failures keep the card's own numbers. */
export function loadLiveSkillLevels() {
  pending ??= getSkillGap({}).catch(() => null)
  return pending
}

export function resetLiveSkillLevels() {
  pending = null
}

export function alignCurrent(current: number, level: EvidenceLevel | undefined) {
  const safe = Number.isFinite(current) ? Math.min(5, Math.max(0, current)) : 0
  if (level === 'POSSESSED') return Math.max(safe, 5)
  if (level === 'PARTIAL') return Math.max(safe, 1)
  return safe
}

function normalize(name: string) {
  return name.normalize('NFKC').trim().toLowerCase().replace(/\s+/g, ' ')
}

export function levelFor(report: SkillGapReport | null | undefined, name: string) {
  if (!report) return undefined
  const key = normalize(name)
  return report.skills.find((skill) => normalize(skill.name) === key)?.level
}
