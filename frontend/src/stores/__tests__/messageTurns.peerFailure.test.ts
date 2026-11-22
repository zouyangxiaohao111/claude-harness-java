import { describe, expect, it } from 'vitest'
import {
  normalizePeerFailureReason,
  peerFailureReceiverTitle,
  peerFailureRowFromEvent,
  peerFailureSentence,
  readPeerFailure,
} from '@/stores/messageTurns'
import type { ChatMessageDto } from '@/api/types'

/**
 * [peer-failure] 纯函数层：原因归一 + 文案 + 实时事件 → 合成行。
 *
 * <h2>WHY</h2>
 * <p>后端线上原因串用连字符（user-declined / receiver-closed / queue-full / rate-limited /
 * relay-budget），需求文档写下划线写法 ⇒ 两种拼写必须归到同一档，否则真实事件全部落到「未知原因」。
 * 合成行 id 必须 = 后端落库 id（peer-failure-&lt;messageId&gt;），否则实时行与刷新行并存两行。
 */

describe('normalizePeerFailureReason · 线上拼写与需求拼写同档', () => {
  it.each([
    ['expired', 'expired'],
    ['user-declined', 'rejected'],
    ['rejected', 'rejected'],
    ['refused', 'refused'],
    ['receiver-closed', 'no_live_session'],
    ['no_live_session', 'no_live_session'],
    ['rate-limited', 'rate_limited'],
    ['rate_limited', 'rate_limited'],
    ['duplicate', 'duplicate'],
    ['queue-full', 'queue_full'],
    ['queue_full', 'queue_full'],
    ['relay-budget', 'relay_budget'],
    ['relay_budget', 'relay_budget'],
    ['hop-loop', 'hop_loop'],
    ['hop_loop', 'hop_loop'],
    ['HOP-LOOP', 'hop_loop'],
    ['hop-runaway', 'hop_runaway'],
    ['hop_runaway', 'hop_runaway'],
    ['HOP-RUNAWAY', 'hop_runaway'],
    ['whatever', 'unknown'],
    ['', 'unknown'],
    [null, 'unknown'],
  ])('%s → %s', (raw, expected) => {
    expect(normalizePeerFailureReason(raw as string | null)).toBe(expected)
  })
})

describe('peerFailureSentence · 未知原因回落', () => {
  it('未知原因 → 投递失败（未知原因），仍带标题与 id', () => {
    expect(peerFailureSentence({
      messageId: 'm1', reason: 'whatever', receiverSessionId: 'sess-x', receiverTitle: '标题',
    })).toBe('投递到《标题》（sess-x）失败（未知原因）。')
  })

  it('标题缺失 → 未命名会话；receiverSessionId 缺失 → 不画空括号', () => {
    const info = { messageId: 'm1', reason: 'expired', receiverSessionId: null, receiverTitle: null }
    expect(peerFailureReceiverTitle(info)).toBe('未命名会话')
    expect(peerFailureSentence(info)).toBe('你发给《未命名会话》的消息已过期，未获批准，未投递给对方。')
  })
})

describe('peerFailureSentence · hop-loop / hop-runaway 专属文案', () => {
  it.each([
    ['hop-loop', '你发给《标题》（sess-x）的消息在会话之间反复往返，已被判定为循环并拦截。'],
    ['hop_loop', '你发给《标题》（sess-x）的消息在会话之间反复往返，已被判定为循环并拦截。'],
    ['hop-runaway', '你发给《标题》（sess-x）的消息转发链过长，已被判定为失控转发并拦截。'],
    ['hop_runaway', '你发给《标题》（sess-x）的消息转发链过长，已被判定为失控转发并拦截。'],
  ])('%s → 专属中文文案（不落未知原因）', (reason, expected) => {
    const text = peerFailureSentence({
      messageId: 'm1', reason, receiverSessionId: 'sess-x', receiverTitle: '标题',
    })
    expect(text).toBe(expected)
    expect(text).not.toContain('未知原因')
    // ⛔ 不得与既有档位串文案（relay_budget 是最接近的「转发」档）
    expect(text).not.toContain('转发预算耗尽')
  })

  it('hop-loop ≠ hop-runaway（两条不许合并成同一档）', () => {
    const base = { messageId: 'm1', receiverSessionId: 'sess-x', receiverTitle: '标题' }
    expect(peerFailureSentence({ ...base, reason: 'hop-loop' }))
      .not.toBe(peerFailureSentence({ ...base, reason: 'hop-runaway' }))
    expect(normalizePeerFailureReason('hop-loop')).not.toBe(normalizePeerFailureReason('hop-runaway'))
  })

  it('标题缺失 → 未命名会话；id 仍带出', () => {
    expect(peerFailureSentence({
      messageId: 'm1', reason: 'hop-runaway', receiverSessionId: 'sess-x', receiverTitle: null,
    })).toBe('你发给《未命名会话》（sess-x）的消息转发链过长，已被判定为失控转发并拦截。')
    expect(peerFailureSentence({
      messageId: 'm1', reason: 'hop-loop', receiverSessionId: 'sess-x', receiverTitle: '   ',
    })).toBe('你发给《未命名会话》（sess-x）的消息在会话之间反复往返，已被判定为循环并拦截。')
  })
})

describe('peerFailureRowFromEvent · 合成行与落库行同形', () => {
  it('id/role/subtype/isMeta/structured_output 与后端落库合同一致', () => {
    const row = peerFailureRowFromEvent({
      type: 'peer.message.failed',
      messageId: 'm-xyz',
      targetSessionId: 'sess-t',
      targetTitle: '审计',
      reason: 'expired',
      text: 'english text',
      occurredAt: '2026-11-20T10:00:00Z',
    }, 'sess-src')
    expect(row).not.toBeNull()
    const r = row as ChatMessageDto
    expect(r.id).toBe('peer-failure-m-xyz')      // ✅ 同 id ⇒ 刷新后不产生第二行
    expect(r.sessionId).toBe('sess-src')
    expect(r.role).toBe('user')
    expect(r.subtype).toBe('agent_message')
    expect(r.isMeta).toBe(true)
    expect(r.userMessageId).toBeNull()
    expect(r.author).toBe('sess-t')
    expect(r.time).toBe('2026-11-20T10:00:00Z')
    expect(readPeerFailure(r)).toEqual({
      messageId: 'm-xyz', reason: 'expired',
      receiverSessionId: 'sess-t', receiverTitle: '审计',
    })
  })

  it('缺 messageId / 缺 sessionId → null（无法去重与归属，不插行）', () => {
    expect(peerFailureRowFromEvent({ type: 'peer.message.failed' }, 'sess-src')).toBeNull()
    expect(peerFailureRowFromEvent({ messageId: 'm1' }, '')).toBeNull()
  })
})
