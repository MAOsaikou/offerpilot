import { afterEach, expect, it, vi } from 'vitest'
import { uploadKnowledgeDocument, getKnowledgeOriginal } from './knowledge'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it('validates files then posts authenticated multipart and downloads the owner original', async () => {
  localStorage.setItem('offerpilot-guest-v1', 'kb-owner')
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const file of [
    new File([], 'empty.txt'),
    new File(['x'], 'x.docx'),
    new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.pdf'),
  ])
    await expect(uploadKnowledgeDocument(file)).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
  const note = { id: 'note-1', indexStatus: 'QUEUED', sourceFilename: '资料.md' }
  fetch.mockResolvedValueOnce(new Response(JSON.stringify({ data: note })))
  const file = new File(['中文经验'], '资料.md', { type: 'text/markdown' })
  expect(await uploadKnowledgeDocument(file, '项目经历')).toEqual(note)
  const [url, request] = fetch.mock.calls[0]!
  expect(url).toContain('/knowledge/notes/upload')
  expect(request.method).toBe('POST')
  expect(request.headers.Authorization).toBe('Bearer kb-owner')
  expect(request.headers['Content-Type']).toBeUndefined()
  expect(request.body.get('file')).toBe(file)
  expect(request.body.get('category')).toBe('项目经历')
  fetch.mockResolvedValueOnce(new Response('original'))
  expect(await (await getKnowledgeOriginal('note/1')).text()).toBe('original')
  expect(fetch.mock.calls[1]![0]).toContain('/notes/note%2F1/file')
  fetch.mockResolvedValueOnce(
    new Response(JSON.stringify({ code: 10001, message: '文本文件必须是 UTF-8 编码' }), {
      status: 400,
    }),
  )
  await expect(uploadKnowledgeDocument(file)).rejects.toThrow('UTF-8')
})
