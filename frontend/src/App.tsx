import './styles/globals.css'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  allProjects,
  models,
  searchItems,
  getDiffFor,
  sessionContexts,
  newSessionContext,
} from '@/data'
import type { Project, Session, SettingsTab, ModelTag } from '@/types'
import type { AppSettings, AttachmentRequest, SessionDto, ChatMessageDto, UpdateSettingsRequest, PermissionMode, MarketExpert } from '@/api/types'
import { sessionApi } from '@/api/sessions'
import { marketApi } from '@/api/market'
import { projectApi, type ProjectDto } from '@/api/projects'
import { selectProjectFolder } from '@/utils/projectFolder'
import { isAbsolutePath, normalizePath } from '@/utils/path'
import { chatApi } from '@/api/chat'
import { commandApi } from '@/api/command'
import { tasksApi } from '@/api/tasks'
import { settingsApi } from '@/api/settings'
import { attachmentApi } from '@/api/attachment'
import { ApiError } from '@/api/rest'
import { debugLog } from '@/utils/debugLog'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { useAwaySummary } from '@/hooks/useAwaySummary'
import { sendPermissionResponse } from '@/api/socket'
import { useClickOutside, useEscapeKey, useTheme, useFontSize, useAnimations, useToastDispatcher } from '@/hooks'
import { useProviders } from '@/hooks/useProviders'
import { useSkills } from '@/hooks/useSkills'
import { useMcp } from '@/hooks/useMcp'
import { useDatabases } from '@/hooks/useDatabases'
import { useSchedules } from '@/hooks/useSchedules'
import { useSession, useProject, useUI, useSettings } from '@/reducers'

import { TitleBar } from '@/components/layout/TitleBar'
import { MenuBar } from '@/components/layout/MenuBar'
import { SessionList } from '@/components/left/SessionList'
import { MessageList } from '@/components/center/MessageList'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { DialogOpsModal } from '@/components/center/DialogOpsModal'
import { RetryBanner } from '@/components/center/RetryBanner'
import { TokenWarningBanner } from '@/components/center/TokenWarningBanner'
import { CompactProgressBar } from '@/components/center/CompactProgressBar'
import { NotificationBanner } from '@/components/center/NotificationBanner'
import { Composer } from '@/components/center/Composer'
import { TraceView } from '@/components/center/TraceView'
import { useCommandQueue } from '@/hooks/useCommandQueue'
import { CommandPalette, isKnownCommand } from '@/components/center/CommandPalette'
import { AgentsPanel } from '@/components/center/AgentsPanel'
import { ChromePanel } from '@/components/center/ChromePanel'
import { SkillSurvey } from '@/components/center/SkillSurvey'
import { RightPanel } from '@/components/right/RightPanel'
import { ProjectContextMenu } from '@/components/right/ProjectContextMenu'
import { DiffModal } from '@/components/modals/DiffModal'
import { FileViewModal } from '@/components/modals/FileViewModal'
import { SearchPalette } from '@/components/modals/SearchPalette'
import { SettingsModal } from '@/components/modals/SettingsModal'
import { ModelPickerModal } from '@/components/modals/ModelPickerModal'
import { EffortModal } from '@/components/modals/EffortModal'
import { ContextAnalyzeModal } from '@/components/modals/ContextAnalyzeModal'
import { UsageCostModal } from '@/components/modals/UsageCostModal'
import { MemoryEditorModal } from '@/components/modals/MemoryEditorModal'
import { SkillMarketModal } from '@/components/modals/SkillMarketModal'
import { IncludeApprovalModal } from '@/components/modals/IncludeApprovalModal'
import { getIncludeStatus } from '@/api/claudeMd'
import { Toast } from '@/components/common/Toast'

/** Per-session project state: main, subs, expanded toggles, transient flash. */
interface SessionProjectState {
  main: Project
  subs: Project[]
  expanded: Record<string, boolean>
  flashing: string | null
}

/** 后端会话列表为空时的稳定兜底（避免 {} as SessionDto 的不安全断言，字段全默认值）。 */
const EMPTY_SESSION_DTO: SessionDto = {
  id: '', model: null, modelName: null, title: '', time: null, group: null,
  tabId: null, mainProjectId: null, effortLevel: null, ultracodeEnabled: null, bareMode: null, messageCount: null,
}

/** 后端 SessionDto → UI 旧 Session 形状（null 兜底，任务 9 起组件原生消费 DTO 后移除）。 */
/** 稳定空数组（避免 selector `?? []` 每次返回新引用触发 useSyncExternalStore 无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []

function toSession(d: SessionDto): Session {
  return {
    id: d.id,
    model: d.model ?? 'DS',
    modelName: d.modelName ?? '',
    title: d.title,
    time: d.time ?? '',
    group: d.group ?? 'current',
    tabId: d.tabId ?? undefined,
  }
}

function App() {
  // ---- 4 reducers own overlay / settings state ----
  const { state: sessionR, dispatch: sessionDispatch } = useSession()
  const { state: projectR, dispatch: projectDispatch } = useProject()
  const { state: ui, dispatch: uiDispatch } = useUI()
  const { state: settings, dispatch: settingsDispatch } = useSettings()

  // ---- 真实后端：4 个 settings 面板的 hook（Provider/Model + Skill/MCP/Database/Schedule） ----
  const providersApi = useProviders()
  const skillsApi = useSkills()
  const mcpApi = useMcp()
  const databasesApi = useDatabases()
  const schedulesApi = useSchedules()

  // ---- all sessions：来自后端（chatStore.sessions，挂载时 sessionApi.list() 拉取）----
  const storeSessions = useChatStore((s) => s.sessions)
  const setSessions = useChatStore((s) => s.setSessions)

  // ---- per-session project state (so right panel syncs) ----
  const initialPerSession: Record<string, SessionProjectState> = {}
  Object.keys(sessionContexts).forEach((id) => {
    const c = sessionContexts[id]
    initialPerSession[id] = {
      main: c.mainProject,
      subs: c.subProjects,
      expanded: {},
      flashing: null,
    }
  })
  const [perSessionProjects, setPerSessionProjects] = useState<Record<string, SessionProjectState>>(initialPerSession)
  // #1 绑定策略：真实项目缓存（projectApi.list），供 projectNameFor / createSession 反查 main 项目名，
  //   替代无 id 的 mock allProjects。挂载时拉取，失败优雅降级（回落 mock）。
  const [realProjects, setRealProjects] = useState<Project[]>([])
  useEffect(() => {
    let cancelled = false
    projectApi.list()
      .then((list) => { if (!cancelled) setRealProjects(list.map((d) => ({ id: d.id, name: d.name, branch: d.branch ?? '', dirty: d.dirty ?? 0, agents: d.agents ?? 0, path: d.path ?? '' }))) })
      .catch(() => { /* 后端未就绪则回落 mock allProjects，不阻断 */ })
    return () => { cancelled = true }
  }, [])

  // ---- 技能市场弹窗（Composer 顶部 agent 胶囊点击触发 · App 持有开关 + 渲染 SkillMarketModal）----
  const [showMarket, setShowMarket] = useState(false)

  // ---- local UI state (kept tiny) ----
  // 中心区视图：'chat' 对话 / 'trace' 轨迹（dsh 式记录列表）
  const [centerView, setCenterView] = useState<'chat' | 'trace'>('chat')
  const [composerText, setComposerText] = useState('')
  // F19/#3 排队命令状态机（后端 B4/B5 未接时恒空，优雅降级）
  const commandQueue = useCommandQueue()
  // `currentModel` is derived from the active session's `modelName` —
  // every tab (session) carries its own main agent model, and switching
  // tabs changes the chat header / menubar automatically.
  // `fastModel` stays global (matches Java `AgentDefaults.fastModel` —
  // used for title generation, lightweight tasks across all sessions).
  const [fastModel, setFastModel] = useState<string | null>(null)
  const [showModelPicker, setShowModelPicker] = useState(false)
  const [showEffort, setShowEffort] = useState(false)
  // /context analyze：前端直连 REST 分类展示（OPD-CM5-F-13）
  const [showContextAnalyze, setShowContextAnalyze] = useState(false)
  // F1 · 用量与花费弹窗（Composer 底部 hint-usage 点击打开）
  const [showUsageCost, setShowUsageCost] = useState(false)
  // 记忆编辑器（/memory 命令 + 设置页环境配置 tab「记忆」模块入口；前端直开，不接 executeBuiltin）
  const [showMemoryEditor, setShowMemoryEditor] = useState(false)
  // CLAUDE.md 外部 include 审批弹窗（功能3）· 待审批的外部 @import 文件路径列表
  // CLAUDE.md 外部 include 审批（功能3）：挂载后 GET /include-status 探测，needsApproval → 弹窗
  const [showIncludeApproval, setShowIncludeApproval] = useState(false)
  const [includeApprovalFiles, setIncludeApprovalFiles] = useState<string[]>([])

  // 挂载后探测外部 @import 审批态（后端 GET /claude-md/include-status，2026-08-24 实现）：
  // needsApproval=true（存在外部 include && 未审批 && 未示警）→ 弹 IncludeApprovalModal。
  // 失败静默（无外部 include / 后端未就绪不阻塞启动）。
  useEffect(() => {
    let cancelled = false
    getIncludeStatus()
      .then((st) => {
        if (cancelled) return
        if (st.needsApproval) {
          setIncludeApprovalFiles(st.files)
          setShowIncludeApproval(true)
        }
      })
      .catch(() => { /* 静默：无外部 include 或后端未就绪 */ })
    return () => { cancelled = true }
  }, [])
  /** 活跃流式会话 → streamTopic（多会话并行订阅：sendMessage 登记 / complete 移除） */
  const [activeStreams, setActiveStreams] = useState<Record<string, string>>({})
  // 合并「对话操作」弹窗（双击 Esc → 压缩 tab；消息 hover「↺ 回退到此」→ 裁剪 tab）
  const [showDialogOps, setShowDialogOps] = useState(false)
  const [dialogOpsTab, setDialogOpsTab] = useState<'compact' | 'trim'>('compact')
  // 命令面板（/ 未命中或 ⌘K 触发）
  const [showCommandPalette, setShowCommandPalette] = useState(false)
  // FNT-SUB-02：/agents 命令 → 打开子代理面板（而非 executeBuiltin）
  const [showAgentsPanel, setShowAgentsPanel] = useState(false)
  // FNT-BROWSER-01：/chrome 命令 → 打开 NexusAI in Chrome 扩展连接面板（静态引导 + sessionId 透出）
  const [showChromePanel, setShowChromePanel] = useState(false)
  // [附件双模式 local-read] 后端 nexusai.attachments.local-read（true=本地桌面：>5MB 拖拽附件传 path 由后端读盘）
  const [attachmentLocalRead, setAttachmentLocalRead] = useState(false)
  // 项目文件树点击查看（真实文件内容）
  const [openFile, setOpenFile] = useState<{ projectId: string; path: string } | null>(null)

  // ---- refs ----
  const modelDropdownRef = useRef<HTMLDivElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  // 右栏拖拽调宽：resizer 在 .app 层（无 backdrop-filter，fixed 相对 viewport 正常）
  const resizerRef = useRef<HTMLDivElement>(null)
  const handleResizeStart = (e: React.MouseEvent) => {
    e.preventDefault()
    const appEl = document.querySelector('.app') as HTMLElement | null
    if (!appEl) return
    const startX = e.clientX
    const startW = parseFloat(appEl.style.gridTemplateColumns?.split(' ')[2]) || 300
    let currentW = startW
    const move = (ev: MouseEvent) => {
      // resizer 在右栏左边缘：往左拖（clientX 减小）→ 右栏变宽
      currentW = Math.max(220, Math.min(640, startW - (ev.clientX - startX)))
      appEl.style.gridTemplateColumns = `260px 1fr ${currentW}px`
      appEl.style.setProperty('--right-w', `${currentW}px`)
    }
    const up = () => {
      localStorage.setItem('nexusai-right-w', String(currentW))
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }
  // 挂载时恢复用户调整过的右栏宽度
  useEffect(() => {
    const saved = localStorage.getItem('nexusai-right-w')
    if (saved) {
      const w = Math.max(220, Math.min(640, parseInt(saved, 10) || 300))
      const appEl = document.querySelector('.app') as HTMLElement | null
      if (appEl) {
        appEl.style.gridTemplateColumns = `260px 1fr ${w}px`
        appEl.style.setProperty('--right-w', `${w}px`)
      }
    }
  }, [])
  // 挂载拉附件模式配置（attachmentLocalRead：true=本地桌面 path 直读；后端未起/无端点 → false 兜底走 upload）
  useEffect(() => {
    const load = () => {
      attachmentApi.config()
        .then((c) => { setAttachmentLocalRead(!!c.localRead); console.debug('[attachment-mode] localRead =', c.localRead) })
        .catch((e) => {
          // 后端未起时首次拉取失败 → 3s 后重试（避免 mount 早于后端导致永久回落 upload）
          console.debug('[attachment-mode] config 拉取失败，3s 重试', e)
          setTimeout(load, 3000)
        })
    }
    load()
  }, [])
  // 上次 Esc 时间戳（turn 运行中两下停流 + 空闲双击弹窗，分离避免状态串扰：
  //  stopStreaming 异步移除 activeStreams → turnRunning 翻 false，同一 ref 会让第二下 Esc 误走空闲分支）
  const lastStopEscRef = useRef(0)
  const lastIdleEscRef = useRef(0)

  // ---- side-effects (P0 theme/font/animations) ----
  useTheme(settings.theme)
  useFontSize(settings.fontSize)
  useAnimations(settings.animationsEnabled)

  const showToast = useToastDispatcher(uiDispatch)

  // ---- provider-env：真实全局设置（挂载拉取，供 ModelPickerModal 档位绑定 + 压缩窗口）----
  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  useEffect(() => {
    let cancelled = false
    settingsApi.get()
      .then((s) => { if (!cancelled) setAppSettings(s) })
      .catch(() => { /* 后端未就绪则档位显示未配置，不阻断 */ })
    return () => { cancelled = true }
  }, [])
  const onSaveSettings = useCallback(async (req: UpdateSettingsRequest) => {
    try {
      const updated = await settingsApi.update(req)
      setAppSettings(updated)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [showToast])

  // 快速模型初始值从后端 settings.fastModelName 读（刷新后恢复全局快速模型配置）
  useEffect(() => {
    if (appSettings?.fastModelName) setFastModel(appSettings.fastModelName)
  }, [appSettings?.fastModelName])

  // ---- 挂载时从后端拉取会话列表 ----
  useEffect(() => {
    let cancelled = false
    sessionApi.list()
      .then((list) => {
        if (cancelled) return
        setSessions(list)
        // activeSession 默认是前端 mock（sess-msgbus），后端真实 session 体系无此 id → 自动切到第一个
        // 真实会话，避免发消息/拉消息走 mock id 触发「session not found」404
        if (list.length > 0 && !list.some((s) => s.id === sessionR.activeSession)) {
          sessionDispatch({ type: 'SWITCH', sessionId: list[0].id })
        }
      })
      .catch((e) => showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info'))
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setSessions, showToast])

  // ---- current session & its context ----
  const activeSessionId = sessionR.activeSession
  // 每会话独立输入草稿（切换会话不串扰）：保存上一个会话的输入，载入目标会话草稿
  const composerDraftsRef = useRef<Record<string, string>>({})
  const lastComposerSessionRef = useRef<string | null>(null)
  useEffect(() => {
    const prev = lastComposerSessionRef.current
    lastComposerSessionRef.current = activeSessionId
    if (!activeSessionId) return
    if (prev && prev !== activeSessionId) {
      composerDraftsRef.current = { ...composerDraftsRef.current, [prev]: composerText }
    }
    setComposerText(composerDraftsRef.current[activeSessionId] ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId])
  // UI 组件仍消费旧 Session 形状，这里做一次 null 兜底映射
  const sessions = useMemo<Session[]>(() => storeSessions.map(toSession), [storeSessions])
  // #1 绑定策略：activeSession 用 SessionDto（含 mainProjectId），供发送拦截/绑定判断
  const activeSession = storeSessions.find((s) => s.id === activeSessionId) ?? storeSessions[0] ?? EMPTY_SESSION_DTO
  // 当前会话权限模式：会话覆盖 ?? 全局默认 ?? 'default'
  const activePermissionMode: PermissionMode = activeSession?.permissionMode ?? appSettings?.permissionMode ?? 'default'
  // 当前会话主线程 agent（V58 main_thread_agent · null/空串 = 默认模式，Composer 顶部胶囊显示）
  const currentAgent = activeSession?.mainThreadAgent ?? null
  const sessionContext = sessionContexts[activeSessionId] ?? newSessionContext

  // 本地专家/市场数据由 SkillMarketModal 打开时自行拉取（agentApi.listAgents + marketApi.*），App 不再常驻清单。

  // ---- 消息渲染源：chatStore.messages + 当前流式 ----
  const storeMessages = useChatStore((s) => s.messages[activeSessionId] ?? EMPTY_MESSAGES)
  const stream = useChatStore((s) => s.streams[activeSessionId])
  // 重拉后按 imagePasteIds 批量拉图显示缩略图（后端 POST /attachments/image/batch · 本地缓存优先，
  //   乐观追加 imageData 已本地有 base64 无需再拉；miss 的 batch 拉取后写缓存供 MessageList 渲染）
  useEffect(() => {
    if (!activeSessionId) return
    const cached = useChatStore.getState().imageCache[activeSessionId] ?? {}
    const miss: string[] = []
    for (const m of storeMessages) {
      for (const id of m.imagePasteIds ?? []) {
        if (id && !cached[id] && !miss.includes(id)) miss.push(id)
      }
    }
    if (miss.length === 0) return
    chatApi.fetchImagesBatch(activeSessionId, miss)
      .then((imgs) => { if (imgs && Object.keys(imgs).length) useChatStore.getState().setImageCache(activeSessionId, imgs) })
      .catch(() => { /* 后端 batch 端点未就绪时静默（重拉后无图，同现状） */ })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId, storeMessages])
  // turn 运行中判定：activeStreams 含本会话 = turn 已登记（含 thinking 阶段，此时 streams 空）。
  //   OR streams 有流式块 = 打字机在推（后端主动推流/cron 续跑未登记 activeStreams 时仍要可停）。
  //   两信号互补：思考阶段靠 activeStreams；打字机阶段靠 streams（防「打字机在动但发送键已出」脱节）
  const turnRunning = !!activeStreams[activeSessionId] || (stream && stream.length > 0)
  // 压缩进行中（compact-progress 事件 · 不经 LlmAgentLoop → turnRunning 假，需并入发送键⇄停止）
  const compactActive = useChatStore((s) => s.compact.visible && s.compact.status === 'running')
  // 运行中会话集合（有 stream = 运行中），供左侧栏状态图标。
  // 用 useMemo 稳定引用避免 selector 每次返回新 Set 触发无限重渲染。
  const streamsMap = useChatStore((s) => s.streams)
  const runningSessionIds = useMemo(() => new Set(Object.keys(streamsMap)), [streamsMap])

  // ---- 权限冒泡：持久队列头部 + 决策出队 ----
  const permissionQueue = useChatStore((s) => s.permissionQueue)
  const dequeuePermission = useChatStore((s) => s.dequeuePermission)
  // 权限弹窗只弹当前查看会话的请求；非本会话留在队列（侧栏黄点提示，切过去再处理）
  // 同一 tool_use 会同时推 message + bridge 两条权限事件（bridge 竞速 racer）——本地桌面优先渲染
  //   message（AskUserForm 选择题）；bridge 请求入队后由 dismiss 事件（permission-bridge-dismiss）
  //   清除，不独立弹出第二个「需要权限」通用弹窗。仅当无 message 请求（如 leader inbox 走 bridge）才回退。
  const currentPermission = permissionQueue.find((r) => r.sessionId === activeSessionId && r.kind === 'message')
    ?? permissionQueue.find((r) => r.sessionId === activeSessionId)
  // 等待权限/提问的会话 id 集合（侧栏黄点 · 对齐 Harness pendingInteraction 优先于运行）
  const pendingSessionIds = useMemo(() => new Set(permissionQueue.map((r) => r.sessionId)), [permissionQueue])
  // 权限卡片出现 → 滚底信号（值变化时 MessageList 强制滚到底部，确保权限弹窗对准最新内容）
  const [permScrollSignal, setPermScrollSignal] = useState(0)
  // 回到底部按钮（Composer 工具栏 · 对齐 deepseek-harness ChatView toBottom）：对话贴底状态 + 触发滚底信号
  const [chatAtBottom, setChatAtBottom] = useState(true)
  const [toBottomSignal, setToBottomSignal] = useState(0)
  const lastPermIdRef = useRef<string | null>(null)
  useEffect(() => {
    const id = currentPermission?.requestId ?? null
    if (id && id !== lastPermIdRef.current) {
      lastPermIdRef.current = id
      setPermScrollSignal((n) => n + 1)
    }
  }, [currentPermission])

  // ---- ApiRetry 重试提示条 ----
  const retry = useChatStore((s) => s.retry)
  const setRetry = useChatStore((s) => s.setRetry)

  // ---- 合并「对话操作」：双击 Esc 触发弹窗（压缩 tab；消息非空且非 loading）----
  const setMessages = useChatStore((s) => s.setMessages)
  const removeMessage = useChatStore((s) => s.removeMessage)
  const clearStream = useChatStore((s) => s.clearStream)
  const setConversationId = useChatStore((s) => s.setConversationId)
  // 会话当前 conversationId（partial 压缩/裁剪后旋转）：作消息 row key 前缀，整列表 remount
  const conversationId = useChatStore((s) => s.conversationIds[activeSessionId])
  // F8 门控：消息非空 && 输入框为空（composerText 无有效内容）&& 非 loading
  const canOpenDialogOps = storeMessages.length > 0 && !turnRunning && !composerText.trim()
  // 停止后静默重拉当前会话（显示已停止 → 立即反映停止后真实状态 · 等同「轨迹点击」刷新：
  //   无 toast、不闪、保留窗口与 WS 连接）
  const refreshAfterStop = useCallback(async () => {
    if (!activeSessionId) return
    try {
      const msgs = await chatApi.listMessages(activeSessionId)
      setMessages(activeSessionId, msgs)
    } catch { /* 停止后刷新失败静默（后续事件/手动 F5 兜底） */ }
  }, [activeSessionId, setMessages])
  // ---- 停止当前流式（Esc 一次 · turn 运行中）----
  const stopStreaming = useCallback(async () => {
    if (!activeSessionId) return
    // 压缩进行中停止：立即收起进度横幅（后端 cancel abort 摘要；随后的 compact_end 不再显示完成态）
    if (useChatStore.getState().compact.visible) {
      useChatStore.getState().setCompact({ visible: false, status: 'canceled' })
    }
    try {
      await chatApi.cancel(activeSessionId)
      clearStream(activeSessionId)
      showToast('已停止生成', 'success')
      await refreshAfterStop()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, clearStream, showToast, refreshAfterStop])

  // ---- 停止当前会话所有任务（Esc 3s 内连按两次 · 对齐 CC killAllAgents 连按确认）----
  const handleStopAll = useCallback(async () => {
    if (!activeSessionId) return
    try {
      // 1) 停止当前会话全部异步任务（background bash/agent/workflow 等）
      await tasksApi.stopAllTasks(activeSessionId)
      // 2) 中止该会话 pending 权限（worker 解除等待）
      const pending = useChatStore.getState().permissionQueue.filter((r) => r.sessionId === activeSessionId)
      for (const req of pending) {
        if (clientRef.current) sendPermissionResponse(clientRef.current, activeSessionId, req.kind, req.requestId, 'deny', {})
        useChatStore.getState().dequeuePermission(req.requestId)
      }
      // 3) 清当前流式
      clearStream(activeSessionId)
      showToast('已停止所有任务', 'success')
      // 显示「已停止」后后台刷新会话数据（停止后立即反映真实状态，等同轨迹 tab 刷新）
      await refreshAfterStop()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, clearStream, showToast, refreshAfterStop])

  // ---- 强行停止所有（Composer 「停止所有」按钮：一键 = 取消当前流式 + 停全部后台任务。
  //      等价双击 Esc 强停，但不依赖 3s 连按时序；turn 不在飞时退化为纯 stop-all）----
  const handleHardStop = useCallback(async () => {
    if (!activeSessionId) return
    if (turnRunning) {
      try { await chatApi.cancel(activeSessionId) } catch { /* 无在飞 turn → 后端无取消目标，静默 */ }
    }
    await handleStopAll()
  }, [activeSessionId, turnRunning, handleStopAll])

  // ---- Ctrl+B：当前会话前台任务全部转后台（对齐 CC task:background · 主线程可继续对话）----
  useEffect(() => {
    const onBg = (e: KeyboardEvent) => {
      if (!(e.ctrlKey && !e.shiftKey && !e.altKey && !e.metaKey)) return
      if (e.key.toLowerCase() !== 'b') return
      e.preventDefault()
      if (!activeSessionId) return
      void tasksApi.backgroundAll(activeSessionId)
        .then((r) => showToast(r.backgrounded > 0 ? `${r.backgrounded} 个任务已转后台` : '没有前台任务可转后台', 'success'))
        .catch((err) => showToast(err instanceof ApiError ? err.userMessage() : String(err), 'info'))
    }
    window.addEventListener('keydown', onBg)
    return () => window.removeEventListener('keydown', onBg)
  }, [activeSessionId, showToast])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      // 命令面板打开时 Esc 交由其自身关闭，避免双击误触发对话操作弹窗
      if (showCommandPalette) return
      const now = Date.now()
      // turn 运行中（activeStreams 含本会话 = 已登记，含 thinking 阶段）：Esc 一次停当前流式；
      //   3s 内第二次停当前会话所有任务（对齐 CC useCancelRequest killAllAgents 连按确认 · KILL_AGENTS_CONFIRM_WINDOW_MS=3000）
      if (turnRunning) {
        if (lastStopEscRef.current !== 0 && now - lastStopEscRef.current < 3000) {
          lastStopEscRef.current = 0
          void handleStopAll()
        } else {
          lastStopEscRef.current = now
          void stopStreaming()
          showToast('再按一次 Esc 停止所有任务', 'info')
        }
        return
      }
      // turn 已停（第一下 Esc 的 stopStreaming 异步移除 activeStreams → turnRunning 翻 false）：
      //   3s 内第二次仍走 killAll（对齐 CC killAgents 两下确认独立于 abortSignal 状态 ——
      //   useCancelRequest.ts handleKillAgents 不依赖请求是否在飞，只按窗口内连按判定）
      if (lastStopEscRef.current !== 0 && now - lastStopEscRef.current < 3000) {
        lastStopEscRef.current = 0
        void handleStopAll()
        return
      }
      // 空闲（无在飞 turn 且非两下确认窗口）：连按两次 → 对话操作弹窗（压缩/裁剪）
      if (now - lastIdleEscRef.current < 400) {
        lastIdleEscRef.current = 0
        if (canOpenDialogOps) {
          setDialogOpsTab('compact')
          setShowDialogOps(true)
        }
      } else {
        lastIdleEscRef.current = now
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [canOpenDialogOps, showCommandPalette, turnRunning, stopStreaming, handleStopAll])

  const handleConfirmCompact = useCallback(async (messageId: string, direction: 'from' | 'up_to') => {
    if (!activeSessionId) return
    try {
      const resp = await chatApi.partialCompact(activeSessionId, { messageId, direction })
      setMessages(activeSessionId, resp.messages)
      // F10：partial 压缩后后端返回新 conversationId（row key 刷新），落 store
      if (resp.conversationId) setConversationId(activeSessionId, resp.conversationId)
      setShowDialogOps(false)
      showToast('已压缩', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setMessages, setConversationId, showToast])

  // ---- 对话裁剪：DELETE 删 pivot 起全部消息 + 旋转 conversationId（响应 messages 已归一化直接覆盖）----
  const handleTrimAfter = useCallback(async (messageId: string) => {
    if (!activeSessionId) return
    try {
      const resp = await chatApi.trimAfter(activeSessionId, messageId)
      setMessages(activeSessionId, resp.messages)
      if (resp.conversationId) setConversationId(activeSessionId, resp.conversationId)
      setShowDialogOps(false)
      showToast('已裁剪，此消息后的对话已删除', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setMessages, setConversationId, showToast])

  // 重拉当前会话消息（DB 权威 · 无 toast）：complete 回调 + F5 共用；块级流契约 #2 用它对齐多轮链
  const reloadMessages = useCallback(async () => {
    if (!activeSessionId) return
    try {
      const msgs = await chatApi.listMessages(activeSessionId)
      setMessages(activeSessionId, msgs)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setMessages, showToast])

  // F5 刷新当前页面（对话/轨迹均基于会话消息）：无弹窗打开时重新拉取当前会话历史；客户端聚焦时 window keydown 天然满足，无额外按钮
  const refreshConversation = useCallback(async () => {
    await reloadMessages()
    showToast('已刷新对话', 'info')
  }, [reloadMessages, showToast])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'F5') return
      // 设置页打开时 F5 刷新当前菜单 tab；其他弹窗打开时不刷新
      if (showDialogOps || showCommandPalette || ui.showSearchPalette || showModelPicker || showAgentsPanel || showChromePanel || showContextAnalyze || showMemoryEditor || showIncludeApproval || showMarket || ui.showAddPanel || !!ui.diffFile) return
      e.preventDefault()
      if (ui.showSettings) {
        // F5 刷新当前设置菜单页（对应 hook refresh；无 hook 的 tab 回落刷新会话历史）
        switch (settings.settingsTab) {
          case 'providers': void providersApi.refresh(); break
          case 'skills': void skillsApi.refresh(); break
          case 'mcp': void mcpApi.refresh(); break
          case 'database': void databasesApi.refresh(); break
          case 'schedules': void schedulesApi.refresh(); break
          default: void refreshConversation()
        }
        showToast('已刷新设置', 'info')
      } else {
        void refreshConversation()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ui, settings.settingsTab, providersApi, skillsApi, mcpApi, databasesApi, schedulesApi, refreshConversation, showToast, showDialogOps, showCommandPalette, showModelPicker, showAgentsPanel, showChromePanel, showContextAnalyze, showMemoryEditor, showIncludeApproval, showMarket])

  // ---- 历史回放：会话切换（真实后端 id）时先清空，再拉取该会话历史消息 ----
  const isRealActive = storeSessions.some((s) => s.id === activeSessionId)
  useEffect(() => {
    if (!isRealActive) return
    let cancelled = false
    setMessages(activeSessionId, [])
    chatApi.listMessages(activeSessionId)
      .then((msgs) => { if (!cancelled) setMessages(activeSessionId, msgs) })
      .catch((e) => { if (!cancelled) showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info') })
    return () => { cancelled = true }
  }, [activeSessionId, isRealActive, setMessages, showToast])

  // 排队消费：queue.drained 到达（工具边界）→ App 立即 append 用户2 气泡；渲染按 userMessageId 分组
  //   自动排到当前 assistant 工具轮之后（对齐 deepseek-harness/CC 工具边界插入，不延后到 complete）。
  // 会话生命周期明确信号（对齐 Harness 事件驱动确定性）：complete/cancel 到达 → 移除 activeStreams。
  //   不用「streams 从有到无」推断移除——推断与 sendMessage 登记竞争，是「换会话卡住」的根因。
  //   topic 校验：事件来源 topic 必须等于当前登记 topic 才删。防竞态——turn A 运行中 send B，
  //   activeStreams[sid] 已登记为 topicB，此时 A 的终止事件（topicA）若盲删会把 topicB 一并移除
  //   → B 订阅被取消 → 新 turn 输出丢失。topic 不匹配说明该终止信号属于已被轮换掉的旧 turn，忽略。
  // ---- 「完成未读」绿点（需求：运行结束且非当前会话 → 静止绿点；切到即清除）----
  const activeSessionIdRef = useRef(activeSessionId)
  activeSessionIdRef.current = activeSessionId
  const [doneUnreadIds, setDoneUnreadIds] = useState<Set<string>>(new Set())
  // 切到某会话 → 视为已读（清除其完成未读绿点）
  useEffect(() => {
    if (!activeSessionId) return
    setDoneUnreadIds((p) => (p.has(activeSessionId) ? (() => { const n = new Set(p); n.delete(activeSessionId); return n })() : p))
  }, [activeSessionId])

  const handleSessionDone = useCallback((sid: string, topic?: string) => {
    // [未读] complete/cancel 运行结束且非当前会话 → 标「完成未读」（running/pending 优先级更高会覆盖显示）
    if (sid !== activeSessionIdRef.current) {
      setDoneUnreadIds((p) => (p.has(sid) ? p : new Set(p).add(sid)))
    }
    setActiveStreams((prev) => {
      if (!prev[sid]) return prev
      if (topic && prev[sid] !== topic) return prev
      const next = { ...prev }
      delete next[sid]
      return next
    })
  }, [])

  // 排队命令出站回调（B5）：queue.changed → 刷新排队框；queue.drained → append 正式气泡 + 登记新 streamTopic
  const handleQueueChanged = useCallback((_sid: string, commands: { content: string; mode: string; isEditable: boolean; isMeta?: boolean }[]) => {
    commandQueue.setQueued(commands)
  }, [commandQueue])
  const handleQueueDrained = useCallback((sid: string, drained: { uuid?: string; content: string }[]) => {
    if (drained.length === 0) return
    // 工具边界消费 → 【立即 append】用户2 气泡（打字机期可见）；渲染按 userMessageId 分组自动排到
    //   当前 assistant 工具轮之后（用户2 的 group 在 bash 后、AI 回复其前）—— 不延后到 complete。
    //   会话级单 topic 常驻（activeStreams 里 sid 仍在）→ 新轮回答经同一订阅收到，无需重新订阅。
    //   uuid = 排队命令 uuid（DB user 消息 id）→ 气泡 id 用它防重。
    const st = useChatStore.getState()
    const prev = st.messages[sid] ?? []
    const existingIds = new Set(prev.map((m) => m.id))
    const msgs = drained
      .filter((d) => !(d.uuid && existingIds.has(d.uuid)))   // 防 queue.drained 重发/与重拉重复
      .map((d) => {
        const msgId = d.uuid ?? `queued-${sid}-${Date.now()}-${Math.random().toString(36).slice(2)}`
        return {
          id: msgId, sessionId: sid, role: 'user' as const, author: '你', content: d.content,
          reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: null, time: null,
          toolCallId: null, assistantMessageId: null, userMessageId: msgId, subtype: null, isMeta: false, isApiErrorMessage: false,
          apiError: null, error: null, errorDetails: null, matchedRule: null,
        }
      })
    if (msgs.length > 0) {
      st.setMessages(sid, [...prev, ...msgs])
      void debugLog(`[drained-append] sid=${sid} uids=${msgs.map((m) => m.userMessageId ?? m.id).join(',')} contents=${msgs.map((m) => m.content?.slice(0, 10) ?? '').join(',')}`)
    }
  }, [])

  // [断连恢复] WS 被服务端强杀（send time limit 超时）断连后,重连第一个 message.complete → 按事件 sid 权威
  //   重拉 GET /messages 补偿缺口（DB 已逐条落库 realtime-persist）。reloadMessages 锁 activeSessionId
  //   （F5/当前会话专用）,后台会话断连需按 sid 拉 → 独立实现。finalizeBlocks 按 id 幂等去重保证不重复。
  const handleReconnectReload = useCallback(async (sid: string) => {
    try {
      const msgs = await chatApi.listMessages(sid)
      setMessages(sid, msgs)
    } catch {
      // 重拉失败静默（不 toast 打扰——可能后台会话;下次 complete / 手动 F5 / 切会话重拉兜底）
      console.debug('[reconnect-reload]', 'sid=', sid, '失败（静默,待下次兜底）')
    }
  }, [setMessages])

  // 多会话并行订阅（订阅所有 activeStreams；complete/cancel 明确回调移除）
  const { clientRef } = useChatSocket(activeSessionId, activeStreams, showToast, handleSessionDone, handleQueueDrained, handleQueueChanged, handleReconnectReload)

  // ---- away-summary：blur 5min 触发，REST 摘要回插为系统消息 ----
  useAwaySummary(activeSessionId, (text) => {
    const st = useChatStore.getState()
    st.setMessages(activeSessionId, [...(st.messages[activeSessionId] ?? []), {
      id: `away-${Date.now()}`, sessionId: activeSessionId, role: 'system', author: 'system', content: text,
      reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: null, time: null,
      toolCallId: null, assistantMessageId: null, subtype: 'away_summary', isMeta: false, isApiErrorMessage: false,
      apiError: null, error: null, errorDetails: null, matchedRule: null,
    }])
  })
  // 单项目模式：session 绑定项目优先反查真实项目（activeSession.mainProjectId → realProjects），
  // 缺省回退 perSessionProjects 本地 state（旧 mock 兜底）
  const boundProject = activeSession?.mainProjectId
    ? (realProjects.find((p) => p.id === activeSession.mainProjectId) ?? null)
    : null
  const sessionProject = boundProject
    ? { main: boundProject, subs: [], expanded: {}, flashing: null }
    : (perSessionProjects[activeSessionId] ?? {
        main: allProjects[0],
        subs: [],
        expanded: {},
        flashing: null,
      })

  // ---- session handlers ----
  const switchSession = useCallback(
    (sessionId: string) => {
      const s = sessions.find((x) => x.id === sessionId)
      if (!s) return
      sessionDispatch({ type: 'SWITCH', sessionId })
      // 排队命令按会话隔离：切会话 → 清空排队框（否则显示上个会话的排队命令）
      commandQueue.clear()
      if (!sessionR.openSessions.includes(sessionId)) {
        sessionDispatch({ type: 'ADD_TAB', tabId: sessionId })
        showToast(`已打开: ${s.title}`, 'success')
      } else {
        showToast(`已切换到: ${s.title}`, 'success')
      }
    },
    [sessions, sessionR.openSessions, sessionDispatch, showToast, commandQueue],
  )

  // ---- delete session（后端删 + 本地清 store + 切到下一个会话）----
  const handleDeleteSession = useCallback(
    (sessionId: string) => {
      void (async () => {
        // 删除会话前：中止该会话 pending 权限请求（对齐 CC onCancel abort · 用户离开会话 worker 须解除等待）
        const pending = useChatStore.getState().permissionQueue.filter((r) => r.sessionId === sessionId)
        for (const req of pending) {
          if (clientRef.current) sendPermissionResponse(clientRef.current, sessionId, req.kind, req.requestId, 'deny', {})
          useChatStore.getState().dequeuePermission(req.requestId)
        }
        try {
          await sessionApi.remove(sessionId)
        } catch (e) {
          showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
          return
        }
        // 本地清理：chatStore 消息/流 + 会话列表
        useChatStore.getState().clearSession(sessionId)
        const remaining = storeSessions.filter((s) => s.id !== sessionId)
        setSessions(remaining)
        // 若删除的是当前会话 → 切到下一个（或空）
        if (activeSessionId === sessionId) {
          const next = remaining[0]
          if (next) {
            sessionDispatch({ type: 'SWITCH', sessionId: next.id })
            if (!sessionR.openSessions.includes(next.id)) sessionDispatch({ type: 'ADD_TAB', tabId: next.id })
          }
        }
        showToast('会话已删除', 'info')
      })()
    },
    [activeSessionId, sessionR.openSessions, sessionDispatch, setSessions, storeSessions, showToast],
  )

  // ---- create new session（走后端 API，成功后写入列表并激活）----
  // 新建会话默认模型：取真实 providers 第一个 enabled 模型的模型（非 mock 随机）；
  // 无可用 provider → 回落当前会话模型 → 最终 mock 兜底（不随机）
  const defaultNewSessionModel = useMemo(() => {
    // 契约：模型全名 = {provider.name}/{model.name}（新建会话 modelName 用全名，后端按全名反查）
    // 1) 优先全局主模型 settings.mainModelName（用户配置的主模型 → 新会话默认用它）
    const main = appSettings?.mainModelName
    if (main) {
      for (const p of providersApi.list) {
        if (!p.enabled) continue
        const hit = p.models.find((m) => m.enabled && `${p.name}/${m.name}` === main)
        if (hit) return { tag: hit.tag, name: main }
      }
    }
    // 2) 回落第一个 enabled provider 的第一个 enabled model
    const firstEnabled = providersApi.list.find((p) => p.enabled && p.models.length > 0)
    const m = firstEnabled?.models.find((x) => x.enabled) ?? firstEnabled?.models[0]
    if (m && firstEnabled) return { tag: m.tag, name: `${firstEnabled.name}/${m.name}` }
    // 3) 回落当前会话
    if (activeSession?.modelName) return { tag: (activeSession.model ?? 'DS') as ModelTag, name: activeSession.modelName }
    return { tag: 'DS' as ModelTag, name: 'DeepSeek-Chat' }
  }, [providersApi.list, activeSession, appSettings?.mainModelName])
  const createSession = useCallback(async () => {
    const pickFrom = defaultNewSessionModel
    // #1 绑定策略：新建会话带当前主项目 mainProjectId（项目维度创建）
    const mainProjectId = activeSession?.mainProjectId ?? perSessionProjects[activeSessionId ?? '']?.main?.id ?? undefined
    // 单项目模式：工作组下已有「无对话空会话」则禁止再次创建（用户需求）
    const chatMessages = useChatStore.getState().messages
    const hasEmptySessionInGroup = storeSessions.some((s) => {
      // 同工作组（同 mainProjectId；无绑定会话视为同一默认组）
      const sameGroup = (s.mainProjectId ?? null) === (mainProjectId ?? null)
      if (!sameGroup) return false
      const msgs = chatMessages[s.id] ?? []
      const isStreaming = !!useChatStore.getState().streams[s.id]
      // 空会话判定用后端 messageCount 权威（sessions 表消息计数）：前端 messages 未加载的会话
      //   （有历史但本端未拉）不再误判为空 → messageCount>0 或前端已有消息皆非空，两者皆无才判空
      return (s.messageCount ?? 0) <= 0 && msgs.length === 0 && !isStreaming
    })
    if (hasEmptySessionInGroup) {
      showToast('当前工作区已有空会话，请先在已有会话中对话', 'info')
      return
    }
    let created: SessionDto
    try {
      created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    setSessions([created, ...storeSessions])
    sessionDispatch({ type: 'SWITCH', sessionId: created.id })
    sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
    // M2：右面板 main 用真实项目反查（created.mainProjectId → realProjects），替代 mock allProjects[0]
    const realMain = (created.mainProjectId && realProjects.find((p) => p.id === created.mainProjectId)) || allProjects[0]
    setPerSessionProjects((prev) => ({
      ...prev,
      [created.id]: {
        main: realMain,
        subs: [],
        expanded: {},
        flashing: null,
      },
    }))
    showToast('已创建新会话', 'success')
  }, [sessionDispatch, showToast, setSessions, storeSessions, activeSession, activeSessionId, perSessionProjects, realProjects, defaultNewSessionModel])

  // 本地文件夹选项目（Tauri 原生文件夹框 · 用户需求）→ 注册项目 + 绑定当前会话
  const handleSelectProjectFolder = useCallback(async () => {
    // 会话已创建并对话后不能切换绑定目录
    if (activeSessionId) {
      const hasMessages = (useChatStore.getState().messages[activeSessionId] ?? []).length > 0
      if (hasMessages) {
        showToast('当前会话已有对话，不能切换绑定目录', 'info')
        return
      }
    }
    const sel = await selectProjectFolder()
    if (!sel) return // 用户取消
    // 后端已校验路径（转绝对 + 目录必须存在）：浏览器 dev 模式仅返回目录名（相对），
    //   传相对路径会污染会话 cwd → 此处硬校验，非绝对路径直接拒绝
    if (!isAbsolutePath(sel.path)) {
      showToast('浏览器模式无法获取项目绝对路径，请使用桌面端选择文件夹', 'info')
      return
    }
    // 注册项目（POST /projects，name=文件夹名，path=绝对路径）
    let proj: ProjectDto
    const folderName = sel.path.split(/[\\/]/).pop() || sel.path
    try {
      proj = await projectApi.create({ name: folderName, path: normalizePath(sel.path) })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    // 刷新真实项目缓存
    setRealProjects((prev) => {
      const p = { id: proj.id, name: proj.name, branch: proj.branch ?? '', dirty: proj.dirty ?? 0, agents: proj.agents ?? 0, path: proj.path ?? '' }
      return [p, ...prev.filter((x) => x.id !== proj.id)]
    })
    // 绑定当前会话
    if (activeSessionId) {
      try {
        const updated = await projectApi.bind(activeSessionId, { projectId: proj.id })
        setSessions(useChatStore.getState().sessions.map((s) => (s.id === activeSessionId ? updated : s)))
        showToast(`已绑定项目 ${proj.name}`, 'success')
      } catch (e) {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      }
    }
  }, [activeSessionId, showToast, setSessions])

  // 添加工作区（新项目工作组）：本地选文件夹 → 注册项目 → 创建该项目首个会话（可添加多个）
  const handleAddWorkspace = useCallback(async () => {
    const sel = await selectProjectFolder()
    if (!sel) return // 用户取消
    // 同 handleSelectProjectFolder：非绝对路径拒绝（浏览器 dev 模式仅目录名）
    if (!isAbsolutePath(sel.path)) {
      showToast('浏览器模式无法获取项目绝对路径，请使用桌面端选择文件夹', 'info')
      return
    }
    const folderName = sel.path.split(/[\\/]/).pop() || sel.path
    // 查同名项目已存在则复用（避免 409）；否则注册
    let proj = realProjects.find((p) => p.name === folderName)
    if (!proj) {
      try {
        const created = await projectApi.create({ name: folderName, path: normalizePath(sel.path) })
        proj = { id: created.id, name: created.name, branch: created.branch ?? '', dirty: created.dirty ?? 0, agents: created.agents ?? 0, path: created.path ?? '' }
        setRealProjects((prev) => [proj!, ...prev])
      } catch (e) {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
        return
      }
    }
    // 创建该项目第一个会话（新工作组起点；不拦截——新工作组无空会话）
    const pickFrom = defaultNewSessionModel
    let created: SessionDto
    try {
      created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId: proj.id })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    setSessions([created, ...storeSessions])
    sessionDispatch({ type: 'SWITCH', sessionId: created.id })
    sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
    setPerSessionProjects((prev) => ({
      ...prev,
      [created.id]: { main: proj!, subs: [], expanded: {}, flashing: null },
    }))
    showToast(`已添加工作区 ${proj.name}`, 'success')
  }, [realProjects, defaultNewSessionModel, showToast, sessionDispatch, setSessions, storeSessions])

  // ---- per-session project handlers ----
  const updateSessionProject = useCallback(
    (id: string, updater: (p: SessionProjectState) => SessionProjectState) => {
      setPerSessionProjects((prev) => ({
        ...prev,
        [id]: updater(prev[id] ?? { main: allProjects[0], subs: [], expanded: {}, flashing: null }),
      }))
    },
    [],
  )

  const handlePromote = useCallback(
    async (sub: Project) => {
      const id = activeSessionId
      if (!id) return
      // H2 + 低项3：sub 升 main 同步走后端 bind。sub 无 id（mock）时用 realProjects 同名匹配补 id；
      //   仍无 id 则纯本地更新（mock 数据固有漂移，非 H2 引入）
      const resolvedSub = sub.id ? sub : (realProjects.find((p) => p.name === sub.name) ?? sub)
      if (resolvedSub.id) {
        try {
          const updated = await projectApi.bind(id, { projectId: resolvedSub.id })
          setSessions(useChatStore.getState().sessions.map((s) => (s.id === id ? updated : s)))
        } catch (e) {
          showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
          return
        }
      }
      updateSessionProject(id, (p) => ({
        ...p,
        main: resolvedSub,
        // M1：不再把旧 main 降为 subs（后端单 main，subs 虚构）
        subs: p.subs.filter((x) => x.name !== resolvedSub.name),
        flashing: resolvedSub.name,
      }))
      projectDispatch({ type: 'CLOSE_DROPDOWN' })
      uiDispatch({ type: 'CLOSE_CONTEXT_MENU' })
      setTimeout(() => {
        setPerSessionProjects((prev) =>
          prev[id] ? { ...prev, [id]: { ...prev[id], flashing: null } } : prev,
        )
      }, 800)
      showToast(`已切换到 ${resolvedSub.name}`, 'success')
    },
    [activeSessionId, realProjects, projectDispatch, uiDispatch, updateSessionProject, showToast, setSessions],
  )

  const unbindProject = useCallback(
    (name: string) => {
      const id = activeSessionId
      if (!id) return
      // H1：sub 解绑纯本地（后端只有单一 main_project_id，无 subs 概念）——
      // 此入口仅用于右键 sub「解除绑定」，不碰后端 main，避免清掉 mainProjectId 导致发送拦截错位。
      // 主项目解绑另走 handleUnbindMain（若需）。
      updateSessionProject(id, (p) => ({ ...p, subs: p.subs.filter((s) => s.name !== name) }))
      uiDispatch({ type: 'CLOSE_CONTEXT_MENU' })
      showToast(`已从关联列表移除 ${name}`, 'info')
    },
    [activeSessionId, uiDispatch, showToast, updateSessionProject],
  )

  // ---- model handlers ----
  // Pick a model for the *active* session only (per-tab model).
  // The picker is also re-opened after picking for tight UX.
  const pickModel = useCallback(
    async (providerName: string, modelName: string, tag: ModelTag) => {
      // 用实际渲染的会话 id（activeSessionId 可能与 storeSessions 不匹配，回退 storeSessions[0]）
      const active = storeSessions.find((s) => s.id === activeSessionId) ?? storeSessions[0]
      const id = active?.id
      setSessions(storeSessions.map((s) =>
        s.id === id ? { ...s, model: tag, modelName } : s,
      ))
      // 持久化到后端会话（模型全名 ds/deepseek-v4-flash）：模型选择不落库刷新即丢，
      // 后端 resolveModelNameForSession 会话 override 层读 DB，故必须 PATCH 到后端
      if (id) {
        try {
          await sessionApi.update(id, { modelName })
          showToast(`本标签页模型 → ${providerName} · ${modelName}`, 'success')
        } catch (e) {
          showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
        }
      }
      // 不自动关闭选择器：配置后由用户主动收起
    },
    [activeSessionId, showToast, setSessions, storeSessions],
  )

  const pickFastModel = useCallback(
    (providerName: string, modelName: string) => {
      setFastModel(modelName)
      // 持久化到后端 settings.fastModelName（快速模型全局 · 配置落库刷新不丢）
      void onSaveSettings({ fastModelName: modelName })
      showToast(`快速模型 → ${modelName}（${providerName} · 轻量任务用）`, 'success')
    },
    [onSaveSettings, showToast],
  )

  const clearFastModel = useCallback(() => {
    setFastModel(null)
    const fallback = activeSession?.modelName ?? '主模型'
    showToast(`快速模型已清除 · 回退到 ${fallback}`, 'info')
  }, [activeSession, showToast])

  // 会话级 effort：/effort 命令写当前会话（V31 effort_level + V32 ultracode_enabled）
  const saveEffort = useCallback(async (v: NonNullable<SessionDto['effortLevel']> | 'ultracode') => {
    try {
      await commandApi.executeBuiltin('effort', { args: v })
      const isUltra = v === 'ultracode'
      setSessions(storeSessions.map((s) =>
        s.id === activeSessionId
          ? { ...s, effortLevel: isUltra ? 'xhigh' : v, ultracodeEnabled: isUltra }
          : s,
      ))
      showToast(`推理等级 → ${isUltra ? 'ultracode' : v}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setSessions, storeSessions, showToast])

  // 会话级精简模式（V33 bare_mode）：PATCH 后端 + 本地同步（EffortModal 开关）
  const toggleBare = useCallback(async (b: boolean) => {
    if (!activeSessionId) return
    try {
      await sessionApi.update(activeSessionId, { bareMode: b })
      setSessions(storeSessions.map((s) => s.id === activeSessionId ? { ...s, bareMode: b } : s))
      showToast(`精简模式已${b ? '开启' : '关闭'}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setSessions, showToast, storeSessions])

  // 会话级权限模式（会话覆盖全局 · Composer 模型名旁切换）：PATCH 后端 + 本地同步
  const handlePermissionModeChange = useCallback(async (m: PermissionMode) => {
    if (!activeSessionId) return
    try {
      const updated = await sessionApi.update(activeSessionId, { permissionMode: m })
      setSessions(storeSessions.map((s) => s.id === activeSessionId ? updated : s))
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setSessions, showToast, storeSessions])

  // 会话级主线程 agent（V58 main_thread_agent · Composer 顶部胶囊）：PATCH 后端 + 本地同步
  //   agentType=null/空串 均表示清除 → 后端传空串（PATCH 语义：null=不改动，空串=清除）
  const handleAgentChange = useCallback(async (agentType: string | null) => {
    if (!activeSessionId) return
    try {
      await sessionApi.update(activeSessionId, { mainThreadAgent: agentType ?? '' })
      setSessions(storeSessions.map((s) => (s.id === activeSessionId ? { ...s, mainThreadAgent: agentType } : s)))
      showToast(agentType ? `已切换到专家 ${agentType}` : '已恢复默认模式', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setSessions, storeSessions, showToast])

  // ---- 技能市场：使用专家（SkillMarketModal 回调）----
  // 本地专家 → 复用 handleAgentChange（现有 PATCH mainThreadAgent 链路）+ 关弹窗；
  // 远端专家 → marketApi.useExpert（后端构造成本地 agent + 设会话 mainThreadAgent）→ store 同步 + 关弹窗 + toast。
  const handleMarketUseLocal = useCallback((agentType: string) => {
    setShowMarket(false)
    void handleAgentChange(agentType)
  }, [handleAgentChange])

  const handleMarketUseRemote = useCallback(async (expert: MarketExpert) => {
    if (!activeSessionId) return
    try {
      const r = await marketApi.useExpert(activeSessionId, expert.marketId)
      // 用 useChatStore.getState() 取最新会话列表（避免闭包 stale）
      const st = useChatStore.getState()
      setSessions(st.sessions.map((s) => (s.id === activeSessionId ? { ...s, mainThreadAgent: r.mainThreadAgent } : s)))
      setShowMarket(false)
      showToast(`已使用 ${r.displayName || expert.displayName || expert.agentName || r.mainThreadAgent} 驱动会话`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setSessions, showToast])

  // 会话重命名（会话栏 ⋯ → 重命名）：PATCH 后端 title + 本地同步
  const renameSession = useCallback(async (id: string, title: string) => {
    try {
      await sessionApi.update(id, { title })
      setSessions(storeSessions.map((s) => (s.id === id ? { ...s, title } : s)))
      showToast('已重命名', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [setSessions, storeSessions, showToast])

  const openSettingsAt = useCallback(
    (tab: SettingsTab) => {
      settingsDispatch({ type: 'SET_SETTINGS_TAB', tab })
      uiDispatch({ type: 'TOGGLE_SETTINGS' })
    },
    [settingsDispatch, uiDispatch],
  )

  // ---- file rollback/confirm (mock: just toast + remove from active session context) ----
  const rollbackFile = useCallback(
    (name: string) => {
      const ctx = sessionContexts[activeSessionId]
      if (ctx) {
        // In a real app this would call a backend; here we just toast.
      }
      showToast(`已回滚: ${name}`, 'info')
    },
    [activeSessionId, showToast],
  )

  const confirmFile = useCallback(
    (name: string) => {
      showToast(`已确认: ${name}`, 'success')
    },
    [showToast],
  )

  // 后端技能命令名集合（GET /api/command · 含 skills）：技能斜杠命令作为消息发送触发，不弹空面板
  const [remoteCmdNames, setRemoteCmdNames] = useState<Set<string>>(new Set())
  useEffect(() => {
    let alive = true
    commandApi.list().then((cs) => { if (alive) setRemoteCmdNames(new Set(cs.map((c) => c.name))) }).catch(() => {})
    return () => { alive = false }
  }, [])

  // ---- 内置命令执行：统一走 executeBuiltin，成功/失败均显式反馈（fail loud）----
  const runBuiltin = useCallback(async (name: string) => {
    try {
      await commandApi.executeBuiltin(name)
      showToast(`已执行 /${name}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [showToast])

  // ---- composer ----
  const sendMessage = useCallback(async (attachments?: AttachmentRequest[]) => {
    const text = composerText.trim()
    // 允许纯图片附件发送（无文字时 content 传空串）；完全无内容不发
    if ((!text && !attachments?.length) || !activeSessionId) return
    // 新 user 消息 → 清除上一轮 API 错误卡（错误卡属上一轮对话 · message.error 已渲染在消息流）
    useChatStore.getState().clearApiErrors(activeSessionId)
    // #1 绑定策略：未绑定项目的会话拦截发送（不发请求到后端）
    if (!activeSession?.mainProjectId) {
      showToast('请先绑定项目再发送', 'info')
      return
    }
    // 命令触发：`/` 开头 → 命中内置命令名直接执行，未命中打开命令面板
    if (text.startsWith('/')) {
      const name = text.slice(1).split(' ')[0]
      // 已知命令 → 前端动作 / 内置执行（分发后 return，不发送）
      if (name === 'help') {
        // /help → 命令面板（前端展示全部命令 + 描述，不依赖后端）
        setShowCommandPalette(true)
        setComposerText('')
        return
      }
      if (name === 'agents') {
        // FNT-SUB-02：/agents 打开子代理面板（后端 GET /api/agents）
        setShowAgentsPanel(true)
        setComposerText('')
        return
      }
      if (name === 'context') {
        // /context analyze：前端直连 REST /api/v1/context/analyze 分类展示
        setShowContextAnalyze(true)
        setComposerText('')
        return
      }
      if (name === 'memory') {
        // 记忆编辑器：前端直开（不接 executeBuiltin('memory')）
        setShowMemoryEditor(true)
        setComposerText('')
        return
      }
      if (name === 'chrome') {
        // FNT-BROWSER-01：/chrome → 打开 NexusAI in Chrome 扩展连接面板（本地 local-jsx 命令，不走 executeBuiltin）
        setShowChromePanel(true)
        setComposerText('')
        return
      }
      if (isKnownCommand(name)) {
        void runBuiltin(name)
        setComposerText('')
        return
      }
      // 技能命令（/zjkycode）/ /plugin:skill / 未命中但带描述或完整补全（如 /import-cc 导入配置、
      //   /zjkycode:brainstorming ）→ 作为普通消息发送给后端 agent（模型 SkillTool 处理），
      //   不 return —— 落到下方 chatApi.send(text)，text 保留 /原始内容
      // 纯命令名未命中（如 /unknowncmd 打错，无空格）→ 弹命令面板（展示命令列表）
      const isSkillCmd = remoteCmdNames.has(name)
        || (name.includes(':') && remoteCmdNames.has(name.split(':').slice(-1)[0]))
      if (!text.includes(' ') && !isSkillCmd) {
        setShowCommandPalette(true)
        setComposerText('')
        return
      }
      // 发送路径：不 return，落到 chatApi.send
    }
    setComposerText('')
    try {
      // F20：attachments（PDF 绝对路径字符串数组）透传给后端，服务端 PDFBox 解析
      const resp = await chatApi.send(activeSessionId, { content: text, attachments })
      const st = useChatStore.getState()
      // 排队分支（B1/B5 · 对齐 CC）：turn 运行中再发消息 → 后端入队返回 queued=true。
      //   【不】乐观插入气泡（交给排队框展示暗色条）、【不】覆盖 activeStreams（保持原 topic 收流），
      //   等 queue.drained 事件（handleQueueDrained）再 append 正式气泡 + 登记新 streamTopic。
      if (resp.queued) {
        if (showToast) showToast('消息已排队，当前轮结束后自动发送', 'info')
        return
      }
      // 立即追加 user 消息（服务端已持久化），并准备流式 assistant
      // 乐观追加带图片 base64（imageData）→ 用户消息立即显示缩略图；DB 重拉后端不出站该字段。
      //   Composer 附件 base64 为完整 dataURL（readAsDataURL）→ 剥前缀存纯 base64，
      //   渲染统一拼 `data:${mediaType};base64,${base64}`（避免双重前缀裂开）。
      const imageData = attachments?.filter((a) => a.type === 'image' && a.base64)
        .map((a) => {
          const raw = a.base64!
          const m = /^data:[^;]+;base64,(.+)$/.exec(raw)
          return { base64: m ? m[1] : raw, mediaType: a.mediaType || 'image/png' }
        }) ?? []
      // 用户附件快照（非图片：PDF/Word/视频/音频/文件）→ user 气泡内联胶囊 + 点击预览。
      //   内容源：base64（≤5MB 即时）→ path（local-read 大文件本地读盘）→ url（upload contentId 后端 /content 预览——
      //   upload 已注册附件表，contentId 立即拼 url，发送即能点预览，不必等 F5 后端出站）
      const userAttachments = attachments?.filter((a) => a.type !== 'image' && a.filename)
        .map((a) => {
          const cid = a.contentId ?? null
          return {
            type: a.type, filename: a.filename!, mediaType: a.mediaType ?? null, contentId: cid,
            url: cid ? `/attachments/content/${activeSessionId}/${cid}` : null,
            base64: a.base64 ?? null, path: a.path ?? null,
          }
        }) ?? []
      st.setMessages(activeSessionId, [
        ...(st.messages[activeSessionId] ?? []),
        { id: resp.userMessageId, sessionId: activeSessionId, role: 'user', author: '你', content: text, reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null, userMessageId: resp.userMessageId, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null, error: null, errorDetails: null, matchedRule: null, imageData: imageData.length ? imageData : null, userAttachments: userAttachments.length ? userAttachments : null },
      ])
      // 注意：发送时【不】clearStream —— 正常流程上一轮 complete 已清 streams（finalizeBlocks 返回 rest）；
      //   若发送时 clearStream，会触发 wasRunning 检测「streams 从有到无」→ 误移除刚登记的 activeStreams
      //   → 订阅取消 → 新消息 chunk 收不到 → 会话卡住（多会话隔离被破坏）
      // 登记活跃流式会话（useChatSocket 订阅所有 activeStreams · 切走不取消，事件持续接收）
      setActiveStreams((prev) => ({ ...prev, [activeSessionId]: resp.streamTopic }))
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [composerText, activeSessionId, activeSession, showToast, runBuiltin])

  // ---- 停止生成：点「停止」/ Esc → 调 /cancel，成功后清空本地流式态 ----
  // ---- 删除消息：后端 DELETE + 本地 store 同步移除 ----
  const handleDeleteMessage = useCallback(async (messageId: string) => {
    if (!activeSessionId) return
    try {
      await chatApi.removeMessage(activeSessionId, messageId)
      removeMessage(activeSessionId, messageId)
      showToast('已删除', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, removeMessage, showToast])

  // ---- derived ----
  const filteredSearchItems = ui.searchQuery
    ? searchItems
        .filter(
          (item) =>
            item.title.toLowerCase().includes(ui.searchQuery.toLowerCase()) ||
            item.sub.toLowerCase().includes(ui.searchQuery.toLowerCase()),
        )
        .slice(0, 8)
    : searchItems.slice(0, 6)
  const currentDiff = ui.diffFile ? getDiffFor(ui.diffFile) : null

  // ---- click outside / escape ----
  useClickOutside(modelDropdownRef, false, () => {}) // modelDropdown is now button-like, modal handles click outside
  useClickOutside(dropdownRef, projectR.showDropdown, () => projectDispatch({ type: 'CLOSE_DROPDOWN' }))
  useEffect(() => {
    if (!ui.contextMenu) return
    const close = () => uiDispatch({ type: 'CLOSE_CONTEXT_MENU' })
    window.addEventListener('click', close)
    return () => window.removeEventListener('click', close)
  }, [ui.contextMenu, uiDispatch])
  useEscapeKey(ui.showAddPanel, () => { uiDispatch({ type: 'CLOSE_ADD_PANEL' }); projectDispatch({ type: 'SET_ADD_SEARCH', value: '' }) })
  useEscapeKey(ui.showSearchPalette, () => { uiDispatch({ type: 'CLOSE_SEARCH' }); uiDispatch({ type: 'SET_SEARCH_QUERY', value: '' }) })
  useEscapeKey(ui.showSettings, () => uiDispatch({ type: 'CLOSE_SETTINGS' }))
  useEscapeKey(!!ui.diffFile, () => uiDispatch({ type: 'SET_DIFF', file: null }))
  useEscapeKey(showModelPicker, () => setShowModelPicker(false))
  useEscapeKey(showAgentsPanel, () => setShowAgentsPanel(false))
  useEscapeKey(showChromePanel, () => setShowChromePanel(false))
  useEscapeKey(showContextAnalyze, () => setShowContextAnalyze(false))
  useEscapeKey(showUsageCost, () => setShowUsageCost(false))
  useEscapeKey(showMemoryEditor, () => setShowMemoryEditor(false))
  useEscapeKey(showIncludeApproval, () => setShowIncludeApproval(false))
  useEscapeKey(showMarket, () => setShowMarket(false))

  // ---- global keys（⌘K 打开命令面板；原 ⌘K→搜索 的快捷键移交给 MenuBar 搜索按钮）----
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        // 若搜索面板正开着，先关掉，避免两个遮罩叠层
        if (ui.showSearchPalette) uiDispatch({ type: 'CLOSE_SEARCH' })
        setShowCommandPalette((v) => !v)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ui.showSearchPalette, uiDispatch])

  return (
    <div className="app">
      <div className="topbar">
        <TitleBar />
        <MenuBar
          openSettings={() => uiDispatch({ type: 'TOGGLE_SETTINGS' })}
        />
      </div>
      <SessionList
        sessions={storeSessions}
        activeSession={activeSessionId}
        switchSession={switchSession}
        projectPathFor={(pid) => {
          const p = realProjects.find((x) => x.id === pid) ?? allProjects.find((x) => x.id === pid)
          return p?.path ?? ''
        }}
        projectNameFor={(pid) => {
          // M2：优先真实项目缓存（realProjects，含 id），其次 mock 兜底
          const p = realProjects.find((x) => x.id === pid) ?? allProjects.find((x) => x.id === pid)
          return p?.name ?? '未绑定项目'
        }}
        onCreateInProject={(pid) => {
          // #1 项目内新建：带 mainProjectId 创建（用真实默认模型，非 mock 随机）
          void (async () => {
            // 单项目模式：该项目下已有空会话则禁止再次创建
            const chatMessages = useChatStore.getState().messages
            const hasEmpty = storeSessions.some((s) => {
              const sameGroup = (s.mainProjectId ?? null) === (pid ?? null)
              if (!sameGroup) return false
              // 后端 messageCount 权威（同 createSession：前端 messages 未加载不算空）
              return (s.messageCount ?? 0) <= 0 && (chatMessages[s.id] ?? []).length === 0 && !useChatStore.getState().streams[s.id]
            })
            if (hasEmpty) {
              showToast('该项目下已有空会话，请先对话', 'info')
              return
            }
            const pickFrom = defaultNewSessionModel
            try {
              const created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId: pid ?? undefined })
              setSessions([created, ...storeSessions])
              sessionDispatch({ type: 'SWITCH', sessionId: created.id })
              sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
              // M2：项目内新建后写 perSessionProjects 真实 main（反查 realProjects）
              const realMain = (pid && realProjects.find((p) => p.id === pid)) || allProjects[0]
              setPerSessionProjects((prev) => ({ ...prev, [created.id]: { main: realMain, subs: [], expanded: {}, flashing: null } }))
              showToast('已创建新会话', 'success')
            } catch (e) {
              showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
            }
          })()
        }}
        onCreateSession={() => createSession()}
        onOpenAgentMarket={() => setShowMarket(true)}
        onOpenKnowledgeBase={() => showToast('知识库建设中，敬请期待', 'info')}
        doneUnreadIds={doneUnreadIds}
        onAddWorkspace={() => void handleAddWorkspace()}
        onOpenSettings={() => uiDispatch({ type: 'TOGGLE_SETTINGS' })}
        onDeleteSession={handleDeleteSession}
        onRenameSession={renameSession}
        runningSessionIds={runningSessionIds}
        pendingSessionIds={pendingSessionIds}
      />
      <div className="center">
        <div className="center-tabs">
          <button className={`center-tab ${centerView === 'chat' ? 'active' : ''}`} onClick={() => setCenterView('chat')}>
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M1.5 3C1.5 2.5 1.8 2.2 2.3 2.2H4L4.8 3.4H9.7C10.2 3.4 10.5 3.7 10.5 4.2V9C10.5 9.5 10.2 9.8 9.7 9.8H2.3C1.8 9.8 1.5 9.5 1.5 9V3Z"/></svg>
            对话
          </button>
          <button
            className={`center-tab ${centerView === 'trace' ? 'active' : ''}`}
            onClick={() => {
              setCenterView('trace')
              // 切到轨迹视图自动后台重拉当前会话最新消息（轨迹 = 消息派生的记录，无需手动 F5 才看到新记录）
              if (activeSessionId) void reloadMessages()
            }}
          >
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M6 1L6 9M6 9L3 6M6 9L9 6M3 11H9"/></svg>
            轨迹
            <span className="count-badge">{storeMessages.filter((m) => !m.isMeta).length}</span>
          </button>
        </div>
        <div className="stream">
          {centerView === 'trace' ? (
            <TraceView messages={storeMessages} />
          ) : (
            <MessageList messages={storeMessages} streaming={stream && stream.length > 0 ? stream : null} onDelete={handleDeleteMessage} conversationId={conversationId} scrollSignal={permScrollSignal + toBottomSignal} thinking={turnRunning && !(stream && stream.length > 0)} onNearBottomChange={setChatAtBottom} />
          )}
        </div>
        {currentPermission && (
          <PermissionBubble
            request={currentPermission}
            onDecision={(id, d, answers, annotations) => {
              // 路由到请求来源会话（用户切走标签时仍发回正确的 session）
              const targetSession = currentPermission.sessionId ?? activeSessionId
              if (clientRef.current) sendPermissionResponse(clientRef.current, targetSession, currentPermission.kind, id, d, { answers, annotations })
              dequeuePermission(id)
            }}
            onAbort={() => {
              // 中止权限请求（对齐 CC Ctrl+C onReject · 用户放弃决策 → deny + dequeue，解除 worker 等待）
              const targetSession = currentPermission.sessionId ?? activeSessionId
              if (clientRef.current) sendPermissionResponse(clientRef.current, targetSession, currentPermission.kind, currentPermission.requestId, 'deny', {})
              dequeuePermission(currentPermission.requestId)
            }}
          />
        )}
        {retry && (
          <div className="retry-wrap">
            <RetryBanner attempt={retry.attempt} maxRetries={retry.maxRetries} retryDelayMs={retry.retryDelayMs} onClose={() => setRetry(null)} />
          </div>
        )}
        <TokenWarningBanner />
        {/* 压缩进度横幅（/compact 命令触发 · 输入框上方 · compact-progress 事件驱动；发送键压缩中变停止） */}
        <CompactProgressBar />
        <NotificationBanner />
        {/* 轨迹视图只查看，不展示对话输入框 */}
        {/* 发送键 ⇄ 停止键切换用 turnRunning（activeStreams 登记 = turn 运行中，含 thinking/重试期；
            非 !!stream —— 重试/thinking 期无流式块 stream 为空，会误显示发送键） */}
        {centerView === 'chat' && (
          <Composer
            composerText={composerText}
            setComposerText={setComposerText}
            sendMessage={sendMessage}
            showToast={showToast}
            permissionMode={activePermissionMode}
            onPermissionModeChange={handlePermissionModeChange}
            streaming={turnRunning || compactActive}
            onStop={stopStreaming}
            queuedCommands={commandQueue.queuedCommands}
            popEditable={() => {
              if (!activeSessionId) return
              void commandQueue.popEditable(activeSessionId).then((content) => {
                if (content) setComposerText(content)
              })
            }}
            boundProjectName={activeSession.mainProjectId ? (realProjects.find((p) => p.id === activeSession.mainProjectId)?.name ?? null) : null}
            onSelectProject={() => void handleSelectProjectFolder()}
            currentModel={activeSession.modelName ?? ''}
            effortLevel={activeSession.effortLevel ?? 'high'}
            ultracodeEnabled={activeSession.ultracodeEnabled}
            bareMode={activeSession.bareMode}
            onModeChange={toggleBare}
            onOpenModelPicker={() => setShowModelPicker(true)}
            onOpenEffort={() => setShowEffort(true)}
            sessionId={activeSessionId ?? ''}
            currentAgent={currentAgent}
            onOpenMarket={() => setShowMarket(true)}
            empty={storeMessages.length === 0 && !stream}
            onOpenUsageCost={() => setShowUsageCost(true)}
            onOpenChromePanel={() => setShowChromePanel(true)}
            showToBottom={!chatAtBottom}
            onScrollToBottom={() => setToBottomSignal((x) => x + 1)}
            onHardStop={handleHardStop}
            localRead={attachmentLocalRead}
          />
        )}
      </div>
      <RightPanel
        sessionContext={{
          files: sessionContext.files,
          tracks: sessionContext.tracks,
          mainProject: sessionProject.main,
          subProjects: sessionProject.subs,
          messages: sessionContext.messages,
        }}
        activeSessionId={activeSessionId}
        rightTab={ui.rightTab}
        setRightTab={(t) => uiDispatch({ type: 'SET_RIGHT_TAB', tab: t })}
        setDiffFile={(f) => uiDispatch({ type: 'SET_DIFF', file: f })}
        onOpenFile={(projectId, path) => setOpenFile({ projectId, path })}
        showToast={showToast}
        openSettingsAt={openSettingsAt}
        flashingProject={sessionProject.flashing}
        rollbackFile={rollbackFile}
        confirmFile={confirmFile}
      />
      {/* 右栏拖拽 resizer（.app 层，fixed 相对 viewport） */}
      <div className="right-resizer" ref={resizerRef} onMouseDown={handleResizeStart} />

      <DiffModal diff={currentDiff} close={() => uiDispatch({ type: 'SET_DIFF', file: null })} />
      {openFile && (
        <FileViewModal
          projectId={openFile.projectId}
          path={openFile.path}
          close={() => setOpenFile(null)}
        />
      )}
      {showEffort && (
        <EffortModal
          value={activeSession.effortLevel ?? 'high'}
          ultracodeEnabled={activeSession.ultracodeEnabled}
          onSave={saveEffort}
          onClose={() => setShowEffort(false)}
        />
      )}
      {showContextAnalyze && (
        <ContextAnalyzeModal onClose={() => setShowContextAnalyze(false)} />
      )}
      {showUsageCost && (
        <UsageCostModal
          activeSessionId={activeSessionId ?? ''}
          onSwitch={switchSession}
          onClose={() => setShowUsageCost(false)}
        />
      )}
      {showMemoryEditor && (
        <MemoryEditorModal onClose={() => setShowMemoryEditor(false)} showToast={showToast} sessionId={activeSessionId} />
      )}
      {/* CLAUDE.md 外部 include 审批（功能3）· 触发待接：后端补事件通知 或 前端检测 CLAUDE.md @import 后置位 */}
      {showIncludeApproval && (
        <IncludeApprovalModal
          files={includeApprovalFiles}
          onApprove={(approved) => {
            setShowIncludeApproval(false)
            showToast(approved ? '已允许加载外部文件' : '已拒绝加载外部文件', 'success')
          }}
          onClose={() => setShowIncludeApproval(false)}
        />
      )}
      {showModelPicker && (
        <ModelPickerModal
          providers={providersApi.list}
          activeSessionModelName={activeSession.modelName ?? ''}
          pickCurrent={pickModel}
          close={() => setShowModelPicker(false)}
        />
      )}
      {ui.showSearchPalette && (
        <SearchPalette
          searchQuery={ui.searchQuery}
          setSearchQuery={(v) => uiDispatch({ type: 'SET_SEARCH_QUERY', value: v })}
          filteredItems={filteredSearchItems}
          close={() => uiDispatch({ type: 'CLOSE_SEARCH' })}
          onSessionPick={switchSession}
          onFilePick={(id) => uiDispatch({ type: 'SET_DIFF', file: id })}
          onModelPick={() => setShowModelPicker(true)}
          onAddProjectPick={() => uiDispatch({ type: 'TOGGLE_ADD_PANEL' })}
          onSettingsPick={() => uiDispatch({ type: 'TOGGLE_SETTINGS' })}
          showToast={showToast}
        />
      )}
      {showCommandPalette && (
        <CommandPalette
          onClose={() => setShowCommandPalette(false)}
          onExecute={(name) => {
            setShowCommandPalette(false)
            if (name === 'agents') { setShowAgentsPanel(true); return }
            if (name === 'memory') { setShowMemoryEditor(true); return }
            if (name === 'chrome') { setShowChromePanel(true); return }
            void runBuiltin(name)
          }}
        />
      )}
      {showAgentsPanel && <AgentsPanel onClose={() => setShowAgentsPanel(false)} />}
      {showChromePanel && <ChromePanel sessionId={activeSessionId} onClose={() => setShowChromePanel(false)} />}
      {/* 技能市场（V58 主线程 agent 胶囊 → SkillMarketModal · 骨架版） */}
      {showMarket && (
        <SkillMarketModal
          sessionId={activeSessionId ?? ''}
          currentAgent={currentAgent}
          busy={turnRunning}
          onClose={() => setShowMarket(false)}
          onUseLocalAgent={handleMarketUseLocal}
          onUseRemoteExpert={(e) => void handleMarketUseRemote(e)}
          showToast={showToast}
        />
      )}
      <SkillSurvey />
      {ui.showSettings && (
        <SettingsModal
          settingsTab={settings.settingsTab}
          setSettingsTab={(t: SettingsTab) => settingsDispatch({ type: 'SET_SETTINGS_TAB', tab: t })}
          theme={settings.theme}
          setTheme={(t) => settingsDispatch({ type: 'SET_THEME', theme: t })}
          fontSize={settings.fontSize}
          setFontSize={(s) => settingsDispatch({ type: 'SET_FONT_SIZE', size: s })}
          animationsEnabled={settings.animationsEnabled}
          setAnimationsEnabled={(b) => settingsDispatch({ type: 'SET_ANIMATIONS', enabled: b })}
          models={models}
          providers={providersApi.list}
          providersApi={providersApi}
          skillsApi={skillsApi}
          mcpApi={mcpApi}
          databasesApi={databasesApi}
          schedulesApi={schedulesApi}
          appSettings={appSettings}
          onSaveSettings={onSaveSettings}
          fastModel={fastModel}
          pickFast={pickFastModel}
          clearFast={clearFastModel}
          onOpenMemoryEditor={() => setShowMemoryEditor(true)}
          close={() => uiDispatch({ type: 'CLOSE_SETTINGS' })}
          showToast={showToast}
        />
      )}
      <ProjectContextMenu
        contextMenu={ui.contextMenu}
        mainProject={sessionProject.main}
        handlePromote={handlePromote}
        unbindProject={unbindProject}
        showToast={showToast}
      />
      {showDialogOps && (
        <DialogOpsModal
          messages={storeMessages}
          initialTab={dialogOpsTab}
          onCompact={handleConfirmCompact}
          onTrim={handleTrimAfter}
          onClose={() => setShowDialogOps(false)}
        />
      )}
      <Toast toast={ui.toast} />
    </div>
  )
}

export default App
