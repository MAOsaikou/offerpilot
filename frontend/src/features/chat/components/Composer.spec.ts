import { createApp, h, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Composer from './Composer.vue'
import { uploadChatImage } from '@/shared/api/attachments'

vi.mock('@/shared/api/attachments', async (original) => ({
  ...(await original<typeof import('@/shared/api/attachments')>()),
  uploadChatImage: vi.fn(),
}))
URL.createObjectURL = vi.fn(() => 'blob:preview')
URL.revokeObjectURL = vi.fn()
let root: HTMLDivElement
afterEach(() => root?.remove())

function choose(input: HTMLInputElement, files: File[]) {
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  input.dispatchEvent(new Event('change'))
}

it('选图后先上传，发送时带上 fileId 并清空；非图片给出提示', async () => {
  const sent: unknown[][] = []
  vi.mocked(uploadChatImage).mockResolvedValue({ fileId: 'file_1', name: 'jd.png' })
  root = document.createElement('div')
  document.body.append(root)
  createApp({
    render: () => h(Composer, { onSend: (...args: unknown[]) => sent.push(args) }),
  }).mount(root)
  const input = root.querySelector<HTMLInputElement>('input[type=file]')!

  choose(input, [new File(['x'], 'a.txt', { type: 'text/plain' })])
  await nextTick()
  expect(root.textContent).toContain('仅支持 PNG、JPG、WebP 图片')
  expect(uploadChatImage).not.toHaveBeenCalled()

  choose(input, [new File(['png'], 'jd.png', { type: 'image/png' })])
  await vi.waitFor(() =>
    expect(root.querySelectorAll('.composer-images figure:not(.uploading)')).toHaveLength(1),
  )
  const field = root.querySelector('textarea')!
  field.value = '帮我看看这张截图'
  field.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector<HTMLButtonElement>('[aria-label="发送"]')!.click()
  expect(sent).toEqual([['帮我看看这张截图', [{ fileId: 'file_1', name: 'jd.png' }]]])
  await nextTick()
  expect(root.querySelector('.composer-images')).toBeNull()
})
