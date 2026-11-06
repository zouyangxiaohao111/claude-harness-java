import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import {
  wikiApi,
  WIKI_ISSUE_TYPE_LABEL,
  WIKI_PAGE_TYPE_LABEL,
  WIKI_SEVERITY_LABEL,
  healthTone,
} from '@/api/wiki'
import type { WikiFolder, WikiIssue, WikiLintReport, WikiPage, WikiRevision } from '@/api/wiki'
import { extractHeadings, renderWikiMarkdown, WIKI_LINK_ATTR } from '@/utils/wikiMarkdown'
import { KnowledgeGraphCanvas } from './KnowledgeGraphCanvas'
import type { GraphEdgeInput, GraphNodeInput, GraphNodeType } from './graphTypes'

interface WikiPanelProps {
  /** 所属知识库 ID。 */
  baseId: string
  /** 知识库名（用于空态文案）。 */
  baseName?: string
  showToast: (msg: string, type?: 'success' | 'info') => void
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
export function WikiPanel({ baseId, baseName, showToast }: WikiPanelProps) {
  const [pages, setPages] = useState<WikiPage[]>([])
  const [folders, setFolders] = useState<WikiFolder[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeSlug, setActiveSlug] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [view, setView] = useState<'tree' | 'graph' | 'lint'>('tree')
  const [lint, setLint] = useState<WikiLintReport | null>(null)
  const [issues, setIssues] = useState<WikiIssue[]>([])
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [revisions, setRevisions] = useState<WikiRevision[]>([])
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
      // ⭐ 保持当前选中；没有则选第一页（避免每次刷新都跳回顶）
      setActiveSlug((cur) => (cur && ps.some((p) => p.slug === cur) ? cur : (ps[0]?.slug ?? null)))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [baseId])

  useEffect(() => {
    void load()
  }, [load])

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
  const tree = useMemo(() => buildTree(folders, pages, query.trim()), [folders, pages, query])

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
      setActiveSlug(slug)
      setEditing(false)
      bodyRef.current?.scrollTo?.({ top: 0 })
    },
    [pages, showToast]
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
      setActiveSlug(created.slug)
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

  // ── 图谱数据（从页面 + 出链映射）──────────────────────────────
  const graphNodes: GraphNodeInput[] = useMemo(
    () =>
      pages.map((p) => ({
        id: p.slug,
        name: p.title || p.slug,
        type: pageTypeToGraphType(p.page_type),
      })),
    [pages]
  )
  const graphEdges: GraphEdgeInput[] = useMemo(() => {
    const live = new Set(pages.map((p) => p.slug))
    const out: GraphEdgeInput[] = []
    for (const p of pages) {
      for (const target of p.out_links ?? []) {
        if (!live.has(target)) continue
        out.push({ id: `${p.slug}->${target}`, sourceId: p.slug, targetId: target, type: 'link' })
      }
    }
    return out
  }, [pages])

  // ══════════════════════════════════════════════════════════════
  // 渲染
  // ══════════════════════════════════════════════════════════════

  if (loading) {
    return <div className="kb-wiki-loading">正在加载 Wiki…</div>
  }
  if (error) {
    return (
      <div className="kb-err">
        Wiki 加载失败：{error}
        <button type="button" className="kb-link" onClick={() => void load()}>
          重试
        </button>
      </div>
    )
  }

  return (
    <div className="kb-wiki">
      {/* ── 顶部工具条 ─────────────────────────────────────────── */}
      <div className="kb-wiki-bar">
        <div className="kb-wiki-views">
          <button
            type="button"
            className={`kb-wiki-tab${view === 'tree' ? ' is-active' : ''}`}
            onClick={() => setView('tree')}
          >
            页面
          </button>
          <button
            type="button"
            className={`kb-wiki-tab${view === 'graph' ? ' is-active' : ''}`}
            onClick={() => setView('graph')}
          >
            图谱
          </button>
          <button
            type="button"
            className={`kb-wiki-tab${view === 'lint' ? ' is-active' : ''}`}
            onClick={() => {
              setView('lint')
              void loadLint()
            }}
          >
            检查
            {lint && lint.issues.length > 0 && (
              <span className="kb-wiki-badge">{lint.issues.length}</span>
            )}
          </button>
        </div>
        <div className="kb-wiki-actions">
          <input
            className="kb-input kb-wiki-search"
            placeholder="搜索页面…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button type="button" className="kb-btn ghost" onClick={() => void load()}>
            刷新
          </button>
          <button type="button" className="kb-btn primary" onClick={() => void createPage()}>
            ＋ 新建页面
          </button>
        </div>
      </div>

      {/* ── 三栏 ───────────────────────────────────────────────── */}
      {view === 'tree' && (
        <div className="kb-wiki-body">
          {/* 左：树 */}
          <aside className="kb-wiki-tree">
            {tree.length === 0 ? (
              <div className="kb-wiki-tree-empty">
                {query.trim() ? '没有匹配的页面' : '还没有页面'}
              </div>
            ) : (
              <TreeList
                items={tree}
                activeSlug={activeSlug}
                onPick={(slug) => {
                  setActiveSlug(slug)
                  setEditing(false)
                }}
              />
            )}
          </aside>

          {/* 中：正文 */}
          <section className="kb-wiki-main">
            {!activePage ? (
              <WikiEmpty hasPages={pages.length > 0} baseName={baseName} />
            ) : editing ? (
              <div className="kb-wiki-editor">
                <textarea
                  className="kb-wiki-textarea"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  spellCheck={false}
                />
                <div className="kb-wiki-editor-ops">
                  <button type="button" className="kb-btn primary" onClick={() => void saveEdit()}>
                    保存
                  </button>
                  <button type="button" className="kb-btn ghost" onClick={() => setEditing(false)}>
                    取消
                  </button>
                </div>
              </div>
            ) : (
              <>
                <header className="kb-wiki-head">
                  <div>
                    <h3 className="kb-wiki-title">{activePage.title || activePage.slug}</h3>
                    <div className="kb-wiki-meta">
                      <span className="kb-wiki-chip">
                        {WIKI_PAGE_TYPE_LABEL[activePage.page_type] ?? activePage.page_type}
                      </span>
                      <span className="kb-wiki-slug">{activePage.slug}</span>
                      <span className="kb-wiki-ver">v{activePage.version}</span>
                    </div>
                  </div>
                  <div className="kb-wiki-head-ops">
                    <button
                      type="button"
                      className="kb-btn ghost"
                      onClick={() => {
                        setDraft(activePage.content)
                        setEditing(true)
                      }}
                    >
                      编辑
                    </button>
                    <button type="button" className="kb-btn ghost" onClick={() => void openHistory()}>
                      版本
                    </button>
                  </div>
                </header>
                {activePage.summary && <p className="kb-wiki-summary">{activePage.summary}</p>}
                <div
                  ref={bodyRef}
                  className="kb-wiki-md"
                  onClick={onBodyClick}
                  // ⚠️ 已由 renderWikiMarkdown 做转义 + 标签白名单（见 utils/wikiMarkdown）
                  dangerouslySetInnerHTML={{ __html: html }}
                />
              </>
            )}
          </section>

          {/* 右：大纲 + 反向链接 */}
          <aside className="kb-wiki-side">
            {headings.length > 0 && (
              <div className="kb-wiki-side-block">
                <div className="kb-wiki-side-title">大纲</div>
                <ul className="kb-wiki-outline">
                  {headings.map((h, i) => (
                    <li key={`${h.anchor}-${i}`} style={{ paddingLeft: (h.level - 1) * 10 }}>
                      {h.text}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div className="kb-wiki-side-block">
              <div className="kb-wiki-side-title">
                反向链接
                <span className="kb-wiki-count">{activePage?.in_links?.length ?? 0}</span>
              </div>
              {(activePage?.in_links ?? []).length === 0 ? (
                <div className="kb-wiki-side-empty">还没有其它页面链接到这里</div>
              ) : (
                <ul className="kb-wiki-links">
                  {(activePage?.in_links ?? []).map((slug) => (
                    <li key={slug}>
                      <button type="button" className="kb-link" onClick={() => setActiveSlug(slug)}>
                        {titleOf(pages, slug)}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            {(activePage?.chunk_refs ?? []).length > 0 && (
              <div className="kb-wiki-side-block">
                <div className="kb-wiki-side-title">
                  证据块
                  <span className="kb-wiki-count">{activePage?.chunk_refs?.length}</span>
                </div>
                <div className="kb-wiki-refs">
                  {(activePage?.chunk_refs ?? []).slice(0, 12).map((c) => (
                    <code key={c} className="kb-wiki-ref">
                      {c.slice(0, 10)}
                    </code>
                  ))}
                </div>
              </div>
            )}
          </aside>
        </div>
      )}

      {/* ── 图谱视图 ───────────────────────────────────────────── */}
      {view === 'graph' && (
        <div className="kb-wiki-graph">
          {graphNodes.length === 0 ? (
            <div className="kb-wiki-tree-empty">还没有页面，无法生成图谱</div>
          ) : (
            <>
              <div className="kb-wiki-graph-note">
                共 {graphNodes.length} 个页面 / {graphEdges.length} 条链接（点节点可跳转）
              </div>
              <div className="kb-wiki-graph-canvas">
                <KnowledgeGraphCanvas
                  nodes={graphNodes}
                  edges={graphEdges}
                  onSelect={(n) => {
                    if (n && pages.some((p) => p.slug === n.id)) {
                      setActiveSlug(n.id)
                      setView('tree')
                    }
                  }}
                />
              </div>
            </>
          )}
        </div>
      )}

      {/* ── Lint 视图 ──────────────────────────────────────────── */}
      {view === 'lint' && (
        <div className="kb-wiki-lint">
          {!lint ? (
            <div className="kb-wiki-loading">正在检查…</div>
          ) : (
            <>
              <div className="kb-wiki-lint-head">
                <div className={`kb-wiki-health tone-${healthTone(lint.health_score)}`}>
                  <span className="kb-wiki-health-num">{lint.health_score}</span>
                  <span className="kb-wiki-health-label">健康分</span>
                </div>
                <div className="kb-wiki-lint-summary">{lint.summary}</div>
                <button
                  type="button"
                  className="kb-btn ghost"
                  onClick={() => void runAutoFix()}
                  disabled={lint.issues.every((i) => !i.auto_fixable)}
                >
                  自动修复
                </button>
              </div>

              {lint.issues.length === 0 ? (
                <div className="kb-empty-cards">
                  <div className="kb-empty-title">没有发现问题</div>
                  <div className="kb-empty-sub">页面结构、链接与引用都正常</div>
                </div>
              ) : (
                <div className="kb-wiki-issues">
                  {lint.issues.map((issue, i) => (
                    <div className={`kb-wiki-issue sev-${issue.severity}`} key={`${issue.type}-${issue.page_slug}-${i}`}>
                      <span className={`kb-wiki-sev sev-${issue.severity}`}>
                        {WIKI_SEVERITY_LABEL[issue.severity] ?? issue.severity}
                      </span>
                      <span className="kb-wiki-issue-type">
                        {WIKI_ISSUE_TYPE_LABEL[issue.type] ?? issue.type}
                      </span>
                      <span className="kb-wiki-issue-desc">{issue.description}</span>
                      {issue.auto_fixable && <span className="kb-wiki-fixable">可自动修复</span>}
                      <button
                        type="button"
                        className="kb-link"
                        onClick={() => {
                          if (pages.some((p) => p.slug === issue.page_slug)) {
                            setActiveSlug(issue.page_slug)
                            setView('tree')
                          }
                        }}
                      >
                        查看
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {issues.length > 0 && (
                <div className="kb-wiki-tracked">
                  <div className="kb-wiki-side-title">已记录的问题（{issues.length}）</div>
                  {issues.slice(0, 20).map((it) => (
                    <div className="kb-wiki-tracked-row" key={it.id}>
                      <span className={`kb-wiki-sev sev-${it.severity}`}>{it.status}</span>
                      <span>{WIKI_ISSUE_TYPE_LABEL[it.issue_type] ?? it.issue_type}</span>
                      <span className="kb-wiki-issue-desc">{it.description}</span>
                      <select
                        className="kb-select kb-wiki-issue-status"
                        value={it.status}
                        onChange={(e) => {
                          void wikiApi
                            .updateIssueStatus(baseId, it.id, e.target.value)
                            .then((u) =>
                              setIssues((prev) => prev.map((x) => (x.id === u.id ? u : x)))
                            )
                            .catch((err) =>
                              showToast(
                                `更新失败：${err instanceof ApiError ? err.message : String(err)}`,
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
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ── 版本历史（浮层）────────────────────────────────────── */}
      {historyOpen && (
        <div className="kb-wiki-modal-backdrop" onClick={() => setHistoryOpen(false)}>
          <div className="kb-wiki-modal" onClick={(e) => e.stopPropagation()}>
            <header className="kb-wiki-modal-head">
              <h4>版本历史 · {activePage?.title}</h4>
              <button type="button" className="kb-link" onClick={() => setHistoryOpen(false)}>
                关闭
              </button>
            </header>
            {revisions.length === 0 ? (
              <div className="kb-wiki-side-empty">还没有历史版本</div>
            ) : (
              <div className="kb-wiki-revs">
                {revisions.map((r) => (
                  <div className="kb-wiki-rev" key={r.id}>
                    <span className="kb-wiki-rev-ver">v{r.version}</span>
                    <span className="kb-wiki-rev-src">{r.edit_source}</span>
                    <span className="kb-wiki-rev-time">{r.edited_at ?? '—'}</span>
                    <span className="kb-wiki-rev-len">{(r.content ?? '').length} 字符</span>
                    {r.version !== activePage?.version && (
                      <button type="button" className="kb-link" onClick={() => void doRevert(r.version)}>
                        回滚到此版本
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// ══════════════════════════════════════════════════════════════
// 子组件 / 工具
// ══════════════════════════════════════════════════════════════

/** 空态。 */
function WikiEmpty({ hasPages, baseName }: { hasPages: boolean; baseName?: string }) {
  return (
    <div className="kb-empty-cards">
      <div className="kb-empty-title">
        {hasPages ? '选一个页面' : `“${baseName ?? '这个知识库'}”还没有 Wiki 页面`}
      </div>
      <div className="kb-empty-sub">
        {hasPages
          ? '从左侧目录里选择要看的页面'
          : 'Wiki 页面由文档入库后自动生成（需要配置 Wiki 生成模型）；也可以点右上角「新建页面」手工创建'}
      </div>
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
    <ul className="kb-wiki-tree-list">
      {items.map((item) => (
        <li key={item.key}>
          {item.kind === 'folder' ? (
            <>
              <div className="kb-wiki-folder" style={{ paddingLeft: item.depth * 12 }}>
                <span className="kb-wiki-folder-ic">📁</span>
                {item.label}
              </div>
              <TreeList items={item.children} activeSlug={activeSlug} onPick={onPick} />
            </>
          ) : (
            <button
              type="button"
              className={`kb-wiki-page${item.slug === activeSlug ? ' is-active' : ''}`}
              style={{ paddingLeft: item.depth * 12 + 8 }}
              onClick={() => item.slug && onPick(item.slug)}
              title={item.slug}
            >
              <span className="kb-wiki-page-ic">{pageTypeIcon(item.pageType)}</span>
              <span className="kb-wiki-page-label">{item.label}</span>
            </button>
          )}
        </li>
      ))}
    </ul>
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

/** 页面类型 → 图谱节点类型。 */
function pageTypeToGraphType(t: string): GraphNodeType {
  switch (t) {
    case 'index':
      return 'core'
    case 'summary':
      return 'domain'
    case 'concept':
      return 'law'
    case 'entity':
      return 'entity'
    default:
      return 'measure'
  }
}
