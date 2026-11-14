/**
 * 知识库弹窗内的「新对话」页。
 *
 * <h2>复用什么</h2>
 * 消息渲染 {@link MessageList}、输入区 {@link Composer}、发送链路（App 的 {@code sendMessage}）
 * 全部复用主会话那一套 —— 不另造对话 UI，也不另造发送通道。
 *
 * <h2>隔离在哪</h2>
 * 会话 id 是独立的一条：{@code useChatStore} 的 messages / streams / serverRunning 都是
 * {@code Record<sessionId, …>}，主对话只订自己的 topic（{@code useChatSocket} 的订阅表按 sid 累计）
 * ⇒ 主会话收不到本页的流，反之亦然。
 *
 * <h2>会话归属</h2>
 * {@code GET /api/v1/kb/workspace/project} 返回 {@code <数据根>/chat} 对应项目
 * （{@code NexusaiDataRoots.chatRoot()}），保证不落到用户自己的仓库下。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import { chatApi } from '@/api/chat'
import { knowledgeApi } from '@/api/knowledge'
import type { ChunkView, Knowledge } from '@/api/knowledge'
import { KbDocPreview } from './KbDocPreview'
import { kbFileExtOf } from './KbDocPreview'
import { sessionApi } from '@/api/sessions'
import { settingsApi } from '@/api/settings'
import { providerApi } from '@/api/providers'
import { commandApi } from '@/api/command'
import { ModelPickerModal } from '@/components/modals/ModelPickerModal'
import { EffortModal } from '@/components/modals/EffortModal'
import { Composer } from '@/components/center/Composer'
import { MessageList } from '@/components/center/MessageList'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { IconSprite } from './IconSprite'
import { sendPermissionResponse } from '@/api/socket'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'
import { useChatStore } from '@/stores/chatStore'
import { useChatSocket } from '@/hooks/useChatSocket'
import { PERMISSION_MODE_LABELS } from '@/api/types'
import type { AttachmentRequest, ChatMessageDto, CitationItem, PermissionMode, Provider, SessionDto } from '@/api/types'
import type { StreamBlock } from '@/stores/chatStore'

/** 稳定空数组（zustand 选择器返回新数组会导致无限重渲染）。 */
/**
 * 四种模式 = 四个内置 KB 智能体（与 {@code KbBuiltInAgentDefs} 的 id 逐字一致）。
 *
 * <p>选模式 = 写会话 {@code main_thread_agent}，复用主会话的专家链路；唯一差别在工具集：
 * quick-answer 走固定 RAG 流水线（无工具），其余三个是 ReAct。
 */
/**
 * 新建会话必须带 `title` 或 `modelName`（后端 `Validation Failed: Session requires at least one of`）。
 *
 * <p>这里刻意**不传 title** —— 传了就是 `title_explicit=1`，会话永远叫固定名；
 * 只传 modelName 时后端会在首条消息后按内容自动总结标题（与主会话同一条链路）。
 */
async function resolveNewSessionModel(): Promise<string | undefined> {
  try {
    const s = await settingsApi.get()
    if (s.mainModelName?.trim()) return s.mainModelName.trim()
  } catch {
    // 读设置失败不阻断：下面按 providers 回落
  }
  try {
    const providers = await providerApi.list()
    const p = providers.find((x) => x.enabled && x.models.some((m) => m.enabled))
    const m = p?.models.find((x) => x.enabled)
    if (p && m) return `${p.name}/${m.name}`
  } catch {
    // ignore
  }
  return undefined
}

const KB_MODES = [
  { key: 'builtin-quick-answer', label: '快速问答', title: '检索式问答 · 不带工具（KbQaPipeline）' },
  { key: 'builtin-smart-reasoning', label: '智能推理', title: '多步 ReAct · 可调 5 个知识库工具' },
  { key: 'builtin-data-analyst', label: '数据分析', title: 'CSV / Excel → DuckDB SQL' },
  { key: 'builtin-wiki-researcher', label: '维基问答', title: '在 Wiki 页面间导航 · 只读' },
  // 后端 KbBuiltInAgentDefs.wikiFixer() 早已注册，此前只是缺这个入口。
  // 源站该人格 kb_selection_mode=selected ⇒ 写操作只允许用户 @ 的库（守卫见 KbWikiToolSupport.checkKbWritable）。
  { key: 'builtin-wiki-fixer', label: '维基修订', title: '整理 Wiki 页面结构、修正内容与链接（只写 @ 的知识库）' },
] as const

/** 默认模式：快速问答（零工具，弹窗内不触发权限门）。 */
const DEFAULT_KB_MODE = 'builtin-quick-answer'
/** 上次的知识库对话会话 id（否则每次打开弹窗/独立窗都会新建一条空会话）。 */
const KB_CHAT_SESSION_KEY = 'nexusai.kb.chat.session'
const EMPTY_MESSAGES: ChatMessageDto[] = []
const EMPTY_BLOCKS: StreamBlock[] = []

export interface KbNewChatPaneProps {
  showToast: (message: string, kind?: 'info' | 'success') => void
  /** 从左侧历史对话点开的会话（变化时切换过去） */
  initialSessionId?: string | null
  /** 当前会话变化（新建 / 切换）时通知宿主，用于列表高亮与刷新 */
  onSessionChange?: (id: string) => void
  /** 宿主点「新对话」时自增 —— 强制进入空白会话（已有空白会话则复用，⛔ 不重复建） */
  newChatToken?: number
}

/**
 * 独立窗口的运行时桥：把主会话那条「STOMP → store」管道接到本窗口。
 *
 * <p>只订阅、只写 store（与主窗口同一个 {@link useChatSocket} 实现）⇒ 流式打印与工具卡由
 * {@link MessageList} 按老规矩渲染，本组件不画任何 UI。
 * ⛔ 主窗口不渲染它：那边 App 已有一个 useChatSocket 实例，再挂一个会多开一条 STOMP 连接。
 */
function SelfSocketBridge({ sessionId, activeStreams }: {
  sessionId: string | null
  activeStreams: Record<string, string>
}) {
  const { clientRef } = useChatSocket(sessionId, activeStreams)
  // ⚠️ 权限 / AskUserQuestion 弹窗必须在本窗口渲染：主窗口 App 的 PermissionBubble 按
  //   `activeSessionId` 过滤，知识库会话不是主会话的活动会话 ⇒ 弹不到这里（2026-11-14 用户报
  //   「Ask 弹窗跑到主会话去了，当前知识库页面没有」）。
  //   clientRef 就在本作用域内，回响应不需要跨组件传引用。
  const permissionQueue = useChatStore((s) => s.permissionQueue)
  const dequeuePermission = useChatStore((s) => s.dequeuePermission)
  const perm = permissionQueue.find((r) => r.sessionId === sessionId && r.kind === 'message')
    ?? permissionQueue.find((r) => r.sessionId === sessionId)
  if (!perm) return null
  const sid = perm.sessionId ?? sessionId ?? ''
  return (
    <PermissionBubble
      request={perm}
      // 知识库独立窗口没有「项目」概念，也没有 appSettings 上下文 ⇒ 两个可选文案来源都传 null
      projectLabel={null}
      selfDirName={null}
      onDecision={(id, d, answers, annotations, permissionUpdates) => {
        if (clientRef.current) {
          sendPermissionResponse(clientRef.current, sid, perm.kind, id, d, { answers, annotations, permissionUpdates })
        }
        useChatStore.getState().markToolAnswered(sid, perm.toolUseId ?? id)
        dequeuePermission(id)
      }}
      onAbort={() => {
        if (clientRef.current) {
          sendPermissionResponse(clientRef.current, sid, perm.kind, perm.requestId, 'deny', {})
        }
        useChatStore.getState().markToolAnswered(sid, perm.toolUseId ?? perm.requestId)
        dequeuePermission(perm.requestId)
      }}
    />
  )
}

export function KbNewChatPane({ showToast, initialSessionId, onSessionChange, newChatToken }: KbNewChatPaneProps) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [creating, setCreating] = useState(false)
  /** 当前模式 = 会话 main_thread_agent */
  const [agentMode, setAgentMode] = useState<string>(DEFAULT_KB_MODE)
  const creatingRef = useRef(false)

  // ── 独立窗口通道（hostSend=false 时启用）──
  //   用主会话同一条管道：useChatSocket 把 chunk/tool 事件写进 store，
  //   MessageList 读 store ⇒ 流式打印与工具卡与主对话完全一致。
  const [activeStreams, setActiveStreams] = useState<Record<string, string>>({})
  /** @ 可指定的知识库（空 = 不限定，检索全部库）与本次已选的库/文件 */
  const [kbs, setKbs] = useState<Array<{ id: string; label: string }>>([])
  /** 已选：kind=kb 进 knowledgeBaseIds，kind=file 进 knowledgeIds（对齐源站 @ 的 kb/file 两类） */
  const [picked, setPicked] = useState<Array<{ id: string; label: string; kind: 'kb' | 'file'; kbId?: string }>>([])
  /** 已选库的文档（拉一次缓存）；@ 面板里可继续选文件 */
  const [docsByKb, setDocsByKb] = useState<Record<string, Array<{ id: string; label: string }>>>({})

  /** 会话级模型 / 推理等级 / 权限模式（照搬主会话输入框右侧那两枚胶囊，值以会话为准） */
  const [meta, setMeta] = useState<{
    /** 会话标题（后端首条消息后自动总结；这里随会话一起回填） */
    title: string
    modelName: string
    model: string | null
    permissionMode: PermissionMode
    effortLevel: SessionDto['effortLevel']
    ultracodeEnabled: boolean | null
  }>({ title: '', modelName: '', model: null, permissionMode: 'default', effortLevel: null, ultracodeEnabled: null })
  const [providers, setProviders] = useState<Provider[]>([])
  const [showModelPicker, setShowModelPicker] = useState(false)
  const [showEffort, setShowEffort] = useState(false)
  /**
   * 右侧抽屉的「来源视图」：点引用卡片后**在抽屉内**直接看原文并高亮命中块。
   * ⛔ 不复用知识库文档抽屉（那是带编辑/分块管理的管理视图）—— 对齐源站 ChatReferenceSourceView。
   */
  const [citeSource, setCiteSource] = useState<CitationItem | null>(null)
  const [sourceChunks, setSourceChunks] = useState<ChunkView[]>([])
  const [sourceLoading, setSourceLoading] = useState(false)
  /** 右侧「文档来源」是否展开 —— 默认关闭，点回答下方「引用 · N」才打开（对齐源站按需抽屉） */
  const [citeOpen, setCiteOpen] = useState(false)
  /**
   * 会话临时附件（`kba-` 开头）的展示：解析后的正文在右栏纯文本显示。
   * ⛔ 不能走 `citeSource`（那条按 knowledge_id 查 /chunks），临时附件没有分块。
   */
  const [tempDoc, setTempDoc] = useState<{
    id: string
    name: string
    loading: boolean
    content: string
    error?: string
  } | null>(null)

  // ── 空态推荐问题（对齐源站「你可以这样问我」+ 换一批）──
  // 源站优先用 agent 配置的 starters（curated），未配置才从范围内知识库取候选生成；
  // 本仓第一步按后者实现：取可检索库的文档标题造句，source 语义 = document。
  const [suggestPool, setSuggestPool] = useState<string[]>([])
  const [suggestBatch, setSuggestBatch] = useState(0)

  // 模型选择器的候选来自 providers（与主会话 ModelPickerModal 同一份数据）
  useEffect(() => {
    let alive = true
    providerApi.list()
      .then((list) => { if (alive) setProviders(list) })
      .catch(() => { /* 列表失败时选择器为空，不影响对话 */ })
    return () => { alive = false }
  }, [])

  // @ 候选 = 知识库列表（后端 `GET /knowledge-bases`）
  useEffect(() => {
    let alive = true
    knowledgeApi.listBases()
      .then((list) => { if (alive) setKbs(list.map((k) => ({ id: k.id, label: k.name }))) })
      .catch(() => { /* 列表失败：@ 不可用，不影响直接提问 */ })
    return () => { alive = false }
  }, [])

  // 拉每个库的文档（懒加载一次、按库缓存）：@ 面板「文件」分类要跨库列出，右侧标所属库
  useEffect(() => {
    for (const kb of kbs) {
      if (docsByKb[kb.id]) continue
      void knowledgeApi.listKnowledge(kb.id, { page: 1, page_size: 200 })
        .then((r) => {
          const items = (r.data ?? []).map((k) => ({ id: k.id, label: k.title || k.file_name || k.id }))
          setDocsByKb((prev) => (prev[kb.id] ? prev : { ...prev, [kb.id]: items }))
        })
        .catch(() => { /* 拉不到：@ 里就只有知识库，不阻断对话 */ })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbs])

  // ── 按会话切片：与主对话互不干扰 ──
  const messages = useChatStore((s) => (sessionId ? s.messages[sessionId] ?? EMPTY_MESSAGES : EMPTY_MESSAGES))
  const blocks = useChatStore((s) => (sessionId ? s.streams[sessionId] ?? EMPTY_BLOCKS : EMPTY_BLOCKS))
  const serverRunning = useChatStore((s) => (sessionId ? !!s.serverRunning[sessionId] : false))
  const conversationId = useChatStore((s) => (sessionId ? s.conversationIds[sessionId] ?? null : null))
  /** 知识库引用：RAG 在生成前推 'citations'，按 assistantMessageId 存在 store（本页只做实时，历史回放未接） */
  const citationMap = useChatStore((s) => (sessionId ? s.citations[sessionId] ?? EMPTY_CITATION_MAP : EMPTY_CITATION_MAP))

  /** 把会话的模型/推理/权限回填到胶囊（与主会话展示同一组字段）。 */
  const applyMeta = useCallback((sess: { id?: string; title?: string | null; modelName?: string | null; model?: string | null; permissionMode?: PermissionMode | null; effortLevel?: SessionDto['effortLevel']; ultracodeEnabled?: boolean | null }) => {
    setMeta({
      title: sess.title ?? '',
      modelName: sess.modelName ?? '',
      model: sess.model ?? null,
      permissionMode: (sess.permissionMode ?? 'default') as PermissionMode,
      effortLevel: sess.effortLevel ?? null,
      ultracodeEnabled: sess.ultracodeEnabled ?? null,
    })
    // 会话没写模型 ⇒ 显示并使用「默认模型」（与主会话新建会话同一个取值链），
    // 并回写会话（幂等，一次到位）—— 否则胶囊空着，每轮都靠后端回落，用户看不出用的什么模型。
    if (!sess.modelName && sess.id) {
      const id = sess.id
      void resolveNewSessionModel().then((def) => {
        if (!def) return
        setMeta((m) => (m.modelName ? m : { ...m, modelName: def }))
        sessionApi.update(id, { modelName: def }).catch(() => { /* 回写失败不阻断对话 */ })
      })
    }
  }, [])

  /** 权限模式：会话级覆盖（与主会话同一个 PATCH）。 */
  const changePermission = useCallback(async (mode: PermissionMode) => {
    if (!sessionId) return
    setMeta((m) => ({ ...m, permissionMode: mode }))
    try {
      await sessionApi.update(sessionId, { permissionMode: mode })
      showToast(`权限模式 → ${PERMISSION_MODE_LABELS[mode]}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [sessionId, showToast])

  /** 会话主模型：只写本会话（⛔ 不动全局 settings.mainModelName）。 */
  const pickModel = useCallback(async (providerName: string, modelName: string) => {
    if (!sessionId) return
    setMeta((m) => ({ ...m, modelName }))
    try {
      await sessionApi.update(sessionId, { modelName })
      showToast(`本会话模型 → ${providerName} · ${modelName}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [sessionId, showToast])

  /** 推理等级：走后端 /effort（与主会话同一个命令），ultracode 是独立概念。 */
  const saveEffort = useCallback(async (v: NonNullable<SessionDto['effortLevel']> | 'ultracode') => {
    if (!sessionId) return
    const isUltra = v === 'ultracode'
    setMeta((m) => ({ ...m, effortLevel: (isUltra ? 'xhigh' : v) as NonNullable<SessionDto['effortLevel']>, ultracodeEnabled: isUltra }))
    try {
      await commandApi.executeBuiltin('effort', sessionId, { args: v })
      showToast(`推理等级 → ${isUltra ? 'ultracode' : v === 'off' ? '关闭思考' : v}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [sessionId, showToast])

  /** 懒创建会话：固定挂到知识库工作区项目下（服务端按 path 幂等）。 */
  const ensureSession = useCallback(async (force = false): Promise<string | null> => {
    if (sessionId && !force) return sessionId
    if (creatingRef.current) return null
    creatingRef.current = true
    setCreating(true)
    try {
      const workspace = await knowledgeApi.workspaceProject()
      // ⭐ 复刻主会话的「空白会话唯一」规则（App.tsx createSession）：
      //   本项目下已有「没发过消息」的会话 ⇒ 复用它并切过去，⛔ 不再新建。
      //   所以「新对话」点多少次都只有一条空白会话，也不会停在一条有历史的旧会话上。
      try {
        const list = await sessionApi.list()
        // 判定逐条对齐 App.tsx:1959-1967：项目内 + 后端 messageCount<=0 + 前端无消息 + 非流式。
        // ⛔ 少了后两条会把「正在跑」或「本端已加载消息」的会话误判成空白 ⇒ 点新对话像回到老会话。
        const st = useChatStore.getState()
        const empty = list.find((s) =>
          s.mainProjectId === workspace.id
          && (s.messageCount ?? 0) <= 0
          && (st.messages[s.id]?.length ?? 0) === 0
          && !st.streams[s.id])
        if (empty) {
          setSessionId(empty.id)
          onSessionChange?.(empty.id)
          applyMeta(empty)
          if (empty.mainThreadAgent) setAgentMode(empty.mainThreadAgent)
          try { localStorage.setItem(KB_CHAT_SESSION_KEY, empty.id) } catch { /* 隐私模式忽略 */ }
          return empty.id
        }
      } catch {
        // 列表拉不到就往下走新建（不影响对话）
      }
      // ⛔ 不写死标题：交给后端首条消息后自动总结（与主会话同一个 maybeGenerateTitle），
      //   否则会话列表里永远是「知识库对话」这一条固定名，分不出是哪次问的什么。
      //   ⚠️ 后端要求 title / modelName 至少给一个 ⇒ 只给 modelName。
      const created = await sessionApi.create({
        modelName: await resolveNewSessionModel(),
        mainProjectId: workspace.id,
      })
      // 默认模式写进会话（失败不阻断对话：模型仍可用，只是工具集不同）
      try {
        await sessionApi.update(created.id, { mainThreadAgent: DEFAULT_KB_MODE })
      } catch {
        showToast('模式设置失败，本会话按默认模式运行', 'info')
      }
      setSessionId(created.id)
      onSessionChange?.(created.id)
      applyMeta(created)
      try { localStorage.setItem(KB_CHAT_SESSION_KEY, created.id) } catch { /* 隐私模式忽略 */ }
      return created.id
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return null
    } finally {
      creatingRef.current = false
      setCreating(false)
    }
  }, [sessionId, showToast, onSessionChange, applyMeta])

  // 宿主点「新对话」：即便当前停在一条有历史的会话上，也进入空白会话（有则复用、无则新建）
  useEffect(() => {
    if (!newChatToken) return
    setSessionId(null)
    setPicked([])
    setText('')
    void ensureSession(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [newChatToken])

  // 从左侧历史列表点开某个会话：切到它（不新建、不复用缓存）
  useEffect(() => {
    if (!initialSessionId || initialSessionId === sessionId) return
    setSessionId(initialSessionId)
    try { localStorage.setItem(KB_CHAT_SESSION_KEY, initialSessionId) } catch { /* 隐私模式忽略 */ }
    void sessionApi.get(initialSessionId)
      .then((sess) => {
        applyMeta(sess)
        if (sess.mainThreadAgent) setAgentMode(sess.mainThreadAgent)
      })
      .catch(() => { /* 读不到就按默认展示，不阻断对话 */ })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialSessionId])

  // 进入页面即建会话（用户看到的就是一条可对话的空会话）
  useEffect(() => {
    if (initialSessionId) return   // 宿主已指定会话：由切换 effect 负责，别再多建一条空会话
    void ensureSession()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 打开某个会话（含从左侧历史列表点开）：把历史消息装进 store，否则切过去是空白
  useEffect(() => {
    if (!sessionId) return
    if ((useChatStore.getState().messages[sessionId]?.length ?? 0) > 0) return
    let alive = true
    void chatApi.listMessagesPage(sessionId, { limit: 200 })
      .then((r) => {
        if (!alive) return
        const st = useChatStore.getState()
        if ((st.messages[sessionId]?.length ?? 0) === 0) st.setMessages(sessionId, r.messages)
      })
      .catch(() => { /* 拉不到就显示空，不阻断发送 */ })
    return () => { alive = false }
  }, [sessionId])

  /** 独立窗口发送：登记活跃流（驱动 {@link useChatSocket} 订阅），再调会话发送接口。
   *  流式块 / 工具卡由主会话那条「STOMP → store」管道写入本窗口 store，MessageList 直接渲染。 */
  const selfSend = useCallback(
    async (sid: string, content: string, uploaded?: AttachmentRequest[]) => {
      // 本地先插 user 气泡（与主对话 sendMessage 同做法；assistant 内容由 STOMP 流入 store）
      const localUserId = `local-u-${Date.now()}`
      // ① @ 选中的库/文件 = 知识库范围快照（后端落 messages.user_attachments，气泡上显示胶囊）
      const pickedSnapshot = picked.length
        ? picked.map((p) => ({
            // type=kb 让气泡按源站「@引用」渲染（正文上方 chip）；filename 只放名称，类型由图标区分
            type: p.kind === 'kb' ? 'kb' : 'file',
            filename: p.label,
            // ⭐ 文件项 = 知识库文档 ⇒ 把文档 id 带进快照（contentId 承载）。
            //   气泡点击预览时走 `/api/v1/knowledge/{id}/download`，
            //   ⛔ 不走附件 base64/path 三态 —— 知识库文档本来就没有那些源
            //   （2026-11-14 用户报「独立预览报『附件内容不可用（缺少 url/path/base64 源）』」）。
            contentId: p.kind === 'file' ? p.id : undefined,
          }))
        : []
      // ② 输入框里**主动上传的文件**（Composer 的附件通道）必须一并带上 —— 此前被整条丢弃：
      //   后端收不到 ⇒ 模型读不到、气泡也不显示（2026-11-14 用户报「上传的 xlsx 无法读取、气泡没有附件」）。
      const attachments = [...pickedSnapshot, ...(uploaded ?? [])]
      useChatStore.getState().appendMessages(sid, [{
        id: localUserId, sessionId: sid, role: 'user', author: '你',
        content, reasoning: null, toolCalls: null, finishReason: null,
        userAttachments: attachments.length ? attachments : undefined,
      } as ChatMessageDto])
      setActiveStreams((prev) => ({ ...prev, [sid]: `/topic/sessions/${sid}/stream` }))
      try {
        // 统一入口：后端按会话 agent_mode 分流（quick-answer → RAG 流水线，其余 → ReAct）。
        // ⛔ 前端不判断模式 —— 那会把分流规则抄两遍，且改一处漏一处。
        // 已选 = 输入框上方的 chip（对齐源站 mentioned_items）：正文保持用户原话，不掺 @ 文本。
        // kb 进 knowledgeBaseIds、file 进 knowledgeIds；选了文件时把它所属库一并带上（否则库范围为空）。
        const kbIds = new Set<string>()
        const docIds: string[] = []
        for (const p of picked) {
          if (p.kind === 'kb') kbIds.add(p.id)
          else {
            docIds.push(p.id)
            if (p.kbId) kbIds.add(p.kbId)
          }
        }
        // 上传文件的临时附件 id（后端据此把正文内联进 user content）
        const attachmentIds = (uploaded ?? [])
          .map((a) => a.contentId)
          .filter((x): x is string => !!x)
        const body = {
          content,
          attachmentIds: attachmentIds.length ? attachmentIds : undefined,
          // 改写/实体抽取/生成都用这个模型（不传 ⇒ 后端「无可用改写模型」直接降级为原问题，
          // 用户实测 query_understand 报「未使用改写模型，已按原问题检索」就是这个原因）
          modelName: meta.modelName || undefined,
          knowledgeBaseIds: kbIds.size ? [...kbIds] : undefined,
          knowledgeIds: docIds.length ? docIds : undefined,
          // 选中的库/文件作为附件快照随消息落库（messages.user_attachments）
          attachments,
        } as Parameters<typeof knowledgeApi.kbChat>[1]
        // 用后端返回的 userMessageId 落一条正式行（带附件快照），本地乐观行退场：
        // · 与后端 message.user 推送同 id ⇒ appendMetaUser 幂等跳过，气泡上的知识库/文件胶囊才留得住
        //   （推送体里没有 attachments，只靠删本地行会让 chip 消失 —— 用户 2026-11-13 报）
        // · 与主会话「乐观行 id = resp.userMessageId」同一语义
        const created = await knowledgeApi.kbChat(sid, body)
        const st = useChatStore.getState()
        if (created?.userMessageId) {
          // 后端 message.user 可能先于这个响应到达（appendMetaUser 插入的行**不带** attachments）：
          // insertServerMessages 是同 id 幂等跳过 ⇒ 必须先删掉那条无附件的同 id 行，
          // 否则带附件快照的这条永远插不进去（用户实测「指定了知识库，气泡上没有胶囊」）。
          st.removeMessage(sid, created.userMessageId)
          st.insertServerMessages(sid, [{
            id: created.userMessageId, sessionId: sid, role: 'user', author: '你',
            content, reasoning: null, toolCalls: null, finishReason: null,
            inputTokens: null, outputTokens: null, reasoningDurationMs: null,
            time: null, createdAt: new Date().toISOString(),
            toolCallId: null, assistantMessageId: null, userMessageId: created.userMessageId,
            subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
            error: null, errorDetails: null, matchedRule: null,
            userAttachments: attachments,
          } as ChatMessageDto])
        }
        st.removeMessage(sid, localUserId)
        // 本轮的范围已随请求发出 ⇒ 输入框里的 chip 属于这条消息，发完即清（失败分支不清，便于重试）
        setPicked([])
        // 标题由后端首条消息后自动总结（异步）⇒ 过几秒回读一次，左上角显示的就是总结后的标题
        window.setTimeout(() => {
          void sessionApi.get(sid)
            .then((sess) => setMeta((m) => ({ ...m, title: sess.title ?? m.title })))
            .catch(() => { /* 读不到就保持旧标题 */ })
        }, 4000)
      } catch (e) {
        setActiveStreams((prev) => {
          const next = { ...prev }
          delete next[sid]
          return next
        })
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      }
    },
    [picked, showToast, meta.modelName],
  )

  /** 发送：主窗口走 App 运行时；独立窗口走自建通道。 */
  const doSend = useCallback(
    // 附件必传：Composer 里主动上传的文件走这条（后端 GatewayRequest.attachments 原样收下）。
    // 旧注释「附件暂不传」已过时 —— 不传就是模型读不到、气泡也不显示。
    (attachments?: AttachmentRequest[]) => {
      const sid = sessionId
      if (!sid) {
        void ensureSession()
        return
      }
      void selfSend(sid, text, attachments)
      setText('')
    },
    [ensureSession, selfSend, sessionId, text],
  )

  /**
   * 停止：按模式分流 —— 两条链的停止接口不同，⛔ 不能都打 /sessions/{sid}/cancel。
   * · 快速问答（RAG）：`POST /knowledge-chat/{sid}/stop`（KbQaTurnService 自己的取消标志）
   * · 智能推理（ReAct）：`POST /sessions/{sid}/cancel`
   */
  const doStop = useCallback(() => {
    if (!sessionId) return
    if (agentMode === DEFAULT_KB_MODE) {
      void knowledgeApi.stopQa(sessionId).catch(() => { /* 已结束/未开始：忽略 */ })
    } else {
      void chatApi.cancel(sessionId)
    }
  }, [sessionId, agentMode])

  /** 切换模式：写会话 main_thread_agent（下次发送生效） */
  const pickMode = useCallback(
    (key: string) => {
      if (!sessionId) return
      setAgentMode(key)
      void sessionApi.update(sessionId, { mainThreadAgent: key }).catch((e) => {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      })
      // ⚠️ 切模式后强制重拉一次历史：本轮 store 里若只有刚才那几轮（或只有当轮），
      //    而「进入会话」的加载条件是「store 非空就不拉」⇒ 历史会永久缺失，
      //    用户看到的就是「只剩当轮」（2026-11-14 用户报）。
      //    切模式时不可能在流式，覆盖安全。
      void chatApi.listMessagesPage(sessionId, { limit: 200 })
        .then((r) => {
          if (r.messages.length > 0) useChatStore.getState().setMessages(sessionId, r.messages)
        })
        .catch(() => { /* 拉不到就保持现状，不阻断 */ })
    },
    [sessionId, showToast],
  )

  /** 点引用卡片 → 切到来源视图（文档原文 + 高亮命中块） */
  const openCiteSource = useCallback((c: CitationItem) => {
    setCiteSource(c)
  }, [])

  // 来源视图的数据：按 knowledge_id 拉分块（原文按块展示，命中块高亮）
  useEffect(() => {
    if (!citeSource?.knowledge_id) { setSourceChunks([]); return }
    let alive = true
    setSourceLoading(true)
    void knowledgeApi.listChunks(citeSource.knowledge_id)
      .then((rows) => { if (alive) setSourceChunks(rows) })
      .catch(() => { if (alive) setSourceChunks([]) })
      .finally(() => { if (alive) setSourceLoading(false) })
    return () => { alive = false }
  }, [citeSource])

  // 滚到命中块（源站同款：打开即定位到引用那段）
  useEffect(() => {
    if (!citeSource?.chunk_id) return
    const t = window.setTimeout(() => {
      const el = document.querySelector(`[data-src-chunk="${citeSource.chunk_id}"]`)
      if (el) (el as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, 180)
    return () => window.clearTimeout(t)
  }, [citeSource, sourceChunks.length])

  /** 当前轮 assistantMessageId：优先最后一块（流式中），否则最后一条 assistant 消息 */
  const lastAssistantId = (() => {
    if (blocks.length > 0) return blocks[blocks.length - 1].assistantMessageId
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant') return messages[i].id
    }
    return ''
  })()
  const citationsAll = lastAssistantId ? (citationMap[lastAssistantId] ?? EMPTY_CITATIONS) : EMPTY_CITATIONS
  // 「N 个文档」按 knowledge_id 去重：一轮引用通常命中同一文档的多个块（实测 4 条引用 = 1 个文档），
  //   直接用 citations.length 会把它说成 4 个文档。knowledge_id 缺失时才退回引用条数。
  const citedDocCount = useMemo(
    () => new Set(citationsAll.map((c) => c.knowledge_id).filter(Boolean)).size || citationsAll.length,
    [citationsAll],
  )
  // 一轮引用不会很多：兜底接口若把多轮混进来，这里截断，避免「引用 · 160」这种噪声
  const citations = citationsAll.length > 20 ? citationsAll.slice(0, 20) : citationsAll
  // 换了一轮就收起旧抽屉（源站同样在会话/消息切换时 close）
  useEffect(() => { setCiteOpen(false) }, [lastAssistantId])

  // 历史回放：切会话 / 刷新后 store 里没有引用（STOMP 事件已过去）⇒ 拉会话级权威接口补齐。
  // 对齐源站 message.KnowledgeReferences：引用挂在 assistant 消息上，刷新后**每一轮**都要能重画；
  // 后端返回会话内全部行，这里按 assistantMessageId 分桶写回 store（旧实现只补最新一轮）。
  useEffect(() => {
    if (!sessionId) return
    // 生成中不做回放：实时 citations 事件马上就到，抢跑会用旧数据覆盖
    if (blocks.length > 0 || serverRunning) return
    const hasGap = messages.some((m) => m.role === 'assistant' && (citationMap[m.id] ?? []).length === 0)
    if (!hasGap) return
    let alive = true
    void knowledgeApi.listSessionCitations(sessionId)
      .then((list) => {
        if (!alive || !list || list.length === 0) return
        const buckets: Record<string, NonNullable<typeof list>> = {}
        for (const c of list) {
          const mid = c.assistantMessageId || ''
          if (!mid || mid === 'msg-stub-pending') continue
          ;(buckets[mid] ??= []).push(c)
        }
        const store = useChatStore.getState()
        for (const [mid, rows] of Object.entries(buckets)) {
          if ((citationMap[mid] ?? []).length > 0) continue
          store.setCitations(sessionId, mid, rows.map((c) => ({
            ref_index: c.refIndex,
            chunk_id: c.chunkId,
            knowledge_id: c.knowledgeId,
            knowledge_base_id: c.knowledgeBaseId,
            knowledge_title: c.knowledgeTitle,
            snippet: c.snippet,
            chunk_index: c.chunkIndex,
          })))
        }
      })
      .catch(() => { /* 没引用是正常状态，不打扰用户 */ })
    return () => { alive = false }
  }, [sessionId, messages, citationMap, blocks.length, serverRunning])

  // 每条 assistant 消息自己的「引用编号 → 文档名」：同一条会话里不同消息的 [1] 指向不同文档
  const citationTitlesByMessage = useMemo(() => {
    const out: Record<string, Record<number, string>> = {}
    for (const [mid, rows] of Object.entries(citationMap)) {
      const titles: Record<number, string> = {}
      for (const c of rows) {
        const t = (c.knowledge_title || '').trim()
        if (t) titles[c.ref_index] = t
      }
      if (Object.keys(titles).length > 0) out[mid] = titles
    }
    return out
  }, [citationMap])

  useEffect(() => {
    // 只在新对话空态拉一次候选（有消息后不再请求）
    if (messages.length > 0 || blocks.length > 0) return
    let alive = true
    void (async () => {
      try {
        const bases = await knowledgeApi.listBases()
        const pool: string[] = []
        // ① 块级「辅助召回问题」：库里**已经生成过**的真实问题
        //    （kb_chunk.metadata.generated_questions[]，本库实测 437 块 / 1311 条）。
        //    ⛔ 不自己造「介绍一下《X》」这种模板问题 —— 用户 2026-11-13 明确否掉。
        for (const b of bases.slice(0, 6)) {
          try {
            const page = await knowledgeApi.listKnowledge(b.id, { page: 1, page_size: 3 })
            for (const d of page.data ?? []) {
              try {
                const chunks = await knowledgeApi.listChunks(d.id)
                for (const c of chunks) {
                  if (!c.metadata) continue
                  try {
                    const meta = JSON.parse(c.metadata) as { generated_questions?: Array<{ question?: string }> }
                    for (const q of meta.generated_questions ?? []) {
                      const text = (q.question ?? '').trim()
                      if (text && !pool.includes(text)) pool.push(text)
                    }
                  } catch { /* 坏 JSON 跳过该块 */ }
                }
              } catch { /* 单篇拉不到跳过 */ }
              if (pool.length >= 18) break
            }
          } catch { /* 单个库拉不到不影响其它库 */ }
          if (pool.length >= 18) break
        }
        // ② 没有块问题时退到图谱实体（真实业务概念，按关联块数排序）
        if (pool.length === 0) {
          for (const b of bases.slice(0, 6)) {
            try {
              const g = await knowledgeApi.getGraph(b.id)
              const ents = (g.entities ?? [])
                .filter((e) => e.name && e.name.trim().length >= 2 && e.name.trim().length <= 30)
                .sort((x, y) => (y.source_chunk_ids?.length ?? 0) - (x.source_chunk_ids?.length ?? 0))
              for (const e of ents.slice(0, 10)) {
                const n = e.name!.trim()
                const type = (e.entity_type ?? '').toLowerCase()
                const isOrg = /(公司|集团|银行|局|中心|委员会|政府|学校|医院|事务所|有限)/.test(n)
                const q = isOrg ? `介绍一下${n}`
                  : type.includes('work') ? `《${n}》主要规定了什么？`
                  : `什么是${n}？`
                if (!pool.includes(q)) pool.push(q)
              }
            } catch { /* 同上 */ }
            if (pool.length >= 18) break
          }
        }
        // ③ 最后兜底：文档标题（去扩展名 / 日期前缀）
        if (pool.length === 0) {
          for (const b of bases.slice(0, 4)) {
            try {
              const page = await knowledgeApi.listKnowledge(b.id, { page: 1, page_size: 6 })
              for (const d of page.data ?? []) {
                const raw = (d.title || d.file_name || '').trim()
                const name = raw.replace(/\.[a-z0-9]{1,5}$/i, '').replace(/^\d{4}-\d{2}-\d{2}\[?|\]?$/g, '').trim()
                if (name) pool.push(`《${name}》讲了什么？`)
              }
            } catch { /* 同上 */ }
          }
        }
        if (alive) setSuggestPool(pool)
      } catch { /* 推荐问题是增强：失败就不显示 */ }
    })()
    return () => { alive = false }
  }, [messages.length, blocks.length])

  /** 推荐问题：池子本身就是「问题」（图谱实体/文档名各按类型成句），按批次轮转，换一批 = 后移 6 条 */
  const suggestQuestions = (() => {
    if (suggestPool.length === 0) return []
    const n = Math.min(6, suggestPool.length)
    return Array.from({ length: n }, (_, i) => suggestPool[(suggestBatch * 6 + i) % suggestPool.length])
  })()

  const streaming = blocks.length > 0 || serverRunning
  // 标题回读：后端在「本轮 complete 之后」才异步总结标题 ⇒ 以 streaming true→false 为触发点，
  // 并在其后补两次延时拉取（总结任务本身是异步的，拉早一次仍可能是旧标题）。
  useEffect(() => {
    if (!sessionId || streaming) return
    let alive = true
    const pull = () => {
      void sessionApi.get(sessionId)
        .then((sess) => { if (alive && sess.title) setMeta((m) => ({ ...m, title: sess.title ?? m.title })) })
        .catch(() => { /* 读不到保持旧标题 */ })
    }
    // 后端 maybeGenerateTitle 是在 complete 之后另起异步任务 ⇒ 立刻拉一次多半还是旧标题，
    // 再补两次延时拉取（标题只会从空/模型名变成总结文案，多拉无副作用）。
    pull()
    const t1 = window.setTimeout(pull, 2500)
    const t2 = window.setTimeout(pull, 6000)
    return () => { alive = false; window.clearTimeout(t1); window.clearTimeout(t2) }
  }, [sessionId, streaming])

  /** 标题栏文案：后端建会话时把 title 默认成 modelName ⇒ 那种值等于「没标题」，显示「新对话」。 */
  const titleText = !meta.title || meta.title === meta.modelName || meta.title === meta.modelName.split('/').pop()
    ? '新对话'
    : meta.title

  /** 空态：无消息且不在生成 —— 与主对话一致，走 `.center.ws-center-empty` + `.composer-empty`（欢迎页居中） */
  const isEmpty = messages.length === 0 && !streaming

  return (
    // ⚠️ `.kb-new-chat` 没有全局样式（globals.css 里零命中）⇒ 必须自带 flex 高度上下文，
    //    否则 `.center` 量不到高度，消息区/输入框会全部堆到面板顶部。
    <div className="kb-new-chat" style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <IconSprite />
      <SelfSocketBridge sessionId={sessionId} activeStreams={activeStreams} />
      {/* 左上角会话标题（对齐主会话 CenterTabs 的 tab 形态：模型标签 + 标题） */}
      <div style={TITLE_BAR}>
        <span style={TITLE_TAG}>{meta.modelName ? meta.modelName.split('/').pop() : 'nexus'}</span>
        <span style={TITLE_TEXT} title={titleText}>{titleText}</span>
      </div>
      <div style={BODY_ROW}>
      <div
        className={`center${isEmpty ? ' ws-center-empty' : ''}`}
        // 空态保持产品原有的 grid 居中（hero）；有消息时改成列布局，
        // 让消息区自己滚动、输入框贴底 —— 否则内容一多就整块贴到面板顶部（用户 2026-11-13 报）。
        style={isEmpty
          ? { flex: 1, minHeight: 0 }
          : { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}
      >
        <div className="stream" style={isEmpty ? undefined : { flex: 1, minHeight: 0, overflowY: 'auto' }}>
          {sessionId ? (
            <MessageList
              // 引用标题是异步到的（刷新后由回放接口补）⇒ 用标题签名当 key：
              // 从「▤ 编号」变成「▤ 文档名」时重挂载一次，否则 Message 是 memo，不会因模块级标题表变化而重渲染。
              key={citations.map((c) => `${c.ref_index}:${c.knowledge_title ?? ''}`).join('|') || 'no-cite'}
              sessionId={sessionId}
              messages={messages}
              onDelete={() => {}}
              conversationId={conversationId}
              // 正文里的 [N] chip 点击 → 打开右侧「文档来源」并滚到该条（对齐源站：点引用直接看来源）
              citationTitlesByMessage={citationTitlesByMessage}
              // 用户气泡里的知识库文件 chip → 在同一右侧抽屉打开原文。
              // ⛔ 不要退回 openStandalone（独立窗口）：知识库场景期望在页面内预览，
              //    且独立窗口走 /knowledge/{id}/download，这条链在本页没有登录上下文时取不到字节。
              onOpenKbDoc={(knowledgeId, title) => {
                // `kba-` = 会话临时附件（上传解析那条链）⇒ 走附件详情，不查分块
                if (knowledgeId.startsWith('kba-')) {
                  setTempDoc({ id: knowledgeId, name: title ?? '附件', loading: true, content: '' })
                  if (!sessionId) return
                  void knowledgeApi.getChatAttachment(sessionId, knowledgeId)
                    .then((r) => setTempDoc((cur) => (cur && cur.id === knowledgeId
                      ? {
                          ...cur,
                          loading: false,
                          content: r.content ?? '',
                          error: r.status === 'ready' ? undefined : (r.errorMessage || '附件尚未解析完成'),
                        }
                      : cur)))
                    .catch((e) => setTempDoc((cur) => (cur && cur.id === knowledgeId
                      ? { ...cur, loading: false, error: e instanceof Error ? e.message : String(e) }
                      : cur)))
                  return
                }
                setCiteOpen(true)
                setCiteSource({ ref_index: 0, knowledge_id: knowledgeId, knowledge_title: title ?? null })
              }}
              // 引用入口挂在最后一条回答的左下角（正文之后、复制/操作条之上），与源站一致
              tailFooter={citations.length > 0 && !citeOpen ? (
                <div style={{ display: 'flex', paddingTop: 8 }}>
                  <button type="button" className="kb-cite-open" onClick={() => setCiteOpen(true)}>
                    本次引用了 {citedDocCount} 个文档
                  </button>
                </div>
              ) : undefined}
              onCitationClick={(msgId, n) => {
                // 源站点击引用 = 直接进「来源详情视图」并在原文里定位高亮（useCitationPopover openSource）。
                // ⛔ 不要先出「文档列表 → 块列表」两级中间页。
                const hit = (citationMap[msgId] ?? []).find((c) => c.ref_index === n)
                setCiteOpen(true)
                if (hit) openCiteSource(hit)
              }}
            />
          ) : (
            <div className="kb-new-chat-empty">{creating ? '正在准备会话…' : '暂无会话'}</div>
          )}
        </div>
        <Composer
          composerText={text}
          setComposerText={setText}
          sendMessage={(attachments) => doSend(attachments)}
          // 知识库对话的上传走独立端点（上传即解析成正文）；主会话不传此 prop，链路不变
          attachmentUploader={async (file, sid) => {
            const r = await knowledgeApi.uploadChatAttachment(sid, file)
            if (r.status !== 'ready') {
              throw new Error(r.errorMessage || '附件解析中，请稍后再发送')
            }
            return { contentId: r.id, filename: r.fileName, size: file.size }
          }}
          showToast={showToast}
          streaming={streaming}
          onStop={doStop}
          queuedCommands={[]}
          popEditable={async () => null}
          boundProjectName="知识库对话"
          onSelectProject={() => {}}
          currentModel={meta.modelName}
          permissionMode={meta.permissionMode}
          onPermissionModeChange={(m) => void changePermission(m)}
          effortLevel={meta.effortLevel}
          ultracodeEnabled={meta.ultracodeEnabled}
          onOpenModelPicker={() => setShowModelPicker(true)}
          onOpenEffort={() => setShowEffort(true)}
          empty={isEmpty}
          currentAgent={KB_MODES.find((m) => m.key === agentMode)?.label ?? null}
          agentModes={KB_MODES.map((m) => ({
            key: m.key,
            label: m.label,
            title: m.title,
            active: agentMode === m.key,
            onPick: () => pickMode(m.key),
          }))}
          sessionId={sessionId ?? ''}
          topChips={picked.length > 0 ? (
            <div style={PICKED_WRAP}>
              {picked.map((p) => (
                <span key={`${p.kind}-${p.id}`} style={PICKED_CHIP} title={p.kind === 'kb' ? `知识库：${p.label}` : `文件：${p.label}`}>
                  <span style={PICKED_ICON_WRAP} aria-hidden="true"><PickChipIcon kind={p.kind} /></span>
                  <span style={PICKED_NAME}>{p.label}</span>
                  <span
                    role="button"
                    aria-label={`移除 ${p.label}`}
                    title="移除"
                    style={PICKED_X}
                    onClick={() => setPicked((prev) => prev.filter((x) => !(x.kind === p.kind && x.id === p.id)))}
                  >×</span>
                </span>
              ))}
            </div>
          ) : null}
          atItems={[
            ...kbs.map((k) => ({ id: k.id, label: k.label, kind: 'kb' as const })),
            ...kbs.flatMap((k) => (docsByKb[k.id] ?? []).map((d) => ({
              id: d.id, label: d.label, sub: k.label, kind: 'file' as const, kbId: k.id,
            }))),
          ]}
          onAtPickItem={(item) => setPicked((prev) => {
            const kind = item.kind ?? 'kb'
            if (prev.some((p) => p.kind === kind && p.id === item.id)) return prev
            return [...prev, { id: item.id, label: item.label, kind, kbId: item.kbId }]
          })}
          minimalChrome
          minimalTools={agentMode === DEFAULT_KB_MODE}
          // 欢迎语下面展示「你可以这样问我」（源站位置）—— 传了就替换默认的三个快捷动作
          emptySuggestions={{
            questions: suggestQuestions,
            onPick: (q) => setText(q),
            onRefresh: () => setSuggestBatch((b) => b + 1),
          }}
        />
        {showModelPicker && (
          <ModelPickerModal
            providers={providers}
            activeSessionModelName={meta.modelName}
            pickCurrent={(providerName, modelName) => void pickModel(providerName, modelName)}
            close={() => setShowModelPicker(false)}
          />
        )}
        {showEffort && (
          <EffortModal
            value={meta.effortLevel ?? 'high'}
            ultracodeEnabled={meta.ultracodeEnabled}
            onSave={(v) => void saveEffort(v)}
            onClose={() => setShowEffort(false)}
          />
        )}
      </div>
      {/* 文档来源（对齐源站 ChatReferencesDrawer：回答右侧列出本轮引用）
          ⚠️ 打开条件不能只看 citations：用户气泡里的知识库文件 chip 会直接设置
             citeSource（该轮可能没有任何引用）⇒ 必须把「有来源视图」也算进去，
             否则点了 chip 右侧栏根本不出现（用户 2026-11-14 报）。 */}
      {((citeOpen && citations.length > 0) || citeSource || tempDoc) && (
        <aside style={CIT_PANEL} aria-label="文档来源">
          {!citeSource && !tempDoc && (
          <div style={CIT_HEAD}>
            <span>文档来源<span style={CIT_COUNT}> · {citations.length}</span></span>
            <button type="button" style={CIT_CLOSE} title="关闭" onClick={() => setCiteOpen(false)}>×</button>
          </div>
          )}
          {tempDoc ? (
            <>
              <div style={CIT_HEAD}>
                <span style={CIT_SRC_TITLE} title={tempDoc.name}>{tempDoc.name}</span>
                <button type="button" style={CIT_CLOSE} title="关闭" onClick={() => setTempDoc(null)}>×</button>
              </div>
              <div style={CIT_LIST}>
                {tempDoc.loading ? (
                  <div style={CIT_HINT}>正在读取附件…</div>
                ) : tempDoc.error ? (
                  <div style={CIT_HINT}>{tempDoc.error}</div>
                ) : (
                  // ⭐ 用**现成的原格式渲染器**（PDF / docx-preview / exceljs / markdown），
                  //   ⛔ 不再把解析后的纯文本摊出来（用户 2026-11-14 要求）。
                  //   取字节走临时附件自己的 raw 端点，不影响知识库文档那条下载链。
                  <KbDocPreview
                    knowledge={{
                      id: tempDoc.id,
                      file_name: tempDoc.name,
                      title: tempDoc.name,
                      file_type: kbFileExtOf({ file_name: tempDoc.name } as unknown as Knowledge),
                    } as unknown as Knowledge}
                    chunks={[]}
                    chunksLoading={false}
                    chunksError={null}
                    mode="auto"
                    onSwitchToChunks={() => {}}
                    rawBytesLoader={() => knowledgeApi.chatAttachmentBytes(sessionId ?? '', tempDoc.id)}
                  />
                )}
              </div>
            </>
          ) : citeSource ? (
            <>
              <div style={CIT_HEAD}>
                <button type="button" style={CIT_BACK} onClick={() => setCiteSource(null)}>
                  {citations.length > 0 ? `‹ 全部引用` : `‹ 返回`}
                  {citations.length > 0 && <span style={CIT_COUNT}> · {citations.length}</span>}
                </button>
                <button type="button" style={CIT_CLOSE} title="关闭" onClick={() => { setCiteSource(null); setCiteOpen(false) }}>×</button>
              </div>
              <div style={CIT_SRC_TITLE} title={citeSource.knowledge_title ?? ''}>{citeSource.knowledge_title || '未命名文档'}</div>
              <div style={CIT_LIST}>
                {sourceLoading ? (
                  <div style={CIT_HINT}>正在加载原文…</div>
                ) : sourceChunks.length === 0 ? (
                  <div style={CIT_HINT}>未取到原文（可能未解析完成）</div>
                ) : sourceChunks.map((ch) => (
                  <div
                    key={ch.id}
                    data-src-chunk={ch.id}
                    style={ch.id === citeSource.chunk_id ? CIT_SRC_BLOCK_HL : CIT_SRC_BLOCK}
                  >
                    <MarkdownTextV2 text={(ch.content ?? '').trim()} className="markdown-prose" />
                  </div>
                ))}
              </div>
            </>
          ) : (
          <>
          <div style={CIT_LIST}>
            {citations.map((c) => (
              <div
                key={`${c.ref_index}-${c.chunk_id ?? ''}`}
                data-cite-ref={c.ref_index}
                style={c.knowledge_id ? CIT_CARD_CLICKABLE : CIT_CARD}
                role={c.knowledge_id ? 'button' : undefined}
                // hover 看原文（对齐源站：鼠标移到引用卡片上能看到该块正文）
                title={(c.snippet || c.knowledge_title || '').trim() || undefined}
                onClick={() => openCiteSource(c)}
              >
                <div style={CIT_CARD_HEAD}>
                  <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="#646a73" strokeWidth="1.4" style={{ flexShrink: 0 }}>
                    <path d="M4.2 2.3h5.1L12 5v8.7H4.2z" strokeLinejoin="round" />
                    <path d="M9 2.5V5h3" strokeLinejoin="round" />
                  </svg>
                  <span style={CIT_TITLE} title={c.knowledge_title ?? ''}>{c.knowledge_title || '未命名文档'}</span>
                </div>
                {c.snippet ? (
                  <div style={CIT_SNIPPET}>
                    {typeof c.chunk_index === 'number' ? <span style={CIT_BLOCK}>第 {c.chunk_index + 1} 块</span> : null}
                    {c.snippet}
                  </div>
                ) : null}
              </div>
            ))}
          </div>
          </>
          )}
        </aside>
      )}
      </div>
    </div>
  )
}

/** 已选条目图标（对齐源站 mention-chip：知识库 = 圆形问号气泡，文件 = 文档轮廓，16×16）。 */
function PickChipIcon({ kind }: { kind: 'kb' | 'file' }) {
  if (kind === 'kb') {
    return (
      <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="#1664ff" strokeWidth="1.4">
        <circle cx="8" cy="8" r="6.1" />
        <path d="M6.3 6.3a1.75 1.75 0 1 1 2.45 1.6c-.55.25-.75.55-.75 1" strokeLinecap="round" />
        <circle cx="8" cy="11.3" r="0.85" fill="#1664ff" stroke="none" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="#646a73" strokeWidth="1.4">
      <path d="M4.2 2.3h5.1L12 5v8.7H4.2z" strokeLinejoin="round" />
      <path d="M9 2.5V5h3" strokeLinejoin="round" />
    </svg>
  )
}

/** 空引用（zustand 选择器禁止返回新对象/数组 ⇒ 模块级常量） */
const EMPTY_CITATIONS: import('@/api/types').CitationItem[] = []
const EMPTY_CITATION_MAP: Record<string, import('@/api/types').CitationItem[]> = {}
const BODY_ROW: React.CSSProperties = { flex: 1, display: 'flex', minHeight: 0 }
const CIT_CARD_CLICKABLE: React.CSSProperties = {
  padding: '8px 10px', borderRadius: 8, border: '1px solid #eff0f1', marginBottom: 8, background: '#fff',
  cursor: 'pointer',
}
const CIT_PANEL: React.CSSProperties = {
  width: 300, flexShrink: 0, borderLeft: '1px solid #e5e6eb', background: '#fff',
  display: 'flex', flexDirection: 'column', minHeight: 0,
}
const CIT_BACK: React.CSSProperties = {
  border: 0, background: 'transparent', fontSize: 12.5, fontWeight: 600,
  color: '#1664ff', cursor: 'pointer', padding: '2px 0',
}
const CIT_SRC_TITLE: React.CSSProperties = {
  padding: '0 14px 8px', fontSize: 12.5, fontWeight: 600, color: '#1f2329',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
}
const CIT_SRC_BLOCK: React.CSSProperties = {
  padding: '8px 10px', fontSize: 12.5, color: '#1f2329', lineHeight: 1.7,
  borderBottom: '1px dashed #f0f1f3',
}
const CIT_SRC_BLOCK_HL: React.CSSProperties = {
  ...CIT_SRC_BLOCK, background: '#fffbe6', boxShadow: 'inset 3px 0 0 #f7ba1e',
}
const CIT_HINT: React.CSSProperties = { padding: '10px 12px', fontSize: 12, color: '#8a8f99' }
const CIT_CLOSE: React.CSSProperties = {
  border: 0, background: 'transparent', fontSize: 16, lineHeight: 1,
  color: '#8a8f99', cursor: 'pointer', padding: '0 2px',
}
const CIT_HEAD: React.CSSProperties = { padding: '12px 14px 8px', fontSize: 13, fontWeight: 600, color: '#1f2329', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }
const CIT_COUNT: React.CSSProperties = { fontWeight: 400, color: '#8a8f99' }
const CIT_LIST: React.CSSProperties = { flex: 1, overflowY: 'auto', padding: '0 10px 12px' }
const CIT_CARD: React.CSSProperties = {
  padding: '8px 10px', borderRadius: 8, border: '1px solid #eff0f1', marginBottom: 8, background: '#fff',
}
const CIT_CARD_HEAD: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6 }
const CIT_TITLE: React.CSSProperties = {
  fontSize: 12.5, fontWeight: 600, color: '#1f2329',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
}
const CIT_BLOCK: React.CSSProperties = {
  display: 'inline-block', marginRight: 6, padding: '0 5px', borderRadius: 4,
  background: '#f2f3f5', color: '#646a73', fontSize: 11, lineHeight: '16px', verticalAlign: '1px',
}
const CIT_SNIPPET: React.CSSProperties = {
  marginTop: 4, fontSize: 12, color: '#646a73', lineHeight: 1.5,
  display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden',
}

const TITLE_BAR: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '10px 16px 6px', minHeight: 34, flexShrink: 0,
}
const TITLE_TAG: React.CSSProperties = {
  fontSize: 11, fontWeight: 600, color: '#1664ff', background: '#eaf1ff',
  borderRadius: 4, padding: '1px 6px', lineHeight: '16px', flexShrink: 0,
}
const TITLE_TEXT: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, color: '#1f2329',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
}

// ── 已选 chip 样式 · 数值逐条对齐源站 .mention-chip（Input-field.vue:3484-3579）
const PICKED_WRAP: React.CSSProperties = {
  display: 'flex', flexWrap: 'wrap', gap: 6, padding: '8px 10px 0', maxWidth: '100%',
  // 源站 `.chat-mentioned-items { margin-bottom: 2px }` —— chip 与下方文字留一点气口
  marginBottom: 2,
}
const PICKED_CHIP: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5,
  minHeight: 26, // ⭐ 源站 `.chat-mentioned-tag` 的 max-width:200px 落在**chip**上，
  maxWidth: 200, //    不是名字上 —— 名字若单独限宽会提前一半就截断（旧值 100px）
  padding: '3px 8px', borderRadius: 6, boxSizing: 'border-box',
  background: '#f2f3f5', border: '1px solid #e3e6ea',
  // 源站 `.chat-resource-chip-surface()` 的 inset 高光，缺了会显得发灰发平
  boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.72)',
  fontSize: 12, fontWeight: 500, lineHeight: '18px', color: '#1f2329',
}
const PICKED_ICON_WRAP: React.CSSProperties = {
  position: 'relative', display: 'inline-flex', width: 16, height: 16,
  alignItems: 'center', justifyContent: 'center', flexShrink: 0,
}
const PICKED_NAME: React.CSSProperties = {
  // ⛔ 不再单独限宽：截断由 chip 的 max-width:200px 统一决定（与源站 .tag_name 一致）
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
}
const PICKED_X: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  width: 14, height: 14, marginLeft: 1, borderRadius: '50%',
  fontSize: 13, lineHeight: 1, fontWeight: 400, opacity: 0.5, cursor: 'pointer', flexShrink: 0,
}
