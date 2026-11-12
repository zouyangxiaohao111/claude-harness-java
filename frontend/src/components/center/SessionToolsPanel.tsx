import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { sessionApi } from '@/api/sessions'
import type { SessionToolCategory, SessionToolDto } from '@/api/types'
import { ApiError } from '@/api/rest'

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
}

/**
 * 核心编排工具（Agent/TaskStop/SendMessage）：后端禁用返回 400。
 * 前端对「禁用」操作直接灰显（禁了也白禁），但若工具已处于禁用态仍允许恢复。
 */
const CORE_ORCHESTRATION_TOOLS = new Set(['Agent', 'TaskStop', 'SendMessage'])
const isCoreTool = (name: string) => CORE_ORCHESTRATION_TOOLS.has(name)

/** 常用工具 → 图标路径（未命中回落通用齿轮） */
const TOOL_ICON_PATHS: Record<string, string> = {
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

/** 四个分类的固定元数据（顺序 = 渲染顺序） */
interface CategoryMeta {
  key: SessionToolCategory
  label: string
  iconPath: string
}
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
    key: 'SYS',
    label: '系统工具',
    iconPath: 'M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  },
]

/** 分类兜底：老后端无 category 字段时按工具名前缀推断 */
function resolveCategory(t: SessionToolDto): SessionToolCategory {
  if (t.category) return t.category
  if (t.name.startsWith('mcp__computer-use__')) return 'CU'
  if (t.name.startsWith('mcp__nexusai-in-chrome__')) return 'BR'
  return 'SYS'
}

interface CategoryGroup {
  meta: CategoryMeta
  tools: SessionToolDto[]
  total: number
  enabled: number
}

export function SessionToolsPanel({ sessionId, showToast, onClose, onCountChange, onOpenChromePanel }: Props) {
  const [tools, setTools] = useState<SessionToolDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
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

  /** 单工具启用/禁用（保留细粒度） */
  const toggleTool = async (t: SessionToolDto) => {
    if (!sessionId || pending) return
    setPending(t.name)
    try {
      const updated = await sessionApi.setToolEnabled(sessionId, t.name, !t.disabled)
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

  /** 点行：已展开则收起（用户报的「无法收起」缺陷），否则展开 */
  const selectCategory = (cat: SessionToolCategory) => {
    setActiveCat((cur) => (cur === cat ? null : cat))
  }

  const activeGroup = groups.find((g) => g.meta.key === activeCat) ?? null

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
          <span className="l1-head-count">{CATEGORIES.length} 类</span>
        </div>
        {groups.map((g) => {
          const empty = g.total === 0
          const swClass = empty
            ? 'tc-sw empty'
            : g.enabled === 0
              ? 'tc-sw'
              : g.enabled >= g.total
                ? 'tc-sw on'
                : 'tc-sw mixed'
          const sub = empty && g.meta.key === 'BR'
            ? '需安装 Chrome 扩展'
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
            <span className="tools-panel-count">{activeGroup.total} 个工具</span>
            <button
              className="tools-panel-close"
              onClick={() => setActiveCat(null)}
              title="收起明细"
              aria-label="收起明细"
            >×</button>
          </div>

          {activeGroup.total === 0 && activeGroup.meta.key === 'BR' ? (
            <div className="tools-panel-state">
              <span>浏览器扩展未启用，模型看不到这组工具。</span>
              {onOpenChromePanel && (
                <button className="tools-panel-retry" onClick={onOpenChromePanel}>检查 / 安装扩展</button>
              )}
            </div>
          ) : activeGroup.total === 0 ? (
            <div className="tools-panel-state">当前会话暂无该类工具</div>
          ) : (
            <>
              {activeGroup.meta.key === 'KB' && (
                <div className="tp-hint">仅绑定「知识库智能体」的会话生效。普通会话的模型本来没挂这组，开关无效果。</div>
              )}
              <div className="tp-status">
                <span className="tp-status-text">
                  {activeGroup.enabled === 0
                    ? <>本组工具当前<b>全部禁用</b>，模型本轮看不到它们</>
                    : <>已启用 <b>{activeGroup.enabled}</b> / {activeGroup.total}
                      {activeGroup.meta.key === 'SYS' && <span>　·　3 个编排工具始终可用</span>}
                    </>}
                </span>
                <button
                  className="tp-bulk"
                  disabled={pending != null}
                  onClick={(e) => void toggleCategory(activeGroup, e)}
                >{activeGroup.enabled >= activeGroup.total ? '全部禁用' : '全部启用'}</button>
              </div>
              <div className="tools-panel-list">
                {activeGroup.tools.map((t) => {
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
                })}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
