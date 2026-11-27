import { translate as uiText } from '@/shared/utils/language'
import type { ServerResume } from '@/shared/types/resume'

/** Same stem the upload API stores when the user does not supply a title. */
export function filenameStem(fileName: string) {
  const base = fileName.replaceAll('\\', '/').split('/').pop() ?? fileName
  return base.replace(/\.[^.]+$/, '')
}

function limitTitle(value: string) {
  const points = [...value]
  return points.length <= 100 ? value : points.slice(0, 100).join('')
}

/**
 * A title that is still the uploaded filename can take the parsed name and objective.
 * A title the user already changed is left alone. Empty parsed fields use a Chinese fallback.
 */
export function suggestedResumeTitle(resume: ServerResume) {
  if (resume.parseStatus !== 'READY' || !resume.currentVersion) return null
  const stem = filenameStem(resume.fileName).trim()
  const current = resume.title.trim()
  if (!stem || (current !== stem && current !== resume.fileName.trim())) return null
  const basics = resume.currentVersion.structured.basics
  const name = basics.name?.trim() ?? ''
  const role = basics.title?.trim() ?? ''
  const next = limitTitle(name && role ? `${name} · ${role}` : name || role || uiText('Untitled resume'))
  return next === current ? null : next
}
