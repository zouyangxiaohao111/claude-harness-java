import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ClipboardEvent, CSSProperties, DragEvent, ReactNode } from 'react'
import { QueuedCommandsBar } from './QueuedCommandsBar'
import { SessionToolsPanel } from './SessionToolsPanel'
import { CatArtBody } from '../startup/CatArt'
import type { PoppedQueueInput, QueuedCommand } from '@/hooks/useCommandQueue'
import { PERMISSION_MODE_LABELS, PERMISSION_MODE_DESCRIPTIONS, type PermissionMode } from '@/api/types'
import type { AttachmentRequest, SessionDto, ChatMessageDto } from '@/api/types'
import { uploadAttachment } from '@/api/chat'
import { AgentSelector } from './AgentSelector'
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { readFile, stat } from '@tauri-apps/plugin-fs'
import { COMMAND_ITEMS, isDisabledCommand } from './CommandPalette'
import { commandApi, type CommandDto } from '@/api/command'
import { projectApi } from '@/api/projects'
import { compactNumber } from '@/utils/format'
import { contentDedupKey, deliverAttachmentFiles, fileDedupKey } from '@/utils/attachmentDelivery'
import { classifyAttachmentPath, pathDedupKey, planPathAttachmentChannel } from '@/utils/pathAttachment'
import { resolveCtxInfo } from '@/utils/contextUsage'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'

/** 稳定空数组（selector `?? []` 每次返回新引用会触发无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []
/** 稳定空数组（streams[sessionId] 不存在时回落，防 selector 每次返回新引用触发重渲染）。 */
const EMPTY_BLOCKS: StreamBlock[] = []

/** Tauri：拖拽被 WebView 拦截，用 onDragDropEvent 拿真实路径 → fs 读文件 → base64/upload */
const IS_TAURI = isTauri()

/** 待发附件（A1 契约）：≤5MB base64 直传；>5MB multipart upload 拿 contentId；local-read 模式 >5MB 传本地 path */
interface PendingAttachment {
  type: AttachmentRequest['type']
  filename: string
  mediaType: string
  /**
   * 去重键（批 ATT-DEDUP-KEY）：图片/≤5MB = 内容 md5；>5MB 的 File = 名+大小；path 附件 = 完整路径。
   * ⛔ 与 chip **同生命周期** —— 移除 chip / 清空 / 发送后必须用**它**释放（键已不是文件名，
   * 按 filename 释放 ⇒ 移除了 chip 但键还在 ⇒ 同一张图再也加不回来）。
   */
  dedupKey: string
  base64?: string   // ≤5MB 直传内容（图片 dataURL）
  contentId?: string // 大文件 upload 后后端附件表缓存 id
  path?: string     // local-read 模式本地绝对路径（>5MB 不 upload，后端同机读盘）
  preview?: string  // 图片缩略图
  size?: number
}

/** 5MB 直传上限（后端 MediaLimitGuard） */
const BASE64_LIMIT = 5 * 1024 * 1024

/** 拉回排队附件时 chip 去重键的命名空间（批 A5）· 与 `md5:` / `file:` / `path:` 并列，互不冲突。 */
const DEDUP_NS_QUEUED = 'queued:'

/** 拉回排队附件时的 chip 去重键（批 A5）。
 *
 * ⛔ **为什么不按内容 md5 算**（base64 腿 chip 正常路径就是这个键）：同一张图被拉回时已在内存里，
 *   但 `mediaLimitGuard` 允许单条 5MB、单请求 100 项 ⇒ 最坏 500MB 逐个同步 md5 = 主线程卡住数秒
 *   （本仓 `attachmentDelivery.ts` / `pathAttachment.ts` 头注都明确禁止在大文件上同步算内容哈希）。
 *   故退回**廉价且唯一**的键：path 腿用完整路径（零 I/O，与入队前**同一个键**）、
 *   上传腿用 contentId、其余用 filename+序号。
 *   代价如实登记：base64 腿拉回后，同一张图再粘一次不会被判为重复（多出一个 chip）—— 相对
 *   「按 Esc 后附件全丢」是可接受的方向；且 chip 的移除按**索引**走，不受该键影响。 */
function restoredDedupKey(a: AttachmentRequest, index: number): string {
  if (a.path) return pathDedupKey(a.path)
  if (a.contentId) return `${DEDUP_NS_QUEUED}${a.contentId}`
  return `${DEDUP_NS_QUEUED}${a.filename ?? 'attachment'}#${index}`
}

/** 后端回传的排队附件 → 待发 chip（批 A5 · 「图片一起还给你」）。
 *
 * <p>字段映射：后端 `AttachmentRequest`（= 队列项 `attachments`，`resolveAttachments` + `MediaLimitGuard`
 * 之后的**已解析**形态）→ `PendingAttachment`。两个关键点：
 * <ul>
 *   <li>`base64` 一律按**纯 base64** 存放（后端出站就是纯 base64），`preview` 另拼 dataURL ——
 *       chip 的 `<img src>` 需要 dataURL，而 `doSend` 又会把 dataURL 前缀剥掉再发 ⇒ 不再重投时出错。</li>
 *   <li>contentId / path 原样保留（上传腿 / local-read 腿重投靠它们）。</li>
 * </ul> */
export function restorePendingAttachments(list?: AttachmentRequest[] | null): PendingAttachment[] {
  if (!list || list.length === 0) return []
  return list.map((a, i) => {
    const pure = a.base64 ? a.base64.replace(/^data:[^;]+;base64,/, '') : undefined
    const mediaType = a.mediaType ?? ''
    return {
      type: a.type,
      filename: a.filename ?? '附件',
      mediaType,
      dedupKey: restoredDedupKey(a, i),
      ...(pure ? { base64: pure } : {}),
      ...(pure && a.type === 'image' ? { preview: `data:${mediaType || 'image/png'};base64,${pure}` } : {}),
      ...(a.contentId ? { contentId: a.contentId } : {}),
      ...(a.path ? { path: a.path } : {}),
    }
  })
}

/** Uint8Array → base64（Tauri fs 读文件二进制 → dataURL 直传） */
function u8ToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

interface ComposerProps {
  composerText: string
  setComposerText: (v: string) => void
  sendMessage: (attachments?: AttachmentRequest[]) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  streaming: boolean
  onStop: () => void
  queuedCommands: QueuedCommand[]
  /**
   * 拉回全部可编辑排队命令（Esc / 排队条「编辑」按钮）。
   *
   * <p>返回值是**新契约**（批 A5）：`{text, attachments}`。`text` 由 `App` 负责写进输入框
   * （`setComposerText` 归 `App` 所有）；`attachments` 由本组件还原成待发 chip
   * （附件 `attachments` state 归本组件所有）。返回 `null` = 无可拉回项/请求失败 → 本组件零改动。
   */
  popEditable: () => Promise<PoppedQueueInput | null>
  /** 当前绑定项目名（null=未绑定，显示"未选中"） */
  boundProjectName: string | null
  /** 当前绑定项目 id（@ 引用文件候选源 = 该项目 git 文件树 · null=未绑定不弹候选） */
  boundProjectId?: string | null
  /** 点击项目选择器 → 弹项目列表（App 处理） */
  onSelectProject: () => void
  /** 当前模型名（发送键旁胶囊展示） */
  currentModel: string
  /** 当前会话权限模式（会话覆盖 ?? 全局默认 ?? 'default' · App 解析后传入） */
  permissionMode: PermissionMode
  /** 切换会话权限模式（App 调 sessionApi.update({permissionMode}) · 会话覆盖全局） */
  onPermissionModeChange?: (mode: PermissionMode) => void
  /** 当前思考深度档位（会话级 · V31）· 显示在胶囊上 */
  effortLevel?: SessionDto['effortLevel']
  /** 会话级 ultracode 开关（V32）· true = 胶囊显示 ultracode */
  ultracodeEnabled?: boolean | null
  /** 会话级精简模式（V33 bare_mode）· true = simple 模式（工具只显 [Bash, Read, Edit]） */
  bareMode?: boolean | null
  /** Mode 切换（App 调 sessionApi.update({bareMode})）· true = simple */
  onModeChange?: (simple: boolean) => void
  /** 上拉抽屉点「模型」行 → 打开模型选择弹窗（App 处理） */
  onOpenModelPicker?: () => void
  /** 上拉抽屉点「推理等级」行 → 打开 EffortModal（App 处理） */
  onOpenEffort?: () => void
  /** 当前会话 id（会话工具面板拉取 · 会话切换时自动刷新） */
  sessionId?: string
  /** 空态（无消息）：输入框居中 */
  empty?: boolean
  /** F1 · 点击底部 hint-usage（token/金额/当前上下文）→ 打开 UsageCostModal（App 处理） */
  onOpenUsageCost?: () => void
  /** NexusAI in Chrome：输入框工具栏浏览器图标 → 打开 ChromePanel（检查/安装/连接引导，App 处理） */
  onOpenChromePanel?: () => void
  /** 回到底部按钮（离底时显示 · 对齐 deepseek-harness ChatView toBottom · 由 MessageList onScroll 驱动） */
  showToBottom?: boolean
  /** 回到底部点击 → 滚动对话到底（App 触发 MessageList scrollSignal） */
  onScrollToBottom?: () => void
  /** 强行停止所有（输入框上方按钮：一键取消当前流式 + 停全部后台任务 · 替代难触发的双击 Esc）· streaming 时显示 */
  onHardStop?: () => void
  /** local-read 附件模式（前后端同机）：>5MB 拖拽附件传本地 path 由后端读盘，不 upload */
  localRead?: boolean
  /** 当前会话主线程 agent（null/空串 = 默认模式，胶囊显示「技能市场」入口） */
  currentAgent?: string | null
  /** 点击顶部 agent 胶囊 → 打开技能市场弹窗（App 持有 showMarket state） */
  onOpenMarket?: () => void
}

// F36：token target 关键词（+500k / +250k / +1m 等），对齐 CC PromptInput 的 findTokenBudgetPositions
const TOKEN_BUDGET_RE = /\+\d+[kmb]?/gi

// 高亮层与 textarea 共用同一套排版参数，保证逐字对齐（背景透明 textarea + 前置高亮层）
const HIGHLIGHT_TEXT_STYLE: CSSProperties = {
  fontFamily: 'var(--font-sans)',
  fontSize: 14,
  lineHeight: 1.5,
  padding: 0,
}

const HIGHLIGHT_SPAN_STYLE: CSSProperties = {
  background: 'var(--accent-soft)',
  borderRadius: 'var(--r-xs)',
}

// @引用 token 匹配（@"引号路径" 或 @路径：遇空白/中文标点/右括号/引号结束；不含 #L 行区间）
const AT_TOKEN_RE = /@"[^"]+"|@[^\s，。、；：（()）“”"'#]+/g

// 单段内再叠加 token-budget(+500k) 高亮
function renderBudget(text: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(TOKEN_BUDGET_RE)) {
    const idx = m.index ?? 0
    if (idx > last) out.push(text.slice(last, idx))
    out.push(<span key={`b-${idx}`} style={HIGHLIGHT_SPAN_STYLE}>{m[0]}</span>)
    last = idx + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

// 把输入文本拆成「普通片段(+budget 高亮) + @引用 chip」
// @引用 chip 渲染在「高亮层」（textArea 之上、透明副本）里 → 鼠标 hover/点击命中 chip 本体；
// 视觉样式由 .input-at-chip（globals.css）提供（阴影框 · hover 加深阴影 · 点击移除）。
function renderHighlighted(text: string, onChipRemove?: (token: string) => void): ReactNode[] {
  const nodes: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(AT_TOKEN_RE)) {
    const idx = m.index ?? 0
    if (idx > last) nodes.push(...renderBudget(text.slice(last, idx)))
    nodes.push(
      <span
        key={`at-${idx}`}
        className="input-at-chip"
        title="点击移除引用"
        onClick={() => onChipRemove?.(m[0])}
      >
        {m[0]}
      </span>,
    )
    last = idx + m[0].length
  }
  if (last < text.length) nodes.push(...renderBudget(text.slice(last)))
  return nodes
}

export function Composer({ composerText, setComposerText, sendMessage, showToast, streaming, onStop, queuedCommands, popEditable, boundProjectName, boundProjectId, onSelectProject, currentModel, permissionMode, onPermissionModeChange, effortLevel, ultracodeEnabled, bareMode, onModeChange, onOpenModelPicker, onOpenEffort, empty, sessionId, onOpenUsageCost, onOpenChromePanel, showToBottom, onScrollToBottom, onHardStop, localRead, currentAgent, onOpenMarket }: ComposerProps) {
  // 模型名显示末段（去掉 provider 前缀，如 ds-openai/deepseek-v4-flash → deepseek-v4-flash）
  const shortModel = currentModel?.split('/').pop() ?? currentModel ?? ''
  // 会话 token/金额汇总（底部 footer · 与 hint-shortcuts 对称）：complete 事件实时覆盖 + F5 从会话列表恢复
  const sessionUsage = useChatStore((s) => s.sessions.find((x) => x.id === sessionId))
  // F1/F5 · 当前上下文「已用 / 窗口（剩余%）」：**只认快照口径**（服务端真实 usage + 模型原始窗口 +
  //   窗口相对百分比 · resolveCtxInfo）。扫描源 = [...msgs, ...liveBlocks]：live 块排尾部 → 从尾向前
  //   命中最新流式块的 usage/上下文，多轮 turn 内每条 assistant message.usage 到达即实时刷新；turn
  //   完成清流后纯 msgs 兜底。
  //   [P3-d 口径统一 2026-09-11] 删除「无快照 → 回落 token_warning」分支：该事件的 tokenUsage 是本地
  //   估算、percentLeft 是**阈值相对**口径（分母 autoCompactThreshold ≠ 窗口），与快照口径异源，混在同
  //   一位置会忽大忽小；且 /compact 后 boundary 之后暂无带 usage 的 assistant（后端
  //   MessageService.applyContextSnapshotToLastAssistant 同义切片）→ 无快照即不显示（对齐 CC
  //   getCurrentUsage 找不到 → 指示器归零），否则会拿压缩前的值顶上来（数字不降）。
  const msgs = useChatStore((s) => (sessionId ? (s.messages[sessionId] ?? EMPTY_MESSAGES) : EMPTY_MESSAGES))
  const liveBlocks = useChatStore((s) => (sessionId ? (s.streams[sessionId] ?? EMPTY_BLOCKS) : EMPTY_BLOCKS))
  const ctxInfo = useMemo(() => resolveCtxInfo([...msgs, ...liveBlocks]), [msgs, liveBlocks])
  // F1 · 缓存利用率（参考 deepseek-harness 缓存概念）：按 provider 分派——
  //   anthropic（claude）：cache_read / (input + cache_read + cache_creation)，input 不含 cache hit；
  //   deepseek（openai 协议）：input_tokens 已含 cache hit（input==H+M），直接 cache_read / input
  //   （真实命中率；按 anthropic 公式会算成真实的一半 ~40% 假象）。provider 由 currentModel
  //   `provider/model` 前缀判定（后端 ContextUsageCalculator.isAnthropic 同口径：provider.type==anthropic）。
  //   取最近一条带 usage 的 assistant 消息（complete 事件 usage 透传 · tokenWarning.tokenUsage 仅 number 无缓存细分）
  const cacheRateInfo = useMemo(() => {
    const isClaudeProvider = (currentModel ?? '').split('/')[0].trim().toLowerCase() === 'anthropic'
    const scanned = [...msgs, ...liveBlocks]
    for (let i = scanned.length - 1; i >= 0; i--) {
      const u = scanned[i]?.usage
      if (!u) continue
      const cr = u.cache_read_input_tokens ?? 0
      const ci = u.input_tokens ?? 0
      const cc = u.cache_creation_input_tokens ?? 0
      if (isClaudeProvider) {
        const total = ci + cc + cr
        if (total > 0) return { rate: Math.round((cr / total) * 100), read: cr }
      } else if (ci > 0) {
        return { rate: Math.round((cr / ci) * 100), read: cr }
      }
    }
    return null
  }, [msgs, liveBlocks, currentModel])
  // F4 · 最近一条 assistant 消息 t/s 速度（output_tokens × 1000 / decode_ms · footer 展示）。
  //   扫描源 = [...msgs, ...liveBlocks]：live 块在 message.usage（assistant 流式结束）即挂 usage/decode_ms，
  //   速率在块转消息（complete）前即可读 —— 多轮 agent 每条 assistant 结束实时刷新，不再等 turn 完成落库。
  //   live 块无 role（隐含 assistant）；decode_ms 语义 = 首 token→完成整段计时，故速率是「每段输出收尾即跳」。
  const lastSpeedTs = useMemo(() => {
    const scanned = [...msgs, ...liveBlocks] as Array<ChatMessageDto & StreamBlock>
    for (let i = scanned.length - 1; i >= 0; i--) {
      const m = scanned[i]
      if (m.role && m.role !== 'assistant') continue
      const ot = m.usage?.output_tokens ?? m.outputTokens ?? 0
      const dm = m.usage?.decode_ms ?? m.decodeMs ?? 0
      if (ot > 0 && dm > 0) return Math.round((ot * 1000) / dm)
    }
    return null
  }, [msgs, liveBlocks])
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const highlightRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [attachments, setAttachments] = useState<PendingAttachment[]>([])
  // 去重：Tauri onDragDropEvent 与浏览器 drop 可能双触发，同一份文件只添加一次。
  // ⭐ 批 ATT-DEDUP-KEY：键**不是文件名** —— 内容 md5（图片/≤5MB）/ 名+大小（大文件 upload）/
  //   完整路径（path 附件）。详见 `utils/attachmentDelivery.ts` 的「去重键 = 身份，不是名字」段。
  //   ⛔ 三个释放点都必须用 chip 上的 `dedupKey`（见下面 chip 移除、清空、doSend）。
  const addedKeysRef = useRef<Set<string>>(new Set())
  // 点击缩略图 → 放大预览（lightbox）
  const [zoomImg, setZoomImg] = useState<string | null>(null)
  // Mode 下拉（受控 · V33 bare_mode：simple=精简 true / full=完整 false）
  const mode: 'simple' | 'full' = bareMode ? 'simple' : 'full'
  const [modeOpen, setModeOpen] = useState(false)
  // 权限模式抽屉（对齐模型胶囊 model-effort-pill 交互）
  const [permOpen, setPermOpen] = useState(false)
  // 对话进行中（有消息/流式）禁止切换模式，仅空会话（新会话）可切换（对齐「文件选择」绑定规则）
  const modeLocked = !empty
  // 输入 `/` → 命令即时提示（slash 补全）
  const [cmdIndex, setCmdIndex] = useState(0)
  // 后端技能/命令（GET /api/command · 含 skills）：挂载拉取，失败静默回落本地 COMMAND_ITEMS
  const [remoteCommands, setRemoteCommands] = useState<CommandDto[]>([])
  useEffect(() => {
    let alive = true
    // [TL-W1 P4] sessionId 直传后端（REST 线程按会话解析绑定项目 → project 级命令进补全）；
    //   切会话 → 重新拉取（避免上一个会话的项目命令滞留；同源 /skills 见 useSkills）。
    commandApi.list(false, sessionId ?? undefined).then((cs) => { if (alive) setRemoteCommands(cs) }).catch(() => {})
    return () => { alive = false }
  }, [sessionId])
  const cmdMatches = useMemo(() => {
    const t = composerText
    if (!t.startsWith('/') || t.includes(' ')) return null
    const q = t.slice(1).toLowerCase()
    // 合并本地内置 + 后端技能命令（技能名 / 插件名前缀触发提示，如 /update-config、/zjkycode）
    // [P0-0/N1 决策] 已停用命令（/clear 及别名 reset/new）不进补全：后端 GET /api/command 仍会回
    //   clear（BuiltInCommands 经 SkillRegistry 五源进入），不滤则补全里重新冒出必失败入口。
    const all: { name: string; description: string; aliases?: string[]; pluginName?: string }[] =
      [...COMMAND_ITEMS].filter((c) => !isDisabledCommand(c.name))
    for (const r of remoteCommands) {
      if (isDisabledCommand(r.name)) continue
      if (!all.some((c) => c.name === r.name)) all.push({ name: r.name, description: r.description ?? '', aliases: undefined, pluginName: r.pluginName ?? undefined })
    }
    // 匹配：技能名 / 别名 / 插件名前缀（输入 /zjkycode 显示该插件全部技能）
    //  + /plugin:skill（CC 格式）：拆冒号精确匹配插件名 + 技能名前缀（如 /zjkycode:brain → brainstorming）
    const list = all.filter((c) => {
      if (q.includes(':')) {
        const colon = q.indexOf(':')
        const plugin = q.slice(0, colon)
        const skill = q.slice(colon + 1)
        if (!c.pluginName || c.pluginName !== plugin) return false
        if (!skill) return true
        return c.name.startsWith(skill) || (c.aliases ?? []).some((a) => a.startsWith(skill))
      }
      return c.name.startsWith(q) ||
        (c.aliases ?? []).some((a) => a.startsWith(q)) ||
        (!!c.pluginName && c.pluginName.startsWith(q))
    })
    return list.length ? list : null
  }, [composerText, remoteCommands])
  // 键盘 ↑↓ 选择时：联动滚动命令列表，确保选中项始终可见
  const slashMenuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!cmdMatches || !slashMenuRef.current) return
    const el = slashMenuRef.current.children[cmdIndex % cmdMatches.length] as HTMLElement | undefined
    el?.scrollIntoView({ block: 'nearest' })
  }, [cmdIndex, cmdMatches])
  // 命令列表从无→有时重置选中索引（重新输入 / 从头开始，不沿用上次位置）
  const hadCmdRef = useRef(false)
  useEffect(() => {
    if (cmdMatches && !hadCmdRef.current) setCmdIndex(0)
    hadCmdRef.current = !!cmdMatches
  }, [cmdMatches])

  // [Phase3 @引用文件] 输入 '@' → 绑定项目文件候选（对齐 CC @file）。端锚定（末行行尾 @token）：
  //   探测末行「最后一个空白/标点之后的尾词」是否以 @ 开头；选中后把该尾词替换成 @path 。
  const atTailIndex = (() => {
    const t = composerText
    if (!t.includes('@')) return null
    const lastNl = t.lastIndexOf('\n')
    const line = lastNl >= 0 ? t.slice(lastNl + 1) : t
    const seg = Math.max(
      line.lastIndexOf(' '), line.lastIndexOf('，'), line.lastIndexOf('。'),
      line.lastIndexOf('：'), line.lastIndexOf('（'), line.lastIndexOf('('),
    )
    const tail = line.slice(seg + 1)
    if (!tail.startsWith('@')) return null
    const segStartAbs = lastNl >= 0 ? lastNl + 1 + seg : seg
    return { segStartAbs, prefix: tail.slice(1) }
  })()
  const atActive = !!atTailIndex && !!boundProjectId && !streaming
  const [atIndex, setAtIndex] = useState(0)
  const atMenuRef = useRef<HTMLDivElement>(null)
  const hadAtRef = useRef(false)
  const [projFiles, setProjFiles] = useState<string[] | null>(null)
  // 懒加载一次绑定项目 git 文件（扁平文件路径；失败静默，@ 仍可手输）
  useEffect(() => {
    if (!atActive || projFiles !== null || !boundProjectId) return
    let alive = true
    projectApi.files(boundProjectId)
      .then((nodes) => {
        if (!alive) return
        const out: string[] = []
        const walk = (list: { type: string; path: string; children?: unknown[] | null }[]) => {
          for (const n of list) {
            if (n.type === 'file') out.push(n.path)
            else if (n.children && Array.isArray(n.children)) walk(n.children as { type: string; path: string; children?: unknown[] | null }[])
          }
        }
        walk(nodes as { type: string; path: string; children?: unknown[] | null }[])
        setProjFiles(out)
      })
      .catch(() => { /* 后端未就绪：@ 提示静默（仍可手输） */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atActive, boundProjectId])
  const atMatches = useMemo(() => {
    if (!atActive || projFiles === null || !atTailIndex) return null
    const q = atTailIndex.prefix.toLowerCase()
    const list = projFiles.filter((p) => !q || p.toLowerCase().includes(q)).slice(0, 40)
    return list.length ? list : null
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atActive, projFiles, atTailIndex])
  // 候选出现/变化 → 重置选中；随 ↑↓ 联动滚动
  useEffect(() => {
    if (atMatches && !hadAtRef.current) setAtIndex(0)
    hadAtRef.current = !!atMatches
  }, [atMatches])
  useEffect(() => {
    if (!atMatches || !atMenuRef.current) return
    const el = atMenuRef.current.children[atIndex % atMatches.length] as HTMLElement | undefined
    el?.scrollIntoView({ block: 'nearest' })
  }, [atIndex, atMatches])
  /** 选中候选 → 把行尾 @token 替换为 @path（保留前导空白/标点分隔符） */
  const applyAtPick = (path: string) => {
    if (!atTailIndex) return
    const head = atTailIndex.segStartAbs >= 0 ? composerText.slice(0, atTailIndex.segStartAbs + 1) : ''
    setComposerText(`${head}@${path} `)
    setAtIndex(0)
    textareaRef.current?.focus()
  }

  /** [Phase3] 点输入框 @chip → 从文本移除该引用 token（并折叠多余空格） */
  const removeAtChip = useCallback((token: string) => {
    const idx = composerText.indexOf(token)
    if (idx < 0) return
    let next = composerText.slice(0, idx) + composerText.slice(idx + token.length)
    // 折叠移除后可能残留的双空格（token 前后各一）
    if (next.includes('  ')) next = next.replace(/ {2,}/g, ' ').replace(/^ /, '')
    setComposerText(next)
    setAtIndex(0)
    requestAnimationFrame(() => textareaRef.current?.focus())
  }, [composerText, setComposerText])
  // 发送键旁胶囊 → 上拉抽屉（模型/推理等级两行）；点击外部收起
  const [pillOpen, setPillOpen] = useState(false)
  const pillRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!pillOpen) return
    const onDoc = (e: MouseEvent) => {
      if (pillRef.current && !pillRef.current.contains(e.target as Node)) setPillOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [pillOpen])
  // 权限模式抽屉：点击外部收起（对齐模型胶囊 mousedown 监听）
  const permRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!permOpen) return
    const onDoc = (e: MouseEvent) => {
      if (permRef.current && !permRef.current.contains(e.target as Node)) setPermOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [permOpen])

  // 会话工具面板（功能2：禁用/恢复 · 会话级临时禁用）；点击外部 / Esc 收起
  const [toolsOpen, setToolsOpen] = useState(false)
  const [toolsDisabledCount, setToolsDisabledCount] = useState(0)
  const toolsPanelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!toolsOpen) return
    const onDoc = (e: MouseEvent) => {
      if (toolsPanelRef.current && !toolsPanelRef.current.contains(e.target as Node)) setToolsOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [toolsOpen])
  // 会话切换 → 重置禁用角标（面板打开时 SessionToolsPanel 按 sessionId 自动重新拉取）
  useEffect(() => {
    setToolsDisabledCount(0)
  }, [sessionId])

  // 附件（A1 契约 · 批 ATT-DROP）：**投递决策全在 utils/attachmentDelivery（有单测）**，本函数只做副作用接线。
  //   ⛔ 红线：不允许「已显示 chip、模型收不到」的静默丢弃 —— 每个 File 必须落进
  //   「真的进入投递通道（base64 / upload）」或「同步拒绝（提示 + 不生成 chip）」。
  //   本批三处行为变更（详见 attachmentDelivery.ts 头注）：
  //   ① video/audio 不再按图片的 5MB 阈值走 base64（后端对其 base64 零消费方 ⇒ 静默丢弃），改走 upload；
  //   ② 其余类型（docx/xlsx/zip/txt…）的 ≤5MB 腿（原实现必然静默丢弃）⇒ 同步拒绝 + 不生成 chip；
  //      其 >5MB 腿仍走 upload（原样，后端白名单按扩展名兜底，可能送达）；
  //   ③ 「已添加 N 个附件」用**真实投递数**，不再用入口文件数（原实现丢弃时虚报）。
  const addFiles = (fileList: FileList | File[]) => {
    const files = Array.from(fileList)
    // [attach] 观测入口：浏览器 drop / 原生 input / paste 通道实际拿到几个 File
    console.warn(`[attach] addFiles 入口 files=${files.length} 明细=${files.map((f) => f.name).join(' | ')}`)
    if (files.length === 0) return
    void (async () => {
      const summary = await deliverAttachmentFiles(files, {
        sessionId,
        isDuplicate: (k) => addedKeysRef.current.has(k),
        reserve: (k) => { addedKeysRef.current.add(k) },
        release: (k) => { addedKeysRef.current.delete(k) },
        onAccepted: (a) => {
          console.warn(`[attach] 结局=${a.filename} → chip（通道=${a.uploading ? 'upload' : 'base64'} type=${a.type} size=${a.size}B 键=${a.dedupKey}）`)
          setAttachments((prev) => [...prev, {
            type: a.type, filename: a.filename, mediaType: a.mediaType, size: a.size, dedupKey: a.dedupKey,
            ...(a.base64 ? { base64: a.base64, ...(a.type === 'image' ? { preview: a.base64 } : {}) } : {}),
            ...(a.uploading ? { contentId: '__uploading__' } : {}),
          }])
        },
        // ⭐ 按**键**回填（不是按文件名）：改键后允许两个同名 chip 共存（两张都叫 image.png 的不同截图），
        //   按 filename 匹配会把 contentId 回填到错的那一个 ⇒ 发送的是 A 的 contentId 配 B 的图。
        onUploaded: (key, filename, contentId) => {
          // 留痕带**键**：现场若出现「同名两个 chip」，凭 filename 无法区分是谁回填的
          console.warn(`[attach] 上传完成 filename=${filename} contentId=${contentId} 键=${key}`)
          setAttachments((prev) => prev.map((a) =>
            a.contentId === '__uploading__' && a.dedupKey === key ? { ...a, contentId } : a))
        },
        onFailed: (key, filename, reason) => {
          // 异步失败 → 撤下占位 chip（绝不留下「有 chip 但模型收不到」）+ 带原因的提示（响亮失败）
          // 同上传回填：按**键**定位被撤的那一个，绝不连带撤掉同名的另一个。
          console.warn(`[attach] 结局=${filename} → 失败（${reason}）`)
          setAttachments((prev) => prev.filter((a) => !(a.contentId === '__uploading__' && a.dedupKey === key)))
          showToast(`附件未能送达：${filename}（${reason}）`, 'info')
        },
        onRejected: (filename, reason) => {
          console.warn(`[attach] 结局=${filename} → 拒绝（${reason}）`)
        },
        // 字节一并交回：去重键要按**内容**算（用户裁定「按字节 md5」），而这份字节本来就要读
        //   ⇒ 不额外读一次盘。详见 utils/attachmentDelivery.ts 的 contentDedupKey。
        encodeDataUrl: async (file, mediaType) => {
          const bytes = new Uint8Array(await file.arrayBuffer())
          return { dataUrl: `data:${mediaType};base64,${u8ToBase64(bytes)}`, bytes }
        },
        upload: (file, sid) => uploadAttachment(file, sid),
      })
      console.warn(`[attach] addFiles 汇总 接受=${summary.accepted} 拒绝=${summary.rejected.length}`
        + ` 重复=${summary.duplicates.length} 读取失败=${summary.failed.length}`)
      const unsupported = summary.rejected.filter((r) => r.reason === 'unsupported').map((r) => r.filename)
      const noSession = summary.rejected.filter((r) => r.reason === 'no-session').map((r) => r.filename)
      const parts: string[] = []
      if (summary.accepted > 0) parts.push(`已添加 ${summary.accepted} 个附件`)
      if (unsupported.length) parts.push(`不支持的文件类型：${unsupported.join('、')}（本通道仅支持 图片 / PDF / 视频 / 音频）`)
      if (noSession.length) parts.push(`需先打开一个会话再添加附件：${noSession.join('、')}`)
      if (summary.failed.length) parts.push(`读取失败：${summary.failed.map((f) => f.filename).join('、')}`)
      if (parts.length) {
        showToast(parts.join('；'), summary.rejected.length || summary.failed.length ? 'info' : 'success')
      }
    })()
  }

  // Tauri：拖拽文件路径 → fs 读 → base64（≤5MB 直传）/ upload（>5MB 拿 contentId）
  const addPaths = async (paths: string[]) => {
    // [attach] 观测入口：drop / dialog 实际交进来的 path 清单
    console.warn(`[attach] addPaths 入口 paths=${paths.length} 明细=${paths.join(' | ')}`)
    const pending: PendingAttachment[] = []
    for (const p of paths) {
      try {
        const cls = classifyAttachmentPath(p)
        const { name, type, mediaType, isImage } = cls
        // [local-read] 前后端同机：>5MB 拖拽附件直接传本地 path（后端同机读盘 + 注册附件表，省一次 upload 拷贝）——
        //   plugin-fs stat 拿 size 判定，不整读大文件进内存
        if (localRead) {
          const info = await stat(p)
          // 图片与 PDF 保持原语义：
          //   - 图片 ≤5MB 必须走 base64（才能变成 image content block 让模型直接看到图）
          //   - PDF 有自己的内联 document block 链（后端 registerBase64Pdf / PdfAttachmentProcessor）
          // 其余类型（Word/Excel/视频/音频/其它）走 path：零拷贝、不落盘、不进请求体
          if (planPathAttachmentChannel(cls, info.size, localRead) === 'path') {
            pending.push({ type, filename: name, mediaType, path: p, size: info.size, dedupKey: pathDedupKey(p) })
            console.warn(`[attach] 结局=${name} → pending（通道=path ${info.size}B）`)
            continue
          }
        }
        const bytes = await readFile(p)
        if (bytes.length > BASE64_LIMIT) {
          // >5MB → multipart upload 落盘 → contentId
          // [批 3a] 同上：无会话显式拒绝，不静默丢附件归属
          if (!sessionId) {
            // [attach] 观测：无会话时该文件被静默跳过（用户只会看到「少了几个」）
            console.warn(`[attach] 结局=${name} → 跳过（无 sessionId，>5MB 无法 upload）`)
            showToast(`大文件需先打开一个会话再上传：${name}`, 'info')
            continue
          }
          const file = new File([bytes], name, { type: mediaType })
          const r = await uploadAttachment(file, sessionId)
          pending.push({ type, filename: name, mediaType, contentId: r.contentId, size: bytes.length, dedupKey: fileDedupKey(name, bytes.length) })
          console.warn(`[attach] 结局=${name} → pending（通道=upload contentId=${r.contentId} ${bytes.length}B）`)
        } else {
          const dataUrl = `data:${mediaType};base64,${u8ToBase64(bytes)}`
          // 键 = 内容 md5（与 addFiles 通道同一套身份；字节已在手，零额外成本）
          pending.push({ type, filename: name, mediaType, base64: dataUrl, ...(isImage ? { preview: dataUrl } : {}), size: bytes.length, dedupKey: contentDedupKey(bytes) })
          console.warn(`[attach] 结局=${name} → pending（通道=base64 ${bytes.length}B）`)
        }
      } catch (err) {
        // [attach] 观测：原实现 catch 吞掉异常对象，只弹「读取失败」—— 真因（无权限/路径不存在）零留痕
        console.warn(`[attach] 结局=${p} → 读取失败`, err)
        showToast(`读取附件失败：${p}`, 'info')
      }
    }
    // [attach] 观测：过滤前对 addedKeysRef 做快照 —— 下面靠它判定「丢弃是撞到已登记的键还是本批内同键」
    //   ⛔ 只读快照，不改上面 filter 的任何判定
    const addedKeysBefore = new Set(addedKeysRef.current)
    const fresh = pending.filter((a) => {
      if (addedKeysRef.current.has(a.dedupKey)) return false
      addedKeysRef.current.add(a.dedupKey)
      return true
    })
    console.warn(`[attach] 去重结果 pending=${pending.length} → fresh=${fresh.length}`)
    // [attach] 观测：按与上面 filter **完全相同**的规则重放一遍，逐个报出被丢弃的 filename 与被命中的键
    //   ⭐ 键已不是文件名（内容 md5 / 完整路径 / 名+大小）⇒ 日志必须把键打出来，否则
    //   「为什么这两张 image.png 被判成同一份」在现场无从判断。
    const seenKeys = new Set(addedKeysBefore)
    for (const a of pending) {
      if (seenKeys.has(a.dedupKey)) {
        console.warn(`[attach] 去重丢弃 filename=${a.filename} 命中键=${a.dedupKey}`
          + (addedKeysBefore.has(a.dedupKey) ? '（addedKeysRef 已登记）' : '（本批内同键，先到先得）'))
        continue
      }
      seenKeys.add(a.dedupKey)
    }
    console.warn(`[attach] toast 前 fresh.length=${fresh.length}`)
    if (fresh.length) {
      setAttachments((prev) => [...prev, ...fresh])
      showToast(`已添加 ${fresh.length} 个附件`, 'success')
    }
  }

  /**
   * ⭐ 拖拽入口必须走**最新**的 `addPaths`（批 ATT-DRAG-STALE）。
   *
   * <b>WHY（真缺陷 · 有日志铁证）</b>：`onDragDropEvent` 只能在挂载时订阅一次（见下面的 useEffect
   * 依赖数组 —— ⛔ 把 addPaths 放进去会每次渲染重订阅/退订，丢事件甚至监听器泄漏），而 `addPaths`
   * 是**每次渲染新建**的函数，闭包捕获 `localRead` 与 `sessionId`。若监听器直接引用它，锁死的
   * 是**首帧**那一个，而首帧时：
   * <ul>
   *   <li>`localRead`（`App` 初值 false，真实值等 `GET /attachments/config` 回来）⇒ `addPaths` 里
   *       `if (localRead)` 整块被跳过 ⇒ 非图片非 PDF 文件直落 base64 腿 ⇒ 后端对 `type=file` 的
   *       base64 **零消费方** ⇒ **静默丢弃**（实测日志：桌面拖入 2.4MB 的 .docx，记的是「通道=base64」，
   *       且全份日志里 `通道=path` 出现 0 次）；</li>
   *   <li>`sessionId`（store 初值 ''）⇒ >5MB 文件命中 `if (!sessionId)` ⇒ 弹「需先打开一个会话」并丢弃。</li>
   * </ul>
   * ⇒ **同一个文件：拖拽丢、走「附件」对话框能送达**（后者每次渲染新建闭包，拿到的是当前值）。
   *
   * `useLayoutEffect`（而非在渲染体内直接赋值）保证 ref 在每次提交后、**在被动副作用（订阅）之前**
   * 指向最新实现；且它无依赖数组 ⇒ 每次渲染后都刷新。
   */
  const addPathsRef = useRef(addPaths)
  useLayoutEffect(() => { addPathsRef.current = addPaths })

  /** 添加文件按钮：Tauri 桌面 → plugin-dialog.open() 拿绝对路径（localRead path 通道，大文件不 upload）；
   *  浏览器（无绝对路径）→ 原生 file input → File 对象走 upload。 */
  const handleAddFiles = async () => {
    if (IS_TAURI) {
      try {
        const { open } = await import('@tauri-apps/plugin-dialog')
        const sel = await open({ multiple: true })
        const paths = Array.isArray(sel) ? sel : sel ? [sel] : []
        if (paths.length) void addPaths(paths)
        return
      } catch (err) {
        // [attach] 观测：原实现完全静默 —— dialog 不可用会静默回退原生 input，实际通道与预期不符且无痕
        console.warn('[attach] dialog 不可用 → 回退原生 file input', err)
        /* dialog 不可用 → 回退原生 file input */
      }
    }
    fileInputRef.current?.click()
  }

  // Tauri 拖拽事件：WebView 拦截浏览器 drop，改由 onDragDropEvent 拿文件真实路径
  //   ⛔ 依赖数组必须是 []：放进 addPaths 会每次渲染重订阅/退订（丢事件 / 监听器泄漏）。
  //   ⇒ 回调经 addPathsRef 取**当前**实现（首个 bug 就是这里锁死了首帧闭包，见上）。
  useEffect(() => {
    if (!IS_TAURI) return
    let un: (() => void) | null = null
    void getCurrentWebview().onDragDropEvent((e) => {
      // [attach] 观测：enter 与 drop 都带 paths（本仓已知 enter 也带）—— ⛔ 非 drop 类型同样留痕，
      //   否则「只收到 1 个路径」到底是 drop 只给了一个、还是被中间某步丢掉，无法区分
      const p = e.payload
      const pathsIn = p.type === 'enter' || p.type === 'drop' ? p.paths : []
      console.warn(`[attach] 拖拽事件 type=${p.type} paths=${pathsIn.length}`
        + (pathsIn.length ? ` 明细=${pathsIn.join(' | ')}` : ''))
      if (e.payload.type === 'drop') void addPathsRef.current(e.payload.paths)
    }).then((u) => { un = u })
    return () => { un?.() }
  }, [])

  // 拖拽文件到输入框 → 附件（Tauri 走 onDragDropEvent 路径；浏览器走 FileReader）
  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    if (IS_TAURI) return
    if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files)
  }
  const onDragOver = (e: DragEvent<HTMLDivElement>) => { e.preventDefault() }
  // 剪贴板粘贴图片（Ctrl+V）→ 附件。来源三层：① clipboardData.items（位图/浏览器复制图片）
  // ② clipboardData.files（资源管理器复制图片文件 · WebView2 items 常不含 file 项）
  // ③ navigator.clipboard.read() 异步兜底（items/files 均无图片时）。
  const onPaste = async (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const images: File[] = []
    for (const item of e.clipboardData?.items ?? []) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const f = item.getAsFile()
        if (f) images.push(f)
      }
    }
    // 粘贴文件（资源管理器复制图片文件）：items 不含 image → clipboardData.files 兜底
    if (images.length === 0 && e.clipboardData?.files?.length) {
      for (const f of e.clipboardData.files) {
        if (f.type.startsWith('image/')) images.push(f)
      }
    }
    if (images.length > 0) {
      // 有图片 → 阻止默认文本粘贴，走附件（避免图片二进制乱码进文本）
      e.preventDefault()
      addFiles(images)
      console.debug('[paste] 图片走 items/files 路径', images.map((f) => `${f.name}:${f.type}:${f.size}`))
      return
    }
    // 兜底：WebView2 某些场景 items/files 不含图片 → navigator.clipboard.read() 异步读剪贴板图片
    try {
      if (!navigator.clipboard?.read) return
      const entries = await navigator.clipboard.read()
      const files: File[] = []
      for (const entry of entries) {
        for (const type of entry.types) {
          if (type.startsWith('image/')) {
            const blob = await entry.getType(type)
            files.push(new File([blob], `clipboard-${Date.now()}.png`, { type }))
          }
        }
      }
      if (files.length) {
        e.preventDefault()
        addFiles(files)
        console.debug('[paste] 图片走 navigator.clipboard.read 路径', files.map((f) => `${f.name}:${f.type}:${f.size}`))
      }
    } catch (err) {
      // 剪贴板读无权限/无图片内容 → 静默（正常文本粘贴场景不打扰）
      console.debug('[paste] navigator.clipboard.read 无图片或拒绝', err)
    }
  }
  /** 发送：组装附件 req → sendMessage（Enter 与发送按钮共用 · 修复 Enter 丢 attachments bug） */
  const doSend = () => {
    if (attachments.some((a) => a.contentId === '__uploading__')) {
      showToast('附件上传中，请稍候', 'info')
      return
    }
    const req: AttachmentRequest[] = attachments.map((a) => {
      // 对齐 CC PastedContent.content：图片直传发【纯 base64】（非 dataURL）——后端 Base64.decode
      //   + Anthropic image block 都要求纯 base64，dataURL 前缀会让后端落盘失败 / LLM 调用异常（无回复）。
      let b64 = a.base64
      if (b64) {
        const m = /^data:[^;]+;base64,(.+)$/.exec(b64)
        if (m) b64 = m[1]
      }
      return {
        type: a.type,
        filename: a.filename,
        mediaType: a.mediaType,
        ...(b64 ? { base64: b64 } : {}),
        ...(a.contentId ? { contentId: a.contentId } : {}),
        ...(a.path ? { path: a.path } : {}),
      }
    })
    sendMessage(req.length ? req : undefined)
    // ⭐ 释放点③（发送后）：键随 chip 一起清空 ⇒ 同一张图发送后可以再粘一次（不被误判重复）
    addedKeysRef.current.clear()
    setAttachments([])
  }
  // 计划模式已并入权限模式下拉（PermissionMode.plan）· 不再独立 tool-chip
  const hasEditableQueued = queuedCommands.some((c) => c.isEditable)

  /**
   * 拉回全部可编辑排队命令（Esc / 排队条「编辑」按钮 · 批 A5）。
   *
   * <p>分工：`text` 由 `App` 写进输入框（`setComposerText` 归 `App`），**附件由本组件还原**成待发
   * chip（`attachments` state 归本组件）。`null` = 无可拉回项/请求失败 → 零改动（绝不半途清空）。
   *
   * <p>未移除的排队项（cron / channel / task-notification 等不可编辑项）由 `useCommandQueue` 与
   * 后端 `popForEdit` 同源判据留在队列 —— 本组件不做任何本地队列裁剪。
   */
  const handlePopEditable = useCallback(async () => {
    const res = await popEditable()
    if (!res) return
    const restored = restorePendingAttachments(res.attachments)
    if (restored.length === 0) return
    // 登记去重键（与「移除 chip / 清空 / 发送后」三处释放点配对，见 addedKeysRef 注释）
    for (const p of restored) addedKeysRef.current.add(p.dedupKey)
    // 还原的 chip 排在已有 chip **之前** —— 与「排队项文本接在草稿之前」同一顺序语义
    setAttachments((prev) => [...restored, ...prev])
  }, [popEditable])

  // 让高亮层的内容区宽度/高度/滚动位置与 textarea 严格一致（滚动条出现会改变 clientWidth/clientHeight）
  const syncHighlight = () => {
    const ta = textareaRef.current
    const hl = highlightRef.current
    if (!ta || !hl) return
    hl.style.width = `${ta.clientWidth}px`
    hl.style.height = `${ta.clientHeight}px`
    hl.scrollTop = ta.scrollTop
    hl.scrollLeft = ta.scrollLeft
  }

  // 输入变化可能引入/移除滚动条（内容区宽度随之变化），需在绘制前重新对齐；
  // 同时 textarea 随内容自动增高（min 22px → max 200px）
  useLayoutEffect(() => {
    syncHighlight()
    const ta = textareaRef.current
    if (ta) {
      ta.style.height = 'auto'
      ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`
    }
  }, [composerText])

  return (
    <div className={`composer${empty ? ' composer-empty' : ''}`} onDrop={onDrop} onDragOver={onDragOver}>
      {/* 空态 hero：logo 动画 + 标题 + quick-actions（对齐主界面原型） */}
      {empty && (
        <div className="welcome">
          <CatArtBody className="welcome-logo" />
          <div className="hero-title">你好，今天有什么可以帮忙的</div>
          <div className="quick-actions">
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>⚡ 生成实体提取脚本</div>
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>📝 优化代码注释</div>
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>🔍 检查架构逻辑</div>
          </div>
        </div>
      )}
      {/* F19/#3 排队命令条（输入框上方，后端 B5 未接恒空隐藏） */}
      {/* 「编辑」按钮与 Esc 走同一个入口（文本 + 附件一起拉回） */}
      <QueuedCommandsBar queuedCommands={queuedCommands} onEdit={() => void handlePopEditable()} />
      <div className="composer-inner">
        {/* 输入框上方一行：左=项目绑定，右=模型选择器（设计稿 v7） */}
        <div className="composer-top">
          <div
            className={`project-binder${modeLocked ? ' locked' : ''}`}
            title={modeLocked ? '对话进行中不可切换项目（仅新会话可切换）' : '选择项目'}
            onClick={() => {
              // 项目绑定与 full 模式一致：对话进行中锁定（仅新会话可切换）
              if (modeLocked) {
                showToast('对话进行中不可切换项目（仅新会话可切换）', 'info')
                return
              }
              onSelectProject()
            }}
          >
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />
            </svg>
            <span>{boundProjectName ?? '未选中项目'}</span>
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 9, height: 9, opacity: 0.6 }}>
              <path d="M3 4.5L6 7.5L9 4.5" />
            </svg>
          </div>
          {/* Mode 下拉（原型）：simple/full · 对话进行中锁定（仅新会话可切换） */}
          <div
            className={`toolbar-select${modeLocked ? ' locked' : ''}`}
            title={modeLocked ? '对话进行中不可切换模式（仅新会话可切换）' : '切换模式'}
            onClick={() => {
              if (modeLocked) {
                showToast('对话进行中不可切换模式（仅新会话可切换）', 'info')
                return
              }
              setModeOpen((v) => !v)
            }}
          >
            <span>{mode} 模式</span> ▾
            {!modeLocked && modeOpen && (
              <div className="mode-dropdown">
                {(['simple', 'full'] as const).map((m) => (
                  <div
                    key={m}
                    className={`mode-item ${m === mode ? 'selected' : ''}`}
                    onClick={(e) => { e.stopPropagation(); onModeChange?.(m === 'simple'); setModeOpen(false) }}
                  >{m}</div>
                ))}
              </div>
            )}
          </div>
          {/* V58 主线程 agent（专家）胶囊 · 显示当前驱动 agent · 放「模式」下拉右侧 · 点开技能市场 */}
          <AgentSelector
            currentAgent={currentAgent}
            onOpen={() => { if (onOpenMarket) onOpenMarket() }}
          />
          {/* 输入框上方右缘操作组（右对齐输入框右缘）：回到底部（离底时）+ 停止所有（运行中） */}
          <div className="composer-top-actions">
            {showToBottom && (
              <button
                className="composer-top-btn"
                onClick={onScrollToBottom}
                title="回到最底部"
                aria-label="回到最底部"
              >
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
                  <path d="M2 5l4 4 4-4" />
                </svg>
                <span>回到底部</span>
              </button>
            )}
            {/* 强行停止所有：一键终止当前会话全部运行任务（后台子代理/续跑一并停止），替代难触发的双击 Esc */}
            {onHardStop && streaming && (
              <button
                className="composer-top-btn hard-stop"
                onClick={onHardStop}
                title="强制终止当前会话所有运行中的任务（含后台子代理/续跑）"
                aria-label="停止所有"
              >
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 10, height: 10 }}>
                  <path d="M3 3l6 6M9 3l-6 6" />
                </svg>
                <span>停止所有</span>
              </button>
            )}
          </div>
        </div>
        <div className="input-box">
          <div style={{ position: 'relative' }}>
            <div
              ref={highlightRef}
              aria-hidden="true"
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                right: 0,
                bottom: 0,
                overflow: 'hidden',
                pointerEvents: 'none',
                whiteSpace: 'pre-wrap',
                color: 'transparent',
                zIndex: 2, // 置于 textarea(z1) 之上：@chip 可命中 hover/click（container 本身 pointer-events:none 放行到 textarea）
                ...HIGHLIGHT_TEXT_STYLE,
              }}
            >
              {renderHighlighted(composerText, removeAtChip)}
            </div>
            <textarea
              ref={textareaRef}
              placeholder="向 nexus 提问，或输入 / 调出命令"
              value={composerText}
              onChange={(e) => setComposerText(e.target.value)}
              onScroll={syncHighlight}
              onPaste={onPaste}
              onKeyDown={(e) => {
                // [Phase3 @引用] 候选打开时优先：↑↓ 选择 · Tab/Enter 引用 · Esc 关闭（不发送）
                if (atMatches) {
                  if (e.key === 'ArrowDown') { e.preventDefault(); setAtIndex((i) => (i + 1) % atMatches.length); return }
                  if (e.key === 'ArrowUp') { e.preventDefault(); setAtIndex((i) => (i - 1 + atMatches.length) % atMatches.length); return }
                  if (e.key === 'Tab' || e.key === 'Enter') {
                    e.preventDefault()
                    applyAtPick(atMatches[atIndex % atMatches.length])
                    return
                  }
                  if (e.key === 'Escape') {
                    e.preventDefault()
                    if (atTailIndex) {
                      setComposerText(atTailIndex.segStartAbs >= 0 ? composerText.slice(0, atTailIndex.segStartAbs + 1) : '')
                    }
                    e.stopPropagation()
                    return
                  }
                }
                // 命令即时提示打开时：↑↓ 选择 · Tab/Enter 补全命令
                if (cmdMatches) {
                  if (e.key === 'ArrowDown') { e.preventDefault(); setCmdIndex((i) => (i + 1) % cmdMatches.length); return }
                  if (e.key === 'ArrowUp') { e.preventDefault(); setCmdIndex((i) => (i - 1 + cmdMatches.length) % cmdMatches.length); return }
                  if (e.key === 'Tab' || e.key === 'Enter') {
                    e.preventDefault()
                    const cmd = cmdMatches[cmdIndex % cmdMatches.length]
                    // /plugin:skill 补全保持插件前缀（用户输入含 ':' 且命令有插件名 → CC 格式）
                    const keepPlugin = composerText.slice(1).includes(':') && !!cmd.pluginName
                    setComposerText(`/${keepPlugin ? `${cmd.pluginName}:` : ''}${cmd.name} `)
                    setCmdIndex(0)
                    return
                  }
                }
                if (e.key === 'Enter') {
                  // Enter 发送；Shift+Enter / Alt+Enter 换行（textarea 默认行为，不拦截）
                  if (!e.shiftKey && !e.altKey && !e.metaKey && !e.ctrlKey) {
                    e.preventDefault()
                    doSend()
                  }
                } else if (e.key === 'Escape') {
                  // F19 交互矩阵（对齐 CC PromptInput.tsx:1916 + useCancelRequest.ts）：
                  //   ① 命令补全打开 → 关闭补全（对齐 CC overlay Esc 优先，不触发 stop）
                  //   ② 有可编辑排队命令 → pop 编辑
                  //   ③ turn 加载中的 Esc 停止交由 App window 统一处理（两下 Esc killAllAgents
                  //      对齐 CC KILL_AGENTS_CONFIRM_WINDOW_MS=3000）——本层不再调 onStop，否则
                  //      stopStreaming 异步清 activeStreams 使 turnRunning 翻 false，第二下 Esc
                  //      会误走空闲分支导致两下确认失效。
                  //   ④ 空输入双击 Esc → 消息选择器（App 全局处理，Composer 不干预）
                  e.preventDefault()
                  if (cmdMatches) {
                    setComposerText(composerText.replace(/\/\S*$/, ''))
                    e.stopPropagation()
                  } else if (hasEditableQueued) {
                    // 批 A5：拉回【全部】可编辑排队项（文本由 App 回填 + 本组件还原附件）
                    void handlePopEditable()
                    e.stopPropagation()
                  }
                }
              }}
              style={{
                position: 'relative',
                background: 'transparent',
                color: 'var(--ink)',
                zIndex: 1,
                ...HIGHLIGHT_TEXT_STYLE,
              }}
            ></textarea>
            {cmdMatches && (
              <div className="slash-menu" ref={slashMenuRef}>
                {cmdMatches.map((c, i) => (
                  <div
                    key={c.name}
                    className={`slash-item ${i === cmdIndex % cmdMatches.length ? 'active' : ''}`}
                    onMouseEnter={() => setCmdIndex(i)}
                    onClick={() => {
                      const keepPlugin = composerText.slice(1).includes(':') && !!c.pluginName
                      setComposerText(`/${keepPlugin ? `${c.pluginName}:` : ''}${c.name} `)
                      setCmdIndex(0)
                    }}
                  >
                    <span className="slash-name">/{c.name}</span>
                    {c.pluginName && (
                      <span style={{ fontSize: 9.5, fontFamily: 'var(--font-mono)', color: 'var(--ink-faint)', marginLeft: 6 }}>{c.pluginName}</span>
                    )}
                    <span className="slash-desc">{c.description}</span>
                  </div>
                ))}
              </div>
            )}
            {atMatches && (
              <div className="at-menu" ref={atMenuRef}>
                <div className="at-menu-cap">@ 引用文件 · {boundProjectName ?? '绑定项目'}</div>
                {atMatches.map((p, i) => (
                  <div
                    key={p}
                    className={`at-item ${i === atIndex % atMatches.length ? 'active' : ''}`}
                    onMouseEnter={() => setAtIndex(i)}
                    onClick={() => applyAtPick(p)}
                    title={p}
                  >
                    <span className="at-at">@</span>
                    <span className="at-path">{p}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
          {attachments.length > 0 && (
            <div className="attach-preview">
              {attachments.map((a, i) => (
                <div key={`${a.filename}-${i}`} className="attach-item" title={a.filename}>
                  {a.preview
                    ? <img src={a.preview} alt={a.filename} className="attach-thumb" onClick={() => setZoomImg(a.preview!)} style={{ cursor: 'zoom-in' }} />
                    : <span className="attach-file">{a.filename}</span>}
                  <button
                    className="attach-remove"
                    onClick={() => {
                      // ⭐ 释放点①：必须用该 chip 的 **dedupKey**（键已不是文件名 —— 用 filename 释放
                      //   会「移除了 chip 但键还在」⇒ 同一张图再也加不回来）
                      addedKeysRef.current.delete(a.dedupKey)
                      setAttachments((prev) => prev.filter((_, j) => j !== i))
                    }}
                    title="移除附件"
                  >×</button>
                </div>
              ))}
            </div>
          )}
          <div className="toolbar">
            <div className="left-tools">
              {/* F20 附件：原生 file input 选文件（web 通用；Tauri 桌面后续可换 plugin-dialog 拿绝对路径） */}
              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => {
                  if (e.target.files?.length) addFiles(e.target.files)
                  e.target.value = ''
                }}
              />
              <div className="tool-chip" onClick={() => void handleAddFiles()}>
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M6 2V10M2 6H10" />
                </svg>
                <span>附件{attachments.length > 0 ? ` (${attachments.length})` : ''}</span>
              </div>
              {/* ⭐ 释放点②（清空）：`clear()` 与键的具体形态无关 ⇒ 改键后无需改动（逐字核过） */}
              {attachments.length > 0 && (
                <button className="tool-chip" onClick={() => { addedKeysRef.current.clear(); setAttachments([]); showToast('已清空附件', 'info') }} title="清空附件">
                  <span>清空</span>
                </button>
              )}
              {/* NexusAI in Chrome：输入框工具栏浏览器按钮 → 打开 ChromePanel（检查/安装/连接引导） */}
              {onOpenChromePanel && (
                <div className="tool-chip" onClick={onOpenChromePanel} title="NexusAI in Chrome：浏览器自动化扩展（检查/安装/连接）">
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <circle cx="6" cy="6" r="4" />
                    <path d="M2 6h8M6 2a4.5 4.5 0 0 1 0 8M6 2a4.5 4.5 0 0 0 0 8" />
                  </svg>
                  <span>浏览器</span>
                </div>
              )}
              {/* 会话工具面板（功能2 · 禁用/恢复 · 会话级临时禁用） */}
              <div className="tools-chip-wrap" ref={toolsPanelRef}>
                <div
                  className={`tool-chip tools-chip${toolsOpen ? ' active' : ''}`}
                  onClick={() => setToolsOpen((v) => !v)}
                  title="会话工具禁用/恢复"
                >
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <path d="M6 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM1 6h.01M11 6h.01M6 1v.01M6 11v.01" />
                  </svg>
                  <span>工具</span>
                  {toolsDisabledCount > 0 && <span className="tools-badge">{toolsDisabledCount}</span>}
                </div>
                {toolsOpen && (
                  <SessionToolsPanel
                    sessionId={sessionId ?? ''}
                    showToast={showToast}
                    onClose={() => setToolsOpen(false)}
                    onCountChange={setToolsDisabledCount}
                  />
                )}
              </div>
            </div>
            <div className="right-tools">
              {onPermissionModeChange && (
                <div className="perm-mode-pill-wrap" ref={permRef}>
                  <div className="perm-mode-pill" onClick={() => setPermOpen((v) => !v)} title="权限模式（会话覆盖全局）">
                    <svg className="pm-icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
                      <path d="M7 1.5L12 3.5V6.5C12 9.5 9.8 11.8 7 12.5C4.2 11.8 2 9.5 2 6.5V3.5L7 1.5Z" />
                    </svg>
                    <span className="pm-label">权限模式:</span>
                    <span className="pm-value">{PERMISSION_MODE_LABELS[permissionMode]}</span>
                    <svg className={`pm-chevron ${permOpen ? 'open' : ''}`} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2.2" style={{ width: 12, height: 12 }}>
                      <path d="M2 4L6 8L10 4" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                  {permOpen && (
                    <div className="pill-drawer perm-mode-drawer">
                      {(Object.keys(PERMISSION_MODE_LABELS) as PermissionMode[]).map((m) => (
                        <div
                          key={m}
                          className={`pill-row pill-option ${permissionMode === m ? 'active' : ''}`}
                          title={PERMISSION_MODE_DESCRIPTIONS[m]}
                          onClick={() => { setPermOpen(false); onPermissionModeChange(m) }}
                        >
                          <span className="pill-label">{PERMISSION_MODE_LABELS[m]}</span>
                          <span className="pill-desc">{PERMISSION_MODE_DESCRIPTIONS[m]}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {(onOpenModelPicker || onOpenEffort) && (
                <div className="model-effort-pill-wrap" ref={pillRef}>
                  <div className="model-effort-pill" onClick={() => setPillOpen((v) => !v)} title="模型与推理等级">
                    <span className="mep-model">{shortModel || '选择模型'}</span>
                    <span className="mep-effort">{ultracodeEnabled ? 'ultracode' : (effortLevel ?? 'high')}</span>
                    <svg className={`mep-chevron ${pillOpen ? 'open' : ''}`} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2.2" style={{ width: 13, height: 13 }}>
                      <path d="M2 4L6 8L10 4" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                  {pillOpen && (
                    <div className="pill-drawer">
                      {onOpenModelPicker && (
                        <div className="pill-row" onClick={() => { setPillOpen(false); onOpenModelPicker() }}>
                          <span className="pill-label">模型</span>
                          <span className="pill-value">{shortModel || '选择模型'} ›</span>
                        </div>
                      )}
                      {onOpenEffort && (
                        <div className="pill-row" onClick={() => { setPillOpen(false); onOpenEffort() }}>
                          <span className="pill-label">推理等级</span>
                          <span className="pill-value">{ultracodeEnabled ? 'ultracode' : (effortLevel ?? 'high')} ›</span>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
              {streaming ? (
                <button className="send-btn danger" onClick={onStop} title="停止生成">
                  <span>停止</span>
                  {/* 实心方块：svg 17px × 方块占满 12 单位中 10 → 视觉高≈14px（与「停止」文字字号同高） */}
                  <svg viewBox="0 0 12 12" fill="currentColor" aria-hidden="true" style={{ width: 17, height: 17 }}>
                    <rect x="1" y="1" width="10" height="10" rx="1.5" />
                  </svg>
                </button>
              ) : (
                <button
                  className="send-btn"
                  onClick={() => doSend()}
                  disabled={!composerText.trim() && attachments.length === 0}
                >
                  <span>发送</span>
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <path d="M3 6H9M9 6L6 3M9 6L6 9" />
                  </svg>
                </button>
              )}
            </div>
          </div>
        </div>
        <div className="hint">
          <span className="hint-shortcuts">
            <kbd>↵</kbd> 发送 · <kbd>⇧↵</kbd> / <kbd>⌥↵</kbd> 换行 · <kbd>/</kbd> 调出命令 · <kbd>Esc</kbd> 停止/取消
          </span>
          {/* F1 · hint-usage 包可点击（打开 UsageCostModal）· tokens 标注「累计」· 附当前上下文条 */}
          {onOpenUsageCost && (
            <span
              className="hint-usage clickable"
              onClick={onOpenUsageCost}
              role="button"
              title="点击查看用量与花费明细"
            >
              {lastSpeedTs != null && <span className="hu-speed">· {lastSpeedTs} t/s</span>}
              {sessionUsage?.totalTokens != null && sessionUsage.totalTokens > 0 && <span className="hu-tokens">⚡ 累计 {compactNumber(sessionUsage.totalTokens)} tokens</span>}
              {ctxInfo && (
                <span className={`hu-ctx${ctxInfo.pct != null ? (ctxInfo.pct > 80 ? ' ok' : ctxInfo.pct >= 40 ? ' warn' : ' hot') : ''}`}>
                  · 当前上下文 {compactNumber(ctxInfo.used)} / {compactNumber(ctxInfo.window)}
                  {ctxInfo.pct != null ? `（${ctxInfo.pct}%）` : ''}
                </span>
              )}
              {cacheRateInfo && (
                <span className={`hu-cache${cacheRateInfo.rate >= 50 ? '' : ' warn'}`} title={`缓存读取 ${compactNumber(cacheRateInfo.read)} tokens`}>
                  · 缓存 {cacheRateInfo.rate}%
                </span>
              )}
              {sessionUsage?.totalCostYuan != null && sessionUsage.totalCostYuan > 0 && <span className="hu-cost">· ¥{sessionUsage.totalCostYuan.toFixed(2)}</span>}
            </span>
          )}
        </div>
      </div>
      {/* 缩略图放大预览（点击任意处/Esc 关闭） */}
      {zoomImg && (
        <div className="attach-zoom" onClick={() => setZoomImg(null)}>
          <img src={zoomImg} alt="附件预览" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  )
}
