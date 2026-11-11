import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import {
  wikiApi,
  WIKI_ISSUE_TYPE_LABEL,
  WIKI_PAGE_TYPE_LABEL,
  WIKI_SEVERITY_LABEL,
  healthTone,
} from '@/api/wiki'
import type { WikiFolder, WikiIndex, WikiIssue, WikiLintReport, WikiPage, WikiRevision } from '@/api/wiki'
import { extractHeadings, renderWikiMarkdown, WIKI_LINK_ATTR } from '@/utils/wikiMarkdown'
import { KbWikiGraph } from './KbWikiGraph'

interface WikiPanelProps {
  /** 所属知识库 ID。 */
  baseId: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 初始视图。
   *
   * <p>⭐ 为什么需要它：源码的「Wiki」与「图谱」是**两个 tab，同一个组件**
   * （`KnowledgeBase.vue:2526` → `WikiBrowser :view="activeKbTab === 'graph' ? 'graph' : 'browser'"`）。
   * 本仓拆成了 `detailTab === 'wiki' | 'graph'` 两个分支，但图谱分支必须渲染
   * **同一个 WikiPanel 的 graph 视图**（页面关系图 + 节点抽屉），
   * ⛔ 不是 `GraphPanel`（那是「文档抽出的实体关系星图」，两套完全不同的东西）。
   */
  initialView?: 'tree' | 'graph'
}

/** 树节点（目录或页面）。 */
interface TreeItem {
  kind: 'folder' | 'page'
  key: string
  label: string
  depth: number
  slug?: string
  pageType?: string
  children: TreeItem[]
}

/**
 * Wiki 面板 · 知识库详情页的一个 tab（源侧形态）。
 *
 * <h2>三栏布局</h2>
 * <pre>
 *   ┌──────────┬─────────────────────────┬──────────────┐
 *   │ 目录/页面 │  页面正文（Markdown）    │ 大纲 + 反链  │
 *   └──────────┴─────────────────────────┴──────────────┘
 * </pre>
 *
 * <h2>⭐⭐ 三个必须显式处理的边界</h2>
 * <ol>
 *   <li><b>空库 ≠ 加载中</b>：没有任何页面时要说「还没有 Wiki 页面」并说明怎么产生，
 *       ⛔ 不能显示一片空白（用户会以为坏了）。</li>
 *   <li><b>页面正文是模型生成的</b> ⇒ 渲染必须走 {@code renderWikiMarkdown}
 *       （已做转义 + 标签白名单），⛔ 不能直接 `dangerouslySetInnerHTML` 原始 Markdown。</li>
 *   <li><b>内部链接点击要拦默认行为</b>：`[[slug]]` 渲染成 `<a data-wiki-slug>`，
 *       点它应该切到目标页，而不是跳转 URL。</li>
 * </ol>
 */
export function WikiPanel({ baseId, showToast, initialView }: WikiPanelProps) {
  const [pages, setPages] = useState<WikiPage[]>([])
  const [folders, setFolders] = useState<WikiFolder[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeSlug, setActiveSlug] = useState<string | null>(null)
  /**
   * ⭐ 侧栏内容分类 tab（对齐源站 `WikiBrowser.vue`）。
   *
   * <p>源站把侧栏的 tab 定义成**内容分类**，不是视图切换：
   * <ul>
   *   <li>「知识」= entity / concept / synthesis / comparison 四类合并
   *       （源站注释原话：「Entity and concept pages look and behave alike, so the
   *       sidebar merges all non-summary content types under a single "knowledge" tab」）</li>
   *   <li>「摘要」= page_type 为 summary 的页</li>
   * </ul>
   * 源站常量：`CONTENT_TABS = [KNOWLEDGE_TAB, 'summary']`、
   * `KNOWLEDGE_TYPES = ['entity','concept','synthesis','comparison']`。
   */
  const [activeTab, setActiveTab] = useState<'knowledge' | 'summary'>('knowledge')
  /**
   * ⭐ 系统视图（照源站 `activeSystemView`）。
   *
   * <p>源站 `WikiBrowser.vue:1005`：`const activeSystemView = ref<'' | 'index'>('')`。
   * 它与 `selectedPage` **互斥**：选中一个普通页 ⇒ `activeSystemView = ''`；
   * 打开索引 ⇒ `selectedPage = null` 且 `activeSystemView = 'index'`。
   *
   * <p>源站默认进索引（`:2980-2991`，注释原话：「The index is the natural landing view
   * — it shows intro + a paginated directory of every page type.」）：
   * ```js
   * if (!selectedPage.value && activeSystemView.value === '') {
   *   if (route.query.slug) navigateToSlug(route.query.slug)
   *   else if (indexAvailable.value) openIndexView()
   * }
   * ```
   */
  const [systemView, setSystemView] = useState<'' | 'index'>('')
  /** 索引数据（源站 indexMarkdown + indexAvailable）。 */
  const [indexData, setIndexData] = useState<WikiIndex | null>(null)
  const [indexLoading, setIndexLoading] = useState(false)
  const [query, setQuery] = useState('')
  // ⭐ 初始视图可由外部指定：图谱 tab 传 'graph'，Wiki tab 传默认 'tree'
  const [view, setView] = useState<'tree' | 'graph' | 'lint'>(initialView ?? 'tree')
  /**
   * ⭐ 外部 tab 切换时必须同步 `view`。
   *
   * <p><b>为什么不能只靠 useState 初值</b>：`KnowledgeBasePage` 的「Wiki」与「图谱」
   * 是同一个 `<WikiPanel>` 出现在两个分支里 —— 元素类型相同、位置相同，React 会
   * **复用同一个实例**（不是卸载重建）。此时 `initialView` 虽然变了，`useState` 的
   * 初值只算第一次 ⇒ 点「图谱」后 `view` 仍是 `'tree'`，画面停在 Wiki 页。
   *
   * <p>源站是**一个组件 + 响应式 prop**（`<WikiBrowser :view="activeKbTab === 'graph' ? 'graph' : 'browser'" />`），
   * 这里用 effect 复现同样的「prop 驱动视图」语义。
   *
   * <p>⚠️ 只在 `initialView` **变化时**才写 state，所以不会打断组件内部自己的
   * 视图切换（右上角 ◍ / ⚠ 两个按钮改的是 `view`，但 `initialView` 没变 ⇒ effect 不重跑）。
   */
  useEffect(() => {
    const next = initialView ?? 'tree'
    setView((cur) => (cur === next ? cur : next))
    console.info(`[Wiki] 视图随 tab 切换 view=${next} kb=${baseId}`)
  }, [initialView, baseId])
  /**
   * 目录的展示形态（设计稿 `.wiki-view-toggle` 的树形/列表两态）。
   * ⚠️ 与 {@link view} 不是一个维度：`view` 是「页面/图谱/检查」功能视图，
   * `listMode` 只是目录列表的排布方式。
   */
  const [listMode, setListMode] = useState<'tree' | 'list'>('tree')
  const [lint, setLint] = useState<WikiLintReport | null>(null)
  const [issues, setIssues] = useState<WikiIssue[]>([])
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [revisions, setRevisions] = useState<WikiRevision[]>([])
  /**
   * 手动生成中的状态（「生成本库 Wiki」按钮）。
   *
   * <p>⚠️ 后端是<b>同步</b>调用：整库多篇文档时可能几十秒到几分钟。
   * 没有这个 loading 态，用户会以为「点了没反应」而反复点（后端有连点保护，
   * 但每次连点都会拿到「正在生成，请稍候」，体验很差）。
   */
  const [rebuildingWiki, setRebuildingWiki] = useState(false)
  const bodyRef = useRef<HTMLDivElement | null>(null)

  const activePage = useMemo(
    () => pages.find((p) => p.slug === activeSlug) ?? null,
    [pages, activeSlug]
  )

  // ── 加载 ──────────────────────────────────────────────────────
  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [ps, fs] = await Promise.all([wikiApi.listPages(baseId), wikiApi.listFolders(baseId)])
      setPages(ps)
      setFolders(fs)
      // ⭐ 照源站：`selectedPage` 初始为 null，**不自动选第一页**（用户点目录里的页才选）。
      // 源站依据：WikiBrowser.vue 里 `selectedPage` 初始 null，只有 `selectPage(page)` 才赋值。
      // ⚠️ 刷新时**保留**已有选中（cur 仍有效就留着），只是首次加载不选。
      setActiveSlug((cur) => (cur && ps.some((p) => p.slug === cur) ? cur : null))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [baseId])

  useEffect(() => {
    void load()
  }, [load])

  /**
   * ⭐ 打开索引视图（照源站 `openIndexView()`，WikiBrowser.vue:2779-2793）。
   *
   * <p>源站语义：`selectedPage = null` + `activeSystemView = 'index'` —— 二者互斥。
   * 索引数据按需拉取（已拉过就不重复拉）。
   */
  const openIndexView = useCallback(async () => {
    setActiveSlug(null)
    setSystemView('index')
    if (indexData) return
    setIndexLoading(true)
    try {
      setIndexData(await wikiApi.getIndex(baseId))
    } catch (e) {
      showToast(`索引加载失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    } finally {
      setIndexLoading(false)
    }
  }, [baseId, indexData, showToast])

  /**
   * ⭐ 默认打开索引（照源站 WikiBrowser.vue:2980-2991）。
   *
   * <p>源站：加载完页面后，若既没有选中页也没有系统视图 ⇒ 默认 `openIndexView()`
   * （注释原话：「The index is the natural landing view — it shows intro + a
   * paginated directory of every page type.」）。
   */
  useEffect(() => {
    if (loading || error) return
    if (activeSlug || systemView) return
    void openIndexView()
  }, [loading, error, activeSlug, systemView, openIndexView])

  /**
   * ⭐ 选中一个普通页（照源站 `selectPage(page)`）。
   *
   * <p>源站选中页时会**退出系统视图**：`activeSystemView.value = ''`
   * （WikiBrowser.vue:3691 / 3710 附近的 `navigateToSlug`；`selectPage` 同理）。
   * ⇒ 本仓这里一并把 `systemView` 清掉，否则索引视图会盖住选中页。
   */
  const selectPage = useCallback((slug: string) => {
    setActiveSlug(slug)
    setSystemView('')
  }, [])

  const loadLint = useCallback(async () => {
    try {
      const [report, list] = await Promise.all([
        wikiApi.lint(baseId),
        wikiApi.listIssues(baseId).catch(() => [] as WikiIssue[]),
      ])
      setLint(report)
      setIssues(list)
    } catch (e) {
      showToast(`Lint 失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    }
  }, [baseId, showToast])

  // ── 树构建 ────────────────────────────────────────────────────
  /**
   * ⭐ 按 tab 过滤侧栏可见的页面（对齐源站的「activeGroup.pages」语义）。
   *
   * <p>源站 `setActiveTab()`（WikiBrowser.vue:1777）按 tab 重新拉一份列表，
   * 树的 `v-for` 数据源是 `activeTreeRows`（:330）—— 严格只含**当前 tab** 的页。
   *
   * <p>口径用 `page_type`（源站 `tabPageTypes()` 的等价）：
   * <ul>
   *   <li>知识 tab = 非 summary（entity/concept/synthesis/comparison，
   *       以及源站 typeOrder 里未知类型的 "other" 桶 —— 都是非 summary）</li>
   *   <li>摘要 tab = `page_type === 'summary'`</li>
   * </ul>
   */
  const tabPages = useMemo(
    () =>
      activeTab === 'summary'
        ? pages.filter((p) => p.page_type === 'summary')
        : pages.filter((p) => p.page_type !== 'summary'),
    [pages, activeTab]
  )
  const tree = useMemo(
    () => buildTree(folders, tabPages, query.trim()),
    [folders, tabPages, query]
  )

  // ── 内容渲染 ──────────────────────────────────────────────────
  const html = useMemo(
    () => (activePage ? renderWikiMarkdown(activePage.content) : ''),
    [activePage]
  )
  const headings = useMemo(
    () => (activePage ? extractHeadings(activePage.content) : []),
    [activePage]
  )

  /** 拦截正文里的内部链接点击。 */
  const onBodyClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const target = e.target as HTMLElement
      const a = target.closest(`a[${WIKI_LINK_ATTR}]`) as HTMLElement | null
      if (!a) return
      e.preventDefault()
      const slug = a.getAttribute(WIKI_LINK_ATTR)
      if (!slug) return
      if (!pages.some((p) => p.slug === slug)) {
        showToast(`页面不存在：${slug}`, 'info')
        return
      }
      selectPage(slug)
      setEditing(false)
      bodyRef.current?.scrollTo?.({ top: 0 })
    },
    [pages, showToast, selectPage]
  )

  // ── 操作 ──────────────────────────────────────────────────────
  const saveEdit = useCallback(async () => {
    if (!activePage) return
    try {
      const updated = await wikiApi.updatePage(baseId, activePage.slug, { content: draft })
      setPages((prev) => prev.map((p) => (p.slug === updated.slug ? updated : p)))
      setEditing(false)
      showToast('已保存', 'success')
    } catch (e) {
      showToast(`保存失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    }
  }, [activePage, baseId, draft, showToast])

  const openHistory = useCallback(async () => {
    if (!activePage) return
    try {
      const list = await wikiApi.revisions(baseId, activePage.slug)
      setRevisions(list)
      setHistoryOpen(true)
    } catch (e) {
      showToast(`读取版本失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    }
  }, [activePage, baseId, showToast])

  const doRevert = useCallback(
    async (version: number) => {
      if (!activePage) return
      try {
        const updated = await wikiApi.revert(baseId, activePage.slug, version)
        setPages((prev) => prev.map((p) => (p.slug === updated.slug ? updated : p)))
        setHistoryOpen(false)
        showToast(`已回滚到 v${version}`, 'success')
      } catch (e) {
        showToast(`回滚失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
      }
    },
    [activePage, baseId, showToast]
  )

  const createPage = useCallback(async () => {
    const title = window.prompt('新页面标题（slug 会自动生成）')
    if (!title?.trim()) return
    const type = window.prompt('类型：entity（实体）或 concept（概念）', 'entity')
    const t = type === 'concept' ? 'concept' : 'entity'
    const slug = `${t}/${title.trim().toLowerCase().replace(/\s+/g, '-')}`
    try {
      const created = await wikiApi.createPage(baseId, { slug, title: title.trim() })
      await load()
      selectPage(created.slug)
      showToast('已创建', 'success')
    } catch (e) {
      showToast(`创建失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    }
  }, [baseId, load, showToast])

  const runAutoFix = useCallback(async () => {
    try {
      const r = await wikiApi.autoFix(baseId)
      showToast(`已修复 ${r.dead_links_removed} 条死链`, 'success')
      await Promise.all([load(), loadLint()])
    } catch (e) {
      showToast(`修复失败：${e instanceof ApiError ? e.message : String(e)}`, 'info')
    }
  }, [baseId, load, loadLint, showToast])

  /**
   * 手动生成本库 Wiki（整库模式 · 不传 knowledge_id）。
   *
   * <p><b>为什么需要这个按钮</b>：Wiki 页面只由「文档入库后自动生成」产出。上传时没配
   * Wiki 模型、或那一轮生成失败，之后配好模型也<b>没有补生成入口</b> ——
   * 只能整篇重新解析（重跑分块与索引，成本高且块 ID 会变）。
   *
   * <p>⚠️ `status` 必须分三支处理（`skipped` 不是成功）：
   * 后端在「没有已解析完成的文档」「正在生成」时返回 HTTP 200 + `skipped`，
   * 当成成功会让用户以为页面生成了。
   */
  const rebuildWiki = useCallback(async () => {
    setRebuildingWiki(true)
    try {
      const r = await wikiApi.rebuild(baseId)
      if (r.status === 'done') {
        showToast(
          `Wiki 生成完成：新建 ${r.created_pages.length} 页 / 更新 ${r.updated_pages.length} 页`,
          'success'
        )
        await load()
      } else if (r.status === 'skipped') {
        // ⭐ 跳过要说清原因，否则用户以为成功了
        showToast(r.detail || '已跳过', 'info')
      } else {
        showToast(`生成失败：${r.detail || '未知错误'}`, 'info')
      }
    } catch (e) {
      // 400（未配置模型）/ 404（库或文档不存在）走这里
      showToast(`生成失败：${e instanceof ApiError ? e.userMessage() : String(e)}`, 'info')
    } finally {
      setRebuildingWiki(false)
    }
  }, [baseId, load, showToast])

  /* ⚠️ 图谱数据不再由本面板拼：`KbWikiGraph` 自己调 `GET /wiki/graph`
     （节点/边由后端给，`linkCount` 也在组件里按有向边现算）。
     早先这里从 `pages + out_links` 拼了一份仅供旧 canvas 画布用的输入，
     换实现后它就是死代码 ⇒ 已删。 */

  // ══════════════════════════════════════════════════════════════
  // 渲染
  // ══════════════════════════════════════════════════════════════

  if (loading) {
    /* 源站 .wiki-reader-empty（居中纵向空态容器），文字用 .wiki-empty-desc。
       ⭐ 文案照源站 i18n `knowledgeEditor.wikiBrowser.loading` = '加载中…'。 */
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-content">
            <div className="wiki-reader">
              <div className="wiki-reader-inner">
                <div className="wiki-reader-empty">
                  <p className="wiki-empty-desc">加载中…</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }
  if (error) {
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-content">
            <div className="wiki-reader">
              <div className="wiki-reader-inner">
                <div className="wiki-reader-empty">
                  <div className="wiki-empty-icon" aria-hidden="true">!</div>
                  <p className="wiki-empty-title">Wiki 加载失败</p>
                  <p className="wiki-empty-desc">{error}</p>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: 8,
                      marginTop: 16,
                    }}
                  >
                    <button
                      type="button"
                      className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                      onClick={() => void load()}
                    >
                      <span className="t-button__text">重试</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    /* 源站层级（KnowledgeBase.vue:2525 + WikiBrowser.vue:2/201-425/694）：
         .wiki-main-area > .wiki-browser > (.wiki-sidebar + .wiki-content > .wiki-reader > .wiki-reader-inner)
       `.wiki-browser` 是 flex row，`.wiki-content` 是 flex column —— 少一层或层级颠倒样式会静默不生效。 */
    <div className="wiki-main-area">
      <div className="wiki-browser" style={{ position: 'relative' }}>
        {/* ── 左：目录（源站 .wiki-sidebar，280px） ─────────────────
            ⚠️ 图谱视图**没有**侧栏：设计稿 04 的 DOM 是
            `.wiki-main-area > .wiki-browser > .wiki-graph`（markup:204-279），
            画布独占整宽。留着 280px 侧栏会让真机 1604 的画布变成 1324，
            力导向装箱与初始视口都会跟着变。 */}
        {view !== 'graph' && (
        <aside className="wiki-sidebar">
          <div className="wiki-sidebar-header">
            {/* 搜索：设计稿 03 页 `.wiki-sidebar-header` 里**只有**搜索框（t-input），
                没有 tab 条。⛔ 早期版本把「页面/图谱/检查」视图切换塞在这里，与设计稿不符。
                搜索框用 `.t-input__wrap > .t-input > .t-input__inner` 三层手写（本仓无 TDesign 组件）。 */}
            <div className="t-input__wrap">
              <div className="t-input t-input--prefix">
                <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
                <input
                  className="t-input__inner"
                  placeholder="搜索 Wiki 页面..."
                  aria-label="搜索页面"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </div>
            </div>
          </div>

          {/* 目录树（设计稿 03 页层级：`.wiki-page-list > .wiki-tree-panel > (.wiki-tab-bar + 列表)`） */}
          <div className="wiki-page-list">
            {/* ⭐ 索引入口（照源站 WikiBrowser.vue:247-251）：
                源站把「索引」作为**置顶的独立入口**（不是 tab 的一员），DOM：
                  <div class="wiki-nav-item" :class="{active: activeSystemView==='index'}" @click="openIndexView">
                    <t-icon name="catalog" class="wiki-nav-icon" />
                    <span class="wiki-nav-text">{{ $t('...indexTitle') }}</span>
                  </div>
                  <div class="wiki-sidebar-divider" v-if="indexAvailable"></div>
                ⛔ 源站这个入口在**搜索结果模式之外**（搜索时隐藏），本仓无独立搜索结果视图，故常驻。 */}
            <div
              className={`wiki-nav-item${systemView === 'index' ? ' active' : ''}`}
              onClick={() => openIndexView()}
              title="索引"
            >
              <svg className="t-icon t-icon-catalog wiki-nav-icon" aria-hidden="true">
                <use href="#t-icon-catalog" />
              </svg>
              <span className="wiki-nav-text">索引</span>
            </div>
            <div className="wiki-sidebar-divider" />
            {/* ⭐ 设计稿 03 页侧栏的 tab 是**内容分类**「知识 15 / 摘要 1」
                （`window.__WIKI.tabs = [{key:knowledge,label:知识,count:15},{key:summary,label:摘要,count:1}]`），
                ⛔ 不是视图切换。右侧 `.wiki-tab-bar-actions` 是「树形/列表视图切换 + 新建目录 + 新建页面」。 */}
            <div className="wiki-tab-bar">
              <div className="wiki-tab-bar-scroll" role="tablist" aria-label="内容分类">
                {/* ⭐ 照源站 WikiBrowser.vue:260-262 —— tab 是**内容分类**筛选器：
                    `@click="setActiveTab(tab.type)"` + `:class="['wiki-tab',{active: activeTab===tab.type}]"`。
                    ⚠️ 源站的 `setActiveTab` 只换侧栏列表，**不改动右侧已选中的页**。 */}
                <div
                  role="tab"
                  aria-selected={activeTab === 'knowledge'}
                  className={`wiki-tab${activeTab === 'knowledge' ? ' active' : ''}`}
                  title="全部知识页面"
                  onClick={() => setActiveTab('knowledge')}
                >
                  <span className="wiki-tab-label">知识</span>
                  {/* 计数口径照源站 `statTotal()`（WikiBrowser.vue:1319-1324）：
                      知识 tab = 四类非 summary 的求和，**不含** summary 页。 */}
                  <span className="wiki-tab-count">
                    {pages.filter((p) => p.page_type !== 'summary').length}
                  </span>
                </div>
                <div
                  role="tab"
                  aria-selected={activeTab === 'summary'}
                  className={`wiki-tab${activeTab === 'summary' ? ' active' : ''}`}
                  title="摘要页"
                  onClick={() => setActiveTab('summary')}
                >
                  <span className="wiki-tab-label">摘要</span>
                  {/* 口径对齐源站 `page_type`（原来是 slug 前缀，本机两者等价但源站用 page_type）。 */}
                  <span className="wiki-tab-count">
                    {pages.filter((x) => x.page_type === 'summary').length}
                  </span>
                </div>
              </div>
              <div className="wiki-tab-bar-actions">
                {/* 树形 / 列表视图切换（设计稿 `.wiki-view-toggle`） */}
                <div className="wiki-view-toggle" role="group" aria-label="切换目录视图">
                  <button
                    type="button"
                    className={`wiki-view-toggle-btn${listMode === 'tree' ? ' active' : ''}`}
                    aria-pressed={listMode === 'tree'}
                    aria-label="树形视图"
                    title="树形视图"
                    onClick={() => setListMode('tree')}
                  >
                    ☰
                  </button>
                  <button
                    type="button"
                    className={`wiki-view-toggle-btn${listMode === 'list' ? ' active' : ''}`}
                    aria-pressed={listMode === 'list'}
                    aria-label="列表视图"
                    title="列表视图"
                    onClick={() => setListMode('list')}
                  >
                    ≡
                  </button>
                </div>
                <button
                  type="button"
                  className="wiki-tab-bar-action"
                  title="刷新"
                  aria-label="刷新"
                  onClick={() => void load()}
                >
                  ⟳
                </button>
                {/* 知识库级操作（⛔ 不是页面级）：补生成 Wiki。
                    本仓特有 —— 源站 Wiki 只在入库后自动生成，没有手动补生成入口。
                    ⚠️ 放在左栏顶部而不是页面工具条：工具条里 4 个按钮都是「当前页面」的操作，
                    混入一个知识库级操作会产生歧义。 */}
                <button
                  type="button"
                  className="wiki-tab-bar-action"
                  title="重新生成本库 Wiki（整库，可能需要几分钟）"
                  aria-label="生成本库 Wiki"
                  disabled={rebuildingWiki}
                  onClick={() => void rebuildWiki()}
                >
                  {rebuildingWiki ? '…' : '✧'}
                </button>
                <button
                  type="button"
                  className="wiki-tab-bar-action"
                  title="新建页面"
                  aria-label="新建页面"
                  onClick={() => void createPage()}
                >
                  ＋
                </button>
              </div>
            </div>
            {view === 'tree' &&
              (tree.length === 0 ? (
                <div className="wiki-empty-state">
                  <p className="wiki-empty-desc">{query.trim() ? '没有匹配的页面' : '还没有页面'}</p>
                </div>
              ) : (
                <div className="wiki-tree-panel">
                  <TreeList
                    items={tree}
                    activeSlug={activeSlug}
                    onPick={(slug) => {
                      selectPage(slug)
                      setEditing(false)
                    }}
                  />
                </div>
              ))}
            {/* ⚠️ 图谱视图的状态卡**不在这里**：
                设计稿 04 的「N / M 个节点」卡在图例底部（`.wiki-graph-legend > .wiki-graph-status-card`），
                由 KbWikiGraph 自己渲染。侧栏再画一份就是自造的第二份 UI。 */}
          </div>
        </aside>
        )}

        {/* ── 右：内容区（源站 .wiki-content > .wiki-reader） ──────── */}
        <div className="wiki-content">
          {view === 'tree' && (
            <div className="wiki-reader">
              <div className="wiki-reader-inner">
                {systemView === 'index' ? (
                  /* ⭐ 索引视图（照源站 WikiBrowser.vue:653-677）：
                       .wiki-reader-header > h2.wiki-reader-title「索引」+ .wiki-reader-meta > t-tag「分类目录」
                       .wiki-reader-body.wiki-index-body（markdown 渲染，[[链接]] 可点）
                     源站索引是**系统视图**，与 selectedPage 互斥。 */
                  <>
                    <div className="wiki-reader-header">
                      <div className="wiki-reader-title-row">
                        <div className="wiki-reader-title-block">
                          <h2 className="wiki-reader-title">
                            <span className="wiki-reader-title-text">索引</span>
                          </h2>
                        </div>
                        <div className="wiki-reader-aside">
                          <div className="wiki-reader-meta">
                            <span className="t-tag t-tag--default t-tag--light t-size-s">
                              <span className="t-tag__text">分类目录</span>
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                    {indexLoading && !indexData ? (
                      <div className="wiki-reader-empty">
                        <p className="wiki-empty-title">加载中…</p>
                      </div>
                    ) : indexData ? (
                      <>
                        <div
                          ref={bodyRef}
                          className="wiki-reader-body wiki-index-body"
                          onClick={onBodyClick}
                          dangerouslySetInnerHTML={{
                            __html: renderWikiMarkdown(
                              [indexData.intro, indexData.toc].filter(Boolean).join('\n\n')
                            ),
                          }}
                        />
                      </>
                    ) : (
                      <div className="wiki-reader-empty">
                        <p className="wiki-empty-title">暂无 Wiki 页面，请先上传文档</p>
                      </div>
                    )}
                  </>
                ) : !activePage ? (
                  <WikiEmpty
                    hasPages={pages.length > 0}
                    rebuilding={rebuildingWiki}
                    onRebuild={() => void rebuildWiki()}
                  />
                ) : editing ? (
                  /* 源站 .wiki-page-editor > .wiki-edit-field--content > .t-textarea > .t-textarea__inner */
                  <div className="wiki-page-editor">
                    <div className="wiki-edit-field wiki-edit-field--content">
                      <div className="t-textarea">
                        <textarea
                          className="t-textarea__inner"
                          value={draft}
                          onChange={(e) => setDraft(e.target.value)}
                          spellCheck={false}
                          aria-label="Markdown 正文"
                          style={{ minHeight: 420 }}
                        />
                      </div>
                    </div>
                    <div className="wiki-reader-actions" role="toolbar" aria-label="编辑操作">
                      <button
                        type="button"
                        className="t-button t-button--variant-base t-button--theme-primary t-size-s"
                        onClick={() => void saveEdit()}
                      >
                        <span className="t-button__text">保存</span>
                      </button>
                      <button
                        type="button"
                        className="t-button t-button--variant-text t-button--theme-default t-size-s"
                        onClick={() => setEditing(false)}
                      >
                        <span className="t-button__text">取消</span>
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    {/* 源站 .wiki-reader-header > .wiki-reader-title-row > (title-block + aside) */}
                    <div className="wiki-reader-header">
                      <div className="wiki-reader-title-row">
                        <div className="wiki-reader-title-block">
                          <h2 className="wiki-reader-title">
                            <span className="wiki-reader-title-text">
                              {activePage.title || activePage.slug}
                            </span>
                          </h2>
                          <div className="wiki-reader-title-badges wiki-reader-title-badges--secondary">
                            <span className="wiki-badge wiki-badge--type">
                              {WIKI_PAGE_TYPE_LABEL[activePage.page_type] ?? activePage.page_type}
                            </span>
                            <span className="wiki-badge wiki-badge--ver">v{activePage.version}</span>
                            {/* 本仓特有：源站标题下不显示 slug */}
                            <span className="wiki-badge" title={activePage.slug}>
                              {activePage.slug}
                            </span>
                          </div>
                          {activePage.summary && (
                            <p className="wiki-reader-lead">{activePage.summary}</p>
                          )}
                        </div>
                        <div className="wiki-reader-aside">
                          <div className="wiki-reader-actions" role="toolbar" aria-label="页面操作">
                            {/* ⚠️ 本仓特有的两个视图入口：设计稿 03 页没有「图谱视图 / 检查」，
                                但这两个是本仓真实功能（页面链接图 / Wiki 健康检查）。
                                ⛔ 不能因为设计稿没有就删掉功能 —— 放在页面操作区，
                                用源站同族按钮样式，不新造类。 */}
                            <button
                              type="button"
                              className="wiki-action-btn"
                              title="页面图谱"
                              aria-label="页面图谱"
                              aria-pressed={false}
                              onClick={() => setView((v) => (v === 'graph' ? 'tree' : 'graph'))}
                            >
                              ◍
                            </button>
                            <button
                              type="button"
                              className="wiki-action-btn"
                              title="Wiki 检查"
                              aria-label="Wiki 检查"
                              aria-pressed={false}
                              onClick={() => {
                                setView((v) => {
                                  const next = v === 'lint' ? 'tree' : 'lint'
                                  if (next === 'lint') void loadLint()
                                  return next
                                })
                              }}
                            >
                              ⚠
                            </button>
                            <button
                              type="button"
                              className="wiki-action-btn"
                              title="编辑"
                              aria-label="编辑"
                              onClick={() => {
                                setDraft(activePage.content)
                                setEditing(true)
                              }}
                            >
                              ✎
                            </button>
                            <button
                              type="button"
                              className="wiki-action-btn"
                              title="版本历史"
                              aria-label="版本历史"
                              onClick={() => void openHistory()}
                            >
                              ⟲
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* 正文：源站类名是 .wiki-reader-body（WikiBrowser.vue:597 / v-html="renderedContent"）。
                        ⛔ 派单映射表里给的 markdown 正文类名在源站全文 0 命中，
                        本仓三份 kb CSS 里也没有它的规则 ⇒ 按源站真类名落。 */}
                    <div
                      ref={bodyRef}
                      className="wiki-reader-body"
                      onClick={onBodyClick}
                      // ⚠️ 已由 renderWikiMarkdown 做转义 + 标签白名单（见 utils/wikiMarkdown）
                      dangerouslySetInnerHTML={{ __html: html }}
                    />

                    {/* 大纲：本仓特有（源站阅读区没有目录），用源站链接块
                        .wiki-reader-links > .wiki-link-group 承载，标题横向排列 */}
                    {headings.length > 0 && (
                      <div className="wiki-reader-links">
                        <div className="wiki-link-group">
                          <span className="wiki-link-label">大纲</span>
                        </div>
                        <div className="wiki-link-group">
                          {headings.map((h, i) => (
                            <span
                              key={`${h.anchor}-${i}`}
                              className="wiki-link-label"
                              style={{ paddingLeft: (h.level - 1) * 10 }}
                            >
                              {h.text}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* 页脚：源站 .wiki-reader-footer —— 反向链接（in_links）+ 证据块（chunk_refs） */}
                    <footer className="wiki-reader-footer">
                      <div className="wiki-reader-footer-row">
                        <span className="wiki-reader-footer-label">反向链接</span>
                        <span className="wiki-reader-footer-value">
                          {(activePage.in_links ?? []).length > 0 && (
                            <span className="t-tag t-tag--default t-tag--light t-size-s">
                              <span className="t-tag__text">{activePage.in_links.length}</span>
                            </span>
                          )}
                          {(activePage.in_links ?? []).length === 0 ? (
                            <span className="wiki-reader-meta-text">还没有其它页面链接到这里</span>
                          ) : (
                            (activePage.in_links ?? []).map((slug) => (
                              <a
                                key={slug}
                                href="#"
                                className="wiki-content-link"
                                onClick={(e) => {
                                  e.preventDefault()
                                  selectPage(slug)
                                }}
                              >
                                {titleOf(pages, slug)}
                              </a>
                            ))
                          )}
                        </span>
                      </div>
                      {(activePage.chunk_refs ?? []).length > 0 && (
                        <div className="wiki-reader-footer-row">
                          <span className="wiki-reader-footer-label">证据块</span>
                          <span className="wiki-reader-footer-value">
                            <span className="t-tag t-tag--default t-tag--light t-size-s">
                              <span className="t-tag__text">{activePage.chunk_refs.length}</span>
                            </span>
                            {(activePage.chunk_refs ?? []).slice(0, 12).map((c) => (
                              <code key={c} className="wiki-link-tag">
                                {c.slice(0, 10)}
                              </code>
                            ))}
                          </span>
                        </div>
                      )}
                    </footer>
                  </>
                )}
              </div>
            </div>
          )}

          {/* ── 图谱视图（源站层级：.wiki-browser > .wiki-graph > .wiki-graph-canvas） ──
               ⭐ 2026-11-09：整块换成设计稿 04 的 SVG 力导向图（`KbWikiGraph`）。
               旧实现 `KnowledgeGraphCanvas` 是 canvas + 固定坐标 + 自造发光动画，
               数据也是「文档抽出的实体」而不是 Wiki 页面 —— 用户明确指出接错。 */}
          {view === 'graph' && <KbWikiGraph baseId={baseId} pages={pages} />}

          {/* ── Lint 视图（复用源站「全局问题」的 .wiki-issue-popup-* 列表形态） ── */}
          {view === 'lint' && (
            <div className="wiki-reader">
              <div className="wiki-reader-inner">
                {!lint ? (
                  <div className="wiki-reader-empty">
                    <p className="wiki-empty-title">正在检查…</p>
                  </div>
                ) : (
                  <>
                    <div className="wiki-reader-header">
                      <div className="wiki-reader-title-row">
                        <div className="wiki-reader-title-block">
                          <h2 className="wiki-reader-title">
                            <span className="wiki-reader-title-text">健康检查</span>
                          </h2>
                          <div className="wiki-reader-title-badges wiki-reader-title-badges--secondary">
                            <span
                              className={`t-tag t-tag--light t-size-s ${
                                healthTone(lint.health_score) === 'ok'
                                  ? 't-tag--success'
                                  : healthTone(lint.health_score) === 'warn'
                                    ? 't-tag--warning'
                                    : 't-tag--danger'
                              }`}
                            >
                              <span className="t-tag__text">健康分 {lint.health_score}</span>
                            </span>
                          </div>
                          {lint.summary && <p className="wiki-reader-lead">{lint.summary}</p>}
                        </div>
                        <div className="wiki-reader-aside">
                          <div className="wiki-reader-actions" role="toolbar" aria-label="检查操作">
                            <button
                              type="button"
                              className="t-button t-button--variant-text t-button--theme-default t-size-s"
                              onClick={() => void runAutoFix()}
                              disabled={lint.issues.every((i) => !i.auto_fixable)}
                            >
                              <span className="t-button__text">自动修复</span>
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>

                    {lint.issues.length === 0 ? (
                      <div className="wiki-reader-empty">
                        <div className="wiki-empty-icon" aria-hidden="true">✓</div>
                        <p className="wiki-empty-title">没有发现问题</p>
                        <p className="wiki-empty-desc">页面结构、链接与引用都正常</p>
                      </div>
                    ) : (
                      /* 源站全局问题列表：.wiki-issue-popup-list > .wiki-issue-popup-item > main */
                      <div className="wiki-issue-popup-list">
                        {lint.issues.map((issue, i) => (
                          <div
                            className="wiki-issue-popup-item"
                            key={`${issue.type}-${issue.page_slug}-${i}`}
                          >
                            <div className="wiki-issue-popup-main">
                              <div className="wiki-issue-popup-tags">
                                <span
                                  className={`t-tag t-tag--light t-size-s ${
                                    issue.severity === 'error'
                                      ? 't-tag--danger'
                                      : issue.severity === 'warning'
                                        ? 't-tag--warning'
                                        : 't-tag--primary'
                                  }`}
                                >
                                  <span className="t-tag__text">
                                    {WIKI_SEVERITY_LABEL[issue.severity] ?? issue.severity}
                                  </span>
                                </span>
                                <span className="t-tag t-tag--default t-tag--light t-size-s">
                                  <span className="t-tag__text">
                                    {WIKI_ISSUE_TYPE_LABEL[issue.type] ?? issue.type}
                                  </span>
                                </span>
                              </div>
                              <div className="wiki-issue-popup-desc">{issue.description}</div>
                              <div className="wiki-issue-popup-meta">
                                <span className="wiki-issue-popup-reporter">
                                  {issue.auto_fixable ? '可自动修复' : ''}
                                </span>
                                <div className="wiki-issue-popup-actions">
                                  <button
                                    type="button"
                                    className="wiki-issue-popup-action"
                                    style={{
                                      border: 0,
                                      background: 'transparent',
                                      fontFamily: 'inherit',
                                      padding: 0,
                                    }}
                                    onClick={() => {
                                      if (pages.some((p) => p.slug === issue.page_slug)) {
                                        selectPage(issue.page_slug)
                                        setView('tree')
                                      }
                                    }}
                                  >
                                    查看
                                  </button>
                                </div>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {issues.length > 0 && (
                      <>
                        <div className="wiki-reader-header" style={{ marginTop: 24 }}>
                          <h2 className="wiki-reader-title">
                            <span className="wiki-reader-title-text">
                              已记录的问题（{issues.length}）
                            </span>
                          </h2>
                        </div>
                        <div className="wiki-issue-popup-list">
                          {issues.slice(0, 20).map((it) => (
                            <div className="wiki-issue-popup-item" key={it.id}>
                              <div className="wiki-issue-popup-main">
                                <div className="wiki-issue-popup-tags">
                                  <span
                                    className={`t-tag t-tag--light t-size-s ${
                                      it.severity === 'error'
                                        ? 't-tag--danger'
                                        : it.severity === 'warning'
                                          ? 't-tag--warning'
                                          : 't-tag--primary'
                                    }`}
                                  >
                                    <span className="t-tag__text">{it.status}</span>
                                  </span>
                                  <span className="t-tag t-tag--default t-tag--light t-size-s">
                                    <span className="t-tag__text">
                                      {WIKI_ISSUE_TYPE_LABEL[it.issue_type] ?? it.issue_type}
                                    </span>
                                  </span>
                                </div>
                                <div className="wiki-issue-popup-desc">{it.description}</div>
                                <div className="wiki-issue-popup-meta">
                                  <span className="wiki-issue-popup-reporter" />
                                  <div className="wiki-issue-popup-actions">
                                    <select
                                      className="t-input"
                                      aria-label="问题状态"
                                      value={it.status}
                                      onChange={(e) => {
                                        void wikiApi
                                          .updateIssueStatus(baseId, it.id, e.target.value)
                                          .then((u) =>
                                            setIssues((prev) =>
                                              prev.map((x) => (x.id === u.id ? u : x))
                                            )
                                          )
                                          .catch((err) =>
                                            showToast(
                                              `更新失败：${
                                                err instanceof ApiError ? err.message : String(err)
                                              }`,
                                              'info'
                                            )
                                          )
                                      }}
                                    >
                                      <option value="pending">待处理</option>
                                      <option value="resolved">已解决</option>
                                      <option value="ignored">忽略</option>
                                    </select>
                                  </div>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      </>
                    )}
                  </>
                )}
              </div>
            </div>
          )}
        </div>

        {/* ── 版本历史（源站 WikiRevisionDrawer：t-drawer + .wiki-revision-drawer）
            ⚠️ 源站 t-drawer 默认 attach:body ⇒ position:fixed（相对视口）。
            本仓挂在 `.kb-scene-panel` 弹窗里，fixed 会跑到弹窗外面
            ⇒ 用源站同款修饰类 `.t-drawer--attach`（kb-tdesign.css → position:absolute），
            定位基准是上一层 `.wiki-browser`（行内 position:relative）。 ── */}
        {historyOpen && (
          <div
            className="wiki-revision-drawer t-drawer t-drawer--open t-drawer--right t-drawer--attach"
            onClick={() => setHistoryOpen(false)}
          >
            <div className="t-drawer__mask" />
            <div
              className="t-drawer__content-wrapper t-drawer__content-wrapper--right"
              style={{ width: 760, maxWidth: '100vw', transform: 'none' }}
              role="dialog"
              aria-modal="true"
              aria-label="版本历史"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="t-drawer__header">
                <div className="setting-drawer__header-block">
                  <div className="setting-drawer__header">
                    <span className="setting-drawer__header-icon" aria-hidden="true">⟲</span>
                    <div className="setting-drawer__header-text">
                      <div className="setting-drawer__title">版本历史 · {activePage?.title}</div>
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  className="t-drawer__close-btn"
                  title="关闭"
                  aria-label="关闭"
                  onClick={() => setHistoryOpen(false)}
                >
                  ✕
                </button>
              </div>
              <div className="t-drawer__body">
                <div className="setting-drawer__body">
                  {revisions.length === 0 ? (
                    <div className="wiki-rev-empty">还没有历史版本</div>
                  ) : (
                    /* 源站 WikiRevisionDrawer.vue:8-42 —— .wiki-rev-layout > .wiki-rev-list > .wiki-rev-list-items。
                       `.wiki-rev-layout` 提供 flex:1 + min-height:0，`.wiki-rev-list-items` 才会内部滚动；
                       缺这一层列表会被撑高而不出滚动条。
                       ⚠️ 本仓没有「选中版本看 diff」的第二栏（.wiki-rev-detail），
                       回滚按钮直接放在每一项里。 */
                    <div className="wiki-rev-layout">
                      <div className="wiki-rev-list" style={{ width: '100%', borderRight: 0 }}>
                        <div className="wiki-rev-list-items">
                          {revisions.map((r) => (
                            <div className="wiki-rev-item" key={r.id}>
                              <div className="wiki-rev-item-primary">
                                <span className="wiki-rev-version">v{r.version}</span>
                                {r.version === activePage?.version && (
                                  <span className="wiki-rev-current-label">当前</span>
                                )}
                                {r.version !== activePage?.version && (
                                  <button
                                    type="button"
                                    className="t-button t-button--variant-text t-button--theme-primary t-size-s"
                                    onClick={() => void doRevert(r.version)}
                                  >
                                    <span className="t-button__text">回滚</span>
                                  </button>
                                )}
                              </div>
                              <div className="wiki-rev-item-secondary">
                                <span>
                                  {r.edit_source} · {(r.content ?? '').length} 字符
                                </span>
                                <span className="wiki-rev-time">{r.edited_at ?? '—'}</span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

// ══════════════════════════════════════════════════════════════
// 子组件 / 工具
// ══════════════════════════════════════════════════════════════

/**
 * 空态（源站 .wiki-reader-empty > .wiki-empty-icon/.wiki-empty-title/.wiki-empty-desc）。
 *
 * <p>⭐⭐ <b>「生成本库 Wiki」按钮是本批新增的补生成入口</b>：Wiki 页面原本只由文档入库后
 * 自动生成（需要配好 Wiki 生成模型）。上传时没配模型、或那一轮生成失败，用户此前
 * <b>没有任何补生成入口</b> —— 只能整篇重新解析。
 *
 * <p>⚠️ 文案不承诺「一定能生成」：没配模型时后端返回 400，按钮会以 toast 说明原因。
 */
function WikiEmpty({
  hasPages,
  rebuilding,
  onRebuild,
}: {
  hasPages: boolean
  /** 是否正在生成（禁用按钮 + 改文案，避免「点了没反应」）。 */
  rebuilding?: boolean
  /** 点击「生成本库 Wiki」。 */
  onRebuild?: () => void
}) {
  return (
    <div className="wiki-reader-empty">
      <div className="wiki-empty-icon" aria-hidden="true">
        {hasPages ? '◍' : '＋'}
      </div>
      {/* ⭐ 文案照抄源站 i18n（zh-CN.ts:5824-5826）：
          emptyTitle='暂无 Wiki 页面' / emptyDesc='上传文档并启用 Wiki 后将自动生成知识页面'
          / selectPageHint='从左侧选择一个页面查看内容'。
          源站判据（WikiBrowser.vue:1357-1362 `hasContentPages`）：任一类型桶非空 ⇒ 有内容页。 */}
      <p className="wiki-empty-title">
        {hasPages ? '从左侧选择一个页面查看内容' : '暂无 Wiki 页面'}
      </p>
      {!hasPages && (
        <p className="wiki-empty-desc">上传文档并启用 Wiki 后将自动生成知识页面</p>
      )}
      {/* ⭐ 空库时把「手动生成」做成可点的入口：原来这里只有一句「点右上角」，
          用户在空态里找不到路 —— 而生成失败 / 没配模型的库里，这里就是唯一的补救入口 */}
      {!hasPages && onRebuild && (
        <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'center' }}>
          <button
            type="button"
            className="t-button t-button--variant-base t-button--theme-primary t-size-m"
            disabled={rebuilding}
            onClick={onRebuild}
          >
            <span className="t-button__text">
              {rebuilding ? '生成中…（整库可能需要几分钟）' : '生成本库 Wiki'}
            </span>
          </button>
        </div>
      )}
    </div>
  )
}

/** 递归树。 */
function TreeList({
  items,
  activeSlug,
  onPick,
}: {
  items: TreeItem[]
  activeSlug: string | null
  onPick: (slug: string) => void
}) {
  return (
    <div className="wiki-tree-list">
      {items.map((item) => (
        <div key={item.key}>
          {item.kind === 'folder' ? (
            <>
              {/* 源站 .wiki-directory-item，缩进靠 `--wiki-tree-depth` 自定义属性
                  （类里是 calc(depth × --wiki-tree-depth-indent + --wiki-list-inset-x)） */}
              <div
                className="wiki-directory-item"
                style={{ ['--wiki-tree-depth' as string]: item.depth }}
              >
                <span className="wiki-directory-toggle" aria-hidden="true">
                  📁
                </span>
                <span className="wiki-directory-title" title={item.label}>
                  {item.label}
                </span>
              </div>
              <TreeList items={item.children} activeSlug={activeSlug} onPick={onPick} />
            </>
          ) : (
            /* 源站 .wiki-page-item.wiki-page-item--tree（active 状态由 `.wiki-page-item.active` 驱动）。
               ⚠️ `.wiki-page-item--tree` 只声明 display/height/padding 等，不重置 button 的
               border/background/font-family ⇒ 这三项必须行内补，否则出现浏览器默认灰框。 */
            <button
              type="button"
              className={`wiki-page-item wiki-page-item--tree${item.slug === activeSlug ? ' active' : ''}`}
              style={{
                ['--wiki-tree-depth' as string]: item.depth,
                width: '100%',
                border: 0,
                background: 'transparent',
                fontFamily: 'inherit',
                textAlign: 'left',
              }}
              onClick={() => item.slug && onPick(item.slug)}
              title={item.slug}
            >
              <span className="wiki-page-file-icon" aria-hidden="true">
                {pageTypeIcon(item.pageType)}
              </span>
              <span className="wiki-page-item-title">{item.label}</span>
            </button>
          )}
        </div>
      ))}
    </div>
  )
}

/** 构建树：目录层级 + 未归属页面放根。 */
function buildTree(folders: WikiFolder[], pages: WikiPage[], keyword: string): TreeItem[] {
  const matched = keyword
    ? pages.filter(
        (p) =>
          (p.title ?? '').toLowerCase().includes(keyword.toLowerCase()) ||
          p.slug.toLowerCase().includes(keyword.toLowerCase())
      )
    : pages

  const byFolder = new Map<string, WikiPage[]>()
  const rootPages: WikiPage[] = []
  for (const p of matched) {
    if (p.folder_id && folders.some((f) => f.id === p.folder_id)) {
      const list = byFolder.get(p.folder_id) ?? []
      list.push(p)
      byFolder.set(p.folder_id, list)
    } else {
      rootPages.push(p)
    }
  }

  // 目录按 path 排序，父在前
  const sortedFolders = [...folders].sort((a, b) => (a.path ?? '').localeCompare(b.path ?? ''))
  const childrenOf = new Map<string, WikiFolder[]>()
  for (const f of sortedFolders) {
    const parent = f.parent_id ?? ''
    const list = childrenOf.get(parent) ?? []
    list.push(f)
    childrenOf.set(parent, list)
  }

  /** 目录 → 树项（只保留有内容的目录链）。 */
  const folderItem = (f: WikiFolder): TreeItem | null => {
    const kids = (childrenOf.get(f.id) ?? [])
      .map(folderItem)
      .filter((x): x is TreeItem => x !== null)
    const ownPages = (byFolder.get(f.id) ?? []).map((p) => pageItem(p, f.depth + 1))
    if (kids.length === 0 && ownPages.length === 0) {
      return null // ⭐ 空目录不显示（避免左侧一堆死目录）
    }
    return {
      kind: 'folder',
      key: `f:${f.id}`,
      label: f.name,
      depth: f.depth,
      children: [...kids, ...ownPages],
    }
  }

  const topFolders = (childrenOf.get('') ?? [])
    .map(folderItem)
    .filter((x): x is TreeItem => x !== null)

  return [...topFolders, ...rootPages.map((p) => pageItem(p, 0))]
}

function pageItem(p: WikiPage, depth: number): TreeItem {
  return {
    kind: 'page',
    key: `p:${p.slug}`,
    label: p.title || p.slug,
    depth,
    slug: p.slug,
    pageType: p.page_type,
    children: [],
  }
}

function titleOf(pages: WikiPage[], slug: string): string {
  return pages.find((p) => p.slug === slug)?.title || slug
}

function pageTypeIcon(t?: string): string {
  switch (t) {
    case 'entity':
      return '◆'
    case 'concept':
      return '◇'
    case 'summary':
      return '📄'
    case 'index':
      return '☰'
    default:
      return '•'
  }
}
