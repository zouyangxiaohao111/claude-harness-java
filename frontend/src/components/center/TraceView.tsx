import { useMemo, useState } from 'react'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'
import { compactNumber } from '@/utils/format'
import { pairCompactSummaries } from '@/utils/compactPairing'
import { PREVIEW_SOURCE_CHARACTERS, projectPreview } from '@/markdown/plainPreview'

/** 轨迹视图 · dsh 式记录列表：从会话消息历史派生 user/assistant/tool 记录（按 turn 分组）。
 *  数据源：chatStore.messages[activeSessionId]（真实后端消息历史，含 toolCalls）。 */

/** 工具类型 → 色条 class（对齐设计稿 v7：bash/edit/read/write/glob/task） */
function toolKind(name: string): string {
  const n = name.toLowerCase()
  if (n.includes('bash') || n.includes('shell') || n.includes('exec')) return 'tool-bash'
  if (n.includes('edit') || n.includes('write')) return 'tool-edit'
  if (n.includes('glob') || n.includes('grep') || n.includes('search')) return 'tool-glob'
  if (n.startsWith('read') || n.includes('read_file')) return 'tool-read'
  if (n.includes('task') || n.includes('agent') || n.includes('subagent')) return 'tool-task'
  return 'tool-other'
}

/** 工具名清理（复用 MessageList 的 FM-14 规则：剥 mcp__server__ 前缀等） */
function cleanToolName(name: string | null): string {
  let n = (name ?? 'tool').trim()
  n = n.replace(/^\s*-\s*/, '').trim()
  if (n.startsWith('mcp__')) {
    const rest = n.slice('mcp__'.length)
    const sep = rest.indexOf('__')
    n = (sep === -1 ? rest : rest.slice(sep + 2)).trim()
  }
  if (n.endsWith('(MCP)')) n = n.slice(0, -'(MCP)'.length).trim()
  return n
}

interface TraceRecord {
  kind: 'user' | 'assistant' | 'tool'
  toolClass: string
  toolName: string
  txt: string
  time: string
  /** 完整原文（列表显示截断 · 点击行看全文用） */
  full?: string
  /** [snip-persist] 该消息已被 Snip 裁剪（id ∈ snippedIds）→ 行加 .snipped + 「已裁剪」标记 */
  snipped?: boolean
  /** [compact 边界] subtype=compact_boundary → 渲染为居中「已压缩」标记行（非普通记录行） */
  compact?: boolean
  /** [边界标记] 详情浮层标题（缺省按 kind 回落；snip 标记用「裁剪摘要」区分「压缩摘要」） */
  detailTitle?: string
}

/** [compact 边界] compact 替换点标记文案前缀（轨迹视图专属；聊天区 MessageList 另有一套文案） */
const COMPACT_MARKER_TXT = '已压缩 · 历史上下文已由 compact 摘要替换'
/** [compact 边界] microcompact 标记文案（与聊天区 MessageList boundaryLabel 同文案） */
const MICROCOMPACT_MARKER_TXT = '已清理 · 旧工具输出已清除'

/** [compact 边界] compact 标记文案：boundary 的 compactMetadata 带 messagesSummarized/preTokens 时追加
 *  「（压缩 N 条 / ~M tokens）」（对齐聊天区 MessageList boundaryLabel 的 token 后缀做法）。 */
function compactMarkerTxt(msg: ChatMessageDto): string {
  const meta = msg.compactMetadata
  const n = typeof meta?.messagesSummarized === 'number' ? meta.messagesSummarized : null
  const pre = meta?.preTokens != null && meta.preTokens > 0 ? compactNumber(meta.preTokens) : null
  const bits: string[] = []
  if (n != null) bits.push(`压缩 ${n} 条`)
  if (pre != null) bits.push(`~${pre} tokens`)
  return bits.length > 0 ? `${COMPACT_MARKER_TXT}（${bits.join(' / ')}）` : COMPACT_MARKER_TXT
}

/** [compact 边界] subtype=compact_boundary → 「已压缩」标记记录（摘要正文可点开读全文）。
 *
 *  <p>摘要正文来源 = 与之配对的 compact 摘要 user 消息（{@code isCompactSummary === true}，
 *  后端 CompactConversation.buildCompactSummaryMessage 产出）：boundary 自身<b>不含</b>摘要
 *  （compactMetadata 无 summary 字段 —— 旧实现读的是幽灵字段 → 详情永远只有 boundary 常量文本）。
 *  配对由 {@link pairCompactSummaries} 栈式完成（不能「遇 boundary 就停」，见该函数注释）。
 *  配不到摘要（或摘要为空）→ 回退 boundary 自身 content。
 */
function compactMarker(msg: ChatMessageDto, summaryDetail: string): TraceRecord {
  const detail = summaryDetail || (msg.content ?? '')
  return {
    kind: 'assistant',
    toolClass: '',
    toolName: '',
    txt: compactMarkerTxt(msg),
    time: msg.time ?? msg.createdAt ?? '',
    full: detail || undefined,
    compact: true,
  }
}

/** [compact 边界] subtype=microcompact_boundary → 「已清理」居中标记条（无摘要正文，不可点开）。
 *  WHY：旧实现落到 role=system 的普通记录行，直接显示后端英文常量 `Context microcompacted`。 */
function microcompactMarker(msg: ChatMessageDto): TraceRecord {
  return {
    kind: 'assistant',
    toolClass: '',
    toolName: '',
    txt: MICROCOMPACT_MARKER_TXT,
    time: msg.time ?? msg.createdAt ?? '',
    compact: true,
  }
}

/** [snip 边界] subtype=snip_boundary → 「已裁剪」居中标记条（与聊天区 MessageList boundaryLabel 同文案）。
 *
 *  <p><b>WHY 必须有本分支</b>：缺少它时 snip_boundary 行落入 {@link toRecords} 的 role=system 兜底 →
 *  被当成「普通 assistant 记录」渲染，直接把 boundary.content（模型写的裁剪摘要）显示成一条助手回复
 *  → <b>轨迹里看不出发生过 snip</b>（P2-19 取证）。
 *
 *  <p>摘要正文（boundary.content = SnipTool 传入的 reason）<b>可点开</b>：它是「这次裁剪做了什么」的说明，
 *  保留可读性（对齐 CC SnipBoundaryMessage.tsx:12-22 把 content 渲染进分隔条本身）。
 *  注：聊天区的居中条只显示条数（不展开摘要），此处以详情浮层承载，两边文案保持同源。 */
function snipBoundaryMarker(msg: ChatMessageDto): TraceRecord {
  const n = msg.snipMetadata?.removedUuids?.length ?? 0
  const detail = msg.content ?? ''
  return {
    kind: 'assistant',
    toolClass: '',
    toolName: '',
    txt: `已裁剪 · 历史消息已由 Snip 移除${n > 0 ? `（${n} 条）` : ''}`,
    time: msg.time ?? msg.createdAt ?? '',
    full: detail || undefined,
    compact: true,
    detailTitle: '裁剪摘要',
  }
}

/** 轨迹记录单行预览：有界源文 → 纯文本投影 → 折成单行 → 超 60 字截断 + 省略号。
 *
 *  <p>投影走 {@link projectPreview}：**先按字符截断源文、再投影**（顺序不可换，已由
 *  markdown/__tests__/plainPreview.test.ts 钉住 —— 反过来等于对全文 parse）。
 *  窗口大小用 {@link PREVIEW_SOURCE_CHARACTERS}（与 harness 同值的单点约定，见 plainPreview.ts）。
 *
 *  <p>投影用 parseGfm（流式臂：**无 math 扩展、不跑 rescue**），故行内 {@code $$x$$} 与
 *  粘连标记（{@code ##标题}，即 rescue 会补空格的那类）**在本预览里保持字面** —— 与展开态
 *  渲染可能不同（预览少剥、不藏内容，两者语法不同源）。投影还逐行 trim，**代码块缩进会被
 *  抹平**（已知取舍）。
 *
 *  <p>投影结果**可能为空串**（当这 {@link PREVIEW_SOURCE_CHARACTERS} 个字符全是
 *  thematicBreak / definition 时，例如用户只发了 {@code ---} 或一行
 *  {@code [ref]: http://…}）→ txt 为空，此时本落点会渲染出一条空内容记录行（kind/time 仍在；
 *  详情浮层仍可按 full 看原文）。**派生影响**：{@code turns} 分组里 turn 标题取自
 *  {@code recs[0].txt.slice(0, 30)}，故空投影的 user 消息会把 turn 标题一起变空（渲染成
 *  「#1 」而此前是「#1 ---」）——同属该边界的后果，此处保留（不额外造兜底文案）。
 *
 *  <p>WHY：原先 user/system 分支把 markdown 源文**全文直出**、assistant 分支取源文前 60 字
 *  → {@code #} 标题 / {@code **} 粗体 / 围栏等标记字面泄漏；顺带把「用户长正文把轨迹列表撑爆」
 *  收敛成单行（3 个分支同一口径）。{@code full} 仍是原文，详情浮层看原文，不受投影影响。 */
function previewTxt(content: string): string {
  const plain = projectPreview(content, PREVIEW_SOURCE_CHARACTERS).replace(/\s+/g, ' ').trim()
  return plain.length > 60 ? `${plain.slice(0, 60)}…` : plain
}

/** 单条消息 → 轨迹记录数组（user 1 条 / assistant 1 条 + 每条 toolCall 1 条，保持顺序） */
function toRecords(msg: ChatMessageDto, snipped: boolean): TraceRecord[] {
  const time = msg.time ?? msg.createdAt ?? ''
  const content = msg.content ?? ''
  if (msg.role === 'user') {
    return [{ kind: 'user', toolClass: '', toolName: '', txt: previewTxt(content), time, full: content, snipped }]
  }
  if (msg.role === 'system') {
    // fallback / 系统消息：归为 assistant 类别，避免空行
    return [{ kind: 'assistant', toolClass: '', toolName: '', txt: previewTxt(content), time, full: content, snipped }]
  }
  const records: TraceRecord[] = []
  for (const tc of msg.toolCalls ?? []) {
    records.push({
      kind: 'tool',
      toolClass: toolKind(tc.name ?? ''),
      toolName: cleanToolName(tc.name),
      txt: summarizeArgs(tc),
      time,
      full: tc.arguments ?? '',
      snipped,
    })
  }
  if (content) {
    records.push({
      kind: 'assistant',
      toolClass: '',
      toolName: '',
      txt: previewTxt(content),
      time,
      full: content,
      snipped,
    })
  }
  return records
}

/** 工具参数摘要：取 arguments 前 40 字（JSON 截断到可读） */
function summarizeArgs(tc: ToolCallDto): string {
  const args = tc.arguments ?? ''
  const cleaned = args.replace(/\s+/g, ' ').trim()
  return cleaned.length > 40 ? `${cleaned.slice(0, 40)}…` : cleaned
}

interface TraceViewProps {
  messages: ChatMessageDto[]
  /** [snip-persist] 当前会话已被 Snip 裁剪的消息 id（命中 → 行标注「已裁剪」）· 缺省 [] */
  snippedIds?: string[]
}

/** 一组轨迹记录（一个 turn + 其标题） */
interface Turn {
  num: number
  title: string
  records: TraceRecord[]
}

/** snippedIds 的缺省值：模块级常量，**不是**每次调用新建的 [] —— 否则该 prop 恒换引用，
 *  下游 useMemo 依赖永不相等、缓存全废（调用点 App.tsx:350 已在传稳定引用）。 */
const EMPTY_SNIPPED_IDS: string[] = []

export function TraceView({ messages, snippedIds = EMPTY_SNIPPED_IDS }: TraceViewProps) {
  // [轨迹详情] 点击记录行 → 浮层看完整内容
  const [detail, setDetail] = useState<TraceRecord | null>(null)
  const detailLabel = detail
    ? (detail.detailTitle ?? (detail.compact ? '压缩摘要' : detail.kind === 'user' ? '用户消息' : detail.kind === 'tool' ? `工具调用 · ${detail.toolName || 'tool'}` : '助手回复'))
    : ''
  // [性能] 以下 useMemo 必须位于**所有提前 return 之前**（守 hooks 顺序 —— 分组循环原先在
  //   `visible.length === 0` 的提前 return 之后，直接把 useMemo 放那儿会破坏 hooks 顺序）。
  //   WHY 要包：分组会对**全量消息**逐条跑 toRecords → previewTxt → projectPreview（非增量 parse，
  //   实测 ~1.5ms/条，1000 条 ≈ 1.5s/次渲染），而改动前这里是廉价切片（~0）；trace tab 打开期间
  //   每次结构性重渲（点行开详情、父组件重渲）都会整份重跑。依赖全部取稳定引用（props 快照 +
  //   会话级 store 数组），故同一份输入只算一次。与 MessageList.ContentGuard 的 useMemo 同款写法。
  const visible = useMemo(() => messages.filter((m) => !m.isMeta), [messages])
  const snippedSet = useMemo(() => new Set(snippedIds), [snippedIds])
  // [compact 边界] 一次前向扫出的 boundary↔摘要 配对表（栈式配对；跨 boundary 扫描会漏掉 from 方向的
  //   外边界摘要 —— 详见 utils/compactPairing.ts 注释）。按 boundary 消息 id 查表。
  const summaryByBoundary = useMemo(() => pairCompactSummaries(visible), [visible])

  // 按 turn 分组：每条 user 消息开新 turn；无 user 起始时兜底为单 turn
  const turns = useMemo(() => {
    const out: Turn[] = []
    let cur: Turn | null = null
    for (let i = 0; i < visible.length; i++) {
      const msg = visible[i]
      // [compact 边界] compact 替换点（CC boundary → summary → kept…）→ 当前 turn 内插一条「已压缩」标记行；
      //   无 turn 上下文（如压缩后首条）时兜底开「会话」分组（与下方非 user 起始同款兜底）。
      //   摘要正文取自 boundary↔摘要 配对表（pairCompactSummaries；后端的摘要消息本身也会
      //   作为普通 user 记录渲染，此处只借用其正文作为标记详情）。
      if (msg.subtype === 'compact_boundary') {
        if (cur === null) {
          cur = { num: out.length + 1, title: '会话', records: [] }
          out.push(cur)
        }
        cur.records.push(compactMarker(msg, summaryByBoundary.get(msg.id) ?? ''))
        continue
      }
      // [compact 边界] microcompact 微压缩分界 → 居中标记条（与聊天区同文案），不落普通记录行
      if (msg.subtype === 'microcompact_boundary') {
        if (cur === null) {
          cur = { num: out.length + 1, title: '会话', records: [] }
          out.push(cur)
        }
        cur.records.push(microcompactMarker(msg))
        continue
      }
      // [snip 边界] snip 裁剪分界 → 居中标记条（与聊天区同文案），不落普通记录行（P2-19）
      if (msg.subtype === 'snip_boundary') {
        if (cur === null) {
          cur = { num: out.length + 1, title: '会话', records: [] }
          out.push(cur)
        }
        cur.records.push(snipBoundaryMarker(msg))
        continue
      }
      const recs = toRecords(msg, msg.id != null && snippedSet.has(msg.id))
      if (recs.length === 0) continue
      if (msg.role === 'user') {
        cur = { num: out.length + 1, title: recs[0].txt.slice(0, 30), records: [] }
        out.push(cur)
      } else if (cur === null) {
        cur = { num: out.length + 1, title: '会话', records: [] }
        out.push(cur)
      }
      cur.records.push(...recs)
    }
    return out
  }, [visible, snippedSet, summaryByBoundary])

  if (visible.length === 0) {
    return (
      <div className="trace-view">
        <div className="trace-empty">该会话暂无轨迹</div>
      </div>
    )
  }
  if (turns.length === 0) {
    return <div className="trace-view"><div className="trace-empty">该会话暂无轨迹</div></div>
  }

  return (
    <div className="trace-view">
      {turns.map((t) => (
        <div key={t.num} className="trace-turn">
          <div className="trace-turn-header"><span className="turn-num">#{t.num}</span> {t.title}</div>
          {t.records.map((r, i) => r.compact ? (
            // [compact 边界] 居中灰条标记行（内容可点开读压缩摘要）
            <div
              key={i}
              className={`trace-compact-marker${r.full != null ? ' clickable' : ''}`}
              onClick={() => { if (r.full != null) setDetail(r) }}
              title={r.full != null ? `点击查看${r.detailTitle ?? '压缩摘要'}` : undefined}
            >
              <span className="trace-compact-txt">{r.txt}</span>
            </div>
          ) : (
            <div
              key={i}
              className={`trace-record ${r.kind === 'user' ? 'user-rec' : r.kind === 'assistant' ? 'assistant-rec' : r.toolClass}${r.snipped ? ' snipped' : ''}${r.full != null ? ' clickable' : ''}`}
              onClick={() => { if (r.full != null) setDetail(r) }}
              title={r.full != null ? '点击查看完整内容' : undefined}
            >
              <span className={`kind ${r.kind}`}>{r.kind}</span>
              {r.snipped && <span className="trace-snipped-tag" title="该消息已被 Snip 裁剪，不再发送给模型">已裁剪</span>}
              <span className="content">
                {r.kind === 'tool' ? (
                  <span className={`tool-name ${r.toolClass.replace('tool-', '')}`}>{r.toolName}</span>
                ) : r.kind === 'user' ? (
                  <span className="user-txt">{r.txt}</span>
                ) : (
                  r.txt
                )}
              </span>
              <span className="time">{r.time}</span>
            </div>
          ))}
        </div>
      ))}
      {detail && (
        <div className="trace-detail-mask" onClick={() => setDetail(null)}>
          <div className="trace-detail" onClick={(e) => e.stopPropagation()}>
            <div className="trace-detail-head">
              <span className="trace-detail-title">{detailLabel}</span>
              <button type="button" className="trace-detail-close" onClick={() => setDetail(null)} aria-label="关闭">
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" width="12" height="12"><path d="M3 3L9 9M9 3L3 9" /></svg>
              </button>
            </div>
            <pre className="trace-detail-body">{detail.full ?? detail.txt}</pre>
          </div>
        </div>
      )}
    </div>
  )
}
