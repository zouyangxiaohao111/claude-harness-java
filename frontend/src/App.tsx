import './styles/globals.css'
// V2 markdown 渲染器的代码块样式（照搬源站 .chat-code-block 系列）。
// ⚠️ 必须排在 globals.css 之后：V1 的 .md-code-block 规则还在 globals.css 里，
//    两者选择器不同（不冲突），但同权重时先加载者被覆盖 —— 放后面确保源站样式生效。
import './styles/chat-code-block.css'
// 代码高亮配色（照搬源站 GitHub Light/Dark）。
// ⚠️ 必须有：highlight.js 只切 token 不配色，缺这个文件代码块全是单色（真机缺陷）。
import './styles/chat-code-hljs.css'
// Markdown 阅读排版 · 统一用「知识库那套」（Typora 风），只服务预览，⛔ 不影响对话消息。
// 来源：kb-02.css 的 .kb-scope .md-content（59 条正文规则 + 33 个变量解析成字面量）。
import './styles/markdown-prose.css'
// 知识库设计稿对齐样式（自动从 weknora-bundle.css 提取，全表作用域在 .kb-scope 子树内）。
// ⚠️ 必须排在 globals.css 之后：同权重时靠后者胜出。
import './styles/kb/index.css'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  allProjects,
  models,
  searchItems,
  getDiffFor,
  brandTagFromName,
} from '@/data'
import type { Project, Session, SettingsTab, ModelTag, DiffFile, RightTab } from '@/types'
import type { AppSettings, AttachmentRequest, ModelTierRole, SessionDto, ChatMessageDto, UpdateSettingsRequest, PermissionMode, PivotCandidateDto } from '@/api/types'
import { sessionApi } from '@/api/sessions'
import { sessionFilesApi } from '@/api/sessionFiles'
import { projectApi, type ProjectDto } from '@/api/projects'
import { selectProjectFolder } from '@/utils/projectFolder'
import { isAbsolutePath, normalizePath } from '@/utils/path'
import { compareSessions } from '@/utils/sessionOrder'
import { resolveNewSessionProjectId } from '@/utils/newSessionProject'
import { chatApi } from '@/api/chat'
import { artifactsApi, type SessionArtifacts, type SessionArtifactDto } from '@/api/artifacts'
import { buildTurnContext, dedupeByFile } from '@/utils/artifactGrouping'
import { goalApi, splitGoalKickoff } from '@/api/goal'
import { usageTurnsApi, type TurnUsageDto } from '@/api/usageTurns'
import { commandApi, isPromptExecuteResult } from '@/api/command'
import { tasksApi } from '@/api/tasks'
import { settingsApi } from '@/api/settings'
import { mergeSettings } from '@/api/settingsMerge'
import { attachmentApi } from '@/api/attachment'
import { ApiError } from '@/api/rest'
import { debugLog } from '@/utils/debugLog'
import { rememberAppName } from '@/utils/appNameCache'
import { buildDrainedUserMessages, type DrainedQueueItem } from '@/utils/queuedUserBubble'
import { computeTurnRunning } from '@/utils/turnRunning'
import { FOLD_DEFAULT_TURNS, isUserTurnRow } from '@/stores/messageTurns'
import { refreshServerRunning, useServerRunningRebuild, useServerRunningReconcile } from '@/hooks/useServerRunning'
import { useRunningSubagentSessionIds, useSubagentRunningReconcile } from '@/hooks/useSubagentRunning'
import { sendSystemNotification, ensureNotificationPermissionOnGesture } from '@/utils/systemNotify'
import { onSystemNotificationClick } from '@/utils/systemNotify'
import { showMainWindow } from '@/utils/tauri-bridge'
import { openKnowledgeWindow } from '@/utils/kbWindow'
import { updateTrayRecent, onTraySwitchSession } from '@/utils/trayRecent'
import { useChatStore, selectCompact, collectRemovedUuids } from '@/stores/chatStore'
import { parseGoalPayload } from '@/utils/goalState'
import { useChatSocket, type ArtifactPushPayload, type TurnEndKind } from '@/hooks/useChatSocket'
import { useAwaySummary } from '@/hooks/useAwaySummary'
import { sendPermissionResponse } from '@/api/socket'
import { useClickOutside, useEscapeKey, useTheme, useFontSize, useAnimations, useToastDispatcher } from '@/hooks'
import { useProviders } from '@/hooks/useProviders'
import { useMcp } from '@/hooks/useMcp'
import { useDatabases } from '@/hooks/useDatabases'
import { useSchedules } from '@/hooks/useSchedules'
import { useFirstRunGuide } from '@/hooks/useFirstRunGuide'
import { useTierInheritGuide } from '@/hooks/useTierInheritGuide'
import { useSession, useProject, useUI, useSettings } from '@/reducers'

import { TitleBar } from '@/components/layout/TitleBar'
import { MenuBar } from '@/components/layout/MenuBar'
import { SessionList } from '@/components/left/SessionList'
import { MessageList } from '@/components/center/MessageList'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { AskUserPendingHint } from '@/components/center/AskUserPendingHint'
import { SubagentRunningHint } from '@/components/center/SubagentRunningHint'
import { DialogOpsModal } from '@/components/center/DialogOpsModal'
import { RetryBanner } from '@/components/center/RetryBanner'
import { TokenWarningBanner } from '@/components/center/TokenWarningBanner'
import { CompactProgressBar } from '@/components/center/CompactProgressBar'
import { NotificationBanner } from '@/components/center/NotificationBanner'
import { Composer, SUMMON_TRAILING_SPACES } from '@/components/center/Composer'
import { WorkspaceHeader } from '@/components/center/WorkspaceHeader'
import { SchedulesPage } from '@/components/center/SchedulesPage'
import { KnowledgeBasePage, KbSceneNav } from '@/components/knowledge/KnowledgeBasePage'
import { KbNewChatPane } from '@/components/knowledge/KbNewChatPane'
import { TraceView } from '@/components/center/TraceView'
import { useCommandQueue, type PoppedQueueInput } from '@/hooks/useCommandQueue'
import { CommandPalette, isDisabledCommand, isKnownCommand } from '@/components/center/CommandPalette'
import { AgentsPanel } from '@/components/center/AgentsPanel'
import { ChromePanel } from '@/components/center/ChromePanel'
import { SkillSurvey } from '@/components/center/SkillSurvey'
import { RightPanel } from '@/components/right/RightPanel'
import { ArtifactDock } from '@/components/right/ArtifactDock'
import { ProjectContextMenu } from '@/components/right/ProjectContextMenu'
import { SearchPalette } from '@/components/modals/SearchPalette'
import { SettingsModal } from '@/components/modals/SettingsModal'
import { BasicSettingsModal } from '@/components/modals/BasicSettingsModal'
import { TourOverlay } from '@/components/startup/tour/TourOverlay'
import { ModelPickerModal } from '@/components/modals/ModelPickerModal'
import { TierInheritModal } from '@/components/modals/TierInheritModal'
import { EffortModal } from '@/components/modals/EffortModal'
import { ContextAnalyzeModal } from '@/components/modals/ContextAnalyzeModal'
import { UsageCostModal } from '@/components/modals/UsageCostModal'
import { MemoryEditorModal } from '@/components/modals/MemoryEditorModal'
import { SkillMarketModal } from '@/components/modals/SkillMarketModal'
import { IncludeApprovalModal } from '@/components/modals/IncludeApprovalModal'
import { describeIncludeStatusProbeFailure, getIncludeStatus } from '@/api/claudeMd'
import { Toast } from '@/components/common/Toast'
import { UpdateCenter } from '@/components/updater/UpdateCenter'

// DiffModal/FileViewModal 改静态 import：monaco 直接打进主包，启动解析一次；
// 桌面 Tauri 本地加载快，换取「点开 diff 零拉取零等待」（放弃此前 L5 懒加载拆分）。
import { DiffModal } from '@/components/modals/DiffModal'
import { FileViewModal } from '@/components/modals/FileViewModal'
import { ArtifactPreviewModal } from '@/components/modals/ArtifactPreviewModal'
import { ArtifactViewModal } from '@/components/modals/ArtifactViewModal'
import { artifactOpenActionOf, openArtifactRawInBrowser } from '@/utils/artifactOpen'
import { openStandalone, previewKindOfPath, basenameOf } from '@/utils/standalonePreview'

/** 会话主模型只允许对话类；embedding/rerank 等非对话模型不能被选为会话模型。
 *  空 type 按历史对话模型兼容（老数据没有 type 值）。 */
const CHAT_MODEL_TYPES = new Set(['chat', 'text', 'vision', 'multimodal'])
const isChatModelType = (t?: string | null) => !t || CHAT_MODEL_TYPES.has(t.toLowerCase())

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

/** 空项目（无绑定项目时右栏「项目」tab 兜底 → 显示「未绑定项目」而非演示名）。 */
const EMPTY_PROJECT: Project = { id: '', name: '', branch: '', dirty: 0, agents: 0, path: '' }

/** 后端 SessionDto → UI 旧 Session 形状（null 兜底，任务 9 起组件原生消费 DTO 后移除）。 */
/** 稳定空数组（避免 selector `?? []` 每次返回新引用触发 useSyncExternalStore 无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []
/** [snip-persist] 空裁剪 id 列表常量（避免选择器每次新建 [] → 引用变化触发重渲染） */
const EMPTY_SNIPPED_IDS: string[] = []
/** 稳定空产物列表（artifacts 接口未就绪 / 切会话重置时兜底 · 避免每次新建对象引用） */
const EMPTY_ARTIFACTS: SessionArtifacts = { deliverables: [], sources: [] }
/**
 * 右栏形态：改版后右栏恒为**抽屉**（默认收起、不占三栏列宽，宽度/拖拽条由 RightPanel 自带）。
 * ⇒ 宿主这条「右栏拖拽 resizer」不再挂载（它改的是 `--right-w`，抽屉不消费）。
 */
const RIGHT_PANEL_DRAWER = true

/**
 * 档位角色 → AppSettings 字段名（写 settings 列用）。
 *
 * <p><b>单一来源</b>：档位的 wire key 与后端 `ModelTierRole` 枚举一一对应；
 * 「档位 → settings 列」的映射只此一份（面板与 App 都从这里取，避免两处各写一遍而漂移）。
 */
const TIER_FIELD_BY_ROLE: Record<ModelTierRole, keyof AppSettings> = {
  main: 'mainModelName',
  fast: 'fastModelName',
  strong: 'strongModelName',
  medium: 'mediumModelName',
  weak: 'weakModelName',
  subagent: 'subagentModelName',
  fallback: 'fallbackModelName',
  multimodal: 'multimodalModelName',
  tts: 'ttsModelName',
  asr: 'asrModelName',
  classifier: 'classifierModel',
}

/**
 * 「一键配置到全部档位」覆盖的角色：全部 − TTS / ASR。
 *
 * <p>TTS（语音合成）与 ASR（语音转文字）是专用模型，把通用对话模型写进去只会得到配错的档位。
 * 与 `ModelSettingsPanel.APPLY_ALL_ROLES` 保持一致（面板负责展示、App 负责写库）。
 */
const APPLY_ALL_TIER_ROLES: ModelTierRole[] = [
  'main', 'strong', 'fast', 'subagent', 'weak', 'medium', 'multimodal', 'fallback', 'classifier',
]

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
  // [TL-W1 P4] 传当前会话 id（= 下方 activeSessionId = sessionR.activeSession，此处尚未解构故直接用
  //   同一源）：技能列表按会话绑定项目解析，并在切会话时重拉（不传 = 后端无会话上下文）。
  const mcpApi = useMcp()
  const databasesApi = useDatabases()
  // [cwd3] 传当前会话 id（与 :348 的 activeSessionId 同源 = sessionR.activeSession）：定时任务的
  //   创建必须带会话锚（后端据它解析任务的项目锚并覆盖请求体里的 boundProject）。
  //   此处直接传 sessionR.activeSession（不提升 :348 的 const，避免改动无关代码）。
  const schedulesApi = useSchedules(sessionR.activeSession)

  // ---- all sessions：来自后端（chatStore.sessions，挂载时 sessionApi.list() 拉取）----
  const storeSessions = useChatStore((s) => s.sessions)
  const setSessions = useChatStore((s) => s.setSessions)
  /**
   * 会话列表请求序号（防「在途响应把本地改动冲掉」）。
   *
   * <p>只有**序号仍是最新**的响应才允许 `setSessions`。本地改动（新建 / 删除会话）也 `++`，
   * 使在途的旧请求结果被丢弃 —— 否则静默轮询会出现「新建的会话闪一下又消失，最多 5 秒后才回来」。
   */
  const sessionListSeqRef = useRef(0)
  // [goal V253] 目标状态写入（STOMP 推送 / REST 拉取都汇到这里）
  const setGoal = useChatStore((s) => s.setGoal)

  // ---- per-session project state (so right panel syncs)；不再从 demo sessionContexts 播种 ----
  const [perSessionProjects, setPerSessionProjects] = useState<Record<string, SessionProjectState>>({})
  // #1 绑定策略：真实项目缓存（projectApi.list），供 projectNameFor / createSession 反查 main 项目名，
  //   替代无 id 的 mock allProjects。挂载时拉取，失败优雅降级（回落 mock）。
  const [realProjects, setRealProjects] = useState<Project[]>([])
  useEffect(() => {
    let cancelled = false
    projectApi.list()
      .then((list) => { if (!cancelled) setRealProjects(list.map((d) => ({ id: d.id, name: d.name, branch: d.branch ?? '', dirty: d.dirty ?? 0, agents: d.agents ?? 0, path: d.path ?? '', createdAt: d.createdAt ?? undefined }))) })
      .catch(() => { /* 后端未就绪则回落 mock allProjects，不阻断 */ })
    return () => { cancelled = true }
  }, [])

  // ---- 技能市场弹窗（Composer 顶部 agent 胶囊点击触发 · App 持有开关 + 渲染 SkillMarketModal）----
  const [showMarket, setShowMarket] = useState(false)
  /**
   * 知识库浮层（2026-11-06 用户裁定：知识库是**独立场景**，用弹窗承载，
   * ⛔ 不再替换主区 —— 主对话保持原位可见）。
   *
   * <p>⚠️ 与 `workspacePage` 解耦：定时任务仍是主区换页（对话面被 CSS 隐藏），
   * 知识库是浮层，两者语义不同，不要合并成一个 state。
   */
  const [showKnowledge, setShowKnowledge] = useState(false)
  /**
   * 知识库浮层的拖动位移（用户 2026-11-09 裁定：保留弹窗但要能移开）。
   *
   * <p>⚠️ 与面板的居中定位并存：居中由 `.kb-scene-overlay` 的 flex 负责，
   * `transform: translate()` 只叠加在中心点之上 ⇒ 用户把窗口挪到任意位置，
   * 窗口尺寸变化时仍会回到「相对居中」的位置（不会漂到屏幕外）。
   *
   * <p>⚠️ 关闭时**不复位**：用户把面板挪到右边、关掉、再打开，应该还在右边
   * —— 那是他的偏好，复位等于每次都要重拖一遍。
   */
  const [kbDrag, setKbDrag] = useState({ x: 0, y: 0 })
  /**
   * 「正在拖」的同步标志（**不能用 state**）。
   *
   * <p>mouse 与 pointer 两套处理器会在同一帧内先后触发，state 更新是异步的 ⇒
   * 第二次进来时 `kbDragging` 还是 false，去重失效、位移翻倍。ref 是同步的。
   */
  const kbDragActiveRef = useRef(false)
  /** 拖动中（用来临时关掉 transition，否则拖动会「橡皮筋」式滞后） */
  const [kbDragging, setKbDragging] = useState(false)

  /**
   * 知识库面板拖动的统一入口（mouse + pointer 双通道）。
   *
   * <p><b>为什么必须双通道（实测事故）</b>：只挂 `onPointerDown` 时，在部分渲染环境下
   * **真实鼠标按下只产生 `mousedown`，一条 `pointerdown` 都没有** ——
   * 于是「按住拖动条」完全没反应，用户报「知识库弹窗无法移动」。
   * 合成事件测试查不出这种问题（合成 PointerEvent 当然能触发 pointer 处理器）。
   *
   * <p>⚠️ 支持 Pointer Events 的环境里两套都会派发 ⇒ 用 {@link kbDragActiveRef}
   * 做「已在拖动中」的去重，否则一次拖动会同时被两个 handler 处理、位移翻倍。
   */
  function startDrag(clientX: number, clientY: number, button: number) {
    if (button !== 0 || kbDragActiveRef.current) return
    kbDragActiveRef.current = true
    const startX = clientX - kbDrag.x
    const startY = clientY - kbDrag.y
    // 面板基准位置（居中后的静态位置，**不含当前位移**）——用来算可拖范围
    const base = document.querySelector('.kb-scene-panel')?.getBoundingClientRect()
    const baseX = base ? base.x - kbDrag.x : 0
    const baseY = base ? base.y - kbDrag.y : 0
    const baseW = base ? base.width : window.innerWidth
    /** 至少留 80px 在视口内，防止整块拖出去再也点不到 */
    const KEEP = 80
    const minX = -baseX - baseW + KEEP
    const maxX = window.innerWidth - baseX - KEEP
    const minY = -baseY
    const maxY = window.innerHeight - baseY - KEEP
    const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi)

    setKbDragging(true)
    const onMove = (ev: MouseEvent | PointerEvent) => {
      setKbDrag({
        x: clamp(ev.clientX - startX, minX, maxX),
        y: clamp(ev.clientY - startY, minY, maxY),
      })
    }
    const onUp = () => {
      kbDragActiveRef.current = false
      // 两套都摘，摘不存在的监听是无害的空操作
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      setKbDragging(false)
    }
    // 挂到 window：拖出拖动条（甚至拖出面板）也要继续跟手
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  /** 知识库场景内的当前面：知识库 / 共享空间（侧栏切换） */
  const [kbScene, setKbScene] = useState<'knowledge' | 'shared'>('knowledge')
  /** 知识库弹窗主区视图：列表 / 新对话（弹窗内换页，不关弹窗） */
  const [kbView, setKbView] = useState<'knowledge' | 'chat'>('knowledge')
  /** 「新对话」信号：自增 ⇒ 弹窗内强制进入空白会话（与独立窗口同一套规则） */
  const [kbNewChatToken, setKbNewChatToken] = useState(0)
  // 每次打开知识库都回到列表页：否则上次停在「新对话」（一条空会话）
  // 再次打开看起来就是「空白弹窗」。关闭时不动 state，交给这里重置。
  useEffect(() => {
    if (showKnowledge) setKbView('knowledge')
  }, [showKnowledge])
  /** 斜杠前缀 chip 的显示别名（召唤技能时携带中文显示名；Composer 仅在前缀名匹配时使用） */
  const [slashAlias, setSlashAlias] = useState<{ name: string; display: string } | null>(null)
  /** sendMessage 内读取最新别名的 ref（避免把 alias 加进 useCallback 依赖列表） */
  const slashAliasRef = useRef<{ name: string; display: string } | null>(null)
  slashAliasRef.current = slashAlias

  // ---- local UI state (kept tiny) ----
  // 中心区视图：'chat' 对话 / 'trace' 轨迹（dsh 式记录列表）
  const [centerView, setCenterView] = useState<'chat' | 'trace'>('chat')
  const [composerText, setComposerText] = useState('')
  // F19/#3 排队命令状态机（后端 B4/B5 未接时恒空，优雅降级）
  // [2026-10-23] 传当前会话：排队快照按会话分桶（多会话并行时 B 的 queue.changed 不得覆盖 A 的排队框）。
  //   此处与 :385 的 activeSessionId 同源（sessionR.activeSession），沿用 :133 的写法避免提升 const。
  const commandQueue = useCommandQueue(sessionR.activeSession)
  // `currentModel` is derived from the active session's `modelName` —
  // every tab (session) carries its own main agent model, and switching
  // tabs changes the chat header / menubar automatically.
  //
  // [2026-10-31] 快速模型（全局档位 · Java `AgentDefaults.fastModel`）**不再在本组件持有 state**：
  // 唯一真相源 = appSettings.fastModelName（后端 settings 单例行）。此前另存了一份 state 再由
  // effect 从 settings 同步 —— 两份真相源必然漂移（实测：清除按钮只清 state 不落库 ⇒ 刷新后绑定复活）。
  // 现改为由面板直接读 settings，写入走 onSaveSettings → applyAppSettings → 界面自动更新。
  const [showModelPicker, setShowModelPicker] = useState(false)
  const [showEffort, setShowEffort] = useState(false)
  // [Phase2] 真实 diff（改动文件行点击拉 REST · 取代 mock getDiffFor 演示；close/Esc 时清空）
  const [liveDiff, setLiveDiff] = useState<DiffFile | null>(null)
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

  // 会话激活后探测外部 @import 审批态（后端 GET /claude-md/include-status?sessionId=，2026-08-24 实现）：
  // needsApproval=true（存在外部 include && 未审批 && 未示警）→ 弹 IncludeApprovalModal。
  // [T15-3 2026-09-14] 后端审批态已按**项目**分区（由 sessionId 解析项目根；CC 的两个标志宿主
  //   都是 project config，claudemd.ts:796 / :1420）⇒ 探测必须带会话，且**触发时机从「挂载一次」
  //   改为「会话激活时」**：activeSession 初值恒为 ''（reducers.ts:34，会话列表是异步 list() 之后
  //   才 SWITCH 到第一个），旧 deps=[] 必然在零态发请求 ⇒ 后端 400（缺 sessionId）⇒ 被 catch 静默
  //   吞掉 ⇒ 弹窗永不出现（功能死）。零态**不发请求** = 客户端的 (b) 类「本就不需要」；
  //   ⚠️ 服务端仍按 (a) 类走 400，⛔ 不因客户端不发而放宽。
  // 切会话时先关闭弹窗：弹窗携带的是**上一个项目**的待审批文件，留着会让用户对 B 项目批准 A 的文件。
  // 失败**按响应分流**（⛔ 不一律静默，见下）：
  //   ① 网络异常（ApiError.status === 0，见 api/rest.ts 的 fetch catch 分支）⇒ 静默 —— 后端未就绪
  //      不应阻塞启动，属 (b) 类「本就不需要」；
  //   ② HTTP ≥400 ⇒ **≥console.warn** —— 本端点按用户裁定「每个会话一定有绑定目录，查不到就是严重
  //      bug」⇒ 400（缺 sessionId / 解析不到项目根）属 (a) 类「本该有却没有」；一律静默会让该缺陷
  //      **结构性不可见**（既不弹窗、也无 toast、更无日志），正是本改造要消灭的失败模式；
  //   ③ 非 ApiError 异常 ⇒ 同样 ≥warn（不静默）。
  // [TL-W1 P4] 用 sessionR.activeSession（= 下方 activeSessionId，此处尚未解构故直接用同一源）
  useEffect(() => {
    const sid = sessionR.activeSession
    setShowIncludeApproval(false)
    if (!sid) return
    let cancelled = false
    getIncludeStatus(sid)
      .then((st) => {
        if (cancelled) return
        if (st.needsApproval) {
          setIncludeApprovalFiles(st.files)
          setShowIncludeApproval(true)
        }
      })
      .catch((e: unknown) => {
        if (cancelled) return
        // 分流判据单点 = describeIncludeStatusProbeFailure（见该函数 javadoc + 其单测）
        const warning = describeIncludeStatusProbeFailure(e, sid)
        if (warning !== null) console.warn(warning, e)
      })
    return () => { cancelled = true }
  }, [sessionR.activeSession])
  /** 活跃流式会话 → streamTopic（多会话并行订阅：sendMessage 登记 / complete 移除） */
  const [activeStreams, setActiveStreams] = useState<Record<string, string>>({})
  // 合并「对话操作」弹窗（双击 Esc → 压缩 tab；消息 hover「↺ 回退到此」→ 裁剪 tab）
  const [showDialogOps, setShowDialogOps] = useState(false)
  const [dialogOpsTab, setDialogOpsTab] = useState<'compact' | 'trim'>('compact')
  // [dialog-ops-pivot] 弹窗候选（当前上下文可见用户消息 · 轻量端点；打开时拉取）
  const [dialogOpsCandidates, setDialogOpsCandidates] = useState<PivotCandidateDto[]>([])
  const [dialogOpsLoading, setDialogOpsLoading] = useState(false)
  const [dialogOpsError, setDialogOpsError] = useState<string | null>(null)
  // [历史提问] 主区头部「历史提问」面板：候选 = 本会话**全部** user 消息（不经 compact 边界过滤）。
  //   与上方 dialogOpsCandidates（当前上下文可见）分属两条口径，不复用。
  const [historyQuestions, setHistoryQuestions] = useState<PivotCandidateDto[]>([])
  const [showHistoryPanel, setShowHistoryPanel] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const historyReqSeq = useRef(0)
  // 主区页面：对话 / 定时任务 / 知识库（主区换页，不弹设置弹窗）
  const [workspacePage, setWorkspacePage] = useState<'chat' | 'schedules' | 'knowledge'>('chat')
  // 右栏抽屉开合（改版后右栏默认收起，由头部面板按钮 / 消息底部入口打开）
  const [rightDrawerOpen, setRightDrawerOpen] = useState(false)
  // 基础设置（左下角用户行 → 轻量四项弹窗）
  const [showBasicSettings, setShowBasicSettings] = useState(false)
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
  // [产物预览] 浏览器通道：产物 = 归档副本（不在项目根内）⇒ 走 /artifacts/{id}/content 只读预览
  const [previewArtifact, setPreviewArtifact] = useState<{ sessionId: string; artifact: SessionArtifactDto; initialEdit?: boolean } | null>(null)
  // [产物预览 · html] Monaco 查看/编辑/保存 + 运行（与项目文件 FileViewModal 同一套交互 · 数据源不同）
  const [viewArtifact, setViewArtifact] = useState<{ sessionId: string; artifact: SessionArtifactDto; initialEdit?: boolean } | null>(null)
  /**
   * [产物独立侧栏 2026-11-02 用户裁定] 只服务「产物自动打开」这一个场景的右侧面板。
   *
   * <p><b>WHY 不复用 RightPanel 抽屉</b>：抽屉是七个 tab 的导航器，产物只是其中一格；
   * 而实时预览要的是「单个产物沉浸查看」。两者混在一起用户永远要确认「我在看哪条栏」。
   * 抽屉保持原样（浏览全部会话资源），本面板只在产物落地时打开（跟进刚生成的那一个）。
   */
  const [dockArtifact, setDockArtifact] = useState<SessionArtifactDto | null>(null)
  const [dockWidth, setDockWidth] = useState<number>(() => {
    const saved = typeof localStorage !== 'undefined' ? Number(localStorage.getItem('nexusai-artifact-dock-w')) : 0
    return Number.isFinite(saved) && saved >= 320 ? saved : 460
  })
  useEffect(() => {
    try { localStorage.setItem('nexusai-artifact-dock-w', String(dockWidth)) } catch { /* 隐私模式忽略 */ }
  }, [dockWidth])
  /**
   * 用户是否**手动收起过**产物侧栏。
   *
   * <p>自动弹出的规则（用户裁定「自动打开」但也要能收起）：用户刚明确表示「我不想看」时，
   * 新产物不再自动把面板弹回去 —— 只在聊天流里留产物卡，点了才开。否则侧栏变成关不掉的东西。
   */
  const dockUserClosedRef = useRef(false)

  // ---- refs ----
  const modelDropdownRef = useRef<HTMLDivElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  // 右栏拖拽调宽：网格列统一走 CSS var（--left-w/--right-w），resizer 只改 --right-w；
  //   折叠 = 给 .app 加 left-collapsed/right-collapsed 类把对应列置 0 + 隐藏 rail（不丢已存宽度）
  const resizerRef = useRef<HTMLDivElement>(null)
  const handleResizeStart = (e: React.MouseEvent) => {
    e.preventDefault()
    const appEl = document.querySelector('.app') as HTMLElement | null
    if (!appEl) return
    const startX = e.clientX
    const startW = parseFloat(appEl.style.getPropertyValue('--right-w')) || 300
    let currentW = startW
    const move = (ev: MouseEvent) => {
      // resizer 在右栏左边缘：往左拖（clientX 减小）→ 右栏变宽
      // 上限从固定 640 改为「视口 70%」（用户 2026-10-21：拖到 640 就卡住不合理）
      const maxW = Math.max(420, Math.round(window.innerWidth * 0.7))
      currentW = Math.max(220, Math.min(maxW, startW - (ev.clientX - startX)))
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
  // 挂载时恢复用户调整过的右栏宽度（只写 var）
  useEffect(() => {
    const saved = localStorage.getItem('nexusai-right-w')
    if (saved) {
      const w = Math.max(220, Math.min(Math.max(420, Math.round(window.innerWidth * 0.7)), parseInt(saved, 10) || 300))
      const appEl = document.querySelector('.app') as HTMLElement | null
      if (appEl) appEl.style.setProperty('--right-w', `${w}px`)
    }
  }, [])
  // 左右栏折叠（会话内临时；启动一律展开 —— 不读/不写 localStorage，避免误存的"已收起"让下次启动只剩中栏）
  // 折叠后网格列归 0、rail 隐藏，经边缘细竖条/头部三横线重开。
  const [leftCollapsed, setLeftCollapsed] = useState(false)
  const [rightCollapsed, setRightCollapsed] = useState(false)
  // 清理历史误存（旧版持久化残留 key）：防止有遗留 true 时再次读到
  useEffect(() => {
    localStorage.removeItem('nexusai-left-collapsed')
    localStorage.removeItem('nexusai-right-collapsed')
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
  // ⭐ appSettings 的**唯一写入口**：只读字段（appName/configHome）在此单点保留。
  //   ⛔ 别处一律不许直接 setAppSettings —— 直接写 = 绕过只读字段保留：PUT /settings 的响应
  //   契约是后端 SettingsDto（**不含** appName/configHome），写进去会把 appName 抹成 undefined
  //   ⇒ PermissionBubble.selfDirName 变 null ⇒ 自有根档位文案**静默**退化回 .nexusai/。
  //   （根修理由与合并规则见 api/settingsMerge.ts 的 mergeSettings。）
  const applyAppSettings = useCallback(
    (incoming: AppSettings) => setAppSettings((prev) => mergeSettings(prev, incoming)),
    [],
  )
  useEffect(() => {
    let cancelled = false
    settingsApi.get()
      .then((s) => {
        if (cancelled) return
        applyAppSettings(s)
        // [appName 通道 2026-09-23] 把只读 appName 落进极薄缓存：后端起不来时 LaunchGate
        //   拿不到 GET /settings，只能靠上次成功加载的值拼日志路径（见 utils/appNameCache）。
        rememberAppName(s.appName)
      })
      .catch(() => { /* 后端未就绪则档位显示未配置，不阻断 */ })
    return () => { cancelled = true }
  }, [applyAppSettings])
  const onSaveSettings = useCallback(async (req: UpdateSettingsRequest): Promise<boolean> => {
    try {
      const updated = await settingsApi.update(req)
      // PUT 的响应契约仍是 SettingsDto（**不含**只读的 appName/configHome）⇒ 必须经 applyAppSettings
      //   （内部 mergeSettings 从旧值保留只读字段），否则每次保存设置后 PermissionBubble 的
      //   自有根标签会静默退化回 .nexusai/。⛔ 不许在此处再手写一份合并（本仓刚因「两套写法」返工过）。
      applyAppSettings(updated)
      return true
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      // ⚠️ 必须把「失败」回传给调用方，否则面板会以为保存成功再弹一条成功提示
      //   ⇒ 用户看到「已设为 X」但配置根本没变（本仓 CLI 默认运行时踩过：值域不匹配被 400 拒，
      //   错误提示被吞 ⇒ 界面显示成功、DB 纹丝不动）。
      return false
    }
  }, [showToast, applyAppSettings])

  // ---- 挂载时从后端拉取会话列表 ----
  // 无 demo 播种：列表空 → activeSession 保持 ''（右栏文件=0、不再渲染演示假文件，首页保持原 welcome hero）；
  // 列表非空 → 自动切到第一个真实会话（真实 id）。
  useEffect(() => {
    let cancelled = false
    const mySeq = ++sessionListSeqRef.current
    sessionApi.list()
      .then((list) => {
        if (cancelled || mySeq !== sessionListSeqRef.current) return
        setSessions(list)
        if (list.length > 0 && !list.some((s) => s.id === sessionR.activeSession)) {
          sessionDispatch({ type: 'SWITCH', sessionId: list[0].id })
        }
      })
      .catch((e) => {
        if (cancelled) return
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setSessions, showToast])

  // ---- 会话列表静默轮询（2026-11-10 用户裁定：「组内会动」要实时，方式=后台定时刷新、前端无感）----
  //
  // WHY 需要它：后端每个用户消息入库都会写 sessions.updated_at（MessageService 三处），
  //   但前端**没有任何地方本地更新 updatedAt**，也没有「列表变了」的 WS 事件 ⇒ 不轮询的话
  //   组内顺序只在「新建会话 / F5」两个时刻才变，用户看不到「谁最近活动」。
  //
  // 为什么是整表 setSessions 而不是增量 patch：列表只有几十行，整表替换最省事且不会漏字段；
  //   渲染层靠 React diff，引用变了但值相同 ⇒ 不重排 DOM。
  //
  // ⚠️ 静默语义：成功不弹 toast、失败也不弹（网络抖动不该在用户面前刷红条）；
  //   ⛔ 不切换 activeSession —— 首屏那次拉取才负责「无会话时自动切第一个」。
  // ⚠️ 页面在后台（切到别的 tab / 窗口最小化）时跳过：没人看，白耗后端连接池（SQLite 写锁敏感）。
  useEffect(() => {
    let cancelled = false
    const poll = () => {
      if (cancelled || document.visibilityState === 'hidden') return
      const mySeq = ++sessionListSeqRef.current
      sessionApi.list()
        .then((list) => { if (!cancelled && mySeq === sessionListSeqRef.current) setSessions(list) })
        .catch(() => { /* 静默：轮询失败不打扰用户，下一轮自然重试 */ })
    }
    const timer = window.setInterval(poll, 5000)
    // 切回前台立刻补一次（否则最多要等 5s 才看到期间发生的变化）
    const onVisible = () => { if (document.visibilityState === 'visible') poll() }
    document.addEventListener('visibilitychange', onVisible)
    return () => { cancelled = true; window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [setSessions])

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
  const sessions = useMemo<Session[]>(() => storeSessions.map(toSession), [storeSessions])  // #1 绑定策略：activeSession 用 SessionDto（含 mainProjectId），供发送拦截/绑定判断
  const activeSession = storeSessions.find((s) => s.id === activeSessionId) ?? storeSessions[0] ?? EMPTY_SESSION_DTO
  // 当前会话权限模式：会话覆盖 ?? 全局默认 ?? 'default'
  const activePermissionMode: PermissionMode = activeSession?.permissionMode ?? appSettings?.permissionMode ?? 'default'
  // 当前会话主线程 agent（V58 main_thread_agent · null/空串 = 默认模式，Composer 顶部胶囊显示）
  const currentAgent = activeSession?.mainThreadAgent ?? null

  // 本地专家/市场数据由 SkillMarketModal 打开时自行拉取（agentApi.listAgents + marketApi.*），App 不再常驻清单。

  // ---- 消息渲染源：chatStore.messages + 当前流式 ----
  const storeMessages = useChatStore((s) => s.messages[activeSessionId] ?? EMPTY_MESSAGES)
  // [snip-persist] 轨迹视图「已裁剪」标注源：当前会话被 Snip 裁剪的消息 id（STOMP message.boundary 实时 + F5 解析）
  const activeSnippedIds = useChatStore((s) => (activeSessionId ? (s.snippedIds[activeSessionId] ?? EMPTY_SNIPPED_IDS) : EMPTY_SNIPPED_IDS))
  // [trace-count] 轨迹 tab 徽标全量：GET /messages/page total（DB sessions.messageCount 非 meta 口径）· 未拉到时回落窗口条数
  const activeMsgTotal = useChatStore((s) => (activeSessionId ? (s.msgTotals[activeSessionId] ?? null) : null))
  // [trace-count 轮询] 轨迹徽标实时：每 5s 拉后端该会话 count（DB sessions.messageCount · 非 meta 口径，轻量）。
  //   覆盖 新消息落库 / 删除 / 裁剪 等一切变化，不依赖「窗口是否全量」。
  useEffect(() => {
    if (!activeSessionId) return
    let alive = true
    const sid = activeSessionId
    const tick = async () => {
      try {
        const r = await chatApi.messageCount(sid)
        if (alive) useChatStore.getState().setMsgTotal(sid, r.total)
      } catch { /* 轮询失败静默：下次 tick 兜底；断网/后端未就绪不打扰 */ }
    }
    void tick()
    const t = window.setInterval(tick, 5000)
    return () => { alive = false; window.clearInterval(t) }
  }, [activeSessionId])
  // [流式性能 2026-09-09] 不再顶层订阅整条 streams[sid] 引用（打字机逐帧 append 会让整 App 每帧重渲）。
  //   改订 streamOrder 长度（结构信号）：块增/删才变化, content 推进不触碰 —— 内容订阅已下沉 MessageList
  //   每行 StreamBlockRow（selectStreamBlock）。此处只用于 turnRunning / thinking / Composer.empty 的「有无流式块」。
  const streamBlockCount = useChatStore((s) => (activeSessionId ? (s.streamOrder[activeSessionId]?.length ?? 0) : 0))
  const hasStream = streamBlockCount > 0
  // 按 imagePasteIds 批量拉图显示缩略图（后端 POST /attachments/image/batch · 本地缓存优先，
  //   乐观追加 imageData 已本地有 base64 无需再拉；miss 的 batch 拉取后写缓存供 MessageList 渲染）
  //
  // [2026-11-01 修复「截图发送后不显示、F5 才出现」] 原实现只发**一次**请求，miss 的 id 拿不到就
  //   永久不重试。而 `image_paste_ids` 的图要等后端 run 起来、`registerRunPromptImages` 分配 id 并
  //   `storeWithId` 写进 image-cache **之后**才可读（本机实测：建行 10:31:03.896 → 回写 id 10:31:04.766）。
  //   排队（busy）路径更极端：queue.drained 时点就构造气泡（已带 id），但落盘要等 drain 后的 run
  //   —— 此刻批量拉图必然 miss，缓存留空且再无触发点 ⇒ 只能 F5（F5 时图已在盘上）。
  //   现改为**有界重试**：拿到部分就写缓存，仍有缺口则按退避再拉，直到补齐或次数用完。
  //   ⛔ 不用轮询：次数有上限，缺图是短暂的「还没落盘」，不是永久缺失。
  /** 缺图重试的退避间隔（ms）。见上方 WHY：图要等后端 run 落盘才可读，单次请求必然 miss。 */
  const IMAGE_FETCH_RETRY_DELAYS = [400, 1200, 3000] as const
  /** 重试用尽的 id（永久失效：image-cache 200 上限逐出 / 删图 / 跨机器）。不记下来 ⇒ 每次
   *  storeMessages 变化（每轮 complete）都会对它重跑 4 次请求，形成无意义请求风暴。 */
  const imageFetchGaveUpRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    if (!activeSessionId) return
    const sid = activeSessionId
    const cached = useChatStore.getState().imageCache[sid] ?? {}
    const gaveUp = imageFetchGaveUpRef.current
    const miss: string[] = []
    for (const m of storeMessages) {
      for (const id of m.imagePasteIds ?? []) {
        if (id && !cached[id] && !gaveUp.has(id) && !miss.includes(id)) miss.push(id)
      }
    }
    if (miss.length === 0) return
    let cancelled = false
    let timer: number | undefined
    const stillMissing = () => {
      const now = useChatStore.getState().imageCache[sid] ?? {}
      return miss.filter((id) => !now[id])
    }
    const attempt = (round: number) => {
      const ids = stillMissing()
      if (ids.length === 0) return
      chatApi.fetchImagesBatch(sid, ids)
        .then((imgs) => {
          if (cancelled) return
          if (imgs && Object.keys(imgs).length) useChatStore.getState().setImageCache(sid, imgs)
          // 后端图尚未落盘（见上方 WHY）⇒ 按退避重试；取到全部或次数用完即止
          const left = stillMissing()
          if (left.length > 0) {
            if (round < IMAGE_FETCH_RETRY_DELAYS.length) {
              timer = window.setTimeout(() => attempt(round + 1), IMAGE_FETCH_RETRY_DELAYS[round])
            } else {
              // 次数用尽：这些 id 在本会话内不再尝试（避免每轮重拉的请求风暴）。
              // 图真被逐出/删除时前端无图可显 —— 这是既成事实，不是本修复能变出来的。
              for (const id of left) gaveUp.add(id)
              console.warn(`[image-fetch] ${left.length} 个图片 id 多次重试仍未取到，本次会话内不再重试:`, left)
            }
          }
        })
        .catch(() => { /* 后端 batch 端点未就绪时静默（重拉后无图，同现状） */ })
    }
    attempt(0)
    return () => { cancelled = true; if (timer) window.clearTimeout(timer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId, storeMessages])
  // turn 运行中判定：activeStreams 含本会话 = turn 已登记（含 thinking 阶段，此时 streams 空）。
  //   OR streams 有流式块 = 打字机在推（后端主动推流/cron 续跑未登记 activeStreams 时仍要可停）。
  //   OR 服务端权威运行态 = 后台 drain（排队命令 / cron / 任务通知）起的 run —— 它不经「本页发送」、
  //     思考或等待权限阶段也没有 chunk ⇒ 前两路全假、UI 连停止键都不出现；F5 还会清空本地簿记。
  //   三信号互补与 WHY 见 utils/turnRunning.ts。
  const serverRunning = useChatStore((s) => (activeSessionId ? !!s.serverRunning[activeSessionId] : false))
  const turnRunning = computeTurnRunning({
    streamRegistered: !!activeStreams[activeSessionId],
    hasStream,
    serverRunning,
  })
  /**
   * [2026-11-02] turnRunning 的 ref 镜像 —— 供**异步回调**读当前值。
   *
   * <p>WHY：`handleArtifactPush` 是在 STOMP 订阅闭包里被调用的（建立于连接时），
   * 若直接闭包捕获 `turnRunning` 会拿到**建立那一刻的旧值**（恒 false）⇒ 门控失效。
   * 用 ref 读「此刻」的运行态。
   */
  const turnRunningRef = useRef(turnRunning)
  turnRunningRef.current = turnRunning
  // 压缩进行中（compact-progress 事件 · 不经 LlmAgentLoop → turnRunning 假，需并入发送键⇄停止）
  // [多会话隔离 2026-09-11] 只看【当前活动会话】的进度：原实现读全局单对象 → A 压缩中切到 B，
  //   B 的发送键变停止（且点击取消的是 B）。此处与 CompactProgressBar 用同一键 → 键出现即横幅出现、
  //   即发送键变停止，取消目标天然一致（见 stopStreaming 的取消会话取值）。
  const activeCompact = useChatStore(selectCompact(activeSessionId))
  const compactActive = activeCompact.visible && activeCompact.status === 'running'

  // ---- [产物/来源] 后端轮末异步抽取结果（GET /sessions/{id}/artifacts · 只读）----
  //   抽取在【轮末异步】跑 ⇒ 数据会晚几秒出现；只拉当前会话，切会话即重置。
  const [sessionArtifacts, setSessionArtifacts] = useState<SessionArtifacts>(EMPTY_ARTIFACTS)
  const artifactsSessionRef = useRef<string | null>(null)
  artifactsSessionRef.current = activeSessionId
  /**
   * 拉取本会话产物（失败静默保持旧值：抽取未就绪 / 后端老版本都不该弹提示）。
   *
   * @returns 成功时返回**本品类（时间倒序）**，供推送回调直接挑最新一条自动打开；
   *          失败 / 已切走会话 → null（调用方据此不再弹面板）。
   */
  const reloadArtifacts = useCallback(async (sessionId: string): Promise<SessionArtifactDto[] | null> => {
    try {
      const r = await artifactsApi.list(sessionId)
      if (artifactsSessionRef.current !== sessionId) return null // 已切到别的会话 → 丢弃迟到响应
      // [脏数据兜底 2026-11-10 用户裁定] 去重规则见 dedupeByFile 的 WHY
      //   （后端 V276~V278 每轮全量重录 ⇒ 同一文件跨轮多行；存量行不会自动消失）。
      const deliverables = dedupeByFile(Array.isArray(r?.deliverables) ? r.deliverables : [])
      setSessionArtifacts({
        deliverables,
        sources: dedupeByFile(Array.isArray(r?.sources) ? r.sources : []),
      })
      return deliverables
    } catch { return null /* 静默：保持旧值 */ }
  }, [])
  // a) 切会话 → 重置并立刻拉一次
  useEffect(() => {
    setSessionArtifacts(EMPTY_ARTIFACTS)
    if (!activeSessionId) return
    void reloadArtifacts(activeSessionId)
  }, [activeSessionId, reloadArtifacts])

  /**
   * [产物按对话分组 2026-11-10] 轮次键 → 用户提问原文 / 时间。
   *
   * <p><b>WHY 在 App 算而不是在 RightPanel 算</b>：RightPanel 只有 artifacts，拿不到消息表；
   * 而「这一轮问了什么」是分组卡头的唯一可读标识（用户原话：「不然没法看是哪个对话产出的」）。
   *
   * <p><b>⛔ 只收真人提问</b>（复用 {@link isUserTurnRow} 单点真源）：系统注入行
   * （`<system-reminder>` / `<task-notification>` / 「A session-scoped Stop hook…」）
   * 在 DB 里也是 role=user，把它们当提问显示会变成「某轮的问题是一串 hook 日志」。
   * 这类轮次不产生条目 ⇒ 其产物自然落到「历史产物」组。
   */
  const turnContext = useMemo(() => buildTurnContext(storeMessages), [storeMessages])

  /**
   * [产物分组「当前轮」高亮] 正在进行的那一轮的用户消息 id。
   *
   * <p>取「最后一条真人提问」而不是跟踪某个独立状态：产物卡组的键就是那条消息的 id，
   * 最后一条提问 = 当前对话正在处理的那一轮。非运行态返回 null（没有「当前」可言，
   * ⛔ 别把最后一轮一直标成「当前」—— 那会让用户以为它还在跑）。
   */
  const currentTurnId = useMemo(() => {
    if (!turnRunning) return null
    for (let i = storeMessages.length - 1; i >= 0; i--) {
      const m = storeMessages[i]
      if (isUserTurnRow(m)) return m.id
    }
    return null
  }, [turnRunning, storeMessages])

  /**
   * [goal V253] 拉取本会话目标（/goal）· 切会话 / F5 时的**初始状态来源**。
   *
   * <p>WHY 需要 REST 兜底：STOMP 推的是会话级 topic（`/topic/sessions/{sid}/goal`），
   * 但 STOMP 只在「订阅已建立且事件到达」时才有值 ⇒ 切会话 / F5 / 后端重启后，
   * 目标头拿不到任何初始状态（对比 compact / tokenWarning / artifacts 都有 REST 兜底）。
   *
   * <p>失败静默（保持无目标显示态）：后端老版本没有该端点时不该弹提示。
   */
  const goalSessionRef = useRef<string | null>(null)
  goalSessionRef.current = activeSessionId
  const reloadGoal = useCallback(async (sessionId: string) => {
    try {
      const r = await goalApi.get(sessionId)
      if (goalSessionRef.current !== sessionId) return // 已切到别的会话 → 丢弃迟到响应
      const g = r?.goal ?? null
      setGoal(sessionId, g ? parseGoalPayload(g) : null)
    } catch { /* 静默：保持旧值 */ }
  }, [])
  // 切会话 → 立刻拉一次目标（清掉上一会话的目标显示态）
  useEffect(() => {
    if (!activeSessionId) return
    void reloadGoal(activeSessionId)
  }, [activeSessionId, reloadGoal])

  // [F1 2026-11-04] 目标**定时轮询** · 只在有 active/paused 目标时跑（无目标不跑，省 CPU）。
  //
  // <p>WHY 需要：STOMP 推的是会话级 topic，推送丢了（订阅建晚了 / 断线重连窗口）⇒ 前端
  // 会永久停在旧态直到 F5。这是与「后端推送」互为兜底的第二路（F4 双通道）。
  //
  // <p>轮询间隔取 10 秒：目标状态变化是「每轮一次」的低频事件，10 秒足够快；
  // 只在有目标时跑 ⇒ 绝大多数会话（没设目标）零额外请求。
  const currentGoal = useChatStore((s) => (activeSessionId ? s.goal[activeSessionId] : null))
  const goalNeedsPolling = !!currentGoal && (currentGoal.phase === 'active' || currentGoal.phase === 'paused')
  useEffect(() => {
    if (!activeSessionId || !goalNeedsPolling) return
    const timer = window.setInterval(() => { void reloadGoal(activeSessionId) }, 10_000)
    return () => window.clearInterval(timer)
  }, [activeSessionId, goalNeedsPolling, reloadGoal])
  // b) 每 15 秒轮询（只在该会话有内容时；切会话/会话空 → 清理定时器）
  const hasSessionContent = !!activeSessionId && storeMessages.length > 0
  useEffect(() => {
    if (!activeSessionId || !hasSessionContent) return
    const timer = window.setInterval(() => { void reloadArtifacts(activeSessionId) }, 15000)
    return () => window.clearInterval(timer)
  }, [activeSessionId, hasSessionContent, reloadArtifacts])
  // d) [产物侧栏 2026-11-02] 有新产物落地 → 自动打开侧栏并聚焦到它。
  //
  //    规则（[2026-11-02 用户二次裁定，收紧]）：
  //      · **只有后端推送**（/topic/sessions/{sid}/artifact）才自动打开；
  //      · 切会话 / 首屏拉取 / 15 秒轮询发现新产物 → **都不打开**（用户明确要求
  //        「切换会话不要默认展开最新的产物」）；
  //      · 用户**手动收起过** → 连推送也不弹回（否则侧栏关不掉）。
  //
  //    ⚠️ 为什么不能靠「列表里出现了没见过的 id」来判断：切会话时列表整个换一遍，
  //      那条判据必然把该会话的历史产物全都当成「新出现的」⇒ 一进会话就弹出最新产物。
  //      故必须由**推送这条事件本身**触发。
  /** 标记「当前这次 setDockArtifact 是推送触发的」，本轮结束后复位（供调试/后续扩展）。 */
  const dockAutoOpenRef = useRef(false)

  // 切会话 → 关掉产物侧栏 + 复位「用户已收起」标记（每会话独立）
  useEffect(() => {
    setDockArtifact(null)
    dockUserClosedRef.current = false
  }, [activeSessionId])

  // c) 轮次结束（serverRunning true → false）→ 延迟 3 秒再拉（给后端轮末抽取留时间）
  const prevServerRunningRef = useRef(serverRunning)
  useEffect(() => {
    const prev = prevServerRunningRef.current
    prevServerRunningRef.current = serverRunning
    if (!prev || serverRunning || !activeSessionId) return
    const timer = window.setTimeout(() => { void reloadArtifacts(activeSessionId) }, 3000)
    return () => window.clearTimeout(timer)
  }, [serverRunning, activeSessionId, reloadArtifacts])
  // ---- [历史轮用量] 后端按轮聚合的真实用量（GET /sessions/{id}/usage/turns · 只读）----
  //   旧轮消息在 DB 里没有 token（真实用量在 llm_usage_calls）→ MessageList 的轮底部用量行据此补缺。
  //   ⚠️ 老后端把「路径不存在」映射成 500（不是 404）⇒ 任何非 2xx 都静默，不按状态码区分。
  const [turnUsageByUser, setTurnUsageByUser] = useState<Map<string, TurnUsageDto>>(new Map())
  const turnUsageSessionRef = useRef<string | null>(null)
  turnUsageSessionRef.current = activeSessionId
  const reloadTurnUsage = useCallback((sid: string) => {
    usageTurnsApi.list(sid)
      .then((r) => {
        if (turnUsageSessionRef.current !== sid) return // 已切到别的会话 → 丢弃迟到响应
        const map = new Map<string, TurnUsageDto>()
        for (const t of r?.turns ?? []) {
          if (t?.userMessageId) map.set(t.userMessageId, t)
        }
        setTurnUsageByUser(map)
      })
      .catch(() => { /* 静默：接口不可用 → 保持空 Map（轮底部保持原文案） */ })
  }, [])
  useEffect(() => {
    setTurnUsageByUser(new Map())
    if (activeSessionId) reloadTurnUsage(activeSessionId)
  }, [activeSessionId, reloadTurnUsage])
  // [2026-10-29] 轮末重拉：后端把该轮用量写进 llm_usage_calls，且 /usage/turns 有 10s 缓存
  //   ⇒ 只靠「切会话拉一次」时，轮次刚结束就 F5 会拿到「不含这一轮」的旧结果且永不刷新
  //   （现象：刷新后 token 消失）。与 artifacts 同策略：收口后延迟 3s 再拉一次。
  useEffect(() => {
    if (!activeSessionId || serverRunning) return
    const t = window.setTimeout(() => reloadTurnUsage(activeSessionId), 3000)
    return () => window.clearTimeout(t)
  }, [serverRunning, activeSessionId, reloadTurnUsage])
  // 主区「新建任务态」= 无消息且无流式（与 Composer 的 empty 同一口径）。
  const workspaceEmpty = storeMessages.length === 0 && !hasStream
  // 主区标题：未选中会话 / 空会话 = null（不显示）；有消息的会话用会话标题，空标题回落「新会话」。
  const workspaceTitle = activeSessionId && !workspaceEmpty
    ? (activeSession.title?.trim() || '新会话')
    : null
  // 主区停在定时任务页（对话面仍挂载，由 CSS 按 .workspace-surface[data-page="schedules"] 隐藏
  //   —— 输入草稿与附件不因换页丢失）。
  const showSchedulesPage = workspacePage === 'schedules'
  // 运行中会话集合（供左侧栏 `.status-dot.running` 闪烁点）。
  // [流式性能] 从 streamOrder 读键集：content 推进不换引用 → 不会每 chunk 重渲；块增删才更新。
  // 用 useMemo 稳定引用避免 selector 每次返回新 Set 触发无限重渲染。
  //
  // ⚠️ [2026-11-04 用户实测「点进去明明在运行、停止键都出来了，左边还是没有运行点」]
  //   **只认 streamOrder 不够**：块只在收到首个 chunk 后才建。而
  //   · 模型思考阶段（已起轮、还没吐 chunk）⇒ streams 为空；
  //   · 等待权限 / 重试退避 ⇒ 同样无块。
  //   这两段「停止键亮着、侧栏却是死的」，判据必须与主对话区的 `computeTurnRunning`
  //   同源三路（本地登记 ∪ 有流式块 ∪ 服务端运行态），否则两处对「在跑」给出相反答案。
  //   ⛔ 不要再退回只看 streamOrder（那正是用户报的缺陷）。
  const streamOrderMap = useChatStore((s) => s.streamOrder)
  const serverRunningMap = useChatStore((s) => s.serverRunning)
  // [2026-11-12] 子代理仍在跑 ⇒ 侧栏保持运行点（主代理完成后不得翻绿）。
  //   ⛔ 只并进侧栏这一路；serverRunning / turnRunning（停止键 / Esc）不碰。
  const subagentRunningIds = useRunningSubagentSessionIds()
  useSubagentRunningReconcile()
  const runningSessionIds = useMemo(() => {
    const out = new Set<string>(Object.keys(streamOrderMap))
    // 本地登记：本页发送后立刻写入（`await chatApi.send` 之前），含「还没收到第一个 chunk」的窗口
    for (const sid of Object.keys(activeStreams)) out.add(sid)
    // 服务端权威：后端起轮即推 status=thinking（ChatService:956）→ useChatSocket 写本表；
    //   也覆盖「后台 drain / cron / F5 后本页无登记」的 run。
    for (const [sid, on] of Object.entries(serverRunningMap)) if (on) out.add(sid)
    // 子代理运行态（独立于主 turn）：主代理 complete 后子代理可能仍在跑。
    for (const sid of subagentRunningIds) out.add(sid)
    return out
  }, [streamOrderMap, serverRunningMap, activeStreams, subagentRunningIds])

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
  // [retry-per-session] 只取**当前会话**那一份：原先是全局单值 ⇒ A 会话重试显示在 B 会话上
  //   （用户实测「会话1 没回复、会话2 显示出来了」），且任一会话 complete 会清掉别人的横幅。
  const retry = useChatStore((s) => (activeSessionId ? (s.retry[activeSessionId] ?? null) : null))
  const setRetry = useChatStore((s) => s.setRetry)

  // ---- 合并「对话操作」：双击 Esc 触发弹窗（压缩 tab；消息非空且非 loading）----
  const setMessages = useChatStore((s) => s.setMessages)
  // [turn-paging] 分页单位 = **轮**（一轮 = 一条真正的用户提问行 + 其后的全部助手/工具内容，整轮不切断）。
  //   条数分页（150 条/页）在「一轮几百条 tool 消息」的数据形态下会把整页填成同一轮 ⇒ 首屏连一轮都显示不全。
  /** [turn-paging] 尾页载入（查看主通道）：打开/刷新/断连补偿走 GET /messages/turns 最近 3 轮，不再全量拉。
   *  消息总数（轨迹徽标）由 /messages/count 的 5s 轮询单独维护，本端点不返回 total。 */
  const loadTailWindow = useCallback(async (sid: string) => {
    // [折叠 = 真删 · 2026-11-02] 首屏/切会话/F5 只拉 FOLD_DEFAULT_TURNS 轮（与内存裁剪同源）
    const { messages, hasMore } = await chatApi.listTurns(sid, { turns: FOLD_DEFAULT_TURNS })
    setMessages(sid, messages)
    useChatStore.getState().setHasMore(sid, hasMore)
  }, [setMessages])
  /** 尾部对账的调用序号（按 sid）：响应回来时若序号已变（期间又发了一次）→ 丢弃旧响应，
   *  否则「先发的慢请求后返回」会把新结果覆盖成旧窗口。 */
  const reconcileSeqRef = useRef<Record<string, number>>({})
  /**
   * [尾部对账] 切会话 / 断连重连 / idle 兜底 三处共用：拉一次尾窗，**只比尾部消息 id**，
   * 变了才整表替换（无变化时完全不写 store ⇒ foldedTurnCounts 不归零、用户展开过的更早轮保留）。
   *
   * <p>本地尾部可能是**服务端不可达**的合成行（`away-*` 离开摘要 / `perm-*` 权限超时留痕，
   * 只存在于前端）⇒ 比对前跳过它们，取最后一条服务端可达的行。
   */
  const reconcileTail = useCallback(async (sid: string): Promise<void> => {
    const token = (reconcileSeqRef.current[sid] ?? 0) + 1
    reconcileSeqRef.current[sid] = token
    try {
      const { messages, hasMore } = await chatApi.listTurns(sid, { turns: FOLD_DEFAULT_TURNS })
      const localMsgs = useChatStore.getState().messages[sid] ?? []
      // 过期守卫：期间又发起了更新的对账 → 本次结果作废
      if (reconcileSeqRef.current[sid] !== token) return
      let localTailId: string | null = null
      for (let i = localMsgs.length - 1; i >= 0; i--) {
        const id = localMsgs[i]?.id ?? null
        if (!id || id.startsWith('away-') || id.startsWith('perm-')) continue
        localTailId = id
        break
      }
      const serverTailId = messages.length > 0 ? messages[messages.length - 1]?.id ?? null : null
      // 尾部一致（且服务器非空、本地尾部 id 非空）→ 内容没变，不写 store
      if (messages.length > 0 && localTailId != null && localTailId === serverTailId) return
      setMessages(sid, messages)
      useChatStore.getState().setHasMore(sid, hasMore)
    } catch (e) {
      // 静默：后台行为，一次失败不该打断用户（下次切会话 / 重连 / idle 仍可兜底）
      console.debug('[reconcile-tail] 尾部对账失败（静默）', sid, e)
    }
  }, [setMessages])
  /** [turn-paging] 向上翻页「加载更多」：以窗口内**最早一轮的用户提问 id** 为 beforeTurnId 再拉 2 轮 prepend
   *  （整轮返回 + 幂等去重）。⚠️ 不能用窗口首条消息 id：它可能是 boundary / system / 半截轮的残留行，
   *  后端对「不是用户提问行」的 beforeTurnId 直接 400（不静默返回空页）。 */
  const handleLoadOlder = useCallback(async (sid: string) => {
    const st = useChatStore.getState()
    const firstTurn = (st.messages[sid] ?? []).find((m) => isUserTurnRow(m))
    if (!firstTurn?.id) return
    try {
      const { messages, hasMore } = await chatApi.listTurns(sid, { turns: 2, beforeTurnId: firstTurn.id })
      st.prependMessages(sid, messages, hasMore)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [showToast])
  /**
   * [折叠 = 真删 · 2026-11-02] 点「⋯ 已折叠 N 轮对话」→ 向后端**重拉**更早的轮（不是从 store 取）。
   *
   * <p>用户裁定：「会话收起来代表内存删除，展开就是重新获取后端的会话，不是取 store 的；F5 也应该
   * 是这样」。store 里那些轮已经不在了（`pruneFoldedTurns` 真删），所以这里必须走网络 ——
   * 与「加载更多」同一个端点（`listTurns` 按 beforeTurnId 向前翻），只是入口不同。
   *
   * <p>失败不静默：提示 + 保持折叠行可见（用户可再点一次）。
   */
  const handleExpandOlder = useCallback(async (sid: string) => {
    const st = useChatStore.getState()
    const msgs = st.messages[sid] ?? []
    // 窗口内**最早一轮的用户提问 id** —— 后端对非提问行 beforeTurnId 直接 400，不能传窗口首条消息 id。
    const firstAsk = msgs.find((m) => isUserTurnRow(m))
    if (!firstAsk?.id) return
    try {
      const { messages, hasMore } = await chatApi.listTurns(sid, { turns: FOLD_DEFAULT_TURNS, beforeTurnId: firstAsk.id })
      if (messages.length === 0) {
        useChatStore.getState().setHasMore(sid, hasMore)
        return
      }
      useChatStore.getState().revealOlderTurns(sid, messages)
      useChatStore.getState().setHasMore(sid, hasMore)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [showToast])

  // [window-paging] Trace 全程：会话全量消息（独立于聊天有界窗口 storeMessages · 用户拍板「Trace 就是全程」）。
  const [traceMessages, setTraceMessages] = useState<Awaited<ReturnType<typeof chatApi.listMessages>>>([])
  // [Trace 释放·内建过期守卫] 上一版把「取消守卫」做成可选入参交调用方 opt-in（isCancelled?）——那只是
  //   一份调用契约：轨迹 tab 的 onClick 当时未传守卫，请求 resolve 后照样 setTraceMessages(全量)，把
  //   「离场置空」的副本重新灌回并驻留（列表越大请求越慢越易命中），并使「轨迹内 A→B 快速切会话响应乱序」
  //   复活。改为内建守卫：响应 resolve 时按【当前是否仍在该会话的 Trace 视图】自行判定丢弃，任何调用点
  //   （含未来新增）都无法绕过。用 ref 读实时值（loadTraceFull 是 useCallback([])，闭包拿不到最新 state）；
  //   沿用本文件既有 activeSessionIdRef 的「渲染期同步 ref」写法。
  const traceViewStateRef = useRef<{ view: 'chat' | 'trace'; sid: string | null }>({ view: centerView, sid: activeSessionId })
  traceViewStateRef.current = { view: centerView, sid: activeSessionId }
  const loadTraceFull = useCallback(async (sid: string) => {
    try {
      const msgs = await chatApi.listMessages(sid) // 全量：Trace = 全程记录（不受窗口限制）
      // [P2-19 F5 角标缺口] 全量列表是【唯一】必然含全部 snip_boundary 的通道：
      //   · 轨迹「已裁剪」pill 的缺口是确定的 —— traceMessages 拿到全量（boundary + 被裁消息都在），
      //     但 snippedIds 只由 STOMP 实时 / setMessages 解析填充，F5（冷启动）后为空 → pill 不渲染
      //     （P2-19 取证②）。本行用同一份全量补齐 snippedIds。
      //   · 聊天区角标：被裁消息落在当前窗口时才需要角标，而尾页窗口（loadTailWindow 只拉最近 3 轮）
      //     可能不含 boundary → setMessages 的解析为空（补上后不再依赖「boundary 是否落在加载页」）。
      //   位置在过期守卫【之前】：snippedIds 是会话级既成事实，与「此刻是否仍停在轨迹 tab」无关；
      //   过期守卫只约束 traceMessages 这个 UI 副本（快速切走不得回灌）。
      const removed = collectRemovedUuids(msgs)
      if (removed.length > 0) useChatStore.getState().markSnipped(sid, removed)
      const cur = traceViewStateRef.current
      // 已离场（切走 tab）或已切会话 → 丢弃响应，绝不写回 state。
      if (cur.view !== 'trace' || cur.sid !== sid) return
      setTraceMessages(msgs)
    } catch { /* trace 全量拉失败静默（重进 trace / 手动再点兜底） */ }
  }, [])
  // 保持在 Trace 视图时切会话 → 重新拉全程
  useEffect(() => {
    if (centerView !== 'trace' || !activeSessionId) {
      // [内存·Trace 离场即释放] traceMessages 是会话【全量】副本（listMessages 无 limit，且 TraceView
      //   每条带 full=完整正文/toolCall.arguments），是全仓第二份全量数据。原实现只在进入/切会话时拉取、
      //   从不置空 → 离开轨迹后整份原样驻留，切会话/删会话也不释放。这里补 else：非 Trace 视图（含
      //   activeSessionId 变化触发的切会话、删当前会话后 activeSessionId 变空）→ 清空副本。
      //   语义不变：Trace 仍是全程（进入轨迹时按 activeSessionId 重新全量拉取）。
      //   函数式更新只在非空时换引用，避免每次 activeSessionId 变化都制造新 [] 触发无谓重渲。
      setTraceMessages((prev) => (prev.length === 0 ? prev : []))
      return
    }
    void loadTraceFull(activeSessionId)
    // 过期守卫已内建在 loadTraceFull（读 traceViewStateRef 实时判定），此处无需再持有 cancelled 闭包。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [centerView, activeSessionId, loadTraceFull])
  const removeMessage = useChatStore((s) => s.removeMessage)
  const clearStream = useChatStore((s) => s.clearStream)
  const setConversationId = useChatStore((s) => s.setConversationId)
  // 会话当前 conversationId（partial 压缩/裁剪后旋转）：作消息 row key 前缀，整列表 remount
  const conversationId = useChatStore((s) => s.conversationIds[activeSessionId])
  /** [折叠 = 真删] 该会话已移出内存的轮数（渲染层「已折叠 N 轮」文案 + 展开入口） */
  const foldedTurnCount = useChatStore((s) => (activeSessionId ? (s.foldedTurnCounts[activeSessionId] ?? 0) : 0))
  // F8 门控：消息非空 && 输入框为空（composerText 无有效内容）&& 非 loading
  const canOpenDialogOps = storeMessages.length > 0 && !turnRunning && !composerText.trim()
  // 停止后静默重拉当前会话（显示已停止 → 立即反映停止后真实状态 · 等同「轨迹点击」刷新：
  //   无 toast、不闪、保留窗口与 WS 连接）
  const refreshAfterStop = useCallback(async () => {
    if (!activeSessionId) return
    try {
      await loadTailWindow(activeSessionId)
    } catch { /* 停止后刷新失败静默（后续事件/手动 F5 兜底） */ }
  }, [activeSessionId, loadTailWindow])
  // ---- 停止当前流式（Esc 一次 · turn 运行中）----
  const stopStreaming = useCallback(async () => {
    if (!activeSessionId) return
    // [多会话隔离 2026-09-11] 停止键与进度横幅共用【同一个会话键】：
    //   compactActive = compact[activeSessionId]（见上），横幅 CompactProgressBar 亦只渲染该键
    //   → 点停止时正在压缩的会话 ≡ 当前活动会话，取消目标不存在歧义（原实现：全局横幅 + 取消
    //   activeSessionId，A 压缩中切到 B 时「显示 A 的横幅、取消 B」）。
    //   取消通道无需新增后端：POST /sessions/{sid}/cancel → ChatService.cancelSession →
    //   CompactProgressState.abortForSession(sid) 会 abort 该会话在飞压缩（摘要 provider 硬断流）。
    // 压缩进行中停止：立即收起该会话进度横幅（后端 cancel abort 摘要；随后的 compact_end 不再显示完成态）
    const cur = useChatStore.getState().compact[activeSessionId]
    if (cur?.visible) {
      useChatStore.getState().setCompact(activeSessionId, { ...cur, visible: false, status: 'canceled' })
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
      const stopAllRes = await tasksApi.stopAllTasks(activeSessionId)
      // 2) 中止该会话 pending 权限（worker 解除等待）
      const pending = useChatStore.getState().permissionQueue.filter((r) => r.sessionId === activeSessionId)
      for (const req of pending) {
        if (clientRef.current) sendPermissionResponse(clientRef.current, activeSessionId, req.kind, req.requestId, 'deny', {})
        useChatStore.getState().dequeuePermission(req.requestId)
      }
      // 3) 清当前流式
      clearStream(activeSessionId)
      // [stop-all 假成功修正] 后端 success = (failed == 0)（TaskController.stopAll）——
      //   有任务真没停掉时如实告知，⛔ 不再恒报「已停止所有任务」（用户裁定本批一起修）。
      //   注意：前端 mock/旧后端的响应可能无 failed 字段 ⇒ 用 `?? 0` 兜底，undefined 走成功分支。
      if (stopAllRes?.success === false) {
        const failedCount = stopAllRes.failed ?? 0
        showToast(failedCount > 0 ? `部分任务未能停止（${failedCount} 个）` : '部分任务未能停止', 'info')
      } else {
        showToast('已停止所有任务', 'success')
      }
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

  // [dialog-ops-pivot] 候选请求序号：仅最新一轮请求可写 state / 清 loading（覆盖「关了再开」旧响应后到、过期请求提前清 loading 两个方向）
  const dialogOpsReqSeq = useRef(0)
  /** [dialog-ops-pivot] 拉取候选（打开弹窗时调用；过期守卫 = 会话未切换且非过期请求：请求序号保证仅最新一轮可落
   *  state / 清 loading，对齐 loadTraceFull 先例）。注：activeSessionIdRef 是渲染期同步 ref（声明在文件后部），
   *  此处仅事件期读取，⛔ 不得挪入 useMemo / 渲染期求值（会 ReferenceError）。 */
  const loadDialogOpsCandidates = useCallback(async (sid: string) => {
    const seq = ++dialogOpsReqSeq.current
    setDialogOpsCandidates([])
    setDialogOpsLoading(true)
    setDialogOpsError(null)
    try {
      const list = await chatApi.listPivotCandidates(sid)
      if (seq !== dialogOpsReqSeq.current || activeSessionIdRef.current !== sid) return
      setDialogOpsCandidates(list)
    } catch (e) {
      if (seq !== dialogOpsReqSeq.current || activeSessionIdRef.current !== sid) return
      setDialogOpsError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      if (seq === dialogOpsReqSeq.current) setDialogOpsLoading(false)
    }
  }, [])

  /** 历史提问：拉当前会话**全部** user 消息（不经过 compact 边界过滤，保证「应该很多」）。 */
  const loadHistoryQuestions = useCallback(async (sid: string) => {
    const seq = ++historyReqSeq.current
    setHistoryQuestions([])
    setHistoryLoading(true)
    setHistoryError(null)
    try {
      const msgs = await chatApi.listMessages(sid)
      if (seq !== historyReqSeq.current || activeSessionIdRef.current !== sid) return
      const qs = msgs
        .filter((m) => m.role === 'user' && !m.isMeta)
        .map((m) => ({
          id: m.id,
          createdAt: m.time ?? '',
          previewSource: m.content ?? '',
          removedAfter: 0,
        }))
        .reverse()
      setHistoryQuestions(qs)
    } catch (e) {
      if (seq !== historyReqSeq.current || activeSessionIdRef.current !== sid) return
      setHistoryError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      if (seq === historyReqSeq.current) setHistoryLoading(false)
    }
  }, [])

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
      // 弹层打开时 Esc 只交给弹层自身关闭（基础设置 / 历史提问各自有 useEscapeKey）
      if (showBasicSettings || showHistoryPanel) return
      // 主区停在非对话页（定时任务 / 知识库）：Esc 先回对话页（不卡在那一页），
      //   不再触发停流 / 连按判定。
      // ⚠️ 每新增一个 workspacePage 取值都必须加进来 —— 漏一个就会出现「Esc 按了没反应、
      //   误触发停流」；本行曾只判 showSchedulesPage，知识库页因此漏掉（2026-11-04 修）。
      if (workspacePage !== 'chat') {
        setWorkspacePage('chat')
        return
      }
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
          const sid = activeSessionIdRef.current
          if (sid) void loadDialogOpsCandidates(sid)
        }
      } else {
        lastIdleEscRef.current = now
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [canOpenDialogOps, showCommandPalette, showBasicSettings, showHistoryPanel, workspacePage, turnRunning, stopStreaming, handleStopAll, loadDialogOpsCandidates])

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

  // ---- 对话裁剪：DELETE 删 pivot 起全部消息 + 旋转 conversationId（响应轻量：{conversationId, deletedCount}）
  //   裁剪后**重拉窗口**（loadTailWindow = GET /messages/turns 按轮分页，毫秒级）——
  //   后端不再回传全量 messages（万级消息 = 几万条 JSON，序列化/传输本身是卡顿的一环）。
  const handleTrimAfter = useCallback(async (messageId: string) => {
    if (!activeSessionId) return
    try {
      const resp = await chatApi.trimAfter(activeSessionId, messageId)
      if (resp.conversationId) setConversationId(activeSessionId, resp.conversationId)
      await loadTailWindow(activeSessionId)
      setShowDialogOps(false)
      showToast('已裁剪，此消息及其之后的对话已删除', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, setConversationId, loadTailWindow, showToast])

  // [window-paging] 重拉当前会话（无 toast · complete 回调 + F5 共用）：查看通道 = 尾页窗口（对齐 deepseek，
  //   不再全量）。Trace 全程由 loadTraceFull 单独全量拉（见上方依赖 centerView 的 effect / 渲染用 traceMessages）。
  const reloadMessages = useCallback(async () => {
    if (!activeSessionId) return
    try {
      await loadTailWindow(activeSessionId)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, loadTailWindow, showToast])

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
  }, [ui, settings.settingsTab, providersApi, mcpApi, databasesApi, schedulesApi, refreshConversation, showToast, showDialogOps, showCommandPalette, showModelPicker, showAgentsPanel, showChromePanel, showContextAnalyze, showMemoryEditor, showIncludeApproval, showMarket])

  // ---- 历史回放：会话切换 —— 有常驻缓存仍校验尾部；仅从未载入的会话才 GET 初始化 ----
  // [chat-switch-stream-align] 对齐 deepseek「Session 数据常驻」：messages/streams 已常驻
  //   chatStore，实时链路（chunk append / message.user / complete finalize / queue.drained）持续增量维护。
  //   旧实现每次切会话都 setMessages(activeSessionId, []) + GET 重拉 → 对仍正流式输出的会话切回瞬间历史
  //   闪没、整列表重排抖动。故缓存命中（历史非空）时不整表重拉；仅 undefined/空缓存（首次切到未载入会话）
  //   才 GET 初始化。
  //   [切会话尾部校验] 缓存非空 ≠ 内容最新：后台任务/子代理起的 run 不登记 activeStreams（本页只在
  //   sendMessage / queue.drained 登记），useChatSocket 的「切回补拉」仅在 activeStreams 命中时触发 ⇒
  //   兜底不生效，后台产出的新消息要 F5 才出现。故缓存命中时仍走一次 reconcileTail（拉尾窗，只比对
  //   **尾部消息 id**，变了才整表替换 —— 无变化时完全不写 store ⇒ foldedTurnCounts 不归零、用户展开过
  //   的更早轮保留）。运行中的会话跳过校验（避免拿到「已落库但缺最后一小段」的窗口把流式尾巴回退）。
  //   校验失败静默：这是后台行为，一次失败不该打断用户。
  const isRealActive = storeSessions.some((s) => s.id === activeSessionId)
  useEffect(() => {
    if (!isRealActive) return
    let cancelled = false
    const cached = useChatStore.getState().messages[activeSessionId]
    if (Array.isArray(cached) && cached.length > 0) {
      // 运行中（本页已登记，或 store 里有流式块）→ 跳过校验
      if (activeStreams[activeSessionId] || (useChatStore.getState().streams?.[activeSessionId]?.length ?? 0) > 0) return
      // 竞态守卫在 reconcileTail 内（按 sid 的调用序号），这里无需 cancelled
      void reconcileTail(activeSessionId)
      return
    }
    // [turn-paging] 无缓存 → 最近 3 轮（有界窗口，不再全量；hasMore 供顶部「加载更多」）
    chatApi.listTurns(activeSessionId, { turns: FOLD_DEFAULT_TURNS })
      .then(({ messages, hasMore }) => {
        if (cancelled) return
        setMessages(activeSessionId, messages)
        useChatStore.getState().setHasMore(activeSessionId, hasMore)
      })
      .catch((e) => { if (!cancelled) showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info') })
    return () => { cancelled = true }
    // 运行中登记的增减不触发本校验（否则每次起停流都会重发 listTurns）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId, isRealActive, setMessages, showToast])

  // [Phase2] 切到真实会话 → GET /files 对账（补订阅前/断连期间的改动文件 · 与 files.changed 事件同源整表替换）
  const realChangedSession = storeSessions.some((s) => s.id === activeSessionId) ? activeSessionId : null
  useEffect(() => {
    if (!realChangedSession) return
    let alive = true
    sessionFilesApi.list(realChangedSession)
      .then((files) => { if (alive) useChatStore.getState().setChangedFiles(realChangedSession, files) })
      .catch(() => { /* 后端未就绪静默（files.changed 事件仍可实时到） */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [realChangedSession])

  // [C6 · 停止键可见性] 载入 / 切会话 → 用服务端权威运行态重建「要不要显示停止键」。
  //   实现（GET + 失败静默 + 非轮询）与 WHY 见 hooks/useServerRunning.ts（抽出去是为了可被渲染级用例
  //   钉住 —— 内联在 App 里时「把 GET 整条改死」任何单测都照绿）。
  useServerRunningRebuild(activeSessionId, isRealActive)

  // [C6 · 遗留2] 服务端权威「收口」（true→false）→ 对账本地 turn 簿记（对账单点见 hooks/useServerRunning.ts：
  //   store 侧定稿残留流式块 + 回调回收本处 activeStreams 登记）。complete/cancel/error 三处事件回调
  //   （handleSessionDone）是**唯一**的登记清除点；其中任一条帧在无断连时静默丢失（未送达 / 处理中途抛错），
  //   登记即恒真 —— 而 turnRunning 是或语义，服务端随后说 idle / GET /running 返回 false 也压不住
  //   ⇒ 停止键永久卡住。本回调**只删登记这一份**（不碰 handleSessionDone 的「完成未读绿点」等副作用）。
  //
  // ⚠️ [C6 · 轮次判据 · 防误回收本轮登记] 收口回调带 sid 但不带轮次 ⇒ 必须自己判「这个边沿关的是哪一轮」：
  //   登记（sendMessage）与边沿是两条独立通道，同一会话上「刚为下一轮登记」与「上一轮的迟到边沿」可重合
  //   （典型：上一轮 idle 帧迟到；或载入/切会话/重连发出、在下一轮起轮后才回来的 GET /running=false）。
  //   盲删会把**新一轮**的登记抹掉 —— 停止键虽仍由 serverRunning / hasStream 撑着（会闪）。
  //   ⛔ 不复用 handleSessionDone 的 topic 校验：streamTopic 是**会话级常量**（后端 streamTopic(sessionId) =
  //   "/topic/sessions/{sid}/stream"），登记值与事件 topic 恒等 ⇒ 那条判据在本仓永不触发（挡不住任何东西）。
  //   真判据 = **轮次号**：每次登记 +1；服务端说 true 时把该会话当时的轮次号记下（= 被服务端确认过的轮）；
  //   只有「登记轮次 ≤ 最近一次 true 的轮次」的登记，才可能是本边沿关掉的那一轮 ⇒ 才可回收。
  //   未见过 true 的登记（本轮起轮帧还没到 / 已丢）永不被这条边沿回收 —— 它本来就没被服务端确认过在跑。
  //   可达性（为什么这条判断不是空转）：store 里存着**过期的 true**（上一轮的收口帧静默丢失 / 迟到的
  //   GET true）时，服务端其实已空闲 ⇒ 用户还能按下发送（服务端不排队、立即受理）⇒ 就是「本轮刚登记、
  //   上一轮的 true 仍在」这个现场；随后上一轮那条迟到的 false 一到就会误回收本轮登记。
  //   ⚠️ 覆盖边界（已知残留，非本判据能兜）：若「上一轮的迟到 false」是在本轮的 true 之后才落地的
  //   （无 STOMP 保序约束的 GET 通道 + 发送恰落在该 GET 的往返窗口内），本地与「本轮自己的收口」逐字
  //   同形 ⇒ 无法区分，仍会误回收；要真区分需服务端在 status 事件里带上轮次 id（当前不带）。
  const streamRegGenRef = useRef<Record<string, number>>({})
  const streamTrueGenRef = useRef<Record<string, number>>({})
  const markStreamRunning = useCallback((sid: string) => {
    streamTrueGenRef.current[sid] = streamRegGenRef.current[sid] ?? 0
  }, [])
  const reclaimStreamRegistration = useCallback((sid: string) => {
    // 登记晚于最近一次 true ⇒ 那个 true 属于上一轮，本登记尚未被服务端确认 ⇒ 不收
    if ((streamRegGenRef.current[sid] ?? 0) > (streamTrueGenRef.current[sid] ?? 0)) return
    setActiveStreams((prev) => {
      if (!prev[sid]) return prev
      const next = { ...prev }
      delete next[sid]
      return next
    })
  }, [])
  useServerRunningReconcile(reclaimStreamRegistration, markStreamRunning)

  // 排队消费：queue.drained 到达（工具边界）→ App 立即 append 用户2 气泡；渲染按 userMessageId 分组
  //   自动排到当前 assistant 工具轮之后（对齐 deepseek-harness/CC 工具边界插入，不延后到 complete）。
  // 会话生命周期明确信号（对齐 Harness 事件驱动确定性）：complete/cancel 到达 → 移除 activeStreams。
  //   不用「streams 从有到无」推断移除——推断与 sendMessage 登记竞争，是「换会话卡住」的根因。
  //   topic 校验（handleSessionDone 里那一行 if (topic && prev[sid] !== topic) return prev）：
  //   ⚠️ [2026-09-19 注释纠错] 原文称它防「turn A 运行中 send B ⇒ A 的终止事件（topicA）误删 B 的登记
  //   （topicB）」—— 该说法在本仓**不成立**（逐处核实：登记值 = resp.streamTopic（:1676 是唯一登记点）
  //   = 后端**会话级常量** /topic/sessions/{sid}/stream（ChatService.streamTopic :2690-2692；ChatController
  //   :137/:150 与 MessageService :1238/:1295 同一字面量），而终止事件也正是推到这个 topic ⇒ topicA ≡ topicB
  //   ⇒ prev[sid] !== topic 恒假 ⇒ 本判据在本仓**永不 early return**，挡不住任何东西（同 :795-796 的结论）。
  //   它成立的前提是旧 per-message topic /topic/sessions/{sid}/messages/{uuid}/stream —— 该形状已废
  //   （ChatService :2682）。故本行是给「改回 per-turn topic」这类将来重设计留的兜底，**不是**当前回归的
  //   守护；真要区分「终止信号属于哪一轮」，需服务端在事件里带轮次 id（当前不带，见上方轮次判据的覆盖边界）。
  //   ⛔ 删/留属行为决策（未动）。
  // ---- 「完成未读」绿点（需求：运行结束且非当前会话 → 静止绿点；切到即清除）----
  const activeSessionIdRef = useRef(activeSessionId)
  activeSessionIdRef.current = activeSessionId
  const [doneUnreadIds, setDoneUnreadIds] = useState<Set<string>>(new Set())
  // 切到某会话 → 视为已读（清除其完成未读绿点）
  useEffect(() => {
    if (!activeSessionId) return
    setDoneUnreadIds((p) => (p.has(activeSessionId) ? (() => { const n = new Set(p); n.delete(activeSessionId); return n })() : p))
  }, [activeSessionId])

  const handleSessionDone = useCallback((sid: string, topic?: string, kind?: TurnEndKind) => {
    // [未读] complete/cancel 运行结束且非当前会话 → 标「完成未读」（running/pending 优先级更高会覆盖显示）
    if (sid !== activeSessionIdRef.current) {
      setDoneUnreadIds((p) => (p.has(sid) ? p : new Set(p).add(sid)))
    }
    /**
     * [2026-11-04 新增] **系统级通知**（Windows 右下角 / macOS 通知中心）。
     *
     * <p><b>什么时候发</b>：只发 `complete` / `error`。`cancel` 是用户自己按的停止，
     * 他本来就在盯着，再弹一条是噪音。
     *
     * <p><b>为什么不再加「窗口在前台就不发」</b>：那版实现把 `visibilityState === 'hidden'`
     * 当门槛，结果用户**正看着界面等结果**时一条都不弹（他实测「已经完成了但右边没通知」）。
     * 用户要的就是「跑完弹一下」，与看不看界面无关 ⇒ 去掉该门槛。
     *
     * <p><b>为什么放这里</b>：`handleSessionDone` 是 complete / cancel / error 三个终态分支的
     * **共同入口**，且覆盖「非当前会话」（后台跑完）。
     *
     * <p>⚠️ 发通知失败一律静默（见 utils/systemNotify.ts）：通知是锦上添花，
     * 绝不能让它的异常打断收口流程。
     */
    if (kind === 'complete' || kind === 'error') {
      const title = useChatStore.getState().sessions.find((s) => s.id === sid)?.title?.trim()
      void sendSystemNotification({
        title: kind === 'error' ? 'NexusAI · 本轮出错了' : 'NexusAI · 本轮已完成',
        body: title || undefined,
      })
    }
    /**
     * ⭐ [2026-11-04 用户实测「会话已经完成，左侧会话栏没有实时展示结束」]
     *
     * <p><b>根因</b>：侧栏状态点优先级是 `pending > running > done`（SessionList），而
     * `serverRunning` 唯一的清零点是 `status=idle` 帧（useChatSocket 的 isStatus 分支）。
     * 那一帧一旦漏收（订阅建立晚于推送 / 断连窗口 / 非本页发起的 run 没有订阅），
     * 该会话就**永久停在 running**：绿点被压住 ⇒ 用户看到「跑完了但侧栏还在跑」。
     *
     * <p><b>为什么在这里清</b>：complete / cancel / error 是本轮**最可靠的终态信号**
     * （这三个分支在 useChatSocket 里都调本回调），它比 idle 帧先到也更不容易丢。
     * 两个信号同源写入 ⇒ 任一到达即可收口，互不依赖。
     *
     * <p>⚠️ 仓内既有口径：`useServerRunningReconcile` 会在 true→false 边沿对账
     * （finalizeBlocks + 回收登记）。此处顺序**天然幂等**：complete 分支已 finalize、
     * error/cancel 分支已 clearStream，且登记在下面几行就要删 ⇒ 对账两件事都是 no-op。
     */
    useChatStore.getState().setServerRunning(sid, false)
    setActiveStreams((prev) => {
      if (!prev[sid]) return prev
      if (topic && prev[sid] !== topic) return prev
      const next = { ...prev }
      delete next[sid]
      return next
    })
    /**
     * [2026-11-03 用户要求「跑完也应该重新拉一下」]
     *
     * <p><b>WHY</b>：执行中本地是「流式块 + 增量拼出的 messages」，与后端权威窗口可能不一致
     * （排队的 user 行、空壳 assistant 段、轮次边界）。收口后用后端权威窗口做一次对齐，
     * 用户就不必靠 F5 才能看到正确顺序与历史折叠。与 `handleQueueDrained` 末尾的
     * 「消费后无感重拉」同一思路，两点合起来覆盖「起轮 / 收口」两端。
     *
     * <p><b>为什么不会打坏刚定稿的内容</b>：`setMessages` 只整表替换 `messages`，不碰 `streams`
     * （此时块已被 `finalizeBlocks` 清空）；轮末用量行来自独立的 `/usage/turns`（
     * `turnUsageFallback`），不随 `messages` 重拉丢失。
     *
     * <p><b>为什么静默</b>：失败不该打断用户；下一次 complete / 切会话 / 手动 F5 仍可兜底。
     */
    void loadTailWindow(sid).catch(() => { /* 静默：后续切会话 / F5 兜底 */ })

    // [F2 2026-11-04] 每轮结束**立刻**拉一次目标状态（不等 10 秒轮询）。
    //
    // <p>WHY：目标的达成判定发生在「这一轮的 Stop hook 段」，而 STOMP 的目标推送与
    // stream complete 是两条独立通道 —— 推送可能晚到、丢包，或被「订阅建立晚于推送」
    // 吃掉。complete 是「本轮真的结束了」最可靠的时间锚点 ⇒ 在这里主动确认一次，
    // 目标达成后绿条能立刻出现（这是用户报的「正文说完成了但 UI 还显示在追」的正面修复）。
    void reloadGoal(sid).catch(() => { /* 静默：F1 轮询兜底 */ })
  }, [loadTailWindow, reloadGoal])

  // 排队命令出站回调（B5）：queue.changed → 刷新排队框；queue.drained → append 正式气泡 + 登记新 streamTopic
  const handleQueueChanged = useCallback((sid: string, commands: { content: string; mode: string; isEditable: boolean; isMeta?: boolean }[]) => {
    // [2026-10-23] sid 必须带上：每个活跃会话都有自己的 queue topic，丢掉 sid 会让
    //   后到达的快照覆盖当前会话的排队框（跨会话串台）。
    commandQueue.setQueued(sid, commands)
  }, [commandQueue])
  const handleQueueDrained = useCallback((sid: string, drained: DrainedQueueItem[]) => {
    if (drained.length === 0) return
    // 工具边界消费 → 【立即 append】用户2 气泡（打字机期可见）；渲染按 userMessageId 分组自动排到
    //   当前 assistant 工具轮之后（用户2 的 group 在 bash 后、AI 回复其前）—— 不延后到 complete。
    //   会话级单 topic 常驻（activeStreams 里 sid 仍在）→ 新轮回答经同一订阅收到，无需重新订阅。
    //   uuid = 排队命令 uuid（DB user 消息 id）→ 气泡 id 用它防重。
    //   [busy 气泡附件胶囊] 气泡附件胶囊由 buildDrainedUserMessages 从**事件里的后端权威快照**搬运
    //   （原实现只用 {uuid, content} ⇒ busy 气泡恒无胶囊、必须 F5 才出现）。⛔ 前端不留快照暂存：
    //   权威源已在事件里，再存一份即平行真源。方案论证见 utils/queuedUserBubble.ts 头注。
    const st = useChatStore.getState()
    const prev = st.messages[sid] ?? []
    const existingIds = new Set(prev.map((m) => m.id))
    const msgs = buildDrainedUserMessages(sid, drained, existingIds)
    if (msgs.length > 0) {
      // [有界窗口] 追加路径 → appendMessages（内部 capTail）：勿用 setMessages([...prev, ...msgs])，
      //   那条路不裁剪，是内存无界的漏洞口。
      st.appendMessages(sid, msgs)
      void debugLog(`[drained-append] sid=${sid} uids=${msgs.map((m) => m.userMessageId ?? m.id).join(',')} contents=${msgs.map((m) => m.content?.slice(0, 10) ?? '').join(',')} attach=${msgs.map((m) => m.userAttachments?.length ?? 0).join(',')}`)
    }
    // [2026-11-03 用户实测「当前轮次补充用户消息、消费后历史锁定时灵时不灵」]
    //   **queue.drained = 新一轮的起点 ⇒ 必须在这里登记 activeStreams。**
    //
    //   <b>原实现的洞</b>：这里只 append 用户气泡（见上方注释「activeStreams 里 sid 仍在 →
    //   无需重新订阅」）—— 该假设只在「旧轮还没收口」时成立。而实际时序常是：
    //     旧轮 complete → handleSessionDone 删掉登记 → queue.drained 才到
    //   ⇒ 新一轮起轮瞬间 `computeTurnRunning` 三路全假（streamRegistered=false /
    //   hasStream=false（新一轮还没吐 chunk）/ serverRunning 取决于 status=thinking 帧是否已到）
    //   ⇒ `turnEnded` 短暂为 true ⇒ 折叠窗口从 RUNNING_FOLD_TURNS(1) 恢复到 FOLD_DEFAULT_TURNS(2)
    //   ⇒ **历史整段露出**；下一个 chunk 到达又锁回去 —— 正是用户说的「时灵时不灵」。
    //
    //   与 sendMessage 同款：轮次号 +1（供 reclaimStreamRegistration 的轮次判据使用）。
    //   ⚠️ 已有登记时不覆盖（旧轮仍在跑时保持它原来的 topic，语义与 sendMessage 一致）。
    streamRegGenRef.current[sid] = (streamRegGenRef.current[sid] ?? 0) + 1
    setActiveStreams((prev) => (
      prev[sid] ? prev : { ...prev, [sid]: `/topic/sessions/${sid}/stream` }
    ))
    /**
     * [2026-11-03 用户裁定「消费后要重拉，等价于 F5」]
     *
     * <p><b>为什么必须重拉</b>：消费瞬间本地 `messages` 是**增量拼出来的**（乐观气泡 + 历史残留），
     * 而 `streams` 里还躺着**上一轮的流式块**。两者轮次边界不一致 ⇒
     *   · `hasStream`（会话级）被旧块顶着恒真 ⇒ `thinking` 恒假 ⇒ 占位不渲染；
     *   · 旧块被 `groups` 的「吸附最新轮」改挂，而 `activeTurnKeys` 仍按块冻结 uid 算旧轮
     *     ⇒ 两处轮次不一致 ⇒ 块的 `showProcessHeader` 也不成立。
     * 两条路全断 ⇒ 新一轮下面**一条「正在执行」都没有**（用户实测的缺陷）。
     *
     * <p><b>为什么是「清 streams + 换 messages」而不是只换 messages</b>：
     * 真 F5 之所以能修，是因为页面重载后 store **整个是空的** —— `messages` 与 `streams`
     * **一起**回到后端权威态。上一版只替换了 `messages`、把 `streams` 留着，
     * 于是两块数据的时间线不一致，这正是它失效的原因。故这里显式补齐 `clearStream`。
     *
     * <p><b>为什么不用 `location.reload()`</b>：重载会让正在生成的内容**永久丢一段**
     * （重连前的 chunk 推给了零订阅者），还会断 WebSocket、白屏、丢输入框与滚动位置。
     * 本处做的是**同一件事的局部版**：只重置这一条会话的两个数据源，连接与界面都不动。
     *
     * <p><b>顺序：先拉成功，再清块</b>（都要成功才生效）——
     *   拉取失败时**什么都不动**（保留现有流式态），不会出现「块被清了、消息又没回来」
     *   这种最坏情况。两个 store 写落在同一个 async 续体里 ⇒ React 合批 ⇒ 一次渲染完成，
     *   不会有「旧块 + 新消息」并存的中间帧。
     *   新一轮的 chunk 到达时会重新建块（`ensureStreamBlock` 惰性建），不受影响。
     *
     * <p><b>代价（如实登记）</b>：正在流式显示、但后端尚未落库的最后一小段会短暂回退，
     * 然后随新 chunk 重新长出来。真 F5 同样如此。
     */
    void (async () => {
      try {
        await loadTailWindow(sid)
        clearStream(sid)
      } catch { /* 静默：拉取失败则保留现状，等 complete / 手动 F5 兜底 */ }
    })()
  }, [clearStream, loadTailWindow])

  // [断连恢复] WS 被服务端强杀（send time limit 超时）断连后,重连第一个 message.complete → 按事件 sid 权威
  //   重拉 GET /messages 补偿缺口（DB 已逐条落库 realtime-persist）。reloadMessages 锁 activeSessionId
  //   （F5/当前会话专用）,后台会话断连需按 sid 拉 → 独立实现。finalizeBlocks 按 id 幂等去重保证不重复。
  const handleReconnectReload = useCallback(async (sid: string) => {
    // 断线重连 / 缺口修复 / idle 兜底三处共用 reconcileTail（尾部 id 一致则不写 store）
    await reconcileTail(sid)
    // [C6 · 停止键可见性] 断连窗口内 run 的起轮 / 收口事件可能推给了零订阅者（STOMP topic 非持久）
    //   → 连上就重查一次服务端运行态，避免「run 早已结束但停止键仍挂着」/「run 在跑却没停止键」。
    //   与载入/切会话共用同一条 GET+回写实现（hooks/useServerRunning.ts）——单点，防两处判据漂移。
    await refreshServerRunning(sid)
  }, [reconcileTail])

  // 多会话并行订阅（订阅所有 activeStreams；complete/cancel 明确回调移除）
  // [sm-boundary-reload] onCompactedReload 注册同一 handleReconnectReload（复用既有重拉实现，不新写一套）：
  //   压缩落库成功（complete.compacted=true）→ finalize 后收尾重拉尾页补对账层（即时层 = message.insert 插行）。
  // [会话状态同步] 实时监听「所有已知会话」的权限/提问（不止当前会话）——否则别的会话发起的
  //   AskUserQuestion 到达时无订阅者、事件被丢弃（侧栏无黄点，切回也不弹）。
  const knownSessionIds = useMemo(() => storeSessions.map((s) => s.id), [storeSessions])
  /**
   * [产物实时推送 2026-11-02] 后端抽取完成即推 → 立刻重拉产物列表（不再等 15 秒轮询）**并按需自动打开侧栏**。
   *
   * <p><b>WHY 自动打开只在这里</b>：用户二次裁定「只有后端推送时再回默认打开」「切换会话不要默认展开」。
   * 切会话 / 首屏 / 轮询都会重新拉列表，但它们**不是**「刚刚有产物落地」⇒ 不能弹面板。
   * 推送才是唯一可信的「新生成了」信号。
   */
  const handleArtifactPush = useCallback((sid: string, payload: ArtifactPushPayload) => {
    void (async () => {
      const list = await reloadArtifacts(sid)
      // [2026-11-02 用户二次裁定] **只在「执行过程中」自动打开**，轮次结束后不打开。
      //   原话：「为什么结束轮次默认打开产物 产物只是在编辑过程中才会实时推送打开
      //   结束后不打开 显示在正文下方 由用户自己点击」。
      //   WHY：结束时产物卡已经出现在正文下方（`TurnArtifacts`），自动弹面板会**盖住**用户
      //   正在读的结论，且打断对话 —— 那时用户的注意力在「结果说了什么」，不是「文件长什么样」。
      //   执行中相反：产物刚落地的瞬间用户正等着看效果，实时展开才是他要的。
      if (!turnRunningRef.current) return
      // 用户主动收起过 → 不抢他的控制权（否则下一个产物又把面板弹回来，等于关不掉）
      if (dockUserClosedRef.current) return
      if (sid !== activeSessionId) return            // 别的会话的推送不该弹本会话的面板
      // [2026-11-04 修「新轮次会默认打开之前的产物」] **只打开「本次推送的那一条」**。
      //   原实现取 `list[0]`（按 updated_at 倒序的第一条）⇒ 上一轮的旧产物被重新推送
      //   （轮末目录扫描 / 轮内监看会把目录里所有文件重新 upsert）时，它被顶到列表第一位
      //   ⇒ 于是「新的」面板弹出来的是**上一轮的产物**。
      //   判据 = 按推送里的 artifactId 精确匹配重拉结果；匹配不到（推送早于入库 / 后端老版本
      //   不带 id）⇒ **不打开**（宁可不开，也不开错的那一条）。
      const artifactId = payload.artifactId
      if (!artifactId) return
      const target = list?.find((a) => a.id === artifactId)
      if (!target) return
      // 来源（source）不自动展开：来源是一批**链接**，不是「刚生成的交付物」，
      //   侧栏预览的是文件（localPath），对 url 形态的来源没有意义；
      //   且自动展开会盖住用户正在读的内容（用户 2026-11-04 裁定：只有产物才自动开）。
      //   判据取**推送载荷的 kind**（这次推的到底是什么）⇒ 不依赖 DB 行恰好在哪个桶里。
      if (payload.kind && payload.kind !== 'deliverable') return
      if (target.kind !== 'deliverable') return
      dockAutoOpenRef.current = true
      setDockArtifact(target)
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId])
  const { clientRef } = useChatSocket(activeSessionId, activeStreams, showToast, handleSessionDone, handleQueueDrained, handleQueueChanged, handleReconnectReload, handleReconnectReload, knownSessionIds, handleArtifactPush)

  // ---- away-summary：blur 5min 触发，REST 摘要回插为系统消息 ----
  useAwaySummary(activeSessionId, (text) => {
    const st = useChatStore.getState()
    // [有界窗口] away-summary 是 blur 触发的追加路径，不保证随后有 finalize 兜底 → 原
    //   setMessages([...prev, new]) 在本会话内只增不减。改用 appendMessages 由 store 统一裁剪。
    st.appendMessages(activeSessionId, [{
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
  // 会话绑定项目反查真实项目；缺省回退 perSessionProjects（会话创建时记录）→ 空项目（「未绑定项目」零态）
  // 引用稳定：流式每个 chunk 都会让 App 重渲染；若这里每次新建对象，下传 RightPanel 的
  //   mainProject/flashingProject 引用恒变 ⇒ 右栏 memo 失效、每个 chunk 重渲染整棵右栏树
  //   （表现为「后台都输出完了，前端还在慢慢打字」）。
  const sessionProject = useMemo(
    () => (boundProject
      ? { main: boundProject, subs: [], expanded: {}, flashing: null }
      : (perSessionProjects[activeSessionId] ?? {
          main: EMPTY_PROJECT,
          subs: [],
          expanded: {},
          flashing: null,
        })),
    [boundProject, perSessionProjects, activeSessionId],
  )

  // ---- session handlers ----
  const switchSession = useCallback(
    (sessionId: string) => {
      const s = sessions.find((x) => x.id === sessionId)
      if (!s) return
      // ⭐ [2026-11-04 用户报「我点击会话 他给我挡住了会话」] 先回对话页再判 no-op。
      //
      // 根因：`workspacePage` 只在「新建会话」与 Esc 里被重置成 'chat'，
      //   点会话走 Schedules/知识库页时**不会**离开 ⇒ 会话切了但主区仍被那一页盖住。
      // 顺序要求（别调换）：
      //   ① 必须在 `sessionId === activeSessionId` 的 no-op 返回**之前** ——
      //      「在知识库页点当前会话」正是最常见的场景（用户离开对话页后想回来），
      //      若放在 no-op 之后，这一下会被吞掉，页面还是回不去。
      //   ② 只认「会话被真正选中」这一件事；不碰其它页面状态。
      // 该函数有三个调用方（左侧栏 SessionList / 用量弹窗 / 搜索面板），
      // 在这里改一处即全部生效。
      setWorkspacePage('chat')
      // 已激活会话重复点击 → no-op（防快速连点把「切换清空+重拉+整列表重渲染」排队压主线程 → 卡死）
      if (sessionId === activeSessionId) return
      sessionDispatch({ type: 'SWITCH', sessionId })
      // [2026-10-23] 不再「切会话清空排队框」——useCommandQueue 已按会话分桶，直接显示目标会话
      //   自己的快照即可；清空会把另一会话**真实在排队**的项一起抹掉。
      if (!sessionR.openSessions.includes(sessionId)) {
        sessionDispatch({ type: 'ADD_TAB', tabId: sessionId })
        showToast(`已打开: ${s.title}`, 'success')
      } else {
        showToast(`已切换到: ${s.title}`, 'success')
      }
    },
    [sessions, sessionR.openSessions, sessionDispatch, showToast, activeSessionId],
  )

  /**
   * [2026-11-04 用户要求「最近任务，WorkBuddy 有的」] 托盘右键菜单里的「最近任务」。
   *
   * <h2>分工</h2>
   * 会话列表的唯一真源在前端 store（含标题实时改名 / 本地乐观新建）⇒ 由前端推给 Rust，
   * Rust 只按收到的列表重建菜单。⛔ 不让 Rust 再走 HTTP 拉一遍（会造第二份真源）。
   *
   * <h2>为什么用 store 的 sessions 而不是 sessions（映射后）</h2>
   * `sessions` 是给旧 UI 消费的形状；托盘只要 `id + title`，
   * 直接用 `storeSessions`（已按 updatedAt 倒序 = 「最近」）最省事也最准。
   */
  useEffect(() => {
    const recent = storeSessions.slice(0, 5).map((s) => ({
      id: s.id,
      title: s.title?.trim() || '新会话',
    }))
    // 带上当前会话：托盘子菜单里给它打 ✓（切会话时列表内容没变，但勾选位置要跟着动）
    void updateTrayRecent(recent, activeSessionId)
  }, [storeSessions, activeSessionId])

  // 托盘「最近任务」点击 → 切到那个会话（Rust 已先把窗口唤回前台）
  useEffect(() => {
    let off: (() => void) | null = null
    let cancelled = false
    void onTraySwitchSession((sid) => {
      if (cancelled) return
      // 会话可能已删除 / 不在当前列表 ⇒ 忽略（不弹错，界面上本来就找不到它）
      if (!useChatStore.getState().sessions.some((x) => x.id === sid)) return
      sessionDispatch({ type: 'SWITCH', sessionId: sid })
      if (!sessionR.openSessions.includes(sid)) sessionDispatch({ type: 'ADD_TAB', tabId: sid })
    }).then((fn) => { if (cancelled) fn(); else off = fn })
    return () => { cancelled = true; off?.() }
    // sessionR.openSessions 每次渲染都是新数组：用 ref 读最新，避免反复重订阅
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionDispatch])

  /**
   * [2026-11-09] 系统通知点击 → 拉回前台 + 切到该会话。
   *
   * <p><b>WHY 必须有</b>：提问类通知（AskUserQuestion）弹在**系统通知中心**，用户点它时的
   * 预期是「回到那个待回答的会话」。而默认行为只是「弹出一条通知」——窗口可能仍在后台/
   * 最小化，用户点了通知什么也没发生（用户实测：「点击不会把客户端拉到前台」）。
   *
   * <p><b>三步</b>：① Rust {@code show_main_window_command}（show + unminimize + set_focus，
   * 走 Rust 是因为前端窗口 API 在「窗口被别的窗口盖住」时不保证能置顶）；② 切会话；
   * ③ 无载荷（Web 通道拿不到 extra）时只拉前台、不切会话。
   *
   * <p>⛔ 会话可能已删除 ⇒ 忽略（不弹错）。
   */
  useEffect(() => {
    let off: (() => void) | null = null
    let cancelled = false
    void onSystemNotificationClick((extra) => {
      if (cancelled) return
      void showMainWindow()
      const sid = typeof extra?.sessionId === 'string' ? extra.sessionId.trim() : ''
      if (!sid) return
      // 会话可能已删除 / 不在当前列表 ⇒ 忽略（界面上本来就找不到它）
      if (!useChatStore.getState().sessions.some((x) => x.id === sid)) return
      sessionDispatch({ type: 'SWITCH', sessionId: sid })
      if (!sessionR.openSessions.includes(sid)) sessionDispatch({ type: 'ADD_TAB', tabId: sid })
    }).then((fn) => { if (cancelled) fn(); else off = fn })
    return () => { cancelled = true; off?.() }
    // sessionR.openSessions 每次渲染都是新数组：用 ref 读最新，避免反复重订阅
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionDispatch])

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
        sessionListSeqRef.current++  // 丢弃在途轮询响应，避免已删会话闪回
        setSessions(remaining)
        // 若删除的是当前会话 → 切到下一个（或空）
        if (activeSessionId === sessionId) {
          const next = remaining[0]
          if (next) {
            sessionDispatch({ type: 'SWITCH', sessionId: next.id })
            if (!sessionR.openSessions.includes(next.id)) sessionDispatch({ type: 'ADD_TAB', tabId: next.id })
          } else {
            // 删除的是最后一个会话 → 激活重置为空。否则 activeSessionId 残留已删 id，
            // 之后欢迎页「绑定项目」会误判为「有会话」→ bind 到已删除的 session → Session not found
            sessionDispatch({ type: 'SWITCH', sessionId: '' })
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
        const hit = p.models.find((m) => m.enabled && isChatModelType(m.type)
          && `${p.name}/${m.name}` === main)
        if (hit) {
          // [半动态 KIMI] 存储 tag 是 DS 兜底时，若品牌能从模型全名推断（如 kimi/moonshot）→ 用推断值，
          // 避免「选了 kimi 却发 model=DS」；已存明确标签（CL/GP/QW/KIMI）则尊重
          const brand = brandTagFromName(main)
          return { tag: hit.tag === 'DS' && brand !== null ? brand : hit.tag, name: main }
        }
      }
    }
    // 2) 回落第一个 enabled provider 的第一个 enabled「对话类」模型
    //    ⛔ 不能只看 enabled：embedding/rerank 模型排在前时会成为新会话默认模型（聊天必失败）
    const firstEnabled = providersApi.list.find((p) => p.enabled
      && p.models.some((x) => x.enabled && isChatModelType(x.type)))
    const m = firstEnabled?.models.find((x) => x.enabled && isChatModelType(x.type))
    if (m && firstEnabled) return { tag: m.tag, name: `${firstEnabled.name}/${m.name}` }
    // 3) 回落当前会话
    if (activeSession?.modelName) return { tag: (activeSession.model ?? 'DS') as ModelTag, name: activeSession.modelName }
    return { tag: 'DS' as ModelTag, name: 'DeepSeek-Chat' }
  }, [providersApi.list, activeSession, appSettings?.mainModelName])
  /**
   * 新建会话（项目内）· [S3 · F-03c] `mainProjectId` 改为**必传参数**。
   *
   * <p>WHY：原实现自行解析 `activeSession?.mainProjectId ?? perSessionProjects[...]?.main?.id ?? undefined`
   * —— `??` 不拦空串，且末段 `?? undefined` 会把「解析不出项目」静默降级为「不传项目」，正是
   * 制造未绑定会话的那条路。改为必传后，解析职责上移到单点
   * {@link resolveNewSessionProjectId}（由 handleNewSession 调用并保证非空），本函数不再可能
   * 发出 `"mainProjectId":undefined` / `""`。
   */
  const createSession = useCallback(async (mainProjectId: string) => {
    const pickFrom = defaultNewSessionModel
    // 单项目模式：工作组下已有「无对话空会话」则不再创建（用户需求）——
    // 但必须**切到那个空会话**：用户点「新建会话」要的就是一个可对话的空会话，已有则复用。
    // 只弹提示就 return 会让按钮看起来没反应（左栏高亮也不动）。
    const chatMessages = useChatStore.getState().messages
    const emptySession = storeSessions.find((s) => {
      // 同工作组（同 mainProjectId；无绑定会话视为同一默认组）
      const sameGroup = (s.mainProjectId ?? null) === (mainProjectId ?? null)
      if (!sameGroup) return false
      const msgs = chatMessages[s.id] ?? []
      const isStreaming = !!useChatStore.getState().streams[s.id]
      // 空会话判定用后端 messageCount 权威（sessions 表消息计数）：前端 messages 未加载的会话
      //   （有历史但本端未拉）不再误判为空 → messageCount>0 或前端已有消息皆非空，两者皆无才判空
      return (s.messageCount ?? 0) <= 0 && msgs.length === 0 && !isStreaming
    })
    if (emptySession) {
      // 与 switchSession 同序（App.tsx:1772）：先回对话页再判，否则主区仍被定时任务/知识库页盖住
      setWorkspacePage('chat')
      if (emptySession.id !== activeSessionId) {
        sessionDispatch({ type: 'SWITCH', sessionId: emptySession.id })
        sessionDispatch({ type: 'ADD_TAB', tabId: emptySession.id })
        showToast('当前工作区已有空会话，已切换到该会话', 'info')
      } else {
        showToast('当前工作区已有空会话，请直接对话', 'info')
      }
      return
    }
    let created: SessionDto
    try {
      created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    // 新增后按 compareSessions 重排（不无条件前插）：新建会话的 updatedAt 由后端置为当前时刻
    //   → 本来就是最新 → 排序后自然在最前；同时整列表从此保持与后端 GET /sessions 同一口径，
    //   消除「新建跳到顶上、F5 后掉回原位」的错序（原写法 [created, ...storeSessions] 前插，
    //   F5 走 sessionApi.list() 的 DB 序 = 另一套口径）。
    sessionListSeqRef.current++  // 丢弃在途轮询响应，避免新会话闪一下又消失
      setSessions([created, ...storeSessions].sort(compareSessions))
    sessionDispatch({ type: 'SWITCH', sessionId: created.id })
    sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
    // M2：右面板 main 用真实项目反查（created.mainProjectId → realProjects），无真实 id → 空项目（不显示演示项目名）
    const realMain = (created.mainProjectId && realProjects.find((p) => p.id === created.mainProjectId)) || EMPTY_PROJECT
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
  }, [sessionDispatch, showToast, setSessions, storeSessions, realProjects, defaultNewSessionModel, activeSessionId])

  /**
   * [分叉] 从某轮末消息截断复制出新会话并自动切过去。
   *
   * <p>复制（消息 / 工具调用 / 产物目录 / 附件）全在后端完成，前端只负责：
   * 按 `compareSessions` 插入左栏（与 {@link createSession} 同一口径，避免 F5 后错序）+
   * SWITCH + ADD_TAB。失败由按钮侧 catch（不跳转），这里只提示。
   */
  const handleForkTurn = useCallback(async (sourceSessionId: string, throughMessageId: string) => {
    try {
      const res = await sessionApi.fork(sourceSessionId, throughMessageId)
      sessionListSeqRef.current++  // 丢弃在途列表响应，避免新会话闪一下又消失
      setSessions([res.session, ...storeSessions].sort(compareSessions))
      setWorkspacePage('chat')
      sessionDispatch({ type: 'SWITCH', sessionId: res.session.id })
      sessionDispatch({ type: 'ADD_TAB', tabId: res.session.id })
      showToast(`已分叉：${res.session.title}（复制 ${res.copiedMessages} 条消息）`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      throw e
    }
  }, [sessionDispatch, setSessions, storeSessions, showToast])

  /**
   * 添加工作区（新项目工作组）：给已校验的绝对目录 → 注册/取项目 → 创建该项目首个会话并置顶左栏。
   *
   * <p>「欢迎页（无会话）绑定项目」与左栏「添加工作区 +」共用此路径——保证任一入口绑定目录后
   * 左侧会话列表立即出现该项目（此前无会话分支只注册项目不建会话 → 左栏无变化，用户误以为没绑上）。
   * <p>注册用后端 POST /projects 幂等（同 path 返回已有、不同 path 同名各自成行）——
   * 不再按 name 前端猜复用，避免同名不同目录误配。
   */
  const openWorkspaceFromFolder = useCallback(async (absPath: string) => {
    const folderName = absPath.split(/[\\/]/).pop() || absPath
    // 注册/取项目（name=文件夹名，path=绝对路径；后端幂等）
    let proj: ProjectDto
    try {
      proj = await projectApi.create({ name: folderName, path: normalizePath(absPath) })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    const p: Project = { id: proj.id, name: proj.name, branch: proj.branch ?? '', dirty: proj.dirty ?? 0, agents: proj.agents ?? 0, path: proj.path ?? '', createdAt: proj.createdAt ?? undefined }
    // 刷新真实项目缓存（置顶去重）
    setRealProjects((prev) => (prev.some((x) => x.id === p.id) ? prev : [p, ...prev]))
    // 创建该项目第一个会话（新工作组起点；不拦截——新工作组无空会话）
    const pickFrom = defaultNewSessionModel
    let created: SessionDto
    try {
      // [S3 · F-03c] 用 proj.id 而非 p.id：Project.id 类型上可缺省（src/types.ts:8，mock 数据形态），
      //   p 是宽化后的 UI 类型；proj 是刚由 projectApi.create 拿到的 ProjectDto，其 id 必填
      //   （src/api/projects.ts:7 `id: string`）——这里要的正是那个必有的 id。
      created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId: proj.id })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return
    }
    // 同 createSession：按统一口径重排（新建会话 updatedAt 最新 → 自然置顶），不无条件前插
    sessionListSeqRef.current++  // 丢弃在途轮询响应，避免新会话闪一下又消失
      setSessions([created, ...storeSessions].sort(compareSessions))
    sessionDispatch({ type: 'SWITCH', sessionId: created.id })
    sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
    setPerSessionProjects((prev) => ({
      ...prev,
      [created.id]: { main: p, subs: [], expanded: {}, flashing: null },
    }))
    showToast(`已添加工作区 ${p.name}`, 'success')
  }, [defaultNewSessionModel, showToast, sessionDispatch, setSessions, storeSessions])

  // 本地文件夹选项目（Tauri 原生文件夹框 · 用户需求）：有有效会话 → 注册(幂等) + 绑定当前会话；
  //   无会话 / 激活会话已被删除（欢迎页空态）→ 等同「添加工作区」，自动建该目录首个会话显示到左栏
  const handleSelectProjectFolder = useCallback(async () => {
    // 「有会话」必须是列表里真实存在的会话：删除最后一个会话后 activeSessionId 可能残留已删 id
    // （见 handleDeleteSession else 分支）——若仍以非空 id 判「有会话」，会 bind 到已删除的 session
    // → 后端 Session not found
    const activeStillExists = !!activeSessionId && storeSessions.some((s) => s.id === activeSessionId)
    const sel = await selectProjectFolder()
    if (!sel) return // 用户取消
    // 后端已校验路径（转绝对 + 目录必须存在）：浏览器 dev 模式仅返回目录名（相对），
    //   传相对路径会污染会话 cwd → 此处硬校验，非绝对路径直接拒绝
    if (!isAbsolutePath(sel.path)) {
      showToast('浏览器模式无法获取项目绝对路径，请使用桌面端选择文件夹', 'info')
      return
    }
    if (!activeStillExists) {
      // 无有效会话（真无会话 / 激活指向已删会话）：建工作区会话 → 左侧立即出现绑定项目
      await openWorkspaceFromFolder(sel.path)
      return
    }
    // 有效会话（空新会话）：已有对话不可切换；否则注册项目（幂等）→ 绑定当前会话
    const hasMessages = (useChatStore.getState().messages[activeSessionId] ?? []).length > 0
    if (hasMessages) {
      showToast('当前会话已有对话，不能切换绑定目录', 'info')
      return
    }
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
      const p = { id: proj.id, name: proj.name, branch: proj.branch ?? '', dirty: proj.dirty ?? 0, agents: proj.agents ?? 0, path: proj.path ?? '', createdAt: proj.createdAt ?? undefined }
      return [p, ...prev.filter((x) => x.id !== proj.id)]
    })
    try {
      const updated = await projectApi.bind(activeSessionId, { projectId: proj.id })
      setSessions(useChatStore.getState().sessions.map((s) => (s.id === activeSessionId ? updated : s)))
      showToast(`已绑定项目 ${proj.name}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [activeSessionId, storeSessions, showToast, setSessions, openWorkspaceFromFolder])

  // 添加工作区（新项目工作组）：选文件夹 → 走公共建工作区路径（可添加多个）
  const handleAddWorkspace = useCallback(async () => {
    const sel = await selectProjectFolder()
    if (!sel) return // 用户取消
    // 非绝对路径拒绝（浏览器 dev 模式仅目录名，会污染会话 cwd）
    if (!isAbsolutePath(sel.path)) {
      showToast('浏览器模式无法获取项目绝对路径，请使用桌面端选择文件夹', 'info')
      return
    }
    await openWorkspaceFromFolder(sel.path)
  }, [openWorkspaceFromFolder])

  /**
   * [S3 · F-03c 2026-09-14] 「+ 新会话」按钮的唯一入口闸门。
   *
   * <p><b>WHY（规则九 · 意图）</b>：本仓「项目」是会话表上的<b>可空</b>列，所以「无项目的新会话」
   * 是前端能<b>制造</b>出来的状态（CC 状态空间里不存在：CC 启动即冻结 projectRoot，
   * Open-ClaudeCode/src/bootstrap/state.ts:278-279）。裁定要求在制造点拦掉 —— 即按钮 →
   * createSession 这条边。闸门放 App 层（不放 SessionList）：SessionList 是纯展示组件
   * （props 只有 projectPathFor/projectNameFor，拿不到项目列表/picker），在这里天然单点。
   *
   * <p><b>为什么两个分支不是同一件事</b>：无项目可绑时，若直接走
   * {@code handleSelectProjectFolder}，存量「未绑定<b>且已有消息</b>」的会话会落进它的
   * 「当前会话已有对话，不能切换绑定目录」分支（App.tsx 该函数末段）—— 那是<b>改绑</b>的提示，
   * 与用户点的「新建」无关（正是 F-03d 点名的孤儿态）。故此处统一走
   * {@link handleAddWorkspace}：「选目录 → 注册项目 → 建该项目的新会话」，语义恒等于「新建」，
   * 且完全不碰当前会话的绑定。无活跃会话时它也等价于原有引导路径，故不必再分流。
   *
   * <p><b>不许静默失效</b>：解析不出项目时<b>不</b>降级成「不传 mainProjectId 照样建」——
   * 而是显式提示 + 打开目录选择（用户可见的两个动作）。
   */
  const handleNewSession = useCallback(async () => {
    const pid = resolveNewSessionProjectId(
      activeSession?.mainProjectId,
      perSessionProjects[activeSessionId ?? '']?.main?.id,
    )
    if (pid) {
      await createSession(pid)
      return
    }
    showToast('请先选择项目目录，再新建会话', 'info')
    await handleAddWorkspace()
  }, [activeSession, activeSessionId, perSessionProjects, createSession, handleAddWorkspace, showToast])

  // ---- per-session project handlers ----
  const updateSessionProject = useCallback(
    (id: string, updater: (p: SessionProjectState) => SessionProjectState) => {
      setPerSessionProjects((prev) => ({
        ...prev,
        [id]: updater(prev[id] ?? { main: EMPTY_PROJECT, subs: [], expanded: {}, flashing: null }),
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

  // [2026-10-31] 档位绑定 / 解绑的三个动作。
  //
  // ① bindModelTier —— 走 PUT（写非 null 值 ⇒ 后端 merge 覆盖）。传的是**全名**（provider/model）：
  //    后端按全名反查（settingsTierModelName），传裸名会在多 provider 同名时歧义。
  // ② clearModelTier —— 走独立 DELETE 端点。⛔ 不能用 PUT 传 null：后端 merge 契约是
  //    「null = 不覆盖」，传 null 会被当成「这次别动这个字段」⇒ **绑定清不掉**
  //    （这正是 2026-10-31 前「清除快速模型」按钮只清本地 state、刷新后复活的原因）。
  // ③ clearAllModelTiers —— 「一键清除所有已配置的模型」。
  //
  // ⚠️ 成功后一律经 applyAppSettings 刷新（App.tsx 的注释明确警告过「不要另写一份合并」）。

  /** 档位中文名（与 ModelSettingsPanel 的档位定义同源，用于 toast 文案）。 */
  const TIER_LABEL: Record<ModelTierRole, string> = {
    main: '主模型', fast: '快速模型', strong: '最强模型', medium: '中等模型',
    weak: '弱模型', subagent: '子代理', fallback: '降级模型',
    multimodal: '多模态模型', tts: 'TTS', asr: 'ASR', classifier: '权限分类器',
  }

  const bindModelTier = useCallback(
    (role: ModelTierRole, fullName: string) => {
      const field = TIER_FIELD_BY_ROLE[role]
      void onSaveSettings({ [field]: fullName } as UpdateSettingsRequest)
      showToast(`${TIER_LABEL[role]} → ${fullName}`, 'success')
    },
    [onSaveSettings, showToast],
  )

  /**
   * 一键把某个模型写进所有「通用」档位（不含 TTS / ASR）。
   *
   * <p><b>一次 PUT 而不是 N 次</b>：settings 的 PUT 天然支持多字段合并 ⇒ 9 档一次写完；
   * 分 9 次发请求既慢又可能半成功（中间失败会留下「一部分档位已改」的脏状态）。
   *
   * <p>⚠️ 不复用 `onSaveSettings`：它会吞掉异常（仅 toast），调用方会误以为成功并再报一次
   * 「已配置」—— 必须像 `clearModelTier` 一样自己发请求，失败时 rethrow 给面板收尾。
   */
  const bindAllModelTiers = useCallback(
    async (fullName: string) => {
      const req = Object.fromEntries(
        APPLY_ALL_TIER_ROLES.map((role) => [TIER_FIELD_BY_ROLE[role], fullName]),
      ) as UpdateSettingsRequest
      try {
        const updated = await settingsApi.update(req)
        applyAppSettings(updated)
        showToast(`已配置 ${APPLY_ALL_TIER_ROLES.length} 个档位 → ${fullName}`, 'success')
      } catch (e) {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
        throw e
      }
    },
    [applyAppSettings, showToast],
  )

  const clearModelTier = useCallback(
    async (role: ModelTierRole) => {
      try {
        const updated = await settingsApi.clearModelTier(role)
        applyAppSettings(updated)
        showToast(`${TIER_LABEL[role]}已清除`, 'info')
      } catch (e) {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
        throw e   // 交给调用方（面板的 pending 状态）收尾
      }
    },
    [applyAppSettings, showToast],
  )

  const clearAllModelTiers = useCallback(async () => {
    try {
      const updated = await settingsApi.clearAllModelTiers()
      applyAppSettings(updated)
      showToast('已清除全部模型档位绑定', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      throw e
    }
  }, [applyAppSettings, showToast])

  // 会话级 effort：/effort 命令写当前会话（V31 effort_level + V32 ultracode_enabled）
  const saveEffort = useCallback(async (v: NonNullable<SessionDto['effortLevel']> | 'ultracode') => {
    // [批 3a] 后端口 sessionId 必填（缺值 400）。旧实现不带会话 → effort 只能靠 MDC 解析（第三态可
    //   写到别的会话）；现显式透传当前会话。
    if (!activeSessionId) {
      showToast('无活动会话：请先打开一个会话再设置推理等级', 'info')
      return
    }
    try {
      await commandApi.executeBuiltin('effort', activeSessionId, { args: v })
      const isUltra = v === 'ultracode'
      setSessions(storeSessions.map((s) =>
        s.id === activeSessionId
          ? { ...s, effortLevel: isUltra ? 'xhigh' : v, ultracodeEnabled: isUltra }
          : s,
      ))
      showToast(`推理等级 → ${isUltra ? 'ultracode' : v === 'off' ? '关闭思考' : v}`, 'success')
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
  // 本地专家 → 复用 handleAgentChange（现有 PATCH mainThreadAgent 链路）+ 关弹窗。
  // 建科市场专家 → 在弹窗内直接走插件安装（installMarketPlugin），不再经过 App。
  const handleMarketUseLocal = useCallback((agentType: string) => {
    setShowMarket(false)
    void handleAgentChange(agentType)
  }, [handleAgentChange])

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

  // [V283] 会话置顶 / 取消置顶（会话栏 ⋯ → 置顶）：PATCH 后端 pinned + 本地同步。
  //   sessionListSeqRef++ 丢弃在途轮询响应，避免刚置顶的会话被旧列表冲回去。
  const pinSession = useCallback(async (id: string, pinned: boolean) => {
    sessionListSeqRef.current++
    try {
      const updated = await sessionApi.update(id, { pinned })
      setSessions(useChatStore.getState().sessions.map((s) =>
        s.id === id ? { ...s, pinned: updated.pinned ?? pinned } : s))
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [setSessions, showToast])

  // 右栏 memo 化配套：这两个回调若内联，每次渲染都是新引用 ⇒ memo 恒失效。
  const setRightTabCb = useCallback(
    (t: RightTab) => uiDispatch({ type: 'SET_RIGHT_TAB', tab: t }),
    [uiDispatch],
  )
  const toggleRightCollapsed = useCallback(() => setRightCollapsed((v) => !v), [])

  // ---- 主区头部的通用动作（WorkspaceHeader）----
  // 历史提问：打开时拉候选（全部 user 消息）；「压缩 / 裁剪」仍走既有 DialogOpsModal
  //   （不另造第二条执行路径，确认/后果提示等既有能力原样保留）。
  const toggleHistoryPanel = useCallback(() => {
    if (!showHistoryPanel && activeSessionId) {
      void loadHistoryQuestions(activeSessionId)
      // 压缩/裁剪弹窗仍用「当前上下文可见」候选，单独加载
      void loadDialogOpsCandidates(activeSessionId)
    }
    setShowHistoryPanel((v) => !v)
  }, [showHistoryPanel, activeSessionId, loadHistoryQuestions, loadDialogOpsCandidates])
  const closeHistoryPanel = useCallback(() => setShowHistoryPanel(false), [])
  // [历史提问跳转] 跳转目标 + 递增 nonce（同一行重复点击也重新定位）；目标任务由 MessageList 完成
  //   定位 + 短暂高亮（不在已加载窗口时先复用 onLoadOlder 补拉更早页）。
  const historyJumpSeq = useRef(0)
  const [historyJump, setHistoryJump] = useState<{ id: string; nonce: number } | null>(null)
  /**
   * [历史提问跳转] 关闭面板并把目标交给 MessageList：定位 + 短暂高亮。
   */
  const handleHistoryJump = useCallback((messageId: string) => {
    setShowHistoryPanel(false)
    setHistoryJump({ id: messageId, nonce: ++historyJumpSeq.current })
  }, [])
  const openHistoryOps = useCallback((tab: 'compact' | 'trim') => {
    // 与双击 Esc 同一道闸：对话进行中 / 有草稿时不允许压缩/裁剪（避免从面板绕过既有保护）。
    if (!canOpenDialogOps) {
      showToast('对话进行中或有未发送内容，暂不能压缩/裁剪', 'info')
      return
    }
    setDialogOpsTab(tab)
    // 历史面板可能只加载了全量提问；打开压缩/裁剪前补拉「当前上下文可见」候选
    if (activeSessionId) void loadDialogOpsCandidates(activeSessionId)
    setShowDialogOps(true)
    setShowHistoryPanel(false)
  }, [canOpenDialogOps, showToast, activeSessionId, loadDialogOpsCandidates])
  /** 主区标题保存：复用会话重命名（PATCH title + 本地同步）。 */
  const handleWorkspaceRename = useCallback((title: string) => {
    if (activeSessionId) void renameSession(activeSessionId, title)
  }, [activeSessionId, renameSession])
  /** 轨迹入口：进入 trace；已在 trace 时再点 = 返回对话（tab 行已移除，必须留回路）。
   *
   *  <p>⚠️ 旧实现第二分支只重试 {@code loadTraceFull} 然后 return，<b>全仓没有任何
   *  {@code setCenterView('chat')}</b> ⇒ 进了轨迹就再也回不去（tooltip 却写着「返回对话」）。
   *  进入时的全量拉取已由依赖 {@code centerView} 的 effect 承担，此处只需纯 toggle。 */
  const handleWorkspaceTrace = useCallback(() => {
    setCenterView((v) => (v === 'trace' ? 'chat' : 'trace'))
  }, [])
  const handleWorkspaceSearch = useCallback(() => {
    if (!ui.showSearchPalette) uiDispatch({ type: 'TOGGLE_SEARCH' })
  }, [ui.showSearchPalette, uiDispatch])
  const handleWorkspaceShare = useCallback(() => showToast('分享功能建设中', 'info'), [showToast])
  // 切会话 / 新建 → 历史提问面板回到关闭态（候选属于上一个会话），跳转目标一并作废
  useEffect(() => { setShowHistoryPanel(false); setHistoryJump(null) }, [activeSessionId])

  /**
   * [任务结束入口] 助手消息底部链接（产物 / 变更 / 来源）→ 打开右栏抽屉并切到对应 tab。
   *
   * <p>⚠️ master 的 `RightTab` 只覆盖图标轨的 files/tasks/projects/terminal；抽屉新增的
   * artifacts / sources 值由 RightPanel 内部按更宽的联合渲染 ⇒ 此处用窄类型出口收敛。
   */
  const openRightTab = useCallback((tab: 'artifacts' | 'files' | 'sources') => {
    uiDispatch({ type: 'SET_RIGHT_TAB', tab: tab as RightTab })
    setRightDrawerOpen(true)
  }, [uiDispatch])

  // [subagent-hint] 点「子代理运行中」胶囊 → 打开右栏「运行」tab（runSection 第一块 = 子代理列表）。
  //   ⚠️ 'run' 不在 @/types 的 RightTab 联合里（RightPanel 内部 PanelTab 更宽）⇒ 与 openRightTab 同款断言。
  const openSubagentPanel = useCallback(() => {
    uiDispatch({ type: 'SET_RIGHT_TAB', tab: 'run' as RightTab })
    setRightDrawerOpen(true)
  }, [uiDispatch])

  /** 左下角用户行 → 基础设置。若完整设置正开着（理论不可达，防御）先收起。 */
  const openBasicSettings = useCallback(() => {
    if (ui.showSettings) uiDispatch({ type: 'CLOSE_SETTINGS' })
    setShowBasicSettings(true)
  }, [ui.showSettings, uiDispatch])

  /** 基础设置「高级设置」→ 关闭基础设置，打开现有完整设置（内部实现一行不改）。 */
  const openAdvancedSettings = useCallback(() => {
    setShowBasicSettings(false)
    if (!ui.showSettings) uiDispatch({ type: 'TOGGLE_SETTINGS' })
  }, [ui.showSettings, uiDispatch])

  const openSettingsAt = useCallback(
    (tab: SettingsTab) => {
      settingsDispatch({ type: 'SET_SETTINGS_TAB', tab })
      uiDispatch({ type: 'TOGGLE_SETTINGS' })
    },
    [settingsDispatch, uiDispatch],
  )

  // L4b 首启引导：打开/切换设置 tab —— 设置已开只切 tab；未开才打开（区别于上方 toggle 语义）
  const openSettingsTo = useCallback(
    (tab: SettingsTab) => {
      settingsDispatch({ type: 'SET_SETTINGS_TAB', tab })
      if (!ui.showSettings) uiDispatch({ type: 'TOGGLE_SETTINGS' })
    },
    [settingsDispatch, uiDispatch, ui.showSettings],
  )
  // L4b 首启引导编排（提供商→模型 顺序向导 · 全新安装自动弹，老用户绝不弹）
  const firstRunGuide = useFirstRunGuide({
    providers: providersApi.list,
    providersLoading: providersApi.loading,
    providersError: providersApi.error,
    appSettings,
    settingsOpen: ui.showSettings,
    openSettingsAt: openSettingsTo,
    closeSettings: () => uiDispatch({ type: 'CLOSE_SETTINGS' }),
  })
  // Task-B 档位一键套用主模型引导：主模型配好 & 六档全空时弹一次；
  // 首启向导运行中（含完成卡）不抢弹，等向导结束后再判。
  const tierInherit = useTierInheritGuide({
    appSettings,
    guideActive: firstRunGuide.active,
    showToast,
    // 「一键套用」的 PUT 响应同样不含 appName ⇒ 必须走唯一写入口（曾在此漏合并 ⇒ F1：点一次
    //   就让自有根标签静默退化成 .nexusai/）。⛔ 别改回 (updated) => setAppSettings(updated)。
    onSettingsUpdated: applyAppSettings,
  })

  // ---- 改动文件：真实 diff / 回滚（Phase 2 · 后端 SessionFilesController / files.changed 同源） ----
  const reloadChangedFiles = useCallback(async (sessionId: string) => {
    try {
      const files = await sessionFilesApi.list(sessionId)
      useChatStore.getState().setChangedFiles(sessionId, files)
    } catch { /* 后端未就绪静默（files.changed 事件仍可实时到） */ }
  }, [])
  const openChangedFileDiff = useCallback(async (path: string) => {
    if (!activeSessionId) return
    try {
      const d = await sessionFilesApi.diff(activeSessionId, path)
      setLiveDiff(d)
      uiDispatch({ type: 'SET_DIFF', file: path })
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : '无法获取 diff（文件可能已回滚）', 'info')
    }
  }, [activeSessionId, showToast])
  const rollbackChangedFile = useCallback(async (path: string) => {
    if (!activeSessionId) return
    const name = path.split('/').pop() ?? path
    if (!window.confirm(`回滚 ${name} 到本会话改动前？该文件的后续修改将被覆盖。`)) return
    try {
      const r = await sessionFilesApi.revert(activeSessionId, path)
      if (r?.success) {
        uiDispatch({ type: 'SET_DIFF', file: null })
        setLiveDiff(null)
        await reloadChangedFiles(activeSessionId)
        showToast(`已回滚 ${name}`, 'success')
      } else {
        showToast('回滚失败（文件可能已被外部修改）', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : '回滚失败', 'info')
    }
  }, [activeSessionId, reloadChangedFiles, showToast])

  // [preview-standalone] 项目文件统一打开：docx/xlsx/pdf/image/video/audio → 独立预览窗；
  //   html 与文本代码 → FileViewModal（源码查看/编辑/保存；html 可在 modal 内「运行」独立窗）。
  const openProjectFile = useCallback((projectId: string, path: string) => {
    const kind = previewKindOfPath(path)
    if (kind) {
      openStandalone({ type: kind, title: basenameOf(path), project: { id: projectId, path } })
      return
    }
    setOpenFile({ projectId, path })
  }, [])

  // [Phase3 @引用预览] 点消息里 @引用卡片 → 打开绑定项目文件（docx/图等独立预览，文本进 FileViewModal）
  const openRefFile = useCallback((path: string) => {
    const pid = activeSession?.mainProjectId
    if (!pid) {
      showToast('未绑定项目，无法预览引用文件', 'info')
      return
    }
    openProjectFile(pid, path)
  }, [activeSession?.mainProjectId, openProjectFile, showToast])

  // [产物/来源] 产物卡点击 → **按类型分发**的统一打开函数（右栏「产物」tab 与正文下方共用同一个）。
  //   ⛔ 不能走 openRefFile/openProjectFile：产物的 localPath 是**归档副本**的绝对路径
  //      （{configHome}/artifacts/<sessionId>/…），不在任何项目根内 ⇒ 必报「文件路径非法」。
  //   分发口径（用户 2026-10-31 裁定）：
  //     目录           → 桌面端打开所在目录（资源管理器）/ 浏览器端复制路径 + 说明
  //     html          → ArtifactViewModal（Monaco 查看/编辑/保存 + 运行 + 在浏览器中打开）
  //     docx/xlsx/pdf/img → 现有渲染（独立预览窗：DocxPreview / ExcelPreview / PdfPreview / 图片）
  //     md/其它文本    → ArtifactPreviewModal（Markdown 渲染 + 编辑保存）
  //     其它二进制      → 提示「该类型暂不支持预览」+ 复制路径（Tauri 下交给系统默认程序）
  const openArtifact = useCallback(async (a: SessionArtifactDto) => {
    if (!activeSessionId) {
      showToast('无法打开产物：未选中会话', 'info')
      return
    }
    const action = artifactOpenActionOf(a)
    if (action.kind === 'open-directory') {
      // 目录：不能预览也不能编辑 ⇒ 桌面端交给系统打开所在目录，浏览器端复制路径让用户自己开
      const dirPath = a.localPath
      try {
        const { isTauri } = await import('@tauri-apps/api/core')
        if (isTauri() && dirPath) {
          const { openPath } = await import('@tauri-apps/plugin-opener')
          await openPath(dirPath)
          return
        }
      } catch { /* 桌面打开失败 → 回落复制路径 */ }
      if (dirPath) {
        try { await navigator.clipboard.writeText(dirPath) } catch { /* 剪贴板被拒不影响提示 */ }
      }
      showToast('该产物是目录，路径已复制，请在文件管理器中打开', 'info')
      return
    }
    if (action.kind === 'view-html') {
      setViewArtifact({ sessionId: activeSessionId, artifact: a })
      return
    }
    if (action.kind === 'standalone') {
      openStandalone({
        type: action.previewType,
        title: a.title,
        artifact: { sessionId: activeSessionId, artifactId: a.id },
      })
      return
    }
    if (action.kind === 'preview-text') {
      setPreviewArtifact({ sessionId: activeSessionId, artifact: a })
      return
    }
    // 其它二进制（pptx / zip / …）：浏览器里没有对应渲染器 ⇒ 复制路径 + 提示；
    // Tauri 桌面端可以交给系统默认程序（.pptx → Office），保留这条既有能力。
    const path = a.localPath
    try {
      const { isTauri } = await import('@tauri-apps/api/core')
      if (isTauri() && path) {
        const { openPath } = await import('@tauri-apps/plugin-opener')
        await openPath(path)
        return
      }
    } catch { /* 桌面打开失败 → 回落复制路径 */ }
    if (path) {
      try { await navigator.clipboard.writeText(path) } catch { /* 剪贴板被拒不影响提示 */ }
    }
    showToast('该类型暂不支持预览，产物路径已复制', 'info')
  }, [activeSessionId, showToast])

  // [产物卡 · 在浏览器中打开] **只有 html 有**这个入口 → raw 端点（后端未上线时降级复制路径）
  const openArtifactInBrowser = useCallback(async (a: SessionArtifactDto) => {
    if (!activeSessionId) return
    const ok = await openArtifactRawInBrowser(activeSessionId, a.id)
    if (ok) return
    const path = a.localPath
    if (path) {
      try { await navigator.clipboard.writeText(path) } catch { /* 剪贴板被拒不影响提示 */ }
    }
    showToast('产物原始地址暂不可用，路径已复制', 'info')
  }, [activeSessionId, showToast])

  // [产物卡 · 编辑] 仅文本类产物有该入口 → html 走 Monaco 视图（直接进编辑态），其余走 Markdown/原文预览视图
  const editArtifact = useCallback((a: SessionArtifactDto) => {
    if (!activeSessionId) {
      showToast('无法编辑产物：未选中会话', 'info')
      return
    }
    if (artifactOpenActionOf(a).kind === 'view-html') {
      setViewArtifact({ sessionId: activeSessionId, artifact: a, initialEdit: true })
      return
    }
    setPreviewArtifact({ sessionId: activeSessionId, artifact: a, initialEdit: true })
  }, [activeSessionId, showToast])

  // [Phase3 @引用] 项目树右键「引用到对话」/ 未来其它入口 → 把 @path 插进输入框（与输入框 @ 补全同效）
  const insertReference = useCallback((path: string) => {
    setComposerText((prev) => {
      const sep = prev && !prev.endsWith(' ') && !prev.endsWith('\n') ? ' ' : ''
      return `${prev}${sep}@${path} `
    })
    showToast(`已引用 @${path}`, 'info')
  }, [showToast])

  // 后端技能命令名集合（GET /api/command · 含 skills）：技能斜杠命令作为消息发送触发，不弹空面板
  const [remoteCmdNames, setRemoteCmdNames] = useState<Set<string>>(new Set())
  useEffect(() => {
    let alive = true
    // [TL-W1 P4] sessionId 直传后端：无 sessionId 时后端无会话上下文 → 绑定项目的 project 级命令
    //   被判「非命令」→ 作为普通消息发出（本条判据失效）。切会话 → 重新拉取。
    commandApi.list(false, activeSessionId ?? undefined)
      .then((cs) => { if (alive) setRemoteCmdNames(new Set(cs.map((c) => c.name))) })
      .catch(() => {})
    return () => { alive = false }
  }, [activeSessionId])
  // 同上：斜杠菜单打开时刷新一次命令名集合 —— 手动安装技能后，发送侧也要立刻认得 /新技能，
  // 否则会被当成普通消息发出去。reload=true 清后端 SkillRegistry memoize。
  const slashMenuOpen = composerText.startsWith('/') && !composerText.includes(' ')
  const prevSlashMenuOpenRef = useRef(false)
  useEffect(() => {
    const opened = slashMenuOpen && !prevSlashMenuOpenRef.current
    prevSlashMenuOpenRef.current = slashMenuOpen
    if (!opened) return
    let alive = true
    commandApi.list(true, activeSessionId ?? undefined)
      .then((cs) => { if (alive) setRemoteCmdNames(new Set(cs.map((c) => c.name))) })
      .catch(() => { /* 静默：旧集合仍可判断内置命令 */ })
    return () => { alive = false }
  }, [slashMenuOpen, activeSessionId])

  // ---- 内置命令执行：统一走 executeBuiltin，成功/失败均显式反馈（fail loud）----
  // [P2-8] args：`/compact 用中文总结` 这类带参内置命令的尾部文本（命令名之后的全部内容）。
  //   后端 /compact 字面端点以 {args} 请求体接收并透传为 customInstructions（CC compact.ts:54），
  //   有指令时跳过 SM 优先直压（compact.ts:44-48）。不带 args → 裸 "/compact" 语义不变。
  const runBuiltin = useCallback(async (name: string, args?: string): Promise<boolean> => {
    // [P0-0/N1 决策 2026-09-11] 已停用命令（/clear 及别名 reset/new）前端零请求显式拒绝 —— 唯一执行
    //   漏斗（命令面板 onExecute / composer 分发都汇到此）。后端同样抛 409 fail loud，此处只为不
    //   发无谓请求 + 给用户可读中文提示（而非把 409 detail 丢给 toast）。
    if (isDisabledCommand(name)) {
      showToast('该命令已停用：请用界面「+」新建会话', 'info')
      return false
    }
    try {
      // [goal V253] /goal 走独立端点：通用端点的请求体是 ResumeExecuteRequest（无 args 字段）
      //   ⇒ args 会被静默丢弃 ⇒ 条件文本丢失。独立端点的 GoalExecuteRequest 才有 args。
      if (name === 'goal') {
        const goalText = await commandApi.executeGoal(activeSessionId, args)
        // [goal V253] 目标设定后**触发一轮执行** · 对齐 CC：
        //   CC 的 /goal 返回 {type:"query", value:"Goal set: <c>", prompt:kickoff}，
        //   processSlashCommand 把 kickoff 当 isMeta 的 user 消息注入本轮 ⇒ 模型立刻开始朝目标工作。
        //   本仓走 REST（text/plain）⇒ 后端用 [[GOAL_KICKOFF]] 标记把 kickoff 附在文本尾部。
        const raw = typeof goalText === 'string' ? goalText : ''
        const { display, kickoff } = splitGoalKickoff(raw)
        showToast(display || '已执行 /goal', 'success')
        // kickoff 作为一条 user 消息发出 ⇒ 目标模式立即开始执行
        if (kickoff) {
          void sendMessage([], kickoff)
        }
        return true
      }
      // [批 3a] 必传 activeSessionId：后端口已改必填（缺值 400），会话级内置命令（/compact 等）的
      //   会话锚定全靠它 —— 不再由后端读 MDC 兜底（MDC 第三态会读到别的会话的 id）。
      // 透传 args：/compact 自定义指令（后端 CompactExecuteRequest{args}）；其余命令忽略该字段
      const result = await commandApi.executeBuiltin(
        name,
        activeSessionId,
        args ? { args } : undefined,
      )
      // [prompt-execute 2026-11-04] prompt 型命令（/init）：后端回 {type:'prompt', content}，
      //   content 就是 InitCommand 的提示词正文。按 CC 语义把它当一条 user 消息注入本轮对话 ——
      //   否则前端只弹「已执行 /init」而模型压根没收到指令（用户实测「发了但后端没执行」）。
      //   ⚠️ 不能再往下走「已执行」toast 分支：那等于报告一个没发生的执行。
      if (isPromptExecuteResult(result)) {
        showToast(`已注入 /${name} 指令`, 'success')
        void sendMessage([], result.content)
        return true
      }
      // [P3-b 前端配合 · 压缩归零] /compact 成功落库后重拉本会话消息：压缩替换了消息链，前端本地仍是
      //   压缩前的消息（含旧 assistant 的上下文快照）→ 「已用/窗口」停在压缩前的大值。重拉走
      //   GET /messages/page（DB 权威），后端 MessageService.applyContextSnapshotToLastAssistant 只在
      //   **最后一个 compact boundary 之后**找带 usage 的 assistant → 压缩后 boundary 之后暂无 assistant
      //   → 不出快照 → Composer 上下文条归为「无快照不显示」（对齐 CC getCurrentUsage undefined 归零）。
      //   WHY 不选「认 assistantMessageId==null 的 message.usage 会话级覆盖」：后端该推送通道
      //   CompactCommand.registerPostCompactSnapshotPushContext 生产侧**未接线**（全仓无注册调用方，
      //   pushPostCompactSnapshotReset 恒 no-op）→ 依赖它则数字根本不降；重拉不依赖新事件，且正是后端
      //   同义归零（boundary 切片）的既有路径。
      if (name === 'compact') await reloadMessages()
      showToast(`已执行 /${name}`, 'success')
      return true
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return false
    }
  }, [showToast, activeSessionId, reloadMessages])

  // ---- composer ----
  /**
   * 发消息。
   *
   * @param attachments 附件
   * @param textOverride 显式文本（**编辑重发用**：重发的文本不在 composerText 里，
   *   而 composerText 是闭包旧值 ⇒ 先 setComposerText 再调本函数会读到旧文本、发出空消息）。
   *   省略 = 用输入框当前草稿（所有既有调用点行为不变）。
   */
  const sendMessage = useCallback(async (attachments?: AttachmentRequest[], textOverride?: string,
    /** 作用域：省略 = 当前会话（主对话，行为不变）；传 = 知识库弹窗内的会话 */
    scope?: { sessionId: string; text: string }) => {
    // ── 作用域解析：主对话走默认（activeSessionId / composerText），弹窗传自己的 ──
    const sid = scope?.sessionId ?? activeSessionId
    const draft = scope?.text ?? composerText
    // 绑定项目按**作用域会话**判定（弹窗会话也有自己的 mainProjectId）
    const scopeSession = scope
      ? useChatStore.getState().sessions.find((x) => x.id === sid)
      : activeSession
    let text = (textOverride ?? draft).trim()
    // [v6 · 2026-10-19] 召唤技能的输入框文本 = 中文显示名 ⇒ 发送时先还原为真实斜杠命令
    // （下面的命令分发/执行逻辑依赖 "/" 开头，必须先于它还原）。
    const alias = slashAliasRef.current
    if (alias && alias.display !== alias.name) {
      if (text === alias.display) text = '/' + alias.name
      else if (text.startsWith(alias.display + ' ')) text = '/' + alias.name + ' ' + text.slice(alias.display.length + 1)
    }
    // 允许纯图片附件发送（无文字时 content 传空串）；完全无内容不发
    if ((!text && !attachments?.length) || !sid) return
    // [2026-11-04 系统通知] 在**用户手势**里顺手申请通知权限。
    //   WHY 必须在发送这个手势内：浏览器只允许手势内弹权限框，轮次结束时（异步）
    //   再请求必被静默拒。这里 fire-and-forget：不 await、不阻塞发送、失败静默。
    //   只在 permission==='default'（从没问过）时真正弹框，见 systemNotify 里的说明。
    void ensureNotificationPermissionOnGesture()
    // 新 user 消息 → 清除上一轮 API 错误卡（错误卡属上一轮对话 · message.error 已渲染在消息流）
    useChatStore.getState().clearApiErrors(sid)
    // #1 绑定策略：未绑定项目的会话拦截发送（不发请求到后端）
    if (!scopeSession?.mainProjectId) {
      showToast('请先绑定项目再发送', 'info')
      return
    }
    // 命令触发：`/` 开头 → 命中内置命令名直接执行，未命中打开命令面板
    if (text.startsWith('/')) {
      const name = text.slice(1).split(' ')[0]
      // [P0-0/N1 决策 2026-09-11] 已停用命令显式拒绝：/clear（含别名 reset/new）在本平台永久停用
      //   （会话 id 复用 + 消息不删 ⇒「释放上下文」从未达成，详见 CommandPalette.DISABLED_COMMANDS）。
      //   WHY 必须在此拦截（而非仅靠清单移除）：'clear' 在 remoteCmdNames（GET /api/command 含
      //   builtin 源）里 → 落下方发送路径会被当普通消息发给模型（静默假执行）。
      if (isDisabledCommand(name)) {
        showToast('该命令已停用：请用界面「+」新建会话', 'info')
        setComposerText('')
        return
      }
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
        // [P2-8] 透传命令名之后的尾部文本为 args（`/compact 用中文总结` → args='用中文总结'）。
        //   旧实现只取 name 并清空输入框 → /compact 的自定义指令在入站即被丢弃（后端 args 恒空
        //   → 永远落 SM 优先分支，走不到 CC 的带指令直压路径 compact.ts:44-48）。其余内置命令
        //   不解 args（后端忽略多余字段）。
        void runBuiltin(name, text.slice(1 + name.length).trim())
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
      // [2026-11-03 用户实测「发送后『正在执行』要立刻渲染，别等模型回复」]
      //   **在 await 之前登记 activeStreams** —— 否则从「点发送」到「响应回来」这段空窗里，
      //   `computeTurnRunning` 三路全假（streamRegistered=false / hasStream=false /
      //   serverRunning 取决于 status 帧是否已到）⇒ `turnEnded` 短暂为 true ⇒
      //   「正在执行」状态行与扫光条**不渲染**；等首个 chunk 建了块才出现 ⇒ 用户看到「空白一段」。
      //
      //   topic 预置为**会话级常量** `/topic/sessions/{sid}/stream`（后端 streamTopic(sessionId)
      //   的字面量，见 ChatService.streamTopic）—— 与响应回来的 resp.streamTopic 必然相同，
      //   所以 await 之后那次 setActiveStreams 只是**同值覆盖**，不产生额外副作用。
      //   ⚠️ 提前登记不会导致重复订阅：useChatSocket.ensureSessionStream 按 sid 幂等
      //   （streamSubsRef.current.has(sid) → 直接 return）。
      streamRegGenRef.current[sid] = (streamRegGenRef.current[sid] ?? 0) + 1
      setActiveStreams((prev) => (
        prev[sid] ? prev : { ...prev, [sid]: `/topic/sessions/${sid}/stream` }
      ))
      // F20：attachments（PDF 绝对路径字符串数组）透传给后端，服务端 PDFBox 解析
      const resp = await chatApi.send(sid, { content: text, attachments })
      const st = useChatStore.getState()
      // 排队分支（B1/B5 · 对齐 CC）：turn 运行中再发消息 → 后端入队返回 queued=true。
      //   【不】乐观插入气泡（交给排队框展示暗色条）、【不】覆盖 activeStreams（保持原 topic 收流），
      //   等 queue.drained 事件（handleQueueDrained）再 append 正式气泡 + 登记新 streamTopic。
      if (resp.queued) {
        // [busy 气泡附件胶囊] 排队时【不】插气泡、也不缓存附件 —— 气泡的附件胶囊由后端权威快照
        //   随 queue.drained 事件带来（前端不按请求体自算：path 附件请求体无 contentId ⇒ 会得到
        //   不可点的胶囊，且与 F5 后不一致）。见 handleQueueDrained。
        //
        // [2026-11-03] **撤销发送前的预登记**：排队意味着「本轮还在跑」，旧轮的登记必须留着
        //   （它就是收旧轮 chunk 的通道）。预登记只多加了轮次号 —— 这里退回去，
        //   否则 reclaimStreamRegistration 的判据（登记晚于最近一次 true ⇒ 不收）会把旧轮的收口
        //   误判成「上一轮的」⇒ 旧轮登记永不回收 ⇒ 停止键卡住。
        streamRegGenRef.current[sid] = Math.max(0, (streamRegGenRef.current[sid] ?? 1) - 1)
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
      //   ⚠️ 已知边界（本批未改，如实登记）：`path` 附件（local-read 通道）在**请求体**里只有 path、
      //   没有 contentId（Composer.doSend 的 `...(a.contentId ? … : {})`）⇒ 本乐观气泡的 path 附件
      //   胶囊 contentId/url 为 null（点击无预览），要 F5 重拉（后端读侧回填 url）才可点。
      //   修它需要发送响应回传「已解析附件」（后端配合），不属本批（本批只修 busy→drained 那条腿，
      //   那条腿已改走后端权威快照，见 handleQueueDrained）。
      const userAttachments = attachments?.filter((a) => a.type !== 'image' && a.filename)
        .map((a) => {
          const cid = a.contentId ?? null
          return {
            type: a.type, filename: a.filename!, mediaType: a.mediaType ?? null, contentId: cid,
            url: cid ? `/attachments/content/${sid}/${cid}` : null,
            base64: a.base64 ?? null, path: a.path ?? null,
          }
        }) ?? []
      // [有界窗口] 发送成功的乐观 user 气泡是追加路径 → appendMessages（内部 capTail）统一裁剪；
      //   勿用 setMessages([...prev, new])（不裁剪，是内存无界的漏洞口）。
      st.appendMessages(sid, [
        { id: resp.userMessageId, sessionId: sid, role: 'user', author: '你', content: text, reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null, reasoningDurationMs: null, time: null, /* [2026-11-02 修「正在执行没有时间」] 乐观气泡必须带 createdAt —— MessageList 的 turnStartByKey 靠它算本轮起点，缺了就算不出时长（`formatTurnDuration(null)` → 空） */ createdAt: new Date().toISOString(), toolCallId: null, assistantMessageId: null, userMessageId: resp.userMessageId, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null, error: null, errorDetails: null, matchedRule: null, imageData: imageData.length ? imageData : null, userAttachments: userAttachments.length ? userAttachments : null },
      ])
      // 注意：发送时【不】clearStream —— 正常流程上一轮 complete 已清 streams（finalizeBlocks 返回 rest）；
      //   若发送时 clearStream，会触发 wasRunning 检测「streams 从有到无」→ 误移除刚登记的 activeStreams
      //   → 订阅取消 → 新消息 chunk 收不到 → 会话卡住（多会话隔离被破坏）
      // 登记活跃流式会话（useChatSocket 订阅所有 activeStreams · 切走不取消，事件持续接收）
      // [C6 · 轮次判据] 登记即开新一轮：轮次号 +1（在 updater **外**自增 —— updater 可能被 React 重复调用）。
      //   收口回调据「本轮次号 ≤ 最近一次 true 的轮次号」判定该边沿是否属于本轮（见 reclaimStreamRegistration）。
      // [2026-11-03] 轮次号已在**发送前**自增过（为了让「正在执行」立刻出现）⇒ 这里**不再自增**，
      //   否则一轮会被算成两轮 ⇒ reclaimStreamRegistration 的判据（登记晚于最近一次 true ⇒ 不收）
      //   会把本轮的收口误判成「上一轮的」⇒ 登记永不回收 ⇒ 停止键卡住。
      //   topic 用 resp.streamTopic 覆盖（与预置的会话级常量同值 ⇒ 实质是幂等写）。
      setActiveStreams((prev) => ({ ...prev, [sid]: resp.streamTopic }))
    } catch (e) {
      // [2026-11-03] **回滚发送前的预登记**：发送失败时后端根本没有 run，
      //   若不撤销，`activeStreams` 会永远挂着这个 sid ⇒ 停止键常驻「停止」且点下去没东西可停。
      //   （实测：发送失败后 composer 一直显示「停止」而会话是空的。）
      //   与排队分支同一处理：退掉预加的轮次号 + 移除登记（**只移除本次自己加的那个**：
      //   若发送前该 sid 已经在跑，`prev[sid]` 本来就有值，此时不该删）。
      streamRegGenRef.current[sid] = Math.max(0, (streamRegGenRef.current[sid] ?? 1) - 1)
      setActiveStreams((prev) => {
        if (!prev[sid]) return prev
        const next = { ...prev }
        delete next[sid]
        return next
      })
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [composerText, activeSessionId, activeSession, showToast, runBuiltin])

  // ---- [用户消息编辑重发] 裁剪该消息起全部对话 → 用新文本重发（复用 trimAfter + sendMessage）----
  //   顺序固定（性能改造后）：① trimAfter（后端 cancel-first + **区间删除**，毫秒级）
  //   → ② 落新 conversationId + **重拉窗口**（loadTailWindow）→ ③ 退出编辑态（onTrimmed 回调）
  //   → ④ 走**普通发送路径**（乐观气泡 + 登记 activeStreams ⇒ 消息区出现「思考中…」）。
  //   ⚠️ 编辑态只覆盖「裁剪」这一小段：裁剪一完成就退出编辑态，不再让用户盯着「发送中…」。
  //   ⚠️ 顺序要点：重拉窗口必须在发送之前（setMessages 会整体替换消息数组 ⇒ 晚于发送就会把
  //   乐观气泡冲掉）；退出编辑态放在重拉之后（pivot 气泡此时已从 store 移除 ⇒ 不会闪一下原文）。
  //   任一步失败 → toast 具体原因并 throw，让气泡保持编辑态（草稿不丢）。
  //   sendMessage 的引用随 composerText 每敲一键变化 ⇒ 走 ref 取最新值，本回调自身引用保持稳定，
  //   否则会把 MessageList 的 memo 打穿（打字时整表重渲，见 MessageList.tsx 末尾的稳定性契约）。
  const sendMessageRef = useRef(sendMessage)
  useEffect(() => { sendMessageRef.current = sendMessage })
  const handleEditResend = useCallback(async (
    messageId: string,
    newText: string,
    onTrimmed?: () => void,
    attachments?: AttachmentRequest[],
  ) => {
    if (!activeSessionId) return
    try {
      const resp = await chatApi.trimAfter(activeSessionId, messageId)
      // F10：裁剪后后端返回新 conversationId（row key 刷新），落 store
      if (resp.conversationId) setConversationId(activeSessionId, resp.conversationId)
      // 重拉窗口（裁剪后剩余内容 = 按轮分页的最近 3 轮；不再依赖响应里的全量 messages）
      await loadTailWindow(activeSessionId)
      // 裁剪完成 ⇒ 立刻退出编辑态（编辑态只覆盖裁剪这一小段）
      onTrimmed?.()
      // [2026-11-01] 原消息的附件随重发一起带上：否则「编辑后重发」会静默丢图
      //   （用户在编辑框里还看得见图，自然以为图还在）。
      await sendMessageRef.current(attachments, newText)
    } catch (e) {
      showToast('编辑并重新发送失败：' + (e instanceof ApiError ? e.userMessage() : String(e)), 'info')
      throw e
    }
  }, [activeSessionId, setConversationId, loadTailWindow, showToast])

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
  useEscapeKey(showBasicSettings, () => setShowBasicSettings(false))
  useEscapeKey(showHistoryPanel, () => setShowHistoryPanel(false))
  useEscapeKey(!!ui.diffFile, () => { uiDispatch({ type: 'SET_DIFF', file: null }); setLiveDiff(null) })
  useEscapeKey(showModelPicker, () => setShowModelPicker(false))
  useEscapeKey(showAgentsPanel, () => setShowAgentsPanel(false))
  useEscapeKey(showChromePanel, () => setShowChromePanel(false))
  useEscapeKey(showContextAnalyze, () => setShowContextAnalyze(false))
  useEscapeKey(showUsageCost, () => setShowUsageCost(false))
  useEscapeKey(showMemoryEditor, () => setShowMemoryEditor(false))
  useEscapeKey(showIncludeApproval, () => setShowIncludeApproval(false))
  useEscapeKey(showMarket, () => setShowMarket(false))
  // ⛔ 知识库浮层**刻意不挂 Esc**（用户 2026-11-09 裁定：「只留 ✕，Esc 也不响应」）。
  //   原因有三，缺一条都会误关：
  //   ① 场景内的输入框（问答 tab 的提问框、文档搜索、FAQ 编辑器、重命名）里按 Esc 是
  //     「取消当前输入」的通用习惯 —— 挂在 window 冒泡阶段会把整个场景一起关掉；
  //   ② `KbSceneNav` 已经用**捕获阶段**做「抽屉 → 全部设置 → 个人信息」逐层退出
  //     （见 KnowledgeBasePage.tsx:236-247），再加一条冒泡阶段的兜底会让「退一层」
  //     与「全关」互相抢；
  //   ③ 它是非模态浮层（可拖动、可边聊天边看），没有「Esc = 关模态框」这个语义。
  //   ⇒ 关闭入口只有 ✕（KnowledgeBasePage 的 `closeScene`）与侧栏里那些显式跳转项。

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

  // 折叠列宽用内联兜底：样式表里存在多条 `.app.right-collapsed`（0 / 44px）历史覆盖，
  // 一旦被别的规则抢先，右栏会收成 0 宽 → 按钮找不回来。内联优先级最高。
  // ⛔ 折叠态第三列只留 36px：右栏**不展示内容面板**，只剩一个裸按钮（用户裁定）。
  return (
    <div
      className={`app${leftCollapsed ? ' left-collapsed' : ''}${rightCollapsed ? ' right-collapsed' : ''}${rightDrawerOpen ? ' right-drawer-open' : ''}${dockArtifact ? ' artifact-dock-open' : ''} workspace-surface`}
      data-page={showSchedulesPage ? 'schedules' : 'chat'}
      style={{
        // [产物侧栏 2026-11-02] 面板宽度写到根节点上：CSS 的 `.artifact-dock-open` 读这个变量
        //   决定第三列列宽（真正压缩中栏，不是覆盖）。
        ...({ '--artifact-dock-w': `${dockWidth}px` } as React.CSSProperties),
        // ⚠️ 内联样式优先级最高，CSS 里的 left-collapsed 规则盖不住它。
        //   左栏收起 = **0 宽**（完全隐藏，用户 2026-11-01 二次裁定），不再是 48px rail。
        //   ⚠️ 第三列必须用 workspace 布局的 `--right-col`（右栏是抽屉、收起时占 0），
        //      用旧的 `--right-w`(300px) 会在「收起左栏」时凭空把右栏撑出 300px ——
        //      即用户报的「收起后右边冒出毛玻璃影」的第二个来源。
        ...((rightCollapsed || leftCollapsed)
          ? { gridTemplateColumns: `${leftCollapsed ? 0 : 260}px 1fr var(--right-col)` }
          : null),
      }}
    >
      {/* [2026-11-04 用户实测「客户端无法移动」] 无边框窗口（tauri.conf decorations:false）
          只能靠 `data-tauri-drag-region` 拖动 —— 这是 Tauri 唯一认的属性
          （tauri/src/window/scripts/drag.js: `TAURI_DRAG_REGION_ATTR`）；
          本仓此前用的是 Electron 的 `-webkit-app-region`，Tauri/WebView2 完全不处理。
          `deep` = 子树任意位置按下都能拖；button/a/input 等可点元素由 Tauri 自动排除。 */}
      <div className="topbar" data-tauri-drag-region="deep">
        {/* 左栏开合按钮在红绿灯右侧（TitleBar 内部渲染） */}
        <TitleBar
          onToggleSidebar={() => setLeftCollapsed((v) => !v)}
          sidebarCollapsed={leftCollapsed}
        />
        <MenuBar
          openSettings={() => uiDispatch({ type: 'TOGGLE_SETTINGS' })}
        />
      </div>
      <SessionList
        sessions={storeSessions}
        // [2026-10-29] 默认折叠所有分组，只自动展开当前会话所在组（用户裁定：不要一进来全展开）
        groupsDefaultCollapsed
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
        // [2026-11-10 用户裁定] 项目分组序 = 注册先后（projects.created_at 升序），与任何会话活动无关
        //   ⇒ 在后面的项目里新建会话不再把该组顶到最前。mock 项目无 createdAt → undefined（沉底兜底）。
        projectCreatedAtFor={(pid) => {
          const p = realProjects.find((x) => x.id === pid) ?? allProjects.find((x) => x.id === pid)
          return p?.createdAt
        }}
        onCreateInProject={(pid) => {
          // #1 项目内新建：带 mainProjectId 创建（用真实默认模型，非 mock 随机）
          void (async () => {
            // [S3 · F-03c 空串收口] 组键来自 `s.mainProjectId ?? '__unbound__'`（SessionList.tsx:97），
            //   而 `''` 不是 nullish ⇒ 存量 TRIM(main_project_id)='' 的行会落在键为 '' 的组、
            //   isUnbound===false ⇒ 组标题的「+」会渲染并以 pid='' 回调。`''` 不是可绑定项目
            //   （EMPTY_PROJECT.id===''）⇒ 与「无项目」同义，走引导路径，不再发 "mainProjectId":""。
            const targetPid = resolveNewSessionProjectId(pid, null)
            if (!targetPid) {
              showToast('该项目未绑定有效目录，请先选择项目目录', 'info')
              await handleAddWorkspace()
              return
            }
            // 单项目模式：该项目下已有空会话则**切过去**（不新建）——只弹提示会让「+」看起来没反应
            const chatMessages = useChatStore.getState().messages
            const emptySession = storeSessions.find((s) => {
              const sameGroup = (s.mainProjectId ?? null) === targetPid
              if (!sameGroup) return false
              // 后端 messageCount 权威（同 createSession：前端 messages 未加载不算空）
              return (s.messageCount ?? 0) <= 0 && (chatMessages[s.id] ?? []).length === 0 && !useChatStore.getState().streams[s.id]
            })
            if (emptySession) {
              setWorkspacePage('chat')
              if (emptySession.id !== activeSessionId) {
                sessionDispatch({ type: 'SWITCH', sessionId: emptySession.id })
                sessionDispatch({ type: 'ADD_TAB', tabId: emptySession.id })
                showToast('该项目下已有空会话，已切换到该会话', 'info')
              } else {
                showToast('该项目下已有空会话，请直接对话', 'info')
              }
              return
            }
            const pickFrom = defaultNewSessionModel
            try {
              // [S3 · F-03c] mainProjectId 由必传的 targetPid 给出（已保证非空）——
              //   原 `pid ?? undefined` 既是类型洞（undefined 混入）也把「空串组键」放行到后端。
              const created = await sessionApi.create({ model: pickFrom.tag, modelName: pickFrom.name, mainProjectId: targetPid })
              // 同 createSession：按统一口径重排（新建会话 updatedAt 最新 → 自然置顶），不无条件前插
              sessionListSeqRef.current++  // 丢弃在途轮询响应，避免新会话闪一下又消失
      setSessions([created, ...storeSessions].sort(compareSessions))
              sessionDispatch({ type: 'SWITCH', sessionId: created.id })
              sessionDispatch({ type: 'ADD_TAB', tabId: created.id })
              // M2：项目内新建后写 perSessionProjects 真实 main（反查 realProjects）；无真实 id → 空项目
              const realMain = realProjects.find((p) => p.id === targetPid) ?? EMPTY_PROJECT
              setPerSessionProjects((prev) => ({ ...prev, [created.id]: { main: realMain, subs: [], expanded: {}, flashing: null } }))
              showToast('已创建新会话', 'success')
            } catch (e) {
              showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
            }
          })()
        }}
        // 新建会话：先回对话页再建（否则建完还停在定时任务页，用户看不到新会话）
        onCreateSession={() => { setWorkspacePage('chat'); void handleNewSession() }}
        // 左栏「定时任务」→ 主区换页（走现有 useSchedules，不弹设置）
        onOpenSchedules={() => setWorkspacePage('schedules')}
        schedulesActive={showSchedulesPage}
        onOpenAgentMarket={() => setShowMarket(true)}
        onOpenKnowledgeBase={() => {
          // ⭐ 用户 2026-11-09 裁定：「点开默认就是独立窗口」。
          //   知识库不再先弹浮层 —— 直接开**新的 Tauri 窗口**（系统级窗口，可拖到另一块屏）。
          //   ⚠️ 浮层形态保留在下面（showKnowledge 仍可被设为 true），但**入口不再用它**：
          //     它只在「独立窗口开不出来」时作为兜底（弹窗被拦 / capability 缺失）。
          void openKnowledgeWindow({ scene: 'knowledge', baseId: null, tab: null }).then((ok) => {
            if (!ok) {
              // 独立窗开不出来 ⇒ 退回浮层，保证用户点了有东西出现（⛔ 不能点了没反应）
              setKbScene('knowledge')
              setShowKnowledge(true)
            }
          })
        }}
        doneUnreadIds={doneUnreadIds}
        onAddWorkspace={() => void handleAddWorkspace()}
        onOpenSettings={openBasicSettings}
        // 左栏改版：任务区标题、磨玻璃、底部用户区、搜索入口、定时任务入口
        workspaceRedesign
        onOpenSearch={handleWorkspaceSearch}
        onDeleteSession={handleDeleteSession}
        onRenameSession={renameSession}
        onPinSession={pinSession}
        runningSessionIds={runningSessionIds}
        pendingSessionIds={pendingSessionIds}
        onToggleCollapse={() => setLeftCollapsed((v) => !v)}
        collapsed={leftCollapsed}
      />
      <div className="center">
        {/* 定时任务页：主区换页而不是弹设置。对话面仍挂载、由 CSS 隐藏，
            避免换页丢掉 Composer 的草稿/附件状态。 */}
        {showSchedulesPage && (
          <SchedulesPage schedulesApi={schedulesApi} showToast={showToast} />
        )}

        {/* 无会话：不插空态页 —— 保留原首页（Composer welcome hero + 左栏「新建会话」），右栏各 tab 显示 0/空态 */}
        {/* 主区头部（左栏改版）：任务标题（可编辑）+ 搜索/分享/轨迹/历史提问/面板开合。 */}
        <WorkspaceHeader
          title={workspaceTitle}
          isWork={false}
          onRename={handleWorkspaceRename}
          onSearch={handleWorkspaceSearch}
          onShare={handleWorkspaceShare}
          onTrace={handleWorkspaceTrace}
          traceActive={centerView === 'trace'}
          traceCount={activeMsgTotal}
          onHistory={toggleHistoryPanel}
          history={{
            open: showHistoryPanel,
            loading: historyLoading,
            error: historyError,
            candidates: historyQuestions,
            onClose: closeHistoryPanel,
            onRetry: () => { if (activeSessionId) void loadHistoryQuestions(activeSessionId) },
            onJump: handleHistoryJump,
            onCompact: () => openHistoryOps('compact'),
            onTrim: () => openHistoryOps('trim'),
          }}
          showPanelToggle
          rightCollapsed={!rightDrawerOpen}
          onTogglePanel={() => setRightDrawerOpen((v) => !v)}
        />
        <div className="stream">
          {centerView === 'trace' ? (
            <TraceView messages={traceMessages} snippedIds={activeSnippedIds} />
          ) : (
            <MessageList sessionId={activeSessionId} messages={storeMessages} onDelete={handleDeleteMessage} conversationId={conversationId} scrollSignal={permScrollSignal + toBottomSignal} thinking={turnRunning && !hasStream} onNearBottomChange={setChatAtBottom} onOpenRefFile={openRefFile} onLoadOlder={handleLoadOlder} jumpToMessage={historyJump} onOpenRightTab={openRightTab} artifacts={sessionArtifacts} onOpenArtifact={openArtifact} onEditArtifact={editArtifact} onOpenArtifactInBrowser={openArtifactInBrowser} turnUsageFallback={turnUsageByUser} onEditResend={handleEditResend} onForkTurn={handleForkTurn} foldedTurnCount={foldedTurnCount} onExpandOlder={() => { if (activeSessionId) void handleExpandOlder(activeSessionId) }} />
          )}
        </div>
        {currentPermission && (
          <PermissionBubble
            request={currentPermission}
            // [批 A4c P3] 项目级档位文案的「项目标识」。currentPermission 已按
            //   sessionId === activeSessionId 过滤（见上方 currentPermission 定义）⇒ 用
            //   activeSession 的绑定项目即可，与 Composer 的 boundProjectName 同一来源。
            projectLabel={activeSession.mainProjectId ? (realProjects.find((p) => p.id === activeSession.mainProjectId)?.name ?? null) : null}
            // [appName 通道 2026-09-23] 「编辑配置目录」档位的自有根标记由 appName 派生。
            //   取不到（settings 未加载完 / 后端未透出）⇒ null ⇒ 回落 .nexusai/（与落地前逐字相同）。
            selfDirName={appSettings?.appName ?? null}
            onDecision={(id, d, answers, annotations, permissionUpdates) => {
              // 路由到请求来源会话（用户切走标签时仍发回正确的 session）
              const targetSession = currentPermission.sessionId ?? activeSessionId
              // permissionUpdates 仅「一键授权」档位携带（原样回传后端 suggestions）；允许/拒绝为 undefined
              if (clientRef.current) sendPermissionResponse(clientRef.current, targetSession, currentPermission.kind, id, d, { answers, annotations, permissionUpdates })
              // [2026-10-22 问答收敛] 已回答 → 给对应工具卡打「已回答 · 等待结果」标记；
              //   tool_result 到达时由 fillToolResult 自动清除（事件缺失时不再无限「执行中」）
              useChatStore.getState().markToolAnswered(targetSession, currentPermission.toolUseId ?? id)
              dequeuePermission(id)
            }}
            onAbort={() => {
              // 中止权限请求（对齐 CC Ctrl+C onReject · 用户放弃决策 → deny + dequeue，解除 worker 等待）
              const targetSession = currentPermission.sessionId ?? activeSessionId
              if (clientRef.current) sendPermissionResponse(clientRef.current, targetSession, currentPermission.kind, currentPermission.requestId, 'deny', {})
              // [问答收敛] 中止/拒绝同样要收敛卡片状态（否则卡片停在「执行中」）
              useChatStore.getState().markToolAnswered(targetSession, currentPermission.toolUseId ?? currentPermission.requestId)
              dequeuePermission(currentPermission.requestId)
            }}
          />
        )}
        {retry && (
          <div className="retry-wrap">
            <RetryBanner attempt={retry.attempt} maxRetries={retry.maxRetries} retryDelayMs={retry.retryDelayMs} at={retry.at} onClose={() => { if (activeSessionId) setRetry(activeSessionId, null) }} />
          </div>
        )}
        {/* 上下文告警 / 压缩进度横幅：均按【当前活动会话】取那一份（多会话并行不串台） */}
        <TokenWarningBanner sessionId={activeSessionId} />
        {/* 压缩进度横幅（/compact 命令触发 · 输入框上方 · compact-progress 事件驱动；发送键压缩中变停止） */}
        <CompactProgressBar sessionId={activeSessionId} />
        <NotificationBanner />
        {/* 轨迹视图只查看，不展示对话输入框 */}
        {/* 发送键 ⇄ 停止键切换用 turnRunning（activeStreams 登记 = turn 运行中，含 thinking/重试期；
            非 仅 streamOrder 有块 —— 重试/thinking 期无流式块会误显示发送键） */}
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
            popEditable={async (): Promise<PoppedQueueInput | null> => {
              if (!activeSessionId) return null
              // 草稿（composerText）随请求上送 —— 对齐 CC popAllEditable(currentInput, …)：回填是
              //   「排队项 + 草稿」，不是「用排队项覆盖草稿」
              const res = await commandQueue.popEditable(activeSessionId, composerText)
              // 回填 = 全部可编辑排队项 + 草稿（后端按 CC `\n` join 好的整段文本，前端不再拼）
              if (res) setComposerText(res.text)
              // 附件交回 Composer 还原为待发 chip（附件状态归 Composer 所有）
              return res
            }}
            boundProjectName={activeSession.mainProjectId ? (realProjects.find((p) => p.id === activeSession.mainProjectId)?.name ?? null) : null}
            boundProjectId={activeSession.mainProjectId}
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
            onClearAgent={() => void handleAgentChange(null)}
            slashAlias={slashAlias}
            onOpenMarket={() => setShowMarket(true)}
            onSetGoal={async (condition) => {
              // 只有真正设成功才关面板：runBuiltin 内部已 catch 并弹 toast，失败时返回 false
              //   ⇒ 保持面板打开，条件不丢（用户可改后重试）。
              const ok = await runBuiltin('goal', condition)
              if (!ok) throw new Error('set goal failed')
            }}
            hasGoal={!!currentGoal}
            goalCondition={currentGoal?.condition ?? null}
            pendingHint={<AskUserPendingHint activeSessionId={activeSessionId} onOpenSession={switchSession} />}
            subagentHint={activeSessionId
              ? <SubagentRunningHint sessionId={activeSessionId} onOpen={openSubagentPanel} />
              : null}
            empty={storeMessages.length === 0 && !hasStream}
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
        activeSessionId={activeSessionId}
        mainProject={sessionProject.main}
        rightTab={ui.rightTab}
        setRightTab={setRightTabCb}
        onOpenFileRow={openChangedFileDiff}
        onOpenFile={openProjectFile}
        artifacts={sessionArtifacts}
        turnContext={turnContext}
        currentTurnId={currentTurnId}
        onOpenArtifact={openArtifact}
        onEditArtifact={editArtifact}
        onOpenArtifactInBrowser={openArtifactInBrowser}
        onQuoteFile={insertReference}
        showToast={showToast}
        openSettingsAt={openSettingsAt}
        flashingProject={sessionProject.flashing}
        onRollbackFile={rollbackChangedFile}
        onToggleCollapse={toggleRightCollapsed}
        collapsed={rightCollapsed}
        // 改版后右栏是默认收起的抽屉（不占列宽）⇒ 用抽屉自带的 × / Esc 关闭，不再有「收起成窄条」
        drawer={RIGHT_PANEL_DRAWER}
        drawerOpen={rightDrawerOpen}
        onDrawerClose={() => setRightDrawerOpen(false)}
        // 「终端」tab 复用既有 STOMP 连接（不新开连接）
        socketClientRef={clientRef}
        // 抽屉的 tab 集合：通用 tab（不含任何场景专属 tab）。'tasks' 无抽屉文案，
        //   RightPanel 会按 DRAWER_TAB_LABEL 过滤掉，保留以对齐图标轨的 tab 口径。
        visibleTabs={['files', 'tasks', 'projects', 'artifacts', 'sources', 'team', 'run', 'terminal']}
      />
      {/* [产物独立侧栏 2026-11-02] 只服务「产物自动打开」。与上面的 RightPanel 抽屉**并存**：
          抽屉浏览全部会话资源，本面板跟进刚生成的那一个产物。「全屏」回落到原来的模态框。 */}
      {dockArtifact && activeSessionId && (
        <ArtifactDock
          sessionId={activeSessionId}
          artifact={dockArtifact}
          items={sessionArtifacts.deliverables}
          onSelect={(a) => setDockArtifact(a)}
          onClose={() => {
            // 用户手动收起 → 记住这个意图，新产物不再自动弹回来
            dockUserClosedRef.current = true
            setDockArtifact(null)
          }}
          onExpand={(a) => void openArtifact(a)}
          width={dockWidth}
          onWidthChange={setDockWidth}
        />
      )}
      {/* 右栏拖拽 resizer（.app 层，fixed 相对 viewport）
          改版后右栏是抽屉（宽度由抽屉自身 `--right-drawer-w` 决定、不占三栏列宽）⇒ 不渲染该 resizer。 */}
      {!RIGHT_PANEL_DRAWER && !rightCollapsed && <div className="right-resizer" ref={resizerRef} onMouseDown={handleResizeStart} />}

      {/* Diff / 文件查看（monaco 已静态入主包，点开即用） */}
      {(liveDiff ?? currentDiff) && (
        <DiffModal
          diff={liveDiff ?? currentDiff}
          close={() => { uiDispatch({ type: 'SET_DIFF', file: null }); setLiveDiff(null) }}
        />
      )}
      {openFile && (
        <FileViewModal
          projectId={openFile.projectId}
          path={openFile.path}
          close={() => setOpenFile(null)}
        />
      )}
      {previewArtifact && (
        <ArtifactPreviewModal
          sessionId={previewArtifact.sessionId}
          artifact={previewArtifact.artifact}
          initialEdit={previewArtifact.initialEdit}
          close={() => setPreviewArtifact(null)}
        />
      )}
      {/* [产物 · html] Monaco 查看/编辑/保存 + 运行 + 在浏览器中打开（与项目文件 FileViewModal 同一套交互） */}
      {viewArtifact && (
        <ArtifactViewModal
          sessionId={viewArtifact.sessionId}
          artifact={viewArtifact.artifact}
          initialEdit={viewArtifact.initialEdit}
          close={() => setViewArtifact(null)}
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
      {/* CLAUDE.md 外部 include 审批（功能3）· [T15-3] 零态（无会话）不渲染：后端审批态按项目分区，
          sessionId 为空时提交必然 400（弹窗会卡在报错里无法完成），故必须挂 `!!activeSessionId` 门 */}
      {showIncludeApproval && !!activeSessionId && (
        <IncludeApprovalModal
          files={includeApprovalFiles}
          sessionId={activeSessionId}
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
          sessionId={activeSessionId}
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
      {showAgentsPanel && <AgentsPanel onClose={() => setShowAgentsPanel(false)} sessionId={activeSessionId} />}
      {showChromePanel && <ChromePanel sessionId={activeSessionId} onClose={() => setShowChromePanel(false)} />}
      {/* 技能市场（V58 主线程 agent 胶囊 → SkillMarketModal · 骨架版） */}
      {/* ⭐ 知识库浮层（独立场景 · 用户 2026-11-06 裁定；2026-11-09 改为非模态可拖动）
          面板承载知识库的**全部**内容（列表 → 详情 → 文档/Wiki/图谱/问答 → 设置）。
          关闭：**只留 ✕**（用户裁定「Esc 也不响应」；点面板外也不关）。
          主对话保持挂载、可见、可输入 —— 覆盖层 pointer-events:none，点击穿透。 */}
      {showKnowledge && (
        <div className="kb-scene-overlay">
          <div
            className="kb-scene-panel kb-scope"
            role="dialog"
            /* ⛔ 不能写 aria-modal="true"：它是**非模态**浮层，主对话同时可交互。
               写了会让读屏软件把主对话标成不可用，与实际行为不符。 */
            aria-label="知识库"
            style={{
              transform: `translate(${kbDrag.x}px, ${kbDrag.y}px)`,
              transition: kbDragging ? 'none' : 'transform 120ms ease-out',
            }}
          >
            {/* ⭐ 拖动条 —— **必须是面板的子元素**（面板 position:relative 才是它的包含块）。
                ⚠️ 放到 overlay 下面会铺满整个视口：overlay 是 fixed inset:0 且没有定位上下文，
               把手按 absolute 定位会以视口为准（实测宽 1876px、x=0，与面板错位且拖不动）。

                ⚠️ 第一版只给了 6px 高、`background:transparent` ⇒ **用户根本看不见，也点不中**。

                ⚠️⚠️ 第二版只挂 `onPointerDown` ⇒ **在部分环境里整个拖拽是死的**。
               实测（真实鼠标，非合成事件）只有 `mousedown / mousemove / mouseup`，
               **一条 pointerdown 都没有** —— 该渲染环境不派发 Pointer Events。
               ⇒ 必须**同时**挂 mouse 与 pointer 两套，且用 `draggingRef` 去重
               （支持 pointer 的环境会两者都发，不去重会算两次位移）。 */}
            <div
              className="kb-scene-drag-handle"
              onPointerDown={(e) => startDrag(e.clientX, e.clientY, e.button)}
              onMouseDown={(e) => startDrag(e.clientX, e.clientY, e.button)}
              title="按住拖动，移动知识库窗口"
              role="separator"
              aria-label="拖动以移动知识库窗口"
            />
            {/* ✕ 关闭（唯一关闭入口）。位置在面板右上角，绝对定位在面板内。 */}
            <button
              type="button"
              className="kb-scene-close"
              onClick={() => setShowKnowledge(false)}
              title="关闭知识库（Esc 不生效，点面板外也不关）"
              aria-label="关闭知识库"
            >
              ✕
            </button>
            {/* ⭐ 「弹出独立窗口」（用户 2026-11-09：面板要能拖到另一块屏幕）。
                ⚠️ 页面元素再怎么 fixed/transform 都只能活在主窗口的 WebView 里，
                   移出边界只会被裁掉 ⇒ 必须开一个新的 Tauri 窗口（系统级窗口，可跨屏）。
                点它：开独立窗 → 关掉这里的面板（避免同一个知识库同时开两份、各改各的）。 */}
            <button
              type="button"
              className="kb-scene-popout"
              onClick={() => {
                void openKnowledgeWindow({
                  scene: kbScene,
                  // ⚠️ 只带「场景」：库与 tab 的选择在 KnowledgeBasePage 内部 state 里，
                  //    主窗口这一层拿不到 ⇒ 独立窗自己回到列表页（⛔ 不猜用户点进了哪个库）。
                  baseId: null,
                  tab: null,
                }).then((ok) => {
                  // ⚠️ 只有**确实开出来了**才关面板。反过来（先关再开）在弹窗被拦时
                  //    表现为「点了没反应、面板还消失了」——用户会以为功能坏了。
                  if (ok) setShowKnowledge(false)
                })
              }}
              title="在独立窗口中打开（可拖到另一块屏幕）"
              aria-label="在独立窗口中打开知识库"
            >
              ⧉
            </button>
            {/* ⭐ 场景内左侧栏（设计稿 <aside class="aside"> 的 7 项）
                ⚠️ 只在弹窗里；主窗口会话栏（SessionList）不动 */}
            <KbSceneNav
              activeKey={kbView === 'chat' ? 'chat' : (kbScene === 'shared' ? 'shared' : 'knowledge')}
              onNewChat={() => { setKbNewChatToken((t) => t + 1); setKbView('chat') }}
              onOpenArtifacts={() => { setShowKnowledge(false); openRightTab('artifacts') }}
              onOpenAgents={() => { setShowKnowledge(false); setShowAgentsPanel(true) }}
              onOpenSchedules={() => { setShowKnowledge(false); setWorkspacePage('schedules') }}
              onOpenToolbox={() => { setShowKnowledge(false); setShowMarket(true) }}
              // ⭐ 共享空间 = 场景内的独立一页（不关弹窗、不重开）
              onOpenSharedSpace={() => setKbScene('shared')}
              onBackToList={() => { setKbView('knowledge'); setKbScene('knowledge') }}
            />
            <div className="kb-scene-main">
              {kbView === 'chat' ? (
                <KbNewChatPane showToast={showToast} newChatToken={kbNewChatToken} />
              ) : (
                <KnowledgeBasePage
                  showToast={showToast}
                  scene={kbScene}
                  onBackToChat={() => setShowKnowledge(false)}
                  onRequestClose={() => setShowKnowledge(false)}
                />
              )}
            </div>
          </div>
        </div>
      )}
      {showMarket && (
        <SkillMarketModal
          sessionId={activeSessionId ?? ''}
          currentAgent={currentAgent}
          busy={turnRunning}
          onClose={() => setShowMarket(false)}
          onUseLocalAgent={handleMarketUseLocal}
          onSummonSkill={(cmd, display) => {
            setShowMarket(false)
            // 有中文显示名 → 输入框用显示名本身（发送时还原为 /命令名）；否则维持 /命令名
            if (display) {
              setComposerText(`${display}${SUMMON_TRAILING_SPACES}`)
              setSlashAlias({ name: cmd, display })
            } else {
              setComposerText(`/${cmd} `)
              setSlashAlias(null)
            }
            // 召唤后聚焦输入框，用户可以接着输入（等弹窗关闭后再聚焦）
            setTimeout(() => document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus(), 60)
          }}
          showToast={showToast}
        />
      )}
      <SkillSurvey />
      {/* 基础设置（左下角用户行）：轻量四项 + 「高级设置」→ 现有完整设置 */}
      {showBasicSettings && (
        <BasicSettingsModal
          theme={settings.theme}
          setTheme={(t) => settingsDispatch({ type: 'SET_THEME', theme: t })}
          fontSize={settings.fontSize}
          setFontSize={(s) => settingsDispatch({ type: 'SET_FONT_SIZE', size: s })}
          dataDir={
            appSettings?.configHome
              ?? (appSettings?.appName ? `~/.${appSettings.appName}` : '~/.nexusai')
          }
          onOpenAdvanced={openAdvancedSettings}
          close={() => setShowBasicSettings(false)}
          showToast={showToast}
        />
      )}
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
          mcpApi={mcpApi}
          databasesApi={databasesApi}
          schedulesApi={schedulesApi}
          appSettings={appSettings}
          onSaveSettings={onSaveSettings}
          onBindModelTier={bindModelTier}
          onBindAllModelTiers={bindAllModelTiers}
          onClearModelTier={clearModelTier}
          onClearAllModelTiers={clearAllModelTiers}
          onOpenMemoryEditor={() => setShowMemoryEditor(true)}
          sessionId={activeSessionId}
          close={() => uiDispatch({ type: 'CLOSE_SETTINGS' })}
          showToast={showToast}
        />
      )}
      {/* Task-B 档位一键套用主模型引导（向导结束后/设置加载完条件满足弹一次 · 独立 Modal 层） */}
      {tierInherit.active && tierInherit.mainModelName && (
        <TierInheritModal
          mainModelName={tierInherit.mainModelName}
          showExtrasNote={tierInherit.extrasAllUnset}
          onApply={tierInherit.apply}
          onDismiss={tierInherit.dismiss}
        />
      )}
      {/* L4b 首启引导 Spotlight（全局层 · TourOverlay 未激活时返回 null） */}
      <TourOverlay
        active={firstRunGuide.active}
        stepIndex={firstRunGuide.stepIndex}
        steps={firstRunGuide.steps}
        onNext={firstRunGuide.onNext}
        onSkip={firstRunGuide.onSkip}
        done={firstRunGuide.done}
      />
      <ProjectContextMenu
        contextMenu={ui.contextMenu}
        mainProject={sessionProject.main}
        handlePromote={handlePromote}
        unbindProject={unbindProject}
        showToast={showToast}
      />
      {showDialogOps && (
        <DialogOpsModal
          candidates={dialogOpsCandidates}
          loading={dialogOpsLoading}
          error={dialogOpsError}
          onRetry={() => {
            const sid = activeSessionIdRef.current
            if (sid) void loadDialogOpsCandidates(sid)
          }}
          initialTab={dialogOpsTab}
          onCompact={handleConfirmCompact}
          onTrim={handleTrimAfter}
          onClose={() => setShowDialogOps(false)}
        />
      )}
      <Toast toast={ui.toast} />
      <UpdateCenter appName={appSettings?.appName ?? null} />
    </div>
  )
}

export default App
