import { describe, expect, it } from 'vitest'
import type { ChatMessageDto } from '../../api/types'
import { pairCompactSummaries } from '../compactPairing'

/** 测试用最小 ChatMessageDto（沿用 chatStore.test.ts 的 baseMsg 惯例，避免每个用例造全字段）。 */
function baseMsg(id: string, content: string = `内容-${id}`): ChatMessageDto {
  return {
    id,
    sessionId: 'sess-1',
    role: 'user',
    author: '你',
    content,
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
  }
}

/** compact_boundary 消息（role=system + subtype=compact_boundary，后端 CompactBoundaryMessage 产出） */
function boundary(id: string): ChatMessageDto {
  return { ...baseMsg(id, ''), role: 'system', subtype: 'compact_boundary' }
}

/** compact 摘要消息（role=user + isCompactSummary=true，后端 buildCompactSummaryMessage 产出） */
function summary(id: string, text: string): ChatMessageDto {
  return { ...baseMsg(id, text), isCompactSummary: true }
}

/** microcompact_boundary 消息（无摘要正文，仅作标记行） */
function microBoundary(id: string): ChatMessageDto {
  return { ...baseMsg(id, ''), role: 'system', subtype: 'microcompact_boundary' }
}

describe('pairCompactSummaries', () => {
  it('全量压缩 [B, S, keep…] → 摘要 S 配给 B（WHY：boundary 自身不含摘要，标记行详情要靠这张表取到真实摘要正文）', () => {
    const pairs = pairCompactSummaries([
      boundary('B'),
      summary('S', '全量摘要正文'),
      baseMsg('m1', '保留的后续消息'),
    ])
    expect(pairs.get('B')).toBe('全量摘要正文')
    expect(pairs.size).toBe(1)
  })

  it('from 方向 [B2, m0, B1, S1, …, S2] → S1 配 B1、S2 配 B2（WHY：from 的 keep 段刻意保留旧的 boundary_A/summary_A，若「遇 boundary 就停」则 B2 只能看到 B1，摘要丢失、标记行回落英文常量）', () => {
    const pairs = pairCompactSummaries([
      boundary('B2'),
      baseMsg('m0', 'from 保留的头段（含旧的压缩产物）'),
      boundary('B1'),
      summary('S1', '旧摘要'),
      summary('S2', '新摘要'),
    ])
    expect(pairs.get('B1')).toBe('旧摘要')
    // RED 条件：改回「从 boundary 往后扫、遇 boundary 就 break」的实现 → B2 在此拿到 undefined → 本断言失败
    expect(pairs.get('B2')).toBe('新摘要')
  })

  it('嵌套压缩（后一次 keep 段含前一次的 boundary/摘要）→ 各自配对不串位（WHY：连续压缩是常态，摘要绝不能配给错误的边界）', () => {
    const pairs = pairCompactSummaries([
      boundary('B2'),
      summary('S2', '第二次摘要'),
      boundary('B1'),
      summary('S1', '第一次摘要'),
    ])
    expect(pairs.get('B2')).toBe('第二次摘要')
    expect(pairs.get('B1')).toBe('第一次摘要')
  })

  it('无摘要 → 返回空表（WHY：调用方据此回落 boundary 自身 content，不误配）', () => {
    expect(pairCompactSummaries([boundary('B'), baseMsg('m1')]).size).toBe(0)
    expect(pairCompactSummaries([]).size).toBe(0)
  })

  it('microcompact_boundary 不参与配对、也不中断扫描（WHY：它没有摘要正文，若让它中断扫描会挡住它之后的 compact 配对）', () => {
    const pairs = pairCompactSummaries([
      boundary('B'),
      microBoundary('MB'), // 夹在 boundary 与其摘要之间 → 不得阻断配对
      summary('S', '摘要正文本'),
      microBoundary('MB2'),
      summary('S2', '无主摘要'),
    ])
    expect(pairs.get('B')).toBe('摘要正文本')
    // MB2 不是 compact_boundary → 压栈对象仍是 B（已弹）→ S2 无人认领，直接丢弃
    expect(pairs.has('MB2')).toBe(false)
    expect(pairs.size).toBe(1)
  })

  it('边界情形：boundary 的摘要因窗口头部截断而不在列表里 → 该 boundary 不入表（WHY：宁可不显示摘要回落常量，也不把别人的摘要错配给它）', () => {
    const pairs = pairCompactSummaries([boundary('B2'), baseMsg('m0'), boundary('B1')])
    expect(pairs.size).toBe(0)
  })

  it('边界情形：摘要先于其 boundary 出现（boundary 被截断）→ 该摘要被丢弃，不配给后来的边界（WHY：错配摘要比不显示摘要更糟）', () => {
    const pairs = pairCompactSummaries([
      summary('S1', '旧摘要（其 boundary 已被截断）'),
      boundary('B2'),
      summary('S2', '新摘要'),
    ])
    expect(pairs.get('B2')).toBe('新摘要')
    expect(pairs.size).toBe(1)
  })

  it('摘要 content 为 null → 配成空串而非丢弃（WHY：调用方用 `|| 常量` 回落，空串与「配不到」在展示上等价，但语义上确实是这一对的摘要）', () => {
    const pairs = pairCompactSummaries([boundary('B'), { ...baseMsg('S'), content: null, isCompactSummary: true }])
    expect(pairs.get('B')).toBe('')
  })
})
