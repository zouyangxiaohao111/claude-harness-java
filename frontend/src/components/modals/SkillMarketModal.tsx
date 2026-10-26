import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { agentApi } from '@/api/agent'
import { commandApi, type CommandDto } from '@/api/command'
import { marketApi, type InstallProgress, type InstalledPluginDetail } from '@/api/market'
import { selectProjectFolder } from '@/utils/projectFolder'
import type {
  AgentListItem,
  ExternalMarketPlugin,
  MarketConnector,
  MarketExpert,
  MarketSkill,
} from '@/api/types'

/**
 * 技能市场弹窗。
 * 三种 Tab：专家（本地 + 远端混排大卡）/ 技能（分类胶囊 + 精选横条 + 推荐 3 列小卡）/ 连接器（3 列小卡）。
 * 两种来源（左上角下拉切换）：建科数字插件市场（自建 MinIO，/api/market/external 解析
 *   marketplace.json，按 category 分流）/ 本地（/agents/list + /api/command 本地技能 + 已装插件）。
 * - 专家「使用」：本地 agentType → App 走现有 PATCH mainThreadAgent；建科市场专家 → 安装插件。
 * - 技能/连接器「+」：建科市场走真实安装；本地来源只展示已装内容。
 * ⛔ 腾讯 workbuddy 源已按用户 2026-10-26 裁定删除。
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

/** 市场来源 id · 决定市场弹窗的数据源（自建 MinIO / 本地） */
export type MarketSourceId = 'jianke-market' | 'local'

/** 建科数字插件市场（自建 MinIO）· 内置固定地址（用户拍板 2026-09-17）。
 *  指向 marketplace.json 索引（几 KB）——看列表只拉索引；装插件时后端按 source 只下载
 *  对应插件包 plugin.zip（按需，上千插件不整包下载）。公网入口 netHost 任意网络可达。 */
const JIANKE_MARKET_URL = 'http://192.168.20.125:9000/nexusai/marketplace/marketplace.json'

/** 市场来源下拉（建科数字插件市场 / 本地 · 建科默认排首） */
const MARKET_SOURCES: { id: MarketSourceId; label: string }[] = [
  { id: 'jianke-market', label: '建科数字插件市场' },
  { id: 'local', label: '本地' },
]

/** 头像兜底色板（图标 URL 缺失时用名字首字 + 色块） */
const AVATAR_COLORS = ['#0f5fd4', '#5B8DC9', '#5DB872', '#7B61FF', '#C99417', '#4A6CF7', '#0a49a8', '#D9534F']

/** 分类标签中文化（服务端 tags 是英文；未命中的原样展示） */
const CATEGORY_LABELS: Record<string, string> = {
  docs: '写作', writing: '写作',
  review: '审查', quality: '质量', compliance: '合规', report: '报告',
  design: '设计', architecture: '架构',
  ppt: '演示', pptx: '演示', presentation: '演示',
  svn: 'SVN', data: '数据', analyst: '分析', engineering: '工程',
}
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

export function SkillMarketModal({ sessionId, currentAgent, busy, onClose, onUseLocalAgent, onSummonSkill, showToast }: {
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
  /** 召唤已安装技能（技能命令名 + 中文显示名）→ App 关弹窗 + 把「/命令名 」写入输入框（2026-10-19 用户需求） */
  onSummonSkill?: (commandName: string, displayName?: string) => void
  /** 轻提示 */
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [tab, setTab] = useState<MarketTab>('expert')
  const [source, setSource] = useState<MarketSourceId>('jianke-market')
  const [q, setQ] = useState('')
  const [srcOpen, setSrcOpen] = useState(false)
  // 「＋ 添加」下拉开合 + 上架弹窗分类（null=不显示上架弹窗）
  const [addOpen, setAddOpen] = useState(false)
  const [publishCat, setPublishCat] = useState<'skill' | 'expert' | null>(null)
  // 上架成功后自增 → 触发市场列表重新加载（否则新上架的条目要关掉弹窗再打开才出现）
  const [reloadKey, setReloadKey] = useState(0)

  // ---- 建科市场安装态（前端记忆已装插件名，安装成功后标记「已安装」）----
  const [installed, setInstalled] = useState<Set<string>>(new Set())
  // 已装插件的本地内容指纹（key=插件名）· 与市场指纹比对判定「有更新」
  const [installedFp, setInstalledFp] = useState<Record<string, string>>({})
  // 安装中的市场 id 集合：按钮就地转圈 + 禁用重复点击（安装是下载 + 解包，耗时可达数秒，
  // 没有反馈时用户会以为没点上而反复点，产生并发安装）。
  const [installing, setInstalling] = useState<Set<string>>(new Set())
  // 安装进度（key=市场插件名）· 轮询 /api/plugins/install/progress 得到真实百分比
  const [progress, setProgress] = useState<Record<string, InstallProgress>>({})
  const [marketName, setMarketName] = useState('zjky-market')
  // 远程下载的插件专家（source='plugin'）· 供建科市场「使用」按钮定位 agentType（不混入本地来源）
  const [pluginAgents, setPluginAgents] = useState<AgentListItem[]>([])
  // 已安装插件明细（「本地」来源展示；含版本/安装路径）
  const [pluginDetails, setPluginDetails] = useState<InstalledPluginDetail[]>([])

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
    // 分类胶囊取「category + tags」（过滤掉内部标记 skill 后就是 docs/review/design/ppt…）
    categories: [p.category ?? '', ...(p.tags ?? [])].filter(Boolean), remote: true,
    fingerprint: p.fingerprint,
  })
  const toMarketConnector = (p: ExternalMarketPlugin): MarketConnector => ({
    marketId: p.name, name: p.displayName || p.name, authType: p.tags?.[0], remote: true,
  })

  // 本地已安装技能来源：用户级 / 项目级 / 插件级（排除内置/捆绑 —— 数量大且不是用户装的）。
  // 「本地」来源过去只列专家；技能与插件从未展示（用户 2026-10-26 反馈），这里补齐。
  const LOCAL_SKILL_SOURCES = new Set(['USER', 'PROJECT_SETTINGS', 'PLUGIN'])
  const isLocalSkill = (c: CommandDto) => !!c.source && LOCAL_SKILL_SOURCES.has(c.source)
  const toLocalSkill = (c: CommandDto): MarketSkill => ({
    marketId: c.name,
    name: c.name,
    displayName: c.name,
    description: c.description ?? '',
    categories: [c.pluginName ? `插件 · ${c.pluginName}` : (c.source === 'PROJECT_SETTINGS' ? '项目' : '用户')],
    preinstalled: true,
    remote: false,
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

    // 本地指纹（判定「市场有更新」）：市场条目指纹 ≠ 本地安装目录指纹 ⇒ 显示更新
    void marketApi.listInstalledDetails().then((list) => {
      if (cancelled) return
      setPluginDetails(list ?? [])
      const map: Record<string, string> = {}
      for (const d of list ?? []) {
        if (d.name && d.fingerprint) map[d.name] = d.fingerprint
      }
      setInstalledFp(map)
    }).catch(() => { /* 指纹不可得 → 不显示更新提示（不影响安装） */ })

    if (source === 'local') {
      // 本地来源：专家（本地 agents）+ 本地技能（用户/项目/插件命令）。插件明细在公共段已加载。
      // commandApi.list(true) 让后端先清 SkillRegistry memoize —— 手动拷进 skills 目录的技能即时可见。
      void Promise.allSettled([
        agentApi.listAgents(sessionId),
        commandApi.list(true, sessionId),
      ]).then(([agR, cmdR]) => {
        if (cancelled) return
        if (agR.status === 'fulfilled') {
          setLocalAgents(agR.value)
          setPluginAgents(agR.value.filter((a) => a.source === 'plugin'))
        } else {
          setLocalAgents([]); setPluginAgents([])
        }
        setSkills(cmdR.status === 'fulfilled'
          ? cmdR.value.filter(isLocalSkill).map(toLocalSkill)
          : [])
        setExperts([]); setConnectors([])
        const failed = [agR, cmdR].filter((r): r is PromiseRejectedResult => r.status === 'rejected')
        setLoadError(failed.length
          ? `部分本地数据加载失败（已尽力展示可用项）：${failed.map((r) => formatFail(r.reason)).join('；')}`
          : null)
      }).finally(done)
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

    return () => { cancelled = true }
  }, [sessionId, source, reloadKey])

  // ---- 已安装计数（顶栏 · =preinstalled / 已装插件 / isConnected / 本地已装专家数）----
  const installedCount = tab === 'expert'
    ? localAgents.length + experts.filter((e) => e.preinstalled).length
    : tab === 'skill'
      ? skills.filter((s) => s.preinstalled || installed.has(s.marketId)).length
      : connectors.filter((c) => c.isConnected).length

  // ---- 技能分类胶囊（category + tags → 中文标签，按显示名去重；`skill` 是内部标记不展示）----
  const skillCategories = useMemo(() => {
    const labels: string[] = []
    for (const s of skills) {
      for (const c of s.categories ?? []) {
        if (!c || c === 'skill') continue
        const label = CATEGORY_LABELS[c] ?? c
        if (!labels.includes(label)) labels.push(label)
      }
    }
    return labels
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
  /**
   * 市场里有更新？名称相同、指纹不同即算 —— 版本号可能没变（重新上架覆盖内容），
   * 所以只认内容指纹（用户裁定 2026-10-21）。
   */
  const hasUpdate = (marketId: string, marketFp?: string | null) =>
    !!marketFp && !!installedFp[marketId] && marketFp !== installedFp[marketId]

  const installMarketPlugin = async (pluginName: string) => {
    if (busy) {
      showToast('对话进行中，请稍后再安装', 'info')
      return
    }
    // 同一插件已在安装中 → 直接忽略（按钮虽已禁用，键盘/连点仍可能进来）
    if (installing.has(pluginName)) return
    const pluginId = `${pluginName}@${marketName}`
    setInstalling((prev) => new Set(prev).add(pluginName))
    setProgress((prev) => ({ ...prev, [pluginName]: { pluginId, stage: 'PREPARING', percent: 2, message: '准备市场索引', updatedAt: Date.now() } }))
    // 安装是同步 POST，拿不到中间态 → 轮询后端进度台账（下载段是真实字节比率）
    const timer = window.setInterval(() => {
      void marketApi.getInstallProgress(pluginId)
        .then((p) => { if (p) setProgress((prev) => ({ ...prev, [pluginName]: p })) })
        .catch(() => { /* 轮询失败不打扰用户：主流程的结果 toast 才是权威 */ })
    }, 400)
    try {
      const r = await marketApi.installPlugin({
        pluginId,
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
    } finally {
      window.clearInterval(timer)
      // 进度圈保留片刻再收：小包（几十 KB）0.2s 就装完，立即复位会让用户完全看不到进度。
      // 期间按钮保持禁用，避免在 100% 未收尾时又点一次。
      window.setTimeout(() => {
        setInstalling((prev) => {
          const next = new Set(prev)
          next.delete(pluginName)
          return next
        })
        setProgress((prev) => {
          const next = { ...prev }
          delete next[pluginName]
          return next
        })
      }, 800)
    }
  }

  // ---- 召唤已安装技能：把「/技能真实注册名 」交给 App 写入输入框（Composer 侧渲染为可删 chip）----
  //   2026-10-19 用户需求：安装成功后可直接选择召唤，输入框以斜杠前缀展示且可悬停删除。
  //   注册名不猜规则（⛔ 不做 zjky- 前缀裁剪——有些技能名本身就以 zjky 开头、市场标识与注册名不同源）：
  //   已装技能必然出现在后端命令目录里（实测 detection-report-reviewer 案例），实时拉取后按
  //   「命令名 === 市场名 或 市场名以它结尾」匹配（多个候选取最长），匹配不到则中止并提示。
  const summonSkill = async (skill: MarketSkill) => {
    if (busy) {
      showToast('对话进行中，请等当前轮完成后再召唤技能', 'info')
      return
    }
    if (!onSummonSkill) {
      showToast('当前环境不支持召唤，请手动在输入框输入斜杠命令', 'info')
      return
    }
    let cmd: string | null = null
    try {
      const list = await commandApi.list(false, sessionId || undefined)
      const names = list.map((c) => c.name)
      const lm = skill.marketId.toLowerCase()
      // 插件技能的真实注册名 =「插件名:技能目录名」（LoadPluginCommands.loadSkillFile：
      // skillName = pluginName + ':' + basename）⇒ 优先按 <marketId>: 前缀匹配；
      // 其后兼容「命令名 === 市场名」与「市场名以命令名结尾」两种旧形态。
      cmd =
        names.find((n) => n.toLowerCase().startsWith(lm + ':')) ??
        names.find((n) => n.toLowerCase() === lm) ??
        names
          .filter((n) => lm.endsWith(n.toLowerCase()))
          .sort((a, b) => b.length - a.length)[0] ??
        null
    } catch {
      cmd = null
    }
    if (!cmd) {
      showToast('该技能还没有注册成斜杠命令（可能仍在加载），请稍后重试或检查插件安装', 'info')
      return
    }
    onSummonSkill(cmd, skill.displayName || undefined)
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

  // ---- 使用专家（本地走 PATCH · 建科市场走 installMarketPlugin）----
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
    }
  }

  // ---- 技能过滤（分类胶囊 + 搜索）----
  const filteredSkills = useMemo(() => {
    return skills.filter((s) => {
      if (cat !== '全部' && !(s.categories ?? []).some((c) => (CATEGORY_LABELS[c] ?? c) === cat)) return false
      return matchAny(s.displayName, s.name, s.description)
    })
  }, [skills, cat, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 本地已安装插件过滤（仅「本地」来源展示）----
  const filteredPlugins = useMemo(() => {
    return pluginDetails.filter((p) => matchAny(p.name, p.version))
  }, [pluginDetails, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

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
          {/* 市场源下拉（建科数字插件市场 / 本地） */}
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
          {/* [2026-10-21] 「＋ 添加」下拉：上传技能 / 上传专家 / 创建技能。
              对齐 WorkBuddy 的「添加技能」菜单；差异：我们额外支持上架专家（用户要求）。 */}
          <div className="sm-add">
            <button className="sm-add-btn" onClick={() => setAddOpen((v) => !v)} title="添加">
              ＋ 添加
            </button>
            {addOpen && (
              <div className="sm-add-menu">
                <div className="sm-add-item" onClick={() => { setAddOpen(false); setPublishCat('skill') }}>
                  上传技能
                </div>
                <div className="sm-add-item" onClick={() => { setAddOpen(false); setPublishCat('expert') }}>
                  上传专家
                </div>
                <div className="sm-add-item" onClick={() => {
                  setAddOpen(false)
                  // 创建技能交给内置 skill-creator（已有能力，不重复造轮子）
                  if (onSummonSkill) {
                    onSummonSkill('skill-creator', '创建技能')
                  } else {
                    showToast('请先在输入框输入 /skill-creator 创建技能', 'info')
                  }
                }}>
                  创建技能
                </div>
              </div>
            )}
          </div>
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
                              <button
                                className={`sm-use-btn${installing.has(c.marketId) ? ' installing' : ''}`}
                                disabled={installing.has(c.marketId)}
                                onClick={() => void installMarketPlugin(c.marketId)}
                                title={installing.has(c.marketId) ? `正在安装 ${c.name}…` : `安装 ${c.name}`}
                              >
                                {installing.has(c.marketId)
                                  ? <><RingProgress percent={progress[c.marketId]?.percent ?? 0} /><span>{progress[c.marketId]?.percent ?? 0}%</span></>
                                  : '安装'}
                              </button>
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
                  {source === 'local' && (
                    <div className="sm-section-head">
                      <span className="sm-section-title">本地技能（已安装）</span>
                      <span className="sm-result-count">{filteredSkills.length} 个</span>
                    </div>
                  )}
                  {/* 分类胶囊 + 计数：始终常驻（筛选后结果变少也不会失去退回“全部”的入口） */}
                  <div className="sm-cat-row">
                    {['全部', ...skillCategories].map((c) => (
                      <button key={c} className={`sm-cat${cat === c ? ' active' : ''}`} onClick={() => setCat(c)}>
                        {c}
                      </button>
                    ))}
                    <span className="sm-result-count">{filteredSkills.length} 个技能</span>
                  </div>

                  {filteredSkills.length === 0 ? (
                    <div className="sm-empty small">
                      {keyword ? '没有匹配的技能' : (source === 'local' ? '暂无本地技能' : '暂无可用技能')}
                    </div>
                  ) : cat !== '全部' || keyword || source === 'local' ? (
                    /* 筛选/搜索态：单一网格（不再分区，避免精选区只剩 3 个造成误解） */
                    <div className="sm-skill-grid">
                      {filteredSkills.map((s) => (
                        <SkillMiniCard
                          key={`flt-${s.marketId}`}
                          skill={s}
                          marked={isMarked('skill', s.marketId)}
                          onMark={() => toggleMark('skill', s.marketId)}
                          onInstall={source === 'jianke-market' ? (id) => void installMarketPlugin(id) : undefined}
                          installedNow={installed.has(s.marketId)}
                          onSummon={() => summonSkill(s)}
                          installing={installing.has(s.marketId)}
                          installPercent={progress[s.marketId]?.percent ?? 0}
                          hasUpdate={hasUpdate(s.marketId, s.fingerprint)}
                        />
                      ))}
                    </div>
                  ) : (
                    /* 默认态：精选前 3 + 推荐其余（两区不重复） */
                    <>
                      <div className="sm-section-head">
                        <span className="sm-section-title">精选技能</span>
                      </div>
                      <div className="sm-skill-grid">
                        {filteredSkills.slice(0, 3).map((s) => (
                          <SkillMiniCard
                            key={`feat-${s.marketId}`}
                            skill={s}
                            marked={isMarked('skill', s.marketId)}
                            onMark={() => toggleMark('skill', s.marketId)}
                            onInstall={source === 'jianke-market' ? (id) => void installMarketPlugin(id) : undefined}
                            installedNow={installed.has(s.marketId)}
                            onSummon={() => summonSkill(s)}
                            installing={installing.has(s.marketId)}
                            installPercent={progress[s.marketId]?.percent ?? 0}
                            hasUpdate={hasUpdate(s.marketId, s.fingerprint)}
                          />
                        ))}
                      </div>
                      {filteredSkills.length > 3 && (
                        <>
                          <div className="sm-section-head">
                            <span className="sm-section-title">推荐</span>
                          </div>
                          <div className="sm-skill-grid">
                            {filteredSkills.slice(3).map((s) => (
                              <SkillMiniCard
                                key={`rec-${s.marketId}`}
                                skill={s}
                                marked={isMarked('skill', s.marketId)}
                                onMark={() => toggleMark('skill', s.marketId)}
                                onInstall={source === 'jianke-market' ? (id) => void installMarketPlugin(id) : undefined}
                                installedNow={installed.has(s.marketId)}
                                onSummon={() => summonSkill(s)}
                                installing={installing.has(s.marketId)}
                                installPercent={progress[s.marketId]?.percent ?? 0}
                                hasUpdate={hasUpdate(s.marketId, s.fingerprint)}
                              />
                            ))}
                          </div>
                        </>
                      )}
                    </>
                  )}

                  {source === 'local' && filteredPlugins.length > 0 && (
                    <div className="sm-local-plugins">
                      <div className="sm-section-head">
                        <span className="sm-section-title">已安装插件</span>
                        <span className="sm-result-count">{filteredPlugins.length} 个</span>
                      </div>
                      <div className="sm-skill-grid">
                        {filteredPlugins.map((p) => (
                          <div key={p.name} className="sm-card sm-mini-card">
                            <div className="sm-mini-head">
                              <span className="sm-avatar sm-avatar-sm" style={{ background: colorOf(p.name) }}>
                                {p.name.charAt(0).toUpperCase()}
                              </span>
                              <span className="sm-mini-name" title={p.installPath ?? p.name}>{p.name}</span>
                              <span className="sm-installed-mark">已安装</span>
                            </div>
                            <div className="sm-desc two">
                              版本 {p.version || '未知'}{p.installPath ? ` · ${p.installPath}` : ''}
                            </div>
                          </div>
                        ))}
                      </div>
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
                                      className={`sm-plus-btn${installed.has(c.marketId) ? ' marked' : ''}${installing.has(c.marketId) ? ' installing' : ''}`}
                                      disabled={installed.has(c.marketId) || installing.has(c.marketId)}
                                      onClick={() => void installMarketPlugin(c.marketId)}
                                      title={installed.has(c.marketId) ? '已安装' : installing.has(c.marketId) ? '正在安装…' : '安装'}
                                    >
                                      {installing.has(c.marketId)
                                        ? <RingProgress percent={progress[c.marketId]?.percent ?? 0} />
                                        : installed.has(c.marketId) ? '✓' : '+'}
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

        {/* 上架弹窗（上传技能 / 上传专家）· 上架成功后重新拉市场列表 */}
        {publishCat && (
          <PublishDialog
            category={publishCat}
            onClose={() => setPublishCat(null)}
            onDone={() => { setPublishCat(null); setReloadKey((k) => k + 1) }}
            showToast={showToast}
          />
        )}
      </div>
    </div>
  )
}

/** 技能小卡（精选横条 + 推荐网格共用）· 3 列小卡：图标 + 名 + 描述 2 行截断 + 右上操作区。
 *  未安装：建科市场来源「+」调安装 / 骨架来源 UI 高亮（onMark）；
 *  已安装（preinstalled 或本次刚装成功 installedNow）：显示「已安装」+「召唤」——
 *  召唤 = 把 /技能命令名 写入输入框（2026-10-19 用户需求：安装成功可直接选择召唤）。 */
/**
 * 上架弹窗：把本地技能 / 专家目录发布到市场。
 *
 * <p>调用 `POST /api/market/manage/publish`；普通用户即可用（不需要市场管理员模式）。
 * 版本留空 → 后端自动 patch +1；内容指纹未变 → 返回 unchanged，不产生重复版本。
 */
function PublishDialog({ category, onClose, onDone, showToast }: {
  category: 'skill' | 'expert'
  onClose: () => void
  onDone: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [dir, setDir] = useState('')
  const [name, setName] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [description, setDescription] = useState('')
  const [tags, setTags] = useState('')
  const [version, setVersion] = useState('')
  const [busy, setBusy] = useState(false)
  const label = category === 'expert' ? '专家' : '技能'

  const pick = async () => {
    const sel = await selectProjectFolder(`选择要上架的${label}目录`)
    if (!sel) return
    setDir(sel.path)
    if (!name) {
      setName(sel.path.split(/[\\/]/).filter(Boolean).pop() ?? '')
    }
  }

  const submit = async () => {
    if (!dir.trim()) {
      showToast('请先选择要上架的目录', 'info')
      return
    }
    setBusy(true)
    try {
      const r = await marketApi.publishPlugin({
        sourcePath: dir.trim(),
        category,
        name: name.trim() || undefined,
        displayName: displayName.trim() || undefined,
        description: description.trim() || undefined,
        tags: tags.split(/[,，\s]+/).map((t) => t.trim()).filter(Boolean),
        version: version.trim() || undefined,
      })
      if (r.status === 'ok') {
        showToast(`已上架 ${r.name} v${r.version}`, 'success')
        onDone()
      } else if (r.status === 'unchanged') {
        showToast(`内容没有变化，未创建新版本（当前 v${r.version}）`, 'info')
      } else {
        showToast(r.message || '上架失败', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="sm-publish-mask" onClick={onClose}>
      <div className="sm-publish" onClick={(e) => e.stopPropagation()}>
        <div className="sm-publish-head">
          <span>上传{label}</span>
          <button className="sm-icon-btn" onClick={onClose} title="关闭">✕</button>
        </div>
        <div className="sm-publish-body">
          <label>本地目录</label>
          <div className="sm-publish-inline">
            <input className="settings-input" value={dir} onChange={(e) => setDir(e.target.value)}
              placeholder={category === 'expert' ? 'C:\\...\\agents\\my-expert' : 'C:\\...\\.nexusai\\skills\\my-skill'} />
            <button className="sm-publish-btn" onClick={() => void pick()}>选择…</button>
          </div>
          <div className="sm-publish-hint">
            {category === 'expert'
              ? '目录里要有 agents/ 或 plugin.json'
              : '目录里要有 SKILL.md（或 plugin.json / skills/）'}
          </div>

          <div className="sm-publish-row">
            <div>
              <label>市场名称</label>
              <input className="settings-input" value={name} onChange={(e) => setName(e.target.value)}
                placeholder="留空取目录名" />
            </div>
            <div>
              <label>版本</label>
              <input className="settings-input" value={version} onChange={(e) => setVersion(e.target.value)}
                placeholder="留空自动 +1" />
            </div>
          </div>
          <label>展示名称</label>
          <input className="settings-input" value={displayName} onChange={(e) => setDisplayName(e.target.value)}
            placeholder={`显示在${label}卡片上的名字`} />
          <label>描述</label>
          <input className="settings-input" value={description} onChange={(e) => setDescription(e.target.value)}
            placeholder="一句话说明它做什么" />
          <label>标签</label>
          <input className="settings-input" value={tags} onChange={(e) => setTags(e.target.value)}
            placeholder="逗号或空格分隔" />
        </div>
        <div className="sm-publish-foot">
          <button className="sm-publish-btn" onClick={onClose}>取消</button>
          <button className="sm-publish-btn primary" disabled={busy} onClick={() => void submit()}>
            {busy ? '上架中…' : '上架到市场'}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 环形进度圈（conic-gradient）· 用于安装按钮原位显示真实百分比。
 *
 * <p>口径：0~100；下载段来自后端按 Content-Length 统计的字节比率，解压/注册段是阶段值。
 */
function RingProgress({ percent }: { percent: number }) {
  const pct = Math.max(0, Math.min(100, Math.round(percent || 0)))
  const deg = pct * 3.6
  return (
    <span
      className="sm-ring"
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
      style={{ background: `conic-gradient(var(--accent, #0f5fd4) ${deg}deg, var(--hairline-strong, #E2E2E2) ${deg}deg)` }}
    >
      <span className="sm-ring-hole" />
    </span>
  )
}

function SkillMiniCard({ skill, marked, onMark, onInstall, installedNow = false, installing = false, installPercent = 0, hasUpdate = false, onSummon }: {
  skill: MarketSkill
  marked: boolean
  onMark: () => void
  onInstall?: (marketId: string) => void
  /** 本次会话内安装成功（installed Set 命中；与服务端 preinstalled 合并判定） */
  installedNow?: boolean
  /** 正在安装（下载 + 解包中）→ 按钮显示环形进度并禁用，避免连点触发并发安装 */
  installing?: boolean
  /** 安装进度百分比（0~100）· 只有下载段是真实字节比率 */
  installPercent?: number
  /** 市场内容指纹与本地已装指纹不同（名称/版本可能都一样）→ 显示「可更新」 */
  hasUpdate?: boolean
  /** 召唤到输入框（仅已安装卡提供） */
  onSummon?: () => void
}) {
  const nm = skill.displayName || skill.name || skill.marketId
  const installed = !!skill.preinstalled || installedNow
  return (
    <div className={`sm-card sm-mini-card${installed ? ' installed' : ''}`}>
      <div className="sm-mini-head">
        {skill.icon
          ? <img className="sm-avatar sm-avatar-img sm-avatar-sm" src={skill.icon} alt={nm} loading="lazy" />
          : <span className="sm-avatar sm-avatar-sm" style={{ background: colorOf(nm) }}>{(nm ?? '?').charAt(0).toUpperCase()}</span>}
        <span className="sm-mini-name" title={nm}>{nm}</span>
        {installed
          ? (
              <span className="sm-mini-actions">
                <span className={`sm-installed-mark${hasUpdate ? ' stale' : ''}`}>
                  {hasUpdate ? '可更新' : '已安装'}
                </span>
                {hasUpdate && onInstall && (
                  <button
                    className="sm-update-btn"
                    disabled={installing}
                    onClick={() => onInstall(skill.marketId)}
                    title="市场内容与本地不同，点击更新"
                  >
                    {installing ? <RingProgress percent={installPercent} /> : '更新'}
                  </button>
                )}
                {onSummon && (
                  <button
                    className="sm-summon-btn"
                    onClick={onSummon}
                    title={`召唤到输入框：/${skill.name || skill.marketId}`}
                  >
                    召唤
                  </button>
                )}
              </span>
            )
          : onInstall
            ? (
                <button
                  className={`sm-plus-btn${installing ? ' installing' : ''}`}
                  disabled={installing}
                  onClick={() => onInstall(skill.marketId)}
                  title={installing ? `正在安装 ${nm}… ${installPercent}%` : `安装 ${nm}`}
                >
                  {installing ? <RingProgress percent={installPercent} /> : '+'}
                </button>
              )
            : <button className={`sm-plus-btn${marked ? ' marked' : ''}`} onClick={onMark} title="安装（骨架：UI 占位）">{marked ? '✓' : '+'}</button>}
      </div>
      {skill.description && <div className="sm-desc two">{skill.description}</div>}
      {installing && (
        <div className="sm-install-line">
          <div
            className="sm-install-bar"
            role="progressbar"
            aria-valuenow={Math.round(installPercent || 0)}
            aria-valuemin={0}
            aria-valuemax={100}
            title={`正在安装 ${nm}… ${Math.round(installPercent || 0)}%`}
          >
            <span
              className="sm-install-bar-fill"
              style={{ width: `${Math.max(2, Math.min(100, installPercent || 0))}%` }}
            />
          </div>
          <span className="sm-install-pct">{Math.round(installPercent || 0)}%</span>
        </div>
      )}
    </div>
  )
}
