import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { agentApi } from '@/api/agent'
import { marketApi } from '@/api/market'
import type { AgentListItem, MarketConnector, MarketExpert, MarketSkill } from '@/api/types'

/**
 * 技能市场弹窗（骨架版 · v3 mockup 结构确认）。
 * 三种 Tab：专家（本地 + 远端混排大卡）/ 技能（分类胶囊 + 精选横条 + 推荐 3 列小卡）/ 连接器（3 列小卡）。
 * - 专家「使用」：本地 agentType → App 走现有 PATCH mainThreadAgent；远端 marketId → marketApi.useExpert
 *   （后端构造成本地 agent + 设会话 mainThreadAgent）→ App 刷新 currentAgent + toast。
 * - 技能/连接器「+」：骨架仅 UI 高亮（不做真实安装）。
 * 数据源：远端走 /api/market/*（固定腾讯 workbuddy 市场源 · 多源结构预留）；本地专家走 /agents/list。
 */

/** Tab 类型 */
export type MarketTab = 'expert' | 'skill' | 'connector'

const TAB_LABELS: Record<MarketTab, string> = { expert: '专家', skill: '技能', connector: '连接器' }
/** 搜索占位随 Tab 切换 */
const SEARCH_PLACEHOLDERS: Record<MarketTab, string> = {
  expert: '搜索专家名 / 专业领域 / 描述',
  skill: '搜索技能名 / 命令 / 描述',
  connector: '搜索连接器名 / 说明',
}

/** 远端来源下拉（多源预留：现仅腾讯 workbuddy 一项，点选为占位） */
const MARKET_SOURCES = [{ id: 'tencent-workbuddy', label: '腾讯 workbuddy' }]

/** 头像兜底色板（图标 URL 缺失时用名字首字 + 色块） */
const AVATAR_COLORS = ['#CC785C', '#5B8DC9', '#5DB872', '#7B61FF', '#C99417', '#4A6CF7', '#C77B5C', '#D9534F']
function colorOf(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

/** 归一化专家卡片视图（本地/远端同构，供两列大卡统一渲染） */
interface ExpertCard {
  key: string
  kind: 'local' | 'remote'
  name: string
  subtitle: string
  desc: string
  tags: string[]
  iconUrl: string | null
  iconColor: string
  iconText: string
  /** 已安装（本地恒 true · 远端 preinstalled） */
  installed: boolean
  /** 当前会话正在使用（mainThreadAgent === 该 agentType/agentName） */
  inUse: boolean
}

export function SkillMarketModal({ sessionId, currentAgent, busy, onClose, onUseLocalAgent, onUseRemoteExpert, showToast }: {
  /** 当前会话 id（/agents/list + /market/* + POST use 需要） */
  sessionId: string
  /** 当前会话主线程 agent（null/空串=默认模式 · 专家卡「使用中」判定 + 使用后胶囊回显） */
  currentAgent?: string | null
  /** 对话进行中（turn 运行）· 禁用「使用」（浏览不受限） */
  busy?: boolean
  /** 关闭弹窗（✕ / 遮罩 / App Esc） */
  onClose: () => void
  /** 使用本地专家（agentType）→ App 调现有 handleAgentChange（PATCH mainThreadAgent）并关弹窗 */
  onUseLocalAgent?: (agentType: string) => void
  /** 使用远端专家（marketId）→ App 调 marketApi.useExpert + 刷新 currentAgent + 关弹窗 + toast */
  onUseRemoteExpert?: (expert: MarketExpert) => void
  /** 轻提示 */
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [tab, setTab] = useState<MarketTab>('expert')
  const [q, setQ] = useState('')
  const [srcOpen, setSrcOpen] = useState(false)

  // ---- 数据（单次加载 · Promise.allSettled 容错：单个源失败不影响其余渲染）----
  const [localAgents, setLocalAgents] = useState<AgentListItem[]>([])
  const [experts, setExperts] = useState<MarketExpert[]>([])
  const [skills, setSkills] = useState<MarketSkill[]>([])
  const [connectors, setConnectors] = useState<MarketConnector[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    void Promise.allSettled([
      agentApi.listAgents(sessionId),
      marketApi.listExperts(sessionId),
      marketApi.listSkills(sessionId),
      marketApi.listConnectors(sessionId),
    ]).then(([localR, exR, skR, coR]) => {
      if (cancelled) return
      setLocalAgents(localR.status === 'fulfilled' ? localR.value : [])
      setExperts(exR.status === 'fulfilled' ? exR.value : [])
      setSkills(skR.status === 'fulfilled' ? skR.value : [])
      setConnectors(coR.status === 'fulfilled' ? coR.value : [])
      const failed = [localR, exR, skR, coR].filter((r): r is PromiseRejectedResult => r.status === 'rejected')
      setLoadError(failed.length
        ? `部分数据加载失败（已尽力展示可用项）：${failed.map((r) => r.reason instanceof ApiError ? r.reason.userMessage() : String(r.reason)).join('；')}`
        : null)
      setLoading(false)
    })
    return () => { cancelled = true }
  }, [sessionId])

  // ---- 已安装计数（顶栏 · =preinstalled 或 isConnected 或本地已装专家数）----
  const installedCount = tab === 'expert'
    ? localAgents.length + experts.filter((e) => e.preinstalled).length
    : tab === 'skill'
      ? skills.filter((s) => s.preinstalled).length
      : connectors.filter((c) => c.isConnected).length

  // ---- 技能分类胶囊（从返回 categories 聚合 + 全部）----
  const skillCategories = useMemo(() => {
    const all: string[] = []
    for (const s of skills) {
      for (const c of s.categories ?? []) {
        if (c && !all.includes(c)) all.push(c)
      }
    }
    return all
  }, [skills])
  const [cat, setCat] = useState<string>('全部')

  // ---- 搜索匹配（名字/展示名/描述 contains · 大小写不敏感）----
  const keyword = q.trim().toLowerCase()
  const matchAny = (...parts: Array<string | null | undefined>) =>
    keyword.length === 0 || parts.some((p) => (p ?? '').toLowerCase().includes(keyword))

  // ---- 专家卡（本地 + 远端合并，混排双列大卡）----
  const expertCards = useMemo<ExpertCard[]>(() => {
    const cards: ExpertCard[] = []
    for (const a of localAgents) {
      const nm = a.agentType
      cards.push({
        key: `local:${nm}`, kind: 'local', name: nm,
        subtitle: a.source || '本地专家',
        desc: a.whenToUse ?? '',
        tags: [],
        iconUrl: null,
        iconColor: a.color || colorOf(nm),
        iconText: (a.agentType ?? '?').charAt(0).toUpperCase(),
        installed: true,
        inUse: !!currentAgent && currentAgent === nm,
      })
    }
    for (const e of experts) {
      const nm = e.displayName || e.agentName || e.marketId
      cards.push({
        key: `remote:${e.marketId}`, kind: 'remote', name: nm,
        subtitle: [e.profession, e.useCountDisplay ?? (e.useCount != null ? `${e.useCount} 次使用` : '')]
          .filter(Boolean).join(' · '),
        desc: e.description ?? '',
        tags: (e.tags ?? []).slice(0, 4),
        iconUrl: e.icon || null,
        iconColor: colorOf(nm),
        iconText: (nm ?? '?').charAt(0).toUpperCase(),
        installed: !!e.preinstalled,
        inUse: !!currentAgent && currentAgent === e.agentName,
      })
    }
    return cards.filter((c) => matchAny(c.name, c.subtitle, c.desc))
  }, [localAgents, experts, currentAgent, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 使用专家（本地走 PATCH · 远端走 marketApi.useExpert）----
  const handleUse = (card: ExpertCard) => {
    if (busy) {
      showToast('对话进行中，请等当前轮完成后切换专家', 'info')
      return
    }
    if (card.inUse) return
    if (card.kind === 'local') {
      const a = localAgents.find((x) => x.agentType === card.name)
      if (a) onUseLocalAgent?.(a.agentType)
    } else {
      const e = experts.find((x) => x.marketId === card.key.replace(/^remote:/, ''))
      if (e) onUseRemoteExpert?.(e)
    }
  }

  // ---- 技能过滤（分类胶囊 + 搜索）----
  const filteredSkills = useMemo(() => {
    return skills.filter((s) => {
      if (cat !== '全部' && !(s.categories ?? []).includes(cat)) return false
      return matchAny(s.displayName, s.name, s.description)
    })
  }, [skills, cat, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 连接器过滤（搜索）----
  const filteredConnectors = useMemo(() => {
    return connectors.filter((c) => matchAny(c.name, c.scope, c.status, c.authType))
  }, [connectors, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 「+」安装 UI 高亮（骨架：仅本地 Set，不做真实安装）----
  const [marked, setMarked] = useState<Set<string>>(new Set())
  const markKey = (kind: string, id: string) => `${kind}:${id}`
  const toggleMark = (kind: string, id: string) =>
    setMarked((prev) => { const n = new Set(prev); const k = markKey(kind, id); if (n.has(k)) n.delete(k); else n.add(k); return n })
  const isMarked = (kind: string, id: string) => marked.has(markKey(kind, id))

  // ---- 精选技能横条：换一换占位（从过滤结果按窗口滚动取一段）----
  const [featOffset, setFeatOffset] = useState(0)
  const FEAT_SHOW = 6
  const featured = filteredSkills.length <= FEAT_SHOW
    ? filteredSkills
    : Array.from({ length: FEAT_SHOW }, (_, i) => filteredSkills[(featOffset + i) % filteredSkills.length])

  // ---- 头像（远端 icon URL → img；缺失/本地 → 色块）----
  const renderAvatar = (c: { iconUrl: string | null; iconColor: string; iconText: string; name: string }) => (
    c.iconUrl
      ? <img className="sm-avatar sm-avatar-img" src={c.iconUrl} alt={c.name} loading="lazy" />
      : <span className="sm-avatar" style={{ background: c.iconColor }}>{c.iconText}</span>
  )

  return (
    <div className="sm-backdrop" onClick={onClose}>
      <div className="sm-panel" onClick={(e) => e.stopPropagation()}>
        {/* ===== 顶栏 ===== */}
        <div className="sm-topbar">
          <button className="sm-icon-btn" onClick={onClose} title="关闭（Esc）" aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" style={{ width: 13, height: 13 }}>
              <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" />
            </svg>
          </button>
          {/* 市场源下拉（固定腾讯 · 多源预留结构） */}
          <div className="sm-source">
            <button className="sm-source-btn" onClick={() => setSrcOpen((v) => !v)} title="市场源（多源预留）">
              <span className="sm-source-label">{MARKET_SOURCES[0].label}</span>
              <span className="sm-source-caret">▾</span>
            </button>
            {srcOpen && (
              <div className="sm-source-menu">
                {MARKET_SOURCES.map((s) => (
                  <div key={s.id} className={`sm-source-item${s.id === MARKET_SOURCES[0].id ? ' active' : ''}`} onClick={() => setSrcOpen(false)}>
                    {s.label}
                  </div>
                ))}
              </div>
            )}
          </div>
          {/* 已安装计数 + 添加（占位） */}
          <div className="sm-installed" title="已安装 = 本地已装专家 / 远端预装（preinstalled）/ 已连接连接器">
            已安装 <span className="sm-installed-n">{installedCount}</span>
          </div>
          <button className="sm-add-btn" onClick={() => showToast('「添加」为占位入口（后续接自定义安装/上架）', 'info')} title="添加（占位）">
            ＋ 添加
          </button>
        </div>

        {/* ===== 次顶栏：Tab 分段 + 搜索 ===== */}
        <div className="sm-subbar">
          <div className="sm-tabs">
            {(Object.keys(TAB_LABELS) as MarketTab[]).map((t) => (
              <button key={t} className={`sm-tab${tab === t ? ' active' : ''}`} onClick={() => setTab(t)}>
                {TAB_LABELS[t]}
              </button>
            ))}
          </div>
          <div className="sm-search">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12, color: 'var(--ink-faint)', flexShrink: 0 }}>
              <circle cx="5" cy="5" r="3.4" />
              <path d="M8.5 8.5L11 11" />
            </svg>
            <input
              className="sm-search-input"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={SEARCH_PLACEHOLDERS[tab]}
            />
            {q && (
              <button className="sm-search-clear" onClick={() => setQ('')} title="清空搜索" aria-label="清空">✕</button>
            )}
          </div>
        </div>

        {/* ===== 内容区 ===== */}
        <div className="sm-body">
          {loading ? (
            <div className="sm-loading">市场加载中…</div>
          ) : (
            <>
              {loadError && <div className="sm-banner">{loadError}</div>}

              {tab === 'expert' && (
                expertCards.length === 0 ? (
                  <div className="sm-empty">{keyword ? '没有匹配的专家' : '暂无可用专家'}</div>
                ) : (
                  <div className="sm-expert-grid">
                    {expertCards.map((c) => (
                      <div key={c.key} className={`sm-card sm-expert-card${c.kind === 'remote' ? ' remote' : ''}${c.inUse ? ' in-use' : ''}`}>
                        <div className="sm-card-head">
                          {renderAvatar(c)}
                          <div className="sm-card-title-wrap">
                            <div className="sm-card-title-row">
                              <span className="sm-card-title" title={c.name}>{c.name}</span>
                              <span className={`sm-badge ${c.kind === 'local' ? 'local' : 'remote'}`}>
                                {c.kind === 'local' ? '本地' : '远程'}
                              </span>
                              {c.kind === 'remote' && c.installed && (
                                <span className="sm-badge inst" title="市场源已内置预装">已安装</span>
                              )}
                            </div>
                            <div className="sm-card-sub">{c.subtitle}</div>
                          </div>
                          <button
                            className={`sm-use-btn${c.inUse ? ' used' : ''}${busy && !c.inUse ? ' disabled' : ''}`}
                            disabled={c.inUse}
                            onClick={() => handleUse(c)}
                            title={busy && !c.inUse ? '对话进行中不可切换（仅新会话可切换）' : (c.inUse ? '当前会话正在使用' : `使用 ${c.name} 驱动会话`)}
                          >
                            {c.inUse ? '使用中' : '使用'}
                          </button>
                        </div>
                        {c.desc && <div className="sm-desc">{c.desc}</div>}
                        {c.tags.length > 0 && (
                          <div className="sm-tags">
                            {c.tags.map((t) => <span key={t} className="sm-tag">{t}</span>)}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )
              )}

              {tab === 'skill' && (
                <div className="sm-skill-wrap">
                  {/* 分类胶囊一行 */}
                  <div className="sm-cat-row">
                    {['全部', ...skillCategories].map((c) => (
                      <button key={c} className={`sm-cat${cat === c ? ' active' : ''}`} onClick={() => setCat(c)}>{c}</button>
                    ))}
                  </div>

                  {/* 精选技能区（标题 + 换一换占位 → 横条滚动取段） */}
                  <div className="sm-section-head">
                    <span className="sm-section-title">精选技能</span>
                    <button className="sm-shuffle" onClick={() => setFeatOffset((o) => (o + FEAT_SHOW) % Math.max(filteredSkills.length, 1))} disabled={filteredSkills.length === 0}>
                      换一换
                    </button>
                  </div>
                  {featured.length === 0 ? (
                    <div className="sm-empty small">{keyword ? '没有匹配的技能' : '暂无可用技能'}</div>
                  ) : (
                    <div className="sm-skill-row">
                      {featured.map((s) => (
                        <SkillMiniCard
                          key={`feat-${s.marketId}`}
                          skill={s}
                          marked={isMarked('skill', s.marketId)}
                          onMark={() => toggleMark('skill', s.marketId)}
                        />
                      ))}
                    </div>
                  )}

                  {/* 推荐区（3 列小卡） */}
                  <div className="sm-section-head">
                    <span className="sm-section-title">推荐</span>
                  </div>
                  {filteredSkills.length === 0 ? (
                    <div className="sm-empty small">{keyword ? '没有匹配的技能' : '暂无可用技能'}</div>
                  ) : (
                    <div className="sm-skill-grid">
                      {filteredSkills.map((s) => (
                        <SkillMiniCard
                          key={`rec-${s.marketId}`}
                          skill={s}
                          marked={isMarked('skill', s.marketId)}
                          onMark={() => toggleMark('skill', s.marketId)}
                        />
                      ))}
                    </div>
                  )}
                </div>
              )}

              {tab === 'connector' && (
                filteredConnectors.length === 0 ? (
                  <div className="sm-empty">{keyword ? '没有匹配的连接器' : '暂无可用连接器'}</div>
                ) : (
                  <div className="sm-connector-grid">
                    {filteredConnectors.map((c) => {
                      const desc = [c.authType, c.scope, c.status].filter(Boolean).join(' · ')
                      return (
                        <div key={`conn-${c.marketId}`} className={`sm-card sm-mini-card${c.isConnected ? ' installed' : ''}`}>
                          <div className="sm-mini-head">
                            <span className="sm-avatar sm-avatar-sm" style={{ background: colorOf(c.name ?? c.marketId) }}>{(c.name ?? 'C').charAt(0).toUpperCase()}</span>
                            <span className="sm-mini-name" title={c.name ?? c.marketId}>{c.name ?? c.marketId}</span>
                            {c.isConnected
                              ? <span className="sm-installed-mark">已连接</span>
                              : <button
                                  className={`sm-plus-btn${isMarked('connector', c.marketId) ? ' marked' : ''}`}
                                  onClick={() => toggleMark('connector', c.marketId)}
                                  title="连接（骨架：UI 占位）"
                                >
                                  {isMarked('connector', c.marketId) ? '✓' : '+'}
                                </button>}
                          </div>
                          {desc && <div className="sm-desc one">{desc}</div>}
                        </div>
                      )
                    })}
                  </div>
                )
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/** 技能小卡（精选横条 + 推荐网格共用）· 3 列小卡：图标 + 名 + 描述 2 行截断 + 右上「+」（UI 高亮） */
function SkillMiniCard({ skill, marked, onMark }: {
  skill: MarketSkill
  marked: boolean
  onMark: () => void
}) {
  const nm = skill.displayName || skill.name || skill.marketId
  return (
    <div className={`sm-card sm-mini-card${skill.preinstalled ? ' installed' : ''}`}>
      <div className="sm-mini-head">
        {skill.icon
          ? <img className="sm-avatar sm-avatar-img sm-avatar-sm" src={skill.icon} alt={nm} loading="lazy" />
          : <span className="sm-avatar sm-avatar-sm" style={{ background: colorOf(nm) }}>{(nm ?? '?').charAt(0).toUpperCase()}</span>}
        <span className="sm-mini-name" title={nm}>{nm}</span>
        {skill.preinstalled
          ? <span className="sm-installed-mark">已安装</span>
          : <button className={`sm-plus-btn${marked ? ' marked' : ''}`} onClick={onMark} title="安装（骨架：UI 占位）">{marked ? '✓' : '+'}</button>}
      </div>
      {skill.description && <div className="sm-desc two">{skill.description}</div>}
    </div>
  )
}
