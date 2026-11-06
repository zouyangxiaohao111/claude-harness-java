import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'
import type { Knowledge, KnowledgeBase, KnowledgeListQuery } from '@/api/knowledge'
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

export function KbSceneNav(props: KbSceneNavProps) {
  const { activeKey = 'knowledge' } = props
  return (
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
    </aside>
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
  const [detailTab, setDetailTab] = useState<'docs' | 'wiki' | 'graph' | 'gallery' | 'qa'>('docs')
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [activeBaseId, setActiveBaseId] = useState<string | null>(null)
  const [items, setItems] = useState<Knowledge[]>([])
  const [total, setTotal] = useState(0)
  const [loadingBases, setLoadingBases] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)
  /** 设置弹窗（详情页右上角齿轮）*/
  const [settingsOpen, setSettingsOpen] = useState(false)
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
      setItems([])
      setTotal(0)
      return
    }
    try {
      const query: KnowledgeListQuery = { page: 1, page_size: 100 }
      if (keyword.trim()) query.keyword = keyword.trim()
      if (statusFilter) query.parse_status = statusFilter
      const page = await knowledgeApi.listKnowledge(activeBaseId, query)
      setItems(page.data)
      setTotal(page.total)
      setError(null)
    } catch (e) {
      setError(toMessage(e))
    }
  }, [activeBaseId, keyword, statusFilter])

  useEffect(() => {
    if (view !== 'detail') return
    void loadItems()
  }, [view, loadItems])

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
   * <p>⭐ 参数来自 {@link KbCreateModal}。本仓 `CreateKnowledgeBaseRequest` 的
   * 真实字段是 `name` / `description` / `type` / `embeddingModelId` / `summaryModelId`；
   * 分块大小与重叠是「上传时的解析参数」，不在创建请求里 ⇒ 这里只落前三个，
   * 分块值记进 localStorage，供上传面板读取（⛔ 不假装写进了后端）。
   */
  const handleCreateBase = async (payload: KbCreateSubmit) => {
    try {
      await knowledgeApi.createBase({
        name: payload.name,
        description: payload.description || undefined,
        type: payload.type,
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
    setUploading(true)
    let ok = 0
    const failures: string[] = []
    for (const file of Array.from(fileList)) {
      try {
        await knowledgeApi.uploadKnowledge(activeBaseId, file)
        ok++
      } catch (e) {
        failures.push(`${file.name}: ${toMessage(e)}`)
      }
    }
    setUploading(false)
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (ok > 0) showToast(`已上传 ${ok} 个文件，正在解析`, 'success')
    if (failures.length > 0) showToast(failures.join('；'), 'info')
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
      <div className="kb-page">
        <div className="kb-list-content">
          <div className="kb-header">
            <div className="kb-title-row">
              <h2 className="kb-h2">
                <svg className="kb-h2-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v8A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5z" />
                </svg>
                知识库
              </h2>
              <div className="kb-header-actions">
                <input
                  className="kb-input kb-header-search"
                  placeholder="搜索知识库…"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
                {closeScene && (
                  <button type="button" className="kb-btn ghost" onClick={closeScene}>
                    ✕ 关闭
                  </button>
                )}
                {/* 设计稿是 .primary（绿底白字 fw500），⛔ 不是白底 ghost */}
                {/* 真机实测：源侧是白底 text 按钮（13px / 32px 高 / 圆角 6px），
                    ⛔ 不是绿底主按钮 —— 绿底是「创建知识库」弹窗里的确认按钮。 */}
                <button type="button" className="kb-btn" onClick={() => setCreateOpen(true)}>
                  ＋ 新建知识库
                </button>
              </div>
            </div>
            <p className="kb-subtitle">管理和组织您的知识库，支持文档型和问答型知识库</p>
          </div>

          {/* 工具条：分类胶囊 + 排序 + 搜索（设计稿 .toolbar） */}
          <div className="kb-toolbar">
            <div className="kb-cat-tabs" role="tablist" aria-label="分类">
              {([
                ['all', '全部', bases.length],
                ['fav', '收藏', bases.filter((b) => b.is_pinned).length],
                ['recent', '最近', bases.length],
              ] as const).map(([key, label, count]) => (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  aria-selected={category === key}
                  className={`kb-cat${category === key ? ' on' : ''}`}
                  onClick={() => setCategory(key)}
                >
                  {label}<span className="kb-cat-n">{count}</span>
                </button>
              ))}
            </div>
            <button
              type="button"
              className="kb-sort-trigger"
              onClick={() => setSortBy(
                sortBy === 'updated' ? 'created' : sortBy === 'created' ? 'name' : 'updated'
              )}
              title="切换排序"
            >
              排序 · {sortBy === 'updated' ? '最近更新' : sortBy === 'created' ? '创建时间' : '名称'}
            </button>
          </div>

          {error && <div className="kb-err">{error}</div>}

          <div className="kb-list-main">
            {loadingBases && bases.length === 0 && (
              <div className="kb-card-wrap">
                {[0, 1, 2, 3, 4, 5].map((n) => (
                  <div className="kb-card kb-card-skeleton" key={n}>
                    <div className="kb-card-header"><div className="kb-skel kb-skel-title" /></div>
                    <div className="kb-card-content"><div className="kb-skel kb-skel-line" /></div>
                    <div className="kb-card-bottom"><div className="kb-skel kb-skel-badge" /></div>
                  </div>
                ))}
              </div>
            )}

            {!loadingBases && bases.length === 0 && (
              <div className="kb-empty-cards">
                <div className="kb-empty-title">还没有知识库</div>
                <div className="kb-empty-sub">创建第一个知识库，然后上传文档开始构建你的知识底座</div>
                <button type="button" className="kb-btn primary" onClick={() => setCreateOpen(true)}>
                  新建知识库
                </button>
              </div>
            )}

            {bases.length > 0 && (
              <div className="kb-card-wrap">
                {filteredBases
                  .map((base) => {
                    const meta = KB_TYPE_META[base.type] ?? KB_TYPE_META.document
                    return (
                      <div
                        className={`kb-card kb-type-${base.type || 'document'}`}
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
                        {/* ⭐ 收藏星标（设计稿 .kb-favorite-star）：置顶即收藏 */}
                        <button
                          type="button"
                          className={`kb-fav${base.is_pinned ? ' on' : ''}`}
                          title={base.is_pinned ? '取消收藏' : '收藏'}
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
                        <div className="kb-card-header">
                          <span className="kb-card-avatar">{meta.mark}</span>
                          <span className="kb-card-title" title={base.name}>
                            <span className="kb-card-title-text">{base.name}</span>
                          </span>
                          <div className="kb-more-wrap" onClick={(e) => e.stopPropagation()}>
                            <button
                              type="button"
                              className={`kb-more${openMenuId === base.id ? ' active' : ''}`}
                              aria-label="更多操作"
                              aria-expanded={openMenuId === base.id}
                              onClick={() => setOpenMenuId((v) => (v === base.id ? null : base.id))}
                            >
                              <svg viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
                                <circle cx="5" cy="12" r="1.8" />
                                <circle cx="12" cy="12" r="1.8" />
                                <circle cx="19" cy="12" r="1.8" />
                              </svg>
                            </button>
                            {openMenuId === base.id && (
                              <div className="kb-menu" onMouseDown={(e) => e.stopPropagation()}>
                                <button
                                  type="button"
                                  className="kb-menu-item danger"
                                  onClick={() => void handleDeleteBase(base)}
                                >
                                  删除知识库
                                </button>
                              </div>
                            )}
                          </div>
                        </div>
                        <div className="kb-card-content">
                          <div className={`kb-card-desc${base.description ? '' : ' is-empty'}`}>
                            {base.description || '暂无描述'}
                          </div>
                        </div>
                        <div className="kb-card-bottom">
                          <span className="kb-badge">
                            <span className="kb-badge-mark">{meta.mark}</span>
                            {meta.label}
                          </span>
                          <span className="kb-card-time">{base.updated_at ?? ''}</span>
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
        <div className="kb-header">
          <div className="kb-title-row">
            <h2 className="kb-breadcrumb">
              <button type="button" className="kb-crumb-link" onClick={() => setView('list')}>
                知识库
              </button>
              <svg className="kb-crumb-sep" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}>
                <path d="M9 5l7 7-7 7" />
              </svg>
              <span className="kb-crumb-current">{activeBase?.name ?? '—'}</span>
            </h2>
            <div className="kb-header-actions">
              {closeScene && (
                <button type="button" className="kb-btn ghost" onClick={closeScene}>
                  ✕ 关闭
                </button>
              )}
              <button type="button" className="kb-btn ghost" onClick={() => void loadItems()}>
                刷新
              </button>
              <button
                type="button"
                className="kb-btn"
                onClick={() => setSettingsOpen(true)}
                title="知识库设置"
              >
                ⚙ 设置
              </button>
              <button
                type="button"
                className="kb-btn primary"
                disabled={!activeBaseId || uploading}
                onClick={() => fileInputRef.current?.click()}
              >
                {uploading ? '上传中…' : '＋ 上传文档'}
              </button>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                hidden
                onChange={(e) => void handleUpload(e.target.files)}
              />
            </div>
          </div>
          <p className="kb-subtitle">
            {activeBase?.description || `${total} 个文档 · 解析完成后即可被检索`}
          </p>
          {/* ⭐ 详情页 Tab（源侧形态：Wiki 是知识库的子页，不是独立入口） */}
          <div className="kb-detail-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={detailTab === 'docs'}
              className={`kb-detail-tab${detailTab === 'docs' ? ' is-active' : ''}`}
              onClick={() => setDetailTab('docs')}
            >
              文档
              <span className="kb-detail-tab-count">{total}</span>
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={detailTab === 'wiki'}
              className={`kb-detail-tab${detailTab === 'wiki' ? ' is-active' : ''}`}
              onClick={() => setDetailTab('wiki')}
            >
              Wiki
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={detailTab === 'graph'}
              className={`kb-detail-tab${detailTab === 'graph' ? ' is-active' : ''}`}
              onClick={() => setDetailTab('graph')}
            >
              图谱
            </button>
            {/* ⭐ QA-13：问答 tab（源侧 KB 详情页没有 ⇒ 本仓新增，不属于「复刻」） */}
            <button
              type="button"
              role="tab"
              aria-selected={detailTab === 'gallery'}
              className={`kb-detail-tab${detailTab === 'gallery' ? ' is-active' : ''}`}
              onClick={() => setDetailTab('gallery')}
            >
              画廊
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={detailTab === 'qa'}
              className={`kb-detail-tab${detailTab === 'qa' ? ' is-active' : ''}`}
              onClick={() => setDetailTab('qa')}
            >
              问答
            </button>
          </div>
        </div>

        {detailTab === 'wiki' ? (
          activeBaseId ? (
            <WikiPanel
              baseId={activeBaseId}
              baseName={activeBase?.name}
              showToast={showToast}
            />
          ) : (
            <div className="kb-wiki-loading">正在加载知识库…</div>
          )
        ) : detailTab === 'graph' ? (
          activeBaseId ? (
            <GraphPanel
              baseId={activeBaseId}
              baseName={activeBase?.name}
              knowledgeOptions={items.map((k) => ({ id: k.id, title: k.title || k.file_name || k.id }))}
              showToast={showToast}
            />
          ) : (
            <div className="kb-wiki-loading">正在加载知识库…</div>
          )
        ) : detailTab === 'gallery' ? (
          activeBaseId ? (
            <KbGalleryPanel baseId={activeBaseId} showToast={showToast} />
          ) : (
            <div className="kb-wiki-loading">正在加载知识库…</div>
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
            <div className="kb-wiki-loading">正在加载知识库…</div>
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
