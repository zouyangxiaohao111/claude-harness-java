import { useCallback, useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '@/api/rest'
import { knowledgeApi, PARSE_STATUS_LABEL, PARSE_STATUS_TONE } from '@/api/knowledge'
import type { KbFolderNode, KbTag, Knowledge, SearchHit } from '@/api/knowledge'
import { KbUploadSource } from './KbUploadSource'
import { KbDocDrawer } from './KbDocDrawer'
import { KbProcessingTimeline } from './KbProcessingTimeline'
import type { FetchSpansFn } from './KbProcessingTimeline'
import { IconSprite } from './IconSprite'
import { FileTypeIcon } from './FileTypeIcon'

interface KnowledgeDocPanelProps {
  baseId: string
  baseName?: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 上传按钮在页头，这里复用同一个文件输入 */
  onRequestUpload: () => void
}

type ViewMode = 'list' | 'grid'

// ⚠️ 2026-11-13 删除本地 KnowledgeStages 类型：
//   它声明的是 {title, spans[], attempts[]}，而 /stages 与 /spans 是同一个后端 handler，
//   实际返回 KnowledgeSpansDto（{knowledge_id, attempt, trace, current_stage, …}）——
//   字段一个都对不上。Trace 弹层已改为复用 KbProcessingTimeline（与抽屉内一致）。

/** 「移动到…」的目标知识库（后端 `KbAdvancedService.MoveTarget`）。 */
interface MoveTarget {
  id: string
  name: string
  description: string | null
  type: string | null
  knowledge_count: number
}

/** 知识库类型 → 中文（后端 `type` 取值：document / faq / …）。 */
function kbTypeLabel(t: string | null | undefined): string {
  switch (t) {
    case 'document':
      return '文档库'
    case 'faq':
      return '问答库'
    case '':
    case null:
    case undefined:
      return '未知类型'
    default:
      return t
  }
}


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
  /** 当前展开 ⋯ 菜单的行 id（null = 全关）。⚠️ 同一时刻只开一个。 */
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)

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

  /**
   * 文档行内「⋯」菜单打开的浮层（Trace / 移动到… / 编辑标签）。
   *
   * <p>⚠️ 用 state 而不是 `<details>`：Trace 与「移动到…」是异步弹层，
   * 需要「先关菜单再开弹层」；`<details>` 的 open 状态不受控，弹层打开时菜单会留在原地。
   */
  // Trace 抽屉只记「打开哪一行」——数据由 KbProcessingTimeline 自己取
  // （对齐源站 `doc-content.vue:346 openTimeline()`，列表入口与抽屉内复用同一组件）
  const [traceState, setTraceState] = useState<{ row: Knowledge } | null>(null)
  const [moveTargetState, setMoveTargetState] = useState<{
    row: Knowledge
    loading: boolean
    error: string | null
    targets: MoveTarget[]
    moving: string | null
  } | null>(null)
  /** 「编辑标签」弹层：给**单行**选标签（`PUT /knowledge/tags` 是替换语义 ⇒ 预填当前集合）。 */
  const [rowTagState, setRowTagState] = useState<{
    row: Knowledge
    /** 当前勾选（初值 = 该行已有标签） */
    selected: string[]
    saving: boolean
    error: string | null
  } | null>(null)

  /**
   * 点「⋯」菜单以外的地方 ⇒ 关闭菜单。
   *
   * <p>⚠️ 菜单是 `<details open>`（受控），没有原生「点外面自动收起」⇒ 不补这一段的话，
   * 用户点筛选栏/空白处后菜单会一直挂在那儿，看起来像界面卡住。
   */
  useEffect(() => {
    if (menuOpenId === null) return
    const onDown = (e: MouseEvent) => {
      const t = e.target as HTMLElement | null
      if (!t) return
      if (t.closest('.card-menu') || t.closest('summary.row-more-btn')) return
      setMenuOpenId(null)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [menuOpenId])

  /**
   * 从行内**任意位置**打开详情抽屉（源侧：点行即开）。
   *
   * <p>⭐⭐ 必须排除「自己有交互」的子控件，否则：
   * <ul>
   *   <li>点复选框 ⇒ 同时勾选 + 弹抽屉（用户只想勾选）；</li>
   *   <li>点 ⋯ ⇒ 菜单刚展开就被抽屉盖住；</li>
   *   <li>点「+ 添加标签」/ 链接 ⇒ 被抽屉抢走。</li>
   * </ul>
   * 判据 = 「最近的 interactive 祖先是否存在」，而不是逐个比对坐标/className 列表
   * （后者加了新控件就会漏）。
   */
  const openDrawerFromRow = (e: React.MouseEvent, row: Knowledge) => {
    const t = e.target as HTMLElement | null
    if (!t) return
    const interactive = t.closest(
      'input,button,a,summary,label,select,textarea,' +
      '[role="menuitem"],[role="checkbox"],[role="switch"],' +
      '.card-menu,.row-more-btn,.cell-check,.doc-list-check,.tag-add,.card-nav-check'
    )
    if (interactive) return
    // 点行内空白处 = 用户已不关心菜单 ⇒ 顺手收起（否则抽屉弹出来时菜单还挂在下面）
    setMenuOpenId(null)
    setDrawerDoc(row)
  }

  /**
   * Trace 取数（注入 KbProcessingTimeline）。
   *
   * <p>⚠️ 后端 `/spans` 返回 `{success, data}` 包装（对齐源侧 DataResponse），
   * `success` 原样透传 —— 后端判「没有 trace」时也是 200 + success:true + 空 trace，
   * 那是「真实的无记录」，不是加载失败。
   */
  const fetchSpans = useCallback<FetchSpansFn>(async (id, attempt) => {
    try {
      const res = await knowledgeApi.getKnowledgeSpans(id, attempt)
      return { success: !!res?.success, data: res?.data }
    } catch (e) {
      return { success: false, message: toMessage(e) }
    }
  }, [])

  /** 打开 Trace 抽屉（对齐源站：只开 trace 抽屉，不弹文档详情抽屉）。 */
  const openTrace = (row: Knowledge) => {
    setTraceState({ row })
  }

  /**
   * Esc 只关 Trace 抽屉。
   *
   * <p>⭐⭐ 与 PanelModal 同一理由：`App.tsx` 也在 window 上监听 Esc（非对话页按 Esc
   * 会把整个知识库场景退掉）。必须捕获阶段 + stopPropagation，否则用户按一次 Esc
   * 连场景一起关。
   */
  useEffect(() => {
    if (!traceState) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      e.preventDefault()
      setTraceState(null)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [traceState])

  /** 打开「移动到…」（跨知识库）弹层。 */
  const openMoveTargets = async (row: Knowledge) => {
    setMoveTargetState({ row, loading: true, error: null, targets: [], moving: null })
    try {
      const targets = await knowledgeApi.listMoveTargets(baseId)
      setMoveTargetState({ row, loading: false, error: null, targets: targets ?? [], moving: null })
    } catch (e) {
      const msg = toMessage(e)
      console.warn(`[文档列表] 目标知识库列表加载失败 base=${baseId} 原因=${msg}`)
      setMoveTargetState({ row, loading: false, error: msg, targets: [], moving: null })
    }
  }

  /** 执行跨库移动。 */
  const doMoveToTarget = async (targetId: string) => {
    const st = moveTargetState
    if (!st || st.moving) return
    setMoveTargetState({ ...st, moving: targetId, error: null })
    try {
      const r = await knowledgeApi.moveKnowledge({
        source_kb_id: baseId,
        target_kb_id: targetId,
        knowledge_ids: [st.row.id],
      })
      setMoveTargetState(null)
      showToast(`已移动 ${r?.moved ?? 1} 个文档到目标知识库`, 'success')
      void loadItems()
      void loadFolders()
    } catch (e) {
      const msg = toMessage(e)
      console.warn(`[文档列表] 移动失败 knowledge=${st.row.id} target=${targetId} 原因=${msg}`)
      setMoveTargetState({ ...st, moving: null, error: msg })
    }
  }

  /** 打开「编辑标签」弹层（预填该行已有标签 —— 替换语义，⛔ 不能从空开始）。 */
  const openRowTags = (row: Knowledge) => {
    const own = (row.tags ?? []).map((t) => t.id)
    setRowTagState({ row, selected: own, saving: false, error: null })
  }

  /** 保存单行标签。 */
  const saveRowTags = async () => {
    const st = rowTagState
    if (!st || st.saving) return
    setRowTagState({ ...st, saving: true, error: null })
    try {
      await knowledgeApi.setKnowledgeTags(baseId, [st.row.id], st.selected)
      setRowTagState(null)
      showToast('标签已更新', 'success')
      void loadItems()
      void loadTags()
    } catch (e) {
      const msg = toMessage(e)
      console.warn(`[文档列表] 标签保存失败 knowledge=${st.row.id} 原因=${msg}`)
      setRowTagState({ ...st, saving: false, error: msg })
      showToast(`标签保存失败：${msg}`, 'info')
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
    <div className="knowledge-main">
      {/* ⭐ tdesign 图标 sprite 必须在本组件内挂一份：
          `KbCreateModal` 里那份随弹窗卸载，而文档行的 ⋯ 菜单、文件类型图标
          都在这里渲染 —— 不挂 ⇒ `<use href="#t-icon-x">` 找不到 symbol，
          渲染成**看不见的空白**（控制台零报错，最典型的静默失效）。
          sprite 用 absolute+0 尺寸，不占布局。 */}
      <IconSprite />
      {/* ── 左：文件夹树（源侧 KbFolderTree.vue:1-130）──────── */}
      {!treeCollapsed && (
        <aside className="kb-folder-tree" aria-label="文件夹">
          <div className="kb-folder-tree__header">
            <span className="kb-folder-tree__title">文件夹</span>
            <span className="kb-folder-tree__total">{baseName ?? '全部文档'} · {total}</span>
          </div>
          <div className="kb-folder-tree__body">
            <div
              className={`kb-folder-row is-root${folderPath === '' ? ' active' : ''}`}
              /* ⚠️ `--app-selection-bg` 只在源站 theme.css 的**另一个** :root 块里定义，
                 kb-scope.css 提取时没带上它 ⇒ 类里的 background 解析成空。
                 这里按源站同一个表达式（theme.css:206）行内补上。 */
              style={folderPath === ''
                ? { paddingLeft: 4, background: 'color-mix(in srgb, var(--td-text-color-primary) 4%, var(--td-bg-color-container))' }
                : { paddingLeft: 4 }}
              role="button"
              tabIndex={0}
              title={baseName}
              onClick={() => setFolderPath('')}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setFolderPath('') }}
            >
              <span className="kb-folder-row__toggle-placeholder" aria-hidden="true" />
              <span className="kb-folder-row__icon">🗂</span>
              <span className="kb-folder-row__label">{baseName ?? '全部文档'}</span>
              <span className="kb-folder-row__trailing">
                <span className="kb-folder-row__count">{total}</span>
              </span>
            </div>
            <FolderBranch
              nodes={folders}
              current={folderPath}
              onSelect={setFolderPath}
              onRename={handleRenameFolder}
            />
            {folders.length === 0 && <div className="no_content">暂无子文件夹</div>}
          </div>
        </aside>
      )}

      {/* ── 右：文档区（源侧 .tag-content > .doc-card-area）──── */}
      <div className="tag-content">
      <div className="doc-card-area">
        {/* 工具条 + 文件夹路径（源侧 .doc-filter-bar，KnowledgeBase.vue:2544）*/}
        <div className="doc-filter-bar">
          <nav className="doc-folder-path" aria-label="文件夹路径">
            {treeCollapsed && (
              <button
                type="button"
                className="doc-folder-path__tree-toggle"
                onClick={() => setTreeCollapsed(false)}
                title="展开文件夹"
                aria-label="展开文件夹"
              >
                ☰
              </button>
            )}
            {crumbs.length === 0 ? (
              <span className="doc-folder-path__crumb is-current" title={baseName} aria-current="page">
                {baseName ?? '全部文档'}
              </span>
            ) : (
              <button type="button" className="doc-folder-path__crumb" title={baseName} onClick={() => setFolderPath('')}>
                {baseName ?? '全部文档'}
              </button>
            )}
            {crumbs.map((c, i) => (
              <span key={c.path} style={{ display: 'contents' }}>
                <span className="doc-folder-path__sep" aria-hidden="true">›</span>
                {i === crumbs.length - 1 ? (
                  <span className="doc-folder-path__crumb is-current" title={c.name} aria-current="page">{c.name}</span>
                ) : (
                  <button type="button" className="doc-folder-path__crumb" title={c.name} onClick={() => setFolderPath(c.path)}>
                    {c.name}
                  </button>
                )}
              </span>
            ))}
            <span className="doc-folder-path__count">
              {filtering ? `筛出 ${visibleItems.length}` : `${total} 个文档`}
            </span>
            {!treeCollapsed && folders.length > 0 && (
              <button
                type="button"
                className="doc-folder-path__tree-toggle"
                onClick={() => setTreeCollapsed(true)}
                title="收起文件夹"
                aria-label="收起文件夹"
              >
                ‹
              </button>
            )}
          </nav>

          {/* 右侧动作区（源侧 .doc-filter-bar__trailing；搜索框在 trailing 里面）*/}
          <div className="doc-filter-bar__trailing">
            {/* 搜索（源侧 t-input：.doc-search-input 挂在外层 wrap 上）*/}
            <div className="t-input__wrap doc-search-input">
              <div className="t-input t-input--prefix">
                <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
                <input
                  className="t-input__inner"
                  placeholder="搜索文档…"
                  aria-label="搜索文档"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </div>
            </div>

            <div style={{ position: 'relative', flexShrink: 0 }}>
              <button
                type="button"
                className={`doc-filter-toggle${tagPanelOpen || activeFilterCount > 0 ? ' active' : ''}`}
                aria-expanded={tagPanelOpen}
                onClick={() => setTagPanelOpen((v) => !v)}
              >
                筛选
                {activeFilterCount > 0 && <span className="doc-filter-count">{activeFilterCount}</span>}
              </button>

              {/* 筛选面板（源侧 .doc-filter-panel，KnowledgeBase.vue:2587；
                  源站由 t-popup 定位，这里用锚定容器 + 内联定位等价实现）*/}
              {tagPanelOpen && (
                <div className="doc-filter-panel" style={{ position: 'absolute', top: 'calc(100% + 6px)', right: 0, zIndex: 20 }}>
                  <div className="doc-filter-panel__header">
                    <strong>筛选</strong>
                    <button
                      type="button"
                      disabled={activeFilterCount === 0}
                      onClick={() => { setStatusFilter(''); setTypeFilter(''); setSelectedTagIds([]) }}
                    >
                      清空
                    </button>
                  </div>
                  <div className="doc-filter-panel__fields">
                    <div className="doc-filter-field">
                      <span>解析状态</span>
                      <select
                        className="t-input"
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
                    </div>
                    <div className="doc-filter-field">
                      <span>文件类型</span>
                      <select
                        className="t-input"
                        value={typeFilter}
                        onChange={(e) => setTypeFilter(e.target.value)}
                        aria-label="按类型筛选"
                      >
                        <option value="">全部类型</option>
                        {['pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'md', 'txt', 'csv'].map((t) => (
                          <option key={t} value={t}>{fileMeta(t).label}</option>
                        ))}
                      </select>
                    </div>
                  </div>

                  {/* 标签筛选（源侧 .doc-filter-tags，KnowledgeBase.vue:2612）*/}
                  <div className="doc-filter-tags">
                    <div className="doc-filter-tags__heading">
                      <span>
                        标签
                        {selectedTagIds.length > 0 && <span className="doc-filter-tags__count">{selectedTagIds.length}</span>}
                      </span>
                      <button type="button" onClick={() => setTagManagerOpen((v) => !v)}>
                        {tagManagerOpen ? '完成' : '管理'}
                      </button>
                    </div>
                    <div className="doc-filter-tags__list">
                      {tags.length === 0 && <span className="doc-filter-tags__empty">还没有标签</span>}
                      {tags.map((t) => (
                        <label key={t.id} className="doc-filter-tag">
                          <input
                            type="checkbox"
                            checked={selectedTagIds.includes(t.id)}
                            onChange={() =>
                              setSelectedTagIds((prev) =>
                                prev.includes(t.id) ? prev.filter((x) => x !== t.id) : [...prev, t.id]
                              )
                            }
                          />
                          <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', flex: 'none', background: t.color || '#94a3b8' }} />
                          <span>{t.name}</span>
                          <span className="doc-filter-tags__count">{t.knowledge_count ?? 0}</span>
                          {tagManagerOpen && (
                            <button
                              type="button"
                              className="popup-menu-item danger"
                              onClick={(e) => { e.preventDefault(); void handleDeleteTag(t) }}
                            >
                              删除
                            </button>
                          )}
                        </label>
                      ))}
                    </div>
                    {tagManagerOpen && (
                      <div className="doc-filter-field" style={{ marginTop: 8 }}>
                        <span>新建</span>
                        <div style={{ display: 'flex', gap: 6, minWidth: 0 }}>
                          <input
                            className="t-input"
                            placeholder="新标签名"
                            value={newTagName}
                            onChange={(e) => setNewTagName(e.target.value)}
                            onKeyDown={(e) => { if (e.key === 'Enter') void handleCreateTag() }}
                          />
                          <button type="button" className="doc-filter-toggle" onClick={() => void handleCreateTag()}>
                            新建
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>

            <button
              type="button"
              className={`doc-filter-toggle${batchMode ? ' active' : ''}`}
              onClick={() => { setBatchMode((v) => !v); setSelectedIds(new Set()) }}
              disabled={visibleItems.length === 0}
            >
              {batchMode ? '取消' : '批量管理'}
            </button>

            <div className="doc-view-toggle" role="group" aria-label="视图模式">
              <button
                type="button"
                className={`doc-view-toggle-btn${viewMode === 'grid' ? ' active' : ''}`}
                onClick={() => setViewMode('grid')}
                title="网格"
                aria-label="网格"
                aria-pressed={viewMode === 'grid'}
              >
                ▦
              </button>
              <button
                type="button"
                className={`doc-view-toggle-btn${viewMode === 'list' ? ' active' : ''}`}
                onClick={() => setViewMode('list')}
                title="列表"
                aria-label="列表"
                aria-pressed={viewMode === 'list'}
              >
                ☰
              </button>
            </div>

            <div className="doc-filter-actions">
              {/* ⭐ 四档来源（文件 / 文件夹 / URL / 手工）· 对齐源侧 KbUploadSourceDropdown */}
              <KbUploadSource
                baseId={baseId}
                folderPath={folderPath}
                showToast={showToast}
                onDone={() => { void loadItems(); void loadFolders() }}
              />
            </div>
          </div>
        </div>

        {/* 语义检索（搜文件内容，与标题搜索是两件事）
            ⚠️ 本仓特有：源站文档页没有 hybrid-search 入口 ⇒ 无真类名可抄。
            沿用本仓既有 `.kb-searchbar` 钩子（已被守卫测试锁定），
            内部控件改为源站 t-input / t-button 真类名。 */}
        <div className="doc-filter-bar kb-searchbar" style={{ paddingTop: 12 }}>
          <div className="t-input__wrap doc-search-input" style={{ marginLeft: 0, width: 320 }}>
            <div className="t-input t-input--prefix">
              <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
              <input
                className="t-input__inner"
                placeholder="在文档内容中检索…（回车执行）"
                aria-label="在文档内容中检索"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') void runSearch() }}
              />
            </div>
          </div>
          <div className="doc-filter-bar__trailing">
            <button
              type="button"
              className="t-button t-button--variant-base t-button--theme-primary t-size-s"
              disabled={searching || !searchQuery.trim()}
              onClick={() => void runSearch()}
            >
              <span className="t-button__text">{searching ? '检索中…' : '检索'}</span>
            </button>
            {searchHits !== null && (
              <button
                type="button"
                className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                onClick={() => setSearchHits(null)}
              >
                <span className="t-button__text">清除结果</span>
              </button>
            )}
          </div>
        </div>

        {/* 批量操作条（源侧 .doc-batch-bar / DocumentBatchBar.vue:45）*/}
        {batchMode && (
          <div className="doc-batch-bar">
            <div className="batch-bar-inner">
              <div className="batch-bar-left">
                <span className="batch-bar-count">已选 {selectedIds.size} 个</span>
                <button
                  type="button"
                  className="t-button t-button--variant-text t-button--theme-default t-size-s batch-bar-clear"
                  onClick={() => setSelectedIds(new Set())}
                >
                  <span className="t-button__text">清空选择</span>
                </button>
              </div>
              <div className="batch-bar-actions">
                {tags.length > 0 && (
                  <select
                    className="t-input"
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
                  className="t-button t-button--variant-outline t-button--theme-default t-size-s"
                  disabled={selectedIds.size === 0}
                  onClick={() => void handleBatchReparse()}
                >
                  <span className="t-button__text">批量重解析</span>
                </button>
                <button
                  type="button"
                  className="t-button t-button--variant-outline t-button--theme-danger t-size-s"
                  disabled={selectedIds.size === 0}
                  onClick={() => void handleBatchDelete()}
                >
                  <span className="t-button__text">批量删除</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div className="t-alert t-alert--error" role="alert">
            <div className="t-alert__content">
              <div>{error}</div>
            </div>
          </div>
        )}

        {/* 检索结果（源侧 .search-results，kb-orphan.css:950）*/}
        {searchHits !== null && (
          <div className="search-results">
            <h3 className="doc-section-header">
              <span>检索结果</span>
              <span className="doc-section-count">{searchHits.length}</span>
            </h3>
            {searchHits.length === 0 && (
              <div className="no_content">没有匹配的片段 —— 换个说法试试；文档需解析完成才可被检索</div>
            )}
            {searchHits.length > 0 && (
              <div className="chunk-list">
                {searchHits.map((h) => (
                  <div className="chunk-item kb-hit" key={h.id}>
                    <div className="chunk-header">
                      <div className="chunk-heading">
                        <span className="chunk-index">{h.knowledge_title}</span>
                        <span className="chunk-meta">
                          {h.match_type === 'hybrid' ? '双路命中' : h.match_type === 'vector' ? '语义' : '关键词'}
                          {` · ${h.score.toFixed(3)}`}
                        </span>
                      </div>
                    </div>
                    {h.context_header && <div className="chunk-history-current">{h.context_header}</div>}
                    <div className="md-content kb-hit-body" style={{ marginTop: 8 }}>{h.content}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 列表 / 网格（源侧 .doc-scroll-container 包住 DocumentListView / DocumentCardView）*/}
        {searchHits === null && (
          <div className="doc-scroll-container">
            {!loading && visibleItems.length === 0 && (
              <div className="doc-empty-state">
                <div className="wk-empty-state">
                  <div className="wk-empty-state__title">
                    {filtering ? '没有匹配的文档' : folderPath ? '这个文件夹还没有文档' : '这个知识库还没有文档'}
                  </div>
                  <div className="wk-empty-state__desc">
                    {filtering
                      ? '换个关键词或清空筛选试试'
                      : '上传 pdf / word / excel / ppt / md / txt 等格式的文档，解析后即可检索'}
                  </div>
                  {!filtering && (
                    <div className="wk-empty-state__actions">
                      <button
                        type="button"
                        className="t-button t-button--variant-base t-button--theme-primary"
                        onClick={onRequestUpload}
                      >
                        <span className="t-button__text">上传文档</span>
                      </button>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* ⚠️ 没有数据时不渲染表头 —— 否则「这个知识库还没有文档」下面还挂一排
                列名，看起来像「有表无数据」而不是「空库」。 */}
            {viewMode === 'list' ? (
              visibleItems.length > 0 && (
              <div className="doc-list-view">
                <div className="doc-list-header" role="row">
                  {/* ⭐ 源侧勾选列恒显（24px，header 里就是 checkbox），
                      ⛔ 不是「批量模式才出现」—— 实测 5 列：24px | 名称 | 标签 | 状态 | 操作 */}
                  <div className="cell cell-check" role="columnheader">
                    <label className="doc-list-check">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        ref={(el) => { if (el) el.indeterminate = someSelected }}
                        onChange={toggleSelectAll}
                        aria-label="全选"
                      />
                    </label>
                  </div>
                  <div className="cell cell-name" role="columnheader">名称</div>
                  <div className="cell cell-tags" role="columnheader">标签</div>
                  <div className="cell cell-status" role="columnheader">状态</div>
                  <div className="cell cell-actions" role="columnheader"></div>
                </div>
                <div className="doc-list-body" role="rowgroup">
                {visibleItems.map((row) => {
                  const tone = PARSE_STATUS_TONE[row.parse_status] ?? 'idle'
                  const inFlight = !['completed', 'failed', 'cancelled'].includes(row.parse_status)
                  const meta = fileMeta(row.file_type)
                  const rowTags = tagsFor(row, tags)
                  return (
                    <div
                      className={`doc-list-row${selectedIds.has(row.id) ? ' selected' : ''}`}
                      style={{
                        ...(selectedIds.has(row.id)
                          ? { background: 'color-mix(in srgb, var(--td-text-color-primary) 4%, var(--td-bg-color-container))' }
                          : {}),
                        // ⭐ 整行可点开抽屉（源侧行为）⇒ 给手型光标，否则用户以为只有文件名能点
                        cursor: 'pointer',
                      }}
                      key={row.id}
                      data-select-id={row.id}
                      role="row"
                      onClick={(e) => openDrawerFromRow(e, row)}
                    >
                      <div className="cell cell-check" role="cell">
                        <label className="doc-list-check">
                          <input
                            type="checkbox"
                            checked={selectedIds.has(row.id)}
                            onChange={() => toggleSelect(row.id)}
                            aria-label={`选择 ${row.title}`}
                          />
                        </label>
                      </div>
                      <div className="cell cell-name" role="cell">
                        <span className="row-file-icon-wrap">
                          <FileTypeIcon ext={row.file_type || row.file_name?.split('.').pop()} size={32} />
                        </span>
                        {/* 源侧 .row-file-text：文件名 + meta 两行。
                            ⭐ 文件名可点 → 打开右侧详情抽屉（源侧点行即开抽屉）。 */}
                        <div className="row-file-text">
                          <div className="row-title-line">
                            <button
                              type="button"
                              className="row-file-name"
                              title={row.title}
                              onClick={() => setDrawerDoc(row)}
                            >
                              {row.title || row.file_name || row.id}
                            </button>
                          </div>
                          <span className="row-file-meta">
                            <span className="row-source">{meta.label}</span>
                            <span className="meta-sep" aria-hidden="true">·</span>
                            <span>{formatSize(row.file_size)}</span>
                          </span>
                          {filtering && row.folder_path && (
                            <button
                              type="button"
                              className="row-file-folder"
                              title={row.folder_path}
                              onClick={() => setFolderPath(row.folder_path ?? '')}
                            >
                              <span>📁</span>
                              <span>{row.folder_path}</span>
                            </button>
                          )}
                        </div>
                      </div>
                      <div className="cell cell-tags" role="cell">
                        <div className="row-tag-trigger">
                          {rowTags.length === 0 ? (
                            /* 源站 DocumentListView.vue:366 —— 空标签列渲染「+ 添加标签」虚线按钮，
                               ⛔ 不是破折号：破折号让用户根本不知道这里可以打标签。 */
                            <button
                              type="button"
                              className="row-tag-add"
                              title="添加标签"
                              onClick={() => openRowTags(row)}
                            >
                              <svg width={12} height={12} aria-hidden="true"><use href="#t-icon-add" /></svg>
                              <span>添加标签</span>
                            </button>
                          ) : (
                            <div className="row-tags">
                              {rowTags.slice(0, 5).map((t) => (
                                <span key={t.id} className="row-tag" title={t.name}>{t.name}</span>
                              ))}
                              {rowTags.length > 5 && (
                                <span
                                  className="row-tag row-tag--overflow"
                                  title={rowTags.slice(5).map((t) => t.name).join(', ')}
                                >
                                  +{rowTags.length - 5}
                                </span>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                      <div className="cell cell-status" role="cell">
                        <span
                          className={`row-status-tag ${tone === 'ok' ? 'status-success' : tone === 'error' ? 'status-danger' : tone === 'running' ? 'status-primary' : 'status-default'}`}
                          title={row.error_message ?? undefined}
                        >
                          {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                          {row.parse_status === 'completed' && row.enable_status !== 'enabled' && (
                            <em className="row-muted">（索引未就绪）</em>
                          )}
                        </span>
                      </div>
                      {/* ⭐ 末列只 28px ⇒ 只能放一个 ⋯（放文字按钮会把列挤成竖排）*/}
                      <div className="cell cell-actions" role="cell">
                        {/* 源站用 t-popup 承载动作菜单；本仓用受控 open（受控才能「点菜单项后
                            立即关闭」—— `<details>` 的 open 不受控，弹层打开时菜单会留在原地） */}
                        <details open={menuOpenId === row.id} style={{ position: 'relative' }}>
                          <summary
                            className="row-more-btn"
                            title="更多操作"
                            aria-label={`${row.title} · 更多操作`}
                            style={{ listStyle: 'none' }}
                            onClick={(e) => {
                              // ⚠️ 阻止冒泡：否则整行 onClick 会顺手把抽屉打开（菜单被盖住）
                              e.preventDefault()
                              e.stopPropagation()
                              setMenuOpenId((cur) => (cur === row.id ? null : row.id))
                            }}
                          >
                            ⋯
                          </summary>
                          <div
                            className="card-menu"
                            style={{
                              position: 'absolute', right: 0, top: 'calc(100% + 4px)', zIndex: 30,
                              minWidth: 148, padding: 4, borderRadius: 10,
                              background: 'var(--td-bg-color-container)',
                              border: '0.5px solid var(--td-component-stroke)',
                              boxShadow: '0 2px 4px #0000000a, 0 6px 20px #00000014',
                            }}
                            role="menu"
                          >
                            {/* ⚠️ 用 <div> 不是 <button> —— 源码 DocumentActionMenu.vue 全部是
                                `<div class="doc-action-menu-item" @click.stop>`。
                                CSS 里没有 `border:0/background:none`（因为 div 本来就不需要），
                                换成 button 会让浏览器默认样式（2px outset 灰框 + buttonface 底）
                                直接暴露 ⇒ 截图里那种「很原始」的样子。 */}
                            <div
                              className="doc-action-menu-item" role="menuitem" tabIndex={0}
                              onClick={() => { openRowTags(row); setMenuOpenId(null) }}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { openRowTags(row); setMenuOpenId(null) } }}
                            >
                              <MenuIcon name="edit" /><span>编辑标签</span>
                            </div>
                            <a
                              className="doc-action-menu-item" role="menuitem"
                              href={knowledgeApi.downloadUrl(row.id)} download
                              onClick={() => setMenuOpenId(null)}
                            >
                              <MenuIcon name="download" /><span>下载</span>
                            </a>
                            <div
                              className="doc-action-menu-item" role="menuitem" tabIndex={0}
                              onClick={() => { setMenuOpenId(null); void openTrace(row) }}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setMenuOpenId(null); void openTrace(row) } }}
                            >
                              <MenuIcon name="chart-bar" /><span>查看 Trace</span>
                            </div>
                            {inFlight ? (
                              <div
                                className="doc-action-menu-item" role="menuitem" tabIndex={0}
                                onClick={() => { void handleCancel(row); setMenuOpenId(null) }}
                                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { void handleCancel(row); setMenuOpenId(null) } }}
                              >
                                <MenuIcon name="close-circle" /><span>停止解析</span>
                              </div>
                            ) : (
                              <div
                                className="doc-action-menu-item" role="menuitem" tabIndex={0}
                                onClick={() => { void handleReparse(row); setMenuOpenId(null) }}
                                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { void handleReparse(row); setMenuOpenId(null) } }}
                              >
                                <MenuIcon name="refresh" /><span>重建知识</span>
                              </div>
                            )}
                            <div
                              className="doc-action-menu-item" role="menuitem" tabIndex={0}
                              onClick={() => { void handleMove(row); setMenuOpenId(null) }}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { void handleMove(row); setMenuOpenId(null) } }}
                            >
                              <MenuIcon name="folder" /><span>移动到目录</span>
                            </div>
                            <div
                              className="doc-action-menu-item" role="menuitem" tabIndex={0}
                              onClick={() => { setMenuOpenId(null); void openMoveTargets(row) }}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setMenuOpenId(null); void openMoveTargets(row) } }}
                            >
                              <MenuIcon name="swap" /><span>移动到...</span>
                            </div>
                            <div
                              className="doc-action-menu-item" role="menuitem" tabIndex={0}
                              onClick={() => {
                                setMenuOpenId(null)
                                // 已在批量模式 ⇒ 菜单项语义变为「退出批量管理」
                                setBatchMode((v) => !v)
                                setSelectedIds(new Set())
                              }}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') {
                                  setMenuOpenId(null); setBatchMode((v) => !v); setSelectedIds(new Set())
                                }
                              }}
                            >
                              <MenuIcon name="queue" />
                              <span>{batchMode ? '退出批量管理' : '批量管理'}</span>
                            </div>
                            {/* 源侧在「批量管理」与「删除文档」之间有一条分隔线 */}
                            <div
                              style={{
                                height: 1,
                                background: 'var(--td-component-stroke)',
                                margin: '4px 0',
                              }}
                              aria-hidden="true"
                            />
                            <div
                              className="doc-action-menu-item danger" role="menuitem" tabIndex={0}
                              onClick={() => { void handleDelete(row); setMenuOpenId(null) }}
                              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { void handleDelete(row); setMenuOpenId(null) } }}
                            >
                              <MenuIcon name="delete" /><span>删除文档</span>
                            </div>
                          </div>
                        </details>
                      </div>
                    </div>
                  )
                })}
                </div>
              </div>
              )
            ) : (
              /* 卡片视图（源侧 DocumentCardView.vue:337-625）*/
              <div className="doc-card-view">
                <div className="doc-card-list doc-card-list-animated">
                {visibleItems.map((row) => {
                  const tone = PARSE_STATUS_TONE[row.parse_status] ?? 'idle'
                  const meta = fileMeta(row.file_type)
                  const rowTags = tagsFor(row, tags)
                  return (
                    <div
                      className={`knowledge-card${selectedIds.has(row.id) ? ' is-selected' : ''}`}
                      style={{
                        ...(selectedIds.has(row.id)
                          ? { background: 'color-mix(in srgb, var(--td-text-color-primary) 4%, var(--td-bg-color-container))' }
                          : {}),
                        // ⭐ 整卡可点开抽屉（与列表视图同一行为）
                        cursor: 'pointer',
                      }}
                      key={row.id}
                      data-select-id={row.id}
                      onClick={(e) => openDrawerFromRow(e, row)}
                    >
                      <div className="card-content">
                        <div className="card-content-nav">
                          <div className="card-file-icon" title={`${meta.label} · ${formatSize(row.file_size)}`}>
                            <FileTypeIcon ext={row.file_type || row.file_name?.split('.').pop()} size={32} />
                          </div>
                          <button
                            type="button"
                            className="card-content-title"
                            title={row.title}
                            onClick={() => setDrawerDoc(row)}
                          >
                            {row.title || row.file_name || row.id}
                          </button>
                          {batchMode && (
                            <div className="card-nav-check">
                              <label className="card-select-checkbox doc-list-check">
                                <input
                                  type="checkbox"
                                  checked={selectedIds.has(row.id)}
                                  onChange={() => toggleSelect(row.id)}
                                  aria-label={`选择 ${row.title}`}
                                />
                              </label>
                            </div>
                          )}
                        </div>
                        <div className="card-preview">
                          {!['completed', 'failed', 'cancelled'].includes(row.parse_status) ? (
                            <div className="card-analyze">
                              <span className={`row-status-tag ${tone === 'running' ? 'status-primary' : 'status-default'}`}>
                                {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                              </span>
                            </div>
                          ) : row.parse_status === 'failed' ? (
                            <div className="card-analyze" title={row.error_message ?? undefined}>
                              <span className={`row-status-tag ${tone === 'error' ? 'status-danger' : 'status-default'}`}>
                                {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                              </span>
                            </div>
                          ) : row.parse_status === 'cancelled' ? (
                            <div className="card-analyze" style={{ color: 'var(--td-text-color-placeholder)' }}>
                              {PARSE_STATUS_LABEL[row.parse_status] ?? row.parse_status}
                            </div>
                          ) : (
                            <div className="card-content-txt is-empty">
                              {row.description || '暂无文档摘要'}
                            </div>
                          )}
                        </div>
                      </div>
                      <div className="card-bottom">
                        <div className="card-tags">
                          {rowTags.length > 0 ? (
                            <>
                              <span className="card-tag-chip" title={rowTags[0].name}>{rowTags[0].name}</span>
                              {rowTags.length > 1 && (
                                <span
                                  className="card-tag-overflow"
                                  title={rowTags.slice(1).map((t) => t.name).join('、')}
                                >
                                  +{rowTags.length - 1}
                                </span>
                              )}
                            </>
                          ) : (
                            <span className="row-muted">—</span>
                          )}
                        </div>
                        <span className="card-time">{formatSize(row.file_size)}</span>
                      </div>
                    </div>
                  )
                })}
                </div>
              </div>
            )}

            {hasInFlight && <div className="no_content">解析进行中 · 自动刷新…</div>}
          </div>
        )}
      </div>
      </div>

      {/* 文档详情抽屉（源侧 .doc-main-drawer） */}
      {drawerDoc && (
        <KbDocDrawer
          knowledge={drawerDoc}
          showToast={showToast}
          onClose={() => setDrawerDoc(null)}
        />
      )}

      {/* ── ⋯ 菜单的三个弹层（Trace / 移动到… / 编辑标签）────────────── */}

      {/* 1. 查看 Trace · 对齐源站：右侧抽屉装「完整模式」的处理流水线
          （`doc-content.vue:1639-1655`：t-drawer placement=right size=820，
           内嵌 KnowledgeProcessingTimeline）。源站注释 :2021-2023 明确
           「只开 trace 抽屉，不弹文档详情抽屉」。
          ⚠️ 不再自造「解析任务列表」——那条链的字段（title/spans/attempts）
             与后端 KnowledgeSpansDto 一个都对不上，已删除。 */}
      {traceState && createPortal(
        <div className="kb-scope">
          <div
            role="presentation"
            onClick={() => setTraceState(null)}
            style={{ position: 'fixed', inset: 0, zIndex: 2050, background: 'rgba(0,0,0,0.45)' }}
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label={`处理流水线 · ${traceState.row.title || traceState.row.file_name || traceState.row.id}`}
            style={{
              position: 'fixed', top: 0, right: 0, bottom: 0, zIndex: 2100,
              width: 'min(820px, 92vw)',
              display: 'flex', flexDirection: 'column',
              background: 'var(--td-bg-color-container, #fff)',
              boxShadow: '-6px 0 24px rgba(0,0,0,.12)',
            }}
          >
            <div className="kp-drawer-shell">
              <KbProcessingTimeline
                knowledgeId={traceState.row.id}
                parseStatus={traceState.row.parse_status}
                docTitle={traceState.row.title || traceState.row.file_name || undefined}
                showClose
                onClose={() => setTraceState(null)}
                fetchSpans={fetchSpans}
              />
            </div>
          </div>
        </div>,
        document.body,
      )}

      {/* 2. 移动到…（跨知识库） */}
      {moveTargetState && (
        <PanelModal
          title={`移动「${moveTargetState.row.title || moveTargetState.row.file_name || moveTargetState.row.id}」到…`}
          onClose={() => { if (!moveTargetState.moving) setMoveTargetState(null) }}
        >
          {moveTargetState.loading && <p style={PANEL_HINT_STYLE}>正在加载目标知识库…</p>}
          {!moveTargetState.loading && moveTargetState.error && (
            <p style={PANEL_ERROR_STYLE}>目标知识库加载失败：{moveTargetState.error}</p>
          )}
          {!moveTargetState.loading && !moveTargetState.error && (
            <>
              {moveTargetState.targets.length === 0 ? (
                <p style={PANEL_HINT_STYLE}>没有其它知识库可移入（需要先创建第二个知识库）</p>
              ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {moveTargetState.targets.map((t) => (
                    <li
                      key={t.id}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                        gap: 12, padding: '8px 10px',
                        border: '1px solid var(--td-component-stroke)',
                        borderRadius: 'var(--app-radius-sm)',
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontSize: 'var(--app-text-base)', fontWeight: 600 }}>{t.name}</div>
                        <div style={PANEL_META_STYLE}>
                          {kbTypeLabel(t.type)} · 已有 {t.knowledge_count} 个文档
                        </div>
                      </div>
                      <button
                        type="button"
                        className="doc-filter-toggle"
                        disabled={moveTargetState.moving !== null}
                        onClick={() => void doMoveToTarget(t.id)}
                      >
                        {moveTargetState.moving === t.id ? '移动中…' : '移到此处'}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <p style={PANEL_HINT_STYLE}>
                移动是<b>同步</b>完成的（本仓跨库移动不是异步任务）。⚠️ 目标知识库的切分/嵌入配置
                可能与当前库不同，移动后如需按新配置重切，请手动「重建知识」。
              </p>
            </>
          )}
        </PanelModal>
      )}

      {/* 3. 编辑标签（单行） */}
      {rowTagState && (
        <PanelModal
          title={`编辑标签 · ${rowTagState.row.title || rowTagState.row.file_name || rowTagState.row.id}`}
          onClose={() => { if (!rowTagState.saving) setRowTagState(null) }}
        >
          {tags.length === 0 ? (
            <p style={PANEL_HINT_STYLE}>本知识库还没有标签，请先在「筛选 → 管理」里创建</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {tags.map((t) => {
                const checked = rowTagState.selected.includes(t.id)
                return (
                  <label
                    key={t.id}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      fontSize: 'var(--app-text-base)', cursor: 'pointer',
                    }}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() =>
                        setRowTagState((prev) => {
                          if (!prev) return prev
                          const next = checked
                            ? prev.selected.filter((x) => x !== t.id)
                            : [...prev.selected, t.id]
                          return { ...prev, selected: next }
                        })
                      }
                    />
                    <span
                      style={{
                        display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
                        flex: 'none', background: t.color || '#94a3b8',
                      }}
                    />
                    <span>{t.name}</span>
                  </label>
                )
              })}
            </div>
          )}
          {rowTagState.error && <p style={PANEL_ERROR_STYLE}>保存失败：{rowTagState.error}</p>}
          <p style={PANEL_HINT_STYLE}>
            标签是<b>整集合替换</b>语义：这里勾选的结果就是该文档最终的标签集合。
          </p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 10 }}>
            <button
              type="button"
              className="doc-filter-toggle"
              disabled={rowTagState.saving}
              onClick={() => setRowTagState(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="doc-filter-toggle"
              disabled={rowTagState.saving || tags.length === 0}
              onClick={() => void saveRowTags()}
            >
              {rowTagState.saving ? '保存中…' : '保存'}
            </button>
          </div>
        </PanelModal>
      )}
    </div>
  )
}

/**
 * 文档列表页的弹层容器（内联样式，⛔ 不动全局 CSS）。
 *
 * <p>⚠️ 必须 `position: fixed` + 高 zIndex：文档列表在 `.kb-scene-panel` 里，
 * 祖先带 `overflow:hidden`，用 absolute 会被裁掉。
 */
/**
 * 文档行 ⋯ 菜单的图标（对齐源码 `DocumentActionMenu.vue` 的 `<t-icon name="x" />`）。
 *
 * <p><b>WHY 走 sprite 而不是内联 path</b>：源站用 tdesign 图标字体运行时，本仓没有。
 * `IconSprite` 里已有同 path 的 symbol（数据取自官方包 tdesign-icons-vue-next@0.4.12），
 * 这里只负责 `<use>` 引用 —— 图标数据只有一份，改的时候改 sprite 即可。
 *
 * <p>⚠️ 尺寸/颜色必须由 CSS 控：源码是 `.doc-action-menu-item .icon{font-size:var(--app-text-2xl);color:...}`，
 * 而 `<svg>` 不认 font-size。⇒ 用 `width/height:1em` 继承父级 font-size，`stroke:currentColor` 继承 color。
 * 这条规则已在 `styles/kb/kb-shared.css` 的 `.doc-action-menu-item .icon` 里覆盖（svg 也命中）。
 */
function MenuIcon({ name }: { name: string }) {
  return (
    <svg className="icon" width="1em" height="1em" aria-hidden="true">
      <use href={`#t-icon-${name}`} />
    </svg>
  )
}

function PanelModal({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: React.ReactNode
}) {
  /**
   * Esc 只关本弹层。
   *
   * <p>⭐⭐ 必须**捕获阶段 + stopPropagation**：`App.tsx` 在 window 上也监听 Esc
   * （非对话页按 Esc 会 `setWorkspacePage('chat')` 把整个知识库场景退掉）。
   * 实测：不加这一段时，按一次 Esc 会连场景一起关，用户以为「弹层把页面弄崩了」。
   */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      e.preventDefault()
      onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  return createPortal(
    <div className="kb-scope" style={{ display: 'contents' }}>
      <div
        role="presentation"
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, zIndex: 1700,
          background: 'rgba(0,0,0,0.45)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
        }}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-label={title}
          onClick={(e) => e.stopPropagation()}
          style={{
            width: '100%', maxWidth: 560, maxHeight: 'calc(100vh - 40px)',
            display: 'flex', flexDirection: 'column',
            background: 'var(--td-bg-color-container, #fff)',
            color: 'var(--td-text-color-primary, rgba(0,0,0,.9))',
            borderRadius: 'var(--app-radius-lg, 10px)',
            boxShadow: 'var(--td-shadow-3, 0 8px 30px rgba(0,0,0,.2))',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              gap: 12, padding: '12px 16px',
              borderBottom: '1px solid var(--td-component-stroke, #e7e7e7)',
              flexShrink: 0,
            }}
          >
            <span style={{ fontSize: 'var(--app-text-xl, 16px)', fontWeight: 600 }}>{title}</span>
            <button
              type="button" onClick={onClose} title="关闭" aria-label="关闭"
              style={{
                border: 'none', background: 'transparent', cursor: 'pointer',
                fontSize: 16, lineHeight: 1, padding: 4,
                color: 'var(--td-text-color-secondary, rgba(0,0,0,.6))',
              }}
            >
              ✕
            </button>
          </div>
          <div style={{ padding: '14px 16px', overflow: 'auto', minHeight: 0 }}>{children}</div>
        </div>
      </div>
    </div>,
    document.body
  )
}

const PANEL_META_STYLE = {
  fontSize: 'var(--app-text-xs, 11px)',
  color: 'var(--td-text-color-placeholder, rgba(0,0,0,.4))',
}

const PANEL_HINT_STYLE = {
  margin: '10px 0 0',
  fontSize: 'var(--app-text-xs, 11px)',
  color: 'var(--td-text-color-placeholder, rgba(0,0,0,.4))',
  lineHeight: 1.6,
}

const PANEL_ERROR_STYLE = {
  margin: '8px 0 0',
  fontSize: 'var(--app-text-sm, 12px)',
  color: 'var(--td-error-color, #e34d59)',
}

/** 递归渲染文件夹树（源侧 KbFolderTree.vue:40-130 的行结构）。 */
function FolderBranch({
  nodes,
  current,
  onSelect,
  onRename,
  depth = 0,
}: {
  nodes: KbFolderNode[]
  current: string
  onSelect: (path: string) => void
  onRename?: (node: KbFolderNode) => void
  /** 缩进层级（源站 `--kb-folder-depth`，CSS 里上限 4 级）。 */
  depth?: number
}) {
  if (nodes.length === 0) return null
  return (
    <div className="kb-folder-branch">
      {nodes.map((n) => {
        const hasChildren = (n.children?.length ?? 0) > 0
        return (
          <div key={n.path} style={{ position: 'relative' }}>
            <div
              className={`kb-folder-row${current === n.path ? ' active' : ''}${onRename ? ' is-editable' : ''}`}
              style={current === n.path
                ? {
                    ['--kb-folder-depth' as string]: depth,
                    background: 'color-mix(in srgb, var(--td-text-color-primary) 4%, var(--td-bg-color-container))',
                  }
                : { ['--kb-folder-depth' as string]: depth }}
              role="button"
              tabIndex={0}
              title={n.path}
              onClick={() => onSelect(n.path)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onSelect(n.path) }}
            >
              <span className="kb-folder-row__toggle-placeholder" aria-hidden="true">
                {hasChildren ? '▾' : ''}
              </span>
              <span className="kb-folder-row__icon">📁</span>
              <span className="kb-folder-row__label">{n.name}</span>
              <span className="kb-folder-row__trailing">
                <span className="kb-folder-row__count">{n.document_count}</span>
              </span>
            </div>
            {onRename && (
              <button
                type="button"
                className="kb-folder-row__more"
                onClick={(e) => { e.stopPropagation(); onRename(n) }}
                title="重命名 / 移动文件夹"
                aria-label={`重命名 ${n.name}`}
              >
                ✎
              </button>
            )}
            {hasChildren && (
              <div className="kb-folder-children">
                <FolderBranch
                  nodes={n.children}
                  current={current}
                  onSelect={onSelect}
                  onRename={onRename}
                  depth={depth + 1}
                />
              </div>
            )}
          </div>
        )
      })}
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
