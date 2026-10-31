import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { isDialogueRow, isUserTurnRow, orderedTurnKeys, turnKeyByRowId, turnKeyOf } from '../../stores/messageTurns'
import { MarkdownText } from '@/markdown/MarkdownText'
import { projectPreview } from '@/markdown/plainPreview'
import type { ChatMessageDto, ToolCallDto } from '@/api/types'
import type { SessionArtifacts, SessionArtifactDto } from '@/api/artifacts'
import type { TurnUsageDto } from '@/api/usageTurns'
import type { SessionFile } from '@/types'
import { subagentColor } from '@/api/types'
import { compactNumber, formatFileSize } from '@/utils/format'
import { briefErrorMessage, briefPayload, dropTextInBriefTurns, isBriefBlock, isSendUserMessage } from '@/utils/sendUserMessage'
import { extractAtRefs } from '@/utils/atRefs'
import { headTailCap } from '@/utils/headTailCap'
// [OBS2] 渲染错误边界（防御性兜底）：见下方「消息区整层」与「逐行」两处包裹
import { ErrorBoundary } from '@/components/common/ErrorBoundary'

/** 稳定空文件列表（changedFiles 零态 selector 兜底 · 避免每次新数组引用触发无限重渲染） */
const NO_CHANGED_FILES: SessionFile[] = []
/** 稳定空产物列表（artifacts 未接时兜底 · 同上避免新数组引用） */
const NO_ARTIFACTS: SessionArtifactDto[] = []

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
import { useChatStore, selectStreamIds, selectStreamBlock, LIVE_SEQ_BASE } from '@/stores/chatStore'
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

/**
 * 流式 token 估算（仅用于「思考中」的实时数字）· 对齐 CC 2.1.296 spinner 口径：
 *   CC `XQr(e)=Math.ceil(e.length/4)` 用 thinking_delta 字符数估 thinking tokens；
 *   正文 delta 走 `onUpdateLength: po += len/4`；两者累加即输出 tokens 估算。
 * provider 的 usage 只在流末尾（OpenAI final chunk / Anthropic message_delta）才出现，
 * 思考过程中的 reasoning delta 不带 usage ⇒ 只能按字数估；message.usage.output_tokens
 * 到达后改用权威值（数字前的 "~" 随之消失）。
 */
function estimateStreamTokens(reasoning: string, content: string): number {
  const len = (reasoning?.length ?? 0) + (content?.length ?? 0)
  return len > 0 ? Math.ceil(len / 4) : 0
}

/** 整轮时长文案（`16m34s` / `34s` / `1h05m`）。无数据 → ''（头部只显示「已完成」）。 */
function formatTurnDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return ''
  const totalSec = Math.round(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const totalMin = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  if (totalMin < 60) return `${totalMin}m${String(sec).padStart(2, '0')}s`
  const hour = Math.floor(totalMin / 60)
  return `${hour}h${String(totalMin % 60).padStart(2, '0')}m`
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

/** ANSI 终端输出渲染（借鉴 TerminalBlock：彩色 spans + 超长 head-tail 截断，
 *  切分口径走 {@link headTailCap}）。 */
function AnsiOutput({ text, error }: { text: string; error?: boolean }) {
  const [expanded, setExpanded] = useState(false)
  // 折叠上限 16 行：对齐 DeepSeek DEFAULT_TERMINAL_MAX_LINES。切分交给 headTailCap ——
  // 它按 ceil(16/2)=8 给出「前 8 + 后 8」的行数（展开态 capped=false，本组件据此渲染全量），故这里必须传 16
  // 而不是 8（传 8 会退化成 4/4）。按钮显隐门控用 hidden > 0 而非 capped：capped 含
  // !expanded，用它会让【展开后按钮消失、无法收起】。
  const MAX_LINES = 16
  // 本仓三个「窗口」常量各司其职，勿互相套用（三处口径独立，改一处不影响其余）：
  //   · MAX_LINES = 16（本处）—— 行数窗口 · 终端输出折叠，按「行」切头/尾
  //   · HEAVY_PREVIEW_CHARS = 5000（同文件 ContentGuard）—— 字符窗口 · 超长 markdown
  //     正文的折叠预览，按「字符」截源文再投影成纯文本
  //   · PREVIEW_SOURCE_CHARACTERS = 2048（@/markdown/plainPreview，单点）—— 字符窗口 ·
  //     轨迹列表 / 弹窗列表的纯文本预览，按「字符」截源文再投影成纯文本
  const lines = useMemo(() => parseAnsiLines(text), [text])
  // 去除末尾纯空行（命令输出的换行终止符不是额外空行）
  const trimmed = useMemo(() => {
    const arr = [...lines]
    while (arr.length > 1 && arr[arr.length - 1]!.every((s) => s.text.trim() === '')) arr.pop()
    return arr
  }, [lines])
  // 纯算术（只吃行数、不 parse），随渲染直接算即可，无需 useMemo。
  const { hidden, capped, headLines, tailLines } = headTailCap(trimmed.length, MAX_LINES, expanded)

  const renderLine = (line: AnsiLine, i: number) => (
    <div key={i} className="tc-ansi-line">
      {line.map((span, j) => span.style === undefined
        ? span.text
        : <span key={j} style={span.style}>{span.text}</span>)}
    </div>
  )

  return (
    <div className={`tc-ansi${error ? ' error' : ''}`}>
      {(capped ? trimmed.slice(0, headLines) : trimmed).map(renderLine)}
      {hidden > 0 && (
        <button type="button" className="tc-ansi-toggle" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '收起' : `… 展开其余 ${hidden} 行`}
        </button>
      )}
      {capped && trimmed.slice(trimmed.length - tailLines).map(renderLine)}
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

/** [轮键·流式块] 块 → 所属轮键：
 *
 *  ① 块**冻结**的 `userMessageId` 命中真实 user 消息 ⇒ 用它 —— 保留「块冻结的落库位置」语义：
 *     用户排队发新消息时，实时块仍属于原轮（若强行改挂「最新 user 消息」，块会排在提问行之前，
 *     头会跑到问题上面）；
 *  ② 否则（null / 窗口外的旧 id / 与落库段不同源）⇒ **最近一条 user 消息 id** —— 与落库 assistant 段
 *     同轮，同一轮只留一个 `N nexus` 头。
 */
function blockTurnKey(
  blockUserId: string | null | undefined,
  userTurnIds: Set<string>,
  latestUserTurnId: string | null,
  fallback: string,
): string {
  if (blockUserId && userTurnIds.has(blockUserId)) return blockUserId
  return latestUserTurnId ?? blockUserId ?? fallback
}

/** 当前窗口内的 user 消息 id 集 + 最近一条 user 消息 id（`groups` 与 `activeTurnKeys` 共用同一口径）。
 *  ⚠️ 判据单点 = stores/messageTurns.isUserTurnRow（与窗口裁剪 capTailTurns 同源）。 */
function collectUserTurnIds(messages: ChatMessageDto[]): { ids: Set<string>; latest: string | null } {
  const ids = new Set<string>()
  let latest: string | null = null
  for (const m of messages) {
    if (!isDialogueRow(m)) continue
    if (isUserTurnRow(m)) { ids.add(m.id); latest = m.id }
  }
  return { ids, latest }
}

/** [工具行图标] 工具名 → 类型（大小写/前缀/后缀都不敏感 · 子串 + 词元）：Bash 终端 / Web 地球 /
 *  Grep 放大镜 / Glob 文件夹+搜索 / Read 眼睛 / Edit 铅笔 / Write 文档 / 其他 扳手。
 *
 *  <p>WHY 组合匹配：工具名有大量变体（`Bash` / `BashTool` / `mcp__x__run_command` / `execute_command` /
 *  `ReadFile` / `WebFetch` …），只按几个固定子串会把「跑命令」的行走成通用扳手。
 *  判定顺序 = 终端 → 网页 → 搜索 → 目录 → 读 → 编辑 → 写，前者优先（`web_search` 不能被 `search` 抢走）。 */
function toolIconKind(name: string): 'bash' | 'web' | 'grep' | 'glob' | 'read' | 'edit' | 'write' | 'other' {
  const n = (name ?? '').toLowerCase()
  const tokens = n.split(/[^a-z0-9]+/).filter(Boolean)
  const has = (keys: string[]): boolean => keys.some((k) => n.includes(k) || tokens.includes(k))
  // 终端 / 命令类：Bash / BashTool / shell / powershell / pwsh / exec / execute_command / run_command / cmd
  if (tokens.includes('sh') || tokens.includes('run')
    || has(['bash', 'shell', 'powershell', 'pwsh', 'terminal', 'exec', 'execute', 'command', 'cmd', 'cmdline'])) return 'bash'
  // 网页类：WebSearch / web_search / WebFetch / web_fetch / browser / fetch
  if (has(['websearch', 'web_search', 'webfetch', 'web_fetch', 'browse', 'browser', 'fetch'])) return 'web'
  // 搜索类：Grep / ripgrep / search_files / file_search / search
  if (has(['grep', 'ripgrep', 'search'])) return 'grep'
  // 目录/文件列举：Glob / list_dir / list_files / find_files
  if (has(['glob', 'list_dir', 'listdir', 'list_files', 'find_files', 'ls_dir'])) return 'glob'
  // 读：Read / ReadFile / read_file / open_file / view_file
  if (has(['read', 'open_file', 'view_file'])) return 'read'
  // 编辑：Edit / str_replace / replace_in_file / apply_patch / NotebookEdit
  //   ⚠️ 'patch' 只认词元/结尾（否则 dispatch 这类词会被误判成编辑）
  if (has(['edit', 'str_replace', 'replace_in_file', 'modify']) || tokens.includes('patch') || n.endsWith('patch')) return 'edit'
  // 写：Write / WriteFile / create_file / save_file
  if (has(['write', 'create_file', 'save_file'])) return 'write'
  return 'other'
}

/** 工具行行首类型图标（内联线性 SVG · 16px 槽位 · 无外部依赖 · 无 emoji） */
function ToolTypeIcon({ name }: { name: string }) {
  const kind = toolIconKind(name)
  const svgProps = {
    viewBox: '0 0 16 16',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.3,
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
  } as const
  return (
    <span className="tc-icon" aria-hidden="true">
      {kind === 'bash' && (
        <svg {...svgProps}><rect x="1.6" y="3" width="12.8" height="10" rx="1.6" /><path d="M4.4 6.4l2.1 2.1-2.1 2.1" /><path d="M8.4 10.6h3.2" /></svg>
      )}
      {kind === 'web' && (
        <svg {...svgProps}><circle cx="8" cy="8" r="6.1" /><path d="M1.9 8h12.2" /><path d="M8 1.9c1.7 1.6 2.6 3.7 2.6 6.1s-.9 4.5-2.6 6.1C6.3 12.5 5.4 10.4 5.4 8s.9-4.5 2.6-6.1z" /></svg>
      )}
      {kind === 'grep' && (
        <svg {...svgProps}><circle cx="7" cy="7" r="4.4" /><path d="M10.3 10.3L14 14" /></svg>
      )}
      {kind === 'glob' && (
        <svg {...svgProps}><path d="M1.7 4.6a1.4 1.4 0 011.4-1.4h2.6l1.3 1.5h4.9a1.4 1.4 0 011.4 1.4v5.5a1.4 1.4 0 01-1.4 1.4H3.1a1.4 1.4 0 01-1.4-1.4z" /><circle cx="9.4" cy="8.7" r="2" /><path d="M10.9 10.2l1.4 1.4" /></svg>
      )}
      {kind === 'read' && (
        <svg {...svgProps}><path d="M1.5 8s2.4-4.4 6.5-4.4S14.5 8 14.5 8s-2.4 4.4-6.5 4.4S1.5 8 1.5 8z" /><circle cx="8" cy="8" r="2" /></svg>
      )}
      {kind === 'edit' && (
        <svg {...svgProps}><path d="M10.9 2.4l2.7 2.7L6.1 12.6l-3.4 1 .9-3.4z" /><path d="M9.4 3.9l2.7 2.7" /></svg>
      )}
      {kind === 'write' && (
        <svg {...svgProps}><path d="M9.4 1.8H4.4a1.4 1.4 0 00-1.4 1.4v9.6a1.4 1.4 0 001.4 1.4h7.2a1.4 1.4 0 001.4-1.4V5.8z" /><path d="M9.4 1.8v4h3.6" /><path d="M5.2 9.4h5.6" /><path d="M5.2 11.8h3.8" /></svg>
      )}
      {kind === 'other' && (
        <svg {...svgProps}><path d="M10.6 2.4a3.4 3.4 0 00-4.4 4.2l-3.7 3.7a1.7 1.7 0 002.4 2.4l3.7-3.7a3.4 3.4 0 004.2-4.4l-2.3 2.3-2.1-2.1z" /></svg>
      )}
    </span>
  )
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
  /** [历史提问跳转] 目标消息 + nonce（同一行重复点击也重新定位）：定位后短暂高亮；
   *  不在当前窗口时先复用 onLoadOlder 补拉更早页，仍找不到则提示「该提问不在当前窗口」。 */
  jumpToMessage?: { id: string; nonce: number } | null
  /** [任务结束入口] 点击「查看所有产物 / 查看所有变更 / 来源」→ App 打开右栏抽屉并切到对应 tab。
   *  省略 ⇒ 不渲染入口。 */
  onOpenRightTab?: (tab: 'artifacts' | 'files' | 'sources') => void
  /** [产物/来源] 本会话产物（后端轮末异步抽取）：正文产物卡片 + 「查看所有产物 (N)」计数 */
  artifacts?: SessionArtifacts
  /** [历史轮用量] 后端按轮聚合的真实用量（键 = 发起该轮的 userMessageId · 只补消息级无 token 的轮） */
  turnUsageFallback?: Map<string, TurnUsageDto>
  /** [用户消息编辑重发] 用户气泡 hover 铅笔 → 裁剪该消息起全部对话并用新文本重发（App 注入）。
   *  缺省（子代理 transcript 等非会话宿主）⇒ 不渲染铅笔入口。
   *  ⚠️ 第三个参数 {@code onTrimmed}：**裁剪完成**回调（App 在裁剪 + 重拉窗口后调用）——
   *  编辑态只覆盖「裁剪」这一小段，裁剪一完成就退出编辑态，发送随后走普通路径
   *  （乐观气泡 + 登记 activeStreams ⇒ 消息区出现「思考中…」），不让用户盯着「发送中…」。 */
  onEditResend?: (messageId: string, newText: string, onTrimmed?: () => void) => Promise<void>
}

/** [紧凑过程行] 工具行标题参数摘要：从 arguments JSON 取「动作对象」字段（command/cmd/path/pattern/query…），
 *  取不到回落原始参数；空白归一为单行、超长截断（完整值放 title，视觉省略由 .tc-summary 负责）。 */
function toolArgSummary(raw: string | null | undefined): string {
  const text = raw?.trim() ?? ''
  if (!text) return ''
  const fromObject = (o: Record<string, unknown>): string => {
    for (const key of ['command', 'cmd', 'file_path', 'filePath', 'path', 'pattern', 'query', 'url', 'prompt', 'description']) {
      const v = o[key]
      if (typeof v === 'string' && v.trim() !== '') return v.trim()
    }
    for (const v of Object.values(o)) {
      if (typeof v === 'string' && v.trim() !== '') return v.trim()
    }
    return ''
  }
  let picked = ''
  if (text.startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(text)
      if (parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        picked = fromObject(parsed as Record<string, unknown>)
      }
    } catch { /* 非合法 JSON（纯命令串等）→ 用原始参数 */ }
  }
  const out = (picked || text).replace(/\s+/g, ' ').trim()
  return out.length > 160 ? `${out.slice(0, 159)}…` : out
}

/** [并行工具组·父行摘要] 在上面的基础上再压短（每行都要短、单行不换行）：
 *  · 复合命令（`cd X && 真正动作`）只取最后一段动作；
 *  · 路径类（无空白的 `D:/a/b/c.py`）只留末段文件名；
 *  · 兜底 60 字符截断（视觉省略另由 .tg-title 的 ellipsis 负责，完整值在 title）。 */
function shortToolArgSummary(raw: string | null | undefined): string {
  let s = toolArgSummary(raw)
  if (!s) return ''
  const parts = s.split(/\s*(?:&&|;|\|\|)\s*/).filter((p) => p.trim() !== '')
  if (parts.length > 1) s = parts[parts.length - 1]!.trim()
  if (!/\s/.test(s) && /[\\/]/.test(s)) s = s.split(/[\\/]/).filter(Boolean).pop() ?? s
  return s.length > 60 ? `${s.slice(0, 59)}…` : s
}

/** 工具调用卡片 · FNT-TC-01：消息级 matchedRule（后端 ChatMessageDto 顶层出站）→ 显示「已自动批准（规则X）」徽标；无数据静默
 *
 *  <p>[紧凑过程行] 默认收起为<b>单行灰字</b>：类型图标 + 状态图标（✓/⊙/✗/⊘）+ 工具名 + 关键参数摘要 + 右侧 chevron，
 *  无卡片边框/背景/阴影；点击整行展开原有 IN/OUT（终端输出保留深底块）。展开/收起能力与状态判据不变。 */
export function ToolCard({ tool, matchedRule, live = false, sessionId }: { tool: NonNullable<ChatMessageDto['toolCalls']>[number]; matchedRule: string | null; live?: boolean; sessionId: string }) {
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
  // [紧凑过程行] 标题参数摘要：WebSearch 优先用解析出的 query，其余取 arguments 关键字段。
  //   [短行] 显示用 shortToolArgSummary（复合命令取末段 / 路径只留文件名，单行不换行）；
  //   完整值仍放 head 与 .tc-summary 的 title tooltip（点开 IN/OUT 也能看全文）。
  const wsQuery = wsData?.query ?? ''
  const summaryTip = wsQuery || toolArgSummary(tool.arguments)
  const summary = wsQuery || shortToolArgSummary(tool.arguments)
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
  // [2026-10-22 问答收敛] 已回答、等待结果：AskUserQuestion 类工具回答后、tool_result 到达前，
  //   卡片显示「已回答 · 等待结果」而不是「执行中」（事件缺失时也不再判为「已中断」）。
  const answered = useChatStore((s) => (tool.id ? s.answeredToolCalls[tool.id] != null : false))
  const interrupted = !hasResult && !tool.isError && !live && !answered
  const runningFront = live && !hasResult && !tool.isError
  // [紧凑过程行] 状态图标/文案与既有判据同源：失败 ✗ / 完成 ✓ / 中断 ⊘ / 运行中（含已回答等待结果）⊙
  const statusKind = tool.isError ? 'error' : hasResult ? 'done' : interrupted ? 'interrupted' : 'running'
  const statusLabel = tool.isError ? '失败' : hasResult ? '已完成' : answered ? '已回答 · 等待结果' : interrupted ? '已中断' : '执行中'
  const statusIcon = tool.isError ? '✗' : hasResult ? '✓' : interrupted ? '⊘' : '⊙'
  const [bgTaskId, setBgTaskId] = useState<string | null>(null)
  useEffect(() => {
    if (!runningFront) { setBgTaskId(null); return }
    let alive = true
    // [批 3a] 显式带当前会话：不带会列出**全部会话**的后台任务（可能命中别的会话同 toolUseId 的任务
    //   → 点「转后台」作用到别的会话的对象上）。本组件 sessionId 为必填 prop。
    tasksApi.list(sessionId).then((list) => {
      if (!alive) return
      const t = (list ?? []).find((x) => x.toolUseId === tool.id && x.isBackgrounded !== true && x.status === 'running')
      if (t) setBgTaskId(t.id)
    }).catch(() => {})
    return () => { alive = false }
  }, [runningFront, tool.id, sessionId])
  const doBackground = async () => {
    if (!bgTaskId) return
    try {
      await tasksApi.background(bgTaskId)
      setBgTaskId(null)
    } catch { /* fail loud：保留按钮可重试 */ }
  }
  return (
    <div className={`tool-card${expanded ? ' open' : ''}`}>
      <button
        type="button"
        className="head"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        title={summaryTip ? `${name}: ${summaryTip}` : name}
      >
        <ToolTypeIcon name={name} />
        <span className={`tc-status ${statusKind}`} role="img" aria-label={statusLabel} title={statusLabel}>{statusIcon}</span>
        <span className={`name${destructive ? ' danger' : ''}`}>{name}</span>
        {summary && <span className="tc-summary" title={summaryTip}>{summary}</span>}
        {rule && (
          <span className="auto-approved" title="该工具调用已被规则自动批准">
            <svg width={9} height={9} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth={1.6} aria-hidden="true">
              <path d="M2.5 6.5l2.2 2.2 4.8-5" />
            </svg>
            已自动批准（{rule}）
          </span>
        )}
        {bgTaskId && (
          <span role="button" className="tc-bg" onClick={(e) => { e.stopPropagation(); e.preventDefault(); void doBackground() }} title="转到后台继续运行（Ctrl+B）">
            转后台
          </span>
        )}
        <svg className="tc-chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} aria-hidden="true">
          <path d="M6 4l4 4-4 4" />
        </svg>
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

/** [并行工具组] 同一条 assistant 消息里的多个工具调用 → 收在一个父行下面（缩进子项），不再平铺成多行同级。
 *
 *  <p>父行：类型图标（取首个工具）+ 聚合状态图标 + 摘要 + 右侧 chevron；点击整行展开/收起（默认展开）。
 *  摘要优先用同轮 `tool_use_summary` 文本（由 MessageList 关联后经 `title` 传入），否则「首个工具名: 参数摘要」，
 *  再否则「并行 N 个工具」；单行不换行，完整值在 title tooltip + 子项 IN/OUT 里看。
 *  子项复用紧凑 {@link ToolCard} —— 每项的状态图标、类型图标、IN/OUT、自动批准徽标、错误/中断全部保留。 */
export function ToolGroup({ tools, matchedRule, sessionId, title, live = false }: {
  tools: ToolCallDto[]
  matchedRule: string | null
  sessionId: string
  /** 同轮 tool_use_summary 文本（父行标题优先来源；缺省回落首个工具名+参数） */
  title?: string
  live?: boolean
}) {
  const [open, setOpen] = useState(true)
  const first = tools[0]!
  const firstName = cleanToolName(first.name)
  const argFull = toolArgSummary(first.arguments)
  const argShort = shortToolArgSummary(first.arguments)
  const fallback = argShort ? `${firstName}: ${argShort}` : firstName
  const label = title?.trim() || fallback || `并行 ${tools.length} 个工具`
  const tip = title?.trim() || (argFull ? `${firstName}: ${argFull}` : firstName) || `并行 ${tools.length} 个工具`
  // 聚合状态：任一失败 → ✗；有未完成（live=执行中 / 已结束=中断）→ ⊙/⊘；否则 ✓
  const anyError = tools.some((t) => t.isError)
  const anyPending = tools.some((t) => t.result == null && !t.isError)
  const anyDone = tools.some((t) => t.result != null)
  const statusKind: 'error' | 'running' | 'interrupted' | 'done' = anyError
    ? 'error'
    : anyPending ? (live ? 'running' : anyDone ? 'done' : 'interrupted') : 'done'
  const statusIcon = statusKind === 'error' ? '✗' : statusKind === 'done' ? '✓' : statusKind === 'interrupted' ? '⊘' : '⊙'
  const statusLabel = statusKind === 'error' ? '有工具失败' : statusKind === 'done' ? '已完成' : statusKind === 'interrupted' ? '已中断' : '执行中'
  return (
    <div className={`tool-group${open ? ' open' : ''}`}>
      <button
        type="button"
        className="tg-head"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        title={tip}
      >
        <ToolTypeIcon name={firstName} />
        <span className={`tc-status ${statusKind}`} role="img" aria-label={statusLabel} title={statusLabel}>{statusIcon}</span>
        <span className="tg-title">{label}</span>
        <svg className="tg-chevron" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} aria-hidden="true">
          <path d="M6 4l4 4-4 4" />
        </svg>
      </button>
      {open && (
        <div className="tg-body">
          {tools.map((t, i) => (
            <ToolCard key={t.id ?? i} tool={t} matchedRule={matchedRule} live={live} sessionId={sessionId} />
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * SendUserMessage 正文渲染 · 对齐 CC {@code tools/BriefTool/UI.tsx:55-68 renderToolResultMessage 默认分支}：
 * <b>无工具边框、无 gutter 标记、无折叠</b>（源码注释逐字：{@code userFacingName() returns '' so …
 * AssistantToolUseMessage renders null (no tool chrome)}），正文 Markdown，下方附件列表。
 *
 * <p><b>WHY 不能走 ToolCard</b>：ToolCard 是「工具调用」的展示（标题 + 折叠 IN/OUT）。SendUserMessage
 * 的定位是「Claude 对用户说的话」——CC 里它读起来就是普通回复正文；而本仓原先因
 * {@code userFacingName()=''} + {@code (name ?? 'tool').trim()} 接不住空串 → 渲染成<b>无名折叠卡</b>，
 * 模型的答案被埋进折叠区。
 *
 * <p>附件行对齐 CC {@code AttachmentList}（UI.tsx:98-100）：{@code [image]}/{@code [file]} + 路径 + 大小。
 */
function SendUserMessageBody({ tool }: { tool: NonNullable<ChatMessageDto['toolCalls']>[number] }) {
  const payload = useMemo(() => briefPayload(tool), [tool])
  // 失败态：不渲染正文/附件，只给失败文案（对齐 CC UserToolResultMessage.tsx:71-86
  //   is_error → UserToolErrorMessage，BriefTool 无 renderToolUseErrorMessage ⇒ 红字兜底）。
  //   ⛔ 顺序在 payload 判定之前 —— 失败时 briefPayload 已返回 null，落到「return null」会静默消失。
  const errText = briefErrorMessage(tool)
  if (errText !== null) return <div className="sum-error">{errText}</div>
  if (!payload) return null
  return (
    <div className="sum-body">
      {payload.message !== '' && <MarkdownText text={payload.message} className="content md" />}
      {payload.attachments.length > 0 && (
        <div className="sum-attachments">
          {payload.attachments.map((a) => (
            <div className="sum-att" key={a.path}>
              <span className="sum-att-kind">{a.isImage === true ? '[image]' : '[file]'}</span>
              <span className="sum-att-path" title={a.path}>{a.path}</span>
              {a.size != null && <span className="sum-att-size"> ({formatFileSize(a.size)})</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** [并行工具组] toolCalls → 渲染段：SendUserMessage 就地渲染（正文语义）；连续的非 brief 工具调用成组
 *  —— 多于 1 个收进一个 {@link ToolGroup} 父行，单个保持原有单行 {@link ToolCard}。
 *  `groupTitle`（同轮 tool_use_summary 文本）只交给本条消息里的第一个组，避免标题重复。 */
function renderToolCalls(
  calls: ToolCallDto[],
  opts: { matchedRule: string | null; sessionId: string; live?: boolean; groupTitle?: string },
): ReactNode[] {
  const out: ReactNode[] = []
  let run: ToolCallDto[] = []
  let titleAssigned = false
  const flush = () => {
    if (run.length === 0) return
    if (run.length === 1) {
      const t = run[0]!
      out.push(<ToolCard key={t.id ?? `tc-${out.length}`} tool={t} matchedRule={opts.matchedRule} live={opts.live} sessionId={opts.sessionId} />)
    } else {
      out.push(
        <ToolGroup
          key={`tg-${run[0]!.id ?? out.length}`}
          tools={run}
          matchedRule={opts.matchedRule}
          sessionId={opts.sessionId}
          live={opts.live}
          title={titleAssigned ? undefined : opts.groupTitle}
        />,
      )
      titleAssigned = true
    }
    run = []
  }
  for (const t of calls) {
    if (isSendUserMessage(t)) {
      flush()
      out.push(<SendUserMessageBody key={t.id ?? `sum-${out.length}`} tool={t} />)
    } else {
      run.push(t)
    }
  }
  flush()
  return out
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
function CompactSummaryCard({ msg, highlighted }: { msg: ChatMessageDto; highlighted?: boolean }) {
  const [expanded, setExpanded] = useState(false)
  const text = msg.content ?? ''
  return (
    <div className={`msg compact-summary${highlighted ? ' jump-highlight' : ''}`} data-msg-id={msg.id}>
      <div className="cs-head">
        <span className="cs-dot" aria-hidden>⏺</span>
        <span className="cs-badge">已压缩</span>
        <span className="cs-title">对话历史已总结</span>
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
 *  ——窗口化只限「条数」不限「单条体积」。初始加载不被病理大消息阻塞，展开由用户主动触发。
 *  预览层已做 markdown→纯文本投影（projectPreview）：原先直接把 markdown 源文塞进
 *  <pre>，`#` 标题 / `**` 粗体 / 围栏等标记字面泄漏。投影语法与展开态**不同源** —— 用流式臂
 *  parseGfm（无 math 扩展、不跑 rescue），故行内 $$…$$ 与粘连标记（`##标题` 这类 rescue 会
 *  补空格的）在预览里保持字面，与展开态可能不同（预览少剥、不藏内容）；且投影逐行 trim，
 *  代码块缩进会被抹平 —— 均为已拍板接受的取舍。 */
const HEAVY_CONTENT_CHARS = 20_000
const HEAVY_PREVIEW_CHARS = 5_000
function ContentGuard({ text, className, onRunHtml }: { text: string; className: string; onRunHtml?: (code: string) => void }) {
  const heavy = text.length > HEAVY_CONTENT_CHARS
  const [expanded, setExpanded] = useState(false)
  // 预览：先按字符截断源文、再投影（顺序不可换 —— 反过来会对 >20KB 全文 parse，
  // 正好抵消本组件「不 parse 超长正文」的存在理由）。
  // 投影用流式臂语法（parseGfm：无 math 扩展、不跑 rescue），故行内 $$…$$ 与粘连标记
  // （##标题 这类 rescue 会补空格的）在此保持字面，与展开态可能不同 —— 已拍板接受。
  // 另：投影逐行 trim，代码块缩进会抹平（<pre> 预览的已知取舍）。
  // useMemo 必须位于提前 return 之前（守 hooks 顺序），但 heavy 短路不可省：text 来自
  // msg.content，流式期间每帧变化，不短路则每条正常消息（≤20000 字符）在流式期间每帧都会
  // 白跑一次非增量 micromark 解析，而 MarkdownText 本身已是增量解析 —— 这笔是净增开销。
  // 该值仅在「heavy 且未展开」分支被读（heavy=false 必然走上面的提前 return），
  // 故短路产生的 '' 不会被读到。注意投影本身也可能返回空串（例如切片全是
  // thematicBreak/definition），那种空预览是已知边界，非本短路所致。
  const shown = useMemo(
    () => (heavy ? projectPreview(text, HEAVY_PREVIEW_CHARS) : ''),
    [text, heavy],
  )
  if (!heavy || expanded) return <MarkdownText text={text} className={className} onRunHtml={onRunHtml} />
  return (
    <div className={className}>
      <pre className="heavy-preview">{shown}{text.length > HEAVY_PREVIEW_CHARS ? '…' : ''}</pre>
      <button type="button" className="heavy-expand" onClick={() => setExpanded(true)} title="展开后需渲染完整内容，可能短暂卡顿">
        查看完整内容（本条 {Math.round(text.length / 1024)}KB · 展开较耗资源）
      </button>
    </div>
  )
}

/** [本轮消耗] TurnFooter 一行展示的数据（见 formatTurnUsageLabel）。 */
interface TurnUsageSummary {
  /** 本轮金额（元）· 会话累计花费差分；无可靠差分 → null（回落 tokens） */
  costYuan: number | null
  /** 本轮 input/output tokens 合计（真实 usage 优先，回落 legacy inputTokens/outputTokens） */
  inputTokens: number | null
  outputTokens: number | null
  /** 本轮模型短名（去 provider 前缀；多模型用 / 连接） */
  model: string | null
  /** 该轮最后一条消息时间（「9月3日 13:01」；相对时间兜底） */
  time: string | null
}

/** 模型全名 → 末段短名（ds-openai/deepseek-v4-flash → deepseek-v4-flash · 与 Composer 同口径）。 */
function shortModelName(name: string | null | undefined): string | null {
  if (!name) return null
  const short = (name.split('/').pop() ?? name).trim()
  return short || null
}

/** 该轮最后一条消息时间 → 「9月3日 13:01」（createdAt 解析失败回落后端人读相对时间）。 */
function formatTurnStamp(m: ChatMessageDto | null): string | null {
  if (!m) return null
  if (m.createdAt) {
    const d = new Date(m.createdAt)
    if (!Number.isNaN(d.getTime())) {
      return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    }
  }
  return m.time ?? null
}

/** 一行文案：`共消耗 ◇ 15.03 · GLM-5.3-Flash · 9月3日 13:01`；
 *  无金额数据 → `共消耗 ↑253.8k ↓762`（本轮 input/output 汇总）。无可展示字段 → null。 */
function formatTurnUsageLabel(u: TurnUsageSummary): string | null {
  const parts: string[] = []
  if (u.costYuan != null && u.costYuan > 0) {
    parts.push(`共消耗 ◇ ${u.costYuan.toFixed(2)}`)
  } else if (u.inputTokens != null || u.outputTokens != null) {
    const tokens = [
      u.inputTokens != null ? `↑${compactNumber(u.inputTokens)}` : '',
      u.outputTokens != null ? `↓${compactNumber(u.outputTokens)}` : '',
    ].filter(Boolean).join(' ')
    if (tokens) parts.push(`共消耗 ${tokens}`)
  }
  if (u.model) parts.push(u.model)
  if (u.time) parts.push(u.time)
  return parts.length > 0 ? parts.join(' · ') : null
}

/** 正文产物卡片图标（文件线条 · 与右栏空态同一描边风格） */
const ARTIFACT_ICON_SVG = (
  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M5.2 2.8h6.1L15 6.5v10.7H5.2z" />
    <path d="M11.1 2.9v3.7h3.8" />
  </svg>
)

/** [产物卡片] 本轮交付物（最多 3 条 · 超出部分走 TurnFooter 的「查看所有产物」）：
 *  整块可点 → 打开本地文件；无 localPath 的产物不绑定点击（不假装能打开）。 */
function TurnArtifacts({ items, onOpen }: {
  items: SessionArtifactDto[]
  onOpen?: (path: string) => void
}) {
  const shown = items.slice(0, 3)
  return (
    <div className="msg-artifacts">
      {shown.map((a) => {
        const path = a.localPath
        const sub = [a.sizeBytes != null ? formatFileSize(a.sizeBytes) : '', a.summary ?? '']
          .filter(Boolean).join(' · ')
        return (
          <div
            key={a.id}
            className={`msg-artifact${path ? ' clickable' : ''}`}
            title={path ?? a.title}
            onClick={path ? () => onOpen?.(path) : undefined}
          >
            <span className="ma-icon" aria-hidden="true">{ARTIFACT_ICON_SVG}</span>
            <span className="ma-main">
              <span className="ma-name">{a.title}</span>
              {sub && <span className="ma-sub">{sub}</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

function TurnFooter({ artifactCount, changeCount, onOpen, usageLabel }: {
  artifactCount: number
  changeCount: number
  /** 右栏入口回调：只有「会话最后一条 assistant」那一轮的 footer 传（历史轮只显示用量行） */
  onOpen?: (tab: 'artifacts' | 'files' | 'sources') => void
  /** [本轮消耗] 本轮汇总一行（金额/模型/时间）；无数据 → 不渲染 */
  usageLabel?: string | null
}) {
  return (
    <div className="msg-links">
      {/* [2026-10-29 用户裁定] 产物 / 变更 / 来源是**一组**：三个入口永远同时出现，
          没有数据也显示 (0) —— 不要按数量决定是否渲染入口（否则用户会以为功能缺失）。 */}
      {onOpen && (
        <button type="button" className="msg-link" onClick={() => onOpen('artifacts')}>
          查看所有产物 <span className="n">({artifactCount})</span> <span className="arr">›</span>
        </button>
      )}
      {onOpen && (
        <button type="button" className="msg-link" onClick={() => onOpen('files')}>
          查看所有变更 <span className="n">({changeCount})</span> <span className="arr">›</span>
        </button>
      )}
      {onOpen && <button type="button" className="msg-link" onClick={() => onOpen('sources')}>来源</button>}
      {usageLabel && <span className="turn-usage">{usageLabel}</span>}
    </div>
  )
}

export function Message({ msg, onDelete, onRunHtml, onOpenRefFile, onEditResend, footer, highlighted, processExpanded, showProcessHeader, turnDurationMs, turnKey, onToggleProcess, showHeader, groupTitle, summaryToolName }: {
  msg: ChatMessageDto
  onDelete: (id: string) => void
  onRunHtml?: (code: string) => void
  onOpenRefFile?: (path: string) => void
  /** [用户消息编辑重发] 见 MessageListProps */
  onEditResend?: (messageId: string, newText: string, onTrimmed?: () => void) => Promise<void>
  footer?: ReactNode
  /** [历史提问跳转] 本次跳转目标正是这行 → 加 .jump-highlight（1.5s 后由宿主清除） */
  highlighted?: boolean
  /** [轮次中间过程] 该轮展开态；undefined = 非轮次折叠宿主（子代理 transcript 等）→ 保持旧渲染 */
  processExpanded?: boolean
  /** 是否在该消息顶部渲染「已完成」行（每轮仅第一条含中间过程的消息渲染一次） */
  showProcessHeader?: boolean
  /** 整轮时长（ms · 无数据 → 只显示「已完成」） */
  turnDurationMs?: number | null
  /** [轮次中间过程] 所属轮次键（切换展开态用） */
  turnKey?: string
  /** 稳定的轮次展开切换回调（turnKey + 当前态） */
  onToggleProcess?: (turnKey: string, open: boolean) => void
  /** [轮次消息头合并] 是否渲染消息头（头像 + 作者 + 时间 + 徽标）。同一轮只有第一条 assistant 段为 true，
   *  后续工具/正文段传 false —— 不再重复「N nexus」；缺省 true（子代理 transcript 等非轮次宿主保持旧渲染）。 */
  showHeader?: boolean
  /** [并行工具组] 同轮 tool_use_summary 文本（该轮首个工具组的父行标题；缺省回落首个工具名+参数） */
  groupTitle?: string
  /** [摘要行图标] 同轮首个工具名（tool_use_summary 行按它选类型图标；缺省留 ⚒） */
  summaryToolName?: string
}) {
  const isUser = msg.role === 'user'
  // [轮次消息头合并] 同轮后续段不渲染头像/作者/时间/徽标；仍留同宽头像占位，保证正文/工具行与首段左对齐。
  const renderHeader = showHeader !== false
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
  // [2026-10-29 用户裁定] 主对话鼠标移上去不再显示「删除」（消息删除能力保留在 onDelete 通道，
  //   仅隐藏入口；将来若要恢复，把下面常量改回 `isUser || msg.role === 'assistant'` 即可）。
  const deletable = false
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
  // 37 · thinking 耗时（ms → 秒，保留 1 位小数 · 对齐 dsh「思考过程 7.3s」）；无耗时 → 不追加
  const reasoningLabel = msg.reasoningDurationMs != null && msg.reasoningDurationMs > 0
    ? ` ${(msg.reasoningDurationMs / 1000).toFixed(1)}s`
    : ''
  const [hovered, setHovered] = useState(false)
  // [用户消息编辑重发] hover 元信息行（时间 / 编辑 / 复制）+ 内联编辑态：状态留在组件内，不进全局 store
  const [editing, setEditing] = useState(false)
  const [editDraft, setEditDraft] = useState('')
  const [editSending, setEditSending] = useState(false)
  // [慢裁剪提示] 裁剪超过 1s 才为 true ⇒ 按钮文案切到「正在裁剪并重新发送…」（防用户以为卡死）
  const [editSlow, setEditSlow] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const editInputRef = useRef<HTMLTextAreaElement>(null)
  const copiedTimerRef = useRef<number | null>(null)
  // 编辑框自动高度：1 行起，最多 8 行（超出滚动）；14px * 1.6 ≈ 22px/行
  useEffect(() => {
    const el = editInputRef.current
    if (!editing || !el) return
    el.style.height = 'auto'
    const line = 23
    el.style.height = `${Math.min(Math.max(el.scrollHeight, line), line * 8)}px`
  }, [editing, editDraft])
  // 进入编辑态即聚焦输入框（光标落在末尾，与气泡文本连续）
  useEffect(() => {
    const el = editInputRef.current
    if (!editing || !el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }, [editing])
  useEffect(() => () => {
    if (copiedTimerRef.current != null) window.clearTimeout(copiedTimerRef.current)
  }, [])
  const startEdit = () => {
    setEditDraft(msg.content ?? '')
    setEditError(null)
    setEditSlow(false)
    setEditing(true)
  }
  const cancelEdit = () => {
    setEditing(false)
    setEditError(null)
    setEditSlow(false)
  }
  const submitEdit = async () => {
    const text = editDraft.trim()
    if (!text || editSending || !onEditResend) return
    setEditSending(true)
    setEditError(null)
    // [慢裁剪提示] 裁剪若超过 1s（大会话 / 磁盘慢）才提示「正在裁剪并重新发送…」——
    //   正常路径（后端区间删除，毫秒级）不显示，避免每次都闪一下长文案。
    const slowTimer = window.setTimeout(() => setEditSlow(true), 1000)
    try {
      // onTrimmed：裁剪完成 ⇒ 立刻退出编辑态（编辑态只覆盖裁剪这一小段）；
      //   随后的发送走普通路径（乐观气泡 + 登记 activeStreams ⇒「思考中…」出现）。
      await onEditResend(msg.id, text, () => {
        window.clearTimeout(slowTimer)
        setEditSlow(false)
        setEditSending(false)
        setEditing(false)
      })
      setEditing(false)
    } catch {
      // 失败保持编辑态（草稿不丢），由调用方另行 toast 具体原因
      setEditError('发送失败，请重试')
    } finally {
      window.clearTimeout(slowTimer)
      setEditSlow(false)
      setEditSending(false)
    }
  }
  const copyContent = async () => {
    try {
      await navigator.clipboard?.writeText(msg.content ?? '')
      setCopied(true)
      if (copiedTimerRef.current != null) window.clearTimeout(copiedTimerRef.current)
      copiedTimerRef.current = window.setTimeout(() => setCopied(false), 1200)
    } catch { /* 剪贴板不可用（非安全上下文）→ 静默，不打断阅读 */ }
  }
  // 思考块折叠：历史消息默认收起（可手动展开）；流式消息（streaming.reasoning）保持展开
  // [2026-10-28] 历史消息的思考块默认收起：轮次展开只展开工具/过程行，
  //   思考内容必须由用户再点「思考过程」才展开（不再跟随 processExpanded 自动铺开）。
  const [showReasoning, setShowReasoning] = useState(false)
  // [轮次中间过程折叠] 折叠粒度 = 轮次（同一 userMessageId 的所有 assistant 段共享展开态）：
  //   轮次进行中 → 中间过程正常展示（不收起）；轮次结束后 → 整轮默认收起，只留最终正文 + 产物入口。
  //   SendUserMessage 是模型交付正文而非中间过程 → 始终留在正文区。
  //   processExpanded === undefined（子代理 transcript 等非轮次宿主）→ 保持旧渲染。
  const collapsible = processExpanded !== undefined
  const intermediateReasoning = cleanReasoning(msg.reasoning)
  const briefToolCalls = (msg.toolCalls ?? []).filter(isSendUserMessage)
  const intermediateToolCalls = (msg.toolCalls ?? []).filter((t) => !isSendUserMessage(t))
  const hasIntermediate = !isUser && !isFallback && !isScheduledFire && !msg.isApiErrorMessage
    && (intermediateReasoning !== '' || intermediateToolCalls.length > 0)
  const turnDuration = formatTurnDuration(turnDurationMs)
  // 轮次展开/收起：内层思考正文跟随轮次态（展开即可读；内层 toggle 仍可单独收起本轮思考）
  useEffect(() => {
    // 轮次折叠/展开切换时，思考块始终回到收起态（用户手动展开仅在当前视图内有效）
    if (collapsible) setShowReasoning(false)
  }, [collapsible, processExpanded])
  // [思考窗口] 固定高度（约 3 行）滚动窗口：展开/内容增长时自动滚到底 —— 只露最新内容，不整段铺开
  const thinkingBodyRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = thinkingBodyRef.current
    if (el && showReasoning) el.scrollTop = el.scrollHeight
  }, [showReasoning, intermediateReasoning])
  const toggleProcess = () => onToggleProcess?.(turnKey ?? '', processExpanded === true)
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
      <div className="msg boundary" data-msg-id={msg.id}>
        <div className="boundary-note">
          <span className="boundary-label">{boundaryLabel}</span>
        </div>
      </div>
    )
  }
  // tool_use_summary 摘要行：居中弱化窄行（类型图标 + 单行文本 · title 全文）——无删除、不进输入历史。
  //   能关联到本轮工具时用真实类型图标（Bash/命令类 → 终端图标，⛔ 不用通用扳手）；关联不上才留 ⚒。
  if (isToolUseSummary) {
    return (
      <div className="msg tool-use-summary" data-msg-id={msg.id}>
        <span className="tus-icon" aria-hidden="true">
          {summaryToolName ? <ToolTypeIcon name={summaryToolName} /> : '⚒'}
        </span>
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
    return <CompactSummaryCard msg={msg} highlighted={highlighted} />
  }
  // [同轮段间距] 同轮非首段（消息头已合并到本轮首段）→ .turn-cont：与上一段贴近（6px），
  //   只收窄「后面紧跟同轮段」的那条；不同轮次 / 用户↔助手之间仍是 24px（见 globals.css）。
  const turnContinuation = !isUser && !isFallback && !isScheduledFire && !renderHeader
  /** 思考块（轮次折叠宿主内由外层「已完成」控制；非轮次宿主保持旧的自折叠行为）。 */
  // [2026-10-28 用户要求] 历史思考过程也要显示 token：后端无 reasoning_tokens 字段 ⇒ 按字符/4 估算（CC 口径）
  const reasoningTokens = intermediateReasoning ? Math.ceil(intermediateReasoning.length / 4) : 0
  const reasoningBlock = intermediateReasoning ? (
    <div className={`thinking-wrap${showReasoning ? ' open' : ''}`}>
      <button className="thinking-toggle" onClick={() => setShowReasoning((v) => !v)}>
        {/* [样式对齐 dsh] 思考图标 + 文案 + 折叠箭头 */}
        <svg className="thinking-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2">
          <path d="M8 2.2v2.6M8 11.2v2.6M2.2 8h2.6M11.2 8h2.6M4.1 4.1l1.8 1.8M10.1 10.1l1.8 1.8M11.9 4.1l-1.8 1.8M5.9 10.1l-1.8 1.8" strokeLinecap="round" />
        </svg>
        <span>
          思考过程{reasoningLabel}
          {reasoningTokens > 0 ? ` · ${compactNumber(reasoningTokens)} tokens` : ''}
        </span>
        <svg className="thinking-caret" viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
      </button>
      {showReasoning && <div className="thinking-body" ref={thinkingBodyRef}>{intermediateReasoning}</div>}
    </div>
  ) : null
  // [用户消息 hover] 时间恒带日期（同一天也显示「10月29日 15:42」，用户要求「时间和日期」）；
  //   createdAt 解析失败才回落原有的相对时间文案。
  const userStamp = (() => {
    if (msg.createdAt) {
      const d = new Date(msg.createdAt)
      if (!Number.isNaN(d.getTime())) {
        return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
      }
    }
    return formatMsgTime(msg)
  })()
  return (
    <div
      className={`msg ${isUser ? 'user' : isFallback ? 'fallback' : isScheduledFire ? 'system-notice' : 'assistant'}${highlighted ? ' jump-highlight' : ''}${turnContinuation ? ' turn-cont' : ''}`}
      data-msg-id={msg.id}
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
          {editing ? (
            /* [用户消息编辑重发] 编辑态：气泡位置换成输入块（底部提示 + 取消/发送） */
            <div className="user-edit">
              <textarea
                ref={editInputRef}
                className="user-edit-input"
                value={editDraft}
                rows={1}
                disabled={editSending}
                onChange={(e) => setEditDraft(e.target.value)}
                onKeyDown={(e) => {
                  // Enter 发送 / Shift+Enter 换行 / Esc 取消（输入法组合中的 Enter 不算发送）
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    void submitEdit()
                  } else if (e.key === 'Escape') {
                    e.preventDefault()
                    cancelEdit()
                  }
                }}
              />
              <div className="user-edit-hint">
                <svg className="ue-info" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.2} aria-hidden="true">
                  <circle cx="8" cy="8" r="6.3" />
                  <path d="M8 7.4v3.1" strokeLinecap="round" />
                  <path d="M8 5.2v.01" strokeLinecap="round" />
                </svg>
                <span>编辑后将从此处重新开始对话，已有产物不会被删除</span>
              </div>
              {editError && <div className="user-edit-error" role="alert">{editError}</div>}
              <div className="user-edit-actions">
                <button type="button" className="ue-cancel" onClick={cancelEdit} disabled={editSending}>取消</button>
                <button
                  type="button"
                  className="ue-send"
                  onClick={() => void submitEdit()}
                  disabled={editSending || editDraft.trim() === ''}
                >
                  {editSending ? (editSlow ? '正在裁剪并重新发送…' : '发送中…') : '发送'}
                </button>
              </div>
            </div>
          ) : (
            <>
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
              {/* [用户消息编辑重发] 气泡下方 meta 行：默认隐藏 · hover 淡入（时间 + 编辑 + 复制） */}
              <div className={`user-msg-meta${hovered ? ' show' : ''}`}>
                <span className="um-time">{userStamp}</span>
                {onEditResend && (
                  <button type="button" className="um-act" title="编辑并从此处重新开始" aria-label="编辑并从此处重新开始" onClick={startEdit}>
                    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.2} aria-hidden="true">
                      <path d="M11.1 2.4l2.5 2.5-8.2 8.2-3.3.8.8-3.3z" strokeLinejoin="round" />
                      <path d="M9.7 3.8l2.5 2.5" strokeLinecap="round" />
                    </svg>
                  </button>
                )}
                <button
                  type="button"
                  className={`um-act${copied ? ' is-copied' : ''}`}
                  title={copied ? '已复制' : '复制'}
                  aria-label="复制"
                  onClick={() => void copyContent()}
                >
                  {copied ? (
                    <span className="um-copied">已复制</span>
                  ) : (
                    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.2} aria-hidden="true">
                      <rect x="5.6" y="5.6" width="7.8" height="7.8" rx="1.6" />
                      <path d="M10.4 3.6H4.2c-.8 0-1.4.6-1.4 1.4v6.2" strokeLinecap="round" />
                    </svg>
                  )}
                </button>
              </div>
            </>
          )}
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
          {renderHeader ? <div className="avatar">N</div> : <div className="avatar-gutter" aria-hidden />}
          <div className="body">
            {renderHeader ? (
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
            ) : isSnipped ? (
              // 消息头已合并到该轮首段：裁剪标注不能随消息头一起消失
              <div className="author"><span className="snipped-badge" title="该消息已被 Snip 裁剪，不再发送给模型">已裁剪</span></div>
            ) : null}
            {collapsible ? (
              <>
                {showProcessHeader && hasIntermediate && (
                  <button
                    type="button"
                    className="turn-process-toggle"
                    onClick={toggleProcess}
                    aria-expanded={processExpanded === true}
                    title={processExpanded ? '收起本轮中间过程' : '展开本轮中间过程'}
                  >
                    <span className="turn-process-status">已完成</span>
                    {turnDuration && <span className="turn-process-dur">· {turnDuration}</span>}
                    <svg className={`turn-process-caret${processExpanded ? ' open' : ''}`} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5}>
                      <path d="M5 6.5L8 9.5L11 6.5" />
                    </svg>
                  </button>
                )}
                {processExpanded === true && hasIntermediate && (
                  <div className="turn-process-body">
                    {reasoningBlock}
                    {/* [并行工具组] 多工具 → 一个父行 + 缩进子项；单个保持单行 ToolCard */}
                    {renderToolCalls(intermediateToolCalls, { matchedRule: msg.matchedRule, sessionId: msg.sessionId, groupTitle })}
                  </div>
                )}
              </>
            ) : reasoningBlock}
            {msg.isApiErrorMessage ? (
              <div className="content error">
                <p>{msg.apiError ?? msg.error ?? 'API 错误'}</p>
                {msg.errorDetails && <p className="error-details">{msg.errorDetails}</p>}
              </div>
            ) : (
              <ContentGuard text={msg.content ?? ''} className="content md" onRunHtml={onRunHtml} />
            )}
            {/* [每轮消耗上收] 原先每条 assistant 消息底部各渲染一次 ↑输入/↓输出（同轮多段反复显示）；
                现改为每轮只在 TurnFooter 汇总一行（见 TurnFooter / usageLabelByTurn）。 */}
            {/* SendUserMessage（CC BriefTool）是模型交付正文（无工具边框）→ 始终显示在正文区；
                普通工具卡（技能加载 / 运行校验等）已收进轮次「已完成」折叠区。
                非轮次折叠宿主（子代理 transcript）保持旧的按序渲染。 */}
            {collapsible
              ? briefToolCalls.map((t, i) => <SendUserMessageBody key={t.id ?? i} tool={t} />)
              : renderToolCalls(msg.toolCalls ?? [], { matchedRule: msg.matchedRule, sessionId: msg.sessionId, groupTitle })}
            {/* [任务结束入口] 仅最后一条 assistant 消息、且本轮不在运行/流式中时传入（见 MessageListInner） */}
            {footer}
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
const StreamBlockRow = memo(function StreamBlockRow({ sessionId, blockId, isStreamingTail, onRunHtml, showHeader = true }: {
  sessionId: string
  blockId: string
  isStreamingTail: boolean
  onRunHtml: (code: string) => void
  /** [轮次消息头合并] 同轮非首段流式块不渲染消息头（见 Message.showHeader） */
  showHeader?: boolean
}) {
  // 只订自身块对象：append 后该块是新对象 → 重渲；其它块 append（数组换新但本块对象引用不变）→ bail
  const b = useChatStore(selectStreamBlock(sessionId, blockId))
  // [bug-101] 流式思考块收起：行内本地态（此前父级 Record 存收起态，整表重渲；行内自持语义不变）
  // [2026-10-22 完成后自动收起] 进行中展开（看得到正在想什么），**本轮结束即收起**为一行
  //   「思考过程」；用户仍可点击重新展开（图 58 → 图 59 的诉求）。
  const [collapsed, setCollapsed] = useState(!isStreamingTail)
  useEffect(() => {
    if (!isStreamingTail) setCollapsed(true)
  }, [isStreamingTail])
  // [思考窗口] 固定高度（约 3 行）滚动窗口：展开且流式增量时自动滚到底 —— 只露最新内容，不整段铺开
  const reasoningText = cleanReasoning(b?.reasoning)
  const thinkingBodyRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = thinkingBodyRef.current
    if (el && !collapsed) el.scrollTop = el.scrollHeight
  }, [collapsed, reasoningText])
  if (!b) return null
  // [思考耗时] 完成态展示与 DB 消息路径同口径：秒数 = 块内起止墙钟差；token = 字符/4 估算
  const reasoningTokens = b.reasoning ? Math.ceil(b.reasoning.length / 4) : 0
  const reasoningMs = b.reasoningStartedAt != null && b.reasoningEndedAt != null
    ? Math.max(0, b.reasoningEndedAt - b.reasoningStartedAt)
    : null
  // 文案与 DB 消息路径（本文件 Message 的 reasoningLabel）完全一致：秒数前置一个空格、无分隔点
  const reasoningLabel = reasoningMs != null ? ` ${(reasoningMs / 1000).toFixed(1)}s` : ''
  // [样式对齐 dsh] 进行中显示「思考中·· {tokens}」；完成显示「思考过程」
  // [2026-10-27 用户要求 · 修订] usage 只在一条 assistant 消息**结束时**上报（reasoning delta 不带
  //   usage），所以思考过程中先按已收到的 reasoning/正文**字数/4**估算输出 tokens（CC 同口径），
  //   数字才会随 delta 递增；message.usage.output_tokens 到达后改用权威值。估算值前加 "~" 明示非精确值。
  //   （旧规则「前端不做估算」因用户要求「思考过程中也要有数字」而放宽，仅限这个显示位。）
  const authoritativeTokens = b.usage?.output_tokens ?? null
  const estimatedTokens = estimateStreamTokens(reasoningText, b.content ?? '')
  const streamTokens = authoritativeTokens ?? (estimatedTokens > 0 ? estimatedTokens : null)
  const isEstimate = authoritativeTokens == null
  const tokenText = streamTokens == null
    ? null
    : streamTokens >= 1000 ? `${(streamTokens / 1000).toFixed(1)}k tokens` : `${streamTokens} tokens`
  const tokenLabel = tokenText == null ? null : `${isEstimate ? '~' : ''}${tokenText}`
  /** 进行中：思考中 + 三点波浪（+ tokens）；完成：思考过程。 */
  const thinkingRunning = isStreamingTail
  return (
    <div className={`msg assistant${isStreamingTail ? ' streaming' : ''}${showHeader ? '' : ' turn-cont'}`}>
      {showHeader ? <div className="avatar">N</div> : <div className="avatar-gutter" aria-hidden />}
      <div className="body">
        {showHeader && <div className="author">nexus</div>}
        {reasoningText !== '' && (
          <div className={`thinking-wrap${collapsed ? '' : ' open'}`}>
            <button className="thinking-toggle" onClick={() => setCollapsed((v) => !v)}>
              {/* [样式对齐 dsh] 思考图标 + 文案 + 折叠箭头（箭头单独 class ⇒ open 时只转箭头） */}
              <svg className="thinking-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2">
                <path d="M8 2.2v2.6M8 11.2v2.6M2.2 8h2.6M11.2 8h2.6M4.1 4.1l1.8 1.8M10.1 10.1l1.8 1.8M11.9 4.1l-1.8 1.8M5.9 10.1l-1.8 1.8" strokeLinecap="round" />
              </svg>
              {/* [思考耗时] 思考中保持原样；已结束（不再尾块）= 思路同 DB 消息路径：思考过程 + 耗时 + tokens */}
              <span>
                {thinkingRunning
                  ? '思考中'
                  : `思考过程${reasoningLabel}${reasoningTokens > 0 ? ` · ${compactNumber(reasoningTokens)} tokens` : ''}`}
              </span>
              {thinkingRunning && (
                <span className="wave-dots" aria-hidden="true"><i /><i /><i /></span>
              )}
              {thinkingRunning && tokenLabel != null && streamTokens != null && streamTokens > 0 && (
                <span className="thinking-tokens">{tokenLabel}</span>
              )}
              <svg className="thinking-caret" viewBox="0 0 24 24"><path d="M9 18l6-6-6-6" /></svg>
            </button>
            {!collapsed && <div className="thinking-body" ref={thinkingBodyRef}>{reasoningText}</div>}
          </div>
        )}
        {/* 实时侧 dropText 等价（对齐 CC dropTextInBriefTurns 作用于流式文本）：本块含
            SendUserMessage → 该轮助手正文是工作笔记，不展示（答案在工具正文里）。 */}
        {b.content && !isBriefBlock(b.toolCalls) && <MarkdownText text={b.content} streaming className="content md" onRunHtml={onRunHtml} />}
        {/* [并行工具组] 流式块同样成组（live=true ⇒ 未返回结果的子项显示「执行中」） */}
        {renderToolCalls(b.toolCalls, { matchedRule: null, sessionId, live: true })}
      </div>
    </div>
  )
})

// ---- [轮分页 + 折叠] 分页单位 = 轮，由**后端**决定（App 首屏拉最近 3 轮、「加载更多」再拉 2 轮）----
//   [折叠显示 · 用户 2026-10-29 裁定] store 里的已加载轮**永不丢弃**（chatStore 追加路径只增不裁）；
//   比「默认展开窗口」（最近 FOLD_DEFAULT_TURNS 轮）更早的轮在**渲染层折叠成一行**
//   「已折叠 N 轮对话（点击展开）」：折叠态**不渲染 DOM**（既留数据又不占渲染），点击后本地展开（不发请求）。
//   · 折叠的轮恒为**头部连续一段**（前缀）—— 这样折叠行固定放在「加载更多」按钮下方就是正确位置；
//     中间出现「展开的早轮」时不再折后面的轮（避免折叠行与展开内容交错）。
//   · 进行中的轮（有流式块 / turn 未结束）**绝不折叠**。
//   · 「加载更多」新拉到的轮**直接展开**（用户主动要的）⇒ 记入 revealed 集合，不再折回去。
//   · F5 / 打开会话 = App 首屏只拉最近 3 轮 + revealed 为空 ⇒ 刷新后自然回到默认 3 轮（不持久化任何折叠态）。
//   ⛔ 不做触顶自动加载，只能用户点按钮（手动加载）。
/** 每次「加载更多」从后端拉取的轮数（与 App.handleLoadOlder 的 turns=2 一致，仅用于按钮文案） */
const LOAD_OLDER_TURNS = 2
/** 默认展开窗口：最近多少轮保持展开（与 App 首屏 listTurns({turns:3}) 同值）*/
const FOLD_DEFAULT_TURNS = 3
/** 稳定空集合（折叠态零态；模块级同引用 ⇒ 不触发无谓重渲）*/
const EMPTY_FOLD_KEYS: ReadonlySet<string> = new Set<string>()

function MessageListInner({ messages, sessionId, onDelete, conversationId, scrollSignal, thinking, onNearBottomChange, onOpenRefFile, onLoadOlder, jumpToMessage, onOpenRightTab, artifacts, turnUsageFallback, onEditResend }: MessageListProps) {
  // F10 · 消息 row key 并入 conversationId（partial 压缩/裁剪后旋转）→ 触发整列表 remount
  //   useCallback 稳定引用（flatRows useMemo 依赖它 —— 每 render 新函数会让 flatRows 每 chunk 全量重建）
  const rowKey = useCallback((id: string) => (conversationId ? `${conversationId}:${id}` : id), [conversationId])
  // [流式性能] 稳定行序订阅：引用只在块增/删/finalize/clear 变化；content 追加不触碰 → 父级不被打字机逐帧重渲
  const streamIds = useChatStore(selectStreamIds(sessionId))
  // [产物口径] 产物 ≠ 修改的文件：数量来自后端轮末异步抽取结果。
  const artifactCount = artifacts?.deliverables.length ?? 0
  const changeCount = useChatStore((s) => (sessionId ? (s.changedFiles[sessionId] ?? NO_CHANGED_FILES).length : 0))
  // [chat-switch-stream-align] 当前渲染会话 ref（store.subscribe 回调需读最新，闭包不捕获过期值）
  const sessionIdRef = useRef(sessionId)
  sessionIdRef.current = sessionId
  /** 本会话流式活动节拍缓存（-1 = 尚未见过首帧；见 streamTicks 说明）。 */
  const lastStreamTickRef = useRef(-1)
  const streamWrapRef = useRef<HTMLDivElement>(null)
  // [本轮消耗] 会话模型名（TurnFooter 行「本轮/会话模型名」的回退源；实时轮优先取 modelUsage 增量键）
  const sessionModelName = useChatStore((s) => (sessionId ? (s.sessions.find((x) => x.id === sessionId)?.modelName ?? null) : null))
  // [window-paging] hasMore（会话有更早历史）→ 顶部「加载更多」按钮；App onLoadOlder 拉前页 prependMessages。
  //   同时用于本轮金额判定：窗口外还有更早轮次时，首轮的「会话累计花费」不能当本轮金额。
  const hasMore = useChatStore((s) => (sessionId ? s.hasMore[sessionId] : undefined))
  const lastMsgId = messages[messages.length - 1]?.id
  // [任务结束入口] 最后一条「会渲染」的 assistant 消息：过滤判据与渲染同源（isDialogueRow），
  //   避免把入口挂到 isMeta/不可见行上（那样结构上永远看不到）。
  const lastAssistantId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i]!
      if (m.role === 'assistant' && isDialogueRow(m)) return m.id
    }
    return null
  }, [messages])
  // 本轮结束判据：不在「思考中」占位、且无流式块。两者合计覆盖 turnRunning 的本地/服务端信号
  //   （服务端运行态无 chunk 时 thinking=true；有 chunk 时 streamIds 非空）。
  const turnEnded = !thinking && streamIds.length === 0
  /** [本轮消耗] 按轮聚合：本轮 input/output tokens 合计 + 会话累计花费/按模型花费快照 + 该轮最后一条 assistant。
   *  轮键与 groups/turnDurationByKey 同口径（最近一条 user 消息 id）。
   *  ⚠ 花费快照（totalCostUsd / modelUsage）是**会话累计**（complete 事件口径），故本轮金额 = 与上一轮差分；
   *  差分不可靠（无上轮快照 / 窗口外还有更早历史 / 有压缩裁剪边界）时回落 tokens。 */
  const turnUsage = useMemo(() => {
    type Acc = {
      input: number
      output: number
      hasInput: boolean
      hasOutput: boolean
      lastAssistant: ChatMessageDto | null
      costYuan: number | null
      /** 按模型**会话累计**花费快照（同一轮各段携带同一 complete 快照 → 覆盖而非相加） */
      modelCum: Record<string, number>
      /** [历史轮] 后端按轮聚合的**本轮**金额（不是会话累计 ⇒ 不走差分，直接展示） */
      directCostYuan: number | null
      /** [历史轮] 后端按轮聚合的模型短名 */
      directModel: string | null
    }
    const byKey = new Map<string, Acc>()
    let turnId: string | null = null
    let prevKey: string | null = null
    let lastAssistantKey: string | null = null
    for (const m of messages) {
      if (!isDialogueRow(m)) continue
      const isUserRow = isUserTurnRow(m)
      if (isUserRow) turnId = m.id
      // [usage 行口径] 与 groups / capTailTurns 同一轮键（messageTurns.turnKeyOf）
      const key: string = turnKeyOf(m, turnId ?? prevKey)
      prevKey = key
      let acc = byKey.get(key)
      if (!acc) {
        acc = { input: 0, output: 0, hasInput: false, hasOutput: false, lastAssistant: null, costYuan: null, modelCum: {}, directCostYuan: null, directModel: null }
        byKey.set(key, acc)
      }
      if (m.role !== 'assistant') continue
      acc.lastAssistant = m
      lastAssistantKey = key
      // 本轮 tokens：真实 usage 优先，回落 legacy inputTokens/outputTokens（DB 重拉态）
      const inTok = m.usage?.input_tokens ?? m.inputTokens ?? null
      const outTok = m.usage?.output_tokens ?? m.outputTokens ?? null
      if (inTok != null) { acc.input += inTok; acc.hasInput = true }
      if (outTok != null) { acc.output += outTok; acc.hasOutput = true }
      if (m.totalCostUsd != null) acc.costYuan = m.totalCostUsd
      if (m.modelUsage) {
        const snapshot: Record<string, number> = {}
        for (const [name, u] of Object.entries(m.modelUsage)) snapshot[name] = u?.costUSD ?? 0
        acc.modelCum = snapshot
      }
    }
    // [历史轮补用量] 旧轮消息在 DB 里没有 token（真实用量在 llm_usage_calls）→
    //   只给「消息级完全没 token」的轮补后端按轮聚合值；有真值的轮不覆盖（避免与 complete 事件实时值打架）。
    if (turnUsageFallback && turnUsageFallback.size > 0) {
      for (const [key, acc] of byKey) {
        if (acc.hasInput || acc.hasOutput) continue
        const fb = turnUsageFallback.get(key)
        if (!fb) continue
        if (fb.inputTokens != null) { acc.input += fb.inputTokens; acc.hasInput = true }
        if (fb.outputTokens != null) { acc.output += fb.outputTokens; acc.hasOutput = true }
        if (acc.costYuan == null && fb.costYuan != null) acc.directCostYuan = fb.costYuan
        if (fb.model) acc.directModel = shortModelName(fb.model)
      }
    }
    return { byKey, lastAssistantKey }
  }, [messages, turnUsageFallback])
  /** [本轮消耗] 每轮 → TurnFooter 一行文案（见 formatTurnUsageLabel）。 */
  const usageLabelByTurn = useMemo(() => {
    const labels = new Map<string, string>()
    const entries = [...turnUsage.byKey.entries()]
    const firstKey = entries[0]?.[0] ?? null
    // 窗口里有压缩/裁剪边界 ⇒ 首轮的「会话累计」含窗口外花费，不能当本轮金额
    const hasBoundary = messages.some((m) => m.subtype === 'compact_boundary'
      || m.subtype === 'microcompact_boundary' || m.subtype === 'snip_boundary')
    let prevCost: number | null = null
    let prevModels: Record<string, number> = {}
    for (const [key, acc] of entries) {
      const cost = acc.costYuan != null && acc.costYuan > 0 ? acc.costYuan : null
      let costYuan: number | null = null
      if (acc.directCostYuan != null) {
        // [历史轮] 后端按轮聚合的金额本身就是本轮值（不是会话累计）→ 直接展示，不做差分
        costYuan = acc.directCostYuan > 0 ? acc.directCostYuan : null
      } else if (cost != null) {
        if (prevCost != null && cost >= prevCost) costYuan = cost - prevCost
        // 窗口内第一轮且无更早历史/边界：会话从 0 起算 → 累计即本轮
        else if (prevCost == null && key === firstKey && !hasMore && !hasBoundary) costYuan = cost
      }
      // 模型：历史轮用后端按轮聚合的模型；否则优先本轮花费有增量的模型（= 本轮实际调用的模型）；最后回落会话模型名
      let model: string | null = acc.directModel
      if (model == null) {
        if (cost != null) {
          const changed = Object.entries(acc.modelCum)
            .filter(([name, c]) => c > (prevModels[name] ?? 0) + 1e-9)
            .map(([name]) => shortModelName(name))
            .filter((v): v is string => v != null)
          model = changed.length > 0 ? changed.join(' / ') : shortModelName(sessionModelName)
        } else {
          model = shortModelName(sessionModelName)
        }
      }
      const label = formatTurnUsageLabel({
        costYuan,
        inputTokens: acc.hasInput ? acc.input : null,
        outputTokens: acc.hasOutput ? acc.output : null,
        model,
        time: formatTurnStamp(acc.lastAssistant),
      })
      if (label) labels.set(key, label)
      prevCost = cost
      if (Object.keys(acc.modelCum).length > 0) prevModels = acc.modelCum
    }
    return labels
  }, [turnUsage, messages, hasMore, sessionModelName])
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
  /** 贴底收敛：首帧写完 scrollTop 后，异步内容（图片 / 公式 / 字体 / markdown 增量）还会把高度撑大，
   *  一次性贴底会停在「差一截」的位置（用户报的「切会话后不是停在最底部」）。
   *  接下来几帧 + 一次短延时内重复贴底；全程只在用户仍贴底（nearBottom 未被滚动翻假）时继续，
   *  ⛔ 绝不把正在上翻查历史的用户拽回底部。 */
  const stickBottomSettled = () => {
    stickBottom()
    let frames = 0
    const tick = () => {
      if (!nearBottomRef.current) return
      stickBottom()
      if (++frames < 4) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
    window.setTimeout(() => { if (nearBottomRef.current) stickBottom() }, 120)
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
      nearBottomRef.current = true
      stickBottomSettled()
    } else if (nearBottomRef.current) {
      stickBottomSettled()
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
      nearBottomRef.current = true
      stickBottomSettled()
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
    /**
     * [2026-10-22 结构对齐 dsh · anchorSeq 排序] 每个渲染项带单调 anchorSeq，渲染时**统一按序号排序**，
     * 不再让「组首次出现的位置」决定行序（dsh chat-snapshot-builder.ts:135 orderedVisible 同款）。
     *
     * <p>WHY：原实现按 userMessageId 建组、组序 = key 首次出现位置。流式块冻结的 userMessageId 一旦
     * 指向上一轮（本轮 user 尚未落历史 / 后台通知唤醒），整块就被插进那段历史中间 —— 用户实测
     * 「实时打字插在历史工具消息之前，后面又冒出更晚的历史」。
     * 序号规则：历史 = 过滤后序列的递增序号；**流式区**（实时块 + 本轮中途追加的行，如排队消息被
     * 消费时的正式 user 行）= LIVE_SEQ_BASE + 到达号（由 chatStore 在追加/建块时盖章）——
     * 于是「已输出的实时内容 < 中途追加的 user 行 < 之后的实时块」，与 dsh 事件 seq 同序；
     * err 卡片 = 所属组最大序号 + 0.5（紧跟该组）。prepend 更早历史后序号重算，相对顺序天然正确。
     */
    type RowItem = { kind: 'msg'; m: ChatMessageDto } | { kind: 'blk'; sid: string; blockId: string }
    const arr: { key: string; items: { item: RowItem; seq: number }[]; maxSeq: number }[] = []
    const order = new Map<string, number>()
    const push = (key: string, item: RowItem, seq: number) => {
      let idx = order.get(key)
      if (idx === undefined) { idx = arr.length; order.set(key, idx); arr.push({ key, items: [], maxSeq: seq }) }
      arr[idx].items.push({ item, seq })
      if (seq > arr[idx].maxSeq) arr[idx].maxSeq = seq
    }
    /** 流式区序号基准（见 chatStore.LIVE_SEQ_BASE）：远大于历史位次 ⇒ 流式区恒在已落库内容之后。 */
    // [brief-align] 含 SendUserMessage 的轮次里丢掉冗余助手正文 · 对齐 CC components/Messages.tsx:169-206
    //   dropTextInBriefTurns（transcript 模式绕过：本仓轨迹 tab 直读 store，不经本函数 —— 同 CC）。
    // [sm-boundary-reload] 轮键在序列上前向解析（与 capTailTurns 前向趟同源）：无锚行（compact boundary
    //   等）归组到【最近前置有锚行】的键 —— 否则 boundary 自成一键、被排到本轮流式块之后（分割线错位）。
    // [轮键 = 最近一条 user 消息的 id] 从一条 user 消息起，到下一个 user 消息之前的所有
    //   assistant / 工具 / 思考 / tool_use_summary 段都属于同一轮；段自身的 userMessageId 缺失或
    //   指向别处都不改变归属（同一轮两个 N nexus 头 / 完成后像新开回复的根因）。
    //   过滤判据仍取 stores/messageTurns（唯一真源），只有「键怎么算」在此按用户裁定收紧。
    let turnId: string | null = null
    /** 最后一条可见消息行的轮键：窗口里**没有 user 提问行**（已滑出加载窗口）时，
     *  它就是「当前轮」的唯一线索 —— 流式块要落在这里，否则会自成一键再出一个头。 */
    let lastMsgTurnKey: string | null = null
    let historySeq = 0
    for (const m of dropTextInBriefTurns(messages)) {
      if (!isDialogueRow(m)) continue
      // 只有真正的用户提问行才开新轮（tool_use_summary / 压缩摘要这类 role=user 的系统产物不开）
      if (isUserTurnRow(m)) turnId = m.id
      // 序号 = 过滤后序列的递增位次（prepend 更早历史后重算，相对顺序不变）；
      //   流式区到达号优先（本轮中途追加的排队 user 行 / 已 finalize 的块行）
      // 轮键单点 = messageTurns.turnKeyOf（当前轮优先；段自身 userMessageId 指向后台/系统消息时不采信）
      const key: string = turnKeyOf(m, turnId ?? lastMsgTurnKey)
      lastMsgTurnKey = key
      push(key, { kind: 'msg', m }, m.renderSeq ?? ++historySeq)
    }
    // streaming 块归属：用【冻结】的块 userMessageId（首 chunk 建立时确定，对应后端 DB 落库逐条推进
    //   的「位置」语义 —— 用户1 任务轮归用户1、排队 append 后的轮归排队）。冻结保证不被排队 append
    //   后到达的 chunk 覆盖。缺失（旧块/未带）回落 assistantMessageId 独立流。
    //   行条目只带 (sid, blockId) —— 具体内容由每行 StreamBlockRow 自订 selectStreamBlock。
    const { ids: userTurnIds, latest: latestUserTurnId } = collectUserTurnIds(messages)
    // 兜底顺序：最近一条 user 提问行 → 最后一条可见消息行所属轮（窗口内已无提问行时）
    const liveTurnFallback = latestUserTurnId ?? lastMsgTurnKey
    // [2026-10-29] 块 id 去重（双保险）：store 已修「任意位置幂等」，但历史内存态/异常路径可能仍带重复 id。
    //   React key = blockId，重复 ⇒ 两行同 key ⇒ 折叠状态被反复重建（思考/所有工具都点不开）。
    const seenBlockIds = new Set<string>()
    for (let i = 0; i < streamIds.length; i++) {
      const blockId = streamIds[i]
      if (seenBlockIds.has(blockId)) continue
      seenBlockIds.add(blockId)
      const b = live.find((x) => x.assistantMessageId === blockId)
      if (!b) continue
      // [轮键] 与落库 assistant 段同口径（见 blockTurnKey）：冻结 id 认不出 ⇒ 归当前轮
      const key = blockTurnKey(b.userMessageId, userTurnIds, liveTurnFallback, b.assistantMessageId ?? 'stream')
      // [anchorSeq] 块 = 建立时盖的流式区到达号（缺省旧块 → 基准 + 块序），恒大于所有历史
      push(key, { kind: 'blk', sid: sessionId, blockId }, b.renderSeq ?? LIVE_SEQ_BASE + i)
    }
    return arr
  }, [messages, streamIds, sessionId])
  // ---- [轮分页] 内存里是已加载窗口（最近 3 轮 + 「加载更多」prepend 的更早轮），**全部渲染**。----
  // 线性渲染行（保时间序：组内顺序即全局消息序；组尾 err 卡片跟随其组）
  const flatRows = useMemo(() => {
    // [anchorSeq 排序] 收集全部行（含 anchorSeq）→ 统一排序（相等序号按 key 兜底，排序稳定）。
    //   组结构仅保留 err 卡片归属语义，**不再决定行序**（对齐 dsh orderedVisible）。
    const rows: (
      | { key: string; kind: 'msg'; m: ChatMessageDto; seq: number; turnKey: string }
      | { key: string; kind: 'blk'; sid: string; blockId: string; seq: number; turnKey: string }
      | { key: string; kind: 'err'; err: ApiFlowError; seq: number; turnKey: string }
    )[] = []
    for (const g of groups) {
      for (const it of g.items) {
        rows.push(it.item.kind === 'msg'
          ? { key: rowKey(it.item.m.id), kind: 'msg', m: it.item.m, seq: it.seq, turnKey: g.key }
          : { key: it.item.blockId, kind: 'blk', sid: it.item.sid, blockId: it.item.blockId, seq: it.seq, turnKey: g.key })
      }
      const gErr = apiErrorMap.get(g.key)
      // err 卡片紧跟所属组（组内最大序号 + 0.5，不会越过下一组）
      if (gErr) rows.push({ key: `err:${g.key}`, kind: 'err', err: gErr, seq: g.maxSeq + 0.5, turnKey: g.key })
    }
    rows.sort((a, b) => (a.seq - b.seq) || a.key.localeCompare(b.key))
    return rows
  }, [groups, apiErrorMap, rowKey])
  // 【贴底·切会话】reset 的判据是【会话身份】（sessionId + conversationId）——
  //   只有真正切换会话 / partial 压缩·裁剪旋转 conversationId 才重置回最新（截图 / 打开会话停在最底部）。
  //   prepend 不改变身份，故「加载更多」不会触发贴底（⛔ 别再改回 [messages[0]?.id]：prepend 必然
  //   改变首条 id，会把「向上翻历史」误判成「切会话」→ 直接跳到底）。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    nearBottomRef.current = true
    onNearBottomChangeRef.current?.(true)
    // [轮分页] 换会话 / 压缩裁剪旋转 conversationId → 回到最新（App 重新拉最近 3 轮）
    //   收敛贴底：消息是异步到的，且图片/公式/字体落地后高度还会再长一截。
    const t = window.setTimeout(() => stickBottomSettled(), 0)
    return () => window.clearTimeout(t)
  }, [sessionId, conversationId])
  // ---- [轮分页·累积式] 渲染行 = flatRows 全量（已加载的轮一律渲染，⚠️ 不做任何轮数裁剪/卸载）----
  //   分页边界（最近 3 轮 → 更早轮 prepend）由 App/后端负责；本组件只负责把它们全部画出来。
  /** [上翻加载 · 锚点行] prepend 是累积式：更早 2 轮插到最前面，上方新增高度会把视口内容整体顶下去。
   *  加载前记录「视口内第一条可见消息」的 id + 屏幕位置，DOM 提交后按**同一行**的位移差补 scrollTop
   *  —— ⛔ 不用 scrollHeight 差值（图片/字体/折叠态高度会让差值失真）。
   *  identity 记会话：切走会话后作废旧锚点（切会话自己的贴底路径不受影响）。 */
  const prependAnchorRef = useRef<{ identity: string; id: string; top: number } | null>(null)
  /** 收敛补偿的 rAF 句柄：首帧补完后新行高度可能再变（图片 / 字体 / 折叠态），
   *  在 4 个 rAF 内逐帧按**同一锚点行**继续纠偏。 */
  const prependRafRef = useRef<number | null>(null)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    const anchor = prependAnchorRef.current
    if (!anchor) return
    prependAnchorRef.current = null
    // 切了会话/轮次旋转 → 旧锚点无意义（该路径由下面的 [sessionId, conversationId] 贴底）
    if (anchor.identity !== `${sessionId}\u0000${conversationId ?? ''}`) return
    const box = scrollElRef.current
    if (!box) return
    /** 读锚点行**实时**位置并按差值写 scrollTop（⛔ 不用 scrollHeight 差值：离屏行高度未定会多补）。
     *  返回 false = 锚点行已不在 DOM（会话已切走/行被卸载）→ 停止收敛。 */
    const apply = (): boolean => {
      const el = box.querySelector<HTMLElement>(`[data-msg-id="${anchor.id}"]`)
      if (!el) return false
      const delta = el.getBoundingClientRect().top - anchor.top
      // 只在有真实位移时补：delta=0（jsdom/无布局）时保持用户当前位置
      if (delta !== 0) box.scrollTop += delta
      return true
    }
    apply()
    // 4 个 rAF 内逐帧收敛（首帧补偿后高度再变时继续纠偏）；同一时刻只保留一条收敛链
    if (prependRafRef.current != null) cancelAnimationFrame(prependRafRef.current)
    let frames = 1
    const tick = () => {
      prependRafRef.current = null
      // 又有新的「加载更多」→ 旧链作废，交给新锚点
      if (prependAnchorRef.current != null) return
      if (!apply()) return
      if (++frames < 4) prependRafRef.current = requestAnimationFrame(tick)
    }
    prependRafRef.current = requestAnimationFrame(tick)
    return () => {
      if (prependRafRef.current != null) {
        cancelAnimationFrame(prependRafRef.current)
        prependRafRef.current = null
      }
    }
  }, [flatRows])
  // [轮分页] 顶部「加载更多」按钮（唯一加载体：⛔ 滚动/触顶都不触发加载）：
  //   点击 = 网络再拉更早 2 轮（App.onLoadOlder → GET /messages/turns?turns=2&beforeTurnId=最早轮），
  //   拿到后 prepend 进 store ⇒ flatRows 自动变多（累积，不丢已加载轮）。
  const [loadingOlder, setLoadingOlder] = useState(false)
  // ---- [折叠显示] 已展开的轮键（本地 UI 态，**不持久化**）----
  //   · 点击折叠行 → 把被折叠的那些轮键记进来 ⇒ 它们渲染出来、折叠行消失；
  //   · 「加载更多」新拉到的轮键也记进来 ⇒ 用户主动要的更早轮**直接展开**、不折回去；
  //   · 切会话 / conversationId 旋转（partial 压缩）→ 清空（F5 后本来也是空 ⇒ 回到默认窗口）。
  const [revealedFoldKeys, setRevealedFoldKeys] = useState<ReadonlySet<string>>(EMPTY_FOLD_KEYS)
  useEffect(() => { setRevealedFoldKeys(EMPTY_FOLD_KEYS) }, [sessionId, conversationId])
  /** [轮分页] 已加载的最早一条消息日期（「X月X日」）：到底时显示「已加载到会话开始 · 日期」，
   *  给累积列表一个绝对位置锚（与「加载更多」按钮互斥）。 */
  const topBoundaryDate = useMemo(() => {
    const first = flatRows.find((r) => r.kind === 'msg')
    if (!first || first.kind !== 'msg') return null
    const createdAt = first.m.createdAt
    if (!createdAt) return null
    const d = new Date(createdAt)
    if (Number.isNaN(d.getTime())) return null
    return `${d.getMonth() + 1}月${d.getDate()}日`
  }, [flatRows])
  const loadOlderClick = async () => {
    if (!sessionId || loadingOlder) return
    if (!hasMore || !onLoadOlder) return
    const sid = sessionId
    const identity = `${sessionId}\u0000${conversationId ?? ''}`
    // [锚点行] 加载前记录视口内第一条可见消息（id + 屏幕位置）：prepend 后由 useLayoutEffect 按位移差补 scrollTop。
    const box = scrollElRef.current
    if (box) {
      const boxTop = box.getBoundingClientRect().top
      for (const el of Array.from(box.querySelectorAll<HTMLElement>('[data-msg-id]'))) {
        const r = el.getBoundingClientRect()
        const id = el.dataset.msgId
        if (id && r.bottom > boxTop) { prependAnchorRef.current = { identity, id, top: r.top }; break }
      }
    }
    setLoadingOlder(true)
    const beforeMsgs = useChatStore.getState().messages[sid] ?? []
    const beforeCount = beforeMsgs.length
    const beforeKeys = new Set(orderedTurnKeys(beforeMsgs))
    try {
      await onLoadOlder(sid)
    } finally {
      setLoadingOlder(false)
    }
    // 会话已切走 → 本次结果属于旧会话，锚点补偿作废（切会话路径自己贴底）
    if (sessionIdRef.current !== sid) { prependAnchorRef.current = null; return }
    // [折叠显示] 本次新拉到的轮**直接展开**（用户主动要的）：记进 revealed，不折回去。
    const addedKeys = orderedTurnKeys(useChatStore.getState().messages[sid] ?? []).filter((k) => !beforeKeys.has(k))
    if (addedKeys.length > 0) {
      setRevealedFoldKeys((prev) => {
        const next = new Set(prev)
        for (const k of addedKeys) next.add(k)
        return next
      })
    }
    // 拉取失败/无增量（长度没变）→ 锚点不会再有位移，作废（免得下次别的原因改 flatRows 时误补）
    const afterCount = useChatStore.getState().messages[sid]?.length ?? 0
    if (afterCount === beforeCount) { prependAnchorRef.current = null; return }
    // ⛔ 上翻加载后不得贴底（用户正在往上翻）
    nearBottomRef.current = false
    onNearBottomChangeRef.current?.(false)
  }
  // ---- [历史提问跳转] 定位 + 短暂高亮 ----
  // 目标在当前窗口 → 直接滚动并高亮 1.5s；不在窗口 → 复用 onLoadOlder 逐页补拉后重试；
  // 补拉无增量 / 已到底仍找不到 → 显示「该提问不在当前窗口」（⛔ 不静默失败）。
  const [highlightedMsgId, setHighlightedMsgId] = useState<string | null>(null)
  const [jumpMiss, setJumpMiss] = useState(false)
  useEffect(() => {
    if (!jumpToMessage) return
    const targetId = jumpToMessage.id
    // 新一次跳转：先清掉上一次的高亮/提示（定时器随 cleanup 失效，不清态会残留）
    setHighlightedMsgId(null)
    setJumpMiss(false)
    let cancelled = false
    let highlightTimer = 0
    let missTimer = 0
    const clearTimers = () => {
      if (highlightTimer) window.clearTimeout(highlightTimer)
      if (missTimer) window.clearTimeout(missTimer)
    }
    const reveal = (): boolean => {
      const box = scrollElRef.current
      const el = box?.querySelector<HTMLElement>(`[data-msg-id="${targetId}"]`)
      if (!el) return false
      setJumpMiss(false)
      setHighlightedMsgId(targetId)
      if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center' })
      else if (box) box.scrollTop = Math.max(0, el.offsetTop - Math.max(0, (box.clientHeight - el.offsetHeight) / 2))
      // 跳转到历史位置后不再贴底跟随（否则流式 chunk 会把人拽回底部）
      nearBottomRef.current = false
      onNearBottomChangeRef.current?.(false)
      highlightTimer = window.setTimeout(() => {
        if (!cancelled) setHighlightedMsgId((cur) => (cur === targetId ? null : cur))
      }, 1500)
      return true
    }
    /** [折叠显示] 目标所在轮若被折叠 → 先展开「该轮及其之前的轮」（折叠恒为头部前缀）：
     *  否则目标行根本没有 DOM（`[data-msg-id]` 查不到）⇒ reveal 恒失败 ⇒ 误报「不在当前窗口」。 */
    const unfoldUpToTarget = () => {
      const msgs = useChatStore.getState().messages[sessionId] ?? []
      const keys = orderedTurnKeys(msgs)
      const key = turnKeyByRowId(msgs).get(targetId)
      const idx = key ? keys.indexOf(key) : -1
      if (idx < 0) return
      const windowStart = Math.max(0, keys.length - FOLD_DEFAULT_TURNS)
      const last = Math.min(idx, windowStart - 1)
      if (last < 0) return // 目标在默认展开窗口内 ⇒ 必然已渲染
      const prefix = keys.slice(0, last + 1)
      setRevealedFoldKeys((prev) => (prefix.every((k) => prev.has(k)) ? prev : new Set([...prev, ...prefix])))
    }
    const nextFrame = () => new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
    void (async () => {
      // [轮分页] 已加载的行全部渲染（折叠态除外 ⇒ 先展开目标之前被折叠的轮）⇒ 目标在 store 里就能查到节点。
      unfoldUpToTarget()
      await nextFrame()
      if (cancelled || reveal()) return
      // 分页外：逐页补拉（与顶部「加载更早」同一 onLoadOlder 实现，最多 20 页防空转）
      for (let page = 0; page < 20; page++) {
        if (cancelled) return
        const st = useChatStore.getState()
        if (!st.hasMore[sessionId] || !onLoadOlder) break
        const before = st.messages[sessionId]?.length ?? 0
        await onLoadOlder(sessionId)
        unfoldUpToTarget()
        await nextFrame()
        await nextFrame()
        if (cancelled) return
        if (reveal()) return
        const after = useChatStore.getState().messages[sessionId]?.length ?? 0
        if (after === before) break // 拉取无增量（失败/已到底）→ 不再空转
      }
      if (cancelled) return
      setJumpMiss(true)
      missTimer = window.setTimeout(() => { if (!cancelled) setJumpMiss(false) }, 4000)
    })()
    return () => {
      cancelled = true
      clearTimers()
    }
  }, [jumpToMessage, sessionId, onLoadOlder])
  /** [轮次消息头合并] 每轮**第一条 assistant 行**（落库消息或流式块）→ 唯一渲染消息头。
   *  按 flatRows 最终行序（anchorSeq）判定，与用户看到的「第一条」同源；同轮其余工具/正文段不再重复
   *  头像 / 名字 / 时间 / 徽标。不同轮次各有一条 ⇒ 轮次之间仍各有消息头。 */
  const firstAssistantRowKeys = useMemo(() => {
    const keys = new Set<string>()
    // [2026-10-29 同轮双头 · 终版判据] 不再按 turnKey 去重（流式块冻结 id / 落库段 userMessageId /
    //   后台注入行可能各说各话，按 turn 去重迟早漏一种组合）。改成**纯行序**判据：
    //   自最近一条「真正的用户提问」以来，只允许第一条 assistant 行显示 `N nexus` 头。
    //   遇到用户提问行即重置 —— 不同轮照常各有一个头；同一轮无论夹了多少块/段，都只会有一个。
    let headAddedSinceUser = false
    for (const row of flatRows) {
      if (row.kind === 'err') continue
      if (row.kind === 'msg' && isUserTurnRow(row.m)) {
        headAddedSinceUser = false
        continue
      }
      if (row.kind === 'msg' && row.m.role !== 'assistant') continue
      if (headAddedSinceUser) continue
      headAddedSinceUser = true
      keys.add(row.key)
    }
    return keys
  }, [flatRows])
  // ---- [轮次中间过程折叠] 折叠粒度 = 轮次（turnKey = 发起该轮的 userMessageId）----
  //   轮次进行中 → 展开（中间过程正常展示）；轮次结束 → 默认收起（历史轮次同理）。
  //   判据只用「该轮是否结束」（上方 turnEnded / 流式块归属），⛔ 不按「消息是否非流式」或 role 单独判断。
  /** 正在进行的轮次键：流式块归属优先；无块但 turn 仍在跑（等待/重试）→ 最后一条 user 消息。 */
  const activeTurnKeys = useMemo(() => {
    const keys = new Set<string>()
    const live = sessionId ? (useChatStore.getState().streams[sessionId] ?? []) : []
    // 与 groups 同一轮键口径：块冻结 id 认不出 ⇒ 归当前轮（同轮只留一个 N nexus 头）
    const { ids: userTurnIds, latest: latestUserTurnId } = collectUserTurnIds(messages)
    const lastRow = flatRows.length > 0 ? flatRows[flatRows.length - 1]! : null
    const liveTurnFallback = latestUserTurnId ?? (lastRow && lastRow.kind !== 'err' ? lastRow.turnKey : null)
    for (const blockId of streamIds) {
      const b = live.find((x) => x.assistantMessageId === blockId)
      if (!b) continue
      keys.add(blockTurnKey(b.userMessageId, userTurnIds, liveTurnFallback, b.assistantMessageId ?? 'stream'))
    }
    // 无块但 turn 仍在跑（等待/重试）：当前轮 = 最新提问行 / 最后一条可见行所属轮
    if (keys.size === 0 && !turnEnded && liveTurnFallback) keys.add(liveTurnFallback)
    return keys
  }, [streamIds, sessionId, messages, turnEnded, flatRows])
  /** [轮次消息头合并] 进行中轮次是否已有 assistant 行（消息或流式块）：有则末尾「思考中…」占位
   *  不再重复消息头（同轮只留首段那一个）。 */
  const activeTurnHasAssistantRow = useMemo(
    () => flatRows.some((row) => row.kind !== 'err' && activeTurnKeys.has(row.turnKey)
      && (row.kind === 'blk' || row.m.role === 'assistant')),
    [flatRows, activeTurnKeys],
  )
  // ---- [折叠显示 · 2026-10-29 用户裁定] 比默认窗口更早的已加载轮 → 折叠成一行 ----
  //   判据三段（与轮分组同一前向趟 orderedTurnKeys，切点绝不落在一轮中间）：
  //   ① 只取**头部连续前缀**：遇到「已展开 / 进行中」的轮即停 —— 保证折叠行（画在最上面）与折叠内容
  //      是同一段，不会与已展开的早轮交错；
  //   ② 前缀范围 = 前 N 轮之外（N = FOLD_DEFAULT_TURNS，与首屏 3 轮一致）；
  //   ③ 进行中的轮（activeTurnKeys）**绝不折叠** —— 正在流式输出的轮必须有 DOM。
  //   revealedFoldKeys = 点过「展开」的轮 + 「加载更多」新拉到的轮（直接展开，不折回去）。
  const foldedTurnKeys = useMemo(() => {
    const keys = orderedTurnKeys(messages)
    const windowStart = Math.max(0, keys.length - FOLD_DEFAULT_TURNS)
    const folded: string[] = []
    for (let i = 0; i < windowStart; i++) {
      const k = keys[i]!
      if (revealedFoldKeys.has(k) || activeTurnKeys.has(k)) break
      folded.push(k)
    }
    return folded
  }, [messages, revealedFoldKeys, activeTurnKeys])
  const foldedSet = useMemo(() => new Set(foldedTurnKeys), [foldedTurnKeys])
  /** 折叠区内的轮**不渲染 DOM**（既留数据又不占渲染）；点击折叠行后它们才出现。 */
  const visibleRows = useMemo(
    () => (foldedSet.size === 0 ? flatRows : flatRows.filter((r) => !foldedSet.has(r.turnKey))),
    [flatRows, foldedSet],
  )
  const expandFoldedTurns = useCallback(() => {
    setRevealedFoldKeys((prev) => new Set([...prev, ...foldedTurnKeys]))
  }, [foldedTurnKeys])
  /** [TurnFooter 逐轮] 每轮**最后一条 assistant 行** → footer 节点：
   *  · 已结束轮次：一行「共消耗 ◇ 金额 · 模型 · 时间」（左栏入口只在会话最后一条 assistant 上显示）；
   *  · 进行中轮次：不挂（内容未定稿，用量行等该轮结束再出现）。
   *  节点按 (文案/链接/计数) 缓存 → 消息增长时历史轮 footer 引用不变，不击穿行级 memo。 */
  const footerCacheRef = useRef(new Map<string, { key: string; node: ReactNode }>())
  /** 产物指纹（id/大小/路径/标题）：内容变了才重建卡片节点，否则历史轮 footer 引用保持稳定 */
  const deliverables = artifacts?.deliverables ?? NO_ARTIFACTS
  const artifactsSig = useMemo(
    () => deliverables.map((d) => `${d.id}\u0001${d.sizeBytes ?? ''}\u0001${d.localPath ?? ''}\u0001${d.title}`).join('\u0000'),
    [deliverables],
  )
  const footerByRowKey = useMemo(() => {
    const lastAssistantRowByTurn = new Map<string, { key: string; msgId: string }>()
    for (const row of flatRows) {
      if (row.kind !== 'msg' || row.m.role !== 'assistant') continue
      lastAssistantRowByTurn.set(row.turnKey, { key: row.key, msgId: row.m.id })
    }
    const prevCache = footerCacheRef.current
    const nextCache = new Map<string, { key: string; node: ReactNode }>()
    const result = new Map<string, ReactNode>()
    for (const [turnKey, row] of lastAssistantRowByTurn) {
      if (activeTurnKeys.has(turnKey)) continue
      const usageLabel = usageLabelByTurn.get(turnKey) ?? null
      const withLinks = row.msgId === lastAssistantId && turnEnded && onOpenRightTab != null
      // [产物卡片] 只挂在「会话最后一条 assistant」的已结束轮上，位于 TurnFooter 之前
      const withArtifacts = row.msgId === lastAssistantId && turnEnded && deliverables.length > 0
      if (!usageLabel && !withLinks && !withArtifacts) continue
      const cacheKey = `${usageLabel ?? ''}\u0000${withLinks ? 1 : 0}\u0000${artifactCount}\u0000${changeCount}`
        + `\u0000${withArtifacts ? artifactsSig : ''}`
      const cached = prevCache.get(row.key)
      const node = cached && cached.key === cacheKey
        ? cached.node
        : (
          <>
            {withArtifacts && <TurnArtifacts items={deliverables} onOpen={onOpenRefFile} />}
            {(usageLabel || withLinks) && (
              <TurnFooter artifactCount={artifactCount} changeCount={changeCount}
                onOpen={withLinks ? onOpenRightTab : undefined} usageLabel={usageLabel} />
            )}
          </>
        )
      nextCache.set(row.key, { key: cacheKey, node })
      result.set(row.key, node)
    }
    footerCacheRef.current = nextCache
    return result
  }, [flatRows, activeTurnKeys, usageLabelByTurn, lastAssistantId, turnEnded, artifactCount, changeCount,
      onOpenRightTab, deliverables, artifactsSig, onOpenRefFile])
  /** 用户手动展开/收起的历史轮次（缺省 = 已结束轮次收起、进行中轮次展开）。 */
  const [processOpenByTurn, setProcessOpenByTurn] = useState<Record<string, boolean>>({})
  const toggleTurnProcess = useCallback((key: string, open: boolean) => {
    setProcessOpenByTurn((prev) => ({ ...prev, [key]: !open }))
  }, [])
  useEffect(() => { setProcessOpenByTurn({}) }, [sessionId])
  /** 每轮第一条「含中间过程」的 assistant 消息 → 该轮唯一的「已完成」开关落点。 */
  const firstIntermediateByTurn = useMemo(() => {
    const map = new Map<string, string>()
    for (const row of flatRows) {
      if (row.kind !== 'msg' || row.m.role !== 'assistant') continue
      if (map.has(row.turnKey)) continue
      const hasInter = cleanReasoning(row.m.reasoning) !== ''
        || (row.m.toolCalls ?? []).some((t) => !isSendUserMessage(t))
      if (hasInter) map.set(row.turnKey, row.m.id)
    }
    return map
  }, [flatRows])
  /** [并行工具组] 同轮 `tool_use_summary` → 该轮**首个**多工具消息的工具组父行标题。
   *  同时记下被「消费」的那条摘要行 id：该轮展开时不再单独渲染它（摘要不显示两遍）；
   *  轮次收起时摘要行照常显示（组在折叠区里不可见，摘要是唯一线索）。 */
  const toolGroupMeta = useMemo(() => {
    const titleByMsgId = new Map<string, string>()
    const summaryIdByTurn = new Map<string, string>()
    const summaryByTurn = new Map<string, { id: string; text: string }>()
    const ownerByTurn = new Map<string, string>()
    /** 每轮首个工具名 → tool_use_summary 行的类型图标（Bash/命令类要显示终端图标，⛔ 通用扳手） */
    const toolNameByTurn = new Map<string, string>()
    let turnId: string | null = null
    let prevKey: string | null = null
    for (const m of messages) {
      if (!isDialogueRow(m)) continue
      // 与 groups 同一轮键口径（messageTurns.turnKeyOf）
      if (isUserTurnRow(m)) turnId = m.id
      const key: string = turnKeyOf(m, turnId ?? prevKey)
      prevKey = key
      if (m.author === 'attachment' && m.subtype === 'tool_use_summary') {
        const text = m.content?.trim() ?? ''
        if (text && !summaryByTurn.has(key)) summaryByTurn.set(key, { id: m.id, text })
        continue
      }
      if (m.role !== 'assistant') continue
      const tools = (m.toolCalls ?? []).filter((t) => !isSendUserMessage(t))
      if (tools.length > 0 && !toolNameByTurn.has(key)) toolNameByTurn.set(key, tools[0]!.name ?? '')
      if (tools.length > 1 && !ownerByTurn.has(key)) ownerByTurn.set(key, m.id)
    }
    for (const [key, ownerId] of ownerByTurn) {
      const sum = summaryByTurn.get(key)
      if (!sum) continue
      titleByMsgId.set(ownerId, sum.text)
      summaryIdByTurn.set(key, sum.id)
    }
    return { titleByMsgId, summaryIdByTurn, toolNameByTurn }
  }, [messages])
  /** 整轮墙钟时长（优先后端 F34 `turnDurationMs`；否则用「发起用户消息 → 本轮最后一条 assistant」
   *  的 createdAt 差值近似）。后端当前不产出 F34 ⇒ 常态走时间戳；解析失败/倒序 → 无时长（只显示「已完成」）。 */
  const turnDurationByKey = useMemo(() => {
    const out = new Map<string, number>()
    const start = new Map<string, number>()
    const end = new Map<string, number>()
    // 轮键与 groups 同口径（最近一条 user 消息的 id）：F34 时长按同一键归位，时间戳近似也按同一趟扫描。
    let turnId: string | null = null
    let prevKey: string | null = null
    for (const m of messages) {
      if (!isDialogueRow(m)) continue
      const isUserRow = isUserTurnRow(m)
      if (isUserRow) turnId = m.id
      const key: string = turnKeyOf(m, turnId ?? prevKey)
      prevKey = key
      // `turnDurationMs` 由后端 F34 出站（DTO 字段与渲染批次分离落地，故按可选字段读取）
      const dur = (m as ChatMessageDto & { turnDurationMs?: number | null }).turnDurationMs
      if (dur != null && dur > 0) out.set(key, dur)
      if (!m.createdAt) continue
      const t = Date.parse(m.createdAt)
      if (Number.isNaN(t)) continue
      if (isUserRow) {
        const prev = start.get(key)
        start.set(key, prev == null ? t : Math.min(prev, t))
      } else if (m.role === 'assistant') {
        const prev = end.get(key)
        end.set(key, prev == null ? t : Math.max(prev, t))
      }
    }
    for (const [key, s] of start) {
      if (out.has(key)) continue
      const e = end.get(key)
      if (e != null && e >= s) out.set(key, e - s)
    }
    return out
  }, [messages])
  // 空态（置于所有 hooks 之后 · React 19 hooks 规则：hooks 前不得条件 return）
  if (messages.length === 0 && streamIds.length === 0) {
    return null
  }
  // 流式尾块 id（给行打「streaming」尾态 class · 只在块增删时变化）
  const lastStreamId = streamIds.length > 0 ? streamIds[streamIds.length - 1] : undefined
  return (
    <>
    <div className="stream-inner" ref={streamWrapRef}>
      {/* [OBS2] 消息区整层边界：兜住「按 flow 组装本区域的 JSX 本身出错」这类容器级异常
          （行级边界在下面 flatRows.map 里，管「某一条消息毒」）。两层都渲染不出额外 DOM（无错时
          边界直接返回 children），所以既不改变布局也不影响 streamWrapRef 的滚动绑定。
          ⛔ 防御性兜底，不是本批根因修复（根因是 STOMP 通道无心跳、半开连接不可察）。 */}
      <ErrorBoundary
        tag="MessageList.area"
        title="消息区显示出错了"
        hint="输入框仍可使用。点「重试」重新渲染消息区。"
      >
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
          box-shadow: 0 1px 3px rgba(20,24,31,.08), 0 1px 2px rgba(20,24,31,.04);
          transition: box-shadow .15s ease;
        }
        .user-attach-file:hover { box-shadow: 0 6px 16px rgba(20,24,31,.14), 0 2px 6px rgba(20,24,31,.08); }
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
        /* 删除按钮醒目：橙边框/文字 + 淡橙底（危险删除语义 · 对齐项目 accent #0f5fd4） */
        .msg .msg-hover-actions .delete-btn {
          border-color: #0f5fd4;
          color: #0f5fd4;
          background: #eaf2fe;
        }
        .msg .msg-hover-actions .delete-btn:hover { background: #0f5fd4; color: #fff; }
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
        /* SendUserMessage（CC BriefTool）正文块：无工具边框/无折叠，读起来就是 Claude 说的话
           （对齐 CC tools/BriefTool/UI.tsx:55-68 默认分支：no tool chrome / no gutter mark） */
        .sum-body { display: block; }
        .sum-attachments { margin-top: 4px; }
        .sum-att {
          display: flex;
          align-items: baseline;
          gap: 4px;
          font-size: 12.5px;
          line-height: 1.7;
          color: var(--ink-muted, #888);
        }
        .sum-att-kind { flex-shrink: 0; opacity: 0.85; }
        /* 路径可能很长：截断显示，完整值在 title（对齐 CC getDisplayPath 单行展示语义） */
        .sum-att-path {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          max-width: 520px;
        }
        .sum-att-size { flex-shrink: 0; opacity: 0.75; }
        /* SendUserMessage 失败态：红字一行（对齐 CC FallbackToolUseErrorMessage.tsx:54 「color='error'」
           + :75-78 的 <Text color="error">）——绝不渲染投递正文/附件，避免失败被当成投递成功 */
        .sum-error {
          color: var(--error);
          font-size: 13px;
          line-height: 1.6;
          white-space: pre-wrap;
          word-break: break-word;
        }
        /* ===== 通用消息 UI（本批从场景分支搬入；样式原在 globals.css，按「本批只改本文件」内联于此） ===== */
        /* [轮次消息头合并] 同轮非首段的头像占位：宽度与头像一致，正文/工具行仍与首段左对齐 */
        .msg .avatar-gutter { width: 30px; flex-shrink: 0; }
        /* [同轮段间距] 同一轮内连续 assistant 段（后段带 .turn-cont）：24px → 6px。
           只收窄「后面紧跟同轮段」的那一条 ⇒ 轮次之间、用户↔助手之间仍是 24px。 */
        .stream-inner > .msg.assistant:has(+ .msg.assistant.turn-cont) { margin-bottom: 6px; }
        /* [轮次中间过程折叠] 已结束轮次顶部的收起开关行（状态文案 + 时长 + 箭头） */
        .turn-process-toggle {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          margin: 2px 0 8px;
          padding: 0;
          border: 0;
          background: none;
          font-family: inherit;
          font-size: 12.5px;
          line-height: 1.5;
          color: var(--ink-faint);
          cursor: pointer;
          user-select: none;
          transition: color 0.15s;
        }
        .turn-process-toggle:hover { color: var(--ink-muted, #777); }
        .turn-process-status { font-weight: 500; }
        .turn-process-dur { font-variant-numeric: tabular-nums; }
        .turn-process-caret { width: 12px; height: 12px; transition: transform 0.2s ease; }
        .turn-process-caret.open { transform: rotate(180deg); }
        .turn-process-body { margin: 2px 0 8px; }
        .turn-process-body .thinking-wrap { margin-top: 0; }
        .turn-process-body .tool-card:last-child { margin-bottom: 4px; }
        /* [过程行间距] 思考块与上一行间距 12px → 4px（段间距另由 .turn-cont 收窄） */
        .thinking-wrap { margin-top: 4px; }
        /* 思考正文：隐藏滚动条（仍可滚动）；流式中：固定 3 行窗口，只露最新内容 */
        .thinking-body::-webkit-scrollbar { display: none; width: 0; height: 0; }
        .msg.streaming .thinking-body { max-height: 64px; overflow-y: auto; }
        /* Tool Row（紧凑过程行）：默认单行灰字，无卡片边框/背景/阴影；点击行展开 IN/OUT */
        .tool-card { background: none; border: 0; border-radius: 0; margin: 0 0 1px; overflow: visible; }
        .tool-card .head {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          width: fit-content;
          max-width: calc(100% + 12px);
          margin: 0 -6px;
          padding: 2px 6px;
          background: none;
          border: 0;
          border-radius: 6px;
          font-family: var(--font-sans);
          font-size: 12px;
          line-height: 1.7;
          color: var(--ink-tertiary);
          text-align: left;
          cursor: pointer;
          transition: background-color .15s ease, box-shadow .15s ease, color .15s ease;
        }
        .tool-card .head .icon {
          width: 16px; height: 16px; color: var(--accent);
          display: flex; align-items: center; justify-content: center;
        }
        .tool-card .head .tc-icon {
          flex-shrink: 0; width: 16px; height: 16px;
          display: inline-flex; align-items: center; justify-content: center;
          color: var(--ink-faint); transition: color .15s ease;
        }
        .tool-card .head .tc-icon svg { width: 14px; height: 14px; }
        .tool-card .head:hover .tc-icon { color: var(--ink-tertiary); }
        .tool-card .head .name {
          flex-shrink: 0; max-width: 42%; overflow: hidden; text-overflow: ellipsis;
          white-space: nowrap; font-family: var(--font-sans);
          color: var(--ink-tertiary); font-weight: 500; font-size: 12px;
        }
        .tool-card .head .name.danger { color: var(--error); }
        .tool-card .tc-summary {
          flex: 0 1 auto; min-width: 0; max-width: 480px; overflow: hidden;
          text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ink-faint);
        }
        .tool-card .tc-status { flex-shrink: 0; width: 12px; font-size: 12px; line-height: 1; text-align: center; }
        .tool-card .tc-status.done { color: var(--success-dark); }
        .tool-card .tc-status.error { color: var(--error); }
        .tool-card .tc-status.interrupted { color: var(--ink-faint); }
        .tool-card .tc-status.running { color: var(--running); animation: tc-status-pulse 1.3s ease-in-out infinite; }
        @keyframes tc-status-pulse { 0%, 100% { opacity: 0.45; } 50% { opacity: 1; } }
        @media (prefers-reduced-motion: reduce) { .tool-card .tc-status.running { animation: none; } }
        .tool-card .head .tc-bg {
          font-size: 10px; padding: 2px 7px; border: 1px solid var(--hairline);
          border-radius: var(--r-xs); background: var(--surface-2); color: var(--running);
          cursor: pointer; flex-shrink: 0;
        }
        .tool-card .head .tc-bg:hover { border-color: var(--running); background: var(--surface-3); }
        .tool-card .body {
          margin: 2px 0 4px 18px; padding: 0 0 0 10px; border-left: 2px solid #E2E2E2;
          font-family: var(--font-sans); font-size: 11.5px; color: var(--ink-muted); line-height: 1.5;
        }
        .tool-card .body .k { color: var(--ink-faint); }
        .tool-card .body .s { color: var(--success-dark); }
        /* hover 整行略深 + 阴影（提示可点击展开查看）· 文字颜色提到与正文同级 */
        .tool-card .head:hover {
          color: var(--ink, #1d1d1f);
          background: var(--surface-2);
          box-shadow: 0 2px 8px rgba(18, 28, 45, 0.12);
        }
        .tool-card .head:hover .name { color: var(--ink, #1d1d1f); }
        .tool-card .head:hover .tc-summary { color: var(--ink-subtle, #5f6875); }
        .tool-card .head:hover .tc-status { color: var(--ink-subtle, #5f6875); }
        .tool-card .head:hover .tc-chevron { color: var(--ink-subtle, #5f6875); }
        .tool-card .tc-chevron {
          width: 12px; height: 12px; margin-left: 2px; color: var(--ink-faint);
          flex-shrink: 0; transition: transform 0.15s ease;
        }
        .tool-card.open .tc-chevron { transform: rotate(90deg); }
        .tool-card .tc-io {
          margin: 2px 0 4px 18px; padding: 2px 0 2px 10px; border-left: 2px solid #E2E2E2;
          font-family: var(--font-sans); font-size: 11.5px; color: var(--ink-muted);
          max-height: 260px; overflow: auto;
        }
        .tool-card .tc-io-row { display: flex; gap: 8px; padding: 4px 0; align-items: baseline; }
        .tool-card .tc-io-label {
          flex-shrink: 0; min-width: 28px; font-size: 10px; font-weight: 600;
          letter-spacing: 0.06em; color: var(--ink-faint);
        }
        .tool-card .tc-io-text { flex: 1; white-space: pre-wrap; word-break: break-word; line-height: 1.5; }
        .tool-card .tc-io-text[data-error='true'] { color: var(--error); }
        .tool-card .tc-ansi {
          flex: 1; min-width: 0; font-family: var(--font-mono, ui-monospace, Consolas, monospace);
          font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-word;
          background: #1e1e1e; color: #d4d4d4; border: 1px solid rgba(255, 255, 255, 0.08);
          border-radius: var(--r-md); padding: 8px 10px;
        }
        .tool-card .tc-ansi.error { color: #ff6b6b; }
        .tool-card .tc-ansi-line { min-height: 1.5em; }
        .tool-card .tc-ansi-toggle {
          margin-top: 6px; background: none; border: none; padding: 0; font-size: 11px;
          color: #9a9a9a; cursor: pointer; text-decoration: underline; text-underline-offset: 2px;
        }
        .tool-card .tc-ansi-toggle:hover { color: #2672d8; }
        .app[data-theme="dark"] .tool-card .tc-ansi { background: #161616; border-color: rgba(255, 255, 255, 0.12); }
        .tool-card .tc-io-divider { display: block; height: 1px; margin: 4px 0; background: var(--hairline); }
        .tool-card .tc-io-row.out { align-items: flex-start; }
        .tool-card .tc-copy {
          margin-left: auto; flex-shrink: 0; margin-top: 1px; padding: 2px 8px;
          font-size: 10.5px; line-height: 1.5; border: 1px solid var(--hairline);
          border-radius: var(--r-xs); background: var(--surface-2); color: var(--ink-muted);
          cursor: pointer; opacity: 0.55;
          transition: opacity 120ms var(--ease), color 120ms var(--ease), border-color 120ms var(--ease);
        }
        .tool-card .tc-copy:hover { opacity: 1; color: var(--ink); border-color: var(--ink-faint); background: var(--surface-3); }
        .tool-card .auto-approved {
          display: inline-flex; align-items: center; flex-shrink: 0; gap: 4px;
          font-size: 10.5px; color: var(--success-dark); background: var(--success-soft);
          border: 1px solid rgba(93, 184, 114, 0.28); border-radius: var(--r-xs); padding: 1px 7px;
        }
        .tool-card .ws-body {
          margin: 2px 0 4px 18px; padding: 4px 0 4px 10px; border-left: 2px solid #E2E2E2;
        }
        /* [并行工具组] 父行 + 缩进子项（默认展开） */
        .tool-group { margin: 0 0 1px; }
        .tool-group .tg-head {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          width: fit-content;
          max-width: calc(100% + 12px);
          margin: 0 -6px;
          padding: 2px 6px;
          background: none;
          border: 0;
          border-radius: 6px;
          font-family: var(--font-sans);
          font-size: 12px;
          line-height: 1.7;
          color: var(--ink-tertiary);
          text-align: left;
          cursor: pointer;
          transition: background-color .15s ease, box-shadow .15s ease, color .15s ease;
        }
        .tool-group .tg-head:hover {
          color: var(--ink-subtle);
          background: var(--surface-2);
          box-shadow: 0 1px 4px rgba(18, 28, 45, 0.08);
        }
        .tool-group .tg-head .tc-icon {
          flex-shrink: 0; width: 16px; height: 16px;
          display: inline-flex; align-items: center; justify-content: center;
          color: var(--ink-faint); transition: color .15s ease;
        }
        .tool-group .tg-head .tc-icon svg { width: 14px; height: 14px; }
        .tool-group .tg-head:hover .tc-icon { color: var(--ink-tertiary); }
        .tool-group .tg-head .tc-status { flex-shrink: 0; width: 12px; font-size: 12px; line-height: 1; text-align: center; }
        .tool-group .tg-head .tc-status.done { color: var(--success-dark); }
        .tool-group .tg-head .tc-status.error { color: var(--error); }
        .tool-group .tg-head .tc-status.interrupted { color: var(--ink-faint); }
        .tool-group .tg-head .tc-status.running { color: var(--running); animation: tc-status-pulse 1.3s ease-in-out infinite; }
        @media (prefers-reduced-motion: reduce) {
          .tool-group .tg-head .tc-status.running { animation: none; }
        }
        .tool-group .tg-title {
          flex: 0 1 auto; min-width: 0; max-width: 480px; overflow: hidden;
          text-overflow: ellipsis; white-space: nowrap; font-weight: 500; color: var(--ink-tertiary);
        }
        .tool-group .tg-head:hover .tg-title { color: var(--ink-subtle); }
        .tool-group .tg-chevron {
          width: 12px; height: 12px; margin-left: 2px; color: var(--ink-faint);
          flex-shrink: 0; transition: transform .15s ease;
        }
        .tool-group.open .tg-chevron { transform: rotate(90deg); }
        .tool-group .tg-body { margin: 1px 0 2px 19px; }
        /* [产物卡片] 最后一条 assistant 下方（TurnFooter 之前） */
        .msg-artifacts { margin-top: 12px; display: flex; flex-direction: column; gap: 7px; }
        .msg-artifact {
          display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 10px 12px;
          border-radius: 10px; background: rgba(120, 120, 128, 0.07);
          transition: background 0.14s var(--ease), box-shadow 0.14s var(--ease);
        }
        .msg-artifact.clickable { cursor: pointer; }
        .msg-artifact.clickable:hover { background: rgba(120, 120, 128, 0.1); box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08); }
        .msg-artifact .ma-icon {
          width: 32px; height: 32px; border-radius: 8px; display: grid; place-items: center;
          flex: 0 0 auto; background: rgba(0, 113, 227, 0.1); color: var(--accent, #0071e3);
        }
        .msg-artifact .ma-icon svg { width: 16px; height: 16px; }
        .msg-artifact .ma-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; }
        .msg-artifact .ma-name {
          font-size: 13px; font-weight: 500; color: var(--ink, #1d1d1f);
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .msg-artifact .ma-sub {
          font-size: 11.5px; color: var(--ink-faint, #86868b);
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        /* [用户消息编辑重发] 气泡下方 meta 行（时间 + 编辑 + 复制）：默认透明，hover 淡入 */
        .user-msg-meta {
          display: flex; gap: 10px; align-items: center; justify-content: flex-end;
          margin-top: 6px; font-size: 11.5px; line-height: 1; color: var(--ink-tertiary);
          opacity: 0; pointer-events: none; transition: opacity .16s ease;
        }
        .user-msg-meta.show { opacity: 1; pointer-events: auto; }
        .um-act {
          width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center;
          padding: 0; border: none; border-radius: 5px; background: transparent; color: inherit;
          font-family: inherit; font-size: 11.5px; line-height: 1; cursor: pointer;
          transition: background .15s ease, color .15s ease;
        }
        .um-act:hover { background: rgba(20, 24, 31, .07); color: var(--ink-muted); }
        .um-act svg { width: 13px; height: 13px; display: block; }
        .um-act.is-copied { width: auto; padding: 0 5px; color: var(--ink-muted); }
        /* [用户消息编辑重发] 编辑态输入块：与气泡同宽 */
        .user-edit {
          width: 100%; min-width: 320px; max-width: 72%; box-sizing: border-box;
          padding: 12px 14px; background: var(--surface-1, #fff);
          border: 1px solid var(--hairline); border-radius: 14px;
          box-shadow: 0 2px 12px rgba(0, 0, 0, .06);
        }
        .user-edit-input {
          display: block; width: 100%; box-sizing: border-box; margin: 0; padding: 0;
          border: none; outline: none; background: transparent; color: var(--ink);
          font-family: inherit; font-size: 14px; line-height: 1.6; resize: none; overflow: auto;
        }
        .user-edit-hint {
          display: flex; align-items: center; gap: 5px; margin-top: 8px;
          font-size: 11.5px; color: var(--ink-tertiary); text-align: left;
        }
        .user-edit-hint .ue-info { width: 12px; height: 12px; flex-shrink: 0; }
        .user-edit-error { margin-top: 6px; font-size: 11.5px; color: var(--error, #d93025); text-align: left; }
        .user-edit-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
        .user-edit-actions button {
          height: 28px; padding: 0 14px; border: 1px solid transparent; border-radius: 8px;
          font-family: inherit; font-size: 12.5px; cursor: pointer;
          transition: background .15s ease, opacity .15s ease;
        }
        .user-edit-actions .ue-cancel { background: rgba(20, 24, 31, .06); color: var(--ink-muted); }
        .user-edit-actions .ue-cancel:hover:not(:disabled) { background: rgba(20, 24, 31, .1); }
        .user-edit-actions .ue-send { background: #14181f; color: #fff; }
        .user-edit-actions .ue-send:hover:not(:disabled) { background: #000; }
        .user-edit-actions button:disabled { opacity: .45; cursor: not-allowed; }
        /* [P2-13] compact 摘要「已压缩」胶囊 */
        .msg.compact-summary .cs-badge {
          flex-shrink: 0; padding: 1px 8px; border-radius: 999px;
          background: var(--accent-soft, #eaf2fe); color: var(--accent, #0f5fd4);
          font-size: 11px; font-weight: 700; letter-spacing: 0.2px;
        }
        /* [任务结束入口] 助手消息底部入口行（产物 / 变更 / 来源）· 深色文字 + 灰色数量 + ›，
           分隔线在上；数量不挤在一起（gap 22px），本轮消耗靠右 */
        .msg-links {
          display: flex;
          align-items: center;
          flex-wrap: wrap;
          gap: 22px;
          margin-top: 20px;
          padding-top: 16px;
          border-top: 1px solid var(--hairline);
        }
        .msg-link {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          padding: 2px 0;
          font-family: inherit;
          font-size: 13px;
          line-height: 1.5;
          color: var(--ink);
          background: transparent;
          border: 0;
          cursor: pointer;
        }
        .msg-link:hover { text-decoration: underline; text-underline-offset: 3px; }
        .msg-link .n { color: var(--ink-subtle); font-weight: 400; }
        .msg-link .arr { color: var(--ink-faint); font-size: 12px; }
        /* [本轮消耗] 行右侧汇总：共消耗 · 模型 · 时间（等宽、弱化、不换行） */
        .turn-usage {
          margin-left: auto;
          font-family: var(--font-mono);
          font-size: 12px;
          line-height: 1.5;
          color: var(--ink-faint);
          white-space: nowrap;
        }
      `}</style>
      {/* [paging] 顶部「加载更多」：滚动容器里的**第一个可见元素**（按钮以上不得有任何 padding/占位
          —— .stream 的 padding-top 已在 globals.css 里按 :has(> .stream-inner) 清零，留白由按钮自身 padding 承担）。
          后端还有更早历史（hasMore，按轮口径）时显示；已经到底 → 隐藏。 */}
      {(hasMore && onLoadOlder != null) && (
        <button type="button" className="ml-load-older" disabled={loadingOlder} onClick={loadOlderClick}>
          {loadingOlder ? '加载中…' : `加载更多（再显示 ${LOAD_OLDER_TURNS} 轮）`}
        </button>
      )}
      {/* [轮窗口] 已经到会话开头（没有更早历史）→ 用日期给出「这是第几条」的绝对位置锚。
          与「加载更多」按钮同一位置、互斥：有更早历史时显示按钮，到底时显示本行。 */}
      {hasMore === false && onLoadOlder != null && flatRows.length > 0 && (
        <div className="ml-top-boundary">{topBoundaryDate ? `已加载到会话开始 · ${topBoundaryDate}` : '已加载到会话开始'}</div>
      )}
      {/* [历史提问跳转] 目标不在已加载窗口且补拉不到 → 明确提示（⛔ 不静默失败） */}
      {jumpMiss && <div className="ml-jump-miss" role="status">该提问不在当前窗口</div>}
      {/* [折叠显示] 比默认窗口更早的已加载轮 → 一行折叠（点击本地展开，不发请求）。
          位置固定为「加载更多」按钮（/ 已到底标记）之下的第一块 ⇒ 按钮仍是滚动容器的第一个可见元素。 */}
      {foldedTurnKeys.length > 0 && (
        <button type="button" className="ml-fold-row" aria-expanded={false} onClick={expandFoldedTurns}>
          ⋯ 已折叠 {foldedTurnKeys.length} 轮对话（点击展开）
        </button>
      )}
      {/* F29 · 元消息（续写提示 / budget nudge）isMeta=true 不展示；role=tool 工具结果消息已含于
          assistant.toolCalls[].result（DB 重拉后），独立渲染会重复噪音 → 一并过滤 */}
      {/* 按 userMessageId 分组渲染（消息链锚定 · 对齐 GET /messages 后端出站链）：
          每组 = 一个 flow（user 消息 + 其 assistant/工具流），工具轮挂主气泡下；排队场景顺序正确 */}
      {visibleRows.map((row) => {
        // [OBS2] 每一行单独包一层错误边界：React 18+ 未捕获的渲染错误会卸载**整棵**子树，
        //   即「一条消息毒」会带走整个消息列表。逐行隔开后，坏的那条只挂自己那一行。
        //   ⛔ 只加这一层壳，不动行内渲染逻辑。
        if (row.kind === 'msg') {
          const turnOpen = activeTurnKeys.has(row.turnKey) ? true : (processOpenByTurn[row.turnKey] ?? false)
          // [并行工具组] 该轮展开时其 tool_use_summary 已并入工具组父行标题 → 独立摘要行不再重复显示
          if (turnOpen && toolGroupMeta.summaryIdByTurn.get(row.turnKey) === row.m.id) return null
          // 空壳 assistant 段（只有空 content）不渲染：否则每段 6px margin 累加成大段空白。
          //   收起态下该段的工具卡/思考正文整段不可见，只剩空 content 外壳；判据全部满足才丢弃，
          //   且只在收起态生效 —— 展开后同一段的工具卡/思考必须照常显示（同一份数据不能删）。
          //   承载消息头 / 「已完成」开关 / 轮次 footer / 已裁剪徽章 / SendUserMessage 正文的行一律保留。
          const shellInCollapsedTurn = !turnOpen
            && row.m.role === 'assistant'
            && (row.m.content ?? '').trim() === ''
            && !(row.m.toolCalls ?? []).some(isSendUserMessage)
            && !row.m.isApiErrorMessage
            && !row.m.isCompactSummary
            && row.m.subtype == null
            && !firstAssistantRowKeys.has(row.key)
            && !footerByRowKey.has(row.key)
            && firstIntermediateByTurn.get(row.turnKey) !== row.m.id
            && !(useChatStore.getState().snippedIds[row.m.sessionId] ?? []).includes(row.m.id)
          if (shellInCollapsedTurn) return null
          return (
            <ErrorBoundary key={row.key} tag="MessageList.row.msg" compact
              title="这条消息显示失败" hint="其余内容不受影响。">
              <MemoMessage msg={row.m} onDelete={onDelete} onRunHtml={openHtmlPreview} onOpenRefFile={onOpenRefFile}
                onEditResend={onEditResend}
                footer={footerByRowKey.get(row.key)}
                highlighted={row.m.id === highlightedMsgId}
                processExpanded={turnOpen}
                showProcessHeader={!activeTurnKeys.has(row.turnKey) && firstIntermediateByTurn.get(row.turnKey) === row.m.id}
                turnDurationMs={turnDurationByKey.get(row.turnKey) ?? null}
                turnKey={row.turnKey}
                showHeader={firstAssistantRowKeys.has(row.key)}
                groupTitle={toolGroupMeta.titleByMsgId.get(row.m.id)}
                summaryToolName={toolGroupMeta.toolNameByTurn.get(row.turnKey)}
                onToggleProcess={toggleTurnProcess} />
            </ErrorBoundary>
          )
        }
        if (row.kind === 'err') {
          return (
            <ErrorBoundary key={row.key} tag="MessageList.row.err" compact
              title="这条错误提示显示失败" hint="其余内容不受影响。">
              <ApiErrorCard err={row.err} />
            </ErrorBoundary>
          )
        }
        // [流式性能] 流式块行 → 独立 memo 组件（只订自己块对象）。父级只给稳定 key(sid, blockId)，
        //   content 推进只让目标行重渲；末块行带 'streaming' 尾态 class（同旧逻辑）。
        return (
          <ErrorBoundary key={row.key} tag="MessageList.row.stream" compact
            title="这段回复显示失败" hint="其余内容不受影响，可点「重试」重新渲染。">
            <StreamBlockRow
              sessionId={row.sid}
              blockId={row.blockId}
              isStreamingTail={row.blockId === lastStreamId}
              onRunHtml={openHtmlPreview}
              showHeader={firstAssistantRowKeys.has(row.key)}
            />
          </ErrorBoundary>
        )
      })}
      {/* 无 flow 锚定的错误（userMessageId/assistantMessageId 均缺失）→ 兜底渲染在末尾 */}
      {(() => { const err = apiErrorMap.get('global'); return err ? <ApiErrorCard key="err-global" err={err} /> : null })()}
      {/* 回到最底部（离底时显示 · 对齐 deepseek-harness ChatView toBottom） */}
      {/* 思考中占位：turn 运行中且无流式块（thinking/重试等待期）→ 发送后立即显示 nexus 思考中，消除空白间隙 */}
      {thinking && (
        <div className="msg assistant streaming">
          {activeTurnHasAssistantRow ? <div className="avatar-gutter" aria-hidden /> : <div className="avatar">N</div>}
          <div className="body">
            {!activeTurnHasAssistantRow && <div className="author">nexus</div>}
            <div className="thinking-wrap open">
              <div className="thinking-toggle">
                <span>思考中…</span>
              </div>
            </div>
          </div>
        </div>
      )}
      </ErrorBoundary>
    </div>
    </>
  )
}

// [打字性能] MessageList 包 memo：打字状态（composerText）在 App 里 ⇒ 每敲一键 App 重渲 ⇒
//   此前会连带把本组件整表重跑（groups/flatRows 两个 useMemo + N 行 reconciliation），N 随窗口增长而变大。
//   本组件 props 均为稳定引用（messages 来自 store 选择器；onDelete / onOpenRefFile / onLoadOlder 是
//   useCallback；onNearBottomChange 是 useState setter；其余为标量）⇒ 父级重渲时这里可整体 bail。
//   ⚠️ store 变化仍会重渲本组件（apiErrors / streamIds 是组件内订阅）—— memo 只拦「父级驱动」的重渲。
export const MessageList = memo(MessageListInner)
