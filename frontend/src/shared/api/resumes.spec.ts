import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadResume, validateResumeFile, saveResume } from './resumes'
import type { ResumeStructure } from '@/shared/types/resume'

beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'resume-owner'))
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
describe('简历上传与保存', () => {
  it('拒绝空文件、超限文件和不支持的类型，不发送请求', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await expect(uploadResume(new File([], 'empty.pdf'))).rejects.toThrow('为空')
    await expect(uploadResume(new File(['x'], 'resume.exe'))).rejects.toThrow('支持')
    expect(
      validateResumeFile(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.pdf')),
    ).toContain('10MB')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('上传携带原始文件与身份，浏览器自动生成 multipart boundary', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ data: { id: 'rs-1', parseStatus: 'QUEUED' } })),
      )
    vi.stubGlobal('fetch', fetch)
    const file = new File(['resume'], '我的简历.docx')
    expect((await uploadResume(file)).id).toBe('rs-1')
    const options = fetch.mock.calls[0]?.[1] as RequestInit
    expect((options.headers as Record<string, string>).Authorization).toBe('Bearer resume-owner')
    expect((options.headers as Record<string, string>)['Content-Type']).toBeUndefined()
    expect((options.body as FormData).get('file')).toBe(file)
  })
  it('保存冲突不自动重试覆盖新版本', async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 30403, message: '简历已更新，请重新加载后保存' }), {
        status: 409,
      }),
    )
    vi.stubGlobal('fetch', fetch)
    const data: ResumeStructure = {
      basics: { name: '', phone: '', email: '', city: '', links: [] },
      education: [],
      experience: [],
      projects: [],
      skills: [],
      certificates: [],
      summary: '',
    }
    await expect(saveResume('rs-1', 'old', data)).rejects.toThrow('重新加载')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
