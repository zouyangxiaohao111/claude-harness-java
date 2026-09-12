import { memo, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { MarkdownText } from '@/markdown/MarkdownText'
import type { ChatMessageDto } from '@/api/types'
import { subagentColor } from '@/api/types'
import { compactNumber } from '@/utils/format'
import { extractAtRefs } from '@/utils/atRefs'

// @引用 token（@"引号路径" 或 @路径 · 遇空白/中文标点/右括号/引号结束）
const AT_MENTION_RE = /@"[^"]+"|@[^\s，。、；：（()）“”"'#]+/g

/** [Phase3] 用户正文 → @token 渲染为「内联可点 chip」（阴影），其余文本原样；换行由 .user-ref-text(pre-wrap) 保真。 */
function renderUserRefText(text: string, onOpen?: (path: string) => void): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  let k = 0
  for (const m of text.matchAll(AT_MENTION_RE)) {
    const idx = m.index ?? 0
    if (idx > last) out.push(text.slice(last, idx))
    const raw = m[0]
    // 点击预览用「相对路径」：去前导 @ 与 @"引号"，并去掉可选 #L 行区间
    const clean = (raw.startsWith('@"') ? raw.slice(2, -1) : raw.slice(1)).split('#L')[0]
    out.push(
      <button key={`r${k++}`} type="button" className="inline-ref-chip" title="点击预览引用文件" onClick={() => onOpen?.(clean)}>
        {m[0]}
      </button>,
    )
    last = idx + raw.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}
import { parseAnsiLines, type AnsiLine } from '@/utils/ansi'
import { useSubagentStore } from '@/stores/subagentStore'
import { useChatStore, selectStreamIds, selectStreamBlock } from '@/stores/chatStore'
import type { ApiFlowError } from '@/stores/chatStore'
import { tasksApi } from '@/api/tasks'
import { openStandalone, previewKindOfAttachment } from '@/utils/standalonePreview'

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
  /** 当前会话 id：父级订阅流式【稳定行序】（selectStreamIds → streamOrder）；流式块内容订阅下沉到每行
   *  StreamBlockRow（selectStreamBlock → streams[sid] 块对象），不再由 App 顶层订阅整条 streams 引用 */
  sessionId: string
  messages: ChatMessageDto[]
  onDelete: (messageId: string) => void
  /** 会话当前 conversationId（partial 压缩/裁剪后旋转）：并入消息 row key，触发整列表 remount */
  conversationId?: string | null
  /** 外部滚底信号（权限卡片出现等 App 层事件）：值变化时强制滚到底部 */
  scrollSignal?: number
  /** turn 运行中且无流式块（thinking/重试等待期）→ 消息流末尾显示「nexus 思考中…」占位，消除发送后空白间隙 */
  thinking?: boolean
  /** [Phase3] 点击 @引用文件卡片 → 打开文件预览（App 用绑定项目 + path 打开 FileViewModal） */
  onOpenRefFile?: (path: string) => void
  /** 滚动贴底状态回调（「回到底部」按钮由 Composer 工具栏渲染 · 离底时 App 传 showToBottom=true） */
  onNearBottomChange?: (atBottom: boolean) => void
  /** [window-paging] 顶部「加载更早」（hasMore 时显示）：App 取窗口首条 beforeMessageId 拉前页 prependMessages */
  onLoadOlder?: (sessionId: string) => Promise<void> | void
}

/** 工具调用卡片 · FNT-TC-01：消息级 matchedRule（后端 ChatMessageDto 顶层出站）→ 显示「已自动批准（规则X）」徽标；无数据静默 */
function ToolCard({ tool, matchedRule, live = false }: { tool: NonNullable<ChatMessageDto['toolCalls']>[number]; matchedRule: string | null; live?: boolean }) {
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
  // [Fix3 2026-09-09 · 对齐 CC resolvedToolUseIDs] "执行中"只在 live(活跃流式块=turn 仍在飞)成立；
  //   settled 历史里 result==null 且非 error = 孤儿(工具被取消/中断/结果缺失) → 显示"已中断"，不永久转圈。
  //   runningFront(转后台按钮) 仅 live 卡有效 —— settled 孤儿不再触发 tasksApi.list 轮询/不再有陈旧"转后台"
  //   （同时消除"任务已结束仍可点转后台→后端 400 静默"的陈旧卡）。
  const interrupted = !hasResult && !tool.isError && !live
  const runningFront = live && !hasResult && !tool.isError
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
        <span className={`status ${tool.isError ? 'error' : hasResult ? 'done' : interrupted ? 'interrupted' : 'running'}`}>
          {tool.isError ? '失败' : hasResult ? '已完成' : interrupted ? '已中断' : '执行中'}
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

/** [P2-13] compact 摘要专用渲染 · 对齐 CC {@code components/CompactSummary.tsx:74-104}（auto-compact 默认分支）
 *  + {@code components/Message.tsx:140-142}（{@code case 'user': if (message.isCompactSummary) return <CompactSummary/>}）。
 *
 *  <p><b>WHY 不能当普通 user 气泡</b>：compact 摘要（role=user / author=system / isCompactSummary=true）
 *  是<b>系统产物</b>不是用户输入 —— 走 user 气泡会以用户口吻展示整段摘要正文，且被误读为「用户说过」。
 *
 *  <p><b>CC 语义映射</b>：CC 在 prompt 模式（= 本仓对话区）只显示 {@code ⏺ Conversation summarized to
 *  free up context} 标题行、正文靠 ctrl+o 进 transcript 模式才展开；本仓无 transcript 全局开关
 *  （轨迹 tab 承担 transcript 角色），故把「展开」下放为标题行内的折叠开关：默认收起 = CC prompt 语义，
 *  展开 = CC transcript 语义（正文可读）。
 *  注：CC 另一分支（{@code summarizeMetadata} 的「Summarized conversation」）本仓不可达 ——
 *  summarizeMetadata 由后端放在 {@code structuredOutput} 内（PartialCompactConversation.buildSummaryMessage），
 *  前端 DTO 不出站该字段；故只实现默认分支（保持数据可得性诚实，不臆造计数/方向）。 */
function CompactSummaryCard({ msg }: { msg: ChatMessageDto }) {
  const [expanded, setExpanded] = useState(false)
  const text = msg.content ?? ''
  return (
    <div className="msg compact-summary">
      <div className="cs-head">
        <span className="cs-dot" aria-hidden>⏺</span>
        <span className="cs-title">Conversation summarized to free up context</span>
        {text.trim() !== '' && (
          <button type="button" className="cs-toggle" onClick={() => setExpanded((v) => !v)} aria-expanded={expanded}>
            {expanded ? '收起摘要' : '查看摘要'}
          </button>
        )}
      </div>
      {expanded && text.trim() !== '' && (
        <div className="cs-body">
          <MarkdownText text={text} className="cs-text md" />
        </div>
      )}
    </div>
  )
}

/** [P2-15] Stop hook 摘要行 · 对齐 CC {@code SystemTextMessage.tsx:151-254 StopHookSummaryMessage}
 *  （默认分支：{@code ⏺ Ran N stop hooks} + 逐条错误 + 阻止继续原因）。
 *
 *  <p><b>元数据型内容</b>（hook 计数 / 错误列表 / 阻止原因），不是对话正文 —— 独立摘要行渲染，
 *  绝不当普通 user/assistant 气泡。
 *
 *  <p>可见性门与 CC 一致（{@code SystemTextMessage.tsx:173-179}：{@code if (hookErrors.length === 0
 *  && !preventedContinuation && !message.hookLabel) ... return null}）：无错误、未阻止继续、
 *  且非 label 摘要 → 不渲染（Stop/SubagentStop 生产形态 hookLabel 恒 null →
 *  即「只有出错或被阻止时才可见」）。 */
function StopHookSummaryRow({ payload }: { payload: NonNullable<ChatMessageDto['stopHookSummary']> }) {
  const errors = payload.hookErrors ?? []
  const hasLabel = payload.hookLabel != null && payload.hookLabel !== ''
  if (errors.length === 0 && !payload.preventedContinuation && !hasLabel) return null
  const label = hasLabel ? payload.hookLabel : 'stop'
  return (
    <div className="msg stop-hook-summary">
      <div className="shs-head">
        <span className="shs-dot" aria-hidden>⏺</span>
        <span className="shs-title">
          Ran <b>{payload.hookCount}</b> {label} {payload.hookCount === 1 ? 'hook' : 'hooks'}
        </span>
      </div>
      {payload.preventedContinuation && payload.stopReason && (
        <div className="shs-line"><span className="shs-branch" aria-hidden>⎿</span>{payload.stopReason}</div>
      )}
      {errors.map((err, i) => (
        <div key={i} className="shs-line shs-error">
          <span className="shs-branch" aria-hidden>⎿</span>{label} hook error: {err}
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

function Message({ msg, onDelete, onRunHtml, onOpenRefFile }: { msg: ChatMessageDto; onDelete: (id: string) => void; onRunHtml?: (code: string) => void; onOpenRefFile?: (path: string) => void }) {
  const isUser = msg.role === 'user'
  // F25 · model_fallback_warning：role=system + subtype='informational' 的消息按「模型降级」提示渲染
  const isFallback = msg.role === 'system' && msg.subtype === 'informational'
  // [P2-15] Stop hook 摘要行（/topic/tasks 实时插入 · 元数据型展示行）：独立摘要行渲染，
  //   不进 user/assistant 气泡分支（CC SystemTextMessage.tsx:121-127 专用分派）
  const isStopHookSummary = msg.subtype === 'stop_hook_summary'
  // [P2-13] compact 摘要 user 消息（isCompactSummary=true）→ CC CompactSummary 专用渲染，
  //   不是普通用户气泡（CC Message.tsx:140-142）。kept 段为空时后端另打
  //   isVisibleInTranscriptOnly=true（对话区不展示，由 groups 过滤在上游 continue 掉）。
  const isCompactSummary = msg.role === 'user' && msg.isCompactSummary === true
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
  // [toolsum-display] tool_use_summary 展示行（作者 attachment + subtype=tool_use_summary · 后端落库 /
  //   /topic/tasks 实时 · 不进模型上下文）→ 渲染独立置灰窄行，非用户气泡/不进输入历史
  const isToolUseSummary = msg.author === 'attachment' && msg.subtype === 'tool_use_summary'
  // 边界标签文案（中文）：snip 有 removedUuids 时说明移除条数
  //   [死展示点清理] 原先 compact 分支还拼过「· 122k→42k」后缀 + 悬停显示 compactMetadata.summary，
  //   但后端 `CompactBoundaryMessage.toCompactMetadataMap` **从不 put** postTokens/summary 这两个 key
  //   （CC 的 compactMetadata 本身也没有它们）→ 该后缀与 tooltip 结构上永不出现。已删。
  //   压缩摘要正文改由轨迹视图经「边界↔摘要配对」读取（`utils/compactPairing.ts`）。
  const boundaryLabel = (() => {
    if (isCompactBoundary) return '已压缩 · 对话历史已总结'
    if (isMicrocompactBoundary) return '已清理 · 旧工具输出已清除'
    if (isSnipBoundary) {
      const n = msg.snipMetadata?.removedUuids?.length ?? 0
      return `已裁剪 · 历史消息已由 Snip 移除${n > 0 ? `（${n} 条）` : ''}`
    }
    return ''
  })()
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
          <span className="boundary-label">{boundaryLabel}</span>
        </div>
      </div>
    )
  }
  // tool_use_summary 摘要行：居中弱化窄行（⚒ 图标 + 单行文本 · title 全文）——无删除、不进输入历史
  if (isToolUseSummary) {
    return (
      <div className="msg tool-use-summary">
        <span className="tus-icon">⚒</span>
        <span className="tus-text" title={msg.content ?? ''}>{msg.content}</span>
      </div>
    )
  }
  // [P2-15] Stop hook 摘要行（元数据型）→ 独立摘要行（可见性门在组件内，同 CC）
  if (isStopHookSummary && msg.stopHookSummary) {
    return <StopHookSummaryRow payload={msg.stopHookSummary} />
  }
  // [P2-13] compact 摘要 → CC CompactSummary 专用渲染（不是普通用户气泡）
  if (isCompactSummary) {
    return <CompactSummaryCard msg={msg} />
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
            {/* [Phase3 @引用] 正文里的 @token 直接渲染为内联可点 chip（单份 · 不再额外加引用卡片行）。
                无 @引用时仍走原有 ContentGuard markdown 渲染。 */}
            {extractAtRefs(msg.content).length > 0 ? (
              <div className="user-text user-ref-text">
                {renderUserRefText(msg.content ?? '', onOpenRefFile)}
              </div>
            ) : (
              <ContentGuard text={msg.content ?? ''} className="user-text md" onRunHtml={onRunHtml} />
            )}
            {msg.userAttachments?.filter((a) => a.type !== 'image' && a.filename).map((a, i) => (
              <button key={i} className="user-attach-file" title={`点击预览：${a.filename}`} onClick={() => openStandalone({ type: previewKindOfAttachment(a), title: a.filename ?? '预览', item: a })}>
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

// [流式性能 2026-09-09] 流式块行独立订阅（对齐 deepseek-harness ChatNodeSeat：只订自己 key 的节点）。
//   父级 MessageList 只订 streamOrder（稳定 id 序）构建「顺序壳」；本行通过 selectStreamBlock(sid, blockId)
//   订阅自己的块对象 —— append 只换该块对象引用 → 仅本行重渲，历史行/其余流式块行/父级全部被 bail。
//   渲染行为与旧内联块一致：reasoning 默认展开可收起（本地态，随行卸载丢弃）、MarkdownText streaming、
//   toolCalls 卡片。class 未动。
const StreamBlockRow = memo(function StreamBlockRow({ sessionId, blockId, isStreamingTail, onRunHtml }: {
  sessionId: string
  blockId: string
  isStreamingTail: boolean
  onRunHtml: (code: string) => void
}) {
  // 只订自身块对象：append 后该块是新对象 → 重渲；其它块 append（数组换新但本块对象引用不变）→ bail
  const b = useChatStore(selectStreamBlock(sessionId, blockId))
  // [bug-101] 流式思考块收起：行内本地态（此前父级 Record 存收起态，整表重渲；行内自持语义不变）
  const [collapsed, setCollapsed] = useState(false)
  if (!b) return null
  return (
    <div className={`msg assistant${isStreamingTail ? ' streaming' : ''}`}>
      <div className="avatar">N</div>
      <div className="body">
        <div className="author">nexus</div>
        {cleanReasoning(b.reasoning) && (
          <div className={`thinking-wrap${collapsed ? '' : ' open'}`}>
            <button className="thinking-toggle" onClick={() => setCollapsed((v) => !v)}>
              <svg viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
              <span>正在思考…</span>
            </button>
            {!collapsed && <div className="thinking-body">{cleanReasoning(b.reasoning)}</div>}
          </div>
        )}
        {b.content && <MarkdownText text={b.content} streaming className="content md" onRunHtml={onRunHtml} streamKey={`${sessionId}:${blockId}`} />}
        {b.toolCalls.length > 0 && b.toolCalls.map((t, j) => <ToolCard key={t.id ?? j} tool={t} matchedRule={null} live />)}
      </div>
    </div>
  )
})

export function MessageList({ messages, sessionId, onDelete, conversationId, scrollSignal, thinking, onNearBottomChange, onOpenRefFile, onLoadOlder }: MessageListProps) {
  // F10 · 消息 row key 并入 conversationId（partial 压缩/裁剪后旋转）→ 触发整列表 remount
  //   useCallback 稳定引用（flatRows useMemo 依赖它 —— 每 render 新函数会让 flatRows 每 chunk 全量重建）
  const rowKey = useCallback((id: string) => (conversationId ? `${conversationId}:${id}` : id), [conversationId])
  // [流式性能] 稳定行序订阅：引用只在块增/删/finalize/clear 变化；content 追加不触碰 → 父级不被打字机逐帧重渲
  const streamIds = useChatStore(selectStreamIds(sessionId))
  // [chat-switch-stream-align] 当前渲染会话 ref（store.subscribe 回调需读最新，闭包不捕获过期值）
  const sessionIdRef = useRef(sessionId)
  sessionIdRef.current = sessionId
  /** 本会话流式活动节拍缓存（-1 = 尚未见过首帧；见 streamTicks 说明）。 */
  const lastStreamTickRef = useRef(-1)
  const streamWrapRef = useRef<HTMLDivElement>(null)
  const lastMsgId = messages[messages.length - 1]?.id
  // HTML 代码块「运行」→ 独立窗口预览（sandbox iframe 运行结果 · 不占右栏、不打断对话）
  //   useCallback 稳定引用：Message 组件已 React.memo —— onRunHtml 引用必须稳定，否则每 chunk 全量击穿
  const openHtmlPreview = useCallback((code: string) => {
    openStandalone({ type: 'html', title: 'HTML 运行预览', code })
  }, [])
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
  // [window-paging] 移除「触顶自动翻页」（原渲染层窗口扩展）——更早历史改为顶部「加载更早」显式按钮
  const onScroll = () => {
    const el = scrollElRef.current
    if (!el) return
    nearBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight <= STICKY_BOTTOM_THRESHOLD
    onNearBottomChangeRef.current?.(nearBottomRef.current)
  }
  // 绑定滚动容器：空态（messages 空 且 无流式）时组件 return null → streamWrapRef 无 DOM，
  //   故容器引用与监听在「消息数或流式出现」后重绑（首条消息/首个流式块出现时 streamWrapRef 才有效）
  useEffect(() => {
    const el = streamWrapRef.current?.parentElement as HTMLElement | null
    scrollElRef.current = el
    el?.addEventListener('scroll', onScroll, { passive: true })
    return () => el?.removeEventListener('scroll', onScroll)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages.length, streamIds.length])
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
  // [流式性能 2026-09-09] 流式滚底改为 store.subscribe（不触发 React 重渲）：打字机 content 逐帧 append 时
  //   父级不再被重渲（只订 streamOrder 稳定引用），但滚动需跟随流尖 —— 订阅 store 原语，贴底时直接写 scrollTop。
  // [chat-switch-stream-align] 按会话隔离 + rAF 后写：
  //   · 只当「当前渲染会话」的流式活动节拍（streamTicks[sid]）推进才滚 —— 修复「A 会话仍在打字、切到 B 查看
  //     时，A 每帧 append 触发 subscribe → 把 B 的滚动容器拉到旧底」的抖动/错乱（对齐 deepseek 每 Session 独立
  //     follow，互不劫持；切换前会话也照常收到其 chunk，但滚动只跟当前显示会话的流尖）。
  //   · rAF 后写：subscribe 回调在 store 通知期同步执行，此时 scrollHeight 是旧值 → 包 rAF 待 React commit 后
  //     读新高度再写，避免反复写回旧底。
  //   语义保留：仅 nearBottom 时跟随；上滚看历史不被拽回。
  useEffect(() => {
    lastStreamTickRef.current = -1
    let raf = 0
    const unsub = useChatStore.subscribe(() => {
      const sid = sessionIdRef.current
      if (!sid || !nearBottomRef.current) return
      const tick = useChatStore.getState().streamTicks[sid] ?? 0
      if (tick === lastStreamTickRef.current) return
      lastStreamTickRef.current = tick
      if (raf) cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => { raf = 0; stickBottom() })
    })
    return () => { unsub(); if (raf) cancelAnimationFrame(raf) }
  }, [])
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
    // [流式性能] 只从快照读块归属(userMessageId)/数量做行序 —— 不把块对象存进父级(内容订阅下沉到行)。
    //   streamOrder 变(块增删)才重算;content 逐帧 append 时 useChatStore.getState() 读到最新 userMessageId,
    //   但本 memo 不会因 content 重跑(依赖只有 messages/streamIds/sessionId)。
    const live = sessionId ? (useChatStore.getState().streams[sessionId] ?? []) : []
    const arr: { key: string; items: ({ kind: 'msg'; m: ChatMessageDto } | { kind: 'blk'; sid: string; blockId: string })[] }[] = []
    const order = new Map<string, number>()
    const push = (key: string, item: (typeof arr)[number]['items'][number]) => {
      let idx = order.get(key)
      if (idx === undefined) { idx = arr.length; order.set(key, idx); arr.push({ key, items: [] }) }
      arr[idx].items.push(item)
    }
    for (const m of messages) {
      if (m.role === 'tool') continue
      // [transcript-only] 仅 transcript 可见的 user 消息不进普通对话流（CC original:
      //   utils/messages.ts:5098-5115 shouldShowUserMessage —— 首行 `if (message.type !== 'user') return true`
      //   使该判据只作用于 user 消息；调用点 components/Messages.tsx:559 主列表过滤）。
      //   TraceView 不过滤（轨迹 tab 需要展示）。
      //   位置：与下方 isMeta 分支是两条互不交叉的 continue（本分支无任何豁免），先后顺序不影响结果集；
      //   保持在 isMeta 之前 = 维持既有「先按显式标志剔除」的读法，也不会遮蔽紧随其后的 tool_use_summary 放行分支。
      //   compact 摘要（kept 段为空）由后端打此标记（PartialCompactConversation.buildSummaryMessage）。
      if (m.role === 'user' && m.isVisibleInTranscriptOnly === true) continue
      // 放行「实时展示行」（isMeta=true 但属 UI 摘要行）：tool_use_summary 与
      //   [P2-15] stop_hook_summary —— 默认 isMeta 跳过会把它们吞掉（渲染分支见 Message 组件）。
      //   两者都只由 /topic/tasks 实时插入（不落库），isMeta=true 是为了不进「消息计数徽标 /
      //   pivot 候选 / 轨迹」这些「真实对话消息」口径。
      const isLiveDisplayRow = (m.author === 'attachment' && m.subtype === 'tool_use_summary')
        || m.subtype === 'stop_hook_summary'
      if (m.isMeta && !isLiveDisplayRow) continue
      push(m.userMessageId ?? m.id, { kind: 'msg', m })
    }
    // streaming 块归属：用【冻结】的块 userMessageId（首 chunk 建立时确定，对应后端 DB 落库逐条推进
    //   的「位置」语义 —— 用户1 任务轮归用户1、排队 append 后的轮归排队）。冻结保证不被排队 append
    //   后到达的 chunk 覆盖。缺失（旧块/未带）回落 assistantMessageId 独立流。
    //   行条目只带 (sid, blockId) —— 具体内容由每行 StreamBlockRow 自订 selectStreamBlock。
    for (const blockId of streamIds) {
      const b = live.find((x) => x.assistantMessageId === blockId)
      if (!b) continue
      const key = b.userMessageId ?? b.assistantMessageId ?? 'stream'
      push(key, { kind: 'blk', sid: sessionId, blockId })
    }
    return arr
  }, [messages, streamIds, sessionId])
  // ---- [window-paging] 有界历史窗口：渲染「已加载窗口」全量（尾页 50 + 顶部「加载更早」prepend 前页）。
  //     数据天然有界 → 移除渲染层固定尾窗（原 150 尾窗造成「滚到顶看不到更早」）；行级 memo + settled LRU 兜底。
  //     更早历史 = 顶部显式按钮（hasMore 时）→ onLoadOlder（App 拉前页 prependMessages + commit 后滚高补偿）。----
  // 线性渲染行（保时间序：组内顺序即全局消息序；组尾 err 卡片跟随其组）→ 全量渲染已加载窗口
  const flatRows = useMemo(() => {
    const rows: (
      | { key: string; kind: 'msg'; m: ChatMessageDto }
      | { key: string; kind: 'blk'; sid: string; blockId: string }
      | { key: string; kind: 'err'; err: ApiFlowError }
    )[] = []
    for (const g of groups) {
      for (const it of g.items) {
        rows.push(it.kind === 'msg'
          ? { key: rowKey(it.m.id), kind: 'msg', m: it.m }
          : { key: it.blockId, kind: 'blk', sid: it.sid, blockId: it.blockId })
      }
      const gErr = apiErrorMap.get(g.key)
      if (gErr) rows.push({ key: `err:${g.key}`, kind: 'err', err: gErr })
    }
    return rows
  }, [groups, apiErrorMap, rowKey])
  // 【根因·「加载更早」跳底】原依赖是 [messages[0]?.id]，注释称「会话内容首条 id 变化 → 滚到底」。
  //   但 prependMessages 把更早页 unshift 到数组【头部】→ messages[0].id 必然变化 → 本 effect 把
  //   「向上扩展历史」误判成「切换会话」，重置 nearBottom 并 setTimeout(0) 调 stickBottom()
  //   （scrollTop = scrollHeight）。它与 loadOlderClick 的高度补偿 rAF 构成竞态：
  //     · timer 先跑 → 写到底部；随后 rAF 再 `scrollTop += Δ`（已到底被钳）→ 仍在底部；
  //     · rAF 先跑 → 补偿正确；随后 timer 覆盖成 scrollHeight → 仍跳到底部。
  //   两种时序都落到底部 = 用户报的「点加载更早直接跳到底」。这就是「哪一行赢了竞争」：
  //   赢家是 setTimeout 的 stickBottom()（旧 effect:823），因为它写的是绝对底部、而补偿只做相对位移。
  //   修法（非补丁）：reset 的判据从「首条消息 id」（会被 prepend 噪声触发）换成【会话身份】
  //   （sessionId + conversationId）——只有真正切换会话 / partial 压缩·裁剪旋转 conversationId 才重置。
  //   prepend 不改变身份，故不再触发，loadOlderClick 的 rAF 成为唯一写者，锚定生效。
  //   语义保持：切会话/压缩裁剪仍回到最新（滚到底），不沿用上一会话滚动位置。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    nearBottomRef.current = true
    onNearBottomChangeRef.current?.(true)
    const t = window.setTimeout(() => stickBottom(), 0)
    return () => window.clearTimeout(t)
  }, [sessionId, conversationId])
  // [window-paging] hasMore（会话有更早历史）→ 顶部「加载更早」按钮；App onLoadOlder 拉前页 prependMessages。
  const hasMore = useChatStore((s) => (sessionId ? s.hasMore[sessionId] : undefined))
  const [loadingOlder, setLoadingOlder] = useState(false)
  const loadOlderClick = async () => {
    if (!sessionId || loadingOlder) return
    setLoadingOlder(true)
    try {
      // 记录加载前高度 → App prependMessages（同步改 store）后在 commit 后补 scrollTop 防阅读跳
      const prevH = scrollElRef.current?.scrollHeight ?? 0
      await onLoadOlder?.(sessionId)
      if (prevH) requestAnimationFrame(() => {
        const el = scrollElRef.current
        if (el) el.scrollTop += el.scrollHeight - prevH
      })
    } finally {
      setLoadingOlder(false)
    }
  }
  // 空态（置于所有 hooks 之后 · React 19 hooks 规则：hooks 前不得条件 return）
  if (messages.length === 0 && streamIds.length === 0) {
    return null
  }
  // 流式尾块 id（给行打「streaming」尾态 class · 只在块增删时变化）
  const lastStreamId = streamIds.length > 0 ? streamIds[streamIds.length - 1] : undefined
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
        /* tool_use_summary 摘要行：居中弱化窄行（工具批 · 不进模型上下文） */
        .msg.tool-use-summary {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 6px;
          max-width: 640px;
          margin: 2px auto 4px;
          padding: 2px 10px;
          font-size: 11.5px;
          line-height: 1.5;
          color: var(--ink-muted, #888);
          background: var(--surface-2, #f2f2f3);
          border: 1px solid var(--hairline, #e5e5e5);
          border-radius: 999px;
          text-align: center;
          user-select: none;
        }
        .msg.tool-use-summary .tus-icon { flex-shrink: 0; font-size: 11px; opacity: 0.85; }
        .msg.tool-use-summary .tus-text {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        /* [P2-13] compact 摘要卡片：CC CompactSummary 专用渲染（⏺ 标题行 + 折叠正文），
           不是用户气泡 —— 摘要正文默认收起（= CC prompt 模式），展开 = CC transcript 模式 */
        .msg.compact-summary {
          flex-direction: column;
          align-items: flex-start;
          max-width: 720px;
          margin: 6px auto 8px;
          padding: 10px 14px;
          font-size: 12.5px;
          color: var(--ink-muted, #888);
          background: var(--surface-2, #f2f2f3);
          border: 1px solid var(--hairline, #e5e5e5);
          border-radius: 10px;
        }
        .msg.compact-summary .cs-head { display: flex; align-items: center; gap: 8px; width: 100%; }
        .msg.compact-summary .cs-dot { flex-shrink: 0; font-size: 12px; line-height: 1; opacity: 0.9; }
        .msg.compact-summary .cs-title { font-weight: 600; color: var(--ink, #333); }
        .msg.compact-summary .cs-toggle {
          margin-left: auto;
          padding: 1px 8px;
          font-size: 11px;
          font-family: inherit;
          color: var(--ink-subtle, #666);
          background: transparent;
          border: 1px solid var(--hairline, #e5e5e5);
          border-radius: 999px;
          cursor: pointer;
        }
        .msg.compact-summary .cs-toggle:hover { color: var(--ink, #333); background: var(--surface-3, #eaeaea); }
        .msg.compact-summary .cs-body {
          width: 100%;
          margin-top: 8px;
          padding-top: 8px;
          border-top: 1px dashed var(--hairline, #e5e5e5);
          color: var(--ink, #333);
        }
        /* [P2-15] Stop hook 摘要行：元数据型内容（⏺ Ran N stop hooks + 错误行），
           左对齐弱化摘要，与 user/assistant 气泡区分（CC SystemTextMessage 同款语义） */
        .msg.stop-hook-summary {
          flex-direction: column;
          align-items: flex-start;
          margin: 4px 0 6px;
          padding-left: 2px;
          font-size: 12.5px;
          color: var(--ink-muted, #888);
          line-height: 1.6;
        }
        .msg.stop-hook-summary .shs-head { display: flex; align-items: baseline; gap: 6px; }
        .msg.stop-hook-summary .shs-dot { flex-shrink: 0; font-size: 11px; opacity: 0.85; }
        .msg.stop-hook-summary .shs-title b { color: var(--ink, #333); }
        .msg.stop-hook-summary .shs-line { padding-left: 14px; }
        .msg.stop-hook-summary .shs-branch { margin-right: 6px; opacity: 0.6; }
        .msg.stop-hook-summary .shs-error { color: var(--warning, #c9820e); }
      `}</style>
      {/* [window-paging] 顶部「加载更早」（hasMore 时；对齐 deepseek loadOlder 显式按钮，不做滚顶自动翻页） */}
      {hasMore && (
        <button className="ml-load-older" disabled={loadingOlder} onClick={loadOlderClick}>
          {loadingOlder ? '加载中…' : '↑ 加载更早'}
        </button>
      )}
      {/* F29 · 元消息（续写提示 / budget nudge）isMeta=true 不展示；role=tool 工具结果消息已含于
          assistant.toolCalls[].result（DB 重拉后），独立渲染会重复噪音 → 一并过滤 */}
      {/* 按 userMessageId 分组渲染（消息链锚定 · 对齐 GET /messages 后端出站链）：
          每组 = 一个 flow（user 消息 + 其 assistant/工具流），工具轮挂主气泡下；排队场景顺序正确 */}
      {flatRows.map((row) => {
        if (row.kind === 'msg') {
          return <MemoMessage key={row.key} msg={row.m} onDelete={onDelete} onRunHtml={openHtmlPreview} onOpenRefFile={onOpenRefFile} />
        }
        if (row.kind === 'err') {
          return <ApiErrorCard key={row.key} err={row.err} />
        }
        // [流式性能] 流式块行 → 独立 memo 组件（只订自己块对象）。父级只给稳定 key(sid, blockId)，
        //   content 推进只让目标行重渲；末块行带 'streaming' 尾态 class（同旧逻辑）。
        return (
          <StreamBlockRow
            key={row.key}
            sessionId={row.sid}
            blockId={row.blockId}
            isStreamingTail={row.blockId === lastStreamId}
            onRunHtml={openHtmlPreview}
          />
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
