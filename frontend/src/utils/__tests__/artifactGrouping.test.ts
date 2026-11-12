import { describe, expect, it } from 'vitest'
import type { SessionArtifactDto } from '@/api/artifacts'
import type { ChatMessageDto } from '@/api/types'
import {
  HISTORY_GROUP_KEY,
  buildTurnContext,
  dedupeByFile,
  groupArtifactsByTurn,
  mergeTurnContext,
} from '@/utils/artifactGrouping'

/**
 * 产物「按对话分组」纯函数单测。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：用户 2026-11-10 原话是「不然没法看是哪个对话产出的」。
 * 这条需求的正确性全落在三个判据上，任一条错了界面上都不明显：
 * <ol>
 *   <li><b>去重</b>：后端 V276~V278 每轮全量重录 ⇒ 同一文件跨轮多行。不去重则同批文件
 *       在几十个组里各出现一次（实测 28 个文件 → 889 行 / 108 轮）；</li>
 *   <li><b>归组</b>：归属键必须能对上「真人提问」，否则组头显示的是一串 hook 日志；</li>
 *   <li><b>兜底</b>：归属未知 / 孤儿键 / 系统注入轮次一律进「历史产物」，⛔ 不能猜。</li>
 * </ol>
 */

const A = (over: Partial<SessionArtifactDto>): SessionArtifactDto => ({
  id: over.id ?? 'art-1',
  kind: 'deliverable',
  title: over.title ?? 'f.md',
  localPath: over.localPath ?? 'C:/art/f.md',
  url: null,
  sizeBytes: 10,
  summary: null,
  sourceMessageId: over.sourceMessageId ?? null,
  origin: over.origin ?? 'deliverables',
  turnQuestion: over.turnQuestion ?? null,
  turnCreatedAt: over.turnCreatedAt ?? null,
  updatedAt: over.updatedAt ?? '2026-11-10T10:00:00+08:00',
  directory: false,
})

const M = (over: Partial<ChatMessageDto>): ChatMessageDto => ({
  id: over.id ?? 'msg-1',
  sessionId: 'sess-1',
  role: over.role ?? 'user',
  author: over.author ?? 'user',
  content: over.content ?? '帮我写个方案',
  reasoning: null,
  toolCalls: null,
  finishReason: null,
  inputTokens: null,
  outputTokens: null,
  reasoningDurationMs: null,
  time: over.time ?? '2026-11-10T09:00:00+08:00',
  createdAt: over.createdAt ?? '2026-11-10T09:00:00+08:00',
  toolCallId: null,
  assistantMessageId: null,
  subtype: over.subtype ?? null,
  isMeta: over.isMeta ?? false,
} as ChatMessageDto)

describe('dedupeByFile · 存量脏数据兜底', () => {
  it('同一 localPath 跨多轮多行 → 只留 updatedAt 最新的一条', () => {
    const rows = [
      A({ id: 'a1', localPath: 'C:/art/x.md', updatedAt: '2026-11-08T10:00:00+08:00' }),
      A({ id: 'a2', localPath: 'C:/art/x.md', updatedAt: '2026-11-10T10:00:00+08:00' }),
      A({ id: 'a3', localPath: 'C:/art/x.md', updatedAt: '2026-11-09T10:00:00+08:00' }),
    ]
    const out = dedupeByFile(rows)
    expect(out).toHaveLength(1)
    expect(out[0].id).toBe('a2')
  })

  it('无 localPath 的来源类条目按 title 去重', () => {
    const rows = [
      A({ id: 's1', kind: 'source', localPath: null, title: 'https://a.com', updatedAt: '2026-11-09T00:00:00+08:00' }),
      A({ id: 's2', kind: 'source', localPath: null, title: 'https://a.com', updatedAt: '2026-11-10T00:00:00+08:00' }),
    ]
    expect(dedupeByFile(rows).map((x) => x.id)).toEqual(['s2'])
  })

  it('不同文件全部保留，且结果按时间倒序', () => {
    const rows = [
      A({ id: 'a', localPath: 'C:/art/a.md', updatedAt: '2026-11-08T00:00:00+08:00' }),
      A({ id: 'b', localPath: 'C:/art/b.md', updatedAt: '2026-11-10T00:00:00+08:00' }),
    ]
    expect(dedupeByFile(rows).map((x) => x.id)).toEqual(['b', 'a'])
  })

  it('保留的是原对象引用（memo 组件靠引用相等跳过重渲染）', () => {
    const one = A({ id: 'a1' })
    expect(dedupeByFile([one])[0]).toBe(one)
  })
})

describe('buildTurnContext · 只收真人提问', () => {
  it('系统注入行（<system-reminder> / task-notification）不算提问', () => {
    const ctx = buildTurnContext([
      M({ id: 'm1', content: '真实提问' }),
      M({ id: 'm2', content: '<system-reminder>技能清单…</system-reminder>' }),
      M({ id: 'm3', content: '<task-notification>\n<task-id>x</task-id>' }),
    ])
    expect([...ctx.keys()]).toEqual(['m1'])
  })

  it('isMeta 行不算提问（author=attachment 的技能清单）', () => {
    const ctx = buildTurnContext([
      M({ id: 'm1', isMeta: true, author: 'attachment', content: '<system-reminder>a</system-reminder>' }),
    ])
    expect(ctx.size).toBe(0)
  })

  it('提问压成单行并截断到 60 字（与组头两行容量对齐）', () => {
    const long = 'a\n\nb   c'.concat('x'.repeat(300))
    const ctx = buildTurnContext([M({ id: 'm1', content: long })])
    const q = ctx.get('m1')!.question
    expect(q).not.toContain('\n')
    expect(q.startsWith('a b c')).toBe(true)
    expect(q.endsWith('…')).toBe(true)
    // ⭐ 必须钉住长度：原先只断言 endsWith('…')，把 160 改成 600 也是绿的。
    //    超长文本会撑高卡片头部（用户实测「一下子很多就会出渲染问题」）。
    //    60 字 + 1 个省略号 = 61。
    expect(q.length).toBe(61)
  })

  it('产物自带的长 turnQuestion 走同一套 60 字截断（两条通道不允许口径漂移）', () => {
    const longRow = A({
      sourceMessageId: 'm1',
      turnQuestion: 'y'.repeat(400),
    })
    const [group] = groupArtifactsByTurn([longRow], new Map())
    expect(group.question).not.toBeNull()
    expect(group.question!.length).toBe(61)
  })

  it('纯附件提问（正文为空）不进映射，避免组头空白', () => {
    expect(buildTurnContext([M({ id: 'm1', content: '   ' })]).size).toBe(0)
  })
})

describe('groupArtifactsByTurn · 归组与兜底', () => {
  it('按 sourceMessageId 归到对应提问，组头是该提问原文', () => {
    const ctx = buildTurnContext([M({ id: 'm1', content: '写个方案' })])
    const groups = groupArtifactsByTurn(
      [A({ id: 'a1', sourceMessageId: 'm1', localPath: 'C:/art/1.md' })],
      ctx,
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('m1')
    expect(groups[0].question).toBe('写个方案')
  })

  it('归属键为 null（历史行）→ 历史产物组', () => {
    const groups = groupArtifactsByTurn([A({ sourceMessageId: null })], new Map())
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
    expect(groups[0].question).toBeNull()
  })

  it('孤儿键（对不上任何消息）→ 历史产物组，⛔ 不猜归属', () => {
    const ctx = buildTurnContext([M({ id: 'm1', content: '真提问' })])
    const groups = groupArtifactsByTurn([A({ sourceMessageId: 'msg-queued-不存在' })], ctx)
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
  })

  it('归属对上是系统注入消息 → 也进历史产物组（组头不能是 hook 日志）', () => {
    const ctx = buildTurnContext([
      M({ id: 'm1', content: '真提问' }),
      M({ id: 'sys1', content: 'A session-scoped Stop hook is now active' }),
    ])
    const groups = groupArtifactsByTurn([A({ sourceMessageId: 'sys1' })], ctx)
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
  })

  it('组间按提问时间倒序，「历史产物」恒排最后', () => {
    const ctx = buildTurnContext([
      M({ id: 'old', content: '早的', createdAt: '2026-11-01T09:00:00+08:00' }),
      M({ id: 'new', content: '新的', createdAt: '2026-11-10T09:00:00+08:00' }),
    ])
    const groups = groupArtifactsByTurn(
      [
        A({ id: 'a1', sourceMessageId: null, localPath: 'C:/art/h.md' }),
        A({ id: 'a2', sourceMessageId: 'old', localPath: 'C:/art/o.md' }),
        A({ id: 'a3', sourceMessageId: 'new', localPath: 'C:/art/n.md' }),
      ],
      ctx,
    )
    expect(groups.map((g) => g.key)).toEqual(['new', 'old', HISTORY_GROUP_KEY])
  })
})

/**
 * 2026-11-12：产物自带 {@code turnQuestion / turnCreatedAt}（后端 join 带出）。
 *
 * <p><b>WHY</b>：窗口只保留最近 2 轮 ⇒ 更早轮次的产物此前全部落进「历史产物」。
 * 这些用例钉住「优先用产物自带字段、窗口映射只作回退」这条两级规则。
 */
describe('产物自带 turnQuestion · 窗口之外的老轮次也能成组', () => {
  it('窗口为空（只加载了最近 2 轮）→ 仍按产物自带字段成组', () => {
    const groups = groupArtifactsByTurn(
      [
        A({
          id: 'a1',
          sourceMessageId: 'm-early',
          localPath: 'C:/art/e.md',
          turnQuestion: '早期的提问',
          turnCreatedAt: '2026-11-01T08:00:00+08:00',
        }),
      ],
      new Map(),
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('m-early')
    expect(groups[0].question).toBe('早期的提问')
    expect(groups[0].createdAt).toBe('2026-11-01T08:00:00+08:00')
  })

  it('产物带的提问是 hook 日志 → 历史产物组（组头不能是 hook 日志）', () => {
    const groups = groupArtifactsByTurn(
      [
        A({
          sourceMessageId: 'm-hook',
          turnQuestion: 'A session-scoped Stop hook is now active',
          turnCreatedAt: '2026-11-01T08:00:00+08:00',
        }),
      ],
      new Map(),
    )
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
    expect(groups[0].question).toBeNull()
  })

  it('产物带的提问是成对 <system-reminder> 块 → 历史产物组', () => {
    const groups = groupArtifactsByTurn(
      [
        A({
          sourceMessageId: 'm-sys',
          turnQuestion: '<system-reminder>技能清单…</system-reminder>',
        }),
      ],
      new Map(),
    )
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
  })

  it('老后端（无 turnQuestion）+ 窗口有该消息 → 仍按窗口映射成组', () => {
    const ctx = buildTurnContext([M({ id: 'm1', content: '窗口里的提问' })])
    const groups = groupArtifactsByTurn([A({ sourceMessageId: 'm1' })], ctx)
    expect(groups[0].key).toBe('m1')
    expect(groups[0].question).toBe('窗口里的提问')
  })

  it('老后端（无 turnQuestion）+ 窗口没有该消息 → 历史产物组（现状不变）', () => {
    const groups = groupArtifactsByTurn([A({ sourceMessageId: 'm-old' })], new Map())
    expect(groups[0].key).toBe(HISTORY_GROUP_KEY)
  })

  it('同一轮多产物只有一个带 turnQuestion → 仍合成 1 组', () => {
    const groups = groupArtifactsByTurn(
      [
        A({ id: 'a1', sourceMessageId: 'm1', localPath: 'C:/art/1.md', turnQuestion: '同一轮的提问' }),
        A({ id: 'a2', sourceMessageId: 'm1', localPath: 'C:/art/2.md' }),
      ],
      new Map(),
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('m1')
    expect(groups[0].items.map((x) => x.id).sort()).toEqual(['a1', 'a2'])
  })

  it('产物字段覆盖同键窗口值（后端全量真源 > 窗口局部视图），且入参 Map 不被修改', () => {
    const ctx = buildTurnContext([M({ id: 'm1', content: '窗口提问' })])
    const before = [...ctx.entries()]
    const merged = mergeTurnContext(
      [A({ sourceMessageId: 'm1', turnQuestion: '产物里的提问', turnCreatedAt: '2026-11-01T08:00:00+08:00' })],
      ctx,
    )
    expect(
      merged.get('m1')!.question,
      '⛔ 反了 ⇒ 默认用的是当前窗口（最近 2 轮）而不是全量真源',
    ).toBe('产物里的提问')
    expect(merged.get('m1')!.createdAt).toBe('2026-11-01T08:00:00+08:00')
    expect(ctx.size).toBe(before.length)
    expect([...ctx.entries()]).toEqual(before)
    expect(merged).not.toBe(ctx)
  })

  it('产物带提问但没带时间 → 保留窗口已有的时间（⛔ 不能用 null 冲掉）', () => {
    const ctx = buildTurnContext([
      M({ id: 'm1', content: '窗口提问', createdAt: '2026-11-02T09:00:00+08:00' }),
    ])
    const merged = mergeTurnContext(
      [A({ sourceMessageId: 'm1', turnQuestion: '产物里的提问', turnCreatedAt: null })],
      ctx,
    )
    expect(merged.get('m1')!.question).toBe('产物里的提问')
    expect(merged.get('m1')!.createdAt).toBe('2026-11-02T09:00:00+08:00')
  })

  it('产物没有合法提问（hook 日志）→ 不动窗口值（回退而不是清空）', () => {
    const ctx = buildTurnContext([M({ id: 'm1', content: '窗口提问' })])
    const merged = mergeTurnContext(
      [A({ sourceMessageId: 'm1', turnQuestion: 'A session-scoped Stop hook is now active' })],
      ctx,
    )
    expect(merged.get('m1')!.question).toBe('窗口提问')
  })
})
