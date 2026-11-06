import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi, PARSE_STATUS_LABEL, PARSE_STATUS_TONE } from '@/api/knowledge'
import type { KbFolderNode, KbTag, Knowledge, SearchHit } from '@/api/knowledge'
import { KbUploadSource } from './KbUploadSource'
import { KbDocDrawer } from './KbDocDrawer'

interface KnowledgeDocPanelProps {
  baseId: string
  baseName?: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 上传按钮在页头，这里复用同一个文件输入 */
  onRequestUpload: () => void
}

type ViewMode = 'list' | 'grid'

const VIEW_MODE_KEY = 'nexusai.kb.docs.viewMode'

/** 文件大小 → 人类可读。 */
function formatSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/** 文件类型 → 中文 + 图标（对齐源侧 DocumentFileIcon 的分档）。 */
const FILE_META: Record<string, { label: string; mark: string }> = {
  pdf: { label: 'PDF', mark: '📕' },
  doc: { label: 'Word', mark: '📘' },
  docx: { label: 'Word', mark: '📘' },
  xls: { label: 'Excel', mark: '📗' },
  xlsx: { label: 'Excel', mark: '📗' },
  ppt: { label: 'PPT', mark: '📙' },
  pptx: { label: 'PPT', mark: '📙' },
  md: { label: 'Markdown', mark: '📝' },
  txt: { label: '文本', mark: '📄' },
  csv: { label: 'CSV', mark: '📊' },
  url: { label: '网页', mark: '🌐' },
}

function fileMeta(t: string | null | undefined) {
  return FILE_META[(t ?? '').toLowerCase()] ?? { label: t || '文件', mark: '📄' }
}

/** 展平文件夹树（供下拉/移动选择）。 */
export function flattenFolders(nodes: KbFolderNode[], out: KbFolderNode[] = []): KbFolderNode[] {
  for (const n of nodes) {
    out.push(n)
    if (n.children?.length) flattenFolders(n.children, out)
  }
  return out
}

/** 按路径取「面包屑链」（不含根）。 */
export function folderCrumbs(path: string): Array<{ path: string; name: string }> {
  if (!path) return []
  const parts = path.split('/').filter(Boolean)
  const out: Array<{ path: string; name: string }> = []
  let acc = ''
  for (const p of parts) {
    acc = acc ? `${acc}/${p}` : p
    out.push({ path: acc, name: p })
  }
  return out
}

/**
 * 文档区 · 对齐源侧 `KnowledgeBase.vue` 的 documents tab
 * （`KbFolderTree` + `DocumentListView` / `DocumentCardView` + 批量条 + 标签筛选）。
 *
 * <h2>⭐ 为什么单独拆出来</h2>
 * 页头（面包屑 / tab / 上传）在 `KnowledgeBasePage`，文档浏览的<b>状态机</b>
 * （当前文件夹 / 视图模式 / 勾选集合 / 筛选条件 / 标签）全在这里。
 * 混在一个 680 行的组件里，加一个筛选项就要横跨半个文件找状态。
 *
 * <h2>⚠️ 两处容易做错的语义</h2>
 * <ul>
 *   <li><b>文件夹路径是派生树</b>：后端从 `kb_knowledge.folder_path` 字符串算出来的，
 *       <b>空目录不存在</b>。⛔ 不要以为「建了文件夹但没文档」也会出现在树里。</li>
 *   <li><b>标签是「替换」不是「追加」</b>：`PUT /knowledge/tags` 传的是最终集合，
 *       合并必须在<b>客户端</b>做（见 {@link mergeTagIds}）。</li>
 * </ul>
 */
export function KnowledgeDocPanel({
  baseId,
  baseName,
  showToast,
  onRequestUpload,
}: KnowledgeDocPanelProps) {
  const [items, setItems] = useState<Knowledge[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 浏览状态
  const [folderPath, setFolderPath] = useState('')
  const [folders, setFolders] = useState<KbFolderNode[]>([])
  const [treeCollapsed, setTreeCollapsed] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    try {
      return localStorage.getItem(VIEW_MODE_KEY) === 'grid' ? 'grid' : 'list'
    } catch {
      return 'list'
    }
  })

  // 标签
  const [tags, setTags] = useState<KbTag[]>([])
  const [selectedTagIds, setSelectedTagIds] = useState<string[]>([])
  const [tagPanelOpen, setTagPanelOpen] = useState(false)
  const [tagManagerOpen, setTagManagerOpen] = useState(false)
  const [newTagName, setNewTagName] = useState('')

  // 批量
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [batchMode, setBatchMode] = useState(false)

  /**
   * 详情抽屉当前打开的文档。
   *
   * <p>⭐ 源侧点文档行打开右侧抽屉（基本信息/摘要/文件内容分块）。
   * 本仓之前点了只是「选中」，用户看不到文档内容 —— 这是「文档点开后交互」的缺口。
   */
  const [drawerDoc, setDrawerDoc] = useState<Knowledge | null>(null)

  // 语义检索
  const [searchQuery, setSearchQuery] = useState('')
  const [searchHits, setSearchHits] = useState<SearchHit[] | null>(null)
  const [searching, setSearching] = useState(false)

  const toMessage = (e: unknown): string =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  useEffect(() => {
    try {
      localStorage.setItem(VIEW_MODE_KEY, viewMode)
    } catch {
      /* 隐私模式下 localStorage 会抛，不影像功能 */
    }
  }, [viewMode])

  // 切库 ⇒ 全部浏览状态归零（否则会停在上一个库的文件夹里）
  useEffect(() => {
    setFolderPath('')
    setKeyword('')
    setStatusFilter('')
    setTypeFilter('')
    setSelectedTagIds([])
    setSelectedIds(new Set())
    setBatchMode(false)
    setSearchHits(null)
    setSearchQuery('')
    setError(null)
  }, [baseId])

  const loadItems = useCallback(
    async (opts: { silent?: boolean } = {}) => {
      if (!opts.silent) setLoading(true)
      try {
        const page = await knowledgeApi.listKnowledge(baseId, {
          page: 1,
          page_size: 200,
          keyword: keyword.trim() || undefined,
          parse_status: statusFilter || undefined,
          file_type: typeFilter || undefined,
          folder_path: folderPath || undefined,
        })
        setItems(page.data)
        setTotal(page.total)
        setError(null)
      } catch (e) {
        setError(toMessage(e))
      } finally {
        if (!opts.silent) setLoading(false)
      }
    },
    [baseId, keyword, statusFilter, typeFilter, folderPath]
  )

  const loadFolders = useCallback(async () => {
    try {
      const r = await knowledgeApi.listFolders(baseId)
      setFolders(r.folders ?? [])
    } catch {
      // 文件夹树失败不该让整个文档区不可用（降级为「无文件夹」）
      setFolders([])
    }
  }, [baseId])

  const loadTags = useCallback(async () => {
    try {
      setTags(await knowledgeApi.listTags(baseId, true))
    } catch {
      setTags([])
    }
  }, [baseId])

  useEffect(() => {
    void loadItems()
    void loadFolders()
    void loadTags()
  }, [loadItems, loadFolders, loadTags])

  // 只在有非终态条目时轮询（终态后停，避免无谓打接口）
  const hasInFlight = useMemo(
    () => items.some((k) => !['completed', 'failed', 'cancelled'].includes(k.parse_status)),
    [items]
  )
  useEffect(() => {
    if (!hasInFlight) return
    const timer = window.setInterval(() => void loadItems({ silent: true }), 2000)
    return () => window.clearInterval(timer)
  }, [hasInFlight, loadItems])

  // 标签筛选是**客户端**过滤：后端列表接口没有 tag 参数（见 api/knowledge.ts 的
  // listKnowledge 契约），所以只在这一层筛。⚠️ 这意味着 total 仍是未筛的数。
  const visibleItems = useMemo(() => {
    if (selectedTagIds.length === 0) return items
    return items.filter((k) => {
      const rowTags = tagsFor(k, tags)
      return rowTags.some((t) => selectedTagIds.includes(t.id))
    })
  }, [items, selectedTagIds, tags])

  const crumbs = folderCrumbs(folderPath)
  const subFolders = useMemo(() => findChildren(folders, folderPath), [folders, folderPath])

  const allSelected = visibleItems.length > 0 && visibleItems.every((k) => selectedIds.has(k.id))
  const someSelected = visibleItems.some((k) => selectedIds.has(k.id)) && !allSelected

  const toggleSelect = (id: string) =>
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const toggleSelectAll = () =>
    setSelectedIds((prev) => {
      if (visibleItems.every((k) => prev.has(k.id))) return new Set()
      return new Set(visibleItems.map((k) => k.id))
    })

  // ── 操作 ──────────────────────────────────────────────────────
  const runSearch = async () => {
    const q = searchQuery.trim()
    if (!q) return
    setSearching(true)
    try {
      setSearchHits(await knowledgeApi.hybridSearch(baseId, { query_text: q, match_count: 20 }))
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setSearching(false)
    }
  }

  const handleReparse = async (row: Knowledge) => {
    try {
      await knowledgeApi.reparse(row.id)
      showToast('已提交重解析', 'success')
      void loadItems()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleCancel = async (row: Knowledge) => {
    try {
      await knowledgeApi.cancelParse(row.id)
      void loadItems()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleDelete = async (row: Knowledge) => {
    if (!window.confirm(`确定删除「${row.title || row.file_name || row.id}」？`)) return
    try {
      await knowledgeApi.deleteKnowledge(row.id)
      void loadItems()
      void loadFolders()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleMove = async (row: Knowledge) => {
    const to = window.prompt('移动到文件夹（留空 = 移出文件夹）', row.folder_path ?? '')
    if (to === null) return
    try {
      await knowledgeApi.moveToFolder([row.id], to.trim())
      showToast('已移动', 'success')
      void loadItems()
      void loadFolders()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleBatchDelete = async () => {
    const ids = [...selectedIds]
    if (ids.length === 0) return
    if (!window.confirm(`确定删除选中的 ${ids.length} 个文档？`)) return
    try {
      const r = await knowledgeApi.batchDelete(ids)
      // ⚠️ 批量接口返回的是「成功 id 列表 + 失败 map」，不是计数 —— 失败必须报出来，
      //    否则用户以为全删了，实际上面板里还留着几行（刷新才看出来）。
      const failedCount = Object.keys(r.failed ?? {}).length
      showToast(`已删除 ${r.succeeded?.length ?? 0} 个${failedCount ? `，${failedCount} 个失败` : ''}`,
        failedCount ? 'info' : 'success')
      setSelectedIds(new Set())
      setBatchMode(false)
      void loadItems()
      void loadFolders()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleBatchReparse = async () => {
    const ids = [...selectedIds]
    if (ids.length === 0) return
    try {
      const r = await knowledgeApi.batchReparse(ids)
      const failedCount = Object.keys(r.failed ?? {}).length
      showToast(`已提交 ${r.succeeded?.length ?? 0} 个重解析${failedCount ? `，${failedCount} 个失败` : ''}`,
        failedCount ? 'info' : 'success')
      void loadItems()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  /** 给选中文档打标签（⭐ 替换语义，合并必须在客户端做）。 */
  const handleBatchTag = async (tagId: string) => {
    const ids = [...selectedIds]
    if (ids.length === 0) return
    try {
      const merged = mergeTagIds(selectedTagIds, tagId)
      await knowledgeApi.setKnowledgeTags(baseId, ids, merged)
      showToast('标签已更新', 'success')
      void loadItems()
      void loadTags()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleCreateTag = async () => {
    const name = newTagName.trim()
    if (!name) return
    try {
      await knowledgeApi.createTag(baseId, { name })
      setNewTagName('')
      await loadTags()
      showToast('标签已创建', 'success')
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const handleDeleteTag = async (tag: KbTag) => {
    if (!window.confirm(`删除标签「${tag.name}」？文档上的该标签也会解除。`)) return
    try {
      await knowledgeApi.deleteTag(baseId, tag.id)
      setSelectedTagIds((prev) => prev.filter((x) => x !== tag.id))
      await loadTags()
      void loadItems()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  /** 重命名/移动文件夹（源侧 `renameFolder`；子树一并移动）。 */
  const handleRenameFolder = async (node: KbFolderNode) => {
    const to = window.prompt(
      `重命名「${node.name}」（可改成 a/b 形式移到别的父目录；留空 = 取消）`,
      node.path
    )
    if (to === null) return
    const target = to.trim()
    if (!target || target === node.path) return
    try {
      const r = await knowledgeApi.renameFolder(baseId, node.path, target)
      showToast(`已重命名，影响 ${r.affected} 个文档`, 'success')
      // 当前正停在被改名的目录里 ⇒ 跟着走，否则会看到空列表
      if (folderPath === node.path || folderPath.startsWith(node.path + '/')) {
        setFolderPath(target + folderPath.slice(node.path.length))
      }
      void loadItems()
      void loadFolders()
    } catch (e) {
      showToast(toMessage(e), 'info')
    }
  }

  const filtering = !!(keyword.trim() || statusFilter || typeFilter || selectedTagIds.length)
  const activeFilterCount =
    (statusFilter ? 1 : 0) + (typeFilter ? 1 : 0) + selectedTagIds.length

  return (
    <div className="kb-docs">
      {/* ── 左：文件夹树 ─────────────────────────────────── */}
      {!treeCollapsed && (
        <aside className="kb-folder-tree" aria-label="文件夹">
          <button
            type="button"
            className={`kb-folder-item${folderPath === '' ? ' is-active' : ''}`}
            onClick={() => setFolderPath('')}
            title={baseName}
          >
            <span className="kb-folder-ic">🗂</span>
            <span className="kb-folder-name">{baseName ?? '全部文档'}</span>
            <span className="kb-folder-count">{total}</span>
          </button>
          <FolderBranch
            nodes={folders}
            current={folderPath}
            onSelect={setFolderPath}
            onRename={handleRenameFolder}
          />
          {folders.length === 0 && <div className="kb-folder-empty">暂无子文件夹</div>}
        </aside>
      )}

      {/* ── 右：文档区 ───────────────────────────────────── */}
      <div className="kb-doc-main">
        {/* 面包屑路径 + 计数 */}
        <div className="kb-doc-path">
          {treeCollapsed && (
            <button
              type="button"
              className="kb-icon-btn"
              onClick={() => setTreeCollapsed(false)}
              title="展开文件夹"
              aria-label="展开文件夹"
            >
              ☰
            </button>
          )}
          <button
            type="button"
            className="kb-crumb-link"
            onClick={() => setFolderPath('')}
            title={baseName}
          >
            {baseName ?? '全部文档'}
          </button>
          {crumbs.map((c, i) => (
            <span key={c.path} className="kb-crumb-seg">
              <span className="kb-crumb-sep">›</span>
              {i === crumbs.length - 1 ? (
                <span className="kb-crumb-current">{c.name}</span>
              ) : (
                <button type="button" className="kb-crumb-link" onClick={() => setFolderPath(c.path)}>
                  {c.name}
                </button>
              )}
            </span>
          ))}
          <span className="kb-doc-count">
            {filtering ? `筛出 ${visibleItems.length}` : `${total} 个文档`}
          </span>
          {!treeCollapsed && folders.length > 0 && (
            <button
              type="button"
              className="kb-icon-btn kb-tree-collapse"
              onClick={() => setTreeCollapsed(true)}
              title="收起文件夹"
              aria-label="收起文件夹"
            >
              ‹
            </button>
          )}
        </div>

        {/* 工具条：搜索 / 筛选 / 视图 / 批量 / 上传 */}
        <div className="kb-doc-toolbar">
          <input
            className="kb-input kb-doc-search"
            placeholder="按标题搜索…"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="kb-select"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            aria-label="按状态筛选"
          >
            <option value="">全部状态</option>
            <option value="pending">排队中</option>
            <option value="processing">解析中</option>
            <option value="completed">已完成</option>
            <option value="failed">失败</option>
            <option value="cancelled">已取消</option>
          </select>
          <select
            className="kb-select"
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            aria-label="按类型筛选"
          >
            <option value="">全部类型</option>
            {['pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'md', 'txt', 'csv'].map((t) => (
              <option key={t} value={t}>{fileMeta(t).label}</option>
            ))}
          </select>

          {/* 标签筛选 */}
          <div className="kb-tag-filter-wrap">
            <button
              type="button"
              className={`kb-btn ghost${activeFilterCount > 0 ? ' is-active' : ''}`}
              onClick={() => setTagPanelOpen((v) => !v)}
            >
              筛选{activeFilterCount > 0 ? ` · ${activeFilterCount}` : ''}
            </button>
            {tagPanelOpen && (
              <div className="kb-tag-panel">
                <div className="kb-tag-panel-head">
                  <strong>标签</strong>
                  <button type="button" className="kb-link" onClick={() => setTagManagerOpen((v) => !v)}>
                    {tagManagerOpen ? '完成' : '管理'}
                  </button>
                </div>
                <div className="kb-tag-panel-list">
                  {tags.length === 0 && <span className="kb-tag-empty">还没有标签</span>}
                  {tags.map((t) => (
                    <label key={t.id} className="kb-tag-check">
                      <input
                        type="checkbox"
                        checked={selectedTagIds.includes(t.id)}
                        onChange={() =>
                          setSelectedTagIds((prev) =>
                            prev.includes(t.id) ? prev.filter((x) => x !== t.id) : [...prev, t.id]
                          )
                        }
                      />
                      <span className="kb-tag-dot" style={{ background: t.color || '#94a3b8' }} />
                      <span className="kb-tag-name">{t.name}</span>
                      <span className="kb-tag-count">{t.knowledge_count ?? 0}</span>
                      {tagManagerOpen && (
                        <button
                          type="button"
                          className="kb-link danger"
                          onClick={(e) => { e.preventDefault(); void handleDeleteTag(t) }}
                        >
                          删除
                        </button>
                      )}
                    </label>
                  ))}
                </div>
                {tagManagerOpen && (
                  <div className="kb-tag-create">
                    <input
                      className="kb-input"
                      placeholder="新标签名"
                      value={newTagName}
                      onChange={(e) => setNewTagName(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') void handleCreateTag() }}
                    />
                    <button type="button" className="kb-btn primary" onClick={() => void handleCreateTag()}>
                      新建
                    </button>
                  </div>
                )}
                {activeFilterCount > 0 && (
                  <button
                    type="button"
                    className="kb-btn ghost"
                    onClick={() => { setStatusFilter(''); setTypeFilter(''); setSelectedTagIds([]) }}
                  >
                    清空筛选
                  </button>
                )}
              </div>
            )}
          </div>

          <div className="kb-view-toggle" role="group" aria-label="视图模式">
            <button
              type="button"
              className={`kb-icon-btn${viewMode === 'list' ? ' is-active' : ''}`}
              onClick={() => setViewMode('list')}
              title="列表"
              aria-pressed={viewMode === 'list'}
            >
              ☰
            </button>
            <button
              type="button"
              className={`kb-icon-btn${viewMode === 'grid' ? ' is-active' : ''}`}
              onClick={() => setViewMode('grid')}
              title="网格"
              aria-pressed={viewMode === 'grid'}
            >
              ▦
            </button>
          </div>

          <button
            type="button"
            className={`kb-btn ghost${batchMode ? ' is-active' : ''}`}
            onClick={() => { setBatchMode((v) => !v); setSelectedIds(new Set()) }}
            disabled={visibleItems.length === 0}
          >
            {batchMode ? '取消批量' : '批量管理'}
          </button>
          {/* ⭐ 四档来源（文件 / 文件夹 / URL / 手工）· 对齐源侧 KbUploadSourceDropdown */}
          <KbUploadSource
            baseId={baseId}
            folderPath={folderPath}
            showToast={showToast}
            onDone={() => { void loadItems(); void loadFolders() }}
          />
        </div>

        {/* 语义检索（搜文件内容，与标题搜索是两件事） */}
        <div className="kb-searchbar">
          <input
            className="kb-input kb-semantic-input"
            placeholder="在文档内容中检索…（回车执行）"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void runSearch() }}
          />
          <button
            type="button"
            className="kb-btn primary"
            disabled={searching || !searchQuery.trim()}
            onClick={() => void runSearch()}
          >
            {searching ? '检索中…' : '检索'}
          </button>
          {searchHits !== null && (
            <button type="button" className="kb-btn ghost" onClick={() => setSearchHits(null)}>
              清除结果
            </button>
          )}
        </div>

        {/* 批量操作条 */}
        {batchMode && (
          <div className="kb-batch-bar">
            <span className="kb-batch-count">已选 {selectedIds.size} 个</span>
            {tags.length > 0 && (
              <select
                className="kb-select"
                value=""
                onChange={(e) => { if (e.target.value) void handleBatchTag(e.target.value) }}
                disabled={selectedIds.size === 0}
                aria-label="批量打标签"
              >
                <option value="">打标签…</option>
                {tags.map((t) => (
                  <option key={t.id} value={t.id}>{t.name}</option>
                ))}
              </select>
            )}
            <button
              type="button"
              className="kb-btn ghost"
              disabled={selectedIds.size === 0}
              onClick={() => void handleBatchReparse()}
            >
              批量重解析
            </button>
            <button
              type="button"
              className="kb-btn ghost danger"
              disabled={selectedIds.size === 0}
              onClick={() => void handleBatchDelete()}
            >
              批量删除
            </button>
          </div>
        )}

        {error && <div className="kb-err">{error}</div>}

        {/* 检索结果 */}
        {searchHits !== null && (
          <div className="kb-hits">
            <div className="kb-hits-head">
              检索结果 <span className="kb-hits-count">{searchHits.length}</span> 条
            </div>
            {searchHits.length === 0 && (
              <div className="kb-empty-cards">
                <div className="kb-empty-title">没有匹配的片段</div>
                <div className="kb-empty-sub">换个说法试试；文档需解析完成才可被检索</div>
              </div>
            )}
            {searchHits.map((h) => (
              <div className="kb-hit" key={h.id}>
                <div className="kb-hit-head">
                  <span className="kb-hit-title">{h.knowledge_title}</span>
                  <span className={`kb-hit-badge is-${h.match_type}`}>
                    {h.match_type === 'hybrid' ? '双路命中' : h.match_type === 'vector' ? '语义' : '关键词'}
                  </span>
                  <span className="kb-hit-score">{h.score.toFixed(3)}</span>
                </div>
                {h.context_header && <div className="kb-hit-header">{h.context_header}</div>}
                <div className="kb-hit-body">{h.content}</div>
              </div>
            ))}
          </div>
        )}

        {/* 列表 / 网格 */}
        {searchHits === null && (
          <>
            {!loading && visibleItems.length === 0 && (
              <div className="kb-empty-cards">
                <div className="kb-empty-title">
                  {filtering ? '没有匹配的文档' : folderPath ? '这个文件夹还没有文档' : '这个知识库还没有文档'}
                </div>
                <div className="kb-empty-sub">
                  {filtering
                    ? '换个关键词或清空筛选试试'
                    : '上传 pdf / word / excel / ppt / md / txt 等格式的文档，解析后即可检索'}
                </div>
                {!filtering && (
                  <button type="button" className="kb-btn primary" onClick={onRequestUpload}>
                    上传文档
                  </button>
                )}
              </div>
            )}

            {/* 子文件夹磁贴（源侧 doc-folder-tile） */}
            {subFolders.length > 0 && !filtering && (
              <div className="kb-folder-tiles">
                {subFolders.map((f) => (
                  <button
                    key={f.path}
                    type="button"
                    className="kb-folder-tile"
                    onClick={() => setFolderPath(f.path)}
                    title={f.path}
                  >
                    <span className="kb-folder-tile-ic">📁</span>
                    <span className="kb-folder-tile-name">{f.name}</span>
                    <span className="kb-folder-tile-count">{f.document_count}</span>
                  </button>
                ))}
              </div>
            )}

            {/* ⚠️ 没有数据时不渲染表头 —— 否则「这个知识库还没有文档」下面还挂一排
                列名，看起来像「有表无数据」而不是「空库」。 */}
            {viewMode === 'list' ? (
              visibleItems.length > 0 && (
              <div className="kb-doc-list">
                <div className="kb-doc-row kb-doc-head">
                  {/* ⭐ 源侧勾选列恒显（24px，header 里就是 checkbox），
                      ⛔ 不是「批量模式才出现」—— 实测 5 列：24px | 名称 | 标签 | 状态 | 操作 */}
                  <span className="kb-doc-check">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      ref={(el) => { if (el) el.indeterminate = someSelected }}
                      onChange={toggleSelectAll}
                      aria-label="全选"
                    />
                  </span>
                  <span className="kb-doc-name">名称</span>
                  <span className="kb-doc-tags">标签</span>
                  <span className="kb-doc-status">状态</span>
                  <span className="kb-doc-ops">操作</span>
                </div>
                {visibleItems.map((row) => {
                  const tone = PARSE_STATUS_TONE[row.parse_status] ?? 'idle'
                  const inFlight = !['completed', 'failed', 'cancelled'].includes(row.parse_status)
                  const meta = fileMeta(row.file_type)
                  const rowTags = tagsFor(row, tags)
                  return (
                    <div className="kb-doc-row" key={row.id}>
                      <span className="kb-doc-check">
                        <input
                          type="checkbox"
                          checked={selectedIds.has(row.id)}
                          onChange={() => toggleSelect(row.id)}
                          aria-label={`选择 ${row.title}`}
                        />
                      </span>
                      <span className="kb-doc-name" title={row.title}>
                        <span className="kb-doc-ic">{meta.mark}</span>
                        {/* 源侧 .row-file-text：文件名 + meta 两行。
                            ⭐ 文件名可点 → 打开右侧详情抽屉（源侧点行即开抽屉）。 */}
                        <span className="kb-doc-name-body">
                          <button
                            type="button"
                            className="kb-doc-name-text kb-doc-name-link"
                            onClick={() => setDrawerDoc(row)}
                          >
                            {row.title || row.file_name || row.id}
                          </button>
                          <span className="kb-doc-meta">
                            {meta.label} · {formatSize(row.file_size)}
                            {filtering && row.folder_path && (
                              <span className="kb-doc-folder-hint">· {row.folder_path}</span>
                            )}
                          </span>
                        </span>
                      </span>
                      <span className="kb-doc-tags">
                        {rowTags.length === 0 && <span className="kb-doc-tags-empty">—</span>}
                        {rowTags.map((t) => (
                          <span
                            key={t.id}
                            className="kb-tag-chip"
                            style={{ borderColor: t.color || undefined }}
                          >
                            <i className="kb-tag-dot" style={{ background: t.color || '#94a3b8' }} />
                            {t.name}
                          </span>
                        ))}
                      </span>
                      <span className={`kb-doc-status tone-${tone}`} title={row.error_message ?? undefined}>
                        <i className="kb-dot" />
                        {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                        {row.parse_status === 'completed' && row.enable_status !== 'enabled' && (
                          <em className="kb-hint">（索引未就绪）</em>
                        )}
                      </span>
                      <span className="kb-doc-ops">
                        {inFlight ? (
                          <button type="button" className="kb-link" onClick={() => void handleCancel(row)}>
                            取消
                          </button>
                        ) : (
                          <button type="button" className="kb-link" onClick={() => void handleReparse(row)}>
                            重解析
                          </button>
                        )}
                        <button type="button" className="kb-link" onClick={() => void handleMove(row)}>
                          移动
                        </button>
                        <a className="kb-link" href={knowledgeApi.downloadUrl(row.id)} download>
                          下载
                        </a>
                        <button type="button" className="kb-link danger" onClick={() => void handleDelete(row)}>
                          删除
                        </button>
                      </span>
                    </div>
                  )
                })}
              </div>
              )
            ) : (
              <div className="kb-doc-grid">
                {visibleItems.map((row) => {
                  const tone = PARSE_STATUS_TONE[row.parse_status] ?? 'idle'
                  const meta = fileMeta(row.file_type)
                  const rowTags = tagsFor(row, tags)
                  return (
                    <div className={`kb-doc-card${selectedIds.has(row.id) ? ' is-selected' : ''}`} key={row.id}>
                      {batchMode && (
                        <input
                          className="kb-doc-card-check"
                          type="checkbox"
                          checked={selectedIds.has(row.id)}
                          onChange={() => toggleSelect(row.id)}
                          aria-label={`选择 ${row.title}`}
                        />
                      )}
                      <div className="kb-doc-card-head">
                        <span className="kb-doc-card-ic">{meta.mark}</span>
                        <button
                          type="button"
                          className="kb-doc-card-title kb-doc-name-link"
                          title={row.title}
                          onClick={() => setDrawerDoc(row)}
                        >
                          {row.title || row.file_name || row.id}
                        </button>
                      </div>
                      <div className="kb-doc-card-tags">
                        {rowTags.map((t) => (
                          <span key={t.id} className="kb-tag-chip">
                            <i className="kb-tag-dot" style={{ background: t.color || '#94a3b8' }} />
                            {t.name}
                          </span>
                        ))}
                      </div>
                      <div className="kb-doc-card-foot">
                        <span className={`kb-doc-status tone-${tone}`}>
                          <i className="kb-dot" />
                          {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                        </span>
                        <span className="kb-doc-card-size">{formatSize(row.file_size)}</span>
                      </div>
                      <div className="kb-doc-card-ops">
                        <button type="button" className="kb-link" onClick={() => void handleReparse(row)}>
                          重解析
                        </button>
                        <button type="button" className="kb-link" onClick={() => void handleMove(row)}>
                          移动
                        </button>
                        <button type="button" className="kb-link danger" onClick={() => void handleDelete(row)}>
                          删除
                        </button>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}

            {hasInFlight && <div className="kb-polling">解析进行中 · 自动刷新…</div>}
          </>
        )}
      </div>

      {/* 文档详情抽屉（源侧 .doc-main-drawer） */}
      {drawerDoc && (
        <KbDocDrawer
          knowledge={drawerDoc}
          showToast={showToast}
          onClose={() => setDrawerDoc(null)}
        />
      )}
    </div>
  )
}

/** 递归渲染文件夹树。 */
function FolderBranch({
  nodes,
  current,
  onSelect,
  onRename,
}: {
  nodes: KbFolderNode[]
  current: string
  onSelect: (path: string) => void
  onRename?: (node: KbFolderNode) => void
}) {
  if (nodes.length === 0) return null
  return (
    <div className="kb-folder-branch">
      {nodes.map((n) => (
        <div key={n.path}>
          <div className="kb-folder-row">
            <button
              type="button"
              className={`kb-folder-item${current === n.path ? ' is-active' : ''}`}
              onClick={() => onSelect(n.path)}
              title={n.path}
            >
              <span className="kb-folder-ic">📁</span>
              <span className="kb-folder-name">{n.name}</span>
              <span className="kb-folder-count">{n.document_count}</span>
            </button>
            {onRename && (
              <button
                type="button"
                className="kb-folder-rename"
                onClick={() => onRename(n)}
                title="重命名 / 移动文件夹"
                aria-label={`重命名 ${n.name}`}
              >
                ✎
              </button>
            )}
          </div>
          {n.children?.length > 0 && (
            <div className="kb-folder-children">
              <FolderBranch
                nodes={n.children}
                current={current}
                onSelect={onSelect}
                onRename={onRename}
              />
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

/** 取某路径下的直接子文件夹。 */
export function findChildren(nodes: KbFolderNode[], path: string): KbFolderNode[] {
  if (!path) return nodes
  for (const n of nodes) {
    if (n.path === path) return n.children ?? []
    const deeper = findChildren(n.children ?? [], path)
    if (deeper.length > 0) return deeper
  }
  return []
}

/**
 * 某文档身上的标签。
 *
 * <p>⭐ 以前这里是空实现（返回 `[]`）—— 因为后端 DTO **不返回** `tags`
 * （javadoc 写「依赖 B9 的标签表」，但表早就建好了）。结果是「能打标签、看不见标签」。
 * 现在后端列表已 JOIN 出 `tags`，这里直接读。
 *
 * @param row  文档行
 * @param tags 库内标签（仅用于把 id 补成完整对象；行上的标签已含 name/color）
 */
export function tagsFor(row: Knowledge, tags: KbTag[]): KbTag[] {
  const own = row.tags ?? []
  if (own.length === 0) return []
  // 行上的标签已带 name/color，够渲染；但补上库内 label 的 sort_order 等信息
  // 便于排序一致（找不到就按行上原样）
  return own.map((t) => {
    const full = tags.find((x) => x.id === t.id)
    return full ?? {
      id: t.id, knowledge_base_id: '', name: t.name, color: t.color,
      sort_order: null, seq_id: null, knowledge_count: null,
      created_at: null, updated_at: null,
    }
  })
}

/**
 * 合并标签 id（`PUT /knowledge/tags` 是**替换**语义，⛔ 不是追加）。
 *
 * @param existing 已选标签
 * @param add      要加上的标签
 * @returns 并集（保序、去重）
 */
export function mergeTagIds(existing: string[], add: string): string[] {
  if (!add || existing.includes(add)) return existing
  return [...existing, add]
}
