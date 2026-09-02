import { api, BASE_URL } from './rest'
import type { ChatMessageDto, MessageCreatedResponse, PartialCompactRequest, PartialCompactResponse, SendMessageRequest } from './types'

export const chatApi = {
  listMessages: (sessionId: string) =>
    api<ChatMessageDto[]>(`/sessions/${encodeURIComponent(sessionId)}/messages`),
  send: (sessionId: string, req: SendMessageRequest) =>
    api<MessageCreatedResponse>(`/sessions/${encodeURIComponent(sessionId)}/messages`, { method: 'POST', body: req }),
  removeMessage: (sessionId: string, messageId: string) =>
    api<void>(`/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}`, { method: 'DELETE' }),
  /** 对话裁剪：删除 pivot 起全部消息并旋转 conversationId（后端已实现 · 前端直接 setMessages + 刷新 row key） */
  trimAfter: (sessionId: string, messageId: string) =>
    api<PartialCompactResponse>(`/sessions/${encodeURIComponent(sessionId)}/messages/after/${encodeURIComponent(messageId)}`, { method: 'DELETE' }),
  cancel: (sessionId: string) =>
    api<void>(`/sessions/${encodeURIComponent(sessionId)}/cancel`, { method: 'POST' }),
  background: (sessionId: string, req?: SendMessageRequest) =>
    api<{ taskId: string }>(`/sessions/${encodeURIComponent(sessionId)}/background`, { method: 'POST', body: req ?? undefined }),
  partialCompact: (sessionId: string, req: PartialCompactRequest) =>
    api<PartialCompactResponse>(`/sessions/${encodeURIComponent(sessionId)}/partial-compact`, { method: 'POST', body: req }),
  // F19/#3 排队命令：弹出可编辑的排队命令（后端 B4 未接，先封装；调用失败优雅降级）
  popEditableQueuedCommand: (sessionId: string) =>
    api<{ content: string; mode?: string } | null>(`/sessions/${encodeURIComponent(sessionId)}/queue/pop`, { method: 'POST' }),
  /** 重拉后按 imagePasteIds 批量拉图（后端 POST /attachments/image/batch/{sessionId} · body {ids} · miss 缺席） */
  fetchImagesBatch: (sessionId: string, ids: string[]) =>
    api<Record<string, { mediaType: string; base64: string }>>(`/attachments/image/batch/${encodeURIComponent(sessionId)}`, { method: 'POST', body: { ids } }),
}

/** 上传附件（multipart · 后端 U1）：大文件（>5MB）先落盘 → { contentId, filename, size }。
 *  sessionId：归属会话（上传须在会话内进行，否则后端 session=null 兜底 'unknown'，附件归属错位） */
export async function uploadAttachment(file: File, sessionId?: string): Promise<{ contentId: string; filename: string; size: number }> {
  const fd = new FormData()
  fd.append('file', file)
  if (sessionId) fd.append('sessionId', sessionId)
  const res = await fetch(`${BASE_URL}/attachments/upload`, {
    method: 'POST',
    headers: { 'X-Client-Env': 'react' },  // FormData 自动设 multipart boundary，不手动 Content-Type
    body: fd,
  })
  if (!res.ok) {
    const txt = await res.text().catch(() => '')
    throw new Error(`附件上传失败 (${res.status}): ${txt.slice(0, 200)}`)
  }
  return await res.json()
}
