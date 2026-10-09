import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadResume, validateResumeFile, saveResume, exportResumePdf } from './resumes'
import type { ResumeStructure } from '@/shared/types/resume'

beforeEach(() => localStorage.setItem('offerpilot-guest-v1', 'resume-owner'))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
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

describe('PDF 导出', () => {
  function downloadMocks() {
    const create = vi.fn().mockReturnValue('blob:resume')
    const revoke = vi.fn()
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = create
        static revokeObjectURL = revoke
      },
    )
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    return { create, revoke, click }
  }
  it('携带身份请求二进制 PDF，按 UTF-8 服务端文件名下载', async () => {
    vi.useFakeTimers()
    const { create, revoke, click } = downloadMocks()
    const fetch = vi.fn().mockResolvedValue(
      new Response('%PDF-fixture', {
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition':
            "attachment; filename*=UTF-8''" + encodeURIComponent('林小雨/简历.pdf'),
        },
      }),
    )
    vi.stubGlobal('fetch', fetch)
    await exportResumePdf('rs/1')
    expect(fetch.mock.calls[0]?.[0]).toContain('/resumes/rs%2F1/export.pdf?template=classic')
    expect(new Headers(fetch.mock.calls[0]?.[1].headers).get('Authorization')).toBe(
      'Bearer resume-owner',
    )
    expect(new Headers(fetch.mock.calls[0]?.[1].headers).get('Accept')).toBe('application/pdf')
    expect(create.mock.calls[0]?.[0].type).toBe('application/pdf')
    expect(await create.mock.calls[0]?.[0].text()).toBe('%PDF-fixture')
    expect((click.mock.instances[0] as HTMLAnchorElement | undefined)?.download).toBe(
      '林小雨_简历.pdf',
    )
    vi.advanceTimersByTime(1000)
    expect(revoke).toHaveBeenCalledWith('blob:resume')
  })
  it('沿用业务错误，不下载错误响应或重放请求', async () => {
    const { create } = downloadMocks()
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 30401, message: '请等待解析完成' }), { status: 409 }),
      )
    vi.stubGlobal('fetch', fetch)
    await expect(exportResumePdf('rs1')).rejects.toThrow('请等待解析完成')
    expect(create).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('拒绝 PDF 以外的成功响应', async () => {
    const { create } = downloadMocks()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response('{}', { headers: { 'Content-Type': 'application/json' } })),
    )
    await expect(exportResumePdf('rs1')).rejects.toThrow('响应格式错误')
    expect(create).not.toHaveBeenCalled()
  })
  it('读取二进制时身份切换，迟到的 PDF 不触发下载', async () => {
    const { create } = downloadMocks()
    const response = new Response('%PDF', { headers: { 'Content-Type': 'application/pdf' } })
    vi.spyOn(response, 'blob').mockImplementation(async () => {
      localStorage.setItem('offerpilot-guest-v1', 'new-owner')
      return new Blob(['%PDF'])
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    await expect(exportResumePdf('rs1')).rejects.toThrow('身份已切换')
    expect(create).not.toHaveBeenCalled()
  })
  it('缺少服务端文件名时使用 PDF 默认名', async () => {
    const { click } = downloadMocks()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('%PDF', { headers: { 'Content-Type': 'application/pdf' } }),
        ),
    )
    await exportResumePdf('rs1')
    expect((click.mock.instances[0] as HTMLAnchorElement | undefined)?.download).toBe('简历.pdf')
  })
})
