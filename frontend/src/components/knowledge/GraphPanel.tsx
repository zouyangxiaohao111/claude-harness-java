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
  // DOM 外壳照源站（KnowledgeBase.vue / WikiBrowser.vue）：
  //   .wiki-main-area > .wiki-browser > .wiki-graph
  //     > .wiki-graph-search-container > .wiki-graph-search-row > .wiki-graph-search
  //     > .wiki-graph-canvas（由 KnowledgeGraphCanvas 渲染）
  //     > .wiki-reader-empty.wiki-graph-empty（空态覆盖层，absolute inset 0）
  if (loading && !data) {
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-graph">
            <div className="wiki-reader-empty wiki-graph-empty">
              <p className="wiki-empty-desc">正在加载图谱…</p>
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
          <div className="wiki-graph">
            <div className="wiki-reader-empty wiki-graph-empty">
              <div className="wiki-empty-icon" aria-hidden="true">◍</div>
              <p className="wiki-empty-title">图谱加载失败</p>
              <p className="wiki-empty-desc">{error}</p>
              <button
                type="button"
                className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                style={{ marginTop: 16 }}
                onClick={() => void load(query)}
              >
                <span className="t-button__text">重试</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  const hasEntities = view.nodeCount > 0
  if (!hasEntities) {
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-graph">
            <div className="wiki-reader-empty wiki-graph-empty">
              <div className="wiki-empty-icon" aria-hidden="true">◍</div>
              <p className="wiki-empty-title">还没有图谱数据</p>
              <p className="wiki-empty-desc">
                图谱是从「已解析完成」的文档里抽取实体与关系得到的。
                如果你刚上传文档，请等解析完成后重建一次；如果是纯图片文档，可能抽不到文字实体。
              </p>
              {/* ⚠️ 重建入口是本仓特有（源站图谱视图没有「重建」）⇒ 没有对应源站类。
                  `.wiki-reader-empty` 只给纵向居中，动作行用手写 flex 行内样式
                  （与 W3 WikiPanel 空态动作行同一写法；⛔ 不新增 CSS 类）。 */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 8,
                  marginTop: 16,
                  flexWrap: 'wrap',
                }}
              >
                <select
                  className="t-input"
                  value={selectedDoc}
                  onChange={(e) => setSelectedDoc(e.target.value)}
                  aria-label="选择要抽取图谱的文档"
                >
                  <option value="">选择要抽取的文档…</option>
                  {knowledgeOptions.map((k) => (
                    <option key={k.id} value={k.id}>{k.title}</option>
                  ))}
                </select>
                <button
                  type="button"
                  className={`t-button t-button--variant-base t-button--theme-primary t-size-m${rebuilding || !selectedDoc ? ' t-is-disabled' : ''}`}
                  disabled={rebuilding || !selectedDoc}
                  onClick={() => void handleRebuild()}
                >
                  <span className="t-button__text">{rebuilding ? '抽取中…' : '重建图谱'}</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="wiki-main-area">
      <div className="wiki-browser">
        <div className="wiki-graph">
          <div className="wiki-graph-search-container">
            <div className="wiki-graph-search-row">
              <div className="wiki-graph-search">
                <div className="t-input__wrap">
                  <div className="t-input t-input--prefix">
                    <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
                    <input
                      className="t-input__inner"
                      placeholder="按实体名筛选…（回车）"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') void load(query) }}
                    />
                  </div>
                </div>
              </div>
              {/* ⚠️ 「刷新」是本仓特有（源站由响应式数据自动重绘，没有刷新按钮）。
                  位置对应源站的 `.wiki-graph-help-trigger`（同行右侧 32px 方块），
                  类名用 t-button 系列，⛔ 不借用 help-trigger 类。 */}
              <button
                type="button"
                className={`t-button t-button--variant-outline t-button--theme-default t-size-s${loading ? ' t-is-disabled' : ''}`}
                style={{ flexShrink: 0 }}
                onClick={() => void load(query)}
                disabled={loading}
              >
                <span className="t-button__text">{loading ? '刷新中…' : '刷新'}</span>
              </button>
            </div>
            {/* 源站把「当前视图摘要」做成 legend 底部的 .wiki-graph-status-card。
                本仓 legend 由 KnowledgeGraphCanvas 持有，而统计数字/截断原因属于本面板
                （且 ⛔ 不许改子组件 props 形状）⇒ 放到搜索容器第二行。
                ⚠️ `.wiki-graph-status-card` 自身只有 `border-top:1px dashed` + `padding-top:8px`
                （设计上是塞在白卡 legend 里的分隔项，不是独立卡片）；直接放在画布上会让
                深色文字压在深色画布上。⇒ 行内补 legend 同款卡片外观（值取自 kb-04.css
                的 `.wiki-graph-legend`，var 后带 token 兜底，独立演示页也能降级）。 */}
            <div
              className="wiki-graph-status-card"
              style={{
                background: 'var(--td-bg-color-container, #fff)',
                border: '1px solid var(--td-component-stroke, #e7e7e7)',
                borderRadius: 'var(--app-radius-sm, 6px)',
                boxShadow: 'var(--td-shadow-1, 0 1px 10px rgba(0,0,0,.05))',
                padding: '8px 12px',
              }}
            >
              <div className="status-card-header">
                <span aria-hidden="true">◍</span>
                <span className="status-card-title">全库概览</span>
              </div>
              <div className="status-card-primary">
                {view.nodeCount} 个实体 / {view.edges.length} 条关系
              </div>
              {view.truncated || data?.meta?.truncated ? (
                <div className="status-card-secondary">
                  {view.truncated ? `已按度数截断到前 ${view.nodeCount} 个` : ''}
                  {view.truncated && data?.meta?.truncated ? ' · ' : ''}
                  {data?.meta?.truncated ? '后端已截断' : ''}
                </div>
              ) : null}
            </div>
          </div>
          <KnowledgeGraphCanvas nodes={view.nodes} edges={view.edges} onSelect={handleSelect} />
        </div>
      </div>
    </div>
  )
}
