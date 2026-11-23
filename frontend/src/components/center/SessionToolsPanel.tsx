import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { sessionApi } from '@/api/sessions'
import type { SessionToolCategory, SessionToolDto } from '@/api/types'
import { ApiError, api } from '@/api/rest'

interface Props {
  /** 当前会话 id（空串不拉取 · 切换时组件自动重新拉取） */
  sessionId: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 关闭整个弹层（点击外部 / Esc / 关闭） */
  onClose: () => void
  /** 被禁用工具数变化通知（供入口 chip 的黄点提示） */
  onCountChange?: (count: number) => void
  /** 浏览器扩展安装/检查引导（BR 组空态按钮）*/
  onOpenChromePanel?: () => void
  /** [Q8] 本会话无人值守是否开启（关 SendUserMessage 时要判断是否会连带退出无人值守） */
  unattended?: boolean
  /** [Q8] 级联改了无人值守状态后，通知上层重拉会话模式（App 的 unattended chip 即时一致） */
  onModeChanged?: () => void
}

/**
 * 核心编排工具（Agent/TaskStop/SendMessage）：后端禁用返回 400。
 * 前端对「禁用」操作直接灰显（禁了也白禁），但若工具已处于禁用态仍允许恢复。
 */
const CORE_ORCHESTRATION_TOOLS = new Set(['Agent', 'TaskStop', 'SendMessage'])
const isCoreTool = (name: string) => CORE_ORCHESTRATION_TOOLS.has(name)

/** 常用工具 → 图标路径（未命中回落通用齿轮） */
const TOOL_ICON_PATHS: Record<string, string> = {
  // AST 结构化工具必须先于 grep/edit 判定（ToolIcon 取第一个命中的键，而 "astgrep" 含 "grep"）
  astgrep: 'M12 3v6M12 9a2 2 0 100 4 2 2 0 000-4zM12 13v4M12 17l-6 4M12 17l6 4',
  astedit: 'M12 3v6M12 9a2 2 0 100 4 2 2 0 000-4zM12 13v4M12 17l-6 4M12 17l6 4',
  bash: 'M4 5l8 7-8 7M12 19h8',
  read: 'M4 5a2 2 0 012-2h4v18H6a2 2 0 01-2-2V5zM14 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4V3z',
  edit: 'M17 3l4 4L8 20l-5 1 1-5L17 3z',
  write: 'M17 3l4 4L8 20l-5 1 1-5L17 3z',
  glob: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z',
  grep: 'M11 19a8 8 0 100-16 8 8 0 000 16zM21 21l-4.35-4.35',
  websearch: 'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a15 15 0 010 20 15 15 0 010-20',
  webfetch: 'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a15 15 0 010 20 15 15 0 010-20',
  task: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  agent: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  taskstop: 'M4 4h12v12H4zM8 8l4 4M12 8l-4 4',
  sendmessage: 'M22 2L11 13M22 2l-7 20-4-9-9-4 22-7z',
  todowrite: 'M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11',
  skill: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  notebook: 'M6 4h12a1 1 0 011 1v15a1 1 0 01-1 1H6a1 1 0 01-1-1V5a1 1 0 011-1zM9 8h6M9 12h6',
}
const DEFAULT_TOOL_PATH = 'M12 2a10 10 0 100 20 10 10 0 000-20zM12 8v5M12 16h.01'

function ToolIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  const hit = Object.keys(TOOL_ICON_PATHS).find((k) => lower.includes(k))
  const d = hit ? TOOL_ICON_PATHS[hit] : DEFAULT_TOOL_PATH
  return (
    <span className="tool-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d={d} />
      </svg>
    </span>
  )
}

/** 分类的固定元数据（顺序 = 渲染顺序） */
interface CategoryMeta {
  key: SessionToolCategory
  label: string
  iconPath: string
}
/**
 * 顺序：CU → KB → BR → MCP → IM → SYS。
 * MCP 排在「系统工具」之前 —— 它是**可关闭的第三方能力**（一个 server 可能带几百个工具），
 * SYS 是内置工具，二者视觉上必须分开（用户 2026-11-16：「mcp 出现系统工具中 导致无法动态关闭指定的 mcp」）。
 * IM 是通讯类能力（SendImAsUser / ListImChats / ReadImMessages / SearchImContacts /
 * SendUserMessage / 本机微信记录工具），
 * 同样独立成组、排在 SYS 之前。
 */
/**
 * 归入「应用层」的分类（当前**只有 MCP**）。
 *
 * <p>2026-11-23 终稿：门 = 会话级 `enabled_apps`（启动才进工具 schema）；请求体的 `selectedApps`
 * 只是**每轮强调**、不改 schema。浏览器（BR）/ 电脑操作（CU）不需要应用指定，在本面板各自开关
 * ⇒ 本面板对 MCP 只读展示 + 历史禁用恢复，⛔ 不再维护第二套 MCP 开关。
 */
const APP_MANAGED_CATEGORIES = new Set<SessionToolCategory>(['MCP'])

const CATEGORIES: CategoryMeta[] = [
  {
    key: 'CU',
    label: '电脑操作',
    iconPath: 'M2 3h20v14H2zM8 21h8M12 17v4',
  },
  {
    key: 'KB',
    label: '知识库',
    iconPath: 'M4 5a2 2 0 012-2h4v18H6a2 2 0 01-2-2V5zM14 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4V3z',
  },
  {
    key: 'BR',
    label: '浏览器控制',
    iconPath: 'M12 2a10 10 0 100 20 10 10 0 000-20zM2 12h20M12 2a15 15 0 010 20 15 15 0 010-20',
  },
  {
    key: 'MCP',
    label: 'MCP 工具',
    // 插头：两个插脚 + 插身 + 线（不引外部资源，沿用本文件 iconPath 风格）
    iconPath: 'M9 3v6M15 3v6M7 9h10v4a5 5 0 01-10 0V9zM12 18v3',
  },
  {
    key: 'IM',
    label: 'IM 工具',
    // 聊天气泡：气泡体 + 左下小尾巴（不引外部资源，沿用本文件 iconPath 风格）
    iconPath: 'M4 5a2 2 0 012-2h12a2 2 0 012 2v9a2 2 0 01-2 2H8l-4 4V5z',
  },
  {
    key: 'SYS',
    label: '系统工具',
    iconPath: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  },
]

/**
 * IM 工具名 · 与后端 {@code SessionToolCategory.IM_TOOL_NAMES} 同口径。
 *
 * <p>⛔ 不含 SendMessage：它是核心编排工具（不可禁用），归 SYS。
 */
const IM_TOOL_NAMES = new Set([
  'SendImAsUser',
  'ListImChats',
  'ReadImMessages',
  'SearchImContacts',
  'SendUserMessage',
  'wechat_search_messages',
  'wechat_list_conversations',
  'wechat_read_conversation',
])

/** 分类兜底：老后端无 category 字段时按工具名前缀推断（CU/BR 专属前缀优先，其余 mcp__ → MCP，IM 工具名 → IM） */
function resolveCategory(t: SessionToolDto): SessionToolCategory {
  if (t.category) return t.category
  if (t.name.startsWith('mcp__computer-use__')) return 'CU'
  if (t.name.startsWith('mcp__nexusai-in-chrome__')) return 'BR'
  if (t.name.startsWith('mcp__')) return 'MCP'
  if (IM_TOOL_NAMES.has(t.name)) return 'IM'
  return 'SYS'
}

interface CategoryGroup {
  meta: CategoryMeta
  tools: SessionToolDto[]
  total: number
  enabled: number
}

export function SessionToolsPanel({ sessionId, showToast, onClose, onCountChange, onOpenChromePanel, unattended, onModeChanged }: Props) {
  const [tools, setTools] = useState<SessionToolDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  /** 浏览器扩展连接真值（GET /api/v1/browser/status）· null = 未知/请求失败 */
  const [browserConnected, setBrowserConnected] = useState<boolean | null>(null)
  /** [Q8] 关 SendUserMessage 且无人值守开启 ⇒ 先确认（取消 = 什么都不变） */
  const [confirmBriefOff, setConfirmBriefOff] = useState(false)
  /** 当前展开二级明细的类（null = 未展开） */
  const [activeCat, setActiveCat] = useState<SessionToolCategory | null>(null)
  const closeTimerRef = useRef<number | null>(null)

  const load = useCallback(async () => {
    if (!sessionId) return
    setError(null)
    try {
      const list = await sessionApi.listTools(sessionId)
      setTools(list)
      onCountChange?.(list.filter((t) => t.disabled).length)
    } catch (e) {
      setError(e instanceof ApiError ? e.userMessage() : String(e))
      setTools(null)
      onCountChange?.(0)
    }
  }, [sessionId, onCountChange])

  // 浏览器扩展连接状态（只读）· 面板打开时拉一次；用于区分「能力未开」与「扩展没连」
  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const r = await api<{ connected: boolean }>('/browser/status')
        if (alive) setBrowserConnected(!!r?.connected)
      } catch {
        if (alive) setBrowserConnected(null)
      }
    })()
    return () => { alive = false }
  }, [sessionId])

  // 会话切换 → 清空旧列表并重新拉取（监听 sessionId）
  useEffect(() => {
    setTools(null)
    setActiveCat(null)
    void load()
  }, [load])

  // Esc：先收二级，再按一次关闭整个弹层
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setActiveCat((cur) => {
        if (cur) return null
        onClose()
        return cur
      })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => () => {
    if (closeTimerRef.current != null) window.clearTimeout(closeTimerRef.current)
  }, [])

  const groups = useMemo<CategoryGroup[]>(() => {
    const byCat = new Map<SessionToolCategory, SessionToolDto[]>()
    CATEGORIES.forEach((c) => byCat.set(c.key, []))
    ;(tools ?? []).forEach((t) => {
      const cat = resolveCategory(t)
      const bucket = byCat.get(cat) ?? byCat.get('SYS')!
      bucket.push(t)
    })
    return CATEGORIES.map((meta) => {
      const list = (byCat.get(meta.key) ?? []).slice().sort((a, b) => a.name.localeCompare(b.name))
      return {
        meta,
        tools: list,
        total: list.length,
        enabled: list.filter((t) => !t.disabled).length,
      }
    })
  }, [tools])

  /**
   * 分类开关：只切本组，⛔ 不打开也不关闭二级明细。
   * 一次批量请求（后端返回整表），⛔ 不循环 N 次单工具 PATCH。
   */
  const toggleCategory = async (g: CategoryGroup, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!sessionId || pending) return
    if (g.total === 0) return // 空组（浏览器未装扩展）开关不可点
    const wantEnabled = g.enabled < g.total // 全开 → 全关；半开/全关 → 全开
    setPending(`cat:${g.meta.key}`)
    try {
      const updated = await sessionApi.setCategoryEnabled(sessionId, g.meta.key, wantEnabled)
      setTools(updated)
      onCountChange?.(updated.filter((t) => t.disabled).length)
      showToast(
        wantEnabled ? `已启用${g.meta.label}全部工具` : `已禁用${g.meta.label}工具`,
        'success',
      )
    } catch (err) {
      showToast(err instanceof ApiError ? err.userMessage() : String(err), 'info')
    } finally {
      setPending(null)
    }
  }

  /**
   * [应用层单一入口] 清除应用层分类（当前只有 MCP）的会话级禁用 —— **只做恢复**。
   *
   * <p>为什么需要：这三类已从工具面板移走，但老会话的 `disabled_tools` 里可能留着历史禁用行；
   * 应用选择器只管「本轮用哪些」，不解开历史禁用 ⇒ 没有这个入口用户就没法恢复。
   * 只对**确实有禁用行**的分类发一次整类启用请求（⛔ 不提供禁用路径）。
   */
  const clearAppManagedDisabled = async () => {
    if (!sessionId || pending) return
    setPending('app-managed')
    try {
      let updated: SessionToolDto[] | null = null
      for (const g of groups) {
        if (!APP_MANAGED_CATEGORIES.has(g.meta.key)) continue
        if (g.total <= g.enabled) continue
        updated = await sessionApi.setCategoryEnabled(sessionId, g.meta.key, true)
      }
      if (updated) {
        setTools(updated)
        onCountChange?.(updated.filter((t) => t.disabled).length)
        showToast('已恢复应用层全部工具（清除会话级禁用）', 'success')
      }
    } catch (err) {
      showToast(err instanceof ApiError ? err.userMessage() : String(err), 'info')
    } finally {
      setPending(null)
    }
  }

  /** 单工具启用/禁用（保留细粒度） */
  const toggleTool = async (t: SessionToolDto) => {
    if (!sessionId || pending) return
    setPending(t.name)
    try {
      // 第三参是**目标状态**（enabled 绝对值，非 toggle）：当前已禁用 → 目标是启用 true；
      // 当前可用 → 目标是禁用 false。⛔ 不能写 !t.disabled（会把「禁用」发成「启用」，
      // 后端 remove 为空操作 → 面板无变化 = 用户看到「点单个禁用没反应」）。
      const wantEnabled = t.disabled
      // [V329] SendUserMessage（BriefTool）的开关 = 会话 Brief 状态，与 /brief 同一份数据。
      //   ⛔ 不能走普通 disabled_tools PATCH：那样只改可见性、不改 Brief 状态，两处会漂移。
      if (t.name === 'SendUserMessage') {
        // [Q8] 无人值守开启时关 Brief：先确认（取消 = 什么都不变，也不发请求）
        if (!wantEnabled && unattended) {
          setConfirmBriefOff(true)
          return
        }
        await sessionApi.setBrief(sessionId, wantEnabled)
        await load()
        showToast(wantEnabled ? '已开启回答直达（与 /brief 同一状态）' : '已关闭回答直达（与 /brief 同一状态）', 'success')
        return
      }
      const updated = await sessionApi.setToolEnabled(sessionId, t.name, wantEnabled)
      const next = (tools ?? []).map((x) => (x.name === updated.name ? updated : x))
      setTools(next)
      onCountChange?.(next.filter((x) => x.disabled).length)
      showToast(updated.disabled ? `已禁用工具 ${updated.userFacingName}` : `已恢复工具 ${updated.userFacingName}`, 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setPending(null)
    }
  }

  /** [Q8] 用户确认「关回答直达并退出无人值守」⇒ 一次请求原子生效（⛔ 不拆两个请求）。 */
  const confirmBriefOffNow = async () => {
    if (!sessionId || pending) return
    setConfirmBriefOff(false)
    setPending('SendUserMessage')
    try {
      await sessionApi.setBrief(sessionId, false, true)
      await load()
      onModeChanged?.()
      showToast('已关闭回答直达，并已退出无人值守', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setPending(null)
    }
  }

  /** 点行：已展开则收起（用户报的「无法收起」缺陷），否则展开 */
  const selectCategory = (cat: SessionToolCategory) => {
    setActiveCat((cur) => (cur === cat ? null : cat))
  }

  /**
   * 单工具行（KB / IM / SYS 共用同一份渲染）。
   * ⚠️ 抽成函数只是去重：样式、核心工具置灰、目标状态语义均与原先内联版本一致。
   */
  const renderToolRow = (t: SessionToolDto) => {
    const coreLocked = isCoreTool(t.name) && !t.disabled
    const busy = pending === t.name
    return (
      <div
        key={t.name}
        className={`tool-row${t.disabled ? ' disabled' : ''}`}
        onClick={() => {
          // 点行展开/收起描述（⛔ 不切开关，避免误触）
        }}
      >
        <ToolIcon name={t.name} />
        <div className="tool-info">
          <div className="tool-name">{t.userFacingName || t.name}</div>
          <div className="tool-key">{t.name}</div>
          {t.description && <div className="tool-desc">{t.description}</div>}
        </div>
        {/* 单工具开关（CU / KB / BR / IM / SYS 保留细粒度；MCP 已归应用层） */}
        <button
          className={`toggle-btn ${t.disabled ? 'restore' : 'disable'}`}
          disabled={coreLocked || busy}
          onClick={(e) => { e.stopPropagation(); void toggleTool(t) }}
          title={coreLocked ? '核心编排工具不可禁用' : (t.disabled ? '恢复此工具' : '禁用此工具')}
        >
          {t.disabled ? '启用' : '禁用'}
        </button>
      </div>
    )
  }

  /** 工具面板只列非应用层分类：MCP 归应用层（门 = 会话 enabled_apps），浏览器/电脑操作 在面板内 */
  const visibleGroups = groups.filter((g) => !APP_MANAGED_CATEGORIES.has(g.meta.key))
  /** 应用层分类的历史禁用总数（>0 才显示「清除」恢复入口） */
  const appManagedDisabledCount = groups
    .filter((g) => APP_MANAGED_CATEGORIES.has(g.meta.key))
    .reduce((n, g) => n + Math.max(0, g.total - g.enabled), 0)

  const activeGroup = visibleGroups.find((g) => g.meta.key === activeCat) ?? null
  /** 状态行与「全部启用」的作用域 = 整类 */
  const scopeEnabled = activeGroup?.enabled ?? 0
  const scopeTotal = activeGroup?.total ?? 0

  if (tools == null) {
    return (
      <div className="tools-panel" role="dialog" aria-label="会话工具">
        <div className="tools-panel-header">
          <span className="tools-panel-title">工具</span>
          <button className="tools-panel-close" onClick={onClose} title="关闭" aria-label="关闭">×</button>
        </div>
        <div className="tools-panel-state">
          {error ? (
            <div className="tools-panel-error">
              <span>{error}</span>
              <button className="tools-panel-retry" onClick={() => void load()}>重试</button>
            </div>
          ) : (
            <span className="tools-panel-loading">加载中…</span>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="tool-menu-layer open" role="dialog" aria-label="会话工具">
      {/* ── 一级：分类目录（带本组开关） ── */}
      <div className="tool-cat-menu">
        <div className="l1-head">
          <span>工具分类</span>
          <span className="l1-head-count">{visibleGroups.length} 类</span>
        </div>
        {visibleGroups.map((g) => {
          const empty = g.total === 0
          const swClass = empty
            ? 'tc-sw empty'
            : g.enabled === 0
              ? 'tc-sw'
              : g.enabled >= g.total
                ? 'tc-sw on'
                : 'tc-sw mixed'
          // 空组不用「需安装扩展」一句话糊过去：按真实连接状态区分「能力未开 / 未连接 / 未知」
          const sub = empty && g.meta.key === 'BR'
            ? (browserConnected === true
              ? '扩展已连接 · 浏览器工具未开启'
              : browserConnected === false ? '未连接浏览器扩展' : '浏览器扩展状态未知')
            : empty && g.meta.key === 'CU'
              ? '功能未开启'
              : `${g.total} 个 · ${g.enabled} 个已启用`
          return (
            <div
              key={g.meta.key}
              className={`l1-item${g.enabled === 0 && !empty ? ' off' : ''}${activeCat === g.meta.key ? ' sel' : ''}`}
              onClick={() => selectCategory(g.meta.key)}
              role="button"
              aria-expanded={activeCat === g.meta.key}
            >
              <span className="l1-ico">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d={g.meta.iconPath} />
                </svg>
              </span>
              <div className="l1-body">
                <div className="l1-name">{g.meta.label}</div>
                <div className="l1-sub">{sub}</div>
              </div>
              <span
                className={swClass}
                role="switch"
                aria-checked={empty ? false : g.enabled >= g.total}
                title={empty ? '未启用' : (g.enabled >= g.total ? '全部启用中，点击全部禁用' : '点击全部启用')}
                onClick={(e) => void toggleCategory(g, e)}
              />
              <svg className="l1-arrow" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M4 2l4 4-4 4" />
              </svg>
            </div>
          )
        })}
        {/* [应用层单一入口] MCP 已从本面板移走：启停在输入框「应用」里写会话 enabled_apps。
            老会话可能带着这三类的历史禁用行 ⇒ 只给一个**恢复**入口，⛔ 不提供禁用。 */}
        {appManagedDisabledCount > 0 && (
          <button
            type="button"
            className="l1-item"
            data-testid="app-managed-history"
            disabled={pending != null}
            title="只恢复，不提供禁用（启用已由「应用」选择器按轮决定）"
            onClick={() => void clearAppManagedDisabled()}
          >
            <span className="l1-ico">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 4a8 8 0 018 8M12 20a8 8 0 01-8-8" />
                <path d="M4 8l2-4 4 2M20 16l-2 4-4-2" />
              </svg>
            </span>
            <div className="l1-body">
              <div className="l1-name">应用层历史禁用</div>
              <div className="l1-sub">{appManagedDisabledCount} 项 · 点击恢复</div>
            </div>
          </button>
        )}
      </div>

      {/* ── 二级：该类明细（并排在右，可收起） ── */}
      {activeGroup && (
        <div className="tools-panel show">
          <div className="tools-panel-header">
            <span className="tools-panel-title">
              <span className="l1-ico l1-ico-sm">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d={activeGroup.meta.iconPath} />
                </svg>
              </span>
              {activeGroup.meta.label}
            </span>
            <span className="tools-panel-count">{scopeTotal} 个工具</span>
            <button
              className="tools-panel-close"
              onClick={() => setActiveCat(null)}
              title="收起明细"
              aria-label="收起明细"
            >×</button>
          </div>

          {activeGroup.total === 0 && activeGroup.meta.key === 'BR' ? (
            <div className="tools-panel-state">
              <span>
                {browserConnected === true
                  ? '浏览器扩展已连接，但浏览器工具未开启（browser-mcp 能力关）。'
                  : browserConnected === false
                    ? '未连接浏览器扩展，模型看不到这组工具。'
                    : '浏览器扩展连接状态未知；可打开面板检查。'}
              </span>
              {onOpenChromePanel && (
                <button className="tools-panel-retry" onClick={onOpenChromePanel}>检查 / 安装扩展</button>
              )}
            </div>
          ) : activeGroup.total === 0 && activeGroup.meta.key === 'CU' ? (
            <div className="tools-panel-state">功能未开启：computer-use 能力默认关闭，工具未注册。</div>
          ) : activeGroup.total === 0 ? (
            <div className="tools-panel-state">当前会话暂无该类工具</div>
          ) : (
            <>
              {activeGroup.meta.key === 'KB' && (
                <div className="tp-hint">仅绑定「知识库智能体」的会话生效。普通会话的模型本来没挂这组，开关无效果。</div>
              )}
              <div className="tp-status">
                <span className="tp-status-text">
                  {scopeEnabled === 0
                    ? <>{'本组工具当前'}<b>全部禁用</b>，模型本轮看不到它们</>
                    : <>已启用 <b>{scopeEnabled}</b> / {scopeTotal}
                      {activeGroup.meta.key === 'SYS' && <span>　·　3 个编排工具始终可用</span>}
                    </>}
                </span>
                <button
                  className="tp-bulk"
                  disabled={pending != null}
                  onClick={(e) => void toggleCategory(activeGroup, e)}
                >{scopeEnabled >= scopeTotal ? '全部禁用' : '全部启用'}</button>
              </div>
              <div className="tools-panel-list">
                {activeGroup.tools.map((t) => renderToolRow(t))}
              </div>
            </>
          )}
        </div>
      )}
      {confirmBriefOff && (
        <div
          data-testid="brief-off-confirm"
          style={{
            position: 'fixed', inset: 0, zIndex: 1200,
            background: 'rgba(0,0,0,0.32)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            style={{
              width: 340, maxWidth: 'calc(100vw - 32px)',
              background: '#fff', color: '#1f2329', borderRadius: 10,
              padding: '18px 20px 14px', boxShadow: '0 12px 32px rgba(0,0,0,0.22)',
            }}
          >
            <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>关闭回答直达？</div>
            <div style={{ fontSize: 13, lineHeight: 1.65, color: '#4e5969', marginBottom: 16 }}>
              无人值守正在开启，关闭回答直达会同时退出无人值守。
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                onClick={() => setConfirmBriefOff(false)}
                style={{
                  padding: '6px 14px', borderRadius: 6, border: '1px solid #d9dde3',
                  background: '#fff', color: '#1f2329', cursor: 'pointer',
                }}
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => void confirmBriefOffNow()}
                style={{
                  padding: '6px 14px', borderRadius: 6, border: 'none',
                  background: '#f54a45', color: '#fff', cursor: 'pointer',
                }}
              >
                确认关闭
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
