import type { RealJob } from '@/shared/types/jobs'
import type { Job } from '@/shared/types/workspace'
export function localJobSnapshot(job: RealJob): Job {
  return {
    id: job.id,
    title: job.title,
    company: job.company,
    city: job.locations || '地点未提供',
    salary: '薪资以官网为准',
    category: job.category,
    tags: [job.nature === 'campus' ? '校招' : job.nature === 'intern' ? '实习' : '企业招聘'],
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
