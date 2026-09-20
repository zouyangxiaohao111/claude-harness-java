import { api, BASE_URL } from './rest'
import type { ChatMessageDto, MessageCreatedResponse, PartialCompactRequest, PartialCompactResponse, QueuePopResponse, SendMessageRequest } from './types'

export const chatApi = {
  listMessages: (sessionId: string) =>
    api<ChatMessageDto[]>(`/sessions/${encodeURIComponent(sessionId)}/messages`),
  /** [window-paging] 有界历史窗口分页：缺省 = 尾页（最新 limit 条）；beforeMessageId = 该消息之前更早一页。
   *  返回 hasMore + total（total = 会话非 meta 消息总数 · 轨迹徽标全量用，避免拿已加载页当全量）。
   *  前端查看主通道（打开/F5/切会话/断连补偿）用此，不再全量拉。 */
  listMessagesPage: (sessionId: string, opts?: { limit?: number; beforeMessageId?: string }) =>
    api<{ messages: ChatMessageDto[]; hasMore: boolean; total: number }>(
      `/sessions/${encodeURIComponent(sessionId)}/messages/page?limit=${opts?.limit ?? 50}`
        + (opts?.beforeMessageId ? `&beforeMessageId=${encodeURIComponent(opts.beforeMessageId)}` : '')),
  /** [trace-count] 会话消息总数（轻量 GET /messages/count · DB sessions.messageCount 非 meta 口径 · 轨迹徽标轮询用） */
  messageCount: (sessionId: string) =>
    api<{ total: number }>(`/sessions/${encodeURIComponent(sessionId)}/messages/count`),
  send: (sessionId: string, req: SendMessageRequest) =>
    api<MessageCreatedResponse>(`/sessions/${encodeURIComponent(sessionId)}/messages`, { method: 'POST', body: req }),
  removeMessage: (sessionId: string, messageId: string) =>
    api<void>(`/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}`, { method: 'DELETE' }),
  /** 对话裁剪：删除 pivot 起全部消息并旋转 conversationId（后端已实现 · 前端直接 setMessages + 刷新 row key） */
  trimAfter: (sessionId: string, messageId: string) =>
    api<PartialCompactResponse>(`/sessions/${encodeURIComponent(sessionId)}/messages/after/${encodeURIComponent(messageId)}`, { method: 'DELETE' }),
  cancel: (sessionId: string) =>
    api<void>(`/sessions/${encodeURIComponent(sessionId)}/cancel`, { method: 'POST' }),
  /** [C6] 会话服务端运行态（GET /sessions/{id}/running → {running}）—— 停止键可见性的权威源。
   *  **非轮询**：只在【载入 / 切会话 / 重连】各查一次，用于重建「本页未发送 / 后台 drain 起的 run」；
   *  运行中的实时翻转走 session.status 事件（thinking/streaming → 运行中，idle → 空闲）。
   *  响应只有一个布尔（后端契约：不含敏感信息）。 */
  sessionRunning: (sessionId: string) =>
    api<{ running: boolean }>(`/sessions/${encodeURIComponent(sessionId)}/running`),
  background: (sessionId: string, req?: SendMessageRequest) =>
    api<{ taskId: string }>(`/sessions/${encodeURIComponent(sessionId)}/background`, { method: 'POST', body: req ?? undefined }),
  partialCompact: (sessionId: string, req: PartialCompactRequest) =>
    api<PartialCompactResponse>(`/sessions/${encodeURIComponent(sessionId)}/partial-compact`, { method: 'POST', body: req }),
  /** F19/#3 排队命令：弹出全部【可编辑】的排队命令（批 A5 · 对齐 CC PopAllEditableResult）。
   *  `currentInput` = 输入框当前草稿（CC `popAllEditable(currentInput, ...)` 的实参，参与 `\n` join）——
   *  ⛔ 不可省：后端用它把草稿接在排队项后面，省掉=按 Esc 静默清掉用户草稿。 */
  popEditableQueuedCommand: (sessionId: string, currentInput?: string) =>
    api<QueuePopResponse>(`/sessions/${encodeURIComponent(sessionId)}/queue/pop`,
      { method: 'POST', body: { currentInput: currentInput ?? '' } }),
  /** 重拉后按 imagePasteIds 批量拉图（后端 POST /attachments/image/batch/{sessionId} · body {ids} · miss 缺席） */
  fetchImagesBatch: (sessionId: string, ids: string[]) =>
    api<Record<string, { mediaType: string; base64: string }>>(`/attachments/image/batch/${encodeURIComponent(sessionId)}`, { method: 'POST', body: { ids } }),
}

/** 上传附件（multipart · 后端 U1）：大文件（>5MB）先落盘 → { contentId, filename, size }。
 *  sessionId：归属会话。**[批 3a] 由可选改必填** —— 旧实现 `if (sessionId) fd.append(...)` 在缺值时
 *  **静默丢弃**会话字段，后端回落 'unknown' ⇒ 附件归属错位（用户看不到，也没有任何报错）。
 *  现由调用方（Composer）在无会话时显式拒绝上传并提示，而非静默丢归属。 */
export async function uploadAttachment(file: File, sessionId: string): Promise<{ contentId: string; filename: string; size: number }> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('sessionId', sessionId)
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
