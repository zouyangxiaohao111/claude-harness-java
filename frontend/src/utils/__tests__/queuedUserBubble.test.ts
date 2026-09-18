import { describe, expect, it } from 'vitest'
import { buildDrainedUserMessages, parseDrainedQueueItems, type DrainedQueueItem } from '../queuedUserBubble'

/**
 * [busy 气泡附件胶囊] `queue.drained` → live 气泡的守卫。
 *
 * <b>被钉住的缺陷（2026-09-18 实测）</b>：busy（agent 正在流式输出）时发的消息走「入队 → drained
 * 再 append 气泡」，而 `handleQueueDrained` 只用 `{uuid, content}` 造气泡 ⇒ 气泡上**没有附件胶囊**，
 * 必须按 F5（后端已把快照落 `messages.user_attachments`）才出现。
 *
 * <b>⭐ 本组最重要的判据：`path` 附件</b>。它是「必须用后端权威快照、不能用前端自算」的决定性理由：
 * `Composer.doSend` 组装请求体时 `...(a.contentId ? { contentId } : {})`，而 `addPaths` 的 local-read
 * `path` 腿（`Composer.tsx:489-492`）只 push `{type, filename, mediaType, path, size}` —— **没有
 * contentId**（contentId 要 `resolveAttachments` 注册附件表之后才有）。⇒ 前端按请求体自算必得
 * `contentId=null / url=null`（胶囊点了没反应），而 F5 后（后端读侧回填 url）又可点 —— **两态不一致**。
 * 后端随事件发来的快照来自**已解析列表**（path 附件已带 contentId + 已拼 url）⇒ live 与 F5 一致。
 */

/** 后端 `queue.drained` 出站的一条快照（= `ChatMessageDto.UserAttachmentInfo` 的 JSON 形状）。 */
type WireSnapshot = NonNullable<DrainedQueueItem['userAttachments']>[number]

/** path 附件（local-read 大 Word）：已解析 ⇒ contentId 非空、url 已由后端拼好。 */
const PATH_DOCX: WireSnapshot = {
  type: 'file',
  filename: '季度报表.docx',
  mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  contentId: '4711',
  url: '/attachments/content/sess-1/4711',
}

describe('buildDrainedUserMessages：drained 事件（含后端快照）→ 带附件胶囊的 user 气泡', () => {
  it('⭐ path 附件（已解析）：live 气泡胶囊 contentId 非空且 url 可点（= F5 重拉后的同一形态）', () => {
    const msgs = buildDrainedUserMessages(
      'sess-1',
      [{ uuid: 'q1', content: '看这个报表', userAttachments: [PATH_DOCX] }],
      new Set(),
    )
    expect(msgs).toHaveLength(1)
    expect(msgs[0].userAttachments).toEqual([PATH_DOCX])
    // 关键两字段（缺陷就是它们恒缺失/恒为 null）
    expect(msgs[0].userAttachments?.[0].contentId, 'path 附件必须有 contentId').toBe('4711')
    expect(msgs[0].userAttachments?.[0].url, 'url 必须可拼/可点').toBe('/attachments/content/sess-1/4711')
  })

  it('⭐ 前端绝不「自算」：事件快照是唯一来源，逐字段原样搬运（不重算 url/contentId）', () => {
    // 后端若改了 url 形态（或将来带 base64/path），前端必须原样透传而不是拼一个自己的
    const odd: WireSnapshot = { ...PATH_DOCX, url: '/backend-chosen/url/9999' }
    const msgs = buildDrainedUserMessages('sess-1', [{ uuid: 'q1', content: 'x', userAttachments: [odd] }], new Set())
    expect(msgs[0].userAttachments?.[0].url).toBe('/backend-chosen/url/9999')
  })

  it('多附件按序搬运（顺序 = 后端列表顺序，前端不重排/不过滤）', () => {
    const second: WireSnapshot = { type: 'video', filename: 'clip.mp4', mediaType: 'video/mp4', contentId: '77', url: '/attachments/content/sess-1/77' }
    const msgs = buildDrainedUserMessages(
      'sess-1', [{ uuid: 'q1', content: 'x', userAttachments: [PATH_DOCX, second] }], new Set(),
    )
    expect(msgs[0].userAttachments?.map((a) => a.filename)).toEqual(['季度报表.docx', 'clip.mp4'])
  })

  it('无快照（纯文本 busy / 非 busy workload）⇒ userAttachments=null（不伪造空数组）', () => {
    const msgs = buildDrainedUserMessages('sess-1', [{ uuid: 'q1', content: 'x', userAttachments: null }], new Set())
    expect(msgs[0].userAttachments).toBeNull()
    const empty = buildDrainedUserMessages('sess-1', [{ uuid: 'q2', content: 'x', userAttachments: [] }], new Set())
    expect(empty[0].userAttachments).toBeNull()
  })

  it('uuid 已在 store ⇒ 跳过（防 drained 重发/与重拉重复）', () => {
    const msgs = buildDrainedUserMessages(
      'sess-1', [{ uuid: 'q1', content: 'x', userAttachments: [PATH_DOCX] }], new Set(['q1']),
    )
    expect(msgs).toEqual([])
  })

  it('气泡其余字段与旧内联实现逐字一致（id/userMessageId=uuid、role=user、防重语义不变）', () => {
    const msgs = buildDrainedUserMessages('sess-1', [{ uuid: 'q1', content: '看这个', userAttachments: null }], new Set())
    expect(msgs[0]).toMatchObject({
      id: 'q1', sessionId: 'sess-1', role: 'user', author: '你', content: '看这个',
      userMessageId: 'q1', isMeta: false, subtype: null, toolCalls: null, reasoning: null,
    })
  })

  it('无 uuid 的项仍生成气泡（兜底 id），不因缺快照而丢消息', () => {
    const msgs = buildDrainedUserMessages('sess-1', [{ content: 'x', userAttachments: null }], new Set())
    expect(msgs).toHaveLength(1)
    expect(msgs[0].id).toContain('queued-sess-1-')
  })
})

describe('parseDrainedQueueItems：原始 STOMP 载荷 → 归一（这一跳此前不可单测）', () => {
  it('⭐ 端到端：后端出站 JSON → live 气泡胶囊（path 附件 contentId 非空 + url 可点 = 与 F5 一致）', () => {
    // 后端 QueueEventPublisher.emitDrained 实际出站的形状（Jackson 序列化 UserAttachmentInfo record）
    const wirePayload = JSON.parse(JSON.stringify({
      type: 'queue.drained',
      sessionId: 'sess-1',
      drained: [{
        uuid: 'msg-queued-a1b2c3d4', content: '看这个报表', mode: 'prompt',
        streamTopic: '/topic/sessions/sess-1/stream',
        userAttachments: [{
          type: 'file', filename: '季度报表.docx',
          mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          contentId: '4711',
          url: '/attachments/content/sess-1/4711',
        }],
      }],
      commands: [],
    })) as { drained: Record<string, unknown>[] }

    const drained = parseDrainedQueueItems(wirePayload.drained)
    const msgs = buildDrainedUserMessages('sess-1', drained, new Set())

    expect(msgs[0].id).toBe('msg-queued-a1b2c3d4')
    expect(msgs[0].userAttachments?.[0].filename).toBe('季度报表.docx')
    expect(msgs[0].userAttachments?.[0].contentId, 'path 附件必须有 contentId').toBe('4711')
    expect(msgs[0].userAttachments?.[0].url, 'url 必须与 F5 读侧一致（可点）').toBe('/attachments/content/sess-1/4711')
  })

  it('缺键 / null / 非数组 ⇒ userAttachments=null（无快照，不伪造空数组）', () => {
    const [missing] = parseDrainedQueueItems([{ uuid: 'q1', content: 'x' }])
    expect(missing.userAttachments).toBeNull()
    const [explicitNull] = parseDrainedQueueItems([{ uuid: 'q1', content: 'x', userAttachments: null }])
    expect(explicitNull.userAttachments).toBeNull()
    const [notArray] = parseDrainedQueueItems([{ uuid: 'q1', content: 'x', userAttachments: 'oops' }])
    expect(notArray.userAttachments).toBeNull()
  })

  it('空数组 ⇒ null（后端「非空才出站」的反向兜底，气泡不画空行）', () => {
    const [it0] = parseDrainedQueueItems([{ uuid: 'q1', content: 'x', userAttachments: [] }])
    expect(it0.userAttachments).toBeNull()
  })

  it('uuid/content 归一与旧内联实现一致（null uuid → undefined；content 缺省 → 空串）', () => {
    const [a] = parseDrainedQueueItems([{ uuid: null, content: null }])
    expect(a.uuid).toBeUndefined()
    expect(a.content).toBe('')
  })

  it('快照字段容错：mediaType/contentId/url 缺失 ⇒ null（不是 "null" 字符串）', () => {
    const [it0] = parseDrainedQueueItems([{
      uuid: 'q1', content: 'x',
      userAttachments: [{ type: 'file', filename: 'a.docx' }],
    }])
    expect(it0.userAttachments?.[0]).toEqual({
      type: 'file', filename: 'a.docx', mediaType: null, contentId: null, url: null,
    })
  })
})
