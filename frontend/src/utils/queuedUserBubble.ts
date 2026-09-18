/**
 * [busy 气泡附件胶囊] `queue.drained` 事件 → live 气泡的**纯构造**。
 *
 * <b>缺陷（2026-09-18 实测）</b>：busy（agent 正在流式输出）时发的消息走
 * 「入队 → 工具边界 drained 再 append 气泡」（`App.tsx` `handleQueueDrained`），而该回调**只用
 * `{uuid, content}` 造气泡** ⇒ 气泡上**没有附件胶囊**；用户必须按 F5（读侧 `GET /messages` 出站
 * `user_attachments`）才看得到刚发的 Word/Excel/视频。
 *
 * <b>数据源 = `queue.drained` 事件的 `drained[].userAttachments`（后端权威快照）</b>
 * <ul>
 *   <li>后端 `QueueEventPublisher.emitDrained` 出站该字段：取自 `QueueItem.userAttachments`
 *       （`ChatService.enqueueBusyPrompt` 第 14 参，即 `resolveAttachments` 之后的**已解析列表**），
 *       并经 `MessageService.resolveAttachmentUrls` 补 url（与 `GET /messages` 读侧**同一投影**）
 *       ⇒ 本模块只做**透传 + 形状搬运**，不重算任何字段。</li>
 *   <li>⛔ <b>为什么不从请求体自算</b>（曾实现、已否决）：`Composer.doSend` 的
 *       `...(a.contentId ? { contentId: a.contentId } : {})` + `...(a.path ? { path: a.path } : {})`
 *       ⇒ **path 附件**（`addPaths` local-read 通道，`Composer.tsx:489-492`）在请求体里**只有 path、
 *       没有 contentId**（contentId 要 `resolveAttachments` 注册附件表之后才有）。前端按请求体自算
 *       必然得到 `contentId=null / url=null` ⇒ 胶囊点了没反应，**且与 F5 后的两态不一致**（比「都没有」
 *       更坏）。事件快照天然没有这个问题。</li>
 *   <li>因此**没有前端暂存（stash）**：权威源已在事件里，前端再存一份 = 平行真源（两处迟早漂移）。</li>
 * </ul>
 *
 * <b>边界（如实登记）</b>：事件**不带** `userAttachments` 键 = 该排队项无附件快照（纯文本 busy）⇒
 * 气泡 `userAttachments` 为 `null`，不伪造空数组。后端在「非空才出站」这一点上已保证。
 */

import type { ChatMessageDto } from '@/api/types'

/**
 * 事件快照项 → 气泡附件胶囊（结构 = `ChatMessageDto['userAttachments']` 元素）。
 *
 * ⛔ **不另立联合类型**：必须能直接塞进 `ChatMessageDto.userAttachments`，多一份定义迟早与契约漂移。
 * 字段与后端 `ChatMessageDto.UserAttachmentInfo`（type/filename/mediaType/contentId/url）一一对应。
 */
export interface UserAttachmentCapsule {
  type: string
  filename: string
  mediaType: string | null
  contentId: string | null
  url: string | null
}

/** `queue.drained` 事件的单条（{@link parseDrainedQueueItems} 归一后的形状）。 */
export interface DrainedQueueItem {
  uuid?: string
  content: string
  /** 后端权威快照；**键缺失/null = 无附件快照**（纯文本 busy / 非 busy workload） */
  userAttachments: readonly UserAttachmentCapsule[] | null
}

/** 事件快照 → 气泡胶囊（纯搬运；`null`/空 → `null`，绝不伪造空数组）。 */
function toCapsules(
  wire: readonly UserAttachmentCapsule[] | null | undefined,
): UserAttachmentCapsule[] | null {
  if (!wire || wire.length === 0) return null
  return wire.map((a) => ({
    type: a.type,
    filename: a.filename,
    mediaType: a.mediaType ?? null,
    contentId: a.contentId ?? null,
    url: a.url ?? null,
  }))
}

/**
 * `queue.drained` **原始 STOMP 载荷**（`JSON.parse(msg.body).drained`）→ {@link DrainedQueueItem}[]。
 *
 * 抽成纯函数的**理由**：这一段是「后端字段 → (wire 归一) → 气泡」链路里唯一不可见的一跳
 * （原实现在 `useChatSocket` 的订阅闭包内联，无法单测）⇒ 归一化写错（丢字段、字符串化 null）时
 * 单测全绿而线上胶囊消失。抽出来后整条链（raw JSON → 气泡）在一个测试里可断言。
 *
 * ⛔ **只做形状归一，不做任何推导**：`contentId`/`url` 原样搬运（`url` 由后端
 * `MessageService.resolveAttachmentUrls` 拼，前端重算即平行真源）。
 *
 * @param raw 已 `JSON.parse` 的 drained 数组（`useChatSocket` 保证是数组；元素可能不规范 ⇒ 逐字段兜底）
 */
export function parseDrainedQueueItems(raw: readonly Record<string, unknown>[]): DrainedQueueItem[] {
  return raw.map((d) => ({
    uuid: d.uuid != null ? String(d.uuid) : undefined,
    content: String(d.content ?? ''),
    // 无快照统一为 null（缺键 / null / 非数组 / 空数组 四种形态归一 —— 下游只判 null，
    //   不给「空数组也画出空行」留口子）
    userAttachments: Array.isArray(d.userAttachments) && d.userAttachments.length > 0
      ? (d.userAttachments as Record<string, unknown>[]).map((a) => ({
          type: String(a.type ?? ''),
          filename: String(a.filename ?? ''),
          mediaType: a.mediaType != null ? String(a.mediaType) : null,
          contentId: a.contentId != null ? String(a.contentId) : null,
          url: a.url != null ? String(a.url) : null,
        }))
      : null,
  }))
}

/**
 * `queue.drained` → 待 append 的 user 气泡列表（**带附件胶囊**）。
 *
 * 顺序与语义逐字沿用原 `handleQueueDrained` 内联实现（防重 + uuid 即气泡 id + 兜底 id），
 * 唯一新增 = 把事件里的权威快照搬到 `userAttachments`（本模块存在的理由）。
 *
 * @param sessionId  事件归属会话（气泡 sessionId）
 * @param drained    已消费的排队命令（含后端快照）
 * @param existingIds store 里已有的消息 id（重复项跳过：防 drained 重发 / 与重拉重复）
 */
export function buildDrainedUserMessages(
  sessionId: string,
  drained: readonly DrainedQueueItem[],
  existingIds: ReadonlySet<string>,
): ChatMessageDto[] {
  return drained
    .filter((d) => !(d.uuid && existingIds.has(d.uuid)))
    .map((d) => {
      const msgId = d.uuid ?? `queued-${sessionId}-${Date.now()}-${Math.random().toString(36).slice(2)}`
      return {
        id: msgId, sessionId, role: 'user' as const, author: '你', content: d.content,
        reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: null, time: null,
        toolCallId: null, assistantMessageId: null, userMessageId: msgId, subtype: null, isMeta: false, isApiErrorMessage: false,
        apiError: null, error: null, errorDetails: null, matchedRule: null,
        userAttachments: toCapsules(d.userAttachments),
      }
    })
}
