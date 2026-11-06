import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import { agentApi } from '@/api/agent'
import { commandApi, type CommandDto } from '@/api/command'
import { marketApi, type InstallProgress, type InstalledPluginDetail } from '@/api/market'
import { selectProjectFolder } from '@/utils/projectFolder'
import { formatCountW } from '@/utils/format'
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
/** 顶栏主按钮文案随 Tab 切换（v4：「添加XX」） */
const ADD_LABELS: Record<MarketTab, string> = {
  expert: '添加专家',
  skill: '添加技能',
  connector: '添加连接器',
}

/** 一级 tab 图标（Apple 工具栏风格：每个 tab 带一个小图标）。 */
const TAB_ICONS: Record<MarketTab, ReactNode> = {
  expert: <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>,
  skill: <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>,
  connector: (
    <svg viewBox="0 0 24 24">
      <path d="M9.5 14.5l5-5" />
      <path d="M13 6.5l1.4-1.4a3.6 3.6 0 015 5L18 11.5" />
      <path d="M11 17.5l-1.4 1.4a3.6 3.6 0 01-5-5L6 12.5" />
    </svg>
  ),
}

/** 市场来源 id · 决定市场弹窗的数据源（自建 MinIO / 本地） */
export type MarketSourceId = 'jianke-market' | 'local'

/** 建科数字插件市场（自建 MinIO）· 内置固定地址（用户拍板 2026-09-17）。
 *  指向 marketplace.json 索引（几 KB）——看列表只拉索引；装插件时后端按 source 只下载
 *  对应插件包 plugin.zip（按需，上千插件不整包下载）。公网入口 netHost 任意网络可达。 */
const JIANKE_MARKET_URL = 'http://192.168.20.125:9000/nexusai/marketplace/marketplace.json'

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
  /**
   * 技能 tab 的子来源（v4 用户拍板：拆子 tab、**默认停在「市场」**）。
   *
   * <p>与已删除的「市场源下拉」的区别：那个下拉在弹窗左上角、且没有任何数量提示，
   * 用户根本不知道「本地」里有 60 个技能；现在子 tab 常驻在内容区顶部并带数量徽标。
   */
  const [skillSrc, setSkillSrc] = useState<'local' | 'market'>('market')
  /**
   * 「已安装 (N)」筛选开关（用户 2026-11-01：**只有点了它**才显示本机技能/专家）。
   *
   * <p>默认 false ⇒ 市场优先（用户明确要求「市场默认展示 而不是本机默认展示」）。
   * 打开后：技能 tab 只看本机技能；专家 tab 只看本地专家。
   * 切换子 tab / 换 tab 时自动清掉（否则用户会以为市场里真的只有这么点东西）。
   */
  const [installedOnly, setInstalledOnly] = useState(false)
  /** 当前展开 ⋯ 菜单的技能（marketId）· null = 无展开 */
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)
  /** 详情页正在查看的技能 · null = 不显示详情 */
  const [detailSkill, setDetailSkill] = useState<MarketSkill | null>(null)
  /** 开发者信息弹窗（null = 不显示）· 按 author.name 聚合该开发者的全部条目 */
  const [devAuthor, setDevAuthor] = useState<{ name: string; verified?: boolean; email?: string | null; avatarUrl?: string | null } | null>(null)
  const [q, setQ] = useState('')
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
  /** 本机已装技能（用户级 / 项目级 / 插件级 · 对应技能 tab 的「本机已装」子 tab） */
  const [skills, setSkills] = useState<MarketSkill[]>([])
  /** 市场技能（对应技能 tab 的「市场」子 tab）· 与 skills 分开，两个子 tab 各自渲染 */
  const [marketSkills, setMarketSkills] = useState<MarketSkill[]>([])
  const [connectors, setConnectors] = useState<MarketConnector[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  // 建科市场插件（ExternalMarketPlugin）→ 现有 Market* 类型（按 category 分流到三 Tab）
  // [2026-11-01 v4] 补统计与开发者：后端已下发原始整数（useCount/downloadCount）与
  //   author（缺省「数字公司开发组」）。统计为 null 时保持 null —— 卡片据 null 整项不渲染。
  const toMarketExpert = (p: ExternalMarketPlugin): MarketExpert => ({
    marketId: p.name, agentName: p.name, displayName: p.displayName, description: p.description,
    tags: p.tags ?? [], categories: p.category ? [p.category] : [], remote: true,
    useCount: p.useCount ?? null,
    authorName: p.authorName ?? null,
    authorVerified: p.authorVerified ?? false,
    authorAvatarUrl: p.authorAvatarUrl ?? null,
    authorEmail: p.authorEmail ?? null,
  })
  const toMarketSkill = (p: ExternalMarketPlugin): MarketSkill => ({
    marketId: p.name, name: p.name, displayName: p.displayName, description: p.description,
    // 分类胶囊取「category + tags」（过滤掉内部标记 skill 后就是 docs/review/design/ppt…）
    categories: [p.category ?? '', ...(p.tags ?? [])].filter(Boolean), remote: true,
    fingerprint: p.fingerprint,
    version: p.version ?? null,
    useCount: p.useCount ?? null,
    downloadCount: p.downloadCount ?? null,
    authorName: p.authorName ?? null,
    authorVerified: p.authorVerified ?? false,
    authorAvatarUrl: p.authorAvatarUrl ?? null,
    authorEmail: p.authorEmail ?? null,
  })
  const toMarketConnector = (p: ExternalMarketPlugin): MarketConnector => ({
    marketId: p.name, name: p.displayName || p.name, authType: p.tags?.[0], remote: true,
  })

  // 本机已安装技能来源：用户级 / 项目级 / 插件级（排除内置/捆绑 —— 数量大且不是用户装的）。
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
    // 本机技能没有市场统计（⛔ 不编 0）；开发者缺省由后端口径统一为「数字公司开发组」
    useCount: null,
    downloadCount: null,
    authorName: null,
    authorVerified: false,
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

    // [2026-11-01 v4] **两个源并行加载**（原来是 if/else 二选一）。
    //   原因：技能 tab 内拆了「本机已装 / 市场」两个子 tab，两个列表必须同时在手
    //   （否则切子 tab 要重新拉一次，且「已安装 N」计数拿不到本机数据）。
    //   ⚠️ 单个源失败不影响另一个（allSettled），失败信息汇总进 banner。
    void Promise.allSettled([
      agentApi.listAgents(sessionId),
      // commandApi.list(true, …) 让后端先清 SkillRegistry memoize —— 手动拷进 skills 目录的技能即时可见
      commandApi.list(true, sessionId),
      marketApi.listExternal(JIANKE_MARKET_URL),
    ]).then(([agR, cmdR, mR]) => {
      if (cancelled) return
      const failMsgs: string[] = []

      // ① 本地 agents（专家 tab 的「本地」卡 + 插件专家定位）
      if (agR.status === 'fulfilled') {
        setLocalAgents(agR.value)
        setPluginAgents(agR.value.filter((a) => a.source === 'plugin'))
      } else {
        setLocalAgents([]); setPluginAgents([])
        failMsgs.push(`本地专家：${formatFail(agR.reason)}`)
      }

      // ② 本机技能（本机已装子 tab）
      if (cmdR.status === 'fulfilled') {
        setSkills(cmdR.value.filter(isLocalSkill).map(toLocalSkill))
      } else {
        setSkills([])
        failMsgs.push(`本机技能：${formatFail(cmdR.reason)}`)
      }

      // ③ 市场条目（市场子 tab；专家/技能/连接器三个 Tab 共用同一份）
      if (mR.status === 'fulfilled') {
        const m = mR.value
        if (m?.name) setMarketName(m.name)
        const pl = m?.plugins ?? []
        setExperts(pl.filter((p) => p.category === 'expert').map(toMarketExpert))
        setMarketSkills(pl.filter((p) => p.category === 'skill').map(toMarketSkill))
        setConnectors(pl.filter((p) => p.category === 'connector').map(toMarketConnector))
      } else {
        setExperts([]); setMarketSkills([]); setConnectors([])
        failMsgs.push(`建科市场：${formatFail(mR.reason)}`)
      }

      setLoadError(failMsgs.length ? `部分数据加载失败（已尽力展示可用项）：${failMsgs.join('；')}` : null)
    }).finally(done)

    return () => { cancelled = true }
  }, [sessionId, reloadKey])

  /**
   * 市场拉取失败且用户没主动切到本机 ⇒ 内容区改为「只有失败说明 + 一个出口」。
   *
   * <p>判据用 `loadError` 里是否含「建科市场」也可以，但那耦合了文案；
   * 更稳的是「市场列表为空 **且** 加载错误非空」—— 市场为空但无错误 = 真的没有技能。
   */
  //    ⚠️ 只用**声明在上面**的 state 判定（expertCards 是 useMemo、在其后才声明，
  //   在这里引用会 TDZ 报错）。改用原始数据源：市场三个列表全空 + 有错误。
  const isMarketFailed = skillSrc === 'market' && !installedOnly
    && marketSkills.length === 0 && experts.length === 0 && connectors.length === 0
    && !!loadError

  // ---- 已安装计数（顶栏）----
  // [2026-11-01 修] 技能 tab 的口径原本是 `preinstalled || installed.has(...)` 混合两项 ——
  //   但 `skills` 本身就是**本机已装**列表（全部 preinstalled），加上 market 侧的 installed
  //   会把「市场里装了但本机命令列表没扫到」的条目也算进来，数字与实际可见条目对不上。
  //   现在严格 = 当前来源里「已安装」的条目数，与点开「已安装」后看到的一一对应。
  const installedCount = tab === 'expert'
    ? localAgents.filter((a) => a.source !== 'plugin').length + experts.filter((e) => e.preinstalled).length
    : tab === 'skill'
      ? skills.length
      : connectors.filter((c) => c.isConnected).length

  // ---- 技能分类胶囊（category + tags → 中文标签，按显示名去重；`skill` 是内部标记不展示）----
  const skillCategories = useMemo(() => {
    const labels: string[] = []
    // 分类胶囊只取当前子 tab 的条目（两边分类集合不同，混在一起会给出无结果的胶囊）
    const pool = skillSrc === 'local' ? skills : marketSkills
    for (const s of pool) {
      for (const c of s.categories ?? []) {
        if (!c || c === 'skill') continue
        const label = CATEGORY_LABELS[c] ?? c
        if (!labels.includes(label)) labels.push(label)
      }
    }
    return labels
  }, [skills, marketSkills, skillSrc])
  const [cat, setCat] = useState<string>('全部')

  // ---- 搜索匹配（名字/展示名/描述 contains · 大小写不敏感）----
  const keyword = q.trim().toLowerCase()
  const matchAny = (...parts: Array<string | null | undefined>) =>
    keyword.length === 0 || parts.some((p) => (p ?? '').toLowerCase().includes(keyword))

  // ---- 专家卡（本地 + 远端合并，混排双列大卡）----
  const expertCards = useMemo<ExpertCard[]>(() => {
    const cards: ExpertCard[] = []
    // [2026-11-01 修] 本机专家**只在「已安装」筛选打开时**才并入（用户要求：
    //   「专家还是失败后默认展示本机的 这是不对的」）。此前无条件并入 ⇒ 市场 502 时
    //   专家 tab 静静地列出一堆本机 agent，看起来像市场拉到了东西。
    for (const a of installedOnly ? localAgents : []) {
      // 插件专家（source='plugin'）归「远程」展示，本机卡恒来自 localAgents
      if (a.source === 'plugin') continue
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
  }, [localAgents, experts, currentAgent, keyword, installedOnly]) // eslint-disable-line react-hooks/exhaustive-deps

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
    // ⭐ 召唤 ≠ 对话：只把斜杠命令写进输入框（不发送、不切 agent），对话进行中也可以操作。
    //   ⛔ 不要用 busy 互斥（用户 2026-10-27 明确：召唤与对话是两码事）。
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

  /**
   * 卸载（v4 · ⋯ → 卸载）。
   *
   * <p><b>⚠️ 不可恢复</b>：后端真实删除安装目录。必须二次确认，且确认框要说清
   * 「卸插件会连带它带来的全部技能/专家」—— 用户点之前得知道后果。
   */
  const handleUninstall = async (skill: MarketSkill, fromLocal: boolean) => {
    const nm = skill.displayName || skill.name || skill.marketId
    const what = fromLocal ? `技能「${nm}」` : `插件「${nm}」`
    const extra = fromLocal
      ? '\n\n（本机技能目录会被删除，无法恢复）'
      : '\n\n（该插件带来的所有技能与专家也会一并移除，无法恢复）'
    if (!window.confirm(`确定卸载${what}？${extra}`)) return
    try {
      const r = await marketApi.uninstallPlugin(skill.marketId)
      if (r.success) {
        showToast(r.message || `已卸载 ${nm}`, 'success')
        setInstalled((prev) => { const n = new Set(prev); n.delete(skill.marketId); return n })
        setReloadKey((k) => k + 1)
      } else {
        showToast(r.message || '卸载失败', 'info')
      }
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }

  /** 打开某技能所属开发者的信息弹窗（按 author.name 聚合）。 */
  const openDeveloper = (s: MarketSkill) => {
    if (!s.authorName) return
    setDevAuthor({
      name: s.authorName,
      verified: !!s.authorVerified,
      email: s.authorEmail ?? null,
      avatarUrl: s.authorAvatarUrl ?? null,
    })
  }

  // ---- 使用专家（本地走 PATCH · 建科市场走 installMarketPlugin）----
  const handleUse = (card: ExpertCard) => {
    // ⭐ 本地专家**不受 busy 互斥**（用户 2026-11-06：「应该和技能一样 没点发送都是可以点击的」）。
    //   理由：使用专家只 PATCH sessions.main_thread_agent（纯写会话字段，不发消息、不开轮），
    //   而后端在**每轮开头**读一次该列（LlmAgentLoop.collectRunMaterial 的 SP-03 段）⇒
    //   切换对**正在跑的那一轮无影响**（它已固化定义），从下一轮起生效。
    //   ⛔ 不要在这里加 busy 门 —— 与「召唤技能」同一条裁定：设定 ≠ 发送。
    //   ⚠️ 市场专家的「使用」实际是**安装插件**（写磁盘 + reconcile），那是安装语义，
    //   仍由 installMarketPlugin 自己的 busy 门拦截（见其函数首行），不在本处拦。
    if (card.inUse) return
    // v4：按卡片种类分流（两个源同时在场，不能再用「当前来源」判断）
    if (card.kind === 'remote') {
      void installMarketPlugin(card.marketId)
      return
    }
    if (card.kind === 'local') {
      const a = localAgents.find((x) => x.agentType === card.name)
      if (a) onUseLocalAgent?.(a.agentType)
    }
  }

  // ---- 技能过滤（分类胶囊 + 搜索）----
  // [2026-11-01 v4] 按子 tab 分源：本机已装 vs 市场；再叠加「只看已安装」筛选。
  // [2026-11-01] 「已安装」开关打开 ⇒ 只看本机（等价于切到 local 子 tab 的内容，但不改子 tab 选中态，
  //   否则用户切回来会莫名其妙停在 local）。这满足「只有点击已安装才显示本机」。
  const srcSkills = installedOnly
    ? skills
    : (skillSrc === 'local' ? skills : marketSkills)
  const filteredSkills = useMemo(() => {
    return srcSkills.filter((s) => {
      if (cat !== '全部' && !(s.categories ?? []).some((c) => (CATEGORY_LABELS[c] ?? c) === cat)) return false
      return matchAny(s.displayName, s.name, s.description)
    })
  }, [srcSkills, cat, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 本地已安装插件过滤（仅「本地」来源展示）----
  const filteredPlugins = useMemo(() => {
    return pluginDetails.filter((p) => matchAny(p.name, p.version))
  }, [pluginDetails, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 连接器过滤（搜索）----
  const filteredConnectors = useMemo(() => {
    return connectors.filter((c) => matchAny(c.name, c.scope, c.status, c.authType))
  }, [connectors, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 「+」安装 UI 高亮（骨架：仅本地 Set，不做真实安装）----

  // ---- 头像（远端 icon URL → img；缺失/本地 → 色块）----
  const renderAvatar = (c: { iconUrl: string | null; iconColor: string; iconText: string; name: string }) => (
    c.iconUrl
      ? <img className="sm-avatar sm-avatar-img" src={c.iconUrl} alt={c.name} loading="lazy" />
      : <span className="sm-avatar" style={{ background: c.iconColor }}>{c.iconText}</span>
  )

  return (
    <div className="sm-backdrop" onClick={onClose}>
      <div className="sm-panel" onClick={(e) => e.stopPropagation()}>
        {/* ===== 顶栏（v4）：关闭 + 一级 tab + 右侧 搜索 / 已安装(N) / 添加XX =====
            [2026-11-01] 原「市场源下拉」已删 —— 它与技能 tab 内的「本机/市场」子 tab
            语义完全重叠（两个控件说同一件事）。同理「已安装 N」计数原有两处，
            现在只保留这一个，并且是**可点的筛选**。
            顺序按用户指定：搜索 → 已安装(N) → 添加XX（已安装在添加左侧）。 */}
        <div className="sm-topbar">
          <button className="sm-icon-btn" onClick={onClose} title="关闭（Esc）" aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" style={{ width: 13, height: 13 }}>
              <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" />
            </svg>
          </button>
          <div className="sm-tabs-flat">
            {(Object.keys(TAB_LABELS) as MarketTab[]).map((t) => (
              <button
                key={t}
                className={`sm-tab-flat${tab === t ? ' active' : ''}`}
                onClick={() => { setTab(t); setInstalledOnly(false) }}
              >
                {TAB_ICONS[t]}
                {TAB_LABELS[t]}
              </button>
            ))}
          </div>
          <div className="sm-topbar-right">
            <div className="sm-search-pill">
              <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4 4" /></svg>
              <input
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder={SEARCH_PLACEHOLDERS[tab]}
              />
            </div>
            <button
              className={`sm-installed-pill${installedOnly ? ' on' : ''}`}
              onClick={() => setInstalledOnly((v) => !v)}
              title={
                installedOnly
                  ? `正在只看本机已安装（${installedCount} 项）· 点一下回到全部`
                  : `只看本机已安装（${installedCount} 项）`
              }
            >
              <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M8 12l3 3 5-6" /></svg>
              已安装 ({installedCount})
            </button>
            <div className="sm-add">
              <button className="sm-add-primary" onClick={() => setAddOpen((v) => !v)} title="添加">
                <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                {ADD_LABELS[tab]}
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
        </div>

        {/* ===== 内容区 ===== */}
        <div className="sm-body">
          {loading ? (
            <div className="sm-loading">市场加载中…</div>
          ) : (
            <>
              {loadError && <div className="sm-banner">{loadError}</div>}

              {tab === 'expert' && (
                /* [2026-11-01 修] 与技能 tab 保持**完全一致**的失败态（用户要求「专家 tab 也和技能一致」）：
                   市场拉取失败时不再只给一句空文案，而是同一套「失败说明 + 查看本机已装」出口。 */
                isMarketFailed ? (
                  <div className="sm-market-failed">
                    <div className="sm-market-failed-txt">市场数据拉取失败，暂无可展示的专家。</div>
                    <button
                      className="sm-market-failed-btn"
                      onClick={() => { setInstalledOnly(true) }}
                    >
                      查看本机已装 ({localAgents.filter((a) => a.source !== 'plugin').length})
                    </button>
                  </div>
                ) : expertCards.length === 0 ? (
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
                          {/* v4：两个源同时在场 ⇒ 按卡片种类分流，不看「当前来源」 */}
                          {c.kind === 'remote' ? (
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
                              className={`sm-use-btn${c.inUse ? ' used' : ''}`}
                              disabled={c.inUse}
                              onClick={() => handleUse(c)}
                              title={c.inUse ? '当前会话正在使用' : `使用 ${c.name} 驱动会话`}
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
                /* [2026-11-01 修] 市场拉取失败且当前停在市场 ⇒ **整块筛选 UI 都不渲染**
                   （用户：「拉取失败后还会展示 本机已装/市场0/全部 也不需要吧？」）。
                   理由：此时「市场 0」「全部」这些计数与胶囊全都是噪音 —— 失败信息已在
                   banner 里，再给一排全是 0 的筛选器只会让人以为「东西丢了」。
                   保留的只有：一句失败说明 + 「查看本机已装」的出口（否则用户困在空页）。 */
                isMarketFailed ? (
                  <div className="sm-market-failed">
                    <div className="sm-market-failed-txt">市场数据拉取失败，暂无可展示的技能。</div>
                    <button
                      className="sm-market-failed-btn"
                      onClick={() => { setSkillSrc('local'); setCat('全部'); setInstalledOnly(false) }}
                    >
                      查看本机已装 ({skills.length})
                    </button>
                  </div>
                ) : (
                <div className="sm-skill-wrap">
                  {/* ===== 子 tab：本机已装 / 市场（v4 用户拍板：默认「市场」）===== */}
                  <div className="sm-subtabs">
                    <button
                      className={`sm-subtab${(installedOnly || skillSrc === 'local') ? ' active' : ''}`}
                      onClick={() => { setSkillSrc('local'); setCat('全部'); setInstalledOnly(false) }}
                    >
                      本机已装 <span className="cnt">{skills.length}</span>
                    </button>
                    <button
                      className={`sm-subtab${skillSrc === 'market' ? ' active' : ''}`}
                      onClick={() => { setSkillSrc('market'); setCat('全部'); setInstalledOnly(false) }}
                    >
                      市场 <span className="cnt">{marketSkills.length}</span>
                    </button>
                  </div>

                  {/* 分类胶囊 + 计数：始终常驻（筛选后结果变少也不会失去退回「全部」的入口） */}
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
                      {keyword
                        ? '没有匹配的技能'
                        : skillSrc === 'local'
                          ? '暂无本机技能'
                          : loadError ? '市场数据拉取失败（见上方提示）' : '暂无可用技能'}
                    </div>
                  ) : (
                    <div className="sk-grid">
                      {filteredSkills.map((s) => (
                        <SkillCard
                          key={`${skillSrc}-${s.marketId}`}
                          skill={s}
                          fromLocal={skillSrc === 'local'}
                          installedNow={installed.has(s.marketId)}
                          installing={installing.has(s.marketId)}
                          installPercent={progress[s.marketId]?.percent ?? 0}
                          hasUpdate={hasUpdate(s.marketId, s.fingerprint)}
                          onSummon={() => summonSkill(s)}
                          onInstall={(id) => void installMarketPlugin(id)}
                          onOpenDetail={() => setDetailSkill(s)}
                          onUninstall={(sk, local) => void handleUninstall(sk, local)}
                          menuOpen={openMenuId === s.marketId}
                          onToggleMenu={() => setOpenMenuId(openMenuId === s.marketId ? null : s.marketId)}
                          onCloseMenu={() => setOpenMenuId(null)}
                        />
                      ))}
                    </div>
                  )}

                  {/* 本机子 tab 额外显示「已安装插件」（插件是技能的上游，用户会想看装了哪些） */}
                  {skillSrc === 'local' && filteredPlugins.length > 0 && (
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
                )
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
                            {/* v4：connectors 只由市场源填充（本机无连接器概念）⇒ 恒走安装分支 */}
                            {c.isConnected
                              ? <span className="sm-installed-mark">已连接</span>
                              : (
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

        {/* 技能详情页（v4）· 点卡片打开 */}
        {detailSkill && (
          <SkillDetailDialog
            skill={detailSkill}
            fromLocal={skillSrc === 'local'}
            installed={skillSrc === 'local' || !!detailSkill.preinstalled || installed.has(detailSkill.marketId)}
            onClose={() => setDetailSkill(null)}
            onSummon={() => void summonSkill(detailSkill)}
            onOpenDeveloper={() => openDeveloper(detailSkill)}
          />
        )}

        {/* 开发者信息弹窗（v4）· 按 author.name 聚合该开发者的全部条目 */}
        {devAuthor && (
          <DeveloperDialog
            authorName={devAuthor.name}
            verified={devAuthor.verified}
            email={devAuthor.email}
            avatarUrl={devAuthor.avatarUrl}
            skills={[...marketSkills, ...skills].filter((x) => x.authorName === devAuthor.name)}
            experts={experts.filter((e) => e.authorName === devAuthor.name)}
            onClose={() => setDevAuthor(null)}
            onPickSkill={(x) => { setDevAuthor(null); setDetailSkill(x) }}
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


/**
 * 技能卡（v4 · Apple 风格）。
 *
 * <p><b>相对旧 SkillMiniCard 的改动</b>（用户 2026-11-01 反馈「现有的卡片还行，就是不太符合 mac」）：
 * <ul>
 *   <li>图标 24px → 44px 渐变底（macOS 应用图标体量）；</li>
 *   <li>去边框，改发丝阴影；悬停只上浮 1px（原来是 2px + 蓝色投影，太「营销页」）；</li>
 *   <li>描述固定 2 行高度 ⇒ 网格不参差；</li>
 *   <li>新增<b>开发者行</b>与<b>底部统计行</b>（使用量/下载量，单位 w）；</li>
 *   <li>操作区（⋯ + 召唤/安装）移到<b>卡片右上角、与标题同一排</b>，且<b>默认隐藏、hover 才浮现</b>；</li>
 *   <li>⋯ 菜单补上「关闭 / 卸载」—— 这两个动作此前界面上完全没有入口。</li>
 * </ul>
 *
 * <p><b>⚠️ hover 才显示按钮的三条兜底</b>（缺一不可，见 CSS）：<code>:focus-within</code>（键盘）、
 * <code>@media (hover:none)</code>（触屏）、<code>pointer-events</code>（防误点隐形按钮）。
 */
function SkillCard({
  skill, fromLocal = false, installedNow = false, installing = false, installPercent = 0,
  hasUpdate = false, onSummon, onInstall, onOpenDetail, onUninstall, menuOpen, onToggleMenu, onCloseMenu,
}: {
  skill: MarketSkill
  /** 来自「本机已装」子 tab（无市场统计、恒为已安装） */
  fromLocal?: boolean
  installedNow?: boolean
  installing?: boolean
  installPercent?: number
  hasUpdate?: boolean
  onSummon: () => void
  onInstall: (marketId: string) => void
  onOpenDetail: () => void
  /** 卸载（已二次确认后才会被调用）· fromLocal 决定文案是「技能」还是「插件」 */
  onUninstall: (skill: MarketSkill, fromLocal: boolean) => void
  menuOpen: boolean
  onToggleMenu: () => void
  onCloseMenu: () => void
}) {
  const nm = skill.displayName || skill.name || skill.marketId
  const installed = fromLocal || !!skill.preinstalled || installedNow
  const useLabel = formatCountW(skill.useCount)
  const dlLabel = formatCountW(skill.downloadCount)
  const author = skill.authorName

  return (
    <div
      className="sk-card"
      onClick={onOpenDetail}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter') onOpenDetail() }}
    >
      <div className="sk-card-head">
        <span
          className="sk-ico"
          style={{ background: `linear-gradient(145deg, ${colorOf(nm)}, ${colorOf(nm + 'x')})` }}
        >
          {nm.charAt(0).toUpperCase()}
        </span>
        <div className="sk-titles">
          <div className="sk-name" title={nm}>{nm}</div>
          <div className="sk-desc">{skill.description || ''}</div>
          {/* 开发者行：后端已兜底默认值，但本机技能可能没有（fromLocal）⇒ 没值就不渲染整行 */}
          {author && (
            <div className="sk-author" onClick={(e) => { e.stopPropagation(); onOpenDetail() }}>
              <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>
              <b>{author}</b>
              {skill.authorVerified && (
                <span className="ver" title="已认证发布者">
                  <svg viewBox="0 0 24 24"><path d="M12 2l2.4 1.8 3-.2 1 2.8 2.6 1.5-1 2.9 1 2.9-2.6 1.5-1 2.8-3-.2L12 22l-2.4-1.8-3 .2-1-2.8L3 16.1l1-2.9-1-2.9 2.6-1.5 1-2.8 3 .2L12 2z" fill="currentColor" /><path d="M8.5 12l2.5 2.5 4.5-4.5" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </span>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 操作区：右上角 · 默认隐藏（CSS）· 与标题同一排 */}
      <div className="sk-acts" onClick={(e) => e.stopPropagation()}>
        <button className="sk-more" title="更多" onClick={onToggleMenu} aria-expanded={menuOpen}>
          <svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
        </button>
        {installed ? (
          <button className="sk-summon" onClick={onSummon} title={`召唤到输入框：/${skill.name || skill.marketId}`}>召唤</button>
        ) : (
          <button
            className={`sk-summon accent${installing ? ' installing' : ''}`}
            disabled={installing}
            onClick={() => onInstall(skill.marketId)}
            title={installing ? `正在安装 ${nm}… ${Math.round(installPercent)}%` : `安装 ${nm}`}
          >
            {installing ? `${Math.round(installPercent)}%` : '＋ 安装'}
          </button>
        )}
      </div>

      {/* ⋯ 菜单 */}
      {menuOpen && (
        <div className="sk-menu" onClick={(e) => e.stopPropagation()}>
          {installed ? (
            <>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onSummon() }}>
                <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>
                召唤到输入框
              </div>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onOpenDetail() }}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /></svg>
                查看详情
              </div>
              <div className="sk-menu-sep" />
              <div className="sk-menu-item danger" onClick={() => { onCloseMenu(); onUninstall(skill, fromLocal) }}>
                <svg viewBox="0 0 24 24"><path d="M4 7h16" /><path d="M9 7V5h6v2" /><path d="M6 7l1 13h10l1-13" /></svg>
                卸载
              </div>
            </>
          ) : (
            <>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onOpenDetail() }}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /></svg>
                查看详情
              </div>
              <div className="sk-menu-sep" />
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onInstall(skill.marketId) }}>
                <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                安装
              </div>
            </>
          )}
        </div>
      )}

      {/* 底部统计：有数据才渲染（⛔ 不显示 0 / 不显示空占位） */}
      <div className="sk-stats">
        {useLabel !== null && (
          <span className="sk-stat">
            <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>
            {useLabel}
          </span>
        )}
        {dlLabel !== null && (
          <span className="sk-stat">
            <svg viewBox="0 0 24 24"><path d="M12 4v11" /><path d="M8 11l4 4 4-4" /><path d="M5 20h14" /></svg>
            {dlLabel}
          </span>
        )}
        {hasUpdate && installed ? (
          <span className="sk-status" style={{ color: 'var(--warning)' }}>可更新</span>
        ) : installed ? (
          <span className="sk-status">{fromLocal ? '已安装 · 本机' : '已安装'}</span>
        ) : (
          <span className="sk-status notinst">未安装</span>
        )}
      </div>
    </div>
  )
}

/**
 * 技能详情页（v4）· 点卡片任意位置打开。
 *
 * <p>结构对齐参考图 3：大图标 + 标题 + 开发者 + 「去试试」/⋯ + 开关 + 关闭；
 * 四格数据条（版本 / 使用量 / 下载量 / 开发者）；下半部「概述」。
 *
 * <p><b>⚠️ 统计缺失时不显示该格</b>：本机技能没有市场统计 —— 四格会退化成两格，
 * 用 <code>grid-auto-flow</code> 自然排列，⛔ 不显示「0」。
 */
function SkillDetailDialog({ skill, fromLocal, installed, onClose, onSummon, onOpenDeveloper }: {
  skill: MarketSkill
  fromLocal: boolean
  installed: boolean
  onClose: () => void
  onSummon: () => void
  onOpenDeveloper: () => void
}) {
  const nm = skill.displayName || skill.name || skill.marketId
  const useLabel = formatCountW(skill.useCount)
  const dlLabel = formatCountW(skill.downloadCount)
  const author = skill.authorName
  // 统计格数就地计算：没有的格子根本不渲染（用 auto-fit 核心由 CSS 处理）
  const statCount = 1 + (useLabel ? 1 : 0) + (dlLabel ? 1 : 0) + (author ? 1 : 0)

  return (
    <div className="detail-mask" onClick={onClose}>
      <div className="detail-card" onClick={(e) => e.stopPropagation()}>
        <div className="dt-head">
          <span className="dt-ico" style={{ background: `linear-gradient(145deg, ${colorOf(nm)}, ${colorOf(nm + 'x')})` }}>
            {nm.charAt(0).toUpperCase()}
          </span>
          <div className="dt-title-wrap">
            <div className="dt-name">{nm}</div>
            {author && (
              <div className="dt-author" onClick={onOpenDeveloper} title="查看开发者信息">
                <span className="av">{author.charAt(0).toUpperCase()}</span>
                <b>{author}</b>
                {skill.authorVerified && (
                  <span className="ver">
                    <svg viewBox="0 0 24 24"><path d="M12 2l2.4 1.8 3-.2 1 2.8 2.6 1.5-1 2.9 1 2.9-2.6 1.5-1 2.8-3-.2L12 22l-2.4-1.8-3 .2-1-2.8L3 16.1l1-2.9-1-2.9 2.6-1.5 1-2.8 3 .2L12 2z" /></svg>
                  </span>
                )}
              </div>
            )}
            <div className="dt-actions">
              {installed && (
                <button className="dt-try" onClick={() => { onSummon(); onClose() }}>去试试</button>
              )}
            </div>
          </div>
          <div className="dt-toggle">
            {installed && (
              <label className="sk-switch" title="启用 / 停用">
                <input type="checkbox" defaultChecked /><i />
              </label>
            )}
            <button className="dt-close" onClick={onClose} title="关闭">✕</button>
          </div>
        </div>

        {skill.description && <div className="dt-desc">{skill.description}</div>}

        <div className="dt-stats" style={{ gridTemplateColumns: `repeat(${Math.max(2, statCount)}, 1fr)` }}>
          <div className="dt-stat">
            <div className="dt-stat-v">v{skill.version ?? '—'}</div>
            <div className="dt-stat-l">版本</div>
          </div>
          {useLabel && (
            <div className="dt-stat">
              <div className="dt-stat-v">
                <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>{useLabel}
              </div>
              <div className="dt-stat-l">使用量</div>
            </div>
          )}
          {dlLabel && (
            <div className="dt-stat">
              <div className="dt-stat-v">
                <svg viewBox="0 0 24 24"><path d="M12 4v11" /><path d="M8 11l4 4 4-4" /><path d="M5 20h14" /></svg>{dlLabel}
              </div>
              <div className="dt-stat-l">下载量</div>
            </div>
          )}
          {author && (
            <div className="dt-stat dev" onClick={onOpenDeveloper} title="查看开发者信息">
              <div className="dt-stat-v trunc" style={{ color: 'var(--ink-muted)' }}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>
                {author}
              </div>
              <div className="dt-stat-l">开发者</div>
            </div>
          )}
        </div>

        <div className="dt-section-h">
          <svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3" /><path d="M8 9h8M8 13h8M8 17h5" /></svg>
          概述
        </div>
        <div className="dt-overview">
          <div className="dt-meta">
            name: {nm}<br />
            slug: {skill.marketId}<br />
            version: {skill.version ?? '—'}<br />
            {author && <>author: {author}{skill.authorEmail ? ` <${skill.authorEmail}>` : ''}<br /></>}
            source: {fromLocal ? '本机 · ~/.nexusai/skills' : '建科数字插件市场'}
          </div>
          {skill.description && (
            <>
              <div className="dt-sep" />
              <div className="dt-body">{skill.description}</div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * 开发者信息弹窗（v4 · 参考图「关于开发者」）。
 *
 * <p>从详情页的「开发者」格 / 卡片开发者行点开。结构：
 * 大头像 + 名字 + 橙色认证 · 灰底「关于开发者」（简介 + 邮箱）· 专家/技能 tab · 两列卡网格。
 *
 * <p><b>按 author.name 聚合</b>（用户 2026-11-01 确认可做到）：弹窗里列出「这个开发者发布的
 * 全部技能/专家」。若某 tab 数量为 0 → 整个 tab 隐藏（不显示「技能 (0)」）。
 */
function DeveloperDialog({ authorName, verified, email, avatarUrl, skills, experts, onClose, onPickSkill }: {
  authorName: string
  verified?: boolean
  email?: string | null
  avatarUrl?: string | null
  /** 该开发者发布的技能（已按 authorName 过滤） */
  skills: MarketSkill[]
  /** 该开发者发布的专家 */
  experts: MarketExpert[]
  onClose: () => void
  onPickSkill: (s: MarketSkill) => void
}) {
  // 默认停在有内容的那个 tab（技能优先，因为从技能详情进来的场景最多）
  const [dtTab, setDtTab] = useState<'expert' | 'skill'>(skills.length > 0 ? 'skill' : 'expert')

  return (
    <div className="detail-mask" onClick={onClose}>
      <div className="dev-modal" onClick={(e) => e.stopPropagation()}>
        <button className="dev-close" onClick={onClose} title="关闭">✕</button>

        <div className="dev-head">
          {avatarUrl
            ? <span className="dev-avatar"><img src={avatarUrl} alt={authorName} /></span>
            : <span className="dev-avatar" style={{ background: `linear-gradient(145deg, ${colorOf(authorName)}, ${colorOf(authorName + 'x')})` }}>
                {authorName.charAt(0).toUpperCase()}
              </span>}
          <div className="dev-name">
            {authorName}
            {verified && (
              <span className="dev-verify" title="已认证发布者">
                <svg viewBox="0 0 24 24"><circle cx="10" cy="8" r="3.4" /><path d="M4 19.5c0-3.2 2.7-5 6-5s6 1.8 6 5" /><path d="M17.5 9.5l1.6 1.6 3-3" /></svg>
              </span>
            )}
          </div>
        </div>

        <div className="dev-about">
          <div className="dev-about-h">关于开发者</div>
          <div className="dev-about-txt">
            {authorName} 发布的技能与专家。共 {skills.length + experts.length} 项。
          </div>
          {email && (
            <div className="dev-about-mail" title="点击复制邮箱"
              onClick={() => { void navigator.clipboard?.writeText(email) }}>
              <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M4 7l8 6 8-6" /></svg>
              {email}
            </div>
          )}
        </div>

        {/* tab：数量为 0 的整项隐藏 */}
        {skills.length > 0 && experts.length > 0 && (
          <div className="dev-tabs">
            <button className={`dev-tab${dtTab === 'expert' ? ' active' : ''}`} onClick={() => setDtTab('expert')}>
              专家 <span className="n">({experts.length})</span>
            </button>
            <button className={`dev-tab${dtTab === 'skill' ? ' active' : ''}`} onClick={() => setDtTab('skill')}>
              技能 <span className="n">({skills.length})</span>
            </button>
          </div>
        )}

        <div className="dev-grid">
          {dtTab === 'skill'
            ? skills.map((s) => {
                const nm = s.displayName || s.name || s.marketId
                return (
                  <div key={s.marketId} className="dev-card" onClick={() => onPickSkill(s)}>
                    <div className="dev-card-head">
                      <span className="dev-card-ico" style={{ background: `linear-gradient(145deg, ${colorOf(nm)}, ${colorOf(nm + 'x')})` }}>
                        {nm.charAt(0).toUpperCase()}
                      </span>
                      <div className="dev-card-titles">
                        <div className="dev-card-name" title={nm}>{nm}</div>
                        <div className="dev-card-desc">{s.description || ''}</div>
                      </div>
                    </div>
                    {(s.categories ?? []).length > 0 && (
                      <div className="dev-card-tags">
                        {(s.categories ?? []).filter((c) => c && c !== 'skill').slice(0, 3)
                          .map((c) => <span key={c} className="dev-card-tag">{CATEGORY_LABELS[c] ?? c}</span>)}
                      </div>
                    )}
                  </div>
                )
              })
            : experts.map((e) => {
                const nm = e.displayName || e.agentName || e.marketId
                return (
                  <div key={e.marketId} className="dev-card">
                    <div className="dev-card-head">
                      <span className="dev-card-ico" style={{ background: `linear-gradient(145deg, ${colorOf(nm)}, ${colorOf(nm + 'x')})` }}>
                        {nm.charAt(0).toUpperCase()}
                      </span>
                      <div className="dev-card-titles">
                        <div className="dev-card-name" title={nm}>{nm}</div>
                        <div className="dev-card-desc">{e.description || ''}</div>
                      </div>
                    </div>
                  </div>
                )
              })}
        </div>
      </div>
    </div>
  )
}
