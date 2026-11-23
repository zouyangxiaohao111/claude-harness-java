import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { ClipboardEvent, CSSProperties, DragEvent, ReactNode } from 'react'
import { QueuedCommandsBar } from './QueuedCommandsBar'
import { SessionToolsPanel } from './SessionToolsPanel'
import { AppPicker } from './AppPicker'
import { type AppEntry, fetchAppEntries, startApp, toggleAppKey } from '@/api/apps'
import { ImChannelPickerModal } from './ImChannelPickerModal'
import type { ImPlatform } from '@/api/im'
import { CatArtBody } from '../startup/CatArt'
import type { PoppedQueueInput, QueuedCommand } from '@/hooks/useCommandQueue'
import { PERMISSION_MODE_LABELS, PERMISSION_MODE_DESCRIPTIONS, type PermissionMode } from '@/api/types'
import { Icon } from '@/icons'
import type { AttachmentRequest, SessionDto, ChatMessageDto } from '@/api/types'
import { uploadAttachment } from '@/api/chat'
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { readFile, stat } from '@tauri-apps/plugin-fs'
import { COMMAND_ITEMS, isDisabledCommand } from './CommandPalette'
import { GoalHead } from './GoalHead'
import { GoalSetPanel } from './GoalSetPanel'
import { commandApi, type CommandDto } from '@/api/command'
import { projectApi } from '@/api/projects'
import { compactNumber, formatFileSize } from '@/utils/format'
import { AttachFileIcon } from './AttachFileIcon'
import { AttachPreviewModal } from './AttachPreviewModal'
import { contentDedupKey, deliverAttachmentFiles, fileDedupKey } from '@/utils/attachmentDelivery'
import { u8ToBase64 } from '@/utils/base64'
import '@/styles/kb-mention.css'
import { classifyAttachmentPath, pathDedupKey, planPathAttachmentChannel } from '@/utils/pathAttachment'
import { resolveCtxInfo } from '@/utils/contextUsage'
import { effortTag, resolveModelBrand } from '@/utils/modelBrand'
import { useChatStore, type StreamBlock } from '@/stores/chatStore'
import { useShallow } from 'zustand/react/shallow'
import { useComposerBlock } from '@/hooks/useComposerBlock'
import { ComposerBlockHint } from './ComposerBlockHint'

/** 稳定空数组（selector `?? []` 每次返回新引用会触发无限重渲染）。 */
const EMPTY_MESSAGES: ChatMessageDto[] = []
/** 稳定空数组（streams[sessionId] 不存在时回落，防 selector 每次返回新引用触发重渲染）。 */
const EMPTY_BLOCKS: StreamBlock[] = []

/** [流式性能] usageProbe 的空值（无会话时回落；稳定引用 + 标量字段供 useShallow 浅比较）。 */
const EMPTY_USAGE_PROBE = {
  ctxUsed: null as number | null,
  ctxWindow: null as number | null,
  ctxPct: null as number | null,
  turnCacheRate: null as number | null,
  lastSpeedTs: null as number | null,
}

/** Tauri：拖拽被 WebView 拦截，用 onDragDropEvent 拿真实路径 → fs 读文件 → base64/upload */
const IS_TAURI = isTauri()

/**
 * 权限模式图标 · 六种模式各一枚 14×14 描边图标（与工具 chip 同一视觉语言）。
 *
 * <p>WHY 一态一枚而不是所有模式共用盾牌：权限模式是「当前会怎么拦截/放行」，
 * 盾牌只表达"权限"这个大类；计划/接受编辑/绕过/不询问/自动 五者的行为差异很大，
 * 用同一枚图标时用户只能靠文字区分，扫视效率低。
 */
function PermissionModeIcon({ mode, className }: { mode: PermissionMode; className?: string }) {
  const glyph: Record<PermissionMode, ReactNode> = {
    // 默认：盾牌（每个敏感操作询问）
    default: <path d="M7 1.5L12 3.5V6.5C12 9.5 9.8 11.8 7 12.5C4.2 11.8 2 9.5 2 6.5V3.5L7 1.5Z" />,
    // 计划：地图（只读探索、先出方案）
    plan: <path d="M2 3.5L5.5 2L8.5 3.5L12 2V10.5L8.5 12L5.5 10.5L2 12V3.5ZM5.5 2V10.5M8.5 3.5V12" />,
    // 接受编辑：铅笔（自动接受文件编辑）
    acceptEdits: <path d="M9.5 2.2L11.8 4.5L4.9 11.4H2.6V9.1L9.5 2.2ZM8.2 3.5L10.5 5.8" />,
    // 绕过权限：盾牌 + 斜线
    bypassPermissions: <path d="M7 1.5L12 3.5V6.5C12 9.5 9.8 11.8 7 12.5C4.2 11.8 2 9.5 2 6.5V3.5L7 1.5ZM2.6 2.6L11.4 11.4" />,
    // 不询问（未预批准的拒绝）：禁止符
    dontAsk: <path d="M7 1.8A5.2 5.2 0 1 0 7 12.2A5.2 5.2 0 1 0 7 1.8ZM3.4 10.6L10.6 3.4" />,
    // 自动：星芒（classifier 判定）
    auto: <path d="M7 1.5L8.4 5.1L12 6.5L8.4 7.9L7 11.5L5.6 7.9L2 6.5L5.6 5.1L7 1.5Z" />,
  }
  return (
    <svg className={className} viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      {glyph[mode]}
    </svg>
  )
}

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

interface ComposerProps {
  composerText: string
  setComposerText: (v: string) => void
  sendMessage: (attachments?: AttachmentRequest[]) => void
  /**
   * 附件上传通道（默认 {@code uploadAttachment} → 主会话 {@code /attachments/upload}）。
   *
   * <p>知识库对话传入自己的上传端点（`/kb/chat/{sid}/attachments`，上传即解析成正文），
   * 返回的 `contentId` 就是临时附件 id。⛔ 主会话不传 ⇒ 行为零变化。
   */
  attachmentUploader?: (file: File, sessionId: string) => Promise<{ contentId: string; filename: string; size: number }>
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
  /** 知识库场景：四种模式（= 四个内置 KB 专家）· 传了则 + 菜单平铺这些项，替代 simple/full 模式 */
  agentModes?: Array<{ key: string; label: string; title?: string; active: boolean; onPick: () => void }>
  /** 极简外框：隐藏「项目绑定 / 模式 / 运行」三个胶囊（知识库弹窗用） */
  minimalChrome?: boolean
  /** 快速问答：只留「文件 / 知识库」两个入口（+ 菜单 / 浏览器 / 工具面板都不显示） */
  minimalTools?: boolean
  /** 输入框内、textarea 之上的自定义区（知识库场景放已选库 chip；不传 = 无） */
  topChips?: ReactNode
  /**
   * 空态欢迎区的推荐问题（知识库新对话用）。
   *
   * <p>传了就**替换**默认的三个快捷动作（⚡生成实体提取脚本…）—— 那些是主会话的固定提示，
   * 与知识库无关；位置对齐源站：欢迎语下面紧跟「你可以这样问我」+ 换一批 + 问题列表。
   */
  emptySuggestions?: {
    questions: string[]
    onPick: (q: string) => void
    onRefresh: () => void
  }
  /** @ 候选改为这组条目（知识库场景：@ 选知识库，替代项目文件树） */
  atItems?: Array<{ id: string; label: string; hint?: string; sub?: string; kind?: 'kb' | 'file'; kbId?: string }>
  /** @ 选中某条目（知识库场景用来记入选中的库/文件；不传则只按文本插入） */
  onAtPickItem?: (item: { id: string; label: string; hint?: string; sub?: string; kind?: 'kb' | 'file'; kbId?: string }) => void
  /** Mode 切换（App 调 sessionApi.update({bareMode})）· true = simple */
  onModeChange?: (simple: boolean) => void
  /** 上拉抽屉点「模型」行 → 打开模型选择弹窗（App 处理） */
  onOpenModelPicker?: () => void
  /** 上拉抽屉点「推理等级」行 → 打开 EffortModal（App 处理） */
  onOpenEffort?: () => void
  /** 当前会话 id（会话工具面板拉取 · 会话切换时自动刷新） */
  sessionId?: string
  /** 已接入的 IM 渠道（弹窗左栏）。platform 用来显示品牌色徽标，缺省回落灰色「渠」 */
  imInstances?: Array<{ id: string; label: string; platform?: ImPlatform }>
  /** 各渠道下的会话（弹窗第二级：群聊 / 私聊） */
  imChats?: Array<{
    id: string
    name: string
    kind: 'group' | 'p2p'
    instanceId: string
    /** 这个聊天现在接在哪个会话上（null = 空闲）；用来做「已被谁占用」的提示 */
    boundSessionId?: string | null
    /** 占用者的会话标题（人看的名字，不是 sess-xxx） */
    boundSessionTitle?: string | null
    /** V335 · 机器人是否在该会话里（false = 已被移出该群；null/undefined = 未判定） */
    botPresent?: boolean | null
  }>
  /** 已经接到当前会话的 chat id（打勾） */
  imBoundChatIds?: string[]
  /**
   * [A 预热提示] 需要激活但还没激活的 chat id（目前只有微信：没收到过对方消息 ⇒ 没有 context_token）。
   *
   * <p>选择器里给那一项标「未激活」，⛔ 不禁用绑定 —— 只是让人知道手机上暂时收不到通知。
   */
  imInactiveChatIds?: string[]
  /** 把某个会话改接到当前会话 */
  onBindImChat?: (chatId: string) => void
  /** [快速绑定] 把该渠道的机器人默认会话接到当前会话（后端挑默认会话） */
  onQuickBindImInstance?: (instanceId: string) => void
  /** 断开某个会话与当前会话的接线（⛔ 与绑定的区别：绑定=改接，解绑=置空） */
  onUnbindImChat?: (chatId: string) => void
  /** 鼠标进入渠道入口时重拉一次渠道/聊天 —— 否则新同步的聊天在这一轮里看不到 */
  onRefreshImData?: () => void
  /** 打开 IM 频道页（管理渠道 / 新建绑定） */
  onOpenImChannels?: () => void
  /**
   * [V329] 本会话是否显式开启无人值守（会话级状态，渠道右侧常驻开关）。
   *
   * <p>开启 ⇒ 回答直达（Brief）开、IM 工具开、AskUserQuestion 从工具池移除；
   * 退出 ⇒ Ask 恢复，Brief 保持。⛔ 与权限模式不是同一个概念。
   */
  unattended?: boolean
  /** [V329] 本轮仍在运行、模式变更要下一轮才生效（只提示，⛔ 不伪装已生效） */
  unattendedPending?: boolean
  /** [V329] 切换无人值守（App 调 `sessionApi.setUnattended`；无渠道时由 App/后端提示） */
  onToggleUnattended?: () => void
  /** [Q8] 工具面板里「确认关闭回答直达并退出无人值守」后，通知 App 重拉会话模式 */
  onReloadSessionMode?: () => void
  /**
   * 是否允许 AI 以**用户本人名义**在 IM 发消息（全局开关）。
   *
   * <p>与「IM 频道页 › 安全与权限 › 允许以我的名义发消息」是**同一份数据**
   * （后端 im_config 单行）⇒ 两处看到的状态一致。
   *
   * <p>⛔ 别把它做成「有没有入口」式的显隐：这是个**授权**，用户随时可能想开/关，
   * 入口常显、状态用开关本身表达。
   */
  imAllowUserIdentity?: boolean
  /** 切换「以用户名义发消息」（App 负责写后端 + 失败回滚） */
  onToggleImUserIdentity?: () => void
  /** 空态（无消息）：输入框居中 */
  empty?: boolean
  /** F1 · 点击底部 hint-usage（token/金额/当前上下文）→ 打开 UsageCostModal（App 处理） */
  onOpenUsageCost?: () => void
  /** NexusAI in Chrome：工具面板「浏览器控制」空态的检查/安装入口 → 打开 ChromePanel（App 处理） */
  onOpenChromePanel?: () => void
  /** 进入计划模式（claude.exe EnterPlanMode 等价：App 走 `/plan` 命令链路，写会话 permission rules） */
  onEnterPlanMode?: () => void
  /** 退出计划模式（= 切回进入前的权限模式；对齐 CC Shift+Tab，随时可切） */
  onExitPlanMode?: () => void
  /** 「进入计划模式」已排队（turn 运行中点击）：chip 显示「排队中」，⛔ 不代表已生效 */
  planPending?: boolean
  /** 协调者模式（会话级；只认本会话 coordinator_mode，全局开关已移除） */
  coordinatorMode?: boolean
  /** 切换协调者模式（App 调 sessionApi.update({coordinatorMode}) · 写入会话级覆盖） */
  onCoordinatorModeChange?: (on: boolean) => void
  /**
   * [应用选择] 会话级**启动集**（启动才进工具 schema）· 三态：
   * `null` = 回落 `defaultOpen` 集合；`[]` = 全部停止；数组 = 显式启动集。
   * 由 App 持有（PATCH 会话），本组件只渲染 + 上抛。
   */
  enabledApps?: string[] | null
  /** [应用选择] 启动/停止（完整新数组；null = 回落默认集合） */
  onEnabledAppsChange?: (keys: string[] | null) => void
  /**
   * [应用选择] 本轮**强调**（星标）· 只进发送体 `selectedApps`，⛔ 不改 schema。
   * `null/不传` = 本轮无强调；数组 = 强调集合。
   */
  selectedApps?: string[] | null
  /** [应用选择] 星标变化（完整新数组；每轮，不持久化到会话） */
  onAppsChange?: (keys: string[] | null) => void
  /**
   * [compact] 右侧面板（抽屉）展开 ⇒ 输入框只留图标：文字收进 title/tooltip，chip 仍显示。
   *
   * <p>由布局状态驱动（App 传 `rightDrawerOpen`），与窄栏 `@container (max-width:700px)`
   * 同口径但互不替代 —— 宽屏开抽屉时容器未必小于 700px。
   */
  compact?: boolean
  /** 回到底部按钮（离底时显示 · 对齐 deepseek-harness ChatView toBottom · 由 MessageList onScroll 驱动） */
  showToBottom?: boolean
  /** 回到底部点击 → 滚动对话到底（App 触发 MessageList scrollSignal） */
  onScrollToBottom?: () => void
  /** 强行停止所有（输入框上方按钮：一键取消当前流式 + 停全部后台任务 · 替代难触发的双击 Esc）· streaming 时显示 */
  onHardStop?: () => void
  /** local-read 附件模式（前后端同机）：>5MB 拖拽附件传本地 path 由后端读盘，不 upload */
  localRead?: boolean
  /** 当前会话主线程 agent（null/空串 = 默认模式，左下角不显示专家胶囊） */
  currentAgent?: string | null
  /** 清除当前专家（专家胶囊悬停出 × 点击触发；由 App 走 handleAgentChange(null)） */
  onClearAgent?: () => void
  /** 斜杠前缀 chip 的显示别名（召唤技能时带中文显示名；仅当前缀名匹配时生效） */
  slashAlias?: { name: string; display: string } | null
  /** 左下角 + 菜单「专家/技能/连接器」→ 打开技能市场弹窗并切到对应 tab（App 持有 showMarket state） */
  onOpenMarket?: (tab?: 'expert' | 'skill' | 'connector') => void
  /** 设定目标（+ 菜单「目标」→ 面板提交）：App 走 runBuiltin('goal', condition) */
  onSetGoal?: (condition: string) => Promise<void>
  /** 当前会话是否已有目标（+ 菜单里「目标」右侧徽章：设定 / 追踪中） */
  hasGoal?: boolean
  /** 当前目标条件（已有目标时点「目标」→ 面板预填，避免看起来像目标没了） */
  goalCondition?: string | null
  /**
   * 输入框上方右侧的提示节点（App 传 `<PendingPermissionHint />`）。
   *
   * <p>放在 `.composer-inner` 内而不是 App 的 fixed 浮层：随输入区宽度/位置走，
   * 用户视线落在输入框时就能看到「其他会话有工具在等决策」，不用去右下角找。
   */
  pendingHint?: ReactNode
  /**
   * 「子代理运行中」胶囊（App 传 `<SubagentRunningHint />`）· 与「停止所有」同一操作组，
   * 主会话里也能看到后台子代理还在跑，点击打开右栏「运行」tab。
   */
  subagentHint?: ReactNode
  /** 「正在监听」胶囊（App 传 `<MonitorRunningHint />`）· 显示 Monitor 正在盯什么。 */
  monitorHint?: ReactNode
  /**
   * [task-list-inline] TaskCreate 任务清单 dock（App 传 `<TaskListDock />`）。
   * 放在 `.composer-inner` 顶部：清单是状态区，不进入消息流；工具调用仍在对话里滚动。
   */
  taskListHint?: ReactNode
  /**
   * [peer-pending] 待处理投递常驻条（App 传 `<PeerPendingHint />`）。
   *
   * <p>放输入区上方独立一行（不用 `.composer-top-actions` 那一排：本条是整句提醒 +
   * 两个动作，塞进右缘单行会挤掉「回到底部/停止所有」）。pendingCount=0 时 App 传 null。
   */
  peerHoldHint?: ReactNode
  /**
   * [composer-block] 「去处理」审批 → 定位到审批卡片（App 触发对话区滚底，露出
   * PermissionBubble / 内联计划审批）。⛔ 不切会话、不改输入内容。
   */
  onRevealApproval?: () => void
}

// F36：token target 关键词（+500k / +250k / +1m 等），对齐 CC PromptInput 的 findTokenBudgetPositions
const TOKEN_BUDGET_RE = /\+\d+[kmb]?/gi

/**
 * 透明高亮层的降级阈值（沿用 `front/src/markdown/MarkdownText.tsx:126` 的 8000）。
 *
 * <p>超过这个长度就不再渲染 overlay：长串会被「textarea + overlay」整形两遍，
 * 且每键重建整棵高亮子树 ⇒ 输入线性变卡（真机 2 万字实测）。⛔ 不影响发送内容。
 */
const OVERLAY_DEGRADE_CHARS = 8000

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

// 斜杠技能/命令前缀：匹配「开头 /名称 + 尾随空白」= 补全选中后的已选态（输入中的半截不匹配，交给补全菜单）。
// [2026-10-19 用户裁定] 技能选择沿用 /xxxx 文本协议，但前端在高亮层把它可视化成一个 chip（可点删除）。
const SLASH_PREFIX_RE = /^\/([A-Za-z0-9_:.-]+)(\s+)/

// 把输入文本拆成「普通片段(+budget 高亮) + @引用 chip + /技能前缀 chip」
// chip 渲染在「高亮层」（textArea 之上、透明副本）里 → 鼠标 hover/点击命中 chip 本体；
// 视觉样式由 .input-at-chip / .input-slash-chip（globals.css）提供（阴影框 · hover 加深阴影 · 点击移除）。
/**
 * 召唤写入的尾随空格（5 个 = 20px）：pill 的左右内边距（10px + 10px）恰好落在这段空格里，
 * 右缘既不压后续首字、也不留多余空隙。⛔ 与 globals.css `.input-slash-chip-visual--alias` 成对修改。
 */
export const SUMMON_TRAILING_SPACES = '      '

/**
 * 当前输入开头那段「技能/命令 chip」的字符长度（0 = 没有 chip）。
 * <p>chip 是**真实文本**（别名显示名 + 尾随空格，或 /命令名 + 空格），所以退格默认会逐字删 ——
 * 本函数供键盘处理判断「光标是否贴着 chip」，用于整块删除（用户 2026-10-29 裁定）。
 */
function slashChipLength(text: string, slashAlias?: { name: string; display: string } | null): number {
  if (slashAlias?.display && text.startsWith(slashAlias.display + SUMMON_TRAILING_SPACES)) {
    return slashAlias.display.length + SUMMON_TRAILING_SPACES.length
  }
  const m = text.match(SLASH_PREFIX_RE)
  return m ? m[0].length : 0
}

function renderHighlighted(
  text: string,
  onChipRemove?: (token: string) => void,
  slashAlias?: { name: string; display: string } | null,
): ReactNode[] {
  const nodes: ReactNode[] = []
  let last = 0
  // [v6 · 2026-10-19] 两种前缀，chip 展示的**就是真实文本**（宽度一致 ⇒ 后续文字紧贴、光标对齐）：
  // ① 召唤来的技能：输入框真实文本 = 中文显示名（发送时由 App 还原为 /命令名）；
  // ② 手动输入的 /命令：真实文本 = /命令名。
  let chip: { token: string; label: string; fromAlias: boolean } | null = null
  if (slashAlias && slashAlias.display && text.startsWith(slashAlias.display + SUMMON_TRAILING_SPACES)) {
    chip = { token: slashAlias.display + SUMMON_TRAILING_SPACES, label: slashAlias.display, fromAlias: true }
  } else {
    const slash = text.match(SLASH_PREFIX_RE)
    if (slash) chip = { token: slash[0], label: slash[1], fromAlias: false }
  }
  if (chip) {
    // 透明占位 = 真实前缀原文（含 "/" 与尾空格），保证流宽与 textarea 逐字符一致
    const phantomText = text.slice(0, chip.token.length)
    nodes.push(
      <span key="slash-prefix" className="input-slash-chip">
        <span className="input-slash-chip-phantom" aria-hidden="true" style={{ color: 'transparent' }}>
          {phantomText}
        </span>
        {/* 视觉层：absolute 收缩包裹「× + 名称」，背景恰好包裹文字；点击删除挂在这里 */}
        <span
          className={`input-slash-chip-visual${chip.fromAlias ? ' input-slash-chip-visual--alias' : ''}`}
          title="点击移除技能/命令"
          onClick={() => onChipRemove?.(chip!.token)}
        >
          <span className="input-slash-chip-x" aria-hidden="true">
            ×
          </span>
          <span className="input-slash-chip-label">{chip.label}</span>
        </span>
      </span>,
    )
    last = chip.token.length
  }
  for (const m of text.matchAll(AT_TOKEN_RE)) {
    const idx = m.index ?? 0
    if (idx < last) continue
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

const SUG_WRAP: React.CSSProperties = { width: '100%', maxWidth: 760, margin: '0 auto', padding: '0 4px' }
const SUG_HEAD: React.CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '4px 0 8px' }
const SUG_TITLE: React.CSSProperties = { fontSize: 13, fontWeight: 600, color: '#1f2329' }
const SUG_REFRESH: React.CSSProperties = {
  border: 0, background: 'transparent', color: '#1664ff', fontSize: 12, cursor: 'pointer', padding: '2px 4px',
}
const SUG_GRID: React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }
const SUG_CARD: React.CSSProperties = {
  padding: '10px 12px', borderRadius: 10, border: '1px solid #e5e6eb', background: '#fff',
  fontSize: 12.5, color: '#1f2329', cursor: 'pointer', textAlign: 'left', lineHeight: 1.45,
  display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
  overflow: 'hidden', whiteSpace: 'normal', minHeight: 48, wordBreak: 'break-word',
}

export function Composer({composerText, setComposerText, sendMessage, attachmentUploader, showToast, streaming, onStop, queuedCommands, popEditable, boundProjectName, boundProjectId, onSelectProject, currentModel, permissionMode, onPermissionModeChange, effortLevel, ultracodeEnabled, bareMode, onModeChange, onOpenModelPicker, onOpenEffort, empty, sessionId, onOpenUsageCost, onOpenChromePanel, onEnterPlanMode, onExitPlanMode, planPending, coordinatorMode, onCoordinatorModeChange, enabledApps, onEnabledAppsChange, selectedApps, onAppsChange, compact, showToBottom, onScrollToBottom, onHardStop, localRead, currentAgent, onClearAgent, slashAlias, onOpenMarket, onSetGoal, hasGoal, goalCondition, pendingHint, subagentHint, monitorHint, taskListHint, peerHoldHint, agentModes, minimalChrome, atItems, onAtPickItem, minimalTools, topChips, emptySuggestions, imInstances, imChats, imBoundChatIds, imInactiveChatIds, onBindImChat, onQuickBindImInstance, onUnbindImChat, onRefreshImData, onOpenImChannels, imAllowUserIdentity, onToggleImUserIdentity, onRevealApproval, unattended, unattendedPending, onToggleUnattended, onReloadSessionMode}: ComposerProps) {
  // 模型胶囊只显两字母别名（见 utils/modelBrand）；完整模型名放在下拉与 tooltip 里
  // [composer-block] 统一发送封锁判据：当前会话待确认消息 > 0 或有未处理审批/Ask 提问。
  //   ⛔ 只封锁发送；面板/滚动/审批卡/切会话照旧（不使用遮罩）。判据与会话严格绑定。
  const blockState = useComposerBlock(sessionId ?? null)
  const composerBlocked = blockState.blocked
  // 待确认消息的原因已由 App 传入的 peerHoldHint（PeerPendingHint：含「去处理」+「全部拒绝」）
  //   常驻条表达；这里只在没有该条时补一条等价提示（避免同一原因出现两条）。
  const showBlockHint = blockState.pendingApprovals > 0
    || (blockState.peerPending > 0 && !peerHoldHint)
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
  // [流式性能 2026-11-18 · 对齐 dsh 节点级订阅] 原实现订阅整个 `streams[sid]` 数组
  //   （appendChunk 每帧换引用）⇒ 这个 1900 行的输入框组件**每帧整块重渲**。
  //   改为 useShallow 订阅「底部只需要的 5 个标量」：文本增长帧字段不变 ⇒ 组件不重渲。
  //   扫描逻辑与原先的 usageScan 同源（msgs 尾部 + live 块尾部，从后往前取最新快照）。
  const usageProbe = useChatStore(useShallow((s) => {
    const sid = sessionId
    if (!sid) return EMPTY_USAGE_PROBE
    const scan = [
      ...(s.messages[sid] ?? EMPTY_MESSAGES),
      ...(s.streams[sid] ?? EMPTY_BLOCKS),
    ] as Array<ChatMessageDto & StreamBlock>
    const ctx = resolveCtxInfo(scan)
    let turnCacheRate: number | null = null
    let lastSpeedTs: number | null = null
    for (let i = scan.length - 1; i >= 0 && (turnCacheRate === null || lastSpeedTs === null); i--) {
      const m = scan[i]!
      if (turnCacheRate === null && m.cacheHitRate != null) turnCacheRate = m.cacheHitRate
      if (lastSpeedTs === null && (!m.role || m.role === 'assistant')) {
        const outputTokens = m.usage?.output_tokens ?? m.outputTokens ?? 0
        const decodeMs = m.usage?.decode_ms ?? m.decodeMs ?? 0
        if (outputTokens > 0 && decodeMs > 0) lastSpeedTs = Math.round((outputTokens * 1000) / decodeMs)
      }
    }
    return {
      ctxUsed: ctx?.used ?? null,
      ctxWindow: ctx?.window ?? null,
      ctxPct: ctx?.pct ?? null,
      turnCacheRate,
      lastSpeedTs,
    }
  }))
  // [D1 usage-source] 底部数字只统计「用户自己的请求」：同会话后台任务（cron 调度 / 任务通知 run）
  //   复用同一 stream topic 推 message.usage（实测 63% 事件出自 cron-idle-* 线程）→ 不过滤则
  //   footer 的「缓存% / 当前上下文 / t/s」会被后台任务那轮顶掉（数字与用户操作无关地跳变）。
  //   来源由后端打标（MessageUsageEvent.source），经块/消息的 usageSource 透传；缺省（旧帧/旧消息/
  //   complete 兜底）按 user 计 —— 向后兼容，不得静默隐藏既有数据。
  //   [R1 · F5/重拉态] DB 行没有 usageSource（实时事件才有）→ 用 DB 判据派生：后台来源 flow = 该轮
  //   user 行 is_meta=true（cron/通知注入行，V51）。集合只依赖 msgs（user 行来自重拉），故 F5 后仍成立。
  // [口径反转 2026-10-10 用户裁定] 删除「只统计用户来源」过滤（原 D1 口径作废）：cron/任务通知
  //   唤醒的**主会话轮**（主会话收到消息并回复了）属于主会话对话，底部数字应随之刷新——「后台轮
  //   顶掉」在用户视角下正是当前活动；真正要排除的「子代理/fork 的 usage」结构性不进本流
  //   （不推 message.usage），无需过滤。
  const ctxInfo = useMemo(
    () => (usageProbe.ctxUsed != null && usageProbe.ctxWindow != null
      ? { used: usageProbe.ctxUsed, window: usageProbe.ctxWindow, pct: usageProbe.ctxPct }
      : null),
    [usageProbe.ctxUsed, usageProbe.ctxWindow, usageProbe.ctxPct],
  )
  // F1 · 缓存利用率：**消费后端权威命中率**（事件顶层 `cache_hit_rate` · **0-1 比率**）。
  //   [V101 口径上收] 删除前端「按 provider type 自行分派算法」的旧实现（anthropic → cr/(input+cr+cc)；
  //   openai/deepseek → cr/input）：该分派已在后端 ContextUsageCalculator.computeCacheHitRate 单点，
  //   前端重复实现必然漂移（历史教训：自定义命名 ant provider 名判 → 算出 3383%）。
  //   取数 = 最近一条带该值的消息/流式块（该条 assistant **自身**命中率，message.usage 事件透传）。
  //   缺值（后端 NON_NULL 省略）→ 不显示，⛔ 不臆造 0（仓内约定见 utils/subagentStats.ts:14）。
  const turnCacheRate = usageProbe.turnCacheRate
  // [缓存%双显示] 第二个数：**会话累计**命中率。优先级：
  //   ① 最近一条 assistant 消息上的 `sessionCacheHitRate`（message.complete 事件顶层 `cache_hit_rate` 透传 ·
  //      实时最新 · 0-1 比率）；
  //   ② F5/重拉态：消息行来自 DB（无该字段）→ 回退**当前活动会话** SessionDto.cacheHitRate
  //      （后端由 sessions.model_usage_json 派生 · 与 sessions 列表同源刷新，见 UsageCostModal 的会话来源）。
  //   ⛔ 不回退 modelUsage 自算（本次已删除前端协议分派）；两者皆缺 → null → 整段不渲染（不臆造 0）。
  const sessionCacheRate = useMemo(() => {
    for (let i = msgs.length - 1; i >= 0; i--) {
      const r = msgs[i]?.sessionCacheHitRate
      if (r != null) return r
    }
    return sessionUsage?.cacheHitRate ?? null
  }, [msgs, sessionUsage])
  // 两个口径各自独立判定（只有一个也照常显示那一个）；两个都缺 → 整个缓存 span 不渲染。
  //   warn 配色沿用旧判据（< 50% 视为偏低）· 基数 = 本回合优先、退累计。
  const cacheWarnBase = turnCacheRate ?? sessionCacheRate
  const cacheText = [
    turnCacheRate != null ? `缓存 ${Math.round(turnCacheRate * 100)}%（本回合）` : null,
    sessionCacheRate != null ? `${Math.round(sessionCacheRate * 100)}%（累计）` : null,
  ].filter((s): s is string => s != null).join(' · ')
  // F4 · 最近一条 assistant 消息 t/s 速度（output_tokens × 1000 / decode_ms · footer 展示）。
  //   扫描源 = [...msgs, ...liveBlocks]：live 块在 message.usage（assistant 流式结束）即挂 usage/decode_ms，
  //   速率在块转消息（complete）前即可读 —— 多轮 agent 每条 assistant 结束实时刷新，不再等 turn 完成落库。
  //   live 块无 role（隐含 assistant）；decode_ms 语义 = 首 token→完成整段计时，故速率是「每段输出收尾即跳」。
  //   [D1 usage-source] 同源于 message.usage → 与缓存%/上下文条统一只统计 user 来源（底部数字口径一致）。
  const lastSpeedTs = usageProbe.lastSpeedTs
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  /**
   * [IME 守卫] 输入法组合态按 Enter 只确认候选，⛔ 不能发送（Mac 中文输入法误发）。
   *
   * <p>Safari/WKWebView 的 `compositionend` 可能**早于** Enter 的 keydown ⇒ 单靠
   * `isComposing` 会漏；再记一个结束时间戳，短时间内到达的 Enter 也吞掉。
   */
  const composingRef = useRef(false)
  const compositionEndAtRef = useRef(0)
  /** [长文本性能] 自动高度合并后的 rAF 句柄（每键最多一次 read scrollHeight） */
  const heightRafRef = useRef<number | null>(null)
  /** [模型胶囊] 自定义快速 tooltip（原生 title 约 500ms 才出，用户要求 ≤100ms） */
  const [mepTipOpen, setMepTipOpen] = useState(false)
  const mepTipTimer = useRef<number | null>(null)
  useEffect(() => () => {
    if (mepTipTimer.current != null) window.clearTimeout(mepTipTimer.current)
  }, [])
  const modelBrand = useMemo(() => resolveModelBrand(currentModel), [currentModel])
  const modelEffortTag = effortTag(effortLevel as string | null | undefined, ultracodeEnabled)
  const highlightRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [attachments, setAttachments] = useState<PendingAttachment[]>([])
  /** 待发附件预览（点击文件卡片 / 图片缩略图以外的文件类型） */
  const [previewAtt, setPreviewAtt] = useState<PendingAttachment | null>(null)
  // 去重：Tauri onDragDropEvent 与浏览器 drop 可能双触发，同一份文件只添加一次。
  // ⭐ 批 ATT-DEDUP-KEY：键**不是文件名** —— 内容 md5（图片/≤5MB）/ 名+大小（大文件 upload）/
  //   完整路径（path 附件）。详见 `utils/attachmentDelivery.ts` 的「去重键 = 身份，不是名字」段。
  //   ⛔ 三个释放点都必须用 chip 上的 `dedupKey`（见下面 chip 移除、清空、doSend）。
  const addedKeysRef = useRef<Set<string>>(new Set())
  // [跨会话附件隔离 2026-11-16] 待发附件是「当前会话的草稿」，⛔ 不得跟着切到另一个会话。
  //   组件本身在切换会话时不会重挂载（挂载方是 App，无 key）⇒ 必须按 sessionId 显式清空 + 释放去重键。
  //   ⚠️ 发送时 doSend 已先 setAttachments([])，所以「新建会话 → 首条发送 → sessionId 落地」这
  //   一步变化不会误清用户正在编辑的附件（那时 attachments 本来就空）。
  useEffect(() => {
    setAttachments([])
    addedKeysRef.current.clear()
  }, [sessionId])
  // 点击缩略图 → 放大预览（lightbox）
  const [zoomImg, setZoomImg] = useState<string | null>(null)
  // Mode 下拉（受控 · V33 bare_mode：simple=精简 true / full=完整 false）
  const mode: 'simple' | 'full' = bareMode ? 'simple' : 'full'
  const [modeOpen, setModeOpen] = useState(false)
  // 权限模式抽屉（对齐模型胶囊 model-effort-pill 交互）
  const [permOpen, setPermOpen] = useState(false)
  /** 渠道弹窗是否打开（点击「渠道」按钮打开独立的居中弹窗，不再靠 hover） */
  const [chanMenuOpen, setChanMenuOpen] = useState(false)
  // [V329] 无人值守入口的「请先配置渠道」引导：点入口但还没有任何已接入渠道时置真，
  //   同时打开既有渠道弹窗（⛔ 入口不做 disabled 死按钮，也不静默）。
  //
  // [2026-11-21 用户实测「这个字不会消失，弹出来就不消失了」] 引导只允许存在于
  //   「本次点击 → 用户处理完这条引导」的时间窗内，不是一个常驻状态：
  //   · 文案本身由既有 toast 统一呈现（6s，见 hooks.ts useToastDispatcher），不另设秒数；
  //   · 弹窗里的说明条由本状态驱动，**随弹窗关闭 / 配置成功 / 切会话**立即清掉；
  //   · 删掉常驻在开关旁的行内气泡（那正是不会消失的那一处）。
  const [uoGuide, setUoGuide] = useState(false)
  // 渠道接好之后自动清掉引导（不必重新点一次入口）
  useEffect(() => {
    if (uoGuide && (imInstances?.length ?? 0) > 0) setUoGuide(false)
  }, [uoGuide, imInstances])
  // 切会话 ⇒ 上一会话的引导不得跟过来（Composer 在切会话时不重挂载）
  useEffect(() => {
    setUoGuide(false)
  }, [sessionId])
  // 对话进行中（有消息/流式）禁止切换模式，仅空会话（新会话）可切换（对齐「文件选择」绑定规则）
  const modeLocked = !empty
  // 输入 `/` → 命令即时提示（slash 补全）
  const [cmdIndex, setCmdIndex] = useState(0)
  // 后端技能/命令（GET /api/command · 含 skills）：挂载拉取，失败静默回落本地 COMMAND_ITEMS
  const [remoteCommands, setRemoteCommands] = useState<CommandDto[]>([])
  useEffect(() => {
    let alive = true
    // [TL-W1 P4] sessionId 直传后端（REST 线程按会话解析绑定项目 → project 级命令进补全）；
    //   切会话 → 重新拉取（避免上一个会话的项目命令滞留；同源 /skills 端点）。
    commandApi.list(false, sessionId ?? undefined).then((cs) => { if (alive) setRemoteCommands(cs) }).catch(() => {})
    return () => { alive = false }
  }, [sessionId])
  // 打开斜杠菜单时重新拉取：用户可能在应用运行期间手动安装/删除技能（拷到 ~/.nexusai/skills），
  // 只按 sessionId 拉一次会让补全一直停在旧列表。reload=true 让后端同时清 SkillRegistry memoize
  // —— 仅 rescan DB 不足以看到新放的技能。
  const slashOpen = composerText.startsWith('/') && !composerText.includes(' ')
  const prevSlashOpenRef = useRef(false)
  useEffect(() => {
    const opened = slashOpen && !prevSlashOpenRef.current
    prevSlashOpenRef.current = slashOpen
    if (!opened) return
    let alive = true
    commandApi.list(true, sessionId ?? undefined)
      .then((cs) => { if (alive) setRemoteCommands(cs) })
      .catch(() => { /* 静默：保留旧列表仍可输入 */ })
    return () => { alive = false }
  }, [slashOpen, sessionId])
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
  const atActive = !!atTailIndex && (!!boundProjectId || !!atItems?.length) && !streaming
  const [atIndex, setAtIndex] = useState(0)
  /** 点「@ 知识库」按钮直接展开面板（源站行为：点按钮只弹窗，不往输入框写 @） */
  const [atOpen, setAtOpen] = useState(false)
  /** @ 面板内的搜索词（对齐源站 MentionSelector 的 .mention-search；空则用输入框 @ 后的文本过滤） */
  const [atSearch, setAtSearch] = useState('')
  /** 知识库面板的二级分类：null = 一级（知识库 / 文件两行） */
  const [atGroup, setAtGroup] = useState<null | 'kb' | 'file'>(null)
  /** 知识库 @ 面板（两级）是否展开 · 关闭时回到一级 */
  const kbPanelOpen = !!atItems?.length && (atOpen || !!atTailIndex) && !streaming
  const kbGroupCounts = useMemo(() => {
    const list = atItems ?? []
    return {
      kb: list.filter((i) => (i.kind ?? 'kb') === 'kb').length,
      file: list.filter((i) => i.kind === 'file').length,
    }
  }, [atItems])
  const atMenuRef = useRef<HTMLDivElement>(null)
  /**
   * 点「@ 知识库」面板外部时收起。
   *
   * <p>⚠️ 之前只有「选中某一项」才关门（{@link applyAtPick}），点别处（消息区、空白、
   * 别条工具栏）面板会一直浮着，用户必须选一个或手动再点按钮才能关 —— 2026-11-14 用户报。
   *
   * <p>排除三类内部点击：面板本身、输入框（点输入框是继续打字，不该关）、
   * 「@ 知识库」触发按钮（它自己是 toggle，重复处理会让面板点了没反应）。
   */
  useEffect(() => {
    if (!kbPanelOpen || !atOpen) return
    const onDocMouseDown = (e: MouseEvent) => {
      const t = e.target as Node | null
      if (!t) return
      // ⭐ 判定基准：**只认面板自己的 DOM**。点输入框也算外部（用户 2026-11-14 明确要求）。
      //   唯一例外是「@ 知识库」触发按钮 —— 它自己是 toggle，若在这里先关掉，
      //   随后 onClick 又会 `setAtOpen(!v)` 打开，表现为「点按钮关不掉」。
      if (atMenuRef.current?.contains(t)) return
      const el = t as HTMLElement
      if (el.closest?.('[data-at-trigger]')) return
      setAtOpen(false)
      setAtGroup(null)
      setAtSearch('')
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [kbPanelOpen, atOpen])
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
    // 知识库面板：一级只有「知识库 / 文件」两行，二级才出候选
    if (atItems?.length && (atOpen || atTailIndex)) {
      if (!atGroup) return null
      const q = (atSearch.trim() || (atTailIndex?.prefix ?? '')).toLowerCase()
      const list = atItems
        .filter((i) => (i.kind ?? 'kb') === atGroup && (!q || i.label.toLowerCase().includes(q)))
        .slice(0, 40).map((i) => i.label)
      return list.length ? list : null
    }
    if (!atActive || !atTailIndex) return null
    const q = (atSearch.trim() || atTailIndex.prefix).toLowerCase()
    // 知识库场景：候选是知识库名（⛔ 不走项目文件树 —— 弹窗没有绑定项目）
    if (atItems?.length) {
      const list = atItems.filter((i) => !q || i.label.toLowerCase().includes(q)).slice(0, 40).map((i) => i.label)
      return list.length ? list : null
    }
    if (projFiles === null) return null
    const list = projFiles.filter((p) => !q || p.toLowerCase().includes(q)).slice(0, 40)
    return list.length ? list : null
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atActive, projFiles, atTailIndex, atItems, atOpen, atGroup, atSearch])
  // 候选出现/变化 → 重置选中；随 ↑↓ 联动滚动
  useEffect(() => {
    if (atMatches && !hadAtRef.current) setAtIndex(0)
    hadAtRef.current = !!atMatches
  }, [atMatches])
  useEffect(() => {
    if (!atMatches || !atMenuRef.current) return
    const el = atMenuRef.current.querySelector<HTMLElement>(`[data-idx="${atIndex}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [atIndex, atMatches])
  /** 选中候选 → 把行尾 @token 替换为 @path（保留前导空白/标点分隔符） */
  const applyAtPick = (path: string) => {
    const hit = atItems?.find((i) => i.label === path)
    if (hit) {
      // 知识库项：选中 = 一个 chip（宿主渲染），⛔ 不往输入框写 @库名
      onAtPickItem?.(hit)
      if (atTailIndex) {
        const head = atTailIndex.segStartAbs >= 0 ? composerText.slice(0, atTailIndex.segStartAbs + 1) : ''
        setComposerText(head.replace(/\s+$/, head.trim() ? ' ' : ''))
      }
      // 选中任意一项（知识库 / 文件）面板立即收起：它盖在输入框上方，
      // 用户选完就想直接写字（2026-11-14 用户报「选了文件面板不消失」）。
      // 想继续多选再按一次 @ 重新打开即可。
      setAtOpen(false)
      setAtIndex(0)
      textareaRef.current?.focus()
      return
    }
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
  // 左下角 + 菜单（添加文件 / 模式 / 专家 / 技能 / 连接器）：点击外部收起
  const [plusOpen, setPlusOpen] = useState(false)
  // 「模式」子菜单展开态（simple/full 两个可选项 · 关菜单时一并复位）
  const [plusModeOpen, setPlusModeOpen] = useState(false)
  // 设定目标面板（+ 菜单「目标」→ 输入框顶部就地变面板；提交/取消后关闭）
  const [goalSetOpen, setGoalSetOpen] = useState(false)
  // 切会话 ⇒ 收起面板：Composer 是单实例，不重置的话 A 会话打开的面板会跟到 B（多会话隔离铁律）
  useEffect(() => {
    setGoalSetOpen(false)
  }, [sessionId])
  const plusRef = useRef<HTMLDivElement>(null)
  /** 关闭 + 菜单：子菜单展开态一并复位，避免下次打开仍留展开态。 */
  const closePlusMenu = () => { setPlusOpen(false); setPlusModeOpen(false) }
  useEffect(() => {
    if (!plusOpen) return
    const onDoc = (e: MouseEvent) => {
      if (plusRef.current && !plusRef.current.contains(e.target as Node)) closePlusMenu()
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [plusOpen])

  // 知识库场景：模式胶囊自己弹模式列表（+ 菜单里不再放「模式」这一项）。
  // 两个菜单互斥：开一个就关另一个，否则叠在一起。
  const [modeMenuOpen, setModeMenuOpen] = useState(false)
  const modeMenuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!modeMenuOpen) return
    const onDoc = (e: MouseEvent) => {
      if (modeMenuRef.current && !modeMenuRef.current.contains(e.target as Node)) setModeMenuOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [modeMenuOpen])

  // 会话工具面板（功能2：禁用/恢复 · 会话级临时禁用）；鼠标移出组合延时收起 / Esc 逐层收
  const [toolsOpen, setToolsOpen] = useState(false)
  const toolsPanelRef = useRef<HTMLDivElement>(null)
  /** hover 关闭延时句柄（鼠标穿越 chip↔一级↔二级的 7px 缝隙时不误收） */
  const hoverCloseTimer = useRef<number | null>(null)
  useEffect(() => {
    if (!toolsOpen) return
    const onDoc = (e: MouseEvent) => {
      if (toolsPanelRef.current && !toolsPanelRef.current.contains(e.target as Node)) setToolsOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [toolsOpen])
  // 卸载时清掉未执行的延时，避免对已卸载组件 setState
  useEffect(() => () => {
    if (hoverCloseTimer.current != null) window.clearTimeout(hoverCloseTimer.current)
  }, [])

  // ---- 应用选择（连接器多选 · 本轮随消息携带 `selectedApps`） ----
  // 选中集**只由上层持有**（App：`useState` + localStorage 记忆 + 拼进请求体）。
  // ⛔ 本组件不存第二份：没有上层接线时连入口都不渲染。
  //
  // [三态语义 · 2026-11-23 用户截图纠正] `selectedApps === null` **不是「什么都没选」**，
  //   而是「不裁剪 = 所有可用应用默认开启」。旧实现把所有行按 OFF 渲染 ⇒ 界面骗人
  //   （codegraph 明明可用却显示关着）。⇒ 行状态一律按**有效选中集**渲染。
  //   chips / 角标仍只表达**显式选择**（null = 没偏离默认，不堆一屏 chip）。
  const appSelKeys: string[] = selectedApps ?? []
  const [appsOpen, setAppsOpen] = useState(false)
  /** 目录：null = 未拉过（惰性，首次打开面板或有记忆选择时才拉） */
  const [appOptions, setAppOptions] = useState<AppEntry[] | null>(null)
  const [appsLoading, setAppsLoading] = useState(false)
  const [appsError, setAppsError] = useState<string | null>(null)
  /** 正在启动的不可用应用（后端 canStart）与失败原因（按 key） */
  const [appsStarting, setAppsStarting] = useState<Set<string>>(new Set())
  const [appsStartError, setAppsStartError] = useState<Record<string, string>>({})
  const appsWrapRef = useRef<HTMLDivElement>(null)
  /** 并发去重（StrictMode 双跑 / 连点重试）：同一时刻只跑一个目录请求 */
  const appsLoadRef = useRef<Promise<void> | null>(null)
  /** 会话未设置启动集（null）时 = 后端标了 defaultOpen 且可用的应用（当前只有 MCP 在应用层） */
  const defaultAppKeys = useMemo(
    () => (appOptions ?? []).filter((a) => a.usable && a.defaultOpen).map((a) => a.key),
    [appOptions],
  )
  /** 有效**启动集**（行开关的真源）：`enabledApps === null` ⇒ 默认集合 */
  const startedAppKeys = useMemo(
    () => (enabledApps ?? defaultAppKeys),
    [enabledApps, defaultAppKeys],
  )
  /** **本轮强调**（点行切换）：只进发送体，不改 schema */
  const emphasizedKeys = useMemo(() => (selectedApps ?? []), [selectedApps])
  /** 入口与 chip 只在「上层真的接收选择」时渲染（App 传了其一） */
  const showAppSelection = !minimalTools
    && (onAppsChange !== undefined || selectedApps !== undefined
      || onEnabledAppsChange !== undefined || enabledApps !== undefined)
  /**
   * 提交**启动集**（三态规范化）：目标集合恰好等于默认集合 ⇒ null（回落后端 defaultOpen）；
   * 否则显式数组（`[]` = 全部停止）。
   *
   * <p>⚠️ 基线用「默认集合」而非「全部可用集合」：把默认关闭的应用启动属于偏离默认，
   * 必须落成显式名单（否则被当成 null 又回落成默认关）。
   */
  const commitStarted = (keys: string[]) => {
    const nextSet = new Set(keys)
    const equalsDefault = defaultAppKeys.length > 0
      && defaultAppKeys.every((k) => nextSet.has(k))
      && nextSet.size === defaultAppKeys.length
    onEnabledAppsChange?.(equalsDefault ? null : keys)
  }
  /** 独立开关：启动/停止（写会话级启动集）；启动 ⇒ 自动加入强调，停止 ⇒ 从强调移除 */
  const toggleStart = (key: string) => {
    const base: string[] = enabledApps ?? defaultAppKeys
    const wasStarted = base.includes(key)
    commitStarted(wasStarted ? base.filter((k) => k !== key) : [...base, key])
    const em = selectedApps ?? []
    if (wasStarted) {
      // 停止 ⇒ 从强调集合移除（停了的应用再"强调"没有意义）
      if (em.includes(key)) onAppsChange?.(em.filter((k) => k !== key))
    } else if (!em.includes(key)) {
      // 启动 ⇒ 默认选中（自动加入强调集，chip 立即出现）
      onAppsChange?.([...em, key])
    }
  }
  /** 点行：选择/取消**本轮强调**（只写发送体 selectedApps，⛔ 不改 schema、不启停） */
  const toggleEmphasis = (key: string) => {
    onAppsChange?.(toggleAppKey(selectedApps ?? [], key))
  }
  /** 启动一个不可用应用（真调后端 MCP start）· 成功重拉目录；失败原样挂到该行，⛔ 不假成功 */
  const startAppByKey = useCallback(async (key: string) => {
    const app = (appOptions ?? []).find((a) => a.key === key)
    if (!app?.serverId) return
    setAppsStarting((prev) => new Set(prev).add(key))
    setAppsStartError((prev) => {
      if (!(key in prev)) return prev
      const next = { ...prev }
      delete next[key]
      return next
    })
    try {
      await startApp(app.serverId, sessionId ?? null)
      setAppOptions(await fetchAppEntries())
    } catch (e) {
      setAppsStartError((prev) => ({ ...prev, [key]: e instanceof Error ? e.message : String(e) }))
    } finally {
      setAppsStarting((prev) => {
        const next = new Set(prev)
        next.delete(key)
        return next
      })
    }
  }, [appOptions, sessionId])

  const loadApps = useCallback(async () => {
    if (appsLoadRef.current) return appsLoadRef.current
    const task = (async () => {
      setAppsLoading(true)
      setAppsError(null)
      try {
        setAppOptions(await fetchAppEntries())
      } catch (e) {
        // 真实原因上屏（面板里给「重试」），⛔ 不糊成「加载失败」
        setAppsError(e instanceof Error ? e.message : String(e))
      } finally {
        setAppsLoading(false)
        appsLoadRef.current = null
      }
    })()
    appsLoadRef.current = task
    return task
  }, [])
  // 有记忆选择时挂载即拉一次目录（只为 chip 显示名字；失败不影响选中集与发送）
  useEffect(() => {
    if (appSelKeys.length === 0 || appOptions !== null) return
    void loadApps()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 只按「首次是否需要」判定，选中集后续变化不重复拉
  }, [])
  // 点击外部收起（与 plus-menu / tools-panel 同一套 mousedown 判据）
  useEffect(() => {
    if (!appsOpen) return
    const onDoc = (e: MouseEvent) => {
      if (appsWrapRef.current && !appsWrapRef.current.contains(e.target as Node)) setAppsOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [appsOpen])
  /** 目录已拉过且一条都没有 ⇒ 视为「没有连接器」：入口置灰，点了只给说明（⛔ 不开空面板装样子） */
  const appCatalogEmpty = appOptions !== null && appOptions.length === 0
  const appByKey = useMemo(() => {
    const m = new Map<string, AppEntry>()
    for (const a of appOptions ?? []) m.set(a.key, a)
    return m
  }, [appOptions])

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
        upload: (file, sid) => (attachmentUploader ?? uploadAttachment)(file, sid),
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
          const r = await (attachmentUploader ?? uploadAttachment)(file, sessionId)
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

  /** + 菜单「模式」：展开 simple/full 两个可选项（不再盲切 · 同一 modeLocked 锁定规则）。 */
  const pickModeFromMenu = (m: 'simple' | 'full') => {
    if (modeLocked) {
      showToast('对话进行中不可切换模式（仅新会话可切换）', 'info')
      return
    }
    onModeChange?.(m === 'simple')
    showToast(`已切换到 ${m} 模式`, 'info')
    closePlusMenu()
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
    // [composer-block] 兜底：任何路径（按钮/Enter/快捷）都不得越过封锁
    if (composerBlocked) return
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
  // 同时 textarea 随内容自动增高（min 22px → max 200px）。
  // [长文本性能] 高度测量合并到**一个 rAF**：`height='auto'` + 读 `scrollHeight` 是强制回流，
  //   原来每键同步跑一次（长串上可达几十 ms）；合并后每帧最多一次，且高度没变就不写回。
  useLayoutEffect(() => {
    if (heightRafRef.current != null) cancelAnimationFrame(heightRafRef.current)
    heightRafRef.current = requestAnimationFrame(() => {
      heightRafRef.current = null
      const ta = textareaRef.current
      if (!ta) return
      syncHighlight()
      const prev = ta.style.height
      ta.style.height = 'auto'
      const next = `${Math.min(Math.max(ta.scrollHeight, 22), 200)}px`
      if (prev !== next) ta.style.height = next
      else ta.style.height = prev
    })
    return () => {
      if (heightRafRef.current != null) {
        cancelAnimationFrame(heightRafRef.current)
        heightRafRef.current = null
      }
    }
  }, [composerText])

  return (
    <div className={`composer${empty ? ' composer-empty' : ''}${composerBlocked ? ' composer-blocked' : ''}${compact ? ' compact' : ''}`} onDrop={onDrop} onDragOver={onDragOver} data-composer-blocked={composerBlocked ? 'true' : undefined}>
      {/* 空态 hero：logo 动画 + 标题 + quick-actions（对齐主界面原型） */}
      {empty && (
        <div className="welcome">
          <CatArtBody className="welcome-logo" />
          <div className="hero-title">你好，今天有什么可以帮忙的</div>
          {emptySuggestions && emptySuggestions.questions.length > 0 ? (
            <div style={SUG_WRAP}>
              <div style={SUG_HEAD}>
                <span style={SUG_TITLE}>你可以这样问我</span>
                <button type="button" style={SUG_REFRESH} title="换一批" onClick={emptySuggestions.onRefresh}>换一批</button>
              </div>
              <div style={SUG_GRID}>
                {emptySuggestions.questions.map((q) => (
                  <button key={q} type="button" style={SUG_CARD} title={q} onClick={() => { if (!composerBlocked) emptySuggestions.onPick(q) }}>{q}</button>
                ))}
              </div>
            </div>
          ) : (
            <div className="quick-actions">
              <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>⚡ 生成实体提取脚本</div>
              <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>📝 优化代码注释</div>
              <div className="action-card" onClick={() => showToast('快捷动作开发中', 'info')}>🔍 检查架构逻辑</div>
            </div>
          )}
        </div>
      )}
      <div className="composer-inner">
        {/* [peer-pending] 待处理投递常驻条：pendingCount=0 时 App 不传（整条不渲染）。 */}
        {peerHoldHint}
        {/* [composer-block] 暂不能发送的明确提示（原因 + 去处理入口）；无待处理时整条不渲染。 */}
        {showBlockHint && (
          <ComposerBlockHint
            peerCount={peerHoldHint ? 0 : blockState.peerPending}
            approvalCount={blockState.pendingApprovals}
            // 待处理入口统一由 App 的 PeerPendingHint 提供（跳会话+定位卡片）；
            // ⛔ 这里不再自带 openPanel（列表不再是来信面板）。
            onRevealApproval={onRevealApproval}
          />
        )}
        {/* F19/#3 排队命令条（输入框上方，后端 B5 未接恒空隐藏） */}
        {/* 「编辑」按钮与 Esc 走同一个入口（文本 + 附件一起拉回） */}
        {/* [goal V253 形态重做] 移进 .composer-inner —— 原先在它<b>外面</b>（兄弟节点）⇒ 通栏， */}
        {/*   与缩进 85% 的输入框左缘差 42px / 右缘差 128px（几何错位，不是配色问题）。 */}
        <QueuedCommandsBar queuedCommands={queuedCommands} onEdit={() => void handlePopEditable()} />
        {/* 输入框上方一行：左=项目绑定，右=模型选择器（设计稿 v7） */}
        <div className="composer-top">
          {!minimalChrome && (
          <div
            className={`project-binder${modeLocked ? ' locked' : ''}`}
            title={modeLocked
              ? '对话进行中不可切换项目（仅新会话可切换）'
              : boundProjectName
                ? `选择项目 · ${boundProjectName}`
                : '选择项目'}
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
          )}
          {/* Mode 下拉（原型）：simple/full · 对话进行中锁定（仅新会话可切换） */}
          {!minimalChrome && (
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
            <span>{mode} 模式</span>
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
          )}
          {/* [task-list-inline] 任务胶囊：与子代理/监控同排，样式复用监控的蓝色呼吸胶囊 */}
          {!minimalChrome && taskListHint}
          {/* [subagent-hint] 子代理运行提示：紧挨 full 模式右侧（位置在视线区，不再放右缘操作组） */}
          {!minimalChrome && subagentHint}
          {/* [monitor-cc] 「正在监听：…」：显示 Monitor 在盯什么，点开右栏运行 tab */}
          {!minimalChrome && monitorHint}
          {/* 输入框上方右缘操作组（右对齐输入框右缘）：待回答提示 + 回到底部（离底时）+ 停止所有（运行中） */}
          <div className="composer-top-actions">
            {pendingHint}
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
        {/* [goal V253] 目标头 · 贴在输入框顶部内缘（不是 App 层横幅 —— 目标是常驻身份，不是告警） */}
        {/* 设定态与展示态占同一个位置：点「+ → 目标」就地变面板，提交后再变回目标头 */}
        {goalSetOpen && onSetGoal ? (
          <GoalSetPanel
            // 已有目标时预填当前条件：点「目标」是来改写/查看的，打开空面板会让人以为目标没了
            initialCondition={goalCondition ?? ''}
            onCancel={() => setGoalSetOpen(false)}
            onSubmit={async (condition) => {
              await onSetGoal(condition)
              setGoalSetOpen(false)
            }}
          />
        ) : (
          <GoalHead sessionId={sessionId ?? null} />
        )}
        <div className="input-box">
          {/* [chip 行] topChips（知识库已选库）与应用 chip **同容器同行**：
              同高 28px、同 gap 6px，容器自身不加内边距 —— ⛔ 不再让应用 chip 单独起一行，
              否则输入框几何被整体抬高一截（用户 2026-11-23 报）。 */}
          {(!!topChips || (showAppSelection && appSelKeys.length > 0)) ? (
            <div className="input-chips" data-testid="input-chips">
              {topChips}
              {showAppSelection && appSelKeys.map((key) => {
                const opt = appByKey.get(key)
                const label = opt?.name ?? key
                return (
                  <span className="app-chip" key={key} data-app-key={key} title={`应用：${label}`}>
                    <span className="app-chip-ico" aria-hidden="true">{(label.trim()[0] ?? '?').toUpperCase()}</span>
                    <span className="app-chip-label">{label}</span>
                    <button
                      type="button"
                      className="app-chip-x"
                      aria-label={`移除本轮强调：${label}`}
                      title={`移除本轮强调：${label}`}
                      onClick={(e) => {
                        e.stopPropagation()
                        // chip = 本轮强调（星标）；移除 = 从强调名单去掉（⛔ 不停止应用）
                        onAppsChange?.(appSelKeys.filter((k) => k !== key))
                      }}
                    >×</button>
                  </span>
                )
              })}
            </div>
          ) : null}
          {attachments.length > 0 && (
            <div className="attach-preview">
              {attachments.map((a, i) => {
                const remove = () => {
                  // ⭐ 释放点①：必须用该 chip 的 **dedupKey**（键已不是文件名 —— 用 filename 释放
                  //   会「移除了 chip 但键还在」⇒ 同一张图再也加不回来）
                  addedKeysRef.current.delete(a.dedupKey)
                  setAttachments((prev) => prev.filter((_, j) => j !== i))
                }
                // 图片：缩略图；其余：DSH `FileCard` 同款横卡（类型图标 + 文件名 + 扩展名/大小）
                if (a.preview) {
                  return (
                    <div key={`${a.filename}-${i}`} className="attach-item" title={a.filename}>
                      <img src={a.preview} alt={a.filename} className="attach-thumb" onClick={() => setZoomImg(a.preview!)} style={{ cursor: 'zoom-in' }} />
                      <button className="attach-remove" onClick={remove} title="移除附件">×</button>
                    </div>
                  )
                }
                const ext = /\.([^./\\]+)$/.exec(a.filename)?.[1]?.toUpperCase().slice(0, 8) ?? ''
                const meta = [ext, a.size != null ? formatFileSize(a.size) : ''].filter(Boolean).join(' ')
                const openPreview = () => {
                  if (a.contentId === '__uploading__') {
                    showToast('附件上传中，完成后可预览', 'info')
                    return
                  }
                  setPreviewAtt(a)
                }
                return (
                  <div
                    key={`${a.filename}-${i}`}
                    className="attach-file-card"
                    title={`点击预览：${a.filename}`}
                    role="button"
                    tabIndex={0}
                    onClick={openPreview}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPreview() }
                    }}
                  >
                    <span className="attach-file-icon"><AttachFileIcon name={a.filename} size={28} /></span>
                    <span className="attach-file-body">
                      <span className="attach-file-name">{a.filename}</span>
                      {meta && <span className="attach-file-meta">{meta}</span>}
                    </span>
                    <button
                      className="attach-file-remove"
                      onClick={(e) => { e.stopPropagation(); remove() }}
                      title="移除附件"
                    >×</button>
                  </div>
                )
              })}
            </div>
          )}
          <div style={{ position: 'relative' }}>
            {/* 长文本降级：超过阈值不渲染高亮层（长串整形/正则/子树重建是卡顿主因） */}
            {composerText.length <= OVERLAY_DEGRADE_CHARS && (
            <div
              ref={highlightRef}
              data-testid="composer-highlight"
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
              {renderHighlighted(composerText, removeAtChip, slashAlias)}
            </div>
            )}
            <textarea
              ref={textareaRef}
              placeholder={composerBlocked ? (blockState.reason ?? '暂不能发送') : '向 nexus 提问，或输入 / 调出命令'}
              value={composerText}
              onChange={(e) => setComposerText(e.target.value)}
              onScroll={syncHighlight}
              onPaste={onPaste}
              onCompositionStart={() => { composingRef.current = true }}
              onCompositionEnd={() => {
                composingRef.current = false
                compositionEndAtRef.current = Date.now()
              }}
              // [composer-block] 待确认消息/待审批期间**只锁发送、不锁输入**：
              //   用户仍可打字（不必丢失正在写的内容）；发送入口在发送按钮与发送回调处拦截。
              title={composerBlocked ? (blockState.reason ?? undefined) : undefined}
              onKeyDown={(e) => {
                // [IME] 组合态（拼音/日文候选）里按 Enter 只确认候选：
                //   ① isComposing / keyCode 229 = 组合中的标准信号；
                //   ② compositionend 早于 keydown 的浏览器（Safari/WKWebView）用短窗口兜底。
                if (e.nativeEvent.isComposing || e.keyCode === 229 || composingRef.current) return
                if (Date.now() - compositionEndAtRef.current < 60) return
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
                // [2026-10-29 用户裁定] 召唤/选择的技能 chip 是真实文本前缀 ⇒ 退格 / Delete 必须**整块删除**，
                //   否则视觉上会「先掉高亮阴影，再逐字删名称」（尾随空格先被吃掉 → chip 失配）。
                if (!atMatches && !cmdMatches && (e.key === 'Backspace' || e.key === 'Delete')) {
                  const chipLen = slashChipLength(composerText, slashAlias)
                  const el = e.currentTarget
                  const selStart = el.selectionStart
                  const selEnd = el.selectionEnd
                  const hitChip = chipLen > 0 && selStart === selEnd && (
                    e.key === 'Backspace'
                      ? selStart > 0 && selStart <= chipLen
                      : selStart < chipLen
                  )
                  if (hitChip) {
                    e.preventDefault()
                    // chip 恒在文本开头（SLASH_PREFIX_RE / 别名前缀都是锚定 ^ 的）
                    setComposerText(composerText.slice(chipLen))
                    requestAnimationFrame(() => {
                      try { el.setSelectionRange(0, 0) } catch { /* 卸载竞态：忽略 */ }
                    })
                    return
                  }
                }
                if (e.key === 'Enter') {
                  // Enter 发送；Shift+Enter / Alt+Enter 换行（textarea 默认行为，不拦截）
                  if (!e.shiftKey && !e.altKey && !e.metaKey && !e.ctrlKey) {
                    e.preventDefault()
                    // [composer-block] 封锁态下 Enter 不得越过禁用（输入框已 disabled，
                    //   这里再兜一层：程序化派发/焦点残留也不会发出消息）
                    if (composerBlocked) return
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
                ...HIGHLIGHT_TEXT_STYLE,
                position: 'relative',
                background: 'transparent',
                zIndex: 1,
                // [v6] 文字由 textarea 显示（高亮层仅画 chip 装饰、色为 transparent）；
                // chip 的可见文字是 visual 层 label（与真实文本同字同位置重叠渲染）。
                color: 'var(--ink)',
                caretColor: 'var(--ink)',
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
            {/* 知识库 @ 面板 · 结构与样式逐项对齐源站 `components/MentionSelector.vue`：
                搜索框（二级带「‹ 组名」面包屑）+ 根层级分组入口 + 二级条目（图标/名称/计数/箭头）。
                ⛔ 不要退回自造的 .at-menu 结构。 */}
            {kbPanelOpen ? (
              <div className="kb-mention mention-menu">
                <div className="mention-search">
                  <div className="mention-search__box">
                    <span className="mention-search__icon">
                      <svg width={14} height={14} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-search" /></svg>
                    </span>
                    {atGroup !== null && (
                      <button
                        type="button"
                        className="mention-search__crumb"
                        title="返回"
                        onClick={() => { setAtGroup(null); setAtIndex(0); setAtSearch('') }}
                      >
                        <svg width={12} height={12} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-chevron-left" /></svg>
                        <span>{atGroup === 'kb' ? '知识库' : '文件'}</span>
                      </button>
                    )}
                    <input
                      className="mention-search__input"
                      value={atSearch}
                      onChange={(e) => { setAtSearch(e.target.value); setAtIndex(0) }}
                      placeholder={atGroup === null ? '选择要引用的类别' : '搜索'}
                      autoComplete="off"
                      spellCheck={false}
                    />
                  </div>
                </div>
                <div className="mention-list" ref={atMenuRef}>
                  {atGroup === null ? (
                    <>
                      <button
                        type="button"
                        className="mention-row mention-group-entry"
                        data-idx={0}
                        onClick={() => { setAtGroup('kb'); setAtIndex(0); setAtSearch('') }}
                      >
                        <span className="mention-row__icon">
                          <svg width={14} height={14} viewBox="0 0 20 20" aria-hidden="true"><use href="#t-icon-kb-resource" /></svg>
                        </span>
                        <span className="mention-row__name">知识库</span>
                        <span className="mention-row__meta">{kbGroupCounts.kb}</span>
                        <svg className="mention-row__arrow" width={14} height={14} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-chevron-right" /></svg>
                      </button>
                      <button
                        type="button"
                        className="mention-row mention-group-entry"
                        data-idx={1}
                        onClick={() => { setAtGroup('file'); setAtIndex(0); setAtSearch('') }}
                      >
                        <span className="mention-row__icon">
                          <svg width={14} height={14} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-file" /></svg>
                        </span>
                        <span className="mention-row__name">文件</span>
                        <span className="mention-row__meta">{kbGroupCounts.file}</span>
                        <svg className="mention-row__arrow" width={14} height={14} viewBox="0 0 24 24" aria-hidden="true"><use href="#t-icon-chevron-right" /></svg>
                      </button>
                    </>
                  ) : (
                    <>
                      {(atMatches ?? []).map((p, i) => {
                        const entry = atItems?.find((x) => x.label === p)
                        const active = i === atIndex % (atMatches?.length ?? 1)
                        const q = (atSearch.trim() || (atTailIndex?.prefix ?? '')).trim()
                        const hit = q ? p.toLowerCase().indexOf(q.toLowerCase()) : -1
                        return (
                          <div
                            key={p}
                            className={`mention-row mention-item ${active ? 'active' : ''}`}
                            data-idx={i}
                            onMouseEnter={() => setAtIndex(i)}
                            onClick={() => applyAtPick(p)}
                            title={p}
                          >
                            <span className="mention-row__icon">
                              <svg width={14} height={14} viewBox={atGroup === 'kb' ? '0 0 20 20' : '0 0 24 24'} aria-hidden="true">
                                <use href={atGroup === 'kb' ? '#t-icon-kb-resource' : '#t-icon-file'} />
                              </svg>
                            </span>
                            <span className="mention-row__name">
                              {hit >= 0 ? (
                                <>
                                  {p.slice(0, hit)}
                                  <mark className="mention-hit">{p.slice(hit, hit + q.length)}</mark>
                                  {p.slice(hit + q.length)}
                                </>
                              ) : p}
                            </span>
                            {entry?.sub && <span className="mention-row__meta mention-row__meta--text">{entry.sub}</span>}
                          </div>
                        )
                      })}
                      {!atMatches && <div className="empty">没有匹配项</div>}
                    </>
                  )}
                </div>
              </div>
            ) : atMatches && (
              <div className="at-menu" ref={atMenuRef}>
                <div className="at-menu-cap">{`@ 引用文件 · ${boundProjectName ?? '绑定项目'}`}</div>
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
          {previewAtt && createPortal(
            <AttachPreviewModal
              att={previewAtt}
              sessionId={sessionId ?? ''}
              onClose={() => setPreviewAtt(null)}
            />,
            document.body,
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
              {/* 左下角「+」：统一收纳 添加文件 / 模式 / 专家 / 技能 / 连接器 */}
              {/* 悬停即展开（用户 2026-11-15）：鼠标移到 + 上直接出菜单，不再需要点击。
                  点击保留为「打开」（键盘 Enter/Space 的可达路径），⛔ 不再做开/合切换——
                  指针悬停时菜单本来就已展开，点击若切换会把它关掉。
                  离开整个 plus-wrap（按钮 + 菜单 + CSS 过渡桥）才收起。 */}
              <div
                className="plus-wrap"
                ref={plusRef}
                onMouseEnter={() => setPlusOpen(true)}
                onMouseLeave={closePlusMenu}
              >
                <button
                  type="button"
                  className={`plus-btn${plusOpen ? ' active' : ''}`}
                  title="添加文件、模式、专家、技能、连接器"
                  aria-label="添加文件、模式、专家、技能、连接器"
                  aria-expanded={plusOpen}
                  onClick={() => setPlusOpen(true)}
                >
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                    <path d="M6 2V10M2 6H10" />
                  </svg>
                </button>
                {plusOpen && (
                  <div className="plus-menu" role="menu">
                    <button type="button" className="plus-item" role="menuitem" onClick={() => { closePlusMenu(); void handleAddFiles() }}>
                      <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                        <path d="M8.2 2.2L4 6.4a1.8 1.8 0 0 0 2.5 2.5L10.7 5a3 3 0 0 0-4.2-4.2L2.4 4.9a4.2 4.2 0 0 0 5.9 5.9l3.5-3.5" />
                      </svg>
                      <span>添加文件</span><span className="plus-chev">›</span>
                    </button>
                    <div className="plus-sep" />
                    {/* 目标：会话行为设定（与「模式」同级），不是内容附件 ⇒ 单独成组、排在添加文件之后。
                        右侧徽章随状态：无目标=「设定」，有目标=「追踪中」。点开 → 输入框顶部就地变面板。 */}
                    {!agentModes && onSetGoal && (
                      <button
                        type="button"
                        className="plus-item"
                        role="menuitem"
                        onClick={() => { closePlusMenu(); setGoalSetOpen(true) }}
                      >
                        <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                          <circle cx="7" cy="7" r="4.6" />
                          <circle cx="7" cy="7" r="1.3" />
                          <path d="M7 .8v1.8M7 11.4v1.8M.8 7h1.8M11.4 7h1.8" strokeLinecap="round" />
                        </svg>
                        <span>目标</span>
                        <span className={`plus-badge${hasGoal ? ' on' : ''}`}>{hasGoal ? '追踪中' : '设定'}</span>
                      </button>
                    )}
                    {/* 模式：右侧 flyout（不再是盲切一项；modeLocked 时只提示不切换） */}
                    {/* 模式：主会话有（simple/full）；知识库没有这一项 —— 模式改由左下角
                        模式胶囊自己弹出（用户 2026-11-18：「知识库菜单少了模式选择和目标」）。 */}
                    {!agentModes && (
                    <div className="plus-sub-anchor">
                      <button
                        type="button"
                        className="plus-item"
                        role="menuitem"
                        aria-expanded={plusModeOpen}
                        onClick={() => setPlusModeOpen((v) => !v)}
                      >
                        <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                          <path d="M2 4h6M10 4h2M2 10h2M6 10h6" />
                          <circle cx="8.6" cy="4" r="1.4" />
                          <circle cx="4.6" cy="10" r="1.4" />
                        </svg>
                        <span>模式</span><span className="plus-chev">{mode} ›</span>
                      </button>
                      {/* 展开后可选：点哪项切哪项（modeLocked 时仍走锁定提示，不盲切） */}
                      {plusModeOpen && (
                        <div className="plus-submode" role="group" aria-label="模式选择">
                          {(['simple', 'full'] as const).map((m) => (
                            <button
                              key={m}
                              type="button"
                              role="menuitemradio"
                              aria-checked={mode === m}
                              className={`plus-subitem${mode === m ? ' selected' : ''}`}
                              onClick={() => pickModeFromMenu(m)}
                            >
                              <span className="plus-check" aria-hidden="true">
                                {mode === m && (
                                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2">
                                    <path d="M2.5 6.2l2.3 2.3 4.7-4.7" strokeLinecap="round" strokeLinejoin="round" />
                                  </svg>
                                )}
                              </span>
                              <span>{m === 'simple' ? '简单模式' : '完整模式'}</span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                    )}
                    {/* 专家：复用现有技能市场弹窗（专家 tab） */}
                    {onOpenMarket && (
                      <button type="button" className="plus-item" role="menuitem" onClick={() => { closePlusMenu(); onOpenMarket?.('expert') }}>
                        <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                          <circle cx="7" cy="5" r="2.4" />
                          <path d="M2.4 12c.6-2.2 2.4-3.4 4.6-3.4s4 1.2 4.6 3.4" />
                        </svg>
                        <span>专家</span><span className="plus-chev">›</span>
                      </button>
                    )}
                    {/* 技能：与专家同一个技能市场弹窗，默认切到「技能」tab */}
                    {onOpenMarket && (
                      <button type="button" className="plus-item" role="menuitem" onClick={() => { closePlusMenu(); onOpenMarket?.('skill') }}>
                        <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                          <path d="M2.5 12L9 5.5" />
                          <path d="M9.5 2l.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6L7.3 4.2l1.6-.6z" />
                        </svg>
                        <span>技能</span><span className="plus-chev">›</span>
                      </button>
                    )}
                    {/* 连接器：同一个技能市场弹窗，默认切到「连接器」tab */}
                    {onOpenMarket && (
                      <button type="button" className="plus-item" role="menuitem" onClick={() => { closePlusMenu(); onOpenMarket?.('connector') }}>
                        <svg className="plus-ic" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                          <path d="M5.6 8.4a2 2 0 0 0 2.8 0l1.6-1.6a2 2 0 0 0-2.8-2.8L6.5 4.7" />
                          <path d="M8.4 5.6a2 2 0 0 0-2.8 0L4 7.2a2 2 0 0 0 2.8 2.8l.7-.7" />
                        </svg>
                        <span>连接器</span><span className="plus-chev">›</span>
                      </button>
                    )}
                  </div>
                )}
              </div>
              {/* 应用（连接器）多选：常驻入口，本轮随消息生效（⛔ 不写会话级禁用表）。
                  无连接器（目录已拉且为空）⇒ 置灰 + 点击给说明，⛔ 不开空面板装样子。 */}
              {showAppSelection && (
                <div className="app-picker-wrap" ref={appsWrapRef}>
                  <button
                    type="button"
                    className={`tool-chip app-trigger${appSelKeys.length ? ' active' : ''}${appCatalogEmpty ? ' disabled' : ''}`}
                    data-testid="app-picker-trigger"
                    title={appCatalogEmpty
                      ? '还没有可用的应用：请先在「+ → 连接器」里接入一个连接器'
                      : '选择应用（本轮生效，可多选）'}
                    aria-label="选择应用"
                    aria-expanded={appsOpen}
                    aria-disabled={appCatalogEmpty ? true : undefined}
                    onClick={() => {
                      // 已开 → 关（入口永远能关自己的面板）；未开且无连接器 → 只给说明，⛔ 不开空面板
                      if (appsOpen) { setAppsOpen(false); return }
                      if (appCatalogEmpty) {
                        showToast('还没有可用的应用：请先在「+ → 连接器」里接入一个连接器', 'info')
                        return
                      }
                      setAppsOpen(true)
                      if (appOptions === null) void loadApps()
                    }}
                  >
                    {/* 图标必须放 <i>：窄栏（<700px）的 `tool-chip > span` 收文字规则会连图标一起藏掉 */}
                    <i className="app-trigger-ico" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="3.5" y="3.5" width="7" height="7" rx="1.6" />
                        <rect x="13.5" y="3.5" width="7" height="7" rx="1.6" />
                        <rect x="3.5" y="13.5" width="7" height="7" rx="1.6" />
                        <path d="M17 13.5v7M13.5 17h7" />
                      </svg>
                    </i>
                    {/* 文字与其他 chip 同款：窄栏/compact 的 `tool-chip > span` 规则会自动收掉 */}
                    <span>应用</span>
                    {/* 角标用 <b>：`> span` 在窄栏会被收文字规则隐藏 */}
                    {appSelKeys.length > 0 && <b className="app-trigger-count">{appSelKeys.length}</b>}
                  </button>
                  {appsOpen && (
                    <AppPicker
                      apps={appOptions ?? []}
                      startedKeys={startedAppKeys}
                      emphasizedKeys={emphasizedKeys}
                      onToggleStart={toggleStart}
                      onToggleEmphasis={toggleEmphasis}
                      startingKeys={[...appsStarting]}
                      startErrors={appsStartError}
                      onStartApp={(key) => { void startAppByKey(key) }}
                      loading={appsLoading}
                      error={appsError}
                      onRetry={() => { void loadApps() }}
                      onOpenConnectors={onOpenMarket
                        ? () => { setAppsOpen(false); onOpenMarket('connector') }
                        : undefined}
                    />
                  )}
                </div>
              )}
              {/* 当前专家/模式胶囊（在 + 右侧）
                  · 主会话：纯展示，hover 出 × 清除（App → handleAgentChange(null)）
                  · 知识库（agentModes）：可点击，自己弹模式列表 —— + 菜单里不再放「模式」 */}
              {agentModes && currentAgent && currentAgent.trim() ? (
                <div className="plus-wrap" ref={modeMenuRef}>
                  <button
                    type="button"
                    className={`agent-pill agent-pill-btn${modeMenuOpen ? ' active' : ''}`}
                    title={`当前模式：${currentAgent} · 点击切换`}
                    aria-label="切换知识库对话模式"
                    aria-expanded={modeMenuOpen}
                    onClick={() => { closePlusMenu(); setModeMenuOpen((v) => !v) }}
                  >
                    <span className="agent-pill-ava" aria-hidden="true">
                      <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M2.5 6.2l2.2 2.2 4.8-4.8" />
                      </svg>
                    </span>
                    <span className="agent-pill-name">{currentAgent}</span>
                    <span className="plus-chev" aria-hidden="true">▾</span>
                  </button>
                  {modeMenuOpen && (
                    <div className="plus-menu" role="menu">
                      {agentModes.map((m) => (
                        <button
                          key={m.key}
                          type="button"
                          className="plus-item"
                          role="menuitemradio"
                          aria-checked={m.active}
                          title={m.title}
                          onClick={() => { setModeMenuOpen(false); m.onPick() }}
                        >
                          <span>{m.active ? '●' : '○'}</span>
                          <span>{m.label}</span>
                          {m.active && <span className="plus-badge on">当前</span>}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : currentAgent && currentAgent.trim() && (
                <span className="agent-pill" title={`当前专家：${currentAgent}`}>
                  {onClearAgent && (
                    <span
                      className="agent-pill-x"
                      role="button"
                      aria-label="移除专家"
                      title="移除专家（回到默认模式）"
                      onClick={(e) => { e.stopPropagation(); onClearAgent() }}
                    >×</span>
                  )}
                  <span className="agent-pill-ava" aria-hidden="true">
                    <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M2.5 6.2l2.2 2.2 4.8-4.8" />
                    </svg>
                  </span>
                  <span className="agent-pill-name">{currentAgent}</span>
                </span>
              )}
              {/* ⭐ 释放点②（清空）：`clear()` 与键的具体形态无关 ⇒ 改键后无需改动（逐字核过） */}
              {attachments.length > 0 && (
                <button className="tool-chip text-only" onClick={() => { addedKeysRef.current.clear(); setAttachments([]); showToast('已清空附件', 'info') }} title="清空附件">
                  <span>清空</span>
                </button>
              )}
              {/* 知识库选择（快速问答唯一入口）：点它等价于在文本末尾敲 @，复用同一套候选面板 */}
              {!!atItems?.length && (
                <button
                  type="button"
                  className="tool-chip"
                  title="选择知识库（等同于输入 @）"
                  data-at-trigger=""
                  onClick={() => { setAtOpen((v) => !v); setAtIndex(0) }}
                >
                  <span aria-hidden="true">@</span>
                  <span>知识库</span>
                </button>
              )}
              {/* [2026-11-19 用户裁定 · 对齐 CC]
                  计划模式 = claude.exe 的 EnterPlanMode（`/plan` 链路），⛔ 不是权限模式下拉的普通选项。
                  · 进入：空闲立即生效；turn 运行中**排队**（CC 语义：输入 /plan 入队，消费后执行），
                    前端先乐观显示「已进入」——排队逻辑在 App，chip 不做拦截。
                  · 退出：= 切换权限模式（CC Shift+Tab 随时可切，无 busy 守卫），恢复进入前模式。
                  激活态读会话有效 permissionMode（SessionService 已合并 session_permission_rules）。 */}
              {(onEnterPlanMode || onExitPlanMode) && !minimalTools && (
                <div
                  className={`tool-chip plan-mode plan-mode-chip${permissionMode === 'plan' ? ' active' : ''}${planPending ? ' pending' : ''}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={permissionMode === 'plan' ? 'true' : 'false'}
                  title={permissionMode === 'plan'
                    ? '退出计划模式（切换回进入前的权限模式）'
                    : planPending
                      ? '已排队：当前任务结束后进入计划模式'
                      : streaming
                        ? '进入计划模式（当前任务结束后生效）'
                        : '进入计划模式（EnterPlanMode）：先探索、出计划，批准后再动手'}
                  onClick={() => {
                    if (permissionMode === 'plan') onExitPlanMode?.()
                    else if (planPending) showToast('已排队：当前任务结束后进入计划模式', 'info')
                    else onEnterPlanMode?.()
                  }}
                  onKeyDown={(e) => {
                    if (e.key !== 'Enter' && e.key !== ' ') return
                    e.preventDefault()
                    if (permissionMode === 'plan') onExitPlanMode?.()
                    else if (planPending) showToast('已排队：当前任务结束后进入计划模式', 'info')
                    else onEnterPlanMode?.()
                  }}
                >
                  <Icon.PlanMode />
                  <span>计划</span>
                  <i className="plan-indicator" aria-hidden="true" />
                </div>
              )}
              {/* [2026-11-19] 浏览器入口挪走（AI 已知道怎么用浏览器）⇒ 原位换成「协调者」。
                  点击 chip 即切换会话级覆盖；激活态复用计划模式样式（浅蓝底 + 点呼吸），
                  不开小开关——整个 chip 就是开关。 */}
              {onCoordinatorModeChange && !minimalTools && (
                <div
                  className={`tool-chip plan-mode coordinator-chip${coordinatorMode ? ' active' : ''}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={coordinatorMode ? 'true' : 'false'}
                  title={`协调者模式：主线程作为协调者调度子代理（本会话${coordinatorMode ? '已开启' : '已关闭'}，覆盖全局设置）`}
                  onClick={() => onCoordinatorModeChange(!coordinatorMode)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onCoordinatorModeChange(!coordinatorMode)
                    }
                  }}
                >
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="3" cy="3" r="1.6" />
                    <circle cx="9" cy="6" r="1.6" />
                    <circle cx="3" cy="9" r="1.6" />
                    <path d="M4.4 3.6l3 1.9M4.4 8.4l3-1.9" />
                  </svg>
                  <span>协调者</span>
                  {/* 复用计划模式的激活语言：关闭灰点 / 开启蓝点呼吸（用户指定「之前的计划模式样式」） */}
                  <i className="plan-indicator" aria-hidden="true" />
                </div>
              )}
              {/* 会话工具：齿轮入口（默认只显图标，hover 滑出「工具」）→ 一级分类目录 → 二级明细 */}
              {!minimalTools && (
              <div
                className={`tools-chip-wrap${toolsOpen ? ' open' : ''}`}
                ref={toolsPanelRef}
                onMouseEnter={() => {
                  if (hoverCloseTimer.current != null) { window.clearTimeout(hoverCloseTimer.current); hoverCloseTimer.current = null }
                  setToolsOpen(true)
                }}
                onMouseLeave={() => {
                  if (hoverCloseTimer.current != null) window.clearTimeout(hoverCloseTimer.current)
                  hoverCloseTimer.current = window.setTimeout(() => setToolsOpen(false), 200)
                }}
              >
                <div className="tool-chip tool-entry" title="工具">
                  {/* 用 <i> 而不是 <span>：窄栏（<620px）的 `tool-chip > span` 收文字规则
                      会把图标一起 display:none 掉——图标必须常驻，文字才收。 */}
                  <i className="entry-ico">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="3" />
                      <path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z" />
                    </svg>
                  </i>
                  <span className="entry-label">工具</span>
                </div>
                {toolsOpen && (
                  <SessionToolsPanel
                    sessionId={sessionId ?? ''}
                    showToast={showToast}
                    onClose={() => setToolsOpen(false)}
                    onOpenChromePanel={onOpenChromePanel}
                    unattended={unattended}
                    onModeChanged={onReloadSessionMode}
                  />
                )}
              </div>
              )}
              {/* IM 渠道入口：与「浏览器」同款 chip（图标 + 文字）。点击打开**独立的居中弹窗**，
                  弹窗内左「渠道」右「聊天」并列展示 —— ⛔ 不再锚在按钮上方、也不再两级下钻。 */}
              {onOpenImChannels && (
                <button
                  type="button"
                  className="tool-chip chan-entry"
                  title="IM 渠道：这个会话的话会发到哪"
                  onClick={(e) => {
                    e.stopPropagation()
                    // 每次打开都重拉一次渠道/聊天 —— 新同步的聊天不用重启就能看到
                    if (!chanMenuOpen) onRefreshImData?.()
                    setChanMenuOpen((v) => !v)
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M21 12a8 8 0 01-11.5 7.2L4 21l1.8-5.5A8 8 0 1121 12z" />
                  </svg>
                  <span>渠道</span>
                  {imBoundChatIds && imBoundChatIds.length > 0 && (
                    <span className="chan-entry-dot" title={`已接 ${imBoundChatIds.length} 个聊天`} />
                  )}
                </button>
              )}
              {/* [V329] 无人值守：常驻在「渠道」右侧。
                  唯一硬前置 = 已配置渠道实例；未配置时入口**仍可点** → 提示「请先配置渠道」并打开既有渠道弹窗。
                  ⛔ 不在渠道弹窗放第二个开关；⛔ 不新增「交互方式」入口。
                  [2026-11-21 用户要求] 视觉对齐「计划 / 协调者」chip：同一个 `.tool-chip.plan-mode`，
                  关闭灰点、开启蓝点呼吸；⛔ 不再用迷你拨杆。 */}
              {onToggleUnattended && (
                <div className="uo-wrap">
                  <button
                    type="button"
                    className={`tool-chip plan-mode uo-entry${unattended ? ' active' : ''}`}
                    role="switch"
                    aria-checked={!!unattended}
                    aria-label="无人值守"
                    title="无人值守：开启后回答直达开、Ask 关闭；未配置渠道会先引导配置"
                    onClick={(e) => {
                      e.stopPropagation()
                      if ((imInstances?.length ?? 0) === 0) {
                        setUoGuide(true)
                        // 用既有 toast 统一时长（info=6s）—— ⛔ 不引入第二种「自动消失」实现
                        showToast('请先配置渠道', 'info')
                        if (!chanMenuOpen) onRefreshImData?.()
                        setChanMenuOpen(true)
                        return
                      }
                      setUoGuide(false)
                      onToggleUnattended()
                    }}
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M12 3a7 7 0 017 7c0 3-1.6 4.6-2.7 6H7.7C6.6 14.6 5 13 5 10a7 7 0 017-7z" />
                      <path d="M9 19h6M10 22h4" />
                    </svg>
                    <span>无人</span>
                    {/* 状态与计划/协调者同一套语言：关闭灰点 / 开启蓝点呼吸；
                        role=switch + aria-checked 保留给读屏与测试。 */}
                    <i className="plan-indicator" aria-hidden="true" />
                    {unattendedPending && <span className="uo-pending" title="本轮仍在运行，下一轮生效">下一轮生效</span>}
                  </button>
                </div>
              )}
              {onOpenImChannels && chanMenuOpen && (
                <ImChannelPickerModal
                  instances={imInstances ?? []}
                  chats={imChats ?? []}
                  boundChatIds={imBoundChatIds ?? []}
                  inactiveChatIds={imInactiveChatIds ?? []}
                  allowUserIdentity={imAllowUserIdentity}
                  onToggleUserIdentity={onToggleImUserIdentity}
                  onBind={(id) => onBindImChat?.(id)}
                  onUnbind={(id) => onUnbindImChat?.(id)}
                  sessionId={sessionId}
                  onQuickBind={onQuickBindImInstance ? (id) => onQuickBindImInstance(id) : undefined}
                  onOpenChannels={onOpenImChannels}
                  guide={uoGuide}
                  onClose={() => { setChanMenuOpen(false); setUoGuide(false) }}
                />
              )}
            </div>
            <div className="right-tools">
              {onPermissionModeChange && (
                <div className="perm-mode-pill-wrap" ref={permRef}>
                  <div className="perm-mode-pill" onClick={() => setPermOpen((v) => !v)} title={`权限模式：${PERMISSION_MODE_LABELS[permissionMode]}（会话覆盖全局）`}>
                    <PermissionModeIcon mode={permissionMode} className={`pm-icon${permissionMode === 'bypassPermissions' ? ' danger' : ''}`} />
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
                          <span className="pill-main">
                            <PermissionModeIcon mode={m} className={`pill-ico${m === 'bypassPermissions' ? ' danger' : ''}`} />
                            <span className="pill-label">{PERMISSION_MODE_LABELS[m]}</span>
                          </span>
                          <span className="pill-desc">{PERMISSION_MODE_DESCRIPTIONS[m]}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {(onOpenModelPicker || onOpenEffort) && (
                <div className="model-effort-pill-wrap" ref={pillRef}>
                  {/* 收窄后长名会被省略 ⇒ title 给完整模型名与推理等级（原为固定文案） */}
                  <div
                    className="model-effort-pill"
                    onClick={() => setPillOpen((v) => !v)}
                    // title 仅兜底（原生约 500ms）；真正的提示是下面 100ms 的自定义 tooltip
                    title={`${currentModel || '选择模型'} · 推理 ${ultracodeEnabled ? 'ultracode' : effortLevel === 'off' ? '关闭' : (effortLevel ?? 'high')}`}
                    onMouseEnter={() => {
                      if (mepTipTimer.current != null) window.clearTimeout(mepTipTimer.current)
                      mepTipTimer.current = window.setTimeout(() => setMepTipOpen(true), 100)
                    }}
                    onMouseLeave={() => {
                      if (mepTipTimer.current != null) window.clearTimeout(mepTipTimer.current)
                      mepTipTimer.current = null
                      setMepTipOpen(false)
                    }}
                  >
                    {/* 别名 + 品牌图标 + 档位标签（顺序按用户：DS → 图标 → max） */}
                    <span className="mep-alias">{modelBrand.alias}</span>
                    <img className="mep-brand" src={modelBrand.icon} alt="" draggable={false} />
                    {modelEffortTag && <span className="mep-effort">{modelEffortTag}</span>}
                    <svg className={`mep-chevron ${pillOpen ? 'open' : ''}`} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2.2" style={{ width: 13, height: 13 }}>
                      <path d="M2 4L6 8L10 4" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                  {mepTipOpen && (
                    <div className="model-effort-tip" role="tooltip">
                      {currentModel || '选择模型'} · {modelBrand.provider}
                      {modelEffortTag ? ` · 推理 ${modelEffortTag}` : ''}
                    </div>
                  )}
                  {pillOpen && (
                    <div className="pill-drawer">
                      {onOpenModelPicker && (
                        <div className="pill-row" onClick={() => { setPillOpen(false); onOpenModelPicker() }}>
                          <span className="pill-main">
                            <svg className="pill-ico" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M7 1.5L12 4.3V9.7L7 12.5L2 9.7V4.3L7 1.5Z" />
                              <path d="M2 4.3L7 7.1L12 4.3M7 7.1V12.5" />
                            </svg>
                            <span className="pill-label">模型</span>
                          </span>
                          <span className="pill-value">{currentModel || '选择模型'} ›</span>
                        </div>
                      )}
                      {onOpenEffort && (
                        <div className="pill-row" onClick={() => { setPillOpen(false); onOpenEffort() }}>
                          <span className="pill-main">
                            <svg className="pill-ico" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M2.4 10.2A4.8 4.8 0 1 1 11.6 10.2" />
                              <path d="M7 9.2L9.1 6.4" />
                              <path d="M7 10.4A1.2 1.2 0 1 0 7 8A1.2 1.2 0 0 0 7 10.4Z" />
                            </svg>
                            <span className="pill-label">推理等级</span>
                          </span>
                          <span className="pill-value">{ultracodeEnabled ? 'ultracode' : effortLevel === 'off' ? '关闭' : (effortLevel ?? 'high')} ›</span>
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
                  disabled={composerBlocked || (!composerText.trim() && attachments.length === 0)}
                  title={composerBlocked ? (blockState.reason ?? undefined) : undefined}
                  aria-disabled={composerBlocked || undefined}
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
              {cacheWarnBase != null && (
                <span
                  className={`hu-cache${Math.round(cacheWarnBase * 100) >= 50 ? '' : ' warn'}`}
                  title="缓存命中率（后端记账口径 · 0-1）：本回合 = 最近一条回复自身；累计 = 本会话"
                >
                  · {cacheText}
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
