import { translate as uiText } from '@/shared/utils/language'
import type { RealJob } from '@/shared/types/jobs'
import type { Job } from '@/shared/types/workspace'
export function localJobSnapshot(job: RealJob): Job {
  return {
    id: job.id,
    title: job.title,
    company: job.company,
    city: job.locations || uiText("Location not provided"),
    salary: uiText("Check company website for salary"),
    category: job.category,
    tags: [job.nature === 'campus' ? uiText("Graduate") : job.nature === 'intern' ? uiText("Internship") : uiText("Company recruitment")],
    description: job.description,
    color: '#3f67eb',
  }
}
export function officialJobUrl(url: string): string | undefined {
  try {
    const parsed = new URL(url)
    return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password
      ? parsed.href
      : undefined
  } catch {
    return undefined
  }
}
