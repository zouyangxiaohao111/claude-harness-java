import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import { agentApi } from '@/api/agent'
import { commandApi, type CommandDto } from '@/api/command'
import { marketApi, type InstallProgress, type InstalledPluginDetail } from '@/api/market'
import { connectorsApi, type ConnectorDto, type ConnectorStatusResponse } from '@/api/connectors'
import { expertsApi, type BundledExpert } from '@/api/experts'
import '@/styles/expert-library.css'
import { ConnectorCard } from '@/components/market/ConnectorCard'
import { MarketCard } from '@/components/market/MarketCard'
import { ConnectorAuthDrawer } from '@/components/market/ConnectorAuthDrawer'
import { CustomConnectorDialog } from '@/components/market/CustomConnectorDialog'
import './marketPage.css'
import { categoryLabel } from '@/components/market/connectorMeta'
import { connectorLogo } from '@/assets/connectors'
import { selectProjectFolder } from '@/utils/projectFolder'
import { formatCountW } from '@/utils/format'
import { renderWikiMarkdown } from '@/utils/wikiMarkdown'
import type {
  AgentListItem,
  ExternalMarketPlugin,
  MarketExpert,
  MarketSkill,
} from '@/api/types'

/**
 * 市场浏览器（专家/技能/连接器）：弹窗与整页共用同一份内容。
 *
 * <p>外壳由调用方提供：`SkillMarketModal`（KB 独立窗仍用弹窗）包 `.sm-backdrop/.sm-panel`；
 * `MarketPage`（主窗口整页，工作区换页）包 `.market-page`。DOM/类名保持与旧弹窗一致，
 * 现有市场测试按 `.sm-tab-flat/.sm-installed-pill/.sm-subtab` 定位，不受外壳替换影响。
 *
 * <p>两种形态的差异只有三处：关闭按钮（✕ ↔ 返回）、顶栏密度（`mk-topbar`）、
 * 右上动作随 tab（整页对齐 WorkBuddy：专家=我的专家 / 技能=我安装的+添加技能 / 连接器=+自定义连接器）。
 *
 * 三种 Tab：专家（本地 + 远端混排大卡）/ 技能（分类胶囊 + 精选横条 + 推荐 3 列小卡）/ 连接器（3 列小卡）。
 * 两种来源（左上角下拉切换）：建科数字插件市场（自建 MinIO，/api/market/external 解析
 *   marketplace.json，按 category 分流）/ 本地（/agents/list + /api/command 本地技能 + 已装插件）。
 * - 专家「使用」：本地 agentType → App 走现有 PATCH mainThreadAgent；建科市场专家 → 安装插件。
 * - 技能「+」：建科市场走真实安装；本地来源只展示已装内容。
 * - 连接器：走独立的连接器授权 API（`/api/market/connectors`），⛔ 不再复用插件安装。
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
/** 技能分类固定口径（2026-11-23 用户截图拍板；⛔ 不再从技能 tags 动态生成）。 */
const FIXED_SKILL_CATS = ['Pay Skill', '办公效率', '内容创作', '开发编程', '数据分析', '设计多媒体', 'AI Agent', '知识管理', '商业运营', '教育学习', '行业专业', 'IT 运维与安全', '生活服务']
/** 技能原始分类/tag → 固定分类（只做展示映射，不改后端数据）。 */
function fixedSkillCat(categories?: string[] | null): string {
  const raw = primaryCategoryLabel(categories)
  if (!raw) return '办公效率'
  if (raw === '设计') return '设计多媒体'
  if (raw === '数据' || raw === '分析') return '数据分析'
  if (raw === '工程' || raw === 'SVN') return '开发编程'
  if (raw === '审查' || raw === '合规' || raw === '质量' || raw === '报告') return '行业专业'
  if (raw === '演示' || raw === '写作') return '办公效率'
  return '办公效率'
}

/** 技能缺失图标时按固定分类生成的线性图标（2026-11-23 用户要求：不再用紫色渐变兜底）。 */
const SKILL_GLYPHS: Record<string, ReactNode> = {
  'Pay Skill': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="6" width="18" height="13" rx="3" /><path d="M3 10h18M16 15h2" /></svg>,
  '办公效率': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M14 3H5v18h14V8zM14 3v5h5M8 13h8M8 17h6" /></svg>,
  '内容创作': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="m4 16-1 5 5-1L21 7l-4-4zM14 6l4 4" /></svg>,
  '开发编程': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18" /></svg>,
  '数据分析': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M4 4v16h17M8 15l4-5 4 2 5-7" /></svg>,
  '设计多媒体': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3a9 9 0 1 0 0 18h1.5a2.5 2.5 0 0 0 0-5H13a2 2 0 0 1 0-4h4a4 4 0 0 0 4-4 5 5 0 0 0-9-5z" /><circle cx="7.5" cy="10.5" r=".8" /><circle cx="10" cy="6.8" r=".8" /><circle cx="15" cy="6.5" r=".8" /></svg>,
  'AI Agent': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="8" width="16" height="12" rx="4" /><path d="M12 8V4m-3 8h.01M15 12h.01M9 16h6" /></svg>,
  '知识管理': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M4 5a2 2 0 0 1 2-2h13v18H6a2 2 0 0 0-2 2zM4 19V5M9 7h6" /></svg>,
  '商业运营': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M4 19V5m0 14h16M8 15v-4m4 4V8m4 7v-6m4 6V6" /></svg>,
  '教育学习': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="m3 9 9-5 9 5-9 5zM7 12v4c0 2 10 2 10 0v-4M21 9v6" /></svg>,
  '行业专业': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="m12 3 8 4v5c0 5-8 9-8 9s-8-4-8-9V7zM9 12l2.5 2.5L16 9" /></svg>,
  'IT 运维与安全': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="4" width="16" height="7" rx="2" /><rect x="4" y="14" width="16" height="7" rx="2" /><path d="M8 7.5h.01M8 17.5h.01M12 7.5h5M12 17.5h5" /></svg>,
  '生活服务': <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 6.6a5 5 0 0 0-7.1 0L12 8.3l-1.7-1.7a5 5 0 1 0-7.1 7.1L12 22l8.8-8.3a5 5 0 0 0 0-7.1z" /></svg>,
}

/** 技能图标：有 icon 用 icon；缺失/加载失败时按分类生成线性图标（不伪造品牌 logo）。 */
function SkillIcon({ icon, categories, className = 'sk-ico' }: { icon?: string | null; categories?: string[] | null; className?: string }) {
  const [failed, setFailed] = useState(false)
  const cat = fixedSkillCat(categories)
  const cls = cat.replace(/[^\w\u4e00-\u9fa5]+/g, '-')
  if (icon && !failed) {
    return (
      <span className={`${className} sk-ico-img`}>
        <img src={icon} alt="" loading="lazy" onError={() => setFailed(true)} />
      </span>
    )
  }
  return (
    <span className={`${className} sk-ico-gen cat-${cls}`} aria-hidden="true">
      {SKILL_GLYPHS[cat] ?? SKILL_GLYPHS['办公效率']}
    </span>
  )
}

/** 内置专家分类目录 → 中文徽章（后端 divisions.json 是 Octop 侧真源，这里只做展示映射） */
const DIVISION_LABELS: Record<string, string> = {
  academic: '学术', design: '设计', engineering: '工程', finance: '金融',
  'game-development': '游戏开发', gis: '地理信息', marketing: '市场营销', 'paid-media': '付费媒体',
  product: '产品', 'project-management': '项目管理', sales: '销售', security: '安全',
  'spatial-computing': '空间计算', specialized: '专项', 'supply-chain': '供应链',
  support: '技术支持', testing: '测试', hr: '人力资源', legal: '法务',
}
function colorOf(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

/**
 * 精选场景（专家页顶部）· 纯前端常量映射到已加载专家（不新增后端）。
 *
 * <p>2026-11-23 用户终稿：点场景 = 打开该场景代表专家的详情弹窗（⛔ 不再筛选下方列表）。
 * 关键词只用于挑出场景代表专家，不代表后端分类。
 */
const SCENES: { key: string; title: string; desc: string; keywords: string[] }[] = [
  { key: 'content', title: '内容创作', desc: '选题、脚本、改写与配图，一条链路做完', keywords: ['内容', '写作', '文案', '创作', '撰写', '编辑', 'docs', 'writing'] },
  { key: 'invest', title: '投资分析', desc: '财报拆解、估值与风险清单，结论带依据', keywords: ['投资', '财报', '金融', '估值', '投研', '分析', 'finance'] },
  { key: 'legal', title: '法律咨询', desc: '合同审查、合规风险与条款建议', keywords: ['法律', '合规', '合同', '法务', 'legal', 'compliance'] },
  { key: 'sme', title: '小微企业', desc: '记账、招聘、补贴申报的顾问团', keywords: ['小微', '记账', '招聘', '补贴', '财税', '企业', 'hr'] },
  { key: 'ecom', title: '电商运营', desc: '选品、详情页、投放到转化的全链路', keywords: ['电商', '运营', '选品', '店铺', '营销', '增长', 'marketing', 'sales'] },
  { key: 'data', title: '数据分析', desc: '问一句，就给 SQL、指标口径和结论', keywords: ['数据', '分析', '报表', '指标', 'sql', 'data', 'analyst'] },
]

/** 精选场景插画（扁平线性图标，衬在浅色横幅上；不用外链图片）。 */
const SCENE_ICONS: Record<string, ReactNode> = {
  content: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M28 6H12v36h24V14z" /><path d="M28 6v8h8M18 24h12M18 31h12M18 17h6" /></svg>,
  invest: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M8 40V8M8 40h32" /><path d="M14 32V22m8 10V16m8 16V26m8 6V12" /></svg>,
  legal: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M24 6l14 6v12c0 9-14 18-14 18S10 33 10 24V12z" /><path d="m17 24 5 5 10-12" /></svg>,
  sme: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="20" cy="18" r="7" /><path d="M8 40c0-8 5.5-12 12-12s12 4 12 12M34 22h8m-8 8h10m-10 8h6" /></svg>,
  ecom: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M12 16h24l-2 24H14z" /><path d="M18 16V11a6 6 0 0 1 12 0v5M18 26h12M19 33h8" /></svg>,
  data: <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M8 40V8M8 40h32" /><path d="m14 30 8-9 7 4 11-13" /><circle cx="40" cy="12" r="2.6" /></svg>,
}
/** 专家详情弹窗的「专家帮你做」示例任务（按场景；通用场景回落到三条通用示例）。 */
const SCENE_PROMPTS: Record<string, string[]> = {
  content: ['帮我写一篇小红书种草笔记', '把这段访谈整理成公众号推文', '为我的品牌写三条短文案'],
  invest: ['帮我写一份某公司的投资研究摘要', '构建 DCF 模型并做可比公司估值', '写一份多头/空头投资推介'],
  legal: ['审查这份采购合同的风险条款', '把违约责任条款改得更清晰', '列出这份协议需要补充的附件'],
  sme: ['帮我整理一份客户跟进计划', '设计一次销售团队的复盘会', '为小团队写一份招聘说明'],
  ecom: ['帮我拆解一轮新品上架计划', '写三条商品详情页卖点', '制定一周的投放监测清单'],
  data: ['帮我为这份销售数据设计分析口径', '把日报整理成一份周报结论', '找出这组数据里最值得关注的变化'],
}
const GENERIC_PROMPTS = (name: string) => [
  `帮我梳理一份「${name}」的工作方案`,
  `用「${name}」的视角分析当前问题`,
  `把结论整理成可执行的下一步`,
]
/** 专家团简介（与 HTML 设计稿一致；只做展示，不改变后端分组）。 */
const TEAM_DESCS: Record<string, string> = {
  engineering: '架构设计、前后端开发、代码审查与部署运维，覆盖从方案到交付的研发工作。',
  finance: '财务分析、投资研究、预算预测与税务筹划，让每一项财务判断有据可查。',
  marketing: '从内容策划、品牌传播到渠道运营与增长，找到适合业务的营销方法。',
  legal: '合同、文书与合规事项的专业支持，帮助识别风险并完善审查材料。',
  design: '用户研究、界面设计与品牌表达，让设计从需求理解走向可用的产品。',
  testing: '从接口测试、性能基准到可访问性检查，用证据检验交付质量。',
}

/**
 * 技能的「唯一分类」：取第一个非内部标记的 category/tag（`skill` 是内部标记，不展示）。
 * 一个技能只贡献、也只命中一个分类胶囊（用户 2026-11-17：胶囊太多，一个技能就一个）。
 */
function primaryCategoryLabel(categories?: string[] | null): string | null {
  for (const c of categories ?? []) {
    if (!c || c === 'skill') continue
    return CATEGORY_LABELS[c] ?? c
  }
  return null
}

/** ISO 时间 → 「2026-11-15 22:08」（本地时区）· 缺失/解析失败 → null（不渲染空占位） */
function formatPublishedAt(iso?: string | null): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
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
  /** 市场内容指纹/版本（远端专家才有；用于判定「可更新」） */
  fingerprint?: string | null
  version?: string | null
  /** 最近一次上架时间（ISO 8601 · 远端专家才有） */
  publishedAt?: string | null
  /** 开发者（远端专家才有；本机 agent 无作者信息 → null，卡片不渲染作者行） */
  authorName?: string | null
  authorVerified?: boolean
  authorAvatarUrl?: string | null
  authorEmail?: string | null
  /** 当前会话正在使用（mainThreadAgent === 该 agentType/agentName） */
  inUse: boolean
}

export function MarketBrowser({ sessionId, currentAgent, busy: _busy, initialTab, mode = 'modal', onClose, onUseLocalAgent, onSummonSkill, showToast }: {
  /** 当前会话 id（/agents/list + /market/* + POST use 需要） */
  sessionId: string
  /** 打开时停在哪个 tab（输入框 + 菜单「专家/技能/连接器」指定）· 缺省专家 */
  initialTab?: MarketTab
  /** 当前会话主线程 agent（null/空串=默认模式 · 专家卡「使用中」判定 + 使用后胶囊回显） */
  currentAgent?: string | null
  /**
   * 对话进行中标记（turn 运行）。
   * <p>⛔ <b>不作为任何操作门禁</b>：安装/更新/召唤/使用专家都不与对话互斥
   * （用户 2026-10-27 / 2026-11-06 / 2026-11-15 三次裁定）。
   * 保留参数是为了回归测试能显式传 busy=true 验证「仍可操作」。
   */
  busy?: boolean
  /** 关闭弹窗（✕ / 遮罩 / App Esc） */
  onClose: () => void
  /** 使用本地专家（agentType）→ App 调现有 handleAgentChange（PATCH mainThreadAgent）并关弹窗 */
  onUseLocalAgent?: (agentType: string) => void
  /** 召唤已安装技能（技能命令名 + 中文显示名）→ App 关弹窗 + 把「/命令名 」写入输入框（2026-10-19 用户需求） */
  onSummonSkill?: (commandName: string, displayName?: string) => void
  /** 轻提示 */
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 外壳形态：`modal` = SkillMarketModal（✕ 关闭）；`page` = 主区整页（← 返回对话，
   * 右上动作按 tab 收敛，不显示「NexusAI 市场」大标题）。
   */
  mode?: 'modal' | 'page'
}) {
  const [tab, setTab] = useState<MarketTab>(initialTab ?? 'expert')
  // 整页形态切页不重挂载：入口再次指定 tab 时必须跟着切（弹窗每次打开都是新挂载，不受影响）
  useEffect(() => {
    if (!initialTab) return
    setTab(initialTab)
    setInstalledOnly(false)
  }, [initialTab])
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
  // 已装插件的本地版本（key=插件名）· 内容没变但版本号变了也算「有更新」
  const [installedVer, setInstalledVer] = useState<Record<string, string>>({})
  // 安装中的市场 id 集合：按钮就地转圈 + 禁用重复点击（安装是下载 + 解包，耗时可达数秒，
  // 没有反馈时用户会以为没点上而反复点，产生并发安装）。
  const [installing, setInstalling] = useState<Set<string>>(new Set())
  // 安装进度（key=市场插件名）· 轮询 /api/plugins/install/progress 得到真实百分比
  const [progress, setProgress] = useState<Record<string, InstallProgress>>({})
  const [marketName, setMarketName] = useState('zjky-market')
  // 远程下载的插件专家（source='plugin'）· 供建科市场「使用」按钮定位 agentType（不混入本地来源）
  const [pluginAgents, setPluginAgents] = useState<AgentListItem[]>([])
  /** 已安装插件明细（「本地」来源展示；含版本/安装路径） */
  const [pluginDetails, setPluginDetails] = useState<InstalledPluginDetail[]>([])

  /** 应用已装插件明细：同步明细列表 + 名称→指纹映射（判定「可更新」的数据源）。 */
  const applyInstalledDetails = (list: InstalledPluginDetail[] | null | undefined) => {
    setPluginDetails(list ?? [])
    const fpMap: Record<string, string> = {}
    const verMap: Record<string, string> = {}
    for (const d of list ?? []) {
      if (d.name && d.fingerprint) fpMap[d.name] = d.fingerprint
      if (d.name && d.version) verMap[d.name] = d.version
    }
    setInstalledFp(fpMap)
    setInstalledVer(verMap)
  }

  // ---- 数据（按来源加载 · Promise.allSettled 容错：单个源失败不影响其余渲染）----
  const [localAgents, setLocalAgents] = useState<AgentListItem[]>([])
  const [experts, setExperts] = useState<MarketExpert[]>([])
  /** 本机已装技能（用户级 / 项目级 / 插件级 · 对应技能 tab 的「本机已装」子 tab） */
  const [skills, setSkills] = useState<MarketSkill[]>([])
  /** 市场技能（对应技能 tab 的「市场」子 tab）· 与 skills 分开，两个子 tab 各自渲染 */
  const [marketSkills, setMarketSkills] = useState<MarketSkill[]>([])
  /**
   * 连接器目录（**惰性加载**）：只在切到「连接器」tab 时才请求。
   *
   * <p>⛔ 不能挂在弹窗挂载期 —— 三个既有 SkillMarketModal 测试把 `@/api/market` mock 成
   * 封闭白名单，新增挂载期 market/connector 请求会让它们以 `is not a function` 直接红。
   */
  const [connectors, setConnectors] = useState<ConnectorDto[]>([])
  const [connState, setConnState] = useState<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const [connError, setConnError] = useState<string | null>(null)
  /** 正在查看 / 授权的连接器（null = 抽屉关闭） */
  /** 连接器详情弹窗（点卡片打开；「连接」再进授权抽屉） */
  const [connModal, setConnModal] = useState<ConnectorDto | null>(null)
  const [connDrawer, setConnDrawer] = useState<ConnectorDto | null>(null)
  /** 统一「自定义连接器」弹窗（连接器页唯一入口） */
  const [customOpen, setCustomOpen] = useState(false)
  /** 整页技能 tab 的「我安装的」管理弹窗（与内容区子 tab 筛选分离） */
  const [installedOpen, setInstalledOpen] = useState(false)
  /** 连接器分类 pill（'全部' = 不筛）与状态筛选 */
  const [connCat, setConnCat] = useState<string>('全部')
  const [connFilter, setConnFilter] = useState<'all' | 'connected' | 'needs-auth'>('all')
  /**
   * 连接器子 tab：已启用（已连接的目录条目）/ 内置（全量目录）/ 自定义（非目录的 MCP server）。
   * 默认「内置」= 对齐 Octop，也让既有用例（进连接器 tab 直接看到目录卡）不变。
   */
  const [connSrc, setConnSrc] = useState<'enabled' | 'builtin'>('builtin')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  /**
   * 专家页子 tab：专家（市场/本机条目）/ 专家团（内置专家按 division 分组的团队卡）。
   * 旧实现是「专家团 + 专家」上下两块，用户要求改成子 tab 切换。
   */
  const [expertView, setExpertView] = useState<'solo' | 'team'>('solo')
  /* 2026-11-23 用户终稿：专家页只保留「全部」，不再按 profession/tags 动态生成分类胶囊。 */
  const [expertCat] = useState('全部')
  /** 专家详情弹窗（场景卡/专家卡点击打开；sceneKey 决定「专家帮你做」示例任务） */
  const [sceneExpert, setSceneExpert] = useState<{ card: ExpertCard; sceneKey: string | null } | null>(null)
  /** 专家团成员弹窗（点「查看成员」打开；不再在卡片内联展开） */
  const [teamModal, setTeamModal] = useState<{ division: string; members: BundledExpert[] } | null>(null)
  const [bundledExperts, setBundledExperts] = useState<BundledExpert[]>([])
  const [bundledState, setBundledState] = useState<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const [bundledError, setBundledError] = useState<string | null>(null)
  /** 手动重试计数：失败后点「重试」→ 重新进入 idle 并触发 effect（不能把 bundledState 放进 deps，
   *  否则 setState('loading') 会让 effect 自我取消，加载永远停在 loading）。 */
  const [bundledRetry, setBundledRetry] = useState(0)
  /** 专家团区块是否已被用户请求加载（默认不加载：弹窗挂载期不许发请求） */
  const [teamRequested, setTeamRequested] = useState(false)
  const [bundledBusy, setBundledBusy] = useState<Set<string>>(new Set())

  // 建科市场插件（ExternalMarketPlugin）→ 现有 Market* 类型（按 category 分流到三 Tab）
  // [2026-11-01 v4] 补统计与开发者：后端已下发原始整数（useCount/downloadCount）与
  //   author（缺省「数字公司开发组」）。统计为 null 时保持 null —— 卡片据 null 整项不渲染。
  const toMarketExpert = (p: ExternalMarketPlugin): MarketExpert => ({
    marketId: p.name, agentName: p.name, displayName: p.displayName, description: p.description,
    tags: p.tags ?? [], categories: p.category ? [p.category] : [], remote: true,
    version: p.version ?? null,
    fingerprint: p.fingerprint ?? null,
    publishedAt: p.publishedAt ?? null,
    useCount: p.useCount ?? null,
    authorName: p.authorName ?? null,
    authorVerified: p.authorVerified ?? false,
    authorAvatarUrl: p.authorAvatarUrl ?? null,
    authorEmail: p.authorEmail ?? null,
    icon: p.icon ?? null,
  })
  const toMarketSkill = (p: ExternalMarketPlugin): MarketSkill => ({
    marketId: p.name, name: p.name, displayName: p.displayName, description: p.description,
    // 分类胶囊取「category + tags」（过滤掉内部标记 skill 后就是 docs/review/design/ppt…）
    categories: [p.category ?? '', ...(p.tags ?? [])].filter(Boolean), remote: true,
    fingerprint: p.fingerprint,
    version: p.version ?? null,
    publishedAt: p.publishedAt ?? null,
    useCount: p.useCount ?? null,
    downloadCount: p.downloadCount ?? null,
    authorName: p.authorName ?? null,
    authorVerified: p.authorVerified ?? false,
    authorAvatarUrl: p.authorAvatarUrl ?? null,
    authorEmail: p.authorEmail ?? null,
    icon: p.icon ?? null,
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
    // [2026-11-23 技能图标] SKILL.md frontmatter icon 透出；null = 卡片用统一兜底图标
    icon: c.icon ?? null,
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
      applyInstalledDetails(list)
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

      // ③ 市场条目（市场子 tab；专家/技能两个 Tab 共用同一份）
      //    ⚠️ 连接器不从这里取：独立目录由 `/api/market/connectors` 提供（惰性加载，见下）
      if (mR.status === 'fulfilled') {
        const m = mR.value
        if (m?.name) setMarketName(m.name)
        const pl = m?.plugins ?? []
        setExperts(pl.filter((p) => p.category === 'expert').map(toMarketExpert))
        setMarketSkills(pl.filter((p) => p.category === 'skill').map(toMarketSkill))
      } else {
        setExperts([]); setMarketSkills([])
        failMsgs.push(`建科市场：${formatFail(mR.reason)}`)
      }

      setLoadError(failMsgs.length ? `部分数据加载失败（已尽力展示可用项）：${failMsgs.join('；')}` : null)
    }).finally(done)

    return () => { cancelled = true }
  }, [sessionId, reloadKey])

  // ---- 连接器目录（惰性）：切到「连接器」tab 才拉一次 ----
  const loadConnectors = async () => {
    setConnState('loading')
    setConnError(null)
    try {
      const r = await connectorsApi.list(true)
      // 全量 30 条（含 hidden 的 qcc/tencent-ardot）：hidden 条目由卡片按 unavailable 置灰 + 「内部/企业」展示
      setConnectors((r?.connectors ?? []).filter((c) => !!c.authType))
      setConnState('loaded')
    } catch (e) {
      setConnState('error')
      setConnError(e instanceof ApiError ? e.userMessage() : String(e))
    }
  }
  useEffect(() => {
    if (tab === 'connector' && connState === 'idle') void loadConnectors()
    // idle → loading → loaded/error 只跑一次；失败不自动重试（抽屉下方给显式「重试」）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, connState])


  // ---- 内置专家目录（惰性）：切到「专家团」子 tab 才拉一次 ----
  useEffect(() => {
    if (tab !== 'expert' || bundledState !== 'idle') return
    if (expertView !== 'team' && !teamRequested) return
    let cancelled = false
    setBundledState('loading')
    setBundledError(null)
    void expertsApi.listBundled()
      .then((list) => {
        if (cancelled) return
        setBundledExperts(list ?? [])
        setBundledState('loaded')
      })
      .catch((e) => {
        if (cancelled) return
        setBundledState('error')
        setBundledError(e instanceof ApiError ? e.userMessage() : String(e))
      })
    return () => { cancelled = true }
    // bundledState 故意不进 deps：它是本 effect 的产物，进 deps 会自我取消（见 bundledRetry 注释）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, expertView, bundledRetry, teamRequested])

  /**
   * 抽屉回写权威状态 → 同步列表卡片与抽屉自身。
   * ⛔ 不做本地乐观置位：`connected/status/account` 全部来自后端 status 响应。
   */
  const applyConnectorStatus = (id: string, st: ConnectorStatusResponse) => {
    const patch = {
      connected: st.connected,
      status: st.connected ? ('connected' as const) : ('unconnected' as const),
      account: st.account ?? null,
      expiresAt: st.expiresAt ?? null,
    }
    setConnectors((prev) => prev.map((c) => (c.id === id ? { ...c, ...patch } : c)))
    setConnDrawer((prev) => (prev && prev.id === id ? { ...prev, ...patch } : prev))
  }

  /**
   * 市场拉取失败且用户没主动切到本机 ⇒ 内容区改为「只有失败说明 + 一个出口」。
   *
   * <p>判据用 `loadError` 里是否含「建科市场」也可以，但那耦合了文案；
   * 更稳的是「市场列表为空 **且** 加载错误非空」—— 市场为空但无错误 = 真的没有技能。
   */
  //    ⚠️ 只用**声明在上面**的 state 判定（expertCards 是 useMemo、在其后才声明，
  //   在这里引用会 TDZ 报错）。改用原始数据源：市场两个列表全空 + 有错误。
  const isMarketFailed = skillSrc === 'market' && !installedOnly
    && marketSkills.length === 0 && experts.length === 0
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
      // 连接器：只数真正可用的已连接（reauth_required 的凭据已失效，不算「已连接」）
      : connectors.filter((c) => c.connected && c.status !== 'reauth_required').length

  // ---- 技能分类胶囊：固定口径（用户 2026-11-23 截图拍板）----
  const skillCategories = FIXED_SKILL_CATS
  const [cat, setCat] = useState<string>('全部')

  // ---- 分类胶囊单行 + 翻页（超出部分点 ›/‹ 横向滚动，用户 2026-11-17）----
  const catRowRef = useRef<HTMLDivElement | null>(null)
  /** 精选场景横向滚动容器（左右翻页按钮用） */
  const sceneTrackRef = useRef<HTMLDivElement | null>(null)
  /** 顶栏搜索框（「添加技能 → 查找技能」聚焦用） */
  const searchRef = useRef<HTMLInputElement | null>(null)
  const [catNav, setCatNav] = useState({ prev: false, next: false })
  const refreshCatNav = () => {
    const el = catRowRef.current
    if (!el) return
    setCatNav({
      prev: el.scrollLeft > 2,
      next: el.scrollLeft + el.clientWidth < el.scrollWidth - 2,
    })
  }
  const pageCat = (dir: 1 | -1) => {
    const el = catRowRef.current
    if (!el) return
    el.scrollBy({ left: dir * Math.max(120, el.clientWidth - 80), behavior: 'smooth' })
  }
  useEffect(() => {
    refreshCatNav()
    const el = catRowRef.current
    if (!el) return
    // jsdom / 老环境没有 ResizeObserver（测试里会直接抛 ReferenceError 把整个弹窗打挂）。
    // 与 MessageList 的滚动跟随守卫同款：能力缺失时静默跳过，不影响首屏渲染。
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => refreshCatNav())
    ro.observe(el)
    return () => ro.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skillCategories, skillSrc, installedOnly, tab])

  // ---- 搜索匹配（名字/展示名/描述 contains · 大小写不敏感）----
  const keyword = q.trim().toLowerCase()
  const matchAny = (...parts: Array<string | null | undefined>) =>
    keyword.length === 0 || parts.some((p) => (p ?? '').toLowerCase().includes(keyword))

  // ---- 内置专家：搜索过滤（名称/描述/slug）----
  const bundledFiltered = useMemo(
    () => bundledExperts.filter((e) => matchAny(e.name, e.description, e.slug, DIVISION_LABELS[e.division])),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [bundledExperts, keyword],
  )

  /**
   * 安装单个内置专家。
   * ⛔ 不做本地乐观置位：只有后端返回成功才把该条置为「已安装」；
   * 409（本地已有同名且内容不同）如实提示，不伪造成功。
   */
  const installBundled = async (e: BundledExpert) => {
    if (bundledBusy.has(e.slug)) return
    setBundledBusy((prev) => new Set(prev).add(e.slug))
    try {
      const r = await expertsApi.installBundled(e.slug)
      setBundledExperts((prev) => prev.map((x) => (x.slug === e.slug ? { ...x, installed: true } : x)))
      showToast(
        r.status === 'UNCHANGED' ? `专家 ${e.name} 已是最新` : `已安装专家 ${e.name}`,
        'success',
      )
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        showToast('该专家已存在且本地内容与内置版本不同；如确认覆盖，请先移走本地文件或带 overwrite 重新安装', 'info')
      } else {
        showToast(err instanceof ApiError ? err.userMessage() : String(err), 'info')
      }
    } finally {
      setBundledBusy((prev) => {
        const next = new Set(prev)
        next.delete(e.slug)
        return next
      })
    }
  }

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
        fingerprint: e.fingerprint ?? null,
        version: e.version ?? null,
        publishedAt: e.publishedAt ?? null,
        authorName: e.authorName ?? null,
        authorVerified: !!e.authorVerified,
        authorAvatarUrl: e.authorAvatarUrl ?? null,
        authorEmail: e.authorEmail ?? null,
      })
    }
    return cards.filter((c) => matchAny(c.name, c.subtitle, c.desc, c.authorName))
  }, [localAgents, experts, currentAgent, keyword, installedOnly]) // eslint-disable-line react-hooks/exhaustive-deps

  /** 专家卡是否命中精选场景（名称 / 描述 / 岗位 / 标签任一处含关键词）。 */
  const sceneHit = (c: ExpertCard, scene: { keywords: string[] }) => {
    const hay = [c.name, c.subtitle, c.desc, ...c.tags].join(' ').toLowerCase()
    return scene.keywords.some((k) => hay.includes(k.toLowerCase()))
  }

  /** 精选场景卡片上的成员预览（最多 2 位，与设计稿一致）。 */
  const sceneMembers = (scene: { keywords: string[] }) => expertCards.filter((c) => sceneHit(c, scene)).slice(0, 2)

  /** 专家卡可见集合（只按搜索过滤；分类只剩「全部」）。 */
  const visibleExperts = useMemo(() => {
    return expertCards.filter((c) => {
      if (expertCat !== '全部' && !(c.subtitle.includes(expertCat) || c.tags.includes(expertCat) || c.name.includes(expertCat))) return false
      return true
    })
  }, [expertCards, expertCat])

  /** 专家团：内置专家按 division 分组（搜索已作用于 bundledFiltered，空组自然消失）。 */
  const teamGroups = useMemo(() => {
    const map = new Map<string, BundledExpert[]>()
    for (const e of bundledFiltered) {
      const arr = map.get(e.division) ?? []
      arr.push(e)
      map.set(e.division, arr)
    }
    return Array.from(map.entries()).map(([division, members]) => ({ division, members }))
  }, [bundledFiltered])

  // ---- 安装建科市场插件（POST /api/plugins/install · 后端自动 reconcile 市场 + 安装链）----
  /**
   * 市场里有更新？内容指纹不同（重新上架覆盖内容）或版本号不同（只改版本号发版）都算。
   * 版本号必须参与：新版若只改版本、内容字节一致，单看指纹会永远显示「已安装」。
   */
  const hasUpdate = (marketId: string, marketFp?: string | null, marketVer?: string | null) =>
    (!!marketFp && !!installedFp[marketId] && marketFp !== installedFp[marketId]) ||
    (!!marketVer && !!installedVer[marketId] && marketVer !== installedVer[marketId])

  const installMarketPlugin = async (pluginName: string) => {
    // ⭐ 安装/更新**不受对话进行中互斥**（用户 2026-11-15：更新技能不该提示「对话进行中」）。
    //   理由与召唤/使用专家同一条裁定：安装只是下载解包 + 刷注册表，不发消息、不切轮；
    //   正在跑的那一轮已固化技能定义，刷新对它是安全的。
    //   ⛔ 不要在这里加 busy 门。防重复点击靠下面的 installing 集合。
    // 同一插件已在安装中 → 直接忽略（按钮虽已禁用，键盘/连点仍可能进来）
    if (installing.has(pluginName)) return
    const isUpdate = installed.has(pluginName)
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
        showToast(`${isUpdate ? '已更新' : '已安装'} ${r.pluginName || pluginName}`, 'success')
        // 安装/更新后重拉本机技能与已装明细 ⇒ 「我安装的」列表与经计数立即联动
        setReloadKey((k) => k + 1)
        // 更新后必须重算本地指纹，否则「可更新」标记不会消失
        if (isUpdate) {
          void marketApi.listInstalledDetails()
            .then((list) => applyInstalledDetails(list))
            .catch(() => { /* 刷新失败：保留旧指纹，下次打开弹窗再对齐 */ })
        }
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

  /** 打开开发者信息弹窗（按 author.name 聚合）。技能卡 / 专家卡 / 详情页共用。 */
  const openDeveloper = (s: {
    authorName?: string | null
    authorVerified?: boolean | null
    authorEmail?: string | null
    authorAvatarUrl?: string | null
  }) => {
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
    //   ⚠️ 市场专家的「使用」/「安装/更新」实际是**安装插件**（写磁盘 + reconcile）；
    //   安装同样不与对话互斥（用户 2026-11-15），故整条链都不看 busy。
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
  /**
   * 技能目录：默认只展示**市场**技能（用户 2026-11-23 终稿）。
   * 本机技能默认不混入；要看本机 → 右上「我安装的」（整页）或「已安装」筛选（弹窗形态）。
   */
  const srcSkills = useMemo(
    () => (installedOnly ? skills : marketSkills),
    [installedOnly, skills, marketSkills],
  )
  const filteredSkills = useMemo(() => {
    return srcSkills.filter((s) => {
      if (cat !== '全部' && fixedSkillCat(s.categories) !== cat) return false
      return matchAny(s.displayName, s.name, s.description, s.authorName)
    })
  }, [srcSkills, cat, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 本地已安装插件过滤（仅「本地」来源展示）----
  const filteredPlugins = useMemo(() => {
    return pluginDetails.filter((p) => matchAny(p.name, p.version))
  }, [pluginDetails, keyword]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 连接器分类（只取有 connection 块且带 category 的条目；顺序按首次出现，稳定不乱跳）----
  const connectorCategories = useMemo(() => {
    const out: string[] = []
    for (const c of connectors) {
      if (c.authType && c.category && !out.includes(c.category)) out.push(c.category)
    }
    return out
  }, [connectors])

  // ---- 连接器三子 tab 的数据视图 ----
  /** 已启用 = 凭据有效且已连接（reauth_required 不算可用） */
  const enabledConnectors = useMemo(
    () => connectors.filter((c) => c.connected && c.status !== 'reauth_required'),
    [connectors],
  )
  /** 目录 key 集合：自定义 tab 要排除「由连接器授权创建的同名 MCP server」 */
  const catalogKeys = useMemo(() => new Set(connectors.map((c) => c.key)), [connectors])

  // ---- 连接器过滤（分类 + 状态 + 搜索）----
  // 「需授权」= 未连接，或凭据还在但已失效（reauth_required）
  const filteredConnectors = useMemo(() => {
    return connectors.filter((c) => {
      if (connCat !== '全部' && c.category !== connCat) return false
      if (connFilter === 'connected'
        && !(c.connected && c.status !== 'reauth_required')) return false
      if (connFilter === 'needs-auth'
        && !(!c.connected || c.status === 'reauth_required')) return false
      return matchAny(c.name, c.key, c.description, c.authType, c.status, c.account, (c.scopes ?? []).join(' '))
    })
  }, [connectors, keyword, connCat, connFilter]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 「+」安装 UI 高亮（骨架：仅本地 Set，不做真实安装）----

  // ---- 头像（远端 icon URL → img；缺失/加载失败 → 色块首字母，⛔ 不许破图）----
  const renderAvatar = (c: { iconUrl: string | null; iconColor: string; iconText: string; name: string }) => (
    <span className="sm-avatar" style={{ background: c.iconColor, position: 'relative' }}>
      {c.iconText}
      {c.iconUrl && (
        <img
          className="sm-avatar-img"
          src={c.iconUrl}
          alt={c.name}
          loading="lazy"
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
          onError={(e) => { e.currentTarget.style.display = 'none' }}
        />
      )}
    </span>
  )

  // ---- 内置专家（专家团子 tab）：团队卡 + 成员安装态在下方 JSX 内渲染 ----

  return (
    <>
        {/* ===== 顶栏（v4）：返回/关闭 + 一级 tab + 右侧 搜索 / 已安装(N) / 添加XX =====
            [2026-11-01] 原「市场源下拉」已删 —— 它与技能 tab 内的「本机/市场」子 tab
            语义完全重叠（两个控件说同一件事）。同理「已安装 N」计数原有两处，
            现在只保留这一个，并且是**可点的筛选**。
            顺序按用户指定：搜索 → 已安装(N) → 添加XX（已安装在添加左侧）。 */}
        <div className={`sm-topbar${mode === 'page' ? ' mk-topbar' : ''}`}>
          {mode === 'page' ? (
            <button className="sm-icon-btn" onClick={onClose} title="返回对话" aria-label="返回对话">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" style={{ width: 15, height: 15 }}>
                <path d="M15 5l-7 7 7 7" />
              </svg>
            </button>
          ) : (
            <button className="sm-icon-btn" onClick={onClose} title="关闭（Esc）" aria-label="关闭">
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" style={{ width: 13, height: 13 }}>
                <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" />
              </svg>
            </button>
          )}
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
                ref={searchRef}
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder={SEARCH_PLACEHOLDERS[tab]}
              />
            </div>
            {/* 整页形态对齐 WorkBuddy：右上动作随 tab —— 专家=我的专家 / 技能=我安装的+添加技能 /
                连接器=+自定义连接器。技能页的「我安装的」是**管理入口**（打开已安装弹窗），
                与内容区「本机已装/市场」子 tab 的筛选不是同一件事。
                弹窗形态保持旧行为（已安装(N)/已连接(N) 筛选），既有测试依赖该 DOM。 */}
            {mode === 'page' && tab === 'skill' ? (
              <button
                className="sm-installed-pill"
                onClick={() => setInstalledOpen(true)}
                title={`管理已安装技能（${skills.length} 个）`}
              >
                <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M8 12l3 3 5-6" /></svg>
                我安装的 ({skills.length})
              </button>
            ) : mode === 'page' && tab === 'connector' ? null : (
              <button
                className={`sm-installed-pill${(tab === 'connector' ? connFilter === 'connected' : installedOnly) ? ' on' : ''}`}
                onClick={() => {
                  if (tab === 'connector') {
                    setConnFilter((v) => (v === 'connected' ? 'all' : 'connected'))
                    return
                  }
                  setInstalledOnly((v) => !v)
                }}
                title={
                  tab === 'connector'
                    ? (connFilter === 'connected'
                      ? `正在只看已连接（${installedCount} 项）· 点一下回到全部`
                      : `只看已连接（${installedCount} 项）`)
                    : (installedOnly
                      ? `正在只看本机已安装（${installedCount} 项）· 点一下回到全部`
                      : `只看本机已安装（${installedCount} 项）`)
                }
              >
                <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M8 12l3 3 5-6" /></svg>
                {tab === 'connector' ? '已连接' : tab === 'expert' && mode === 'page' ? '我的专家' : '已安装'} ({installedCount})
              </button>
            )}
            <div className="sm-add">
              {mode === 'page' && tab === 'expert' ? null : tab === 'connector' ? (
                /* 连接器页唯一入口：列表/表单/JSON 都在弹窗内（上传技能/专家属于技能/专家上下文） */
                <button className="sm-add-primary" onClick={() => setCustomOpen(true)} title="添加自定义连接器">
                  <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                  自定义连接器
                </button>
              ) : (
                <>
                  <button className="sm-add-primary" onClick={() => setAddOpen((v) => !v)} title="添加">
                    <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                    {ADD_LABELS[tab]}
                  </button>
                  {addOpen && (
                    <div className="sm-add-menu">
                      {tab === 'skill' && (
                        <div className="sm-add-item" onClick={() => { setAddOpen(false); searchRef.current?.focus() }}>
                          查找技能
                        </div>
                      )}
                      <div className="sm-add-item" onClick={() => { setAddOpen(false); setPublishCat('skill') }}>
                        上传技能
                      </div>
                      {tab !== 'skill' && (
                        <div className="sm-add-item" onClick={() => { setAddOpen(false); setPublishCat('expert') }}>
                          上传专家
                        </div>
                      )}
                      <div className="sm-add-item" onClick={() => {
                        setAddOpen(false)
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
                </>
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
                <div className="mk-expert">
                  {/* ===== 精选场景：点场景打开代表专家的详情弹窗（⛔ 不筛选列表）===== */}
                  {!keyword && (
                    <div className="mk-section" data-testid="expert-scenes">
                      <div className="mk-section-head">
                        <div>
                          <h3 className="mk-h2">精选场景</h3>
                          <p className="mk-sub">从要做的事，找到合适的专家</p>
                        </div>
                        <div className="mk-scene-nav">
                          <button className="mk-scene-arrow" aria-label="上一组场景" onClick={() => sceneTrackRef.current?.scrollBy({ left: -320, behavior: 'smooth' })}>‹</button>
                          <button className="mk-scene-arrow" aria-label="下一组场景" onClick={() => sceneTrackRef.current?.scrollBy({ left: 320, behavior: 'smooth' })}>›</button>
                        </div>
                      </div>
                      <div className="mk-scene-scroll" ref={sceneTrackRef}>
                        {SCENES.map((s) => {
                          const members = sceneMembers(s)
                          const open = (card?: ExpertCard) => {
                            const target = card ?? members[0]
                            if (!target) { showToast('该场景暂无匹配的专家，请稍后在目录中查看', 'info'); return }
                            setSceneExpert({ card: target, sceneKey: s.key })
                          }
                          return (
                            <div
                              key={s.key}
                              className="mk-scene"
                              data-scene={s.key}
                              role="button"
                              tabIndex={0}
                              onClick={() => open()}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open() } }}
                            >
                              <div className={`mk-scene-art art-${s.key}`}>
                                <span className="mk-scene-art-ico">{SCENE_ICONS[s.key]}</span>
                                <span className="mk-scene-title">{s.title}</span>
                              </div>
                              <div className="mk-scene-body">
                                <span className="mk-scene-desc">{s.desc}</span>
                                <span className="mk-scene-people">
                                  {members.length > 0
                                    ? members.map((m) => (
                                      <button
                                        key={m.key}
                                        className="mk-scene-person"
                                        onClick={(e) => { e.stopPropagation(); open(m) }}
                                      >
                                        {renderAvatar(m)}
                                        <span className="mk-scene-person-name">{m.name}</span>
                                      </button>
                                    ))
                                    : <span className="mk-scene-person-name">暂无匹配</span>}
                                </span>
                              </div>
                            </div>
                          )
                        })}
                      </div>
                    </div>
                  )}

                  {/* ===== 子 tab：专家 / 专家团（替换原来的上下两块）===== */}
                  <div className="sm-subtabs">
                    <button
                      className={`sm-subtab${expertView === 'solo' ? ' active' : ''}`}
                      onClick={() => { setExpertView('solo'); setInstalledOnly(false) }}
                    >
                      专家
                    </button>
                    <button
                      className={`sm-subtab${expertView === 'team' ? ' active' : ''}`}
                      onClick={() => { setExpertView('team'); setInstalledOnly(false) }}
                    >
                      专家团
                    </button>
                  </div>

                  {expertView === 'solo' ? (
                    <>
                      <div className="sm-cat-row">
                        <div className="sm-cat-scroll">
                          <button className="sm-cat active" aria-pressed="true">全部</button>
                        </div>
                        <span className="sm-result-count">{visibleExperts.length} 位</span>
                      </div>
                      {isMarketFailed ? (
                        <div className="sm-market-failed">
                          <div className="sm-market-failed-txt">市场数据拉取失败，暂无可展示的专家。</div>
                          <button
                            className="sm-market-failed-btn"
                            onClick={() => { setInstalledOnly(true) }}
                          >
                            查看本机已装 ({localAgents.filter((a) => a.source !== 'plugin').length})
                          </button>
                        </div>
                      ) : visibleExperts.length === 0 ? (
                        <div className="sm-empty">
                          {keyword ? '没有匹配的专家' : '暂无可用专家'}
                        </div>
                      ) : (
                        <div className="sm-expert-grid">
                          {visibleExperts.map((c) => (
                            <MarketCard
                              key={c.key}
                              extraClass={`sm-card sm-expert-card${c.kind === 'remote' ? ' remote' : ''}${c.inUse ? ' in-use' : ''}`}
                              accent={c.iconColor}
                              onClick={() => setSceneExpert({ card: c, sceneKey: null })}
                              icon={renderAvatar(c)}
                              title={c.name}
                              chip={c.kind === 'local' ? '本地专家' : (c.subtitle.split(' · ')[0] || '市场专家')}
                              badges={c.kind === 'remote' && c.installed
                                ? <span className="sm-badge inst" title="市场源已内置预装">已安装</span>
                                : null}
                              description={c.desc}
                              extra={(
                                <>
                                  {c.subtitle && <div className="mk-expert-role" title={c.subtitle}>{c.subtitle}</div>}
                                  {c.authorName && (
                                    <div
                                      className="sk-author"
                                      onClick={(e) => { e.stopPropagation(); openDeveloper(c) }}
                                      title={`查看 ${c.authorName} 的全部作品`}
                                    >
                                      <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>
                                      <b>{c.authorName}</b>
                                      {c.authorVerified && (
                                        <span className="ver" title="已认证发布者">
                                          <svg viewBox="0 0 24 24"><path d="M12 2l2.4 1.8 3-.2 1 2.8 2.6 1.5-1 2.9 1 2.9-2.6 1.5-1 2.8-3-.2L12 22l-2.4-1.8-3 .2-1-2.8L3 16.1l1-2.9-1-2.9 2.6-1.5 1-2.8 3 .2L12 2z" fill="currentColor" /><path d="M8.5 12l2.5 2.5 4.5-4.5" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                                        </span>
                                      )}
                                    </div>
                                  )}
                                  {c.tags.length > 0 && (
                                    <div className="sm-tags">
                                      {c.tags.map((t) => <span key={t} className="sm-tag">{t}</span>)}
                                    </div>
                                  )}
                                  {c.publishedAt && (
                                    <div className="sm-card-sub" title={c.publishedAt}>
                                      上架时间：{formatPublishedAt(c.publishedAt)}
                                    </div>
                                  )}
                                </>
                              )}
                              footerRight={<span style={{ display: 'contents' }} onClick={(e) => e.stopPropagation()}>{c.kind === 'remote' ? (
                                installed.has(c.marketId) ? (
                                  <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                    {hasUpdate(c.marketId, c.fingerprint, c.version) ? (
                                      <button
                                        className={`sm-use-btn${installing.has(c.marketId) ? ' installing' : ''}`}
                                        disabled={installing.has(c.marketId)}
                                        onClick={() => void installMarketPlugin(c.marketId)}
                                      >
                                        {installing.has(c.marketId) ? `${progress[c.marketId]?.percent ?? 0}%` : '更新'}
                                      </button>
                                    ) : (
                                      <>
                                        <span className="sm-installed-mark">已安装</span>
                                        <button className="sm-use-btn" onClick={() => useInstalledPlugin(c.marketId)}>使用</button>
                                      </>
                                    )}
                                  </span>
                                ) : (
                                  <button
                                    className={`sm-use-btn${installing.has(c.marketId) ? ' installing' : ''}`}
                                    disabled={installing.has(c.marketId)}
                                    onClick={() => void installMarketPlugin(c.marketId)}
                                  >
                                    {installing.has(c.marketId) ? `${progress[c.marketId]?.percent ?? 0}%` : '安装'}
                                  </button>
                                )
                              ) : (
                                <button
                                  className={`sm-use-btn${c.inUse ? ' used' : ''}`}
                                  disabled={c.inUse}
                                  onClick={() => handleUse(c)}
                                >
                                  {c.inUse ? '使用中' : '使用'}
                                </button>
                              )}</span>}
                            />
                          ))}
                        </div>
                      )}
                    </>
                  ) : (
                    <div className="mk-team-wrap">
                      <div className="mk-section-head" style={{ marginTop: 4 }}>
                        <div>
                          <h3 className="mk-h2">专家团</h3>
                          <p className="mk-sub">平台、团队与个人发布的专家，可按组查看成员</p>
                        </div>
                        <span className="sm-result-count">{bundledFiltered.length} 位内置专家</span>
                      </div>
                      <div className="mc-hint">随安装包提供，按需安装；点「查看成员」查看该组专家并逐个安装。</div>
                      {bundledState === 'idle' && (
                        <div className="mc-hint" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          内置专家团（按分组归档）按需加载，不占用打开时间。
                          <button className="mc-btn" onClick={() => setTeamRequested(true)}>加载专家团</button>
                        </div>
                      )}
                      {bundledState === 'loading' && <div className="sm-loading">专家团加载中…</div>}
                      {bundledState === 'error' && (
                        <div className="sm-market-failed">
                          <div className="sm-market-failed-txt">内置专家目录加载失败：{bundledError}</div>
                          <button className="sm-market-failed-btn" onClick={() => { setBundledState('idle'); setBundledRetry((n) => n + 1) }}>重试</button>
                        </div>
                      )}
                      {bundledState === 'loaded' && teamGroups.length === 0 && (
                        <div className="sm-empty small">{keyword ? '没有匹配的内置专家' : '暂无内置专家'}</div>
                      )}
                      {bundledState === 'loaded' && teamGroups.length > 0 && (
                        <div className="mk-team-grid">
                          {teamGroups.map((g) => (
                            <div className="mk-team-card" key={g.division} data-testid="expert-team-card">
                              <div className="mk-team-top">
                                <span className="mk-team-art">
                                  {g.members.slice(0, 3).map((e) => (
                                    <span key={e.slug} className="sm-avatar sm-avatar-sm" style={{ background: e.color || colorOf(e.name) }}>
                                      {e.emoji || e.name.charAt(0).toUpperCase()}
                                    </span>
                                  ))}
                                </span>
                                <div className="mk-team-titles">
                                  <span className="mk-team-name">{DIVISION_LABELS[g.division] ?? g.division}专家团</span>
                                  <span className="mk-team-count">{g.members.length} 位内置专家{g.members.some((m) => m.installed) ? ` · 已安装 ${g.members.filter((m) => m.installed).length}` : ''}</span>
                                </div>
                              </div>
                              <p className="mk-team-desc">{TEAM_DESCS[g.division] ?? g.members.slice(0, 3).map((e) => e.name).join('、') + '等专业角色，按任务查看成员并选择合适的专家。'}</p>
                              <div className="sm-tags">
                                {g.members.slice(0, 3).map((e) => <span key={e.slug} className="sm-tag">{e.name}</span>)}
                              </div>
                              <div className="mk-team-foot">
                                <span className="mk-team-more">按专业分组 · 成员独立安装</span>
                                <button className="mc-btn" onClick={() => setTeamModal({ division: g.division, members: g.members })}>查看成员</button>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
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
                  {/* 2026-11-23：去掉「本机已装 / 市场」子 tab（用户截图终稿），目录直接展示全部技能 */}
                  <div className="sm-skill-head">
                    <h3 className="mk-h2">技能目录</h3>
                    <span className="sm-result-count">{filteredSkills.length} 个技能</span>
                  </div>

                  {/* 分类胶囊（单行 + 左右翻页）+ 计数：始终常驻（筛选后结果变少也不会失去退回「全部」的入口） */}
                  <div className="sm-cat-row">
                    <div className="sm-cat-scroll" ref={catRowRef} onScroll={refreshCatNav}>
                      {['全部', ...skillCategories].map((c) => (
                        <button key={c} className={`sm-cat${cat === c ? ' active' : ''}`} onClick={() => setCat(c)}>
                          {c}
                        </button>
                      ))}
                    </div>
                    <button
                      className={`sm-cat-page${catNav.prev ? '' : ' off'}`}
                      onClick={() => pageCat(-1)}
                      aria-label="上一页分类"
                      tabIndex={catNav.prev ? 0 : -1}
                    >‹</button>
                    <button
                      className={`sm-cat-page${catNav.next ? '' : ' off'}`}
                      onClick={() => pageCat(1)}
                      aria-label="下一页分类"
                      tabIndex={catNav.next ? 0 : -1}
                    >›</button>
                    <span className="sm-result-count">{filteredSkills.length} 个技能</span>
                  </div>

                  {filteredSkills.length === 0 ? (
                    <div className="sm-empty small">
                      {keyword
                        ? '没有匹配的技能'
                        : cat !== '全部'
                          ? '该分类暂无技能'
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
                          hasUpdate={hasUpdate(s.marketId, s.fingerprint, s.version)}
                          onSummon={() => summonSkill(s)}
                          onInstall={(id) => void installMarketPlugin(id)}
                          onUpdate={(id) => void installMarketPlugin(id)}
                          onOpenDetail={() => setDetailSkill(s)}
                          onOpenDeveloper={s.authorName ? () => openDeveloper(s) : undefined}
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
                /* 连接器 = 独立目录（惰性加载）+ 两子 tab：已启用 / 内置；「+ 自定义连接器」是唯一自定义入口 */
                <>
                  <div className="mc-cat-row" style={{ marginBottom: 4 }}>
                    <div className="mc-cat-scroll">
                      {([['enabled', '已启用'], ['builtin', '内置']] as const).map(([v, l]) => (
                        <button
                          key={v}
                          className={`mc-cat${connSrc === v ? ' active' : ''}`}
                          onClick={() => setConnSrc(v)}
                        >
                          {l}
                        </button>
                      ))}
                    </div>
                    <span className="sm-result-count">
                      {connSrc === 'enabled' ? `${enabledConnectors.length} 个` : `${connectors.length} 个`}
                    </span>
                  </div>
                  {connSrc === 'builtin' && (
                    connState === 'idle' || connState === 'loading' ? (
                  <div className="sm-loading">连接器加载中…</div>
                ) : connState === 'error' ? (
                  <div className="sm-market-failed">
                    <div className="sm-market-failed-txt">连接器服务不可用：{connError}</div>
                    <button className="sm-market-failed-btn" onClick={() => void loadConnectors()}>重试</button>
                  </div>
                ) : (
                  <>
                    {/* 分类 pill（数据来自 ConnectorDto.category；只在有分类时出现） */}
                    {connectorCategories.length > 0 && (
                      <div className="mc-cat-row">
                        <div className="mc-cat-scroll">
                          {['全部', ...connectorCategories].map((c) => (
                            <button
                              key={c}
                              className={`mc-cat${connCat === c ? ' active' : ''}`}
                              onClick={() => setConnCat(c)}
                            >
                              {c === '全部' ? '全部' : (categoryLabel(c) ?? c)}
                            </button>
                          ))}
                        </div>
                        <span className="sm-result-count">{filteredConnectors.length} 个连接器</span>
                      </div>
                    )}
                    {/* 状态筛选：全部 / 已连接 / 需授权（需授权 = 未连接或 reauth_required） */}
                    <div className="mc-cat-row" style={{ marginTop: connectorCategories.length > 0 ? 0 : 12 }}>
                      <div className="mc-cat-scroll">
                        {([['all', '全部'], ['connected', '已连接'], ['needs-auth', '需授权']] as const).map(([v, l]) => (
                          <button
                            key={v}
                            className={`mc-cat${connFilter === v ? ' active' : ''}`}
                            onClick={() => setConnFilter(v)}
                          >
                            {l}
                          </button>
                        ))}
                      </div>
                      {connectorCategories.length === 0 && (
                        <span className="sm-result-count">{filteredConnectors.length} 个连接器</span>
                      )}
                    </div>

                    {filteredConnectors.length === 0 ? (
                      <div className="sm-empty small">
                        {keyword
                          ? '没有匹配的连接器'
                          : connFilter === 'needs-auth'
                            ? '没有待授权的连接器'
                            : connFilter === 'connected'
                              ? '还没有已连接的连接器'
                              : '暂无可用连接器'}
                      </div>
                    ) : (
                      <div className="mc-grid">
                        {filteredConnectors.map((c) => (
                          <ConnectorCard
                            key={c.id}
                            connector={c}
                            onOpen={() => setConnModal(c)}
                          />
                        ))}
                      </div>
                    )}
                  </>
                    )
                  )}

                  {/* 已启用：凭据有效且已连接的目录条目 */}
                  {connSrc === 'enabled' && (
                    enabledConnectors.length === 0 ? (
                      <div className="sm-empty small">
                        还没有已启用的连接器 · 去「内置」里完成授权
                      </div>
                    ) : (
                      <div className="mc-grid">
                        {enabledConnectors.map((c) => (
                          <ConnectorCard key={c.id} connector={c} onOpen={() => setConnModal(c)} />
                        ))}
                      </div>
                    )
                  )}

                </>
              )}
            </>
          )}
        </div>

        {/* 自定义连接器（唯一入口：列表 / 表单 / JSON） */}
        {customOpen && (
          <CustomConnectorDialog
            catalogKeys={catalogKeys}
            onClose={() => setCustomOpen(false)}
            showToast={showToast}
          />
        )}

        {/* 「我安装的」管理弹窗（整页技能 tab 右上入口；与内容区子 tab 的筛选分离） */}
        {installedOpen && (
          <InstalledSkillsDialog
            skills={skills}
            onClose={() => setInstalledOpen(false)}
            onSummon={(s) => { setInstalledOpen(false); void summonSkill(s) }}
            onUninstall={(s) => void handleUninstall(s, true)}
          />
        )}

        {/* 连接器授权抽屉 · 后端权威状态回写列表卡片 */}
        {connDrawer && (
          <ConnectorAuthDrawer
            connector={connDrawer}
            onClose={() => setConnDrawer(null)}
            onStatusChange={applyConnectorStatus}
            showToast={showToast}
          />
        )}

        {/* 上架弹窗（上传技能 / 上传专家）· 上架成功后重新拉市场列表 */}
        {publishCat && (
          <PublishDialog
            category={publishCat}
            onClose={() => setPublishCat(null)}
            onDone={() => { setPublishCat(null); setReloadKey((k) => k + 1) }}
            showToast={showToast}
          />
        )}

        {/* 连接器详情弹窗（2026-11-23 设计稿）· 点卡片打开；「连接」再进授权抽屉 */}
        {connModal && (
          <ConnectorDetailDialog
            connector={connModal}
            onClose={() => setConnModal(null)}
            onConnect={() => {
              const c = connModal
              setConnModal(null)
              setConnDrawer(c)
            }}
          />
        )}

        {/* 专家详情弹窗（2026-11-23 设计稿）· 场景卡 / 专家卡点击打开 */}
        {sceneExpert && (
          <ExpertDetailDialog
            card={sceneExpert.card}
            sceneKey={sceneExpert.sceneKey}
            installed={sceneExpert.card.kind === 'local' || installed.has(sceneExpert.card.marketId)}
            installing={installing.has(sceneExpert.card.marketId)}
            installPercent={progress[sceneExpert.card.marketId]?.percent ?? 0}
            onClose={() => setSceneExpert(null)}
            onUse={() => {
              const c = sceneExpert.card
              if (c.kind === 'remote' && installed.has(c.marketId)) useInstalledPlugin(c.marketId)
              else handleUse(c)
            }}
            onOpenDeveloper={sceneExpert.card.authorName ? () => openDeveloper(sceneExpert.card) : undefined}
          />
        )}

        {/* 专家团成员弹窗 · 点「查看成员」打开 */}
        {teamModal && (
          <TeamDetailDialog
            division={teamModal.division}
            members={teamModal.members}
            busy={bundledBusy}
            onInstall={(e) => void installBundled(e)}
            onClose={() => setTeamModal(null)}
          />
        )}

        {/* 技能详情页（v4）· 点卡片打开 */}
        {detailSkill && (
          <SkillDetailDialog
            skill={detailSkill}
            fromLocal={skillSrc === 'local'}
            installed={skillSrc === 'local' || !!detailSkill.preinstalled || installed.has(detailSkill.marketId)}
            sessionId={sessionId}
            installing={installing.has(detailSkill.marketId)}
            installPercent={progress[detailSkill.marketId]?.percent ?? 0}
            hasUpdate={hasUpdate(detailSkill.marketId, detailSkill.fingerprint, detailSkill.version)}
            onClose={() => setDetailSkill(null)}
            onSummon={() => void summonSkill(detailSkill)}
            onOpenDeveloper={() => openDeveloper(detailSkill)}
            onInstall={() => void installMarketPlugin(detailSkill.marketId)}
            onUpdate={() => void installMarketPlugin(detailSkill.marketId)}
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
            installed={installed}
            installing={installing}
            progress={progress}
            hasUpdate={hasUpdate}
            onInstall={(id) => void installMarketPlugin(id)}
            onClose={() => setDevAuthor(null)}
            onPickSkill={(x) => { setDevAuthor(null); setDetailSkill(x) }}
            onPickExpert={(e) => {
              // 本地专家 → 切会话主线程 agent；远端专家 → 安装插件（与专家卡「使用」同口径）
              if (e.remote === false) onUseLocalAgent?.(e.agentName || e.marketId)
              else void installMarketPlugin(e.marketId)
            }}
          />
        )}
    </>
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
        showToast(r.message || `内容没有变化，未创建新版本（当前 v${r.version}）`, 'info')
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
              : '目录里要有 SKILL.md（或 plugin.json / skills/）；可在 SKILL.md frontmatter 写 icon 指定技能图标（图片路径或 URL）'}
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
/**
 * 技能卡：结构与连接器卡共用 {@link MarketCard}（图标 44/40、标题 15/600、分类 chip、
 * 描述 13/1.45 两行截断、底栏动作）。作者行可点击 → 打开「该开发者的全部作品」。
 */
function SkillCard({
  skill, fromLocal = false, installedNow = false, installing = false, installPercent = 0,
  hasUpdate = false, onSummon, onInstall, onUpdate, onOpenDetail, onOpenDeveloper, onUninstall,
  menuOpen, onToggleMenu, onCloseMenu,
}: {
  skill: MarketSkill
  fromLocal?: boolean
  installedNow?: boolean
  installing?: boolean
  installPercent?: number
  hasUpdate?: boolean
  onSummon: () => void
  onInstall: (marketId: string) => void
  onUpdate: (marketId: string) => void
  onOpenDetail: () => void
  /** 点作者：按 author 聚合作品（复用开发者弹窗） */
  onOpenDeveloper?: () => void
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
  const publishedLabel = formatPublishedAt(skill.publishedAt)
  const catLabel = primaryCategoryLabel(skill.categories)

  return (
    <MarketCard
      extraClass="sk-card"
      accent={colorOf(nm)}
      onClick={onOpenDetail}
      icon={<SkillIcon icon={skill.icon} categories={skill.categories} />}
      title={nm}
      chip={catLabel}
      badges={fromLocal ? <span className="sm-badge local">本机</span> : null}
      description={skill.description}
      extra={(
        <>
          {author && (
            <div className="sk-author" onClick={(e) => { e.stopPropagation(); (onOpenDeveloper ?? onOpenDetail)() }} title={`查看 ${author} 的全部作品`}>
              <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>
              <b>{author}</b>
              {skill.authorVerified && (
                <span className="ver" title="已认证发布者">
                  <svg viewBox="0 0 24 24"><path d="M12 2l2.4 1.8 3-.2 1 2.8 2.6 1.5-1 2.9 1 2.9-2.6 1.5-1 2.8-3-.2L12 22l-2.4-1.8-3 .2-1-2.8L3 16.1l1-2.9-1-2.9 2.6-1.5 1-2.8 3 .2L12 2z" fill="currentColor" /><path d="M8.5 12l2.5 2.5 4.5-4.5" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </span>
              )}
            </div>
          )}
          <div className="sk-stats">
            {useLabel !== null && (
              <span className="sk-stat">
                <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>{useLabel}
              </span>
            )}
            {dlLabel !== null && (
              <span className="sk-stat">
                <svg viewBox="0 0 24 24"><path d="M12 4v11" /><path d="M8 11l4 4 4-4" /><path d="M5 20h14" /></svg>{dlLabel}
              </span>
            )}
            {publishedLabel && (
              <span className="sk-stat" title={skill.publishedAt ?? undefined}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8" /><path d="M12 7.5V12l3 2" /></svg>
                上架时间：{publishedLabel}
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
        </>
      )}
      footerRight={(
        <span className="sk-acts">
          <button className="sk-more" title="更多" onClick={(e) => { e.stopPropagation(); onToggleMenu() }} aria-expanded={menuOpen}>
            <svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
          </button>
          {installed ? (
            hasUpdate ? (
              <button className="sk-summon accent" disabled={installing} onClick={(e) => { e.stopPropagation(); onUpdate(skill.marketId) }}>
                {installing ? `${Math.round(installPercent)}%` : '更新'}
              </button>
            ) : (
              <button className="sk-summon" onClick={(e) => { e.stopPropagation(); onSummon() }}>召唤</button>
            )
          ) : (
            <button className="sk-summon accent" disabled={installing} onClick={(e) => { e.stopPropagation(); onInstall(skill.marketId) }}>
              {installing ? `${Math.round(installPercent)}%` : '＋ 安装'}
            </button>
          )}
        </span>
      )}
      overlay={menuOpen ? (
        <div className="sk-menu" onClick={(e) => e.stopPropagation()}>
          {installed ? (
            <>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onSummon() }}>
                <svg viewBox="0 0 24 24"><path d="M13 3L4 14h6l-1 7 9-11h-6l1-7z" /></svg>召唤到输入框
              </div>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onOpenDetail() }}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /></svg>查看详情
              </div>
              <div className="sk-menu-sep" />
              <div className="sk-menu-item danger" onClick={() => { onCloseMenu(); onUninstall(skill, fromLocal) }}>
                <svg viewBox="0 0 24 24"><path d="M4 7h16" /><path d="M9 7V5h6v2" /><path d="M6 7l1 13h10l1-13" /></svg>卸载
              </div>
            </>
          ) : (
            <>
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onOpenDetail() }}>
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="M12 3v3M12 18v3M3 12h3M18 12h3" /></svg>查看详情
              </div>
              <div className="sk-menu-sep" />
              <div className="sk-menu-item" onClick={() => { onCloseMenu(); onInstall(skill.marketId) }}>
                <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>安装
              </div>
            </>
          )}
        </div>
      ) : null}
    />
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
function SkillDetailDialog({
  skill, fromLocal, installed, sessionId, installing = false, installPercent = 0, hasUpdate = false,
  onClose, onSummon, onOpenDeveloper, onInstall, onUpdate,
}: {
  skill: MarketSkill
  fromLocal: boolean
  installed: boolean
  /** 本机技能正文按 name 查时带上（project 级技能依赖会话上下文） */
  sessionId?: string
  installing?: boolean
  installPercent?: number
  hasUpdate?: boolean
  onClose: () => void
  onSummon: () => void
  onOpenDeveloper: () => void
  onInstall?: () => void
  onUpdate?: () => void
}) {
  const nm = skill.displayName || skill.name || skill.marketId
  const useLabel = formatCountW(skill.useCount)
  const dlLabel = formatCountW(skill.downloadCount)
  const author = skill.authorName
  const publishedLabel = formatPublishedAt(skill.publishedAt)
  // 统计格数就地计算：没有的格子根本不渲染（用 auto-fit 核心由 CSS 处理）
  const statCount = 1 + (useLabel ? 1 : 0) + (dlLabel ? 1 : 0) + (author ? 1 : 0) + (publishedLabel ? 1 : 0)

  // ---- SKILL.md 正文（本机 /skills/{name}/content；市场 /market/skills/{name}/preview）----
  const [preview, setPreview] = useState<{ state: 'loading' | 'ok' | 'error'; markdown: string; error: string | null }>(
    { state: 'loading', markdown: '', error: null },
  )
  const [previewRetry, setPreviewRetry] = useState(0)
  useEffect(() => {
    let alive = true
    setPreview({ state: 'loading', markdown: '', error: null })
    const load = fromLocal
      // 本机正文按需加载 skills api（避免挂载期引入额外模块；市场预览走已导入的 marketApi）
      ? import('@/api/skills')
        .then(({ skillApi }) => skillApi.content(skill.marketId || skill.name || '', sessionId))
        .then((r) => r.content ?? r.markdown ?? '')
      : marketApi.skillPreview(skill.marketId, { version: skill.version ?? null }).then((r) => r.markdown ?? '')
    void load
      .then((md) => {
        if (!alive) return
        if (!md || !md.trim()) {
          setPreview({ state: 'error', markdown: '', error: '正文为空（后端未返回内容）' })
          return
        }
        setPreview({ state: 'ok', markdown: md, error: null })
      })
      .catch((e) => {
        if (!alive) return
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        const status = e instanceof ApiError && e.status ? `（HTTP ${e.status}）` : ''
        setPreview({ state: 'error', markdown: '', error: `正文加载失败${status}：${msg}` })
      })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skill.marketId, skill.version, fromLocal, sessionId, previewRetry])

  return (
    <div className="detail-mask" onClick={onClose}>
      <div className="detail-card" onClick={(e) => e.stopPropagation()}>
        <div className="dt-head">
          <SkillIcon icon={skill.icon} categories={skill.categories} className="dt-ico" />
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
              {installed ? (
                hasUpdate ? (
                  <button className="dt-try" disabled={installing} onClick={() => onUpdate?.()}>
                    {installing ? `更新中 ${Math.round(installPercent)}%` : '更新'}
                  </button>
                ) : (
                  <button className="dt-try" onClick={() => { onSummon(); onClose() }}>去试试</button>
                )
              ) : (
                <button className="dt-try" disabled={installing} onClick={() => onInstall?.()}>
                  {installing ? `安装中 ${Math.round(installPercent)}%` : '安装'}
                </button>
              )}
            </div>
          </div>
          <div className="dt-toggle">
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
          {publishedLabel && (
            <div className="dt-stat">
              <div className="dt-stat-v" style={{ fontSize: 13 }} title={skill.publishedAt ?? undefined}>{publishedLabel}</div>
              <div className="dt-stat-l">上架时间</div>
            </div>
          )}
        </div>

        {(skill.categories ?? []).filter((c) => c && c !== 'skill').length > 0 && (
          <div className="dt-tags-row">
            {(skill.categories ?? []).filter((c) => c && c !== 'skill').slice(0, 6)
              .map((c) => <span key={c} className="dt-tag">{CATEGORY_LABELS[c] ?? c}</span>)}
          </div>
        )}

        <div className="dt-section-h">
          <svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3" /><path d="M8 9h8M8 13h8M8 17h5" /></svg>
          SKILL.md
        </div>
        <div className="dt-preview" data-testid="skill-preview">
          {preview.state === 'loading' && <div className="dt-preview-state">正文加载中…</div>}
          {preview.state === 'error' && (
            <div className="dt-preview-error" data-testid="skill-preview-error">
              <div>{preview.error}</div>
              <button className="dt-try" onClick={() => setPreviewRetry((n) => n + 1)}>重试</button>
            </div>
          )}
          {preview.state === 'ok' && (
            <div
              className="markdown-prose dt-md"
              data-testid="skill-preview-md"
              dangerouslySetInnerHTML={{ __html: renderWikiMarkdown(preview.markdown) }}
            />
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
 * 开发者信息弹窗（v5 · 760px 两列表）。
 *
 * <p>从技能卡作者行 / 专家卡作者行 / 详情页「开发者」格点开。结构：
 * 56 头像 + 名字 18/600 + 认证 · 邮箱/作者 ID 小字可复制 · bio 两行截断 · 「N 技能 · N 专家」
 * · 技能/专家 tab（切换保留滚动）· 两列作品网格（全部复用 {@link MarketCard}）· 空态。
 *
 * <p><b>按 author.name 聚合</b>：列出该作者当前已加载的全部技能/专家。
 * 关闭只收起本弹窗 —— 不改父层筛选/子 tab，回到原列表原样。
 */
function DeveloperDialog({
  authorName, verified, email, avatarUrl, skills, experts, installed, installing, progress, hasUpdate,
  onClose, onPickSkill, onInstall, onPickExpert,
}: {
  authorName: string
  verified?: boolean
  email?: string | null
  avatarUrl?: string | null
  /** 该作者发布的技能（已按 authorName 过滤；后端无按作者查询时用已加载列表过滤） */
  skills: MarketSkill[]
  experts: MarketExpert[]
  installed: Set<string>
  installing: Set<string>
  progress: Record<string, InstallProgress>
  hasUpdate: (marketId: string, fp?: string | null, ver?: string | null) => boolean
  onClose: () => void
  onPickSkill: (s: MarketSkill) => void
  onInstall: (marketId: string) => void
  /** 专家卡点击：父层按 local → 使用该专家 / remote → 安装插件 分流 */
  onPickExpert?: (e: MarketExpert) => void
}) {
  // 默认停在有内容的那个 tab（技能优先，因为从技能详情进来的场景最多）
  const [dtTab, setDtTab] = useState<'expert' | 'skill'>(skills.length > 0 ? 'skill' : 'expert')
  // 切 tab 前后各自记住滚动位置（同一个滚动容器，不重挂载）
  const bodyRef = useRef<HTMLDivElement | null>(null)
  const scrollMemo = useRef<Record<'skill' | 'expert', number>>({ skill: 0, expert: 0 })
  const switchTab = (t: 'skill' | 'expert') => {
    if (t === dtTab) return
    if (bodyRef.current) scrollMemo.current[dtTab] = bodyRef.current.scrollTop
    setDtTab(t)
  }
  useLayoutEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = scrollMemo.current[dtTab]
  }, [dtTab])
  // Esc 只关本弹窗（捕获阶段拦下，避免 App 的 Esc 守卫把市场整页一起退掉）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  const copy = (text: string) => { void navigator.clipboard?.writeText(text) }
  const bio = `${authorName} 发布的技能与专家，共 ${skills.length + experts.length} 项。`

  return (
    <div className="detail-mask" onClick={onClose}>
      <div className="dev-modal" role="dialog" aria-label={`${authorName} 的开发者信息`} onClick={(e) => e.stopPropagation()}>
        <div className="dev-head">
          {avatarUrl
            ? <span className="dev-avatar"><img src={avatarUrl} alt={authorName} /></span>
            : <span className="dev-avatar" style={{ background: `linear-gradient(145deg, ${colorOf(authorName)}, ${colorOf(authorName + 'x')})` }}>
                {authorName.charAt(0).toUpperCase()}
              </span>}
          <div className="dev-head-main">
            <div className="dev-name">
              {authorName}
              {verified && (
                <span className="dev-verify" title="已认证发布者">
                  <svg viewBox="0 0 24 24"><circle cx="10" cy="8" r="3.4" /><path d="M4 19.5c0-3.2 2.7-5 6-5s6 1.8 6 5" /><path d="M17.5 9.5l1.6 1.6 3-3" /></svg>
                </span>
              )}
            </div>
            <div className="dev-meta">
              {email && (
                <button className="dev-copy" title="点击复制邮箱" onClick={() => copy(email)}>
                  <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M4 7l8 6 8-6" /></svg>
                  {email}
                </button>
              )}
              <button className="dev-copy" title="点击复制作者 ID" onClick={() => copy(authorName)}>
                <svg viewBox="0 0 24 24"><path d="M10 13a4 4 0 005.7 0l2.3-2.3a4 4 0 00-5.7-5.7L11 6.3" /><path d="M14 11a4 4 0 00-5.7 0L6 13.3a4 4 0 005.7 5.7l1.3-1.3" /></svg>
                作者 ID · {authorName}
              </button>
            </div>
            <div className="dev-bio" title={bio}>{bio}</div>
            <div className="dev-stats">
              <span>{skills.length} 技能</span>
              <span className="dot">·</span>
              <span>{experts.length} 专家</span>
            </div>
          </div>
          <button className="dev-close" onClick={onClose} title="关闭">✕</button>
        </div>

        <div className="dev-tabs">
          <button className={`dev-tab${dtTab === 'skill' ? ' active' : ''}`} onClick={() => switchTab('skill')}>
            技能 <span className="n">({skills.length})</span>
          </button>
          <button className={`dev-tab${dtTab === 'expert' ? ' active' : ''}`} onClick={() => switchTab('expert')}>
            专家 <span className="n">({experts.length})</span>
          </button>
        </div>

        <div className="dev-body" ref={bodyRef}>
          <div className="dev-grid">
          {dtTab === 'skill'
            ? (skills.length === 0 ? <div className="dev-empty">该开发者暂无技能</div> : skills.map((s) => {
                const nm = s.displayName || s.name || s.marketId
                const isInstalled = installed.has(s.marketId)
                const busy = installing.has(s.marketId)
                return (
                  <MarketCard
                    key={s.marketId}
                    accent={colorOf(nm)}
                    onClick={() => onPickSkill(s)}
                    icon={<SkillIcon icon={s.icon} categories={s.categories} />}
                    title={nm}
                    chip={primaryCategoryLabel(s.categories)}
                    description={s.description}
                    footerLeft={s.version ? <span className="sm-card-sub">v{s.version}</span> : null}
                    footerRight={isInstalled ? (
                      hasUpdate(s.marketId, s.fingerprint, s.version) ? (
                        <button className="sm-use-btn" disabled={busy} onClick={(e) => { e.stopPropagation(); onInstall(s.marketId) }}>
                          {busy ? `${progress[s.marketId]?.percent ?? 0}%` : '更新'}
                        </button>
                      ) : (
                        <span className="sm-installed-mark">已安装</span>
                      )
                    ) : (
                      <button className="sm-use-btn" disabled={busy} onClick={(e) => { e.stopPropagation(); onInstall(s.marketId) }}>
                        {busy ? `${progress[s.marketId]?.percent ?? 0}%` : '安装'}
                      </button>
                    )}
                  />
                )
              }))
            : (experts.length === 0 ? <div className="dev-empty">该开发者暂无专家</div> : experts.map((e) => {
                const nm = e.displayName || e.agentName || e.marketId
                const isInstalled = installed.has(e.marketId)
                const busy = installing.has(e.marketId)
                return (
                  <MarketCard
                    key={e.marketId}
                    accent={colorOf(nm)}
                    onClick={() => onPickExpert?.(e)}
                    icon={(
                      <span className="sm-avatar" style={{ background: colorOf(nm), position: 'relative' }}>
                        {nm.charAt(0).toUpperCase()}
                        {e.icon && (
                          <img
                            className="sm-avatar-img"
                            src={e.icon}
                            alt={nm}
                            loading="lazy"
                            style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
                            onError={(ev) => { ev.currentTarget.style.display = 'none' }}
                          />
                        )}
                      </span>
                    )}
                    title={nm}
                    chip={e.profession ?? null}
                    description={e.description}
                    footerLeft={e.version ? <span className="sm-card-sub">v{e.version}</span> : null}
                    footerRight={isInstalled ? (
                      hasUpdate(e.marketId, e.fingerprint, e.version) ? (
                        <button className="sm-use-btn" disabled={busy} onClick={(ev) => { ev.stopPropagation(); onInstall(e.marketId) }}>
                          {busy ? `${progress[e.marketId]?.percent ?? 0}%` : '更新'}
                        </button>
                      ) : (
                        <span className="sm-installed-mark">已安装</span>
                      )
                    ) : (
                      <button className="sm-use-btn" disabled={busy} onClick={(ev) => { ev.stopPropagation(); onInstall(e.marketId) }}>
                        {busy ? `${progress[e.marketId]?.percent ?? 0}%` : '安装'}
                      </button>
                    )}
                  />
                )
              }))}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * 「我安装的」管理弹窗（整页技能 tab 右上入口）。
 *
 * <p>与内容区「本机已装 / 市场」子 tab 的筛选是两件事：这里只做**管理**（搜索 / 召唤 / 卸载），
 * 不改变下方列表的筛选状态。数据来自本机命令目录（skills），卸载走真实接口（二次确认）。
 */
function InstalledSkillsDialog({ skills, onClose, onSummon, onUninstall }: {
  skills: MarketSkill[]
  onClose: () => void
  onSummon: (s: MarketSkill) => void
  onUninstall: (s: MarketSkill) => void
}) {
  const [q, setQ] = useState('')
  const k = q.trim().toLowerCase()
  const list = skills.filter((s) =>
    !k || [s.displayName, s.name, s.marketId, s.description].join(' ').toLowerCase().includes(k))
  return (
    <div className="detail-mask" onClick={onClose}>
      <div
        className="mk-installed-card"
        role="dialog"
        aria-modal="true"
        aria-label="我安装的"
        data-testid="installed-manager"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mk-installed-head">
          <span className="mk-installed-title">我安装的 <span className="cnt">{skills.length}</span></span>
          <button className="sm-icon-btn" onClick={onClose} title="关闭" aria-label="关闭">✕</button>
        </div>
        <div className="mk-installed-search">
          <input
            className="settings-input"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="搜索已安装技能…"
          />
        </div>
        <div className="mk-installed-list">
          {list.length === 0 ? (
            <div className="sm-empty small">{skills.length === 0 ? '还没有安装技能' : '没有匹配的已安装技能'}</div>
          ) : list.map((s) => {
            const nm = s.displayName || s.name || s.marketId
            return (
              <div className="mk-installed-row" key={s.marketId}>
                <SkillIcon icon={s.icon} categories={s.categories} className="sk-ico mk-installed-ico" />
                <span className="mk-installed-main">
                  <span className="mk-installed-name" title={nm}>{nm}</span>
                  <span className="mk-installed-desc" title={s.description ?? undefined}>{s.description || '暂无说明'}</span>
                </span>
                <button className="mc-btn" onClick={() => onSummon(s)}>召唤</button>
                <button className="mc-btn danger" onClick={() => onUninstall(s)}>卸载</button>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

/**
 * 专家详情弹窗（2026-11-23 设计稿）。
 *
 * 场景卡 → sceneKey 决定「专家帮你做」示例任务；专家卡 → 通用示例。
 * ⛔ 示例任务只是可复制的建议文本，不冒充真实使用记录；「使用案例」需要真实数据后再补。
 */
function ExpertDetailDialog({ card, sceneKey, installed, installing, installPercent, onClose, onUse, onOpenDeveloper }: {
  card: ExpertCard
  sceneKey: string | null
  installed: boolean
  installing: boolean
  installPercent: number
  onClose: () => void
  onUse: () => void
  onOpenDeveloper?: () => void
}) {
  const [copied, setCopied] = useState<string | null>(null)
  const prompts = sceneKey ? (SCENE_PROMPTS[sceneKey] ?? GENERIC_PROMPTS(card.name)) : GENERIC_PROMPTS(card.name)
  const role = card.subtitle || (card.kind === 'local' ? '本机专家' : '市场专家')
  const copy = (text: string) => {
    void Promise.resolve(navigator.clipboard?.writeText(text)).then(() => {
      setCopied(text)
      window.setTimeout(() => setCopied((v) => (v === text ? null : v)), 1600)
    }).catch(() => setCopied(null))
  }
  return (
    <div className="detail-mask ed-mask" onClick={onClose}>
      <div className="detail-card ed-card" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={`${card.name} 详情`}>
        <button className="ed-close" onClick={onClose} aria-label="关闭专家详情">✕</button>
        <div className="ed-head">
          <span className="ed-avatar" style={{ background: card.iconColor, position: 'relative' }}>
            {card.iconText}
            {card.iconUrl && (
              <img src={card.iconUrl} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.display = 'none' }} />
            )}
          </span>
          <div className="ed-title">
            <h2>{card.name}<span className="ed-bar" /><span className="ed-role">{role}</span></h2>
            <button
              className="ed-primary"
              disabled={installing || card.inUse}
              onClick={onUse}
            >
              {installing ? `安装中 ${Math.round(installPercent)}%` : card.inUse ? '使用中' : installed ? (card.kind === 'local' ? '使用此专家' : '使用') : '安装专家'}
            </button>
          </div>
        </div>
        {card.authorName && (
          <div className="sk-author ed-author" onClick={onOpenDeveloper} title={`查看 ${card.authorName} 的全部作品`}>
            <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.2" /><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6" /></svg>
            <b>{card.authorName}</b>
            {card.authorVerified && <span className="ver">已认证</span>}
          </div>
        )}
        <p className="ed-desc">{card.desc || '暂无说明'}</p>
        {card.tags.length > 0 && (
          <div className="sm-tags ed-tags">
            {card.tags.map((t) => <span key={t} className="sm-tag">{t}</span>)}
          </div>
        )}
        <h3 className="ed-sec">专家帮你做</h3>
        <div className="ed-prompts">
          {prompts.map((p) => (
            <button key={p} className="ed-prompt" onClick={() => copy(p)}>
              <span>“{p}”</span>
              <span className="ed-copy">{copied === p ? '已复制' : '复制'}</span>
            </button>
          ))}
        </div>
        <h3 className="ed-sec">使用说明</h3>
        <p className="ed-note">
          {card.kind === 'local'
            ? '来自本机专家目录；使用后该专家成为当前会话的主线程专家，从下一轮对话起生效。'
            : installed
              ? '已安装到本机；召唤只写入会话设置，不会自动发送消息。'
              : '来自建科数字插件市场；安装完成后再开始对话即可使用。'}
        </p>
      </div>
    </div>
  )
}

/** 专家团成员弹窗（点「查看成员」）：列出该 division 的全部专家，逐个安装。 */
function TeamDetailDialog({ division, members, busy, onInstall, onClose }: {
  division: string
  members: BundledExpert[]
  busy: Set<string>
  onInstall: (e: BundledExpert) => void
  onClose: () => void
}) {
  return (
    <div className="detail-mask ed-mask" onClick={onClose}>
      <div className="detail-card ed-card td-card" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={`${DIVISION_LABELS[division] ?? division}专家团成员`}>
        <button className="ed-close" onClick={onClose} aria-label="关闭成员列表">✕</button>
        <h2 className="td-title">{DIVISION_LABELS[division] ?? division}专家团</h2>
        <p className="td-sub">{members.length} 位内置专家 · 成员独立安装</p>
        <div className="td-list">
          {members.map((e) => (
            <div className="td-row" key={e.slug}>
              <span className="sm-avatar sm-avatar-sm" style={{ background: e.color || colorOf(e.name) }}>
                {e.emoji || e.name.charAt(0).toUpperCase()}
              </span>
              <span className="td-main">
                <b>{e.name}</b>
                <span>{e.description}</span>
              </span>
              {e.installed ? (
                <span className="sm-installed-mark">已安装</span>
              ) : (
                <button className={`sm-use-btn${busy.has(e.slug) ? ' installing' : ''}`} disabled={busy.has(e.slug)} onClick={() => onInstall(e)}>
                  {busy.has(e.slug) ? '安装中…' : '安装'}
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/**
 * 连接器详情弹窗（2026-11-23 设计稿）：双 Logo + 标题/说明 + 连接按钮。
 * ⛔ 不在弹窗里伪造授权成功：点「连接」只是进入既有授权抽屉，状态仍由后端校验。
 */
function ConnectorDetailDialog({ connector, onClose, onConnect }: {
  connector: ConnectorDto
  onClose: () => void
  onConnect: () => void
}) {
  const logo = connectorLogo(connector.icon, connector.key)
  const blocked = !!connector.hidden || connector.supportTier === 'unavailable'
  const label = connector.connected && connector.status !== 'reauth_required'
    ? '管理连接'
    : blocked ? '暂不可用' : '连接'
  return (
    <div className="detail-mask ed-mask" onClick={onClose}>
      <div className="detail-card cd-card" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={`连接 ${connector.name}`}>
        <button className="ed-close" onClick={onClose} aria-label="关闭连接器详情">✕</button>
        <div className="cd-hero">
          <span className="cd-logo nexus" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
              <path d="M5 19V5l14 14V5M5 12l7-7m0 14 7-7" />
            </svg>
          </span>
          <span className="cd-link" aria-hidden="true"><i /><b /><i /><b /><i /></span>
          <span className="cd-logo">
            {logo ? <img src={logo} alt="" loading="lazy" /> : <span className="cd-logo-fallback">{connector.name.charAt(0)}</span>}
          </span>
        </div>
        <h2 className="cd-title">连接 {connector.name}</h2>
        <p className="cd-desc">{connector.description}</p>
        {connector.authHint && <p className="cd-hint">正式接入时：{connector.authHint}</p>}
        <div className="cd-action">
          <button className="cd-primary" disabled={blocked} onClick={onConnect}>{label}</button>
        </div>
        <p className="cd-note">
          {blocked
            ? (connector.hidden ? '内部 / 企业连接器，本机不开放授权。' : (connector.unavailableReason || '当前批次暂不支持该授权方式。'))
            : '授权与连接状态以后端校验为准；本弹窗不直接完成授权。'}
        </p>
      </div>
    </div>
  )
}
