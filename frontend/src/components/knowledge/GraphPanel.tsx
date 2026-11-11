import { useCallback, useEffect, useMemo, useState } from 'react'
import { graphApi } from '@/api/graph'
import type { GraphSearchResult } from '@/api/graph'
import { ApiError } from '@/api/rest'
import { KbWikiGraph } from './KbWikiGraph'
import type { GraphTypeSchema } from './KbWikiGraph'
import {
  buildSvgGraph,
  ENTITY_SLUG,
  DOC_SLUG,
} from './graphAdapter'

interface GraphPanelProps {
  baseId: string
  baseName?: string
  /** 文档列表（用于「重建某篇文档的图谱」下拉）。 */
  knowledgeOptions?: Array<{ id: string; title: string }>
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/**
 * 关系图谱的节点视觉方案 —— **复用 Wiki 图谱同一套动画与交互**
 * （用户 2026-11-11：「图谱按照 wiki 图谱动画样式来 完整实现」）。
 *
 * <p>⭐ 图例只保留三种真实存在的类型：文档（hub）/ 实体 / 概念。
 * ⛔ 不照搬 Wiki 的「综合 / 对比」—— 那两类在实体-关系图里没有对应物，
 * 点了也不会有任何变化（假图例）。
 *
 * <p>⚠️ 必须是**模块级常量**：`KbWikiGraph` 的渲染依赖 `schema` 引用稳定，
 * 每次渲染都新建对象会让整张图重建、力导向从 alpha=1 重跑（画面会跳）。
 */
const ENTITY_SCHEMA: GraphTypeSchema = {
  colors: {
    summary: '#0052d9',
    entity: '#2ba471',
    concept: '#e37318',
    synthesis: '#0594fa',
    comparison: '#d54941',
    index: '#8c8c8c',
  },
  labels: {
    summary: '文档',
    entity: '实体',
    concept: '概念',
    synthesis: '关系',
    comparison: '属性',
    index: 'Index',
  },
  themes: {
    summary: 'primary',
    entity: 'success',
    concept: 'warning',
    synthesis: 'primary',
    comparison: 'danger',
    index: 'default',
  },
  legend: [
    { type: 'summary', label: '文档', color: '#0052d9' },
    { type: 'entity', label: '实体', color: '#2ba471' },
    { type: 'concept', label: '概念', color: '#e37318' },
  ],
  all: ['summary', 'entity', 'concept'],
}

/**
 * 知识库图谱面板 · W7（接真实后端）。
 *
 * <p><b>⭐ 与 WikiPanel 的图谱视图不是同一个东西</b>：
 * <ul>
 *   <li>WikiPanel 的图谱 = <b>Wiki 页面之间的 [[链接]]</b>（页面级）</li>
 *   <li>本面板 = <b>文档中抽取的实体与关系</b>（图谱抽取产物，`kb_graph_entity`）</li>
 * </ul>
 * 两者<b>数据源完全不同</b>，⛔ 不要合并数据；但<b>渲染引擎共用</b>
 * （`KbWikiGraph`：SVG + 力导向 + 集群轮廓 + 标签避让 + pan/zoom + 展开子图），
 * 这是用户 2026-11-11 明确要求的「按 wiki 图谱动画样式来」。
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
    setSelectedDoc('')
  }, [load])

  /** 文档 id → 标题（喂给文档 hub 节点）。 */
  const docTitles = useMemo(
    () => new Map(knowledgeOptions.map((k) => [k.id, k.title] as const)),
    [knowledgeOptions]
  )

  /** 后端结果 → SVG 引擎输入（文档 hub + 实体 + 关系边）。 */
  const svgGraph = useMemo(() => buildSvgGraph(data, docTitles), [data, docTitles])

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
      await load('')
    } catch (e) {
      showToast(e instanceof ApiError ? e.message : '重建失败', 'info')
    } finally {
      setRebuilding(false)
    }
  }, [baseId, selectedDoc, showToast, load])

  /**
   * 节点抽屉正文。
   *
   * <p>关系图谱的节点是**实体**（或文档 hub），没有 Wiki 页面正文 ⇒
   * 这里渲染「属性 + 关联关系 + 证据片段」，数据全部来自已加载的 `GraphSearchResult`
   * （⛔ 不再发请求：`source_chunks` 后端已经一并返回）。
   */
  const renderNodeBody = useCallback(
    (slug: string) => {
      if (!data) return null
      const isDoc = slug.startsWith(DOC_SLUG)
      const rawId = isDoc ? slug.slice(DOC_SLUG.length) : slug.slice(ENTITY_SLUG.length)

      if (isDoc) {
        const own = data.entities.filter((e) => e.knowledge_id === rawId)
        return (
          <div className="kb-entity-detail">
            <p className="kb-entity-detail-meta">
              该文档抽取到 <b>{own.length}</b> 个实体、<b>{data.relationships.filter((r) => own.some((e) => e.id === r.source_id || e.id === r.target_id)).length}</b> 条关系。
            </p>
            <p className="kb-entity-detail-meta">双击图谱中的实体节点可聚焦到它的子图。</p>
          </div>
        )
      }

      const entity = data.entities.find((e) => e.id === rawId)
      if (!entity) return <p className="wiki-empty-desc">实体已不在当前图谱中</p>

      const rels = data.relationships.filter(
        (r) => r.source_id === entity.id || r.target_id === entity.id
      )
      const nameOf = new Map(data.entities.map((e) => [e.id, e.name] as const))

      return (
        <div className="kb-entity-detail">
          {entity.entity_type ? (
            <p className="kb-entity-detail-meta">类型：{entity.entity_type}</p>
          ) : null}
          {entity.knowledge_id ? (
            <p className="kb-entity-detail-meta">
              所属文档：{docTitles.get(entity.knowledge_id) || '（文档已删除）'}
            </p>
          ) : null}

          <h4 className="kb-entity-detail-title">属性（{entity.attributes?.length ?? 0}）</h4>
          {entity.attributes?.length ? (
            <ul className="kb-entity-detail-list">
              {entity.attributes.map((a, i) => (
                <li key={i}>{a}</li>
              ))}
            </ul>
          ) : (
            <p className="wiki-empty-desc">该实体没有抽取到属性</p>
          )}

          <h4 className="kb-entity-detail-title">关系（{rels.length}）</h4>
          {rels.length ? (
            <ul className="kb-entity-detail-list">
              {rels.map((r) => {
                const out = r.source_id === entity.id
                const other = nameOf.get(out ? r.target_id : r.source_id) || '未知'
                return (
                  <li key={r.id}>
                    {out ? `→ ${r.type || '相关'} → ` : `← ${r.type || '相关'} ← `}
                    <b>{other}</b>
                  </li>
                )
              })}
            </ul>
          ) : (
            <p className="wiki-empty-desc">该实体没有已抽取的关系</p>
          )}
        </div>
      )
    },
    [data, docTitles]
  )

  // ── 渲染 ──────────────────────────────────────────────────────
  // DOM 外壳照源站（KnowledgeBase.vue / WikiBrowser.vue）：
  //   .wiki-main-area > .wiki-browser > .wiki-graph
  // 有数据时把整个画布交给 `KbWikiGraph`（与 Wiki 图谱同一个引擎）。
  if (loading && !data) {
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-graph">
            {/* ⚠️ `is-open` 不能省：kb-04.css 里 `.wiki-graph-empty{display:none}`，
                只有 `.is-open` 才 `display:flex`。 */}
            <div className="wiki-reader-empty wiki-graph-empty is-open">
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
            <div className="wiki-reader-empty wiki-graph-empty is-open">
              <div className="wiki-empty-icon" aria-hidden="true">◍</div>
              <p className="wiki-empty-title">图谱加载失败</p>
              <p className="wiki-empty-desc">{error}</p>
              <button
                type="button"
                className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                style={{ marginTop: 16 }}
                onClick={() => void load('')}
              >
                <span className="t-button__text">重试</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  if (svgGraph.nodes.length === 0) {
    return (
      <div className="wiki-main-area">
        <div className="wiki-browser">
          <div className="wiki-graph">
            {/* ⚠️ `is-open` 不能省（见上） */}
            <div className="wiki-reader-empty wiki-graph-empty is-open">
              <div className="wiki-empty-icon" aria-hidden="true">◍</div>
              <p className="wiki-empty-title">还没有图谱数据</p>
              {/* ⭐ 文案必须与「自动抽取」的现行行为一致（2026-11-11）。
                  旧文案写「请等解析完成后重建一次」，会让用户以为**本来就要手动**
                  —— 实际开启图谱后上传的文档会在解析完成时自动派发抽取任务。
                  只有两种情况需要手动点重建：① 开启图谱之前就已入库的存量文档；
                  ② 当时抽取失败 / 没配模型。⛔ 不要把文案改回「必须手动」。
                  ⚠️ maxWidth：`.wiki-reader-empty` 是 flex-column + align-items:center，
                  子元素按**内容宽度**排布 ⇒ 这段文案会单行铺到 800+ px（实测 862px）。
                  限宽后自然折行。 */}
              <p className="wiki-empty-desc" style={{ maxWidth: 560 }}>
                开启「知识图谱」后，新上传的文档会在解析完成时自动抽取实体与关系，无需手动操作。
                若这是开启图谱之前就已入库的文档，或当时抽取失败，请在下方选中文档点「重建图谱」补抽。
                纯图片文档可能抽不到文字实体。
              </p>
              {/* ⚠️ 重建入口是本仓特有（源站图谱视图没有「重建」）⇒ 没有对应源站类。
                  `.wiki-reader-empty` 只给纵向居中，动作行用手写 flex 行内样式。 */}
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
                  /* ⚠️ 必须限宽：`<select>` 的固有宽度跟随**最长 option**（实测被一个
                     30 字的文档名撑到 563px），在居中的空态里显得极不协调。 */
                  style={{ width: 260, minWidth: 0 }}
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
        {svgGraph.truncated ? (
          /* ⚠️ 截断必须显式告诉用户（⛔ 不能静默少画）：KbWikiGraph 状态卡只报
             「已展示全部节点」，不知道后端还有多少没画。 */
          <div className="wiki-graph-truncate-tip">
            当前库实体过多，按关系数展示前 {svgGraph.nodes.length} 个（共 {svgGraph.total} 个）
          </div>
        ) : null}
        <KbWikiGraph
          baseId={baseId}
          pages={[]}
          dataOverride={svgGraph}
          schema={ENTITY_SCHEMA}
          renderNodeBody={renderNodeBody}
          searchPlaceholder="搜索实体…"
          searchEmptyText="没有找到匹配的实体"
          statusTitle={baseName ? `${baseName} · 概览` : '全库概览'}
        />
      </div>
    </div>
  )
}
