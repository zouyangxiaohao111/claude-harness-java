import { useCallback, useEffect, useMemo, useState } from 'react'
import { graphApi } from '@/api/graph'
import type { GraphSearchResult } from '@/api/graph'
import { ApiError } from '@/api/rest'
import { KnowledgeGraphCanvas } from './KnowledgeGraphCanvas'
import type { GraphNodeInput } from './graphTypes'
import { buildGraphView } from './graphAdapter'

interface GraphPanelProps {
  baseId: string
  baseName?: string
  /** 文档列表（用于「重建某篇文档的图谱」下拉）。 */
  knowledgeOptions?: Array<{ id: string; title: string }>
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/**
 * 知识库图谱面板 · W7（接真实后端，替代 MOCK）。
 *
 * <p><b>⭐ 与 WikiPanel 里的图谱视图不是同一个东西</b>：
 * <ul>
 *   <li>WikiPanel 的图谱 = <b>Wiki 页面之间的 [[链接]]</b>（页面级）</li>
 *   <li>本面板 = <b>文档中抽取的实体与关系</b>（图谱抽取产物，`kb_graph_entity`）</li>
 * </ul>
 * 两者数据源完全不同，⛔ 不要合并成一个视图。
 *
 * <p><b>空态必须分成因</b>（三次踩过的坑）：
 * ① 库还没抽过图谱 → 引导去重建；
 * ② 开了图谱但文档还没解析完成 → 说「等解析完」；
 * ③ 抽过但一条都没抽到（纯图片文档）→ 说「这份文档没有可抽取的文字」。
 * 三种都说成「还没有图谱」会让用户反复重试一件不会成功的事。
 */
export function GraphPanel({ baseId, baseName, knowledgeOptions = [], showToast }: GraphPanelProps) {
  const [loading, setLoading] = useState(false)
  const [rebuilding, setRebuilding] = useState(false)
  const [data, setData] = useState<GraphSearchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [selectedDoc, setSelectedDoc] = useState('')

  const load = useCallback(
    async (q: string) => {
      setLoading(true)
      setError(null)
      try {
        const r = await graphApi.get(baseId, q)
        setData(r)
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : '图谱加载失败'
        setError(msg)
        setData(null)
      } finally {
        setLoading(false)
      }
    },
    [baseId]
  )

  // ⚠️ 换知识库必须重新拉（否则显示的是上一个库的图）
  useEffect(() => {
    void load('')
    setQuery('')
  }, [load])

  const view = useMemo(() => buildGraphView(data, baseName || '知识库'), [data, baseName])

  const handleSelect = useCallback((n: GraphNodeInput | null) => {
    if (n) showToast(`实体：${n.name}`, 'info')
  }, [showToast])

  const handleRebuild = useCallback(async () => {
    if (!selectedDoc) {
      showToast('请先选择要重建图谱的文档', 'info')
      return
    }
    setRebuilding(true)
    try {
      const r = await graphApi.rebuild(baseId, selectedDoc)
      if (r.status === 'done') {
        showToast(`图谱已重建：${r.detail}`, 'success')
      } else if (r.status === 'skipped') {
        // ⭐ 跳过要说清原因，否则用户以为成功了
        showToast(`未抽取：${r.detail}`, 'info')
      } else if (r.status === 'superseded') {
        showToast('文档已被重新解析，请稍后重试', 'info')
      } else {
        showToast(`重建失败：${r.detail}`, 'info')
      }
      await load(query)
    } catch (e) {
      showToast(e instanceof ApiError ? e.message : '重建失败', 'info')
    } finally {
      setRebuilding(false)
    }
  }, [baseId, selectedDoc, showToast, load, query])

  // ── 渲染 ──────────────────────────────────────────────────────
  if (loading && !data) {
    return <div className="kb-wiki-loading">正在加载图谱…</div>
  }
  if (error) {
    return (
      <div className="kb-graph-state">
        <p className="kb-graph-state-title">图谱加载失败</p>
        <p className="kb-graph-state-desc">{error}</p>
        <button type="button" className="kb-btn" onClick={() => void load(query)}>
          重试
        </button>
      </div>
    )
  }

  const hasEntities = view.nodeCount > 0
  if (!hasEntities) {
    return (
      <div className="kb-graph-state">
        <p className="kb-graph-state-title">还没有图谱数据</p>
        <p className="kb-graph-state-desc">
          图谱是从「已解析完成」的文档里抽取实体与关系得到的。
          如果你刚上传文档，请等解析完成后重建一次；如果是纯图片文档，可能抽不到文字实体。
        </p>
        <div className="kb-graph-rebuild">
          <select
            className="kb-input"
            value={selectedDoc}
            onChange={(e) => setSelectedDoc(e.target.value)}
          >
            <option value="">选择要抽取的文档…</option>
            {knowledgeOptions.map((k) => (
              <option key={k.id} value={k.id}>{k.title}</option>
            ))}
          </select>
          <button
            type="button"
            className="kb-btn kb-btn-primary"
            disabled={rebuilding || !selectedDoc}
            onClick={() => void handleRebuild()}
          >
            {rebuilding ? '抽取中…' : '重建图谱'}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="kb-graph-panel">
      <div className="kb-graph-toolbar">
        <input
          className="kb-input kb-graph-search"
          placeholder="按实体名筛选…（回车）"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void load(query) }}
        />
        <span className="kb-graph-stat">
          {view.nodeCount} 个实体 / {view.edges.length} 条关系
          {view.truncated ? `（已按度数截断到前 ${view.nodeCount} 个）` : ''}
          {data?.meta?.truncated ? ' · 后端已截断' : ''}
        </span>
        <button
          type="button"
          className="kb-btn"
          onClick={() => void load(query)}
          disabled={loading}
        >
          {loading ? '刷新中…' : '刷新'}
        </button>
      </div>
      <div className="kb-graph-canvas">
        <KnowledgeGraphCanvas nodes={view.nodes} edges={view.edges} onSelect={handleSelect} />
      </div>
    </div>
  )
}
