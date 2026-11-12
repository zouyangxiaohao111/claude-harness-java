import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase, KnowledgeListQuery } from '@/api/knowledge'
import { WikiPanel } from './WikiPanel'
import { GraphPanel } from './GraphPanel'
import { KbQaPanel } from './KbQaPanel'
import { KnowledgeDocPanel } from './KnowledgeDocPanel'
import { KbSettingsModal } from './KbSettingsModal'
import { KbFaqManager } from './KbFaqManager'
import { KbSharedSpace } from './KbSharedSpace'
import { KbCreateModal } from './KbCreateModal'
import type { KbCreateSubmit } from './KbCreateModal'
import { KbGalleryPanel } from './KbGalleryPanel'
import { KbSceneSettingsModal } from './KbSceneSettingsModal'
import { KbPersonalInfoModal } from './KbPersonalInfoModal'

interface KnowledgeBasePageProps {
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 返回对话页。
   *
   * <p>⭐ 此前只有「点面包屑」和「按 Esc」两条路，都不显眼 ——
   * 用户点进知识库后不知道该怎么回去（2026-11-06 用户反馈）。
   * 现在页头给一个明确的按钮。
   */
  onBackToChat?: () => void
  /**
   * 关闭整个知识库场景（浮层的 ✕）。
   *
   * <p>与 {@link onBackToChat} 的区别：这个只关浮层，不涉及「回到哪」；
   * 浮层形态下两者指向同一个动作，但语义分开写便于以后换形态。
   */
  onRequestClose?: () => void
  /**
   * 场景内要显示哪个面。
   *
   * <p>`'knowledge'` = 知识库列表/详情；`'shared'` = 共享空间（独立一页）。
   * 由 App 侧栏决定 —— 点「共享空间」时传 'shared'。
   */
  scene?: 'knowledge' | 'shared'
}

/**
 * 知识库场景的左侧栏 · 逐字对照设计稿 `方案A-主区换页.html` 的 `<aside class="aside">`。
 *
 * <h2>⚠️ 作用域</h2>
 * 这份侧栏**只在知识库弹窗里**出现（`kb-scene-panel` 内），
 * ⛔ 不是主窗口的会话栏 —— 主窗口左栏（SessionList）保持原样不动。
 *
 * <h2>映射到本仓真实落点</h2>
 * 每一项都有去处，⛔ 不做点了没反应的空壳：
 * <pre>
 * 新对话  → 新建会话并关闭弹窗
 * 知识库  → 当前场景（永远 active）
 * 产物    → 右栏产物抽屉
 * 智能体  → 专家面板
 * 自动化  → 定时任务页
 * 工具箱  → 技能市场
 * 共享空间 → 知识库列表（共享库在「分享」里）
 * </pre>
 */
export interface KbSceneNavProps {
  /** 当前选中项（场景内只有「知识库」恒选中；其余项点了会离开场景）*/
  activeKey?: string
  onNewChat?: () => void
  onOpenArtifacts?: () => void
  onOpenAgents?: () => void
  onOpenSchedules?: () => void
  onOpenToolbox?: () => void
  onOpenSharedSpace?: () => void
  /** 底部「知识库列表」入口（场景内返回列表页）*/
  onBackToList?: () => void
}

/**
 * 7 个菜单项 —— 图标用**字符**，与设计稿逐字一致。
 *
 * <p>⚠️ 设计稿这里是 `<div class="menu_icon">✎</div>` 这种纯字符，
 * ⛔ 不是 SVG。字符的好处：字号/颜色完全跟随 .menu_icon 的 13px + rgba(0,0,0,.6)，
 * 而 SVG 有自己的 viewBox，尺寸和视觉重量都会偏。
 */
const NAV_ITEMS: Array<{
  key: string
  label: string
  glyph: string
  handlerKey: keyof Omit<KbSceneNavProps, 'activeKey' | 'onBackToList'>
}> = [
  { key: 'chat',       label: '新对话',   glyph: '✎', handlerKey: 'onNewChat' },
  { key: 'knowledge',  label: '知识库',   glyph: '▤', handlerKey: 'onNewChat' },
  { key: 'artifacts',  label: '产物',     glyph: '◫', handlerKey: 'onOpenArtifacts' },
  { key: 'agents',     label: '智能体',   glyph: '◉', handlerKey: 'onOpenAgents' },
  { key: 'automation', label: '自动化',   glyph: '⚙', handlerKey: 'onOpenSchedules' },
  { key: 'toolbox',    label: '工具箱',   glyph: '✚', handlerKey: 'onOpenToolbox' },
  { key: 'shared',     label: '共享空间', glyph: '◍', handlerKey: 'onOpenSharedSpace' },
]

/**
 * 左下角用户区 · 行内样式。
 *
 * <p>取值对齐 `globals.css:17133-17197` 的 `.kb-aside` 家族，让用户区与导航项同视觉重量。
 */
const USER_AREA: CSSProperties = {
  position: 'relative',
  flexShrink: 0,
  borderTop: '1px solid #e7e7e7',
  marginTop: 6,
  paddingTop: 6,
}
const USER_BTN: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  width: '100%',
  height: 44,
  padding: '6px 8px 6px 10px',
  border: 0,
  borderRadius: 4,
  background: 'transparent',
  font: 'inherit',
  textAlign: 'left',
  cursor: 'pointer',
  boxSizing: 'border-box',
  transition: 'background-color 0.2s ease',
}
const AVATAR: CSSProperties = {
  width: 26,
  height: 26,
  borderRadius: '50%',
  background: '#07c05f',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 13,
  fontWeight: 600,
  flex: '0 0 26px',
}
const USER_META: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  flex: 1,
  overflow: 'hidden',
}
const USER_NAME: CSSProperties = {
  fontSize: 14,
  fontWeight: 500,
  lineHeight: '18px',
  color: 'rgba(0, 0, 0, 0.9)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}
const USER_EMAIL: CSSProperties = {
  fontSize: 12,
  lineHeight: '16px',
  color: 'rgba(0, 0, 0, 0.4)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

/** 上弹抽屉：贴着用户区上边缘弹出（`.kb-aside` 有 `overflow:hidden`，故宽度取 `left/right:0` 自适应）。 */
const MENU_BOX: CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  left: 0,
  right: 0,
  marginBottom: 4,
  background: '#fff',
  border: '1px solid #e7e7e7',
  borderRadius: 8,
  boxShadow: '0 4px 16px rgba(0, 0, 0, 0.08)',
  padding: 4,
  zIndex: 20,
}
/** 菜单项：字号/圆角/内边距对齐 `.kb-aside-item` 的紧凑档。 */
const MENU_ITEM: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  height: 34,
  padding: '6px 8px 6px 10px',
  borderRadius: 4,
  fontSize: 14,
  fontWeight: 500,
  color: 'rgba(0, 0, 0, 0.9)',
  cursor: 'pointer',
  transition: 'background-color 0.2s ease',
  boxSizing: 'border-box',
}
/** 图标列宽与 `.kb-aside-icon` 的 18px 对齐，保证两项文字左缘与上方导航项齐平。 */
const MENU_ICON: CSSProperties = {
  width: 18,
  flex: '0 0 18px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: 'rgba(0, 0, 0, 0.6)',
  fontSize: 14,
}

export function KbSceneNav(props: KbSceneNavProps) {
  const { activeKey = 'knowledge' } = props
  /** 上弹抽屉是否展开 */
  const [menuOpen, setMenuOpen] = useState(false)
  /** 场景级「全部设置」弹窗 */
  const [sceneSettingsOpen, setSceneSettingsOpen] = useState(false)
  /** 「个人信息」弹窗 */
  const [personalInfoOpen, setPersonalInfoOpen] = useState(false)
  /** 悬停项 key（行内样式做不了 `:hover`，用 state 模拟，取值同 `.kb-aside-item:hover`） */
  const [hoverKey, setHoverKey] = useState<string | null>(null)
  /** 用户区容器：点外部关闭抽屉时用来判断点击是否落在里面 */
  const userAreaRef = useRef<HTMLDivElement>(null)

  // 点抽屉外部关闭（抽屉本身在 userAreaRef 内 ⇒ 点按钮/菜单项不会先被关掉）
  useEffect(() => {
    if (!menuOpen) return
    const onDocClick = (e: MouseEvent) => {
      if (userAreaRef.current && !userAreaRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [menuOpen])

  /**
   * Esc 逐层退出：抽屉 → 全部设置 → 个人信息，就近的那层先关。
   *
   * <p>⚠️ 必须在<b>捕获阶段</b>处理并 `stopPropagation`：App.tsx:2817 有一条
   * `useEscapeKey(showKnowledge, () => setShowKnowledge(false))`，挂在 window 冒泡阶段；
   * 两个子弹窗内部也各自挂了冒泡阶段的 Esc。若不在捕获阶段截断，一次 Esc 会
   * **同时**关掉弹窗和整个知识库场景（实测：`.kb-scene-panel` 计数 1→0）。
   * 捕获阶段在 window 上最先执行，截断后下层监听器都收不到，于是只退一层。
   */
  useEffect(() => {
    if (!menuOpen && !sceneSettingsOpen && !personalInfoOpen) return
    const onEscCapture = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      if (personalInfoOpen) setPersonalInfoOpen(false)
      else if (sceneSettingsOpen) setSceneSettingsOpen(false)
      else setMenuOpen(false)
    }
    window.addEventListener('keydown', onEscCapture, true)
    return () => window.removeEventListener('keydown', onEscCapture, true)
  }, [menuOpen, sceneSettingsOpen, personalInfoOpen])

  return (
    <>
      <aside className="kb-aside">
        <div className="kb-aside-top">
          {NAV_ITEMS.map((item) => {
            const handler = props[item.handlerKey] as (() => void) | undefined
            const onClick = item.key === 'knowledge'
              ? (props.onBackToList ?? handler)
              : handler
            return (
              <button
                key={item.key}
                type="button"
                className={`kb-aside-item${activeKey === item.key ? ' is-active' : ''}`}
                onClick={onClick}
                title={item.label}
              >
                <span className="kb-aside-icon">{item.glyph}</span>
                <span className="kb-aside-label">{item.label}</span>
              </button>
            )
          })}
        </div>

        {/* 左下角用户区（`.kb-aside-top` 的 flex:1 已把它顶到底部，⛔ 不要再加 marginTop:auto） */}
        <div className="kb-aside-user" ref={userAreaRef} style={USER_AREA}>
          {menuOpen && (
            <div className="kb-user-menu" role="menu" style={MENU_BOX}>
              <div
                className="kb-user-menu-item"
                role="menuitem"
                style={{ ...MENU_ITEM, background: hoverKey === 'personal' ? '#f3f3f3' : 'transparent' }}
                onMouseEnter={() => setHoverKey('personal')}
                onMouseLeave={() => setHoverKey(null)}
                onClick={() => { setMenuOpen(false); setPersonalInfoOpen(true) }}
              >
                <span style={MENU_ICON}>◎</span>
                <span>个人设置</span>
              </div>
              <div
                className="kb-user-menu-item"
                role="menuitem"
                style={{ ...MENU_ITEM, background: hoverKey === 'scene-settings' ? '#f3f3f3' : 'transparent' }}
                onMouseEnter={() => setHoverKey('scene-settings')}
                onMouseLeave={() => setHoverKey(null)}
                onClick={() => { setMenuOpen(false); setSceneSettingsOpen(true) }}
              >
                <span style={MENU_ICON}>⚙</span>
                <span>全部设置</span>
              </div>
            </div>
          )}

          <button
            type="button"
            className="kb-aside-user-btn"
            style={{ ...USER_BTN, background: hoverKey === 'user' ? '#f3f3f3' : 'transparent' }}
            onClick={() => setMenuOpen((v) => !v)}
            onMouseEnter={() => setHoverKey('user')}
            onMouseLeave={() => setHoverKey(null)}
            aria-expanded={menuOpen}
            aria-haspopup="menu"
            title="账号菜单"
          >
            <span style={AVATAR}>A</span>
            <span style={USER_META}>
              <span className="kb-aside-user-name" style={USER_NAME}>admin</span>
              <span className="kb-aside-user-email" style={USER_EMAIL}>admin@weknora.local</span>
            </span>
          </button>
        </div>
      </aside>

      {/* 两个弹窗必须挂在 `<aside>` 外：`.kb-aside` 有 `overflow:hidden`，放里面会被裁 */}
      <KbSceneSettingsModal open={sceneSettingsOpen} onClose={() => setSceneSettingsOpen(false)} />
      <KbPersonalInfoModal open={personalInfoOpen} onClose={() => setPersonalInfoOpen(false)} />
    </>
  )
}

const KB_TYPE_META: Record<string, { label: string; mark: string }> = {
  document: { label: '文档库', mark: '📄' },
  faq: { label: 'FAQ', mark: '💬' },
  wiki: { label: 'Wiki', mark: '📚' },
}

/**
 * 知识库页面 · 复刻 WeKnora 的两级结构（源侧
 * `views/knowledge/KnowledgeBaseList.vue` + `KnowledgeBase.vue`）。
 *
 * <h2>⭐ 为什么是两级而不是单页</h2>
 * 源侧是「知识库列表（卡片网格）→ 进入某个库看文档」，面包屑可回退。
 * 我第一版把库选择和文档列表塞在同一页，用户看不出「当前在哪个库」，
 * 也无法一次浏览多个库。此版本按原型拆成两级：
 * <pre>
 *   view='list'    →  卡片网格（对应源侧 .kb-card-wrap / .kb-card）
 *   点卡片          →  view='detail'（面包屑「知识库 › 库名」+ 文档表）
 * </pre>
 *
 * <h2>视觉口径（照抄源侧 `components/css/resource-card.less`）</h2>
 * <ul>
 *   <li>网格：`repeat(auto-fill, minmax(min(100%, 260px), 1fr))`，gap 10px</li>
 *   <li>卡片：min-height 132px、padding 12px、1px 边框、hover 双层阴影</li>
 *   <li>标题：600 / 20px 行高 / 单行省略</li>
 *   <li>描述：2 行截断 / 18px 行高</li>
 *   <li>底部：1px 分隔线 + padding-top 6px + 特性徽标</li>
 * </ul>
 *
 * <h2>解析是异步的 ⇒ 详情页只在有非终态条目时轮询</h2>
 */
export function KnowledgeBasePage({ showToast, onBackToChat, onRequestClose, scene = 'knowledge' }: KnowledgeBasePageProps) {
  // 浮层模式下「返回对话」= 关闭浮层
  const closeScene = onRequestClose ?? onBackToChat
  // ── 视图状态 ──────────────────────────────────────────────────
  const [view, setView] = useState<'list' | 'detail'>('list')

  /**
   * 详情页 Tab：文档 / Wiki。
   *
   * <p>⭐ 按源侧形态：Wiki 是<b>知识库详情里的一个子页</b>，不是独立入口。
   * <p>⚠️ 切库时必须重置回 `docs` —— 否则从「A 库的 Wiki」进入「B 库」，
   * 会停在 Wiki tab 看 B 库（用户预期是看新库的文档列表）。
   */
  const [detailTab, setDetailTab] = useState<
    'docs' | 'wiki' | 'graph' | 'entity-graph' | 'gallery' | 'qa'
  >('docs')
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [activeBaseId, setActiveBaseId] = useState<string | null>(null)
  /**
   * 每个库的条目数（卡片底部「N 个文档 / N 组问句」用，设计稿 01 页卡片底部就是这个）。
   *
   * <p>⚠️ 后端 `GET /knowledge-bases` **不返回** count 字段（只回 18 个字段，见
   * `api/knowledge.ts:KnowledgeBase`），所以只能对每个库单独拉一次 `listKnowledge({page_size:1})`
   * 读它的 `total`。库数量是个位数，N 次并行请求可接受；失败时该库不显示计数（⛔ 不显示 0 冒充）。
   */
  const [itemCounts, setItemCounts] = useState<Record<string, number>>({})
  const [total, setTotal] = useState(0)
  /**
   * 当前库的文档清单（只给「关系图谱」tab 的「选择要抽取的文档」下拉用）。
   *
   * <p>⚠️ 复用 {@code loadItems} 那一次请求的返回值，⛔ 不另开一个请求 ——
   * 两个定时器/两套列表会互相打架（本文件同款注释见 {@code loadItems} 上方）。
   */
  const [docOptions, setDocOptions] = useState<Array<{ id: string; title: string }>>([])
  const [loadingBases, setLoadingBases] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)
  /** 设置弹窗（详情页右上角齿轮）*/
  const [settingsOpen, setSettingsOpen] = useState(false)
  /** 信息弹层（详情页标题行右侧 ℹ 圆钮，对齐源码 KBInfoPopover） */
  const [infoOpen, setInfoOpen] = useState(false)

  /**
   * 当前已挂载解析引擎**不支持**的扩展名 —— 对齐源码
   * `KnowledgeBase.vue:2508` 的 `unsupportedFileTypes` 提示条。
   *
   * <p>数据源：`GET /system/parser-engines`（本仓已有，返回 Name/FileTypes/Available）。
   * 口径 = 「所有 Available=true 引擎声明支持的类型」的并集，再与用户最常传的几类求差。
   */
  const [engineTypes, setEngineTypes] = useState<{ supported: Set<string>; checked: boolean }>({
    supported: new Set(),
    checked: false,
  })
  const unsupportedFileTypes = useMemo(() => {
    if (!engineTypes.checked) return []
    /** 用户最常传、且缺引擎时后果最明显的几类（与源码提示条同一组） */
    const WATCHED = ['bmp', 'gif', 'jpeg', 'jpg', 'pdf', 'png', 'tiff', 'webp']
    return WATCHED.filter((ext) => !engineTypes.supported.has(ext))
  }, [engineTypes])
  /**
   * 分类标签（设计稿 .toolbar 里的胶囊组：全部 / 收藏 / 最近 / 本空间 / 我创建的）。
   *
   * <p>⚠️ 本仓单机单用户，没有「空间/成员」概念 ⇒ '本空间' 与 '我创建的' 是同一批，
   * 刻意只留「全部 / 收藏 / 最近」三项，⛔ 不做点了没反应的空分类。
   */
  const [category, setCategory] = useState<'all' | 'fav' | 'recent'>('all')
  const [sortBy, setSortBy] = useState<'updated' | 'created' | 'name'>('updated')
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const activeBase = useMemo(
    () => bases.find((b) => b.id === activeBaseId) ?? null,
    [bases, activeBaseId]
  )

  /**

  /**
   * 列表页展示的库：按「分类 + 搜索 + 排序」派生。
   *
   * <p>⚠️ '收藏' 读 `is_pinned`（后端的置顶位）；'最近' 与 '全部' 同集合，
   * 差别只在排序固定按 updatedAt —— 本仓没有访问记录表，⛔ 不假装有「最近访问」。
   */
  const filteredBases = useMemo(() => {
    let list = bases
    if (category === 'fav') list = list.filter((b) => b.is_pinned)
    const kw = keyword.trim().toLowerCase()
    if (kw) list = list.filter((b) => b.name.toLowerCase().includes(kw))
    const sorted = [...list]
    if (sortBy === 'name') sorted.sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
    else if (sortBy === 'created') sorted.sort((a, b) => String(b.created_at ?? '').localeCompare(String(a.created_at ?? '')))
    else sorted.sort((a, b) => String(b.updated_at ?? '').localeCompare(String(a.updated_at ?? '')))
    return sorted
  }, [bases, category, keyword, sortBy])

  const toMessage = (e: unknown): string =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  // ── 知识库列表 ────────────────────────────────────────────────
  const loadBases = useCallback(async () => {
    setLoadingBases(true)
    setError(null)
    try {
      const list = await knowledgeApi.listBases()
      setBases(list)
      setActiveBaseId((prev) => (prev && list.some((b) => b.id === prev) ? prev : (list[0]?.id ?? null)))
      // 并行拉每个库的条目总数（卡片底部「N 个文档 / N 组问句」，设计稿 01 页要这个）。
      // 失败静默：拿不到就不显示计数，⛔ 不用 0 冒充。
      void Promise.all(
        list.map(async (b) => {
          try {
            const p = await knowledgeApi.listKnowledge(b.id, { page: 1, page_size: 1 })
            return [b.id, p.total] as const
          } catch {
            return null
          }
        }),
      ).then((pairs) => {
        const next: Record<string, number> = {}
        for (const p of pairs) if (p) next[p[0]] = p[1]
        setItemCounts(next)
      })
    } catch (e) {
      setError(toMessage(e))
    } finally {
      setLoadingBases(false)
    }
  }, [])

  useEffect(() => {
    void loadBases()
  }, [loadBases])

  // 点击卡片外部关闭「更多」菜单
  useEffect(() => {
    if (!openMenuId) return
    const onDown = () => setOpenMenuId(null)
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [openMenuId])

  // ── 文档列表（只供页头的「N 个文档」计数与图谱标题下拉）──────────
  //   ⚠️ 文档区自己的列表/筛选/轮询全在 KnowledgeDocPanel 内（它自己管状态），
  //   这里只取一份用于页头展示。⛔ 不要在这里也做一套轮询 —— 两个定时器会互相打架。
  const loadItems = useCallback(async () => {
    if (!activeBaseId) {
      setTotal(0)
      setDocOptions([])
      return
    }
    try {
      const query: KnowledgeListQuery = { page: 1, page_size: 100 }
      if (keyword.trim()) query.keyword = keyword.trim()
      if (statusFilter) query.parse_status = statusFilter
      const page = await knowledgeApi.listKnowledge(activeBaseId, query)
      setTotal(page.total)
      // 顺带喂「关系图谱」tab 的重建下拉（同一次请求，零额外开销）
      setDocOptions((page.data || []).map((k) => ({ id: k.id, title: k.title || k.file_name || k.id })))
      setError(null)
    } catch (e) {
      setError(toMessage(e))
    }
  }, [activeBaseId, keyword, statusFilter])

  useEffect(() => {
    if (view !== 'detail') return
    void loadItems()
  }, [view, loadItems])

  /**
   * 拉一次解析引擎清单，算出「当前没有任何可用引擎能解析的扩展名」。
   *
   * <p>对齐源码 `KnowledgeBase.vue:2508` 的提示条。只拉一次（引擎能力不随选库变化），
   * 失败就静默降级（不显示提示条）—— 提示条缺席比报错阻塞页面好。
   */
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        // ⚠️ `listParserEngines` 已在 api 层解包，直接是数组（不是 { success, data }）
        const engines = await knowledgeApi.listParserEngines()
        if (!alive) return
        const supported = new Set<string>()
        for (const e of engines ?? []) {
          if (!e.Available) continue
          for (const t of e.FileTypes ?? []) {
            supported.add(String(t).replace(/^\./, '').toLowerCase())
          }
        }
        setEngineTypes({ supported, checked: true })
        console.info(
          `[知识库] 解析引擎清单 engines=${(engines ?? []).length} 可用类型=${supported.size}`
        )
      } catch (e) {
        // 拿不到清单 = 无法判断，不显示提示条（⛔ 不要谎报「全部不支持」）
        console.warn(`[知识库] 解析引擎清单加载失败，跳过不支持类型提示：${String(e)}`)
        if (alive) setEngineTypes({ supported: new Set(), checked: false })
      }
    })()
    return () => { alive = false }
  }, [])

  // ── 操作 ──────────────────────────────────────────────────────
  const openBase = (base: KnowledgeBase) => {
    setActiveBaseId(base.id)
    setKeyword('')
    setStatusFilter('')
    // ⭐ 切库时重置 tab：否则会停在「上一个库的 Wiki」，用户预期是看新库的文档
    setDetailTab('docs')
    setView('detail')
  }

  /**
   * 创建知识库。
   *
   * <p>⭐ 参数来自 {@link KbCreateModal}。后端 `CreateKnowledgeBaseRequest` 接收
   * `name` / `description` / `type` / `embedding_model_id` / `summary_model_id` /
   * **`indexing_strategy`**（本批补的，源侧创建时同一个请求就带它）。
   *
   * <p>分块大小与重叠仍是「上传时的解析参数」，不在创建请求里 ⇒ 记进 localStorage
   * 供上传面板读取（⛔ 不假装写进了后端）。
   */
  const handleCreateBase = async (payload: KbCreateSubmit) => {
    try {
      await knowledgeApi.createBase({
        name: payload.name,
        description: payload.description || undefined,
        type: payload.type,
        // ⭐ RAG / Wiki / 图谱开关 —— Wiki 的启用**只**由这个字段承载
        //    （源码 KnowledgeBaseEditorModal.vue:1675）。null ⇒ 不写（FAQ 库走这条）。
        indexing_strategy: payload.indexingStrategy ?? undefined,
        // ⭐ 图谱判据的第二半（`extract_config.enabled`）。弹窗已序列化成字符串
        //    ⇒ 这里原样透传（⛔ 不再 stringify）。不转发的话 selection 白勾。
        extract_config: payload.extract_config ?? undefined,
        // ⭐ 图片处理三项（V286，顶层列）。创建弹窗暂无控件 ⇒ 显式写后端默认值
        //    （true / true / default），⛔ 不依赖「后端缺省」隐式生效。
        //    ⚠️ 与 `image_processing_config.image_pipeline`（旧键，`""`/`"attrs"`，
        //    后端零消费者）同名不同义，别把旧键透传到这里。
        image_caption_enabled: payload.imageCaptionEnabled ?? true,
        image_ocr_enabled: payload.imageOcrEnabled ?? true,
        image_pipeline: payload.imagePipeline ?? 'default',
      })
      try {
        window.localStorage.setItem(
          `kb-chunk-pref-${payload.name}`,
          JSON.stringify({ size: payload.chunkSize, overlap: payload.chunkOverlap })
        )
      } catch {
        /* localStorage 不可用时忽略：分块参数只是偏好，不影响创建 */
      }
      setCreateOpen(false)
      await loadBases()
      showToast('知识库已创建', 'success')
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleDeleteBase = async (base: KnowledgeBase) => {
    if (!window.confirm(`确定删除知识库「${base.name}」？其下的文档也会一并不可见。`)) return
    try {
      await knowledgeApi.deleteBase(base.id)
      showToast('知识库已删除', 'success')
      setOpenMenuId(null)
      if (activeBaseId === base.id) {
        setView('list')
        setActiveBaseId(null)
      }
      await loadBases()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleUpload = async (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0 || !activeBaseId) return
    // ⭐ 与文档面板头的 KbUploadSource 共用同一个并发池（全局槽位恒 3）
    const r = await knowledgeApi.uploadMany(
      activeBaseId,
      Array.from(fileList).map((file) => ({ file }))
    )
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (r.ok > 0) showToast(`已上传 ${r.ok} 个文件，正在解析`, 'success')
    if (r.failures.length > 0) {
      showToast(r.failures.map((f) => `${f.name}: ${f.message}`).join('；'), 'info')
    }
    await loadItems()
  }

  // ══════════════════════════════════════════════════════════════
  // 共享空间（场景内的独立一页，由侧栏「共享空间」进入）
  // ══════════════════════════════════════════════════════════════
  if (scene === 'shared') {
    return (
      <KbSharedSpace bases={bases} showToast={showToast} />
    )
  }

  /**
   * 新建/编辑知识库弹窗。
   *
   * <p>⚠️ 必须定义在**两个 return 之前**并各挂一次 ——
   * 列表页也能建库（「＋ 新建知识库」按钮就在列表页），
   * 只挂详情页会导致点按钮没反应（本轮实测踩到）。
   */
  const createModalEl = createOpen ? (
    <KbCreateModal
      showToast={showToast}
      onClose={() => setCreateOpen(false)}
      onCreate={handleCreateBase}
    />
  ) : null

  // ══════════════════════════════════════════════════════════════
  // 一级：知识库卡片网格（源侧 KnowledgeBaseList）
  // ══════════════════════════════════════════════════════════════
  if (view === 'list') {
    return (
      <>
      <div className="kb-list-container">
        <div className="kb-list-content">
          {/* 页头：源站 KnowledgeBaseList.vue:4-25 —— header > header-title > title-row > (h2 + header-actions) */}
          <div className="header">
            <div className="header-title">
              <div className="title-row">
                {/* 标题字号由源站 CSS 提供：`.header h2 { font-size: var(--app-text-4xl) }`，
                    该 token 的定义在源站 `assets/theme/theme.css:273`（24px），
                    已随 kb-scope.css 一并落库 ⇒ 这里不需要内联兜底。 */}
                <h2>
                  {/* 源站 ResourceIcon.vue 是 <span class="resource-icon" style="mask-image:url(svg)">，
                      由 background-color 填充遮罩；本仓没有该组件，改用真 <svg> 自绘 ⇒
                      必须把 `background-color:currentColor` 清掉，否则 24×24 会变成实心色块。
                      （设计稿 mock-chrome.css:343 对同一元素就是 `background:none !important`） */}
                  <svg className="resource-icon" width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" style={{ backgroundColor: 'transparent' }}>
                    <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v8A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5z" />
                  </svg>
                  知识库
                </h2>
                {/* ⭐ 源站 .header-actions 与 h2 平级（同在 .title-row 内） */}
                <div className="header-actions">
                  {/* 排序：源站 ResourceSortControl.vue:20 —— .resource-sort-trigger */}
                  <button
                    type="button"
                    className="resource-sort-trigger"
                    onClick={() => setSortBy(
                      sortBy === 'updated' ? 'created' : sortBy === 'created' ? 'name' : 'updated'
                    )}
                    title="切换排序"
                  >
                    <span className="resource-sort-trigger__label">
                      排序 · {sortBy === 'updated' ? '最近更新' : sortBy === 'created' ? '创建时间' : '名称'}
                    </span>
                  </button>
                  {/* ⛔ 设计稿 01 页的 .header-actions 只有「排序 + 新建」两个按钮（无关闭）。
                      实测设计稿 headerActions = ["排序 · 最近更新", "＋ 新建知识库"]。
                      「退出」由 Esc / 点遮罩承担（App.tsx:2817 useEscapeKey + 3244 遮罩 onClick）。 */}
                  {/* 真机实测：源侧是白底 text 按钮（13px / 32px 高 / 圆角 6px），
                      ⛔ 不是绿底主按钮 —— 绿底是「创建知识库」弹窗里的确认按钮。 */}
                  <button
                    type="button"
                    className="t-button t-button--variant-text t-button--theme-default t-button--shape-rectangle t-size-s header-action-btn"
                    onClick={() => setCreateOpen(true)}
                  >
                    <span className="t-button__text">＋ 新建知识库</span>
                  </button>
                </div>
              </div>
              <p className="header-subtitle">管理和组织您的知识库，支持文档型和问答型知识库</p>
            </div>
          </div>

          {/* 工具条：源站 ResourceListToolbar.vue —— .resource-toolbar > .scope-filters > .category-tabs */}
          <div className="resource-toolbar">
            <div className="scope-filters">
              <div className="category-tabs" role="group" aria-label="分类">
                {([
                  ['all', '全部', bases.length],
                  ['fav', '收藏', bases.filter((b) => b.is_pinned).length],
                  ['recent', '最近', bases.length],
                  // ⚠️ 设计稿 01 页有 5 个分类（全部/收藏/最近/本空间/我创建的）。
                  // 本仓 `KnowledgeBase` 契约**没有** creator / space 字段
                  // （见 api/knowledge.ts:只回 18 个字段，无 owner/space）
                  // ⇒ 这两项无法真实过滤。在这里如实呈现为「不可用」而不是造一个假列表。
                  ['space', '本空间', 0],
                  ['mine', '我创建的', 0],
                ] as const).map(([key, label, count]) => {
                  const unavailable = key === 'space' || key === 'mine'
                  return (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={category === key}
                    aria-disabled={unavailable || undefined}
                    title={unavailable ? '本仓知识库契约无 owner/space 字段，无法按此过滤' : undefined}
                    onClick={() => {
                      if (unavailable) {
                        showToast('本仓知识库无「所属空间/创建者」字段，暂不支持该分类', 'info')
                        return
                      }
                      setCategory(key)
                    }}
                  >
                    {label}<span className="scope-count">{count}</span>
                  </button>
                  )
                })}
              </div>
            </div>
            {/* 搜索：源站是 t-input；本仓无 TDesign 组件，手写三层 .t-input__wrap > .t-input > .t-input__inner
                （源站 class 落在 t-input__wrap 上，`.search-input .t-input` 才能命中内层） */}
            <div className="t-input__wrap search-input">
              <div className="t-input">
                <input
                  className="t-input__inner"
                  placeholder="搜索知识库…"
                  aria-label="搜索知识库"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </div>
            </div>
          </div>

          {error && <div className="kb-err">{error}</div>}

          <div className="kb-list-main">
            {/* 骨架屏：源站 KnowledgeBaseList.vue:47-61 —— .kb-card.kb-card-skeleton.is-skeleton
                ⚠️ 本仓无 t-skeleton，内部保留 .kb-skel* 由 globals.css 兜底 */}
            {loadingBases && bases.length === 0 && (
              <div className="kb-card-wrap">
                {[0, 1, 2, 3, 4, 5].map((n) => (
                  <div className="kb-card kb-card-skeleton is-skeleton" key={n}>
                    <div className="card-header"><div className="kb-skel kb-skel-title" /></div>
                    <div className="card-content"><div className="kb-skel kb-skel-line" /></div>
                    <div className="card-bottom"><div className="kb-skel kb-skel-badge" /></div>
                  </div>
                ))}
              </div>
            )}

            {/* 空态：源站 EmptyState.vue —— .wk-empty-state 系列 */}
            {!loadingBases && bases.length === 0 && (
              <div className="wk-empty-state">
                <div className="wk-empty-state__icon">
                  <svg width={32} height={32} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.4} strokeLinecap="round" strokeLinejoin="round">
                    <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v8A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5z" />
                  </svg>
                </div>
                <div className="wk-empty-state__title">还没有知识库</div>
                <div className="wk-empty-state__desc">创建第一个知识库，然后上传文档开始构建你的知识底座</div>
                <div className="wk-empty-state__actions">
                  <button
                    type="button"
                    className="t-button t-button--variant-base t-button--theme-primary t-button--shape-rectangle"
                    onClick={() => setCreateOpen(true)}
                  >
                    <span className="t-button__text">新建知识库</span>
                  </button>
                </div>
              </div>
            )}

            {bases.length > 0 && (
              <div className="kb-card-wrap">
                {filteredBases
                  .map((base) => {
                    const meta = KB_TYPE_META[base.type] ?? KB_TYPE_META.document
                    const isFaq = base.type === 'faq'
                    return (
                      <div
                        className={`kb-card ${isFaq ? 'kb-type-faq' : 'kb-type-document'}`}
                        key={base.id}
                        role="link"
                        tabIndex={0}
                        onClick={() => openBase(base)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            openBase(base)
                          }
                        }}
                      >
                        {/* ⭐ 收藏星标（源站 KnowledgeBaseList.vue:395-398）：置顶即收藏 */}
                        <button
                          type="button"
                          className={`kb-favorite-star${base.is_pinned ? ' is-favorited' : ''}`}
                          title={base.is_pinned ? '取消收藏' : '收藏'}
                          aria-label="收藏"
                          aria-pressed={!!base.is_pinned}
                          onClick={async (e) => {
                            e.stopPropagation()
                            try {
                              await knowledgeApi.togglePin(base.id)
                              await loadBases()
                            } catch (err) {
                              showToast(toMessage(err), 'info')
                            }
                          }}
                        >
                          {base.is_pinned ? '★' : '☆'}
                        </button>
                        <div className="card-header">
                          {/* 类型图标：源站 KbTypeAvatar.vue —— 16px SVG，不是字符 */}
                          <span className={`kb-type-avatar ${isFaq ? 'is-faq' : 'is-document'}`} aria-hidden="true">
                            {isFaq ? (
                              <svg className="kb-type-icon kb-faq-icon" width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
                                <path d="M5 4h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H9l-5 3v-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" />
                                <g className="kb-faq-question">
                                  <path d="M9.5 9a2.5 2.5 0 0 1 5 .5c0 1.5-2.5 1.5-2.5 3" />
                                  <circle cx="12" cy="15.5" r="0.8" fill="currentColor" stroke="none" />
                                </g>
                              </svg>
                            ) : (
                              <svg className="kb-type-icon kb-folder-icon" width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
                                <path d="M3 19V6a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2Z" />
                                <g className="kb-folder-paper">
                                  <rect x="7" y="8" width="10" height="10" rx="1" />
                                  <path d="M10 11h4m-4 3h4" />
                                </g>
                                <path className="kb-folder-front" d="M3 9h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
                              </svg>
                            )}
                          </span>
                          <span className="card-title" title={base.name}>
                            <span className="card-title-text">{base.name}</span>
                          </span>
                          <button
                            type="button"
                            className={`more-wrap${openMenuId === base.id ? ' active-more' : ''}`}
                            aria-label="展开"
                            aria-expanded={openMenuId === base.id}
                            aria-haspopup="true"
                            onClick={(e) => {
                              e.stopPropagation()
                              setOpenMenuId((v) => (v === base.id ? null : base.id))
                            }}
                          >
                            <svg viewBox="0 0 24 24" fill="currentColor" width="16" height="16" aria-hidden="true">
                              <circle cx="5" cy="12" r="1.8" />
                              <circle cx="12" cy="12" r="1.8" />
                              <circle cx="19" cy="12" r="1.8" />
                            </svg>
                          </button>
                          {/* 菜单：源站是 t-popup（overlayClassName=card-more-popup）；
                              本仓无 TDesign，保留现有"卡内绝对定位"实现，类名换 .popup-menu。
                              ⚠️ 定位值内联：globals.css 里 .kb-menu 的绝对定位随旧类名一起弃用，
                              而 kb-*.css 只负责 .popup-menu 的外观（flex + item 样式），不含定位。 */}
                          {openMenuId === base.id && (
                            <div
                              className="popup-menu"
                              style={{ position: 'absolute', top: 44, right: 8, zIndex: 20, minWidth: 132, padding: 4, border: '1px solid var(--td-component-stroke, #e7e7e7)', borderRadius: 10, background: 'var(--td-bg-color-container, #fff)', boxShadow: '0 6px 20px rgba(0, 0, 0, 0.10)' }}
                              onMouseDown={(e) => e.stopPropagation()}
                            >
                              {/* 置顶 / 取消置顶：源站 KnowledgeBaseList.vue:415-418（handleTogglePin）。
                                  ⚠️ 本仓原有菜单只有「删除」，派单书 §4 DOM 骨架要求补这一项。
                                  逻辑**整段复用卡片星标**已有调用（togglePin → loadBases → 关菜单），
                                  不新增 API、不新增 state、不改任何既有 handler 函数体。 */}
                              <div
                                className="popup-menu-item"
                                role="button"
                                tabIndex={0}
                                style={{ borderRadius: 4 }}
                                onClick={async (e) => {
                                  e.stopPropagation()
                                  try {
                                    await knowledgeApi.togglePin(base.id)
                                    await loadBases()
                                    setOpenMenuId(null)
                                  } catch (err) {
                                    showToast(toMessage(err), 'info')
                                  }
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault()
                                    e.stopPropagation()
                                  }
                                }}
                              >
                                <span>{base.is_pinned ? '取消置顶' : '置顶'}</span>
                              </div>
                              <div
                                className="popup-menu-item delete"
                                role="button"
                                tabIndex={0}
                                style={{ borderRadius: 4 }}
                                onClick={(e) => {
                                  e.stopPropagation()
                                  void handleDeleteBase(base)
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault()
                                    e.stopPropagation()
                                    void handleDeleteBase(base)
                                  }
                                }}
                              >
                                <span>删除知识库</span>
                              </div>
                            </div>
                          )}
                        </div>
                        <div className="card-content">
                          <div className={`card-description${base.description ? '' : ' is-empty'}`}>
                            {base.description || '暂无描述'}
                          </div>
                        </div>
                        <div className="card-bottom">
                          <div className="bottom-left">
                            <div className="feature-badges">
                              <div className={`feature-badge is-count ${isFaq ? 'type-faq' : 'type-document'}`}>
                                <span aria-hidden="true">{meta.mark}</span>
                                {/* 设计稿 01 页卡片底部是「N 个文档」/「N 组问句」（实测：
                                    ["▤|1 个文档","▤|0 个文档","💬|24 组问句"]），
                                    不是类型名。计数拿不到时回落成类型名（不显示 0 冒充）。 */}
                                <span className="badge-count">
                                  {itemCounts[base.id] == null
                                    ? meta.label
                                    : `${itemCounts[base.id]} ${isFaq ? '组问句' : '个文档'}`}
                                </span>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
              </div>
            )}
          </div>
        </div>
      </div>
      {createModalEl}
      </>
    )
  }

  // ══════════════════════════════════════════════════════════════
  // 二级：某个库的文档列表（源侧 KnowledgeBase）
  // ══════════════════════════════════════════════════════════════
  return (
    <div className="kb-page">
      <div className="kb-list-content">
        {/* 面包屑：知识库 › 当前库（点「知识库」回列表 —— 这就是用户要的退出路径） */}
        {/* ⚠️ 容器类名必须是 `document-header`（不是自造的 kb-header）：
            kb-shared.css 里这一整块规则全是 `.kb-scope .document-header .xxx` 后代选择器，
            缺这一层 ⇒ `.breadcrumb-separator{font-size:14px}` 等规则**全部不命中**，
            `<svg>` 退回默认尺寸 300×150（截图里那个巨大箭头就是这么来的）。 */}
        <div className="document-header">
          <div className="document-header-title">
          <div className="kb-title-row">
            {/* ⭐ 结构逐字对齐源码 KnowledgeBase.vue:2458-2495：
                h2.document-breadcrumb > (breadcrumb-link 知识库) + 分隔 + (当前库下拉)
                + 分隔 + div.kb-view-tabs > button.kb-view-tab[role=tab]
                标题行右侧 kb-title-actions = 信息(ℹ) + 设置(⚙) 两个圆钮。
                ⛔ 之前这里是自造的 kb-breadcrumb / kb-detail-tabs / kb-header-actions。 */}
            <h2 className="document-breadcrumb">
              <button type="button" className="breadcrumb-link" onClick={() => setView('list')}>
                知识库
              </button>
              <svg className="breadcrumb-separator" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                <path d="M9 5l7 7-7 7" />
              </svg>
              <button type="button" className="breadcrumb-link dropdown" disabled={!activeBaseId}>
                <span>{activeBase?.name ?? '—'}</span>
                {bases.length > 1 && (
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                )}
              </button>
              <svg className="breadcrumb-separator" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                <path d="M9 5l7 7-7 7" />
              </svg>
              {/* ⭐ tabs：文档 / Wiki / 图谱 / 画廊 —— **四个恒定显示**。
                  ⚠️ 设计稿 02（文档列表）、03（Wiki）、04（图谱）三页的 header
                  都是这 4 个 tab 全在（已逐页核对），用户也确认「所有知识库都显示」。
                  ⛔ 不要按源码 `if (isWiki) {wiki, graph}` 的条件渲染 ——
                  本仓 `indexing_strategy` 常为 NULL，条件渲染会让这两个 tab
                  **在所有库上永久消失**（用户报的正是这个）。
                 点进 Wiki/图谱时若库没开 wiki 能力，由对应面板自己说明未启用。 */}
              <div className="kb-view-tabs" role="tablist" aria-label="知识库视图">
                <button
                  type="button" role="tab"
                  className={`kb-view-tab${detailTab === 'docs' ? ' active' : ''}`}
                  aria-selected={detailTab === 'docs'}
                  onClick={() => setDetailTab('docs')}
                >
                  <svg className="kb-view-tab__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <path d="M4 22h16V8h-6V2H4v20Z" /><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H4v20h16V8" />
                  </svg>
                  <span>文档</span>
                </button>
                <button
                  type="button" role="tab"
                  className={`kb-view-tab${detailTab === 'wiki' ? ' active' : ''}`}
                  aria-selected={detailTab === 'wiki'}
                  onClick={() => setDetailTab('wiki')}
                >
                  <svg className="kb-view-tab__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <path d="M2 4h9v17H2V4Zm11 0h9v17h-9V4Z" /><path d="M5 8h3M5 12h3M5 16h3M16 8h3M16 12h3" />
                  </svg>
                  <span>Wiki</span>
                </button>
                <button
                  type="button" role="tab"
                  className={`kb-view-tab${detailTab === 'graph' ? ' active' : ''}`}
                  aria-selected={detailTab === 'graph'}
                  onClick={() => setDetailTab('graph')}
                >
                  <svg className="kb-view-tab__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <circle cx="6" cy="6" r="2.6" /><circle cx="18" cy="7" r="2.6" /><circle cx="12" cy="18" r="2.6" />
                    <path d="M8.2 7.2 15.6 8M7.3 8.3l3.5 7.6M16.7 9.2l-3.4 6.6" />
                  </svg>
                  <span>图谱</span>
                </button>
                <button
                  type="button" role="tab"
                  className={`kb-view-tab${detailTab === 'entity-graph' ? ' active' : ''}`}
                  aria-selected={detailTab === 'entity-graph'}
                  onClick={() => setDetailTab('entity-graph')}
                >
                  {/* ⭐ 第 5 个 tab「关系图谱」（2026-11-11 用户要求）。
                      ⚠️ 与「图谱」tab **不是同一个东西**：
                      · 图谱 = Wiki 页面之间的 [[链接]]（WikiPanel 的 graph 视图，源站同款）
                      · 关系图谱 = 从文档正文抽出的**实体-关系**（kb_graph_entity / kb_graph_relation）
                      用户明确指出「还有关系图谱页面也没有 不是 wiki图谱哦」。 */}
                  <svg className="kb-view-tab__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <circle cx="12" cy="5" r="2.4" /><circle cx="5" cy="19" r="2.4" /><circle cx="19" cy="19" r="2.4" />
                    <path d="M12 7.4v4.2M6.4 16.9l4.3-3.7M17.6 16.9l-4.3-3.7" />
                  </svg>
                  <span>关系图谱</span>
                </button>
                <button
                  type="button" role="tab"
                  className={`kb-view-tab${detailTab === 'gallery' ? ' active' : ''}`}
                  aria-selected={detailTab === 'gallery'}
                  onClick={() => setDetailTab('gallery')}
                >
                  <svg className="kb-view-tab__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                    <rect x="2.5" y="4.5" width="19" height="15" rx="2" /><circle cx="8.5" cy="10" r="1.6" /><path d="M4 17l5-4.5 4 3.5 3.5-3L21 17" />
                  </svg>
                  <span>画廊</span>
                </button>
              </div>
            </h2>
            {/* ⭐ 标题行右侧动作锚点：源码 kb-title-actions = 信息 + 设置（两个圆形按钮）。
                ⚠️ 额外保留「关闭」：源码是独立路由页（浏览器可后退），本仓是浮层，
                没有浏览器后退 ⇒ ⛔ 不能按源码删掉它，否则唯一出口消失（死锁）。 */}
            <div className="kb-title-actions">
              {closeScene && (
                <button
                  type="button"
                  className="kb-btn ghost"
                  onClick={closeScene}
                  title="关闭知识库"
                  aria-label="关闭知识库"
                >
                  ✕
                </button>
              )}
              <button
                type="button"
                className="kb-info-button"
                title="知识库信息"
                aria-label="知识库信息"
                onClick={() => setInfoOpen((v) => !v)}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                  <circle cx="12" cy="12" r="9.2" /><path d="M12 11v6M12 7.6v.8" />
                </svg>
              </button>
              <button
                type="button"
                className="kb-settings-button"
                title="知识库设置"
                aria-label="知识库设置"
                disabled={!activeBaseId}
                onClick={() => setSettingsOpen(true)}
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.7} aria-hidden="true">
                  <circle cx="12" cy="12" r="3.1" />
                  <path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 1.55V21a2 2 0 1 1-4 0v-.09A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.55-1H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.55V3a2 2 0 1 1 4 0v.09a1.7 1.7 0 0 0 1 1.55 1.7 1.7 0 0 0 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.7 1.7 0 0 0 19.4 9v0a1.7 1.7 0 0 0 1.55 1H21a2 2 0 1 1 0 4h-.09a1.7 1.7 0 0 0-1.51 1Z" />
                </svg>
              </button>
            </div>
          </div>
          </div>
          <p className="document-subtitle">
            {activeBase?.description || `${total} 个文档 · 解析完成后即可被检索`}
          </p>
          {/* ⭐ 源码 KnowledgeBase.vue:2508-2515：有不可解析的扩展名时提示去配置（点整行走设置） */}
          {unsupportedFileTypes.length > 0 && (
            <p className="parser-hint" onClick={() => setSettingsOpen(true)}>
              <svg className="parser-hint-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
                <circle cx="12" cy="12" r="9.2" /><path d="M12 11v6M12 7.6v.8" />
              </svg>
              <span>
                部分文档类型（{unsupportedFileTypes.map((t) => '.' + t).join('、')}）暂无可用解析引擎，上传后将无法解析
              </span>
              <span className="parser-hint-link">前往配置 →</span>
            </p>
          )}
        </div>

        {detailTab === 'wiki' ? (
          activeBaseId ? (
            <WikiPanel
              baseId={activeBaseId}
              showToast={showToast}
              initialView="tree"
            />
          ) : (
            <div className="wiki-reader-empty"><p className="wiki-empty-desc">正在加载知识库…</p></div>
          )
        ) : detailTab === 'graph' ? (
          /* ⭐ 图谱 tab = **同一个 WikiPanel 的 graph 视图**（源码 KnowledgeBase.vue:2526：
             `<WikiBrowser :view="activeKbTab === 'graph' ? 'graph' : 'browser'" />`）。
             ⛔ 不是 GraphPanel —— 那是「文档抽出的实体关系星图」（暗色背景 + 29 实体/55 关系），
             与源码的「Wiki 页面关系图 + 480px 节点抽屉」是两套完全不同的东西。
             用户已指出接错（图一 vs 图二）。 */
          activeBaseId ? (
            <WikiPanel
              baseId={activeBaseId}
              showToast={showToast}
              initialView="graph"
            />
          ) : (
            <div className="wiki-reader-empty"><p className="wiki-empty-desc">正在加载知识库…</p></div>
          )
        ) : detailTab === 'entity-graph' ? (
          /* ⭐ 关系图谱 tab（2026-11-11 用户要求「还有关系图谱页面也没有 不是 wiki图谱哦」）。
             数据源 = `kb_graph_entity` / `kb_graph_relation`（LLM 从文档抽出的实体-关系）。
             ⛔ 与上面「图谱」tab 的 Wiki 页面链接图完全无关，两套独立实现。
             `knowledgeOptions` 供「重建图谱」下拉选文档（用的是 loadItems 同一次请求的结果）。 */
          activeBaseId ? (
            <GraphPanel
              baseId={activeBaseId}
              baseName={activeBase?.name}
              knowledgeOptions={docOptions}
              showToast={showToast}
            />
          ) : (
            <div className="wiki-reader-empty"><p className="wiki-empty-desc">正在加载知识库…</p></div>
          )
        ) : detailTab === 'gallery' ? (
          activeBaseId ? (
            <KbGalleryPanel baseId={activeBaseId} showToast={showToast} />
          ) : (
            <div className="wiki-reader-empty"><p className="wiki-empty-desc">正在加载知识库…</p></div>
          )
        ) : detailTab === 'qa' ? (
          activeBaseId ? (
            <KbQaPanel
              baseId={activeBaseId}
              baseName={activeBase?.name}
              bases={bases}
              showToast={showToast}
            />
          ) : (
            <div className="wiki-reader-empty"><p className="wiki-empty-desc">正在加载知识库…</p></div>
          )
        ) : (
          /* ⭐ FAQ 型知识库走条目管理（结构化问答对），文档库才走文件列表 */
          activeBase?.type === 'faq' && activeBaseId ? (
            <KbFaqManager baseId={activeBaseId} showToast={showToast} />
          ) : (
            <KnowledgeDocPanel
              baseId={activeBaseId ?? ''}
              baseName={activeBase?.name}
              showToast={showToast}
              onRequestUpload={() => fileInputRef.current?.click()}
            />
          )
        )}
      </div>

      {createModalEl}

      {/* ⚠️ 隐藏文件输入：**不是 UI 入口**（源码 header 也没有上传按钮），
          只给文档区空态按钮「上传文档」用 —— `KnowledgeDocPanel.onRequestUpload`
          需要一个能被 click() 的 input。 */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        hidden
        onChange={(e) => void handleUpload(e.target.files)}
      />

      {/* ⭐ 信息弹层（对齐源码 KBInfoPopover：只读展示知识库元信息）。
          ⚠️ 本仓 `KnowledgeBaseDto` 不返回 indexing_strategy / 存储引擎详情，
          所以只列**读得到**的字段，⛔ 不编造缺失项。 */}
      {infoOpen && activeBase && (
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 300, background: 'transparent' }}
          onClick={() => setInfoOpen(false)}
        >
          <div
            className="kb-info-popover"
            style={{
              position: 'absolute',
              right: 24,
              top: 96,
              minWidth: 260,
              padding: 16,
              borderRadius: 'var(--app-radius-lg, 10px)',
              background: 'var(--td-bg-color-container, #fff)',
              border: '0.5px solid var(--td-component-stroke, #e7e7e7)',
              boxShadow: '0 2px 4px #0000000a, 0 6px 20px #00000014',
              fontSize: 'var(--app-text-base, 14px)',
              color: 'var(--td-text-color-primary)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ fontWeight: 600, marginBottom: 10 }}>{activeBase.name}</div>
            <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 12px' }}>
              <dt style={{ color: 'var(--td-text-color-placeholder)' }}>类型</dt>
              <dd style={{ margin: 0 }}>
                {activeBase.type === 'faq' ? '问答库' : activeBase.type === 'wiki' ? 'Wiki 库' : '文档库'}
              </dd>
              <dt style={{ color: 'var(--td-text-color-placeholder)' }}>文档数</dt>
              <dd style={{ margin: 0 }}>{total}</dd>
              <dt style={{ color: 'var(--td-text-color-placeholder)' }}>创建时间</dt>
              <dd style={{ margin: 0 }}>{activeBase.created_at ?? '—'}</dd>
              <dt style={{ color: 'var(--td-text-color-placeholder)' }}>更新时间</dt>
              <dd style={{ margin: 0 }}>{activeBase.updated_at ?? '—'}</dd>
            </dl>
            {activeBase.description && (
              <p style={{ margin: '10px 0 0', color: 'var(--td-text-color-secondary)' }}>
                {activeBase.description}
              </p>
            )}
          </div>
        </div>
      )}

      {settingsOpen && activeBase && (
        <KbSettingsModal
          base={activeBase}
          showToast={showToast}
          onClose={() => setSettingsOpen(false)}
          onSaved={() => void loadBases()}
        />
      )}
    </div>
  )
}
