import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MarkdownText } from '@/markdown/MarkdownText'
import type { ChatMessageDto } from '@/api/types'
import { subagentColor } from '@/api/types'
import { compactNumber } from '@/utils/format'
import { parseAnsiLines, type AnsiLine } from '@/utils/ansi'
import { useSubagentStore } from '@/stores/subagentStore'
import { useChatStore } from '@/stores/chatStore'
import type { StreamBlock, ApiFlowError } from '@/stores/chatStore'
import { tasksApi } from '@/api/tasks'
import { usePreviewStore } from '@/stores/previewStore'

/** 附件胶囊类型图标：PDF 红 / Word 蓝 / Excel 绿 文字徽标；视频/音频/文件 SVG 图标 */
function attachIcon(a: NonNullable<ChatMessageDto['userAttachments']>[number]) {
  const lower = a.filename.toLowerCase()
  if (a.type === 'pdf' || lower.endsWith('.pdf')) return <span className="uaf-badge pdf">PDF</span>
  if (lower.endsWith('.docx') || lower.endsWith('.doc')) return <span className="uaf-badge word">W</span>
  if (lower.endsWith('.xlsx') || lower.endsWith('.xls')) return <span className="uaf-badge excel">X</span>
  if (a.type === 'video') return (
    <span className="uaf-badge icon video"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg></span>
  )
  if (a.type === 'audio') return (
    <span className="uaf-badge icon audio"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></svg></span>
  )
  return (
    <span className="uaf-badge icon file"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg></span>
  )
}

/** 消息时间：优先后端人读相对时间（GET /messages time：刚刚/N分钟前/N小时前/N天前/≥30天 yyyy-MM-dd）；
 *  无则回落 createdAt HH:MM（live 消息兜底）；都缺省 → '' */
function formatMsgTime(msg: ChatMessageDto): string {
  if (msg.time) return msg.time
  return formatMsgTimeAbsolute(msg)
}

/** 消息绝对时间（HH:MM）：从 createdAt 解析（live 消息 / 相对时间缺省时兜底）；解析失败 → '' */
function formatMsgTimeAbsolute(msg: ChatMessageDto): string {
  if (msg.createdAt) {
    const d = new Date(msg.createdAt)
    if (!Number.isNaN(d.getTime())) {
      return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
    }
  }
  return ''
}

/** 思考文本清理：过滤 null 重复串（流式累积残留）+ trim；空 → ''（不展示） */
function cleanReasoning(s?: string | null): string {
  if (!s) return ''
  return s.replace(/(?:null)+/g, '').trim()
}

/** 对话正文 markdown 走增量 mdast 渲染器（@/markdown/MarkdownText，双态 streaming/settled）。 */

/** 流式 API 错误卡（message.error → 对话流助手回复位置渲染 · 对齐 CC assistant API error / isApiErrorMessage 展示） */
function ApiErrorCard({ err }: { err: ApiFlowError }) {
  return (
    <div className="msg assistant">
      <div className="avatar">N</div>
      <div className="body">
        <div className="author">nexus</div>
        <div className="api-error-card" role="alert">
          <svg width={14} height={14} viewBox="0 0 14 14" fill="none" stroke="var(--error)" strokeWidth={1.5} style={{ flexShrink: 0, marginTop: 1 }}>
            <circle cx="7" cy="7" r="5.5" />
            <path d="M7 4.5V7.5" />
            <path d="M7 9.8h.01" />
          </svg>
          <div>
            <div className="api-error-title">模型调用失败</div>
            <div className="api-error-msg">{err.message}</div>
          </div>
        </div>
      </div>
    </div>
  )
}

/** 复制按钮（工具输出行 · 常显半透明，hover 全显；点击复制 + 「已复制」短暂反馈） */
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      className="tc-copy"
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setCopied(true)
          window.setTimeout(() => setCopied(false), 1200)
        }).catch(() => {})
      }}
    >
      {copied ? '已复制' : '复制'}
    </button>
  )
}

/** ANSI 终端输出渲染（借鉴 TerminalBlock：彩色 spans + 超长 head-tail 截断）。 */
function AnsiOutput({ text, error }: { text: string; error?: boolean }) {
  const [expanded, setExpanded] = useState(false)
  // 截断阈值：对齐 DeepSeek DEFAULT_TERMINAL_MAX_LINES（16 行），超长折叠中间
  const MAX_LINES = 16
  const lines = useMemo(() => parseAnsiLines(text), [text])
  // 去除末尾纯空行（命令输出的换行终止符不是额外空行）
  const trimmed = useMemo(() => {
    const arr = [...lines]
    while (arr.length > 1 && arr[arr.length - 1]!.every((s) => s.text.trim() === '')) arr.pop()
    return arr
  }, [lines])
  const capped = trimmed.length > MAX_LINES
  const hidden = capped ? trimmed.length - MAX_LINES : 0
  const shown = expanded || !capped ? trimmed : trimmed.slice(0, MAX_LINES)

  const renderLine = (line: AnsiLine, i: number) => (
    <div key={i} className="tc-ansi-line">
      {line.map((span, j) => span.style === undefined
        ? span.text
        : <span key={j} style={span.style}>{span.text}</span>)}
    </div>
  )

  return (
    <div className={`tc-ansi${error ? ' error' : ''}`}>
      {shown.map(renderLine)}
      {capped && (
        <button type="button" className="tc-ansi-toggle" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '收起' : `… 展开其余 ${hidden} 行`}
        </button>
      )}
    </div>
  )
}

/** FM-14 · MCP 工具显示名清理：剥 mcp__<server>__ 前缀，去尾部 (MCP) 后缀与前导 ' - ' 连接符；无前缀原样返回 */
function cleanToolName(name: string | null): string {
  let n = (name ?? 'tool').trim()
  n = n.replace(/^\s*-\s*/, '').trim() // 前导 ' - ' 连接符（若存在）
  if (n.startsWith('mcp__')) {
    const rest = n.slice('mcp__'.length)
    const sep = rest.indexOf('__')
    n = (sep === -1 ? rest : rest.slice(sep + 2)).trim()
  }
  if (n.endsWith('(MCP)')) n = n.slice(0, -'(MCP)'.length).trim()
  return n
}

/** F30/F33 · finishReason → 退出角标中文文案（stop 不显示；未知值静默） */
const FINISH_REASON_LABEL: Record<string, string | undefined> = {
  stop: undefined,
  length: '已截断',
  error: '出错',
  tool_calls: '工具调用',
  max_turns: '达到轮次上限',
  max_tokens: '达到长度上限',
  content_filter: '内容被过滤',
}

/** F30/F33 · 退出角标配色（未映射的 reason 回落默认） */
const FINISH_REASON_COLOR: Record<string, string> = {
  length: 'var(--warning)',
  error: 'var(--error)',
  tool_calls: 'var(--running)',
  max_turns: 'var(--warning)',
  max_tokens: 'var(--warning)',
  content_filter: 'var(--warning)',
}

interface MessageListProps {
  messages: ChatMessageDto[]
  /** 契约 #1：流式按 assistantMessageId 分块（每轮独立 thinking/content，三字段皆可空）；
   *   complete 后 App 重拉 DB 权威多轮链替换，此处仅流式进行中展示 */
  streaming?: StreamBlock[] | null
  onDelete: (messageId: string) => void
  /** 会话当前 conversationId（partial 压缩/裁剪后旋转）：并入消息 row key，触发整列表 remount */
  conversationId?: string | null
  /** 外部滚底信号（权限卡片出现等 App 层事件）：值变化时强制滚到底部 */
  scrollSignal?: number
  /** turn 运行中且无流式块（thinking/重试等待期）→ 消息流末尾显示「nexus 思考中…」占位，消除发送后空白间隙 */
  thinking?: boolean
  /** 滚动贴底状态回调（「回到底部」按钮由 Composer 工具栏渲染 · 离底时 App 传 showToBottom=true） */
  onNearBottomChange?: (atBottom: boolean) => void
}

/** 工具调用卡片 · FNT-TC-01：消息级 matchedRule（后端 ChatMessageDto 顶层出站）→ 显示「已自动批准（规则X）」徽标；无数据静默 */
function ToolCard({ tool, matchedRule }: { tool: NonNullable<ChatMessageDto['toolCalls']>[number]; matchedRule: string | null }) {
  // 工具卡片默认折叠（用户手动点击展开 IN/OUT）· 对齐 Harness ToolRow；组件本地展开态
  const [expanded, setExpanded] = useState(false)
  const rule = matchedRule
  const name = cleanToolName(tool.name) // FM-14 · 剥离 mcp__<server>__ 前缀等
  // F21 · isDestructive 工具名标红（后端 DTO 未出站该字段，无数据时静默）
  const destructive = (tool as NonNullable<ChatMessageDto['toolCalls']>[number] & { isDestructive?: boolean }).isDestructive ?? false
  // #30 · WebSearch 工具结果展示（v0.4.4 契约）：tool.result JSON 抽取 outputShape——
  //   query=搜索词；results 中对象块（content:[{title,url}]）= hits 列表；string 项 = 弱模型总结。
  //   勿读已删除的 summary 键；result 缺失/解析失败 → 「搜索中…/总结中…」（按 tool.isError 显示失败）。
  const isWs = /web[\s_-]?search/i.test(name)
  const wsData = isWs ? (() => {
    try {
      const r: { query?: unknown; results?: unknown } = tool.result ? JSON.parse(tool.result) : null
      if (!r || typeof r !== 'object') return null
      const results = Array.isArray(r.results) ? r.results : []
      const hits: { title: string; url: string }[] = []
      const summaries: string[] = []
      for (const item of results) {
        if (typeof item === 'string') {
          if (item.trim()) summaries.push(item.trim())
        } else if (item && typeof item === 'object') {
          const content = (item as { content?: unknown }).content
          if (Array.isArray(content)) {
            for (const h of content) {
              if (h && typeof h === 'object' && typeof (h as { title?: unknown }).title === 'string' && typeof (h as { url?: unknown }).url === 'string') {
                hits.push({ title: (h as { title: string }).title, url: (h as { url: string }).url })
              }
            }
          }
        }
      }
      return {
        query: typeof r.query === 'string' && r.query ? r.query : null,
        hits,
        weakSummary: summaries.join('\n'),
      }
    } catch { return null }
  })() : null
  // 工具状态：收到 tool_result（result 由 null → 有值，含空串 = 无输出成功命令）即已完成；
  //   result 仍为 null 且非 error = 执行中（OUT 未回前不应标「已完成」）。
  //   WHY：空输出成功 Bash（cmd start 开浏览器等零 stdout）后端实时推 result=""，旧实现拿
  //   result.trim()!=='' 判完成 → 空结果被当未完成 → 永久「执行中」假卡
  //   （BashTool 空输出假卡事故 2026-09-05 · 修复 B）。OUT 区显隐用内联 result.trim()!==''（下方 body）。
  const hasResult = tool.result != null
  // [Ctrl+B 转后台] 运行中的前台工具卡（Bash/Agent · 后端按类型自动分发）：toolUseId 关联 taskId →
  //   isBackgrounded!==true（前台）且 running → 显示「转后台」；已后台化任务不显示（异步面板可见）
  const runningFront = !hasResult && !tool.isError
  const [bgTaskId, setBgTaskId] = useState<string | null>(null)
  useEffect(() => {
    if (!runningFront) { setBgTaskId(null); return }
    let alive = true
    tasksApi.list().then((list) => {
      if (!alive) return
      const t = (list ?? []).find((x) => x.toolUseId === tool.id && x.isBackgrounded !== true && x.status === 'running')
      if (t) setBgTaskId(t.id)
    }).catch(() => {})
    return () => { alive = false }
  }, [runningFront, tool.id])
  const doBackground = async () => {
    if (!bgTaskId) return
    try {
      await tasksApi.background(bgTaskId)
      setBgTaskId(null)
    } catch { /* fail loud：保留按钮可重试 */ }
  }
  return (
    <div className={`tool-card${expanded ? ' open' : ''}`}>
      <button type="button" className="head" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded}>
        <svg className="tc-chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5}>
          <path d="M5 6.5L8 9.5L11 6.5" />
        </svg>
        <span className={`name${destructive ? ' danger' : ''}`}>{name}</span>
        {rule && (
          <span className="auto-approved" title="该工具调用已被规则自动批准">
            <svg width={9} height={9} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth={1.6}>
              <path d="M2.5 6.5l2.2 2.2 4.8-5" />
            </svg>
            已自动批准（{rule}）
          </span>
        )}
        <span className={`status ${tool.isError ? 'error' : hasResult ? 'done' : 'running'}`}>
          {tool.isError ? '失败' : hasResult ? '已完成' : '执行中'}
        </span>
        {bgTaskId && (
          <span role="button" className="tc-bg" onClick={(e) => { e.stopPropagation(); e.preventDefault(); void doBackground() }} title="转到后台继续运行（Ctrl+B）">
            转后台
          </span>
        )}
      </button>
      {expanded && (isWs ? (
        wsData ? (
          <div className="ws-body">
            {wsData.query && <div className="ws-query">{wsData.query}</div>}
            {wsData.hits.length > 0 && (
              <ul className="ws-hits">
                {wsData.hits.map((h, i) => (
                  <li key={i} className="ws-hit">
                    <a href={h.url} target="_blank" rel="noreferrer">{h.title}</a>
                    <span className="ws-url">{h.url}</span>
                  </li>
                ))}
              </ul>
            )}
            {wsData.weakSummary ? (
              <div className="ws-summary">{wsData.weakSummary}</div>
            ) : wsData.hits.length > 0 ? (
              <div className="ws-summary ws-pending">总结中…</div>
            ) : (
              <div className="ws-pending">{tool.isError ? '搜索失败' : '搜索中…'}</div>
            )}
          </div>
        ) : (
          <div className="body">{tool.isError ? '搜索失败' : '搜索中…'}</div>
        )
      ) : (
        <div className="tc-io">
          {tool.arguments != null && tool.arguments.trim() !== '' && (
            <div className="tc-io-row">
              <span className="tc-io-label">IN</span>
              <span className="tc-io-text">{tool.arguments}</span>
            </div>
          )}
          {tool.arguments != null && tool.arguments.trim() !== '' && tool.result != null && tool.result.trim() !== '' && (
            <span className="tc-io-divider" aria-hidden />
          )}
          {tool.result != null && tool.result.trim() !== '' && (
            <div className="tc-io-row out">
              <span className="tc-io-label">OUT</span>
              <AnsiOutput text={tool.result} error={tool.isError ?? false} />
              <CopyButton text={tool.result} />
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

/** 单条超长正文防护：正文 > HEAVY_CONTENT_CHARS 时只渲染截断纯文本预览 + 「查看完整内容」，
 *  展开后才走整段 mdast（MarkdownText）。否则打开含 350KB 级单条消息的会话会被一条 DOM 卡死
 *  ——窗口化只限「条数」不限「单条体积」。初始加载不被病理大消息阻塞，展开由用户主动触发。 */
const HEAVY_CONTENT_CHARS = 20_000
const HEAVY_PREVIEW_CHARS = 5_000
function ContentGuard({ text, className, onRunHtml }: { text: string; className: string; onRunHtml?: (code: string) => void }) {
  const heavy = text.length > HEAVY_CONTENT_CHARS
  const [expanded, setExpanded] = useState(false)
  if (!heavy || expanded) return <MarkdownText text={text} className={className} onRunHtml={onRunHtml} />
  const shown = text.slice(0, HEAVY_PREVIEW_CHARS)
  return (
    <div className={className}>
      <pre className="heavy-preview">{shown}{text.length > HEAVY_PREVIEW_CHARS ? '…' : ''}</pre>
      <button type="button" className="heavy-expand" onClick={() => setExpanded(true)} title="展开后需渲染完整内容，可能短暂卡顿">
        查看完整内容（本条 {Math.round(text.length / 1024)}KB · 展开较耗资源）
      </button>
    </div>
  )
}

function Message({ msg, onDelete, onRunHtml }: { msg: ChatMessageDto; onDelete: (id: string) => void; onRunHtml?: (code: string) => void }) {
  const isUser = msg.role === 'user'
  // F25 · model_fallback_warning：role=system + subtype='informational' 的消息按「模型降级」提示渲染
  const isFallback = msg.role === 'system' && msg.subtype === 'informational'
  // CRON · scheduled_task_fire：定时任务触发系统通知（对齐 CC SystemTextMessage.tsx:137 「❋ 任务执行中」）
  const isScheduledFire = msg.role === 'system' && msg.subtype === 'scheduled_task_fire'
  // 裁剪/压缩边界消息（role=system + subtype 分界线标记）：compact_boundary 自动/手动压缩分界、
  //   microcompact_boundary 微压缩、snip_boundary snip 裁剪分界；snip_marker 为 snip 内部标记（不渲染）。
  //   渲染为居中「裁剪标记条」（对齐 CC SystemTextMessage / AttachmentMessage 边界视觉：居中 muted + 分隔线）
  const isSnipMarker = msg.role === 'system' && msg.subtype === 'snip_marker'
  const isCompactBoundary = msg.role === 'system' && msg.subtype === 'compact_boundary'
  const isMicrocompactBoundary = msg.role === 'system' && msg.subtype === 'microcompact_boundary'
  const isSnipBoundary = msg.role === 'system' && msg.subtype === 'snip_boundary'
  const isBoundary = isCompactBoundary || isMicrocompactBoundary || isSnipBoundary
  // 边界标签文案（中文）：compact 有 compactMetadata.preTokens/postTokens 时追加 token 数（122k→42k）；
  //   snip 有 removedUuids 时说明移除条数
  const boundaryLabel = (() => {
    if (isCompactBoundary) {
      const m = msg.compactMetadata
      const pre = m?.preTokens != null && m.preTokens > 0 ? compactNumber(m.preTokens) : null
      const post = m?.postTokens != null && m.postTokens > 0 ? compactNumber(m.postTokens) : null
      return `已压缩 · 对话历史已总结${pre != null && post != null ? ` · ${pre}→${post}` : ''}`
    }
    if (isMicrocompactBoundary) return '已清理 · 旧工具输出已清除'
    if (isSnipBoundary) {
      const n = msg.snipMetadata?.removedUuids?.length ?? 0
      return `已裁剪 · 历史消息已由 Snip 移除${n > 0 ? `（${n} 条）` : ''}`
    }
    return ''
  })()
  // 边界消息悬停提示：compact 携带 summary 时显示压缩摘要
  const boundaryTitle = isCompactBoundary ? (msg.compactMetadata?.summary ?? undefined) : undefined
  const deletable = isUser || msg.role === 'assistant'
  // FNT-SUB-01/07：author 带真实身份（非 nexus）→ 判定为子代理消息，作者区显示子代理名 + 颜色点徽标
  const isSubagent = msg.role === 'assistant' && msg.author != null && msg.author !== '' && msg.author !== 'nexus'
  // SUB-10 · 子代理身份：join key = msg.toolCallId ↔ task 事件 tool_use_id（后端实测同源）。
  //   selector 直接精确键访问 s.identities[toolCallId]（避免 resolve 内 get() 全量扫描）；
  //   未命中再按 author 精确键 + byName 兜底。无身份数据则非子代理（显示 nexus）。
  //   对齐 CC AttachmentMessage.tsx:466-479。
  const subagentId = useSubagentStore((s) => {
    const session = s.bySession[msg.sessionId] ?? {}
    return session[msg.toolCallId ?? ''] ?? session[msg.author ?? ''] ?? (msg.author ? Object.values(session).find((id) => id.name === msg.author) ?? null : null)
  })
  const subagentName = isSubagent ? (msg.author ?? subagentId?.name) : subagentId?.name
  const showSubagent = !isUser && !isFallback && (isSubagent || subagentId != null)
  // F30/F33 · finishReason 退出角标文案（stop/未知值 → null 不渲染）
  const exitLabel = FINISH_REASON_LABEL[msg.finishReason ?? ''] ?? null
  // 37 · thinking 耗时（ms → 秒取整）；无耗时 → 标题不追加时长
  const reasoningLabel = msg.reasoningDurationMs != null && msg.reasoningDurationMs > 0
    ? `（用时 ${Math.round(msg.reasoningDurationMs / 1000)}s）`
    : ''
  // CHK-8 · output_token_usage attachment（后端 F37 已实施）：优先走附件三值（turn/session/budget），
  // 无附件则回落到 msg.outputTokens（优雅降级）。对齐 CC messages.ts:4077-4089。
  const tokenUsage = msg.attachments?.find((a) => a.attachmentType === 'output_token_usage')
  const [hovered, setHovered] = useState(false)
  // 思考块折叠：历史消息默认收起（可手动展开）；流式消息（streaming.reasoning）保持展开
  const [showReasoning, setShowReasoning] = useState(false)
  // 消息时间切换：默认相对时间（44分钟前）· 点击切绝对时间（HH:MM）· 再点切回
  const [showAbsTime, setShowAbsTime] = useState(false)
  // 图片附件放大预览（user-attach-img 点击 → lightbox）
  const [zoomImg, setZoomImg] = useState<string | null>(null)
  // 图片渲染源：乐观追加 imageData（本地 base64）优先；否则按 imagePasteIds 从 imageCache 取（重拉后 batch 拉图）
  const imageCache = useChatStore((s) => s.imageCache)
  // [snip-persist] 该消息是否已被 Snip 裁剪（实时 STOMP message.boundary + F5 GET /messages 解析合并进 snippedIds）
  const snippedIds = useChatStore((s) => s.snippedIds)
  const isSnipped = (snippedIds[msg.sessionId] ?? []).includes(msg.id)
  const userImages = useMemo(() => {
    if (msg.imageData?.length) return msg.imageData
    return (msg.imagePasteIds ?? [])
      .map((id) => imageCache[msg.sessionId]?.[id])
      .filter((v): v is { mediaType: string; base64: string } => !!v)
  }, [msg.imageData, msg.imagePasteIds, msg.sessionId, imageCache])
  // 对齐 Harness ChatView：展开思考/工具块是内容变化而非流尖推进，不触发滚动跟随。
  //   用户上滚查历史时点开思考下拉，滚动位置保持不动（治「点开思考回到底部」）。
  // snip_marker：snip 内部标记消息，前端不渲染（仅占位记录，避免气泡噪音）
  if (isSnipMarker) return null
  // 边界消息（裁剪/压缩分界线）：渲染为居中的「裁剪标记条」，非普通消息气泡
  if (isBoundary) {
    return (
      <div className="msg boundary">
        <div className="boundary-note">
          <span className="boundary-label" title={boundaryTitle}>{boundaryLabel}</span>
        </div>
      </div>
    )
  }
  return (
    <div
      className={`msg ${isUser ? 'user' : isFallback ? 'fallback' : isScheduledFire ? 'system-notice' : 'assistant'}`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {hovered && (
        <div className="msg-hover-actions">
          {deletable && (
            <button
              className="delete-btn"
              onClick={() => onDelete(msg.id)}
              title="删除消息"
            >
              删除
            </button>
          )}
        </div>
      )}
      {isUser ? (
        <div className="user-msg">
          {isSnipped && (
            <div className="user-snipped"><span className="snipped-badge" title="该消息已被 Snip 裁剪，不再发送给模型">已裁剪</span></div>
          )}
          {userImages.map((img, i) => (
            <img key={i} src={`data:${img.mediaType};base64,${img.base64}`} alt="图片附件" className="user-attach-img" style={{ cursor: 'zoom-in' }} onClick={() => setZoomImg(`data:${img.mediaType};base64,${img.base64}`)} />
          ))}
          {/* 文件附件（PDF/Word/视频/音频）内联在 user 气泡里（文字下方 · 点击预览）——
              图片附件走上方缩略图 imageData/imagePasteIds 通道 */}
          <div className="user-bubble">
            <ContentGuard text={msg.content ?? ''} className="user-text md" onRunHtml={onRunHtml} />
            {msg.userAttachments?.filter((a) => a.type !== 'image' && a.filename).map((a, i) => (
              <button key={i} className="user-attach-file" title={`点击预览：${a.filename}`} onClick={() => usePreviewStore.getState().open({ kind: 'attachment', title: a.filename, item: a })}>
                {attachIcon(a)}
                <span className="uaf-name">{a.filename}</span>
              </button>
            ))}
          </div>
        </div>
      ) : isFallback ? (
        <div className="fallback-banner">
          <div className="head"><svg className="icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M7 2L12 11H2L7 2Z" /><path d="M7 6V8.5" /><path d="M7 10V10.01" /></svg>模型降级</div>
          <div className="body">{msg.content ?? ''}</div>
        </div>
      ) : isScheduledFire ? (
        <div className="system-notice">
          <span className="sn-icon">❋</span>
          <span className="sn-text">{msg.content || '定时任务已触发，正在执行'}</span>
        </div>
      ) : (
        <>
          <div className="avatar">N</div>
          <div className="body">
            <div className="author">
              {showSubagent ? (
                <>
                  <span
                    className="subagent-dot"
                    style={{ background: subagentId?.color ?? subagentColor(subagentName ?? msg.author) }}
                  />
                  @{subagentName ?? '子代理'}
                </>
              ) : 'nexus'}
              <span
                className="ts"
                title={showAbsTime ? '点击切换为相对时间' : '点击切换为绝对时间'}
                onClick={() => setShowAbsTime((v) => !v)}
                style={{ cursor: 'pointer' }}
              >
                {showAbsTime ? (formatMsgTimeAbsolute(msg) || formatMsgTime(msg)) : formatMsgTime(msg)}
              </span>
              {/* [snip-persist] 已被 Snip 裁剪的消息右上角标注「已裁剪」 */}
              {isSnipped && (
                <span className="snipped-badge" title="该消息已被 Snip 裁剪，不再发送给模型">已裁剪</span>
              )}
              {exitLabel && (
                <span className="exit-badge" style={{ color: FINISH_REASON_COLOR[msg.finishReason ?? ''] }}>{exitLabel}</span>
              )}
            </div>
            {cleanReasoning(msg.reasoning) && (
              <div className={`thinking-wrap${showReasoning ? ' open' : ''}`}>
                <button className="thinking-toggle" onClick={() => setShowReasoning((v) => !v)}>
                  <svg viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
                  <span>已思考{reasoningLabel}</span>
                </button>
                {showReasoning && <div className="thinking-body">{cleanReasoning(msg.reasoning)}</div>}
              </div>
            )}
            {msg.isApiErrorMessage ? (
              <div className="content error">
                <p>{msg.apiError ?? msg.error ?? 'API 错误'}</p>
                {msg.errorDetails && <p className="error-details">{msg.errorDetails}</p>}
              </div>
            ) : (
              <ContentGuard text={msg.content ?? ''} className="content md" onRunHtml={onRunHtml} />
            )}
            {/* CHK-8 · token 用量 / 花费：优先 complete 事件透传的真实 usage（本轮输入↑输出↓ + 会话花费¥），
                无则回落 output_token_usage attachment（turn/session/budget），再回落 msg.outputTokens；均无数据不渲染 */}
            {msg.usage && (msg.usage.input_tokens != null || msg.usage.output_tokens != null) ? (
              <div className="msg-usage">
                {msg.usage.input_tokens != null && <span className="usage-up">↑{compactNumber(msg.usage.input_tokens)}</span>}
                {msg.usage.output_tokens != null && <span className="usage-down">↓{compactNumber(msg.usage.output_tokens)}</span>}
                {/* 金额已移出消息（msg.totalCostUsd 是会话累计值，每轮重复显示错乱）→ 集中在底部 footer 汇总 */}
              </div>
            ) : tokenUsage && (tokenUsage.outputTokenTurn != null || tokenUsage.outputTokenSession != null) ? (
              <div className="msg-usage">
                {tokenUsage.outputTokenTurn != null && `本轮 ${compactNumber(tokenUsage.outputTokenTurn)}`}
                {tokenUsage.outputTokenBudget != null && ` / ${compactNumber(tokenUsage.outputTokenBudget)}`}
                {tokenUsage.outputTokenSession != null && ` · 会话 ${compactNumber(tokenUsage.outputTokenSession)}`}
              </div>
            ) : msg.outputTokens != null && msg.outputTokens > 0 ? (
              // [bug-368] fallback 显示的是 token 数（本轮输出）非金额 —— 补单位标注，消除与 footer
              //   「¥ 金额」误读（此前「本轮 368」视觉像 $368 金额）
              <div className="msg-usage">本轮输出 {compactNumber(msg.outputTokens)} tokens</div>
            ) : null}
            {msg.toolCalls?.map((t, i) => <ToolCard key={t.id ?? i} tool={t} matchedRule={msg.matchedRule} />)}
          </div>
        </>
      )}
      {zoomImg && (
        <div className="msg-img-zoom" onClick={() => setZoomImg(null)}>
          <img src={zoomImg} alt="图片预览" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  )
}

// [打字机性能] 历史消息 memo 化：streaming 每 chunk 推进 → MessageList 整体 re-render，
//   但历史 msg 引用不变（store 只在 complete 追加）→ memo 短路跳过其函数体/markdown DOM diff，
//   只重渲「正在流式的最后一块」。props 引用稳定前提：onDelete=App useCallback、onRunHtml=openHtmlPreview useCallback。
const MemoMessage = memo(Message)

export function MessageList({ messages, streaming, onDelete, conversationId, scrollSignal, thinking, onNearBottomChange }: MessageListProps) {
  // F10 · 消息 row key 并入 conversationId（partial 压缩/裁剪后旋转）→ 触发整列表 remount
  //   useCallback 稳定引用（flatRows useMemo 依赖它 —— 每 render 新函数会让 flatRows 每 chunk 全量重建）
  const rowKey = useCallback((id: string) => (conversationId ? `${conversationId}:${id}` : id), [conversationId])
  const streamWrapRef = useRef<HTMLDivElement>(null)
  const lastMsgId = messages[messages.length - 1]?.id
  // HTML 代码块「运行」→ 右栏覆盖预览（sandbox iframe 运行结果 · 中间对话不受影响）
  //   useCallback 稳定引用：Message 组件已 React.memo —— onRunHtml 引用必须稳定，否则每 chunk 全量击穿
  const openHtmlPreview = useCallback((code: string) => {
    usePreviewStore.getState().open({ kind: 'html', title: 'HTML 预览', code })
  }, [])
  // [bug-101] 流式思考块收起：按块 assistantMessageId 记收起态（此前恒展开 + 无 onClick 无法收起）
  const [collapsedStreamReasoning, setCollapsedStreamReasoning] = useState<Record<string, boolean>>({})
  // message.error → 对话流错误卡：展平跨会话错误按 flow 键锚定（当前视图单会话；无 flow 兜底 global）
  const allApiErrors = useChatStore((s) => s.apiErrors)
  const apiErrorMap = useMemo(() => {
    const map = new Map<string, ApiFlowError>()
    for (const list of Object.values(allApiErrors)) {
      for (const e of list) {
        const key = e.userMessageId ?? e.assistantMessageId ?? 'global'
        if (!map.has(key)) map.set(key, e)
      }
    }
    return map
  }, [allApiErrors])

  // 滚动跟随策略：
  //  - 新增消息（lastMsgId 变化，含发送 user 消息 / 助手回复落库）→ 无条件滚到底（覆盖「看历史时输入」场景）
  //  - 流式增量（同一条助手消息 streaming.content 变化）→ 仅当用户已贴近底部才跟随（看历史时不被拽走）
  //  - 用户向上滚查历史（距离底部超过阈值）→ 流式停止跟随，直到再次拉到底部
  const STICKY_BOTTOM_THRESHOLD = 60 // 距底部 < 60px 视为「贴近底部」
  const scrollElRef = useRef<HTMLElement | null>(null)
  const nearBottomRef = useRef(true) // 初始贴近底部（新会话从底部开始）
  // 贴底状态回调走 ref（effect 依赖 [messages.length] 重绑时避免闭包过期）
  const onNearBottomChangeRef = useRef(onNearBottomChange)
  onNearBottomChangeRef.current = onNearBottomChange
  const stickBottom = () => {
    const el = scrollElRef.current
    if (el) el.scrollTop = el.scrollHeight
  }
  // 记录滚动位置：用户上滚查历史 → nearBottom=false；拉到底部 → true（含外部滚动，如浏览器）
  //  [窗口化] 触顶（scrollTop ≤ 24）且仍有更早历史 → 增量扩展可见窗口（顶部插入内容的高度补偿在渲染后 effect）
  const onScroll = () => {
    const el = scrollElRef.current
    if (!el) return
    nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight <= STICKY_BOTTOM_THRESHOLD
    onNearBottomChangeRef.current?.(nearBottomRef.current)
    if (el.scrollTop <= 24 && flatRowsLenRef.current > visCountRef.current) {
      loadPrevScrollHRef.current = el.scrollHeight
      setVisCount((v) => Math.min(v + WINDOW_STEP, flatRowsLenRef.current))
    }
  }
  // 绑定滚动容器：空态（messages 空 且 无流式）时组件 return null → streamWrapRef 无 DOM，
  //   故容器引用与监听在「消息数或流式出现」后重绑（首条消息/首个流式块出现时 streamWrapRef 才有效）
  useEffect(() => {
    const el = streamWrapRef.current?.parentElement as HTMLElement | null
    scrollElRef.current = el
    el?.addEventListener('scroll', onScroll, { passive: true })
    return () => el?.removeEventListener('scroll', onScroll)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages.length, streaming?.length])
  // 新增消息：用户发送（user 角色）→ 无条件跳底部（用户要开始新回复）；助手落库（assistant）→ 仅贴近底部时跟随（看历史不拽）
  useEffect(() => {
    const last = messages[messages.length - 1]
    if (last?.role === 'user') {
      stickBottom()
      nearBottomRef.current = true
    } else if (nearBottomRef.current) {
      stickBottom()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastMsgId])
  // 流式增量 → 无条件滚底（AI 回复期间用户应默认在底部看最新内容；块级流监听最后一块对象
  //   —— 正文推进 + 思考流式展开都算流尖；用户上滚看历史时也被拽回底部，对齐「AI 回复强制跟底」）
  const streamingTail = streaming && streaming.length > 0 ? streaming[streaming.length - 1] : undefined
  useEffect(() => {
    // 流式推进：仅用户贴底时跟随滚底；上滚看历史不被拽回（「回到底部」按钮负责拉回）——
    //   否则 AI/cron 回复流式时反复滚回底部，用户上滚看历史 + 回到底部按钮永远不出现
    if (nearBottomRef.current) stickBottom()
  }, [streamingTail])
  // 外部滚底信号（权限卡片出现等 App 层事件）→ 强制滚底
  useEffect(() => {
    if (scrollSignal !== undefined) {
      stickBottom()
      nearBottomRef.current = true
    }
  }, [scrollSignal])
  // 按 userMessageId 分组渲染（消息链锚定）：每组 = 一个 flow（user 消息 + 其 assistant/工具流）。
  //   排队场景 user2/AI回复2 独立 group → 顺序正确（对齐 GET /messages 后端出站链）。
  //   userMessageId 缺失（旧数据/流式前）fallback：user 用自身 id，stream 块用 assistantMessageId。
  //   ⚠ 必须置于所有 hooks 之后、条件 return 之前（React 19 hooks 规则：hooks 前不得条件 return，
  //   否则 messages 空↔非空时 hooks 数量变化 → "Rendered more hooks than during the previous render" 白屏）
  const groups = useMemo(() => {
    const arr: { key: string; items: ({ kind: 'msg'; m: ChatMessageDto } | { kind: 'blk'; b: StreamBlock; i: number })[] }[] = []
    const order = new Map<string, number>()
    const push = (key: string, item: (typeof arr)[number]['items'][number]) => {
      let idx = order.get(key)
      if (idx === undefined) { idx = arr.length; order.set(key, idx); arr.push({ key, items: [] }) }
      arr[idx].items.push(item)
    }
    for (const m of messages) {
      if (m.isMeta || m.role === 'tool') continue
      push(m.userMessageId ?? m.id, { kind: 'msg', m })
    }
    // streaming 块归属：用【冻结】的块 userMessageId（首 chunk 建立时确定，对应后端 DB 落库逐条推进
    //   的「位置」语义 —— 用户1 任务轮归用户1、排队 append 后的轮归排队）。冻结保证不被排队 append
    //   后到达的 chunk 覆盖。缺失（旧块/未带）回落 assistantMessageId 独立流。
    for (const [i, b] of (streaming ?? []).entries()) {
      const key = b.userMessageId ?? b.assistantMessageId ?? 'stream'
      push(key, { kind: 'blk', b, i })
    }
    return arr
  }, [messages, streaming])
  // ---- [窗口化渲染] 长会话防卡：默认只渲染尾部 WINDOW_MAX 行（消息 + 流式块 + 组尾错误卡），
  //      上拉到顶自动增量加载更早 WINDOW_STEP 行。渲染行数上限恒定 → 打开长会话/流式推进不随历史量卡死。----
  const WINDOW_MAX = 150 // 初始可见渲染行数
  const WINDOW_STEP = 100 // 触顶单次增量加载行数
  // 线性渲染行（保时间序：组内顺序即全局消息序；组尾 err 卡片跟随其组）供窗口截取
  const flatRows = useMemo(() => {
    const rows: (
      | { key: string; kind: 'msg'; m: ChatMessageDto }
      | { key: string; kind: 'blk'; b: StreamBlock; i: number }
      | { key: string; kind: 'err'; err: ApiFlowError }
    )[] = []
    for (const g of groups) {
      for (const it of g.items) {
        rows.push(it.kind === 'msg'
          ? { key: rowKey(it.m.id), kind: 'msg', m: it.m }
          : { key: it.b.assistantMessageId ?? `blk:${it.i}`, kind: 'blk', b: it.b, i: it.i })
      }
      const gErr = apiErrorMap.get(g.key)
      if (gErr) rows.push({ key: `err:${g.key}`, kind: 'err', err: gErr })
    }
    return rows
  }, [groups, apiErrorMap, rowKey])
  const [visCount, setVisCount] = useState(WINDOW_MAX)
  // 会话内容首条 id 变化（切换会话/清空历史）→ 窗口重置为尾部 WINDOW_MAX；同会话 F5 重拉首 id 不变 → 保留展开窗口
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    setVisCount(WINDOW_MAX)
    // 切会话默认滚到【最底部 = 最新回复】，不沿用上一会话滚动位置/贴底态（否则上拉过旧会话后
    //   切到 assistant 结尾的新会话会停在顶部）；等容器绑 + 内容渲染后再滚（setTimeout 0）
    nearBottomRef.current = true
    onNearBottomChangeRef.current?.(true)
    const t = window.setTimeout(() => stickBottom(), 0)
    return () => window.clearTimeout(t)
  }, [messages[0]?.id])
  const hasMoreOlder = flatRows.length > visCount
  const visible = hasMoreOlder ? flatRows.slice(flatRows.length - visCount) : flatRows
  // onScroll 在滚动容器重绑时闭包捕获旧值 → 经 ref 读最新（窗口扩展后触顶监听不停摆）
  const flatRowsLenRef = useRef(flatRows.length)
  flatRowsLenRef.current = flatRows.length
  const visCountRef = useRef(visCount)
  visCountRef.current = visCount
  const loadPrevScrollHRef = useRef(0)
  // 窗口扩展（顶部插入更早历史）后补偿 scrollTop（内容增高差）→ 阅读位置不跳动（loadPrev 置位的那次渲染后执行）
  useEffect(() => {
    if (!loadPrevScrollHRef.current) return
    const el = scrollElRef.current
    if (el) el.scrollTop += el.scrollHeight - loadPrevScrollHRef.current
    loadPrevScrollHRef.current = 0
  })
  // 空态（置于所有 hooks 之后 · React 19 hooks 规则：hooks 前不得条件 return）
  if (messages.length === 0 && !streaming) {
    return null
  }
  return (
    <>
    <div className="stream-inner" ref={streamWrapRef}>
      {/* 对话裁剪 hover 按钮样式（组件内联 · 对齐 CommandPalette 先例）；确认弹窗已并入 DialogOpsModal 裁剪 tab */}
      <style>{`
        .msg-hover-actions {
          position: absolute;
          top: 4px;
          right: 8px;
          display: flex;
          gap: 6px;
          z-index: 1;
        }
        /* 用户消息：顶部恒定留白（28px）供删除按钮 absolute 右上——按钮不占文档流，
           不因 hover 改变布局（不抖动），且气泡在留白下方不被覆盖 */
        .msg.user { padding-top: 28px; }
        /* 用户消息图片附件缩略图（乐观追加 imageData · base64 直传图） */
        .user-attach-img {
          max-width: 220px;
          max-height: 220px;
          border-radius: 8px;
          object-fit: cover;
          display: block;
          margin-bottom: 6px;
        }
        /* 用户消息文件附件（PDF/Word/视频/音频）卡片：白底 + 阴影 + hover 阴影加深 · 点击预览 */
        .user-attach-file {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          max-width: 320px;
          padding: 7px 12px;
          background: #fff;
          border: 1px solid rgba(0,0,0,.08);
          border-radius: 10px;
          font-size: 12.5px;
          color: var(--ink);
          cursor: pointer;
          font-family: inherit;
          line-height: 1.5;
          text-align: left;
          box-shadow: 0 1px 3px rgba(20,20,19,.08), 0 1px 2px rgba(20,20,19,.04);
          transition: box-shadow .15s ease;
        }
        .user-attach-file:hover { box-shadow: 0 6px 16px rgba(20,20,19,.14), 0 2px 6px rgba(20,20,19,.08); }
        .user-attach-file .uaf-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        /* 类型徽标：PDF 红 / Word 蓝 / Excel 绿 文字徽标；视频/音频/文件 SVG 图标 */
        .uaf-badge {
          flex-shrink: 0;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          min-width: 22px;
          height: 17px;
          padding: 0 4px;
          border-radius: 4px;
          font-size: 10px;
          font-weight: 700;
          color: #fff;
        }
        .uaf-badge.pdf { background: #FA5151; }
        .uaf-badge.word { background: #2B579A; }
        .uaf-badge.excel { background: #217346; }
        .uaf-badge.icon { background: transparent; color: #666; min-width: 16px; padding: 0; }
        .uaf-badge.icon svg { width: 15px; height: 15px; display: block; }
        .uaf-badge.icon.video { color: #E4572E; }
        .uaf-badge.icon.audio { color: #6B5BCE; }
        /* CRON 定时任务触发系统通知（scheduled_task_fire · 对齐 CC SystemTextMessage「❋ 任务执行中」） */
        .msg.system-notice { justify-content: flex-start; padding: 4px 0; }
        .system-notice {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          font-size: 12.5px;
          color: var(--ink-muted);
          background: rgba(0,0,0,.045);
          border: 1px solid rgba(0,0,0,.06);
          border-radius: 6px;
          padding: 5px 10px;
        }
        .system-notice .sn-icon { color: var(--accent); font-size: 13px; line-height: 1; }
        /* 覆盖 globals.css 的 .msg .delete-btn 绝对定位，改由 flex 容器排布 */
        .msg .msg-hover-actions .delete-btn { position: static; }
        /* 删除按钮醒目：橙边框/文字 + 淡橙底（危险删除语义 · 对齐项目 accent #FF7A3D） */
        .msg .msg-hover-actions .delete-btn {
          border-color: #FF7A3D;
          color: #FF7A3D;
          background: #FFF3EB;
        }
        .msg .msg-hover-actions .delete-btn:hover { background: #FF7A3D; color: #fff; }
      `}</style>
      {/* F29 · 元消息（续写提示 / budget nudge）isMeta=true 不展示；role=tool 工具结果消息已含于
          assistant.toolCalls[].result（DB 重拉后），独立渲染会重复噪音 → 一并过滤 */}
      {/* 按 userMessageId 分组渲染（消息链锚定 · 对齐 GET /messages 后端出站链）：
          每组 = 一个 flow（user 消息 + 其 assistant/工具流），工具轮挂主气泡下；排队场景顺序正确 */}
      {visible.map((row) => {
        if (row.kind === 'msg') {
          return <MemoMessage key={row.key} msg={row.m} onDelete={onDelete} onRunHtml={openHtmlPreview} />
        }
        if (row.kind === 'err') {
          return <ApiErrorCard key={row.key} err={row.err} />
        }
        const b = row.b
        const blkIdx = row.i
        return (
          <div className={`msg assistant${blkIdx === (streaming?.length ?? 0) - 1 ? ' streaming' : ''}`} key={row.key}>
            <div className="avatar">N</div>
            <div className="body">
              <div className="author">nexus</div>
              {cleanReasoning(b.reasoning) && (() => {
                // [bug-101] 流式思考块收起：按块 assistantMessageId 记收起态（此前恒展开 + div 无 onClick）
                const blkId = b.assistantMessageId ?? String(blkIdx)
                const collapsed = !!collapsedStreamReasoning[blkId]
                return (
                  <div className={`thinking-wrap${collapsed ? '' : ' open'}`}>
                    <button className="thinking-toggle" onClick={() => setCollapsedStreamReasoning((prev) => ({ ...prev, [blkId]: !collapsed }))}>
                      <svg viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
                      <span>正在思考…</span>
                    </button>
                    {!collapsed && <div className="thinking-body">{cleanReasoning(b.reasoning)}</div>}
                  </div>
                )
              })()}
              {b.content && <MarkdownText text={b.content} streaming className="content md" onRunHtml={openHtmlPreview} />}
              {b.toolCalls.length > 0 && b.toolCalls.map((t, j) => <ToolCard key={t.id ?? j} tool={t} matchedRule={null} />)}
            </div>
          </div>
        )
      })}
      {/* 无 flow 锚定的错误（userMessageId/assistantMessageId 均缺失）→ 兜底渲染在末尾 */}
      {(() => { const err = apiErrorMap.get('global'); return err ? <ApiErrorCard key="err-global" err={err} /> : null })()}
      {/* 回到最底部（离底时显示 · 对齐 deepseek-harness ChatView toBottom） */}
      {/* 思考中占位：turn 运行中且无流式块（thinking/重试等待期）→ 发送后立即显示 nexus 思考中，消除空白间隙 */}
      {thinking && (
        <div className="msg assistant streaming">
          <div className="avatar">N</div>
          <div className="body">
            <div className="author">nexus</div>
            <div className="thinking-wrap open">
              <div className="thinking-toggle">
                <span>思考中…</span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
    </>
  )
}
