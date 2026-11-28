import { expect, it } from 'vitest'
import { suggestedResumeTitle } from './resumeTitle'
import type { ServerResume } from '@/shared/types/resume'

function resume(patch: Partial<ServerResume>): ServerResume {
  return {
    id: 'rs1',
    title: 'demo-resume',
    parseStatus: 'READY',
    parseProgress: 100,
    parseError: null,
    currentVersionId: 'v1',
    fileName: 'demo-resume.pdf',
    createdAt: '',
    updatedAt: '',
    currentVersion: {
      id: 'v1',
      versionNo: 1,
      parentVersionId: null,
      source: 'UPLOAD',
      structured: {
        basics: { name: '林晓舟', title: 'Java 后端实习', phone: '', email: '', city: '', links: [] },
        education: [],
        experience: [],
        projects: [],
        skills: [],
        certificates: [],
        summary: '',
      },
      plainText: '',
      parseMeta: {},
      createdAt: '',
    },
    ...patch,
  }
}

it('names a parsed upload from the person and objective, and leaves a custom title', () => {
  expect(suggestedResumeTitle(resume({}))).toBe('林晓舟 · Java 后端实习')
  expect(suggestedResumeTitle(resume({ title: '投递用简历' }))).toBeNull()
  expect(
    suggestedResumeTitle(
      resume({
        currentVersion: {
          ...resume({}).currentVersion!,
          structured: {
            ...resume({}).currentVersion!.structured,
            basics: { name: '', title: '', phone: '', email: '', city: '', links: [] },
          },
        },
      }),
    ),
  ).toBe('未命名简历')
  expect(suggestedResumeTitle(resume({ parseStatus: 'PARSING' }))).toBeNull()
})
