import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { agentApi } from '@/api/agent'
import { marketApi } from '@/api/market'
import type {
  AgentListItem,
  ExternalMarketPlugin,
  MarketConnector,
  MarketExpert,
  MarketSkill,
} from '@/api/types'

/**
 * 技能市场弹窗（骨架版 · v3 mockup 结构确认）。
 * 三种 Tab：专家（本地 + 远端混排大卡）/ 技能（分类胶囊 + 精选横条 + 推荐 3 列小卡）/ 连接器（3 列小卡）。
 * 三种来源（左上角下拉切换）：腾讯 workbuddy（/api/market/* 远端）/ 建科数字插件市场（自建 MinIO，
 *   /api/market/external 解析 marketplace.json，按 category 分流）/ 本地（仅 /agents/list）。
 * - 专家「使用」：本地 agentType → App 走现有 PATCH mainThreadAgent；腾讯远端 marketId →
 *   marketApi.useExpert；建科市场专家需安装插件（当前仅展示，按钮提示开发中）。
 * - 技能/连接器「+」：骨架仅 UI 高亮（不做真实安装）。
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

/** 市场来源 id · 决定市场弹窗的数据源（腾讯 workbuddy / 自建 MinIO / 本地） */
export type MarketSourceId = 'tencent-workbuddy' | 'jianke-market' | 'local'

/** 建科数字插件市场（自建 MinIO）· 内置固定地址（用户拍板 2026-09-17）。
 *  指向 marketplace.json 索引（几 KB）——看列表只拉索引；装插件时后端按 source 只下载
 *  对应插件包 plugin.zip（按需，上千插件不整包下载）。公网入口 netHost 任意网络可达。 */
const JIANKE_MARKET_URL = 'http://101.68.93.109:9102/nexusai/marketplace/marketplace.json'

/** 市场来源下拉（多源：建科数字插件市场 / 腾讯 workbuddy / 本地 · 建科默认排首） */
const MARKET_SOURCES: { id: MarketSourceId; label: string }[] = [
  { id: 'jianke-market', label: '建科数字插件市场' },
  { id: 'tencent-workbuddy', label: '腾讯 workbuddy' },
  { id: 'local', label: '本地' },
]

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
  /** 安装/使用标识（本地=agentType · 建科远端=marketId 插件名） */
  marketId: string
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
  const [source, setSource] = useState<MarketSourceId>('jianke-market')
  const [q, setQ] = useState('')
  const [srcOpen, setSrcOpen] = useState(false)

  // ---- 建科市场安装态（前端记忆已装插件名，安装成功后标记「已安装」）----
  const [installed, setInstalled] = useState<Set<string>>(new Set())
  const [marketName, setMarketName] = useState('zjky-market')
  // 远程下载的插件专家（source='plugin'）· 供建科市场「使用」按钮定位 agentType（不混入本地来源）
  const [pluginAgents, setPluginAgents] = useState<AgentListItem[]>([])

  // ---- 数据（按来源加载 · Promise.allSettled 容错：单个源失败不影响其余渲染）----
  const [localAgents, setLocalAgents] = useState<AgentListItem[]>([])
  const [experts, setExperts] = useState<MarketExpert[]>([])
  const [skills, setSkills] = useState<MarketSkill[]>([])
  const [connectors, setConnectors] = useState<MarketConnector[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  // 建科市场插件（ExternalMarketPlugin）→ 现有 Market* 类型（按 category 分流到三 Tab）
  const toMarketExpert = (p: ExternalMarketPlugin): MarketExpert => ({
    marketId: p.name, agentName: p.name, displayName: p.displayName, description: p.description,
    tags: p.tags ?? [], categories: p.category ? [p.category] : [], remote: true,
  })
  const toMarketSkill = (p: ExternalMarketPlugin): MarketSkill => ({
    marketId: p.name, name: p.name, displayName: p.displayName, description: p.description,
    categories: p.category ? [p.category] : [], remote: true,
  })
  const toMarketConnector = (p: ExternalMarketPlugin): MarketConnector => ({
    marketId: p.name, name: p.displayName || p.name, authType: p.tags?.[0], remote: true,
  })

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    const formatFail = (e: unknown) => (e instanceof ApiError ? e.userMessage() : String(e))
    const done = () => { if (!cancelled) setLoading(false) }

    // 已安装标记初始化（权威数据源 · 后端 installed_plugins.json · 各来源共用）
    void marketApi.listInstalled().then((names) => {
      if (cancelled) return
      if (names && names.length > 0) setInstalled(new Set(names))
    }).catch(() => { /* 查询失败静默：标记为空，安装仍可用 */ })

    if (source === 'local') {
      // 本地来源：仅本地 agents（专家 Tab；技能/连接器无数据）。pluginAgents 一并记录（供「使用」定位）
      void agentApi.listAgents(sessionId)
        .then((ag) => {
          if (cancelled) return
          setLocalAgents(ag)
          setPluginAgents(ag.filter((a) => a.source === 'plugin'))
          setExperts([]); setSkills([]); setConnectors([])
        })
        .catch((e) => {
          if (cancelled) return
          setLoadError(`本地专家加载失败：${formatFail(e)}`)
          setLocalAgents([]); setExperts([]); setSkills([]); setConnectors([])
        })
        .finally(done)
      return () => { cancelled = true }
    }

    if (source === 'jianke-market') {
      // 建科数字插件市场：/api/market/external 下载解析 marketplace.json → 按 category 分流；
      // 并行取本地 agents（仅记录 source='plugin' 的插件专家，供「使用」定位 agentType）
      void Promise.allSettled([
        agentApi.listAgents(sessionId),
        marketApi.listExternal(JIANKE_MARKET_URL),
      ]).then(([agR, mR]) => {
        if (cancelled) return
        setLocalAgents([])
        setPluginAgents(agR.status === 'fulfilled' ? agR.value.filter((a) => a.source === 'plugin') : [])
        if (mR.status === 'fulfilled') {
          const m = mR.value
          if (m?.name) setMarketName(m.name)
          const pl = m?.plugins ?? []
          setExperts(pl.filter((p) => p.category === 'expert').map(toMarketExpert))
          setSkills(pl.filter((p) => p.category === 'skill').map(toMarketSkill))
          setConnectors(pl.filter((p) => p.category === 'connector').map(toMarketConnector))
        } else {
          setLoadError(`建科市场拉取失败：${formatFail((mR as PromiseRejectedResult).reason)}`)
          setExperts([]); setSkills([]); setConnectors([])
        }
      }).finally(done)
      return () => { cancelled = true }
    }

    // 腾讯 workbuddy：本地 agents + 远端 expert/skill/connector（现有 4 路）
    void Promise.allSettled([
      agentApi.listAgents(sessionId),
      marketApi.listExperts(sessionId),
      marketApi.listSkills(sessionId),
      marketApi.listConnectors(sessionId),
    ]).then(([localR, exR, skR, coR]) => {
      if (cancelled) return
      const locals = localR.status === 'fulfilled' ? localR.value : []
      setLocalAgents(locals)
      setPluginAgents(locals.filter((a) => a.source === 'plugin'))
      setExperts(exR.status === 'fulfilled' ? exR.value : [])
      setSkills(skR.status === 'fulfilled' ? skR.value : [])
      setConnectors(coR.status === 'fulfilled' ? coR.value : [])
      const failed = [localR, exR, skR, coR].filter((r): r is PromiseRejectedResult => r.status === 'rejected')
      setLoadError(failed.length
        ? `部分数据加载失败（已尽力展示可用项）：${failed.map((r) => formatFail(r.reason)).join('；')}`
        : null)
      done()
    })
    return () => { cancelled = true }
  }, [sessionId, source])

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
      // 本地来源只显示用户自己放的（非插件）agents；远程下载的插件专家归「远程」来源展示
      if (source === 'local' && a.source === 'plugin') continue
      const nm = a.agentType
      cards.push({
        key: `local:${nm}`, kind: 'local', name: nm, marketId: nm,
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
        key: `remote:${e.marketId}`, kind: 'remote', name: nm, marketId: e.marketId,
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
  }, [localAgents, experts, currentAgent, keyword, source]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 安装建科市场插件（POST /api/plugins/install · 后端自动 reconcile 市场 + 安装链）----
  const installMarketPlugin = async (pluginName: string) => {
    if (busy) {
      showToast('对话进行中，请稍后再安装', 'info')
      return
    }
    try {
      const r = await marketApi.installPlugin({
        pluginId: `${pluginName}@${marketName}`,
        marketplaceUrl: JIANKE_MARKET_URL,
        scope: 'user',
      })
      if (r.success) {
        setInstalled((prev) => new Set(prev).add(pluginName))
        showToast(`已安装 ${r.pluginName || pluginName}`, 'success')
      } else {
        showToast(r.message || '安装失败', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  // ---- 使用已安装的插件专家（agentType = <插件名>:<agent名> · 从 pluginAgents 匹配）----
  const useInstalledPlugin = (pluginName: string) => {
    const agent = pluginAgents.find((a) => a.agentType.startsWith(`${pluginName}:`))
    if (!agent) {
      showToast('该插件的专家尚未加载，请稍后重试', 'info')
      return
    }
    onUseLocalAgent?.(agent.agentType)
  }

  // ---- 使用专家（本地走 PATCH · 腾讯远端走 marketApi.useExpert · 建科市场走 installMarketPlugin）----
  const handleUse = (card: ExpertCard) => {
    if (busy) {
      showToast('对话进行中，请等当前轮完成后切换专家', 'info')
      return
    }
    if (card.inUse) return
    if (source === 'jianke-market') {
      void installMarketPlugin(card.marketId)
      return
    }
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
          {/* 市场源下拉（腾讯 workbuddy / 建科数字插件市场 / 本地） */}
          <div className="sm-source">
            <button className="sm-source-btn" onClick={() => setSrcOpen((v) => !v)} title="切换市场来源">
              <span className="sm-source-label">{MARKET_SOURCES.find((s) => s.id === source)?.label ?? '来源'}</span>
              <span className="sm-source-caret">▾</span>
            </button>
            {srcOpen && (
              <div className="sm-source-menu">
                {MARKET_SOURCES.map((s) => (
                  <div key={s.id} className={`sm-source-item${source === s.id ? ' active' : ''}`} onClick={() => { setSource(s.id); setSrcOpen(false) }}>
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
                          {source === 'jianke-market' ? (
                            installed.has(c.marketId) ? (
                              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                <span className="sm-installed-mark">已安装</span>
                                <button className="sm-use-btn" onClick={() => useInstalledPlugin(c.marketId)} title={`使用 ${c.name} 驱动会话`}>使用</button>
                              </div>
                            ) : (
                              <button className="sm-use-btn" onClick={() => void installMarketPlugin(c.marketId)} title={`安装 ${c.name}`}>安装</button>
                            )
                          ) : (
                            <button
                              className={`sm-use-btn${c.inUse ? ' used' : ''}${busy && !c.inUse ? ' disabled' : ''}`}
                              disabled={c.inUse}
                              onClick={() => handleUse(c)}
                              title={busy && !c.inUse ? '对话进行中不可切换（仅新会话可切换）' : (c.inUse ? '当前会话正在使用' : `使用 ${c.name} 驱动会话`)}
                            >
                              {c.inUse ? '使用中' : '使用'}
                            </button>
                          )}
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
                          onInstall={source === 'jianke-market' ? (id) => void installMarketPlugin(id) : undefined}
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
                          onInstall={source === 'jianke-market' ? (id) => void installMarketPlugin(id) : undefined}
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
                              : source === 'jianke-market'
                                ? (
                                    <button
                                      className={`sm-plus-btn${installed.has(c.marketId) ? ' marked' : ''}`}
                                      disabled={installed.has(c.marketId)}
                                      onClick={() => void installMarketPlugin(c.marketId)}
                                      title={installed.has(c.marketId) ? '已安装' : '安装'}
                                    >
                                      {installed.has(c.marketId) ? '✓' : '+'}
                                    </button>
                                  )
                                : (
                                    <button
                                      className={`sm-plus-btn${isMarked('connector', c.marketId) ? ' marked' : ''}`}
                                      onClick={() => toggleMark('connector', c.marketId)}
                                      title="连接（骨架：UI 占位）"
                                    >
                                      {isMarked('connector', c.marketId) ? '✓' : '+'}
                                    </button>
                                  )}
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

/** 技能小卡（精选横条 + 推荐网格共用）· 3 列小卡：图标 + 名 + 描述 2 行截断 + 右上「+」。
 *  onInstall 存在（建科市场来源）→ 「+」调安装；否则维持骨架 UI 高亮（onMark）。 */
function SkillMiniCard({ skill, marked, onMark, onInstall }: {
  skill: MarketSkill
  marked: boolean
  onMark: () => void
  onInstall?: (marketId: string) => void
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
          : onInstall
            ? (
                <button className="sm-plus-btn" onClick={() => onInstall(skill.marketId)} title="安装">
                  +
                </button>
              )
            : <button className={`sm-plus-btn${marked ? ' marked' : ''}`} onClick={onMark} title="安装（骨架：UI 占位）">{marked ? '✓' : '+'}</button>}
      </div>
      {skill.description && <div className="sm-desc two">{skill.description}</div>}
    </div>
  )
}
