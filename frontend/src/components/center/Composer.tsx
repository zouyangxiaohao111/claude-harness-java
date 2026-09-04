import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ClipboardEvent, CSSProperties, DragEvent, ReactNode } from 'react'
import { QueuedCommandsBar } from './QueuedCommandsBar'
import { SessionToolsPanel } from './SessionToolsPanel'
import type { QueuedCommand } from '@/hooks/useCommandQueue'
import { PERMISSION_MODE_LABELS, PERMISSION_MODE_DESCRIPTIONS, type PermissionMode } from '@/api/types'
import type { AttachmentRequest, SessionDto, ChatMessageDto } from '@/api/types'
import { uploadAttachment } from '@/api/chat'
import { AgentSelector } from './AgentSelector'
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { readFile, stat } from '@tauri-apps/plugin-fs'
import { COMMAND_ITEMS } from './CommandPalette'
import { commandApi, type CommandDto } from '@/api/command'
import { compactNumber } from '@/utils/format'
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
  base64?: string   // ≤5MB 直传内容（图片 dataURL）
  contentId?: string // 大文件 upload 后后端附件表缓存 id
  path?: string     // local-read 模式本地绝对路径（>5MB 不 upload，后端同机读盘）
  preview?: string  // 图片缩略图
  size?: number
}

/** 5MB 直传上限（后端 MediaLimitGuard） */
const BASE64_LIMIT = 5 * 1024 * 1024

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
  popEditable: () => void
  /** 当前绑定项目名（null=未绑定，显示"未选中"） */
  boundProjectName: string | null
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

// 把输入文本拆成「普通片段 + token target 高亮片段」
function renderHighlighted(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(TOKEN_BUDGET_RE)) {
    const idx = m.index ?? 0
    if (idx > last) nodes.push(text.slice(last, idx))
    nodes.push(<span key={idx} style={HIGHLIGHT_SPAN_STYLE}>{m[0]}</span>)
    last = idx + m[0].length
  }
  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}

export function Composer({ composerText, setComposerText, sendMessage, showToast, streaming, onStop, queuedCommands, popEditable, boundProjectName, onSelectProject, currentModel, permissionMode, onPermissionModeChange, effortLevel, ultracodeEnabled, bareMode, onModeChange, onOpenModelPicker, onOpenEffort, empty, sessionId, onOpenUsageCost, onOpenChromePanel, showToBottom, onScrollToBottom, onHardStop, localRead, currentAgent, onOpenMarket }: ComposerProps) {
  // 模型名显示末段（去掉 provider 前缀，如 ds-openai/deepseek-v4-flash → deepseek-v4-flash）
  const shortModel = currentModel?.split('/').pop() ?? currentModel ?? ''
  // 会话 token/金额汇总（底部 footer · 与 hint-shortcuts 对称）：complete 事件实时覆盖 + F5 从会话列表恢复
  const sessionUsage = useChatStore((s) => s.sessions.find((x) => x.id === sessionId))
  // F1/F5 · 当前上下文「已用 / 窗口（剩余%）」：优先末条带快照（实时=流式块 message.usage 挂载、
  //   complete 落库消息），无则回落 token_warning 事件（tokenUsage/contextWindow/percentLeft）。
  //   扫描源 = [...msgs, ...liveBlocks]：live 块排尾部 → 从尾向前命中最新流式块的 usage/上下文，
  //   多轮 turn 内每条 assistant message.usage 到达即实时刷新；turn 完成清流后纯 msgs 兜底。
  const msgs = useChatStore((s) => (sessionId ? (s.messages[sessionId] ?? EMPTY_MESSAGES) : EMPTY_MESSAGES))
  const liveBlocks = useChatStore((s) => (sessionId ? (s.streams[sessionId] ?? EMPTY_BLOCKS) : EMPTY_BLOCKS))
  const tokenWarning = useChatStore((s) => s.tokenWarning)
  const ctxInfo = useMemo(() => {
    const scanned = [...msgs, ...liveBlocks]
    for (let i = scanned.length - 1; i >= 0; i--) {
      const m = scanned[i]
      if (m.contextTokensUsed != null && m.contextWindow != null) {
        return { used: m.contextTokensUsed, window: m.contextWindow, pct: m.percentLeft ?? null }
      }
    }
    if (tokenWarning && tokenWarning.tokenUsage != null && tokenWarning.contextWindow != null) {
      return { used: tokenWarning.tokenUsage, window: tokenWarning.contextWindow, pct: tokenWarning.percentLeft ?? null }
    }
    return null
  }, [msgs, liveBlocks, tokenWarning])
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
  // 去重：Tauri onDragDropEvent 与浏览器 drop 可能双触发，同文件只添加一次
  const addedNamesRef = useRef<Set<string>>(new Set())
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
    commandApi.list().then((cs) => { if (alive) setRemoteCommands(cs) }).catch(() => {})
    return () => { alive = false }
  }, [])
  const cmdMatches = useMemo(() => {
    const t = composerText
    if (!t.startsWith('/') || t.includes(' ')) return null
    const q = t.slice(1).toLowerCase()
    // 合并本地内置 + 后端技能命令（技能名 / 插件名前缀触发提示，如 /update-config、/zjkycode）
    const all: { name: string; description: string; aliases?: string[]; pluginName?: string }[] = [...COMMAND_ITEMS]
    for (const r of remoteCommands) {
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

  // 附件（A1 契约）：≤5MB 读 base64 直传（图片带预览）；>5MB multipart upload 拿 contentId
  const addFiles = (fileList: FileList | File[]) => {
    const files = Array.from(fileList)
    if (files.length === 0) return
    files.forEach((f) => {
      const mediaType = f.type || 'application/octet-stream'
      const type: PendingAttachment['type'] = mediaType.startsWith('image/') ? 'image'
        : f.name.toLowerCase().endsWith('.pdf') ? 'pdf'
        : mediaType.startsWith('video/') ? 'video'
        : mediaType.startsWith('audio/') ? 'audio'
        : 'file'
      if (f.size > BASE64_LIMIT) {
        // 大文件 → multipart 上传落盘 → contentId
        if (addedNamesRef.current.has(f.name)) return
        addedNamesRef.current.add(f.name)
        setAttachments((prev) => [...prev, { type, filename: f.name, mediaType, size: f.size, contentId: '__uploading__' }])
        void uploadAttachment(f, sessionId)
          .then((r) => setAttachments((prev) => prev.map((a) =>
            a.contentId === '__uploading__' && a.filename === f.name ? { ...a, contentId: r.contentId } : a)))
          .catch(() => {
            setAttachments((prev) => prev.filter((a) => !(a.contentId === '__uploading__' && a.filename === f.name)))
            showToast(`大文件上传失败：${f.name}`, 'info')
          })
      } else {
        // ≤5MB → base64 直传（图片 dataURL 预览）
        const reader = new FileReader()
        reader.onload = () => {
          if (addedNamesRef.current.has(f.name)) return
          addedNamesRef.current.add(f.name)
          const dataUrl = reader.result as string
          setAttachments((prev) => [...prev, {
            type, filename: f.name, mediaType, size: f.size,
            base64: dataUrl,
            ...(type === 'image' ? { preview: dataUrl } : {}),
          }])
        }
        reader.readAsDataURL(f)
      }
    })
    showToast(`已添加 ${files.length} 个附件`, 'success')
  }

  // Tauri：拖拽文件路径 → fs 读 → base64（≤5MB 直传）/ upload（>5MB 拿 contentId）
  const addPaths = async (paths: string[]) => {
    const pending: PendingAttachment[] = []
    for (const p of paths) {
      try {
        const name = p.split(/[\\/]/).pop() ?? p
        const lower = name.toLowerCase()
        const isImage = /\.(png|jpe?g|gif|webp|bmp)$/.test(lower)
        const isPdf = lower.endsWith('.pdf')
        const type: PendingAttachment['type'] = isImage ? 'image' : isPdf ? 'pdf'
          : /\.(mp4|webm|mov)$/.test(lower) ? 'video'
          : /\.(mp3|wav|ogg|m4a)$/.test(lower) ? 'audio' : 'file'
        const mediaType = isImage ? 'image/*' : isPdf ? 'application/pdf'
          : type === 'video' ? 'video/*' : type === 'audio' ? 'audio/*' : 'application/octet-stream'
        // [local-read] 前后端同机：>5MB 拖拽附件直接传本地 path（后端同机读盘 + 注册附件表，省一次 upload 拷贝）——
        //   plugin-fs stat 拿 size 判定，不整读大文件进内存
        if (localRead) {
          const info = await stat(p)
          if (info.size > BASE64_LIMIT) {
            pending.push({ type, filename: name, mediaType, path: p, size: info.size })
            continue
          }
        }
        const bytes = await readFile(p)
        if (bytes.length > BASE64_LIMIT) {
          // >5MB → multipart upload 落盘 → contentId
          const file = new File([bytes], name, { type: mediaType })
          const r = await uploadAttachment(file, sessionId)
          pending.push({ type, filename: name, mediaType, contentId: r.contentId, size: bytes.length })
        } else {
          const dataUrl = `data:${mediaType};base64,${u8ToBase64(bytes)}`
          pending.push({ type, filename: name, mediaType, base64: dataUrl, ...(isImage ? { preview: dataUrl } : {}), size: bytes.length })
        }
      } catch {
        showToast(`读取附件失败：${p}`, 'info')
      }
    }
    const fresh = pending.filter((a) => {
      if (addedNamesRef.current.has(a.filename)) return false
      addedNamesRef.current.add(a.filename)
      return true
    })
    if (fresh.length) {
      setAttachments((prev) => [...prev, ...fresh])
      showToast(`已添加 ${fresh.length} 个附件`, 'success')
    }
  }

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
      } catch {
        /* dialog 不可用 → 回退原生 file input */
      }
    }
    fileInputRef.current?.click()
  }

  // Tauri 拖拽事件：WebView 拦截浏览器 drop，改由 onDragDropEvent 拿文件真实路径
  useEffect(() => {
    if (!IS_TAURI) return
    let un: (() => void) | null = null
    void getCurrentWebview().onDragDropEvent((e) => {
      if (e.payload.type === 'drop') void addPaths(e.payload.paths)
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
    addedNamesRef.current.clear()
    setAttachments([])
  }
  // 计划模式已并入权限模式下拉（PermissionMode.plan）· 不再独立 tool-chip
  const hasEditableQueued = queuedCommands.some((c) => c.isEditable)

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
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="welcome-logo">
            <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm0 18a8 8 0 1 1 8-8 8 8 0 0 1-8 8z" />
            <path d="M12 6a6 6 0 1 0 6 6 6 6 0 0 0-6-6zm0 10a4 4 0 1 1 4-4 4 4 0 0 1-4 4z" />
          </svg>
          <div className="hero-title">你好，今天有什么可以帮忙的</div>
          <div className="quick-actions">
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>⚡ 生成实体提取脚本</div>
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>📝 优化代码注释</div>
            <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>🔍 检查架构逻辑</div>
          </div>
        </div>
      )}
      {/* F19/#3 排队命令条（输入框上方，后端 B5 未接恒空隐藏） */}
      <QueuedCommandsBar queuedCommands={queuedCommands} onEdit={popEditable} />
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
                zIndex: 0,
                ...HIGHLIGHT_TEXT_STYLE,
              }}
            >
              {renderHighlighted(composerText)}
            </div>
            <textarea
              ref={textareaRef}
              placeholder="向 nexus 提问，或输入 / 调出命令"
              value={composerText}
              onChange={(e) => setComposerText(e.target.value)}
              onScroll={syncHighlight}
              onPaste={onPaste}
              onKeyDown={(e) => {
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
                    popEditable()
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
                      addedNamesRef.current.delete(attachments[i].filename)
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
              {attachments.length > 0 && (
                <button className="tool-chip" onClick={() => { addedNamesRef.current.clear(); setAttachments([]); showToast('已清空附件', 'info') }} title="清空附件">
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
                <span className={`hu-ctx${ctxInfo.pct != null ? (ctxInfo.pct <= 5 ? ' hot' : ctxInfo.pct <= 20 ? ' warn' : '') : ''}`}>
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
