import {
  apiUrl,
  assertResponseIdentity,
  authenticatedHeaders,
  fetchWithIdentity,
  toApiError,
} from './client'

export interface ChatImage {
  fileId: string
  name: string
  mime?: string
  size?: number
}

export const CHAT_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']
export const CHAT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
export const CHAT_IMAGE_MAX_COUNT = 3

/** AG-02：上传一张聊天图片，返回 fileId，随下一条消息的 attachments 发送 */
export async function uploadChatImage(file: File): Promise<ChatImage> {
  const body = new FormData()
  body.append('file', file)
  const response = await fetchWithIdentity(apiUrl('/agent/attachments'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  assertResponseIdentity(response)
  return ((await response.json()) as { data: ChatImage }).data
}

/** 图片接口需要登录头，<img> 不能直接引用；取回后转成本地 object URL */
export async function chatImageUrl(fileId: string): Promise<string> {
  const response = await fetchWithIdentity(
    apiUrl(`/agent/attachments/${encodeURIComponent(fileId)}`),
    { headers: await authenticatedHeaders() },
  )
  if (!response.ok) throw await toApiError(response)
  assertResponseIdentity(response)
  return URL.createObjectURL(await response.blob())
}
