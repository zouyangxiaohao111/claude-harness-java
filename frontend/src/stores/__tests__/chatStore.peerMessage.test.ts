import { describe, expect, it } from 'vitest'
import { createChatStore } from '../chatStore'
import { readPeerNoticeSummary, readPeerSource } from '../messageTurns'
import type { ChatMessageDto } from '../../api/types'

/**
 * 跨会话通知行的 **store 收敛**（方案 §3.6 / §5 D 行 / P-F01）。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>跨会话 SendMessage 在目标会话落库一条 `role=user, isMeta, subtype=agent_message` 的整行消息，
 * 经 STOMP `message.insert` 到达前端。用户诉求是「看到是谁（哪个会话）发来的通知，点开能看原文」，
 * 且：<b>实时推送与 F5 重拉必须一致、切会话不能串台、同一条消息不能重复出现</b>。
 *
 * <h2>本文件钉住的四条不变量</h2>
 * <ol>
 *   <li>重复推送幂等（同 id 只一行）；</li>
 *   <li>同 id **占位行**（排队投影/乐观行）能被完整行补全元数据 —— 旧「直接跳过」会让通知
 *       永远是用户气泡/主代理通知；</li>
 *   <li>完整行绝不被随后到达的空/旧行降级；</li>
 *   <li>实时 → GET 重拉（setMessages 整表替换）→ 再推送，三种顺序都收敛到同一行，且只影响本会话桶。</li>
 * </ol>
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>把 `insertServerMessages` 的同 id 分支改回「直接跳过」⇒ 占位补全用例红；</li>
 *   <li>去掉 `mergeServerRowMetadata` 的 structuredOutput 合并 ⇒ 补全后 readPeerSource 为空 ⇒ 红；</li>
 *   <li>把合并规则改成用 incoming 覆盖非空旧值 ⇒ 完整行被空行降级用例红。</li>
 * </ul>
 */

const SID = 'sess-c7767a8d'
const OTHER = 'sess-ce9234ed'

/** 后端出站 ChatMessageDto 的真实形态（字段与 GET /messages 同源；测试只构造内存对象）。 */
function baseMsg(id: string, sessionId: string, patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id,
    sessionId,
    role: 'user',
    author: null,
    content: null,
    reasoning: null,
    toolCalls: null,
    finishReason: null,
    inputTokens: null,
    outputTokens: null,
    reasoningDurationMs: null,
    time: null,
    toolCallId: null,
    assistantMessageId: null,
    subtype: null,
    isMeta: false,
    isApiErrorMessage: false,
    apiError: null,
    error: null,
    errorDetails: null,
    matchedRule: null,
    ...patch,
  }
}

/** 跨会话通知完整行（数据合同：author=来源 session id、queuedOrigin=peer、peer_source + agent_summary）。 */
function peerNotice(id = 'peer-1', patch: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return baseMsg(id, SID, {
    role: 'user',
    author: OTHER,
    content: '这里是原始正文：请复核 §3.6 的前端呈现',
    summary: null,
    subtype: 'agent_message',
    isMeta: true,
    queuedOrigin: 'peer',
    structuredOutput: {
      peer_source: {
        sourceSessionId: OTHER,
        sourceTitle: '审计报告',
        sourceKind: 'session',
        messageId: 'src-msg-9',
        endpointGeneration: 3,
        fromMode: 'default',
        from: 'sess-ce9234ed',
        hopChain: ['sess-ce9234ed'],
      },
      agent_summary: '请复核前端呈现方案',
    },
    userMessageId: null,
    ...patch,
  })
}

describe('chatStore · 跨会话通知行（message.insert 整行事件收敛）', () => {
  it('重复推送同一条 → 只保留一行（WHY：STOMP 重放/双通道不能把通知显示两遍）', () => {
    const s = createChatStore()
    const row = peerNotice()
    s.getState().insertServerMessages(SID, [row])
    s.getState().insertServerMessages(SID, [row])
    const msgs = s.getState().messages[SID] ?? []
    expect(msgs).toHaveLength(1)
    expect(msgs[0]!.id).toBe('peer-1')
    expect(msgs[0]!.subtype).toBe('agent_message')
    expect(readPeerSource(msgs[0]!)?.sourceTitle).toBe('审计报告')
  })

  it('⭐ 同 id 占位行 → 完整行到达时补全元数据（不重复插、不换位置、不丢正文）', () => {
    const s = createChatStore()
    // GET 重拉/排队投影先给了一份占位行：author 写死 'user'、没有 subtype/来源/摘要
    const placeholder = baseMsg('peer-1', SID, {
      author: 'user',
      content: '',
      subtype: null,
      isMeta: false,
      userMessageId: null,
    })
    const before = baseMsg('a1', SID, { role: 'assistant', content: '上一轮回答', userMessageId: 'u1' })
    s.getState().setMessages(SID, [before, placeholder])

    // 完整行随后经 message.insert 到达
    s.getState().insertServerMessages(SID, [peerNotice()])

    const msgs = s.getState().messages[SID] ?? []
    expect(msgs).toHaveLength(2)
    // 位置不变（仍是列表尾的第 2 行），不是被追加成第 3 行
    expect(msgs.map((m) => m.id)).toEqual(['a1', 'peer-1'])
    const filled = msgs[1]!
    expect(filled.author).toBe(OTHER)
    expect(filled.subtype).toBe('agent_message')
    expect(filled.isMeta).toBe(true)
    expect(filled.queuedOrigin).toBe('peer')
    expect(filled.content).toBe('这里是原始正文：请复核 §3.6 的前端呈现')
    // 来源 + 真实摘要都可读（UI 依赖这两个字段，缺一个就会退回错误归属）
    expect(readPeerSource(filled)?.sourceSessionId).toBe(OTHER)
    expect(readPeerSource(filled)?.sourceKind).toBe('session')
    expect(readPeerNoticeSummary(filled)).toBe('请复核前端呈现方案')
  })

  it('完整行不会被随后到达的空/旧行降级（WHY：迟到重放不得把来源和正文冲掉）', () => {
    const s = createChatStore()
    s.getState().setMessages(SID, [peerNotice()])
    const stale = baseMsg('peer-1', SID, {
      author: 'user',
      content: '',
      subtype: null,
      isMeta: false,
      queuedOrigin: null,
      structuredOutput: null,
    })
    s.getState().insertServerMessages(SID, [stale])

    const msgs = s.getState().messages[SID] ?? []
    expect(msgs).toHaveLength(1)
    expect(msgs[0]!.author).toBe(OTHER)
    expect(msgs[0]!.subtype).toBe('agent_message')
    expect(msgs[0]!.isMeta).toBe(true)
    expect(msgs[0]!.content).toBe('这里是原始正文：请复核 §3.6 的前端呈现')
    expect(readPeerNoticeSummary(msgs[0]!)).toBe('请复核前端呈现方案')
  })

  it('实时推送 → F5 重拉（setMessages 整表替换）→ 再推送：三种顺序都收敛为一行、同一来源', () => {
    const s = createChatStore()
    const row = peerNotice()
    s.getState().insertServerMessages(SID, [row])          // 实时
    s.getState().setMessages(SID, [row])                   // F5 重拉（后端同源行）
    s.getState().insertServerMessages(SID, [row])          // 重拉后的迟到重放

    const msgs = s.getState().messages[SID] ?? []
    expect(msgs).toHaveLength(1)
    expect(msgs[0]!.id).toBe(row.id)
    expect(msgs[0]!.author).toBe(OTHER)
    expect(readPeerSource(msgs[0]!)?.messageId).toBe('src-msg-9')
  })

  it('切会话隔离：A 会话的通知不会出现在 B 的桶里，补全也不跨桶', () => {
    const s = createChatStore()
    const aPlaceholder = baseMsg('peer-1', SID, { author: 'user', content: '' })
    s.getState().setMessages(SID, [aPlaceholder])
    s.getState().setMessages(OTHER, [baseMsg('b1', OTHER, { userMessageId: 'b1', content: 'B 的提问' })])

    s.getState().insertServerMessages(SID, [peerNotice()])

    expect((s.getState().messages[SID] ?? []).map((m) => m.id)).toEqual(['peer-1'])
    expect((s.getState().messages[SID] ?? [])[0]!.subtype).toBe('agent_message')
    // B 会话原样（没有 peer 行、也没有被误补全）
    const b = s.getState().messages[OTHER] ?? []
    expect(b.map((m) => m.id)).toEqual(['b1'])
    expect(b[0]!.subtype).toBeNull()
  })

  it('插入顺序保留：新 peer 行按到达序插尾，既有行不被重排', () => {
    const s = createChatStore()
    s.getState().setMessages(SID, [
      baseMsg('u1', SID, { content: '提问', userMessageId: 'u1' }),
      baseMsg('a1', SID, { role: 'assistant', content: '回答', userMessageId: 'u1' }),
    ])
    s.getState().insertServerMessages(SID, [peerNotice('peer-2')])
    expect((s.getState().messages[SID] ?? []).map((m) => m.id)).toEqual(['u1', 'a1', 'peer-2'])
  })

  // ── 同批 rows 内重复 id（旧行为「先到先得」⇒ 先到的空占位会把后到的完整行丢掉） ──

  it('⭐ 同批 [占位, 完整]：合并为一行，完整行不得被先到的空占位丢弃', () => {
    const s = createChatStore()
    s.getState().setMessages(SID, [baseMsg('u1', SID, { content: '提问', userMessageId: 'u1' })])
    const placeholder = baseMsg('peer-1', SID, {
      author: 'user', content: '', subtype: null, isMeta: false,
    })
    s.getState().insertServerMessages(SID, [placeholder, peerNotice()])

    const msgs = s.getState().messages[SID] ?? []
    expect(msgs.map((m) => m.id)).toEqual(['u1', 'peer-1'])
    const filled = msgs[1]!
    expect(filled.author).toBe(OTHER)
    expect(filled.subtype).toBe('agent_message')
    expect(filled.isMeta).toBe(true)
    expect(filled.content).toBe('这里是原始正文：请复核 §3.6 的前端呈现')
    expect(readPeerSource(filled)?.sourceTitle).toBe('审计报告')
    expect(readPeerNoticeSummary(filled)).toBe('请复核前端呈现方案')
  })

  it('⭐ 同批 [完整, 占位]：完整行保持，不被后到的空占位降级', () => {
    const s = createChatStore()
    const placeholder = baseMsg('peer-1', SID, {
      author: 'user', content: '', subtype: null, isMeta: false,
    })
    s.getState().insertServerMessages(SID, [peerNotice(), placeholder])

    const msgs = s.getState().messages[SID] ?? []
    expect(msgs).toHaveLength(1)
    expect(msgs[0]!.author).toBe(OTHER)
    expect(msgs[0]!.subtype).toBe('agent_message')
    expect(msgs[0]!.content).toBe('这里是原始正文：请复核 §3.6 的前端呈现')
    expect(readPeerSource(msgs[0]!)?.sourceTitle).toBe('审计报告')
  })

  // ── structuredOutput 的「非空优先」补齐（仅 peer_source / agent_summary） ──

  it('嵌套缺字段补齐：旧 peer_source 缺 sourceTitle/from/hopChain → 一层合并补上，旧非空值保留', () => {
    const s = createChatStore()
    const partial = peerNotice('peer-1')
    partial.structuredOutput = {
      peer_source: { sourceSessionId: OTHER, sourceKind: 'session', messageId: 'src-msg-9' },
      agent_summary: '旧摘要',
    }
    s.getState().setMessages(SID, [partial])
    s.getState().insertServerMessages(SID, [peerNotice()])

    const got = (s.getState().messages[SID] ?? [])[0]!
    const src = readPeerSource(got)
    expect(src?.sourceSessionId).toBe(OTHER)   // 旧非空值保留
    expect(src?.sourceKind).toBe('session')
    expect(src?.sourceTitle).toBe('审计报告')  // 缺失键补齐
    expect(src?.from).toBe('sess-ce9234ed')
    expect(src?.hopChain).toEqual(['sess-ce9234ed'])
    expect(readPeerNoticeSummary(got)).toBe('旧摘要') // 已有非空摘要不被覆盖
  })

  it('peer_source=null 后可被完整行补上（旧顶层浅合并会永久压住真实来源）', () => {
    const s = createChatStore()
    const nulled = peerNotice('peer-1')
    nulled.structuredOutput = { peer_source: null, agent_summary: '旧摘要' }
    s.getState().setMessages(SID, [nulled])
    s.getState().insertServerMessages(SID, [peerNotice()])

    const got = (s.getState().messages[SID] ?? [])[0]!
    expect(readPeerSource(got)?.sourceTitle).toBe('审计报告')
    expect(readPeerSource(got)?.sourceSessionId).toBe(OTHER)
  })

  it("agent_summary='' 后可被完整行补上（空串不等于已配置）", () => {
    const s = createChatStore()
    const blank = peerNotice('peer-1')
    blank.structuredOutput = {
      peer_source: {
        sourceSessionId: OTHER, sourceTitle: '审计报告', sourceKind: 'session',
        messageId: 'src-msg-9', from: 'sess-ce9234ed', hopChain: ['sess-ce9234ed'],
      },
      agent_summary: '',
    }
    s.getState().setMessages(SID, [blank])
    s.getState().insertServerMessages(SID, [peerNotice()])

    const got = (s.getState().messages[SID] ?? [])[0]!
    expect(readPeerNoticeSummary(got)).toBe('请复核前端呈现方案')
  })

  it('已有非空来源/摘要不被篡改（反向降级永不发生）', () => {
    const s = createChatStore()
    s.getState().setMessages(SID, [peerNotice('peer-1')])
    const incoming = peerNotice('peer-1')
    incoming.structuredOutput = {
      peer_source: {
        sourceSessionId: 'other-src', sourceTitle: '别的标题', sourceKind: 'agent',
        messageId: 'm2', from: 'x', hopChain: ['x'],
      },
      agent_summary: '别的摘要',
    }
    s.getState().insertServerMessages(SID, [incoming])

    const got = (s.getState().messages[SID] ?? [])[0]!
    expect(got.author).toBe(OTHER)
    expect(readPeerSource(got)?.sourceSessionId).toBe(OTHER)
    expect(readPeerSource(got)?.sourceTitle).toBe('审计报告')
    expect(readPeerNoticeSummary(got)).toBe('请复核前端呈现方案')
  })
})
