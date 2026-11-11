import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import { wikiApi } from '@/api/wiki'
import type { WikiGraph, WikiPage } from '@/api/wiki'
import { renderWikiMarkdown, WIKI_LINK_ATTR } from '@/utils/wikiMarkdown'
import {
  buildGraphLayout,
  graphClusterOutline,
  graphNodeRadius,
  initialGraphViewport,
  landmarkTitle,
  stepGraphLayout,
  visibleGraphLabels,
} from './kbWikiGraphLayout'
import type { GraphNodeLike, LabelBox, Point, SimNode } from './kbWikiGraphLayout'

/**
 * Wiki 图谱 · 逐行移植设计稿 04（真机 `WikiBrowser.vue` 的 graph 视图）。
 *
 * <h2>真源（唯一规格，⛔ 不要自己设计）</h2>
 * <ol>
 *   <li>`C:/Users/WIN/.nexusai/artifacts/sess-189e74fa/deliverables/kb-design/04-图谱.html`
 *       —— `<style>` 区（DOM 骨架 / 图例 / 抽屉数值）</li>
 *   <li>同页 `<script>` 区（2015 行，导出到 `D:/tmp/graph04.js`）—— 全部行为</li>
 * </ol>
 *
 * <h2>与旧实现（KnowledgeGraphCanvas）的关键差别</h2>
 * <ul>
 *   <li>渲染：<b>SVG</b>（`g.graph-root` &gt; `g.edge-layer` + `g.node-layer`），⛔ 不是 canvas</li>
 *   <li>布局：力导向 `stepGraphLayout`，`alpha *= 0.985`，`&lt;0.02` 停</li>
 *   <li>动画：`setInterval(tick, 16)`，⛔ 不用 rAF（后台 tab rAF 不派发 ⇒ 卡在第 0 帧）</li>
 *   <li>数据：Wiki 页面（`type/name` slug），不是文档抽出的实体</li>
 * </ul>
 *
 * <h2>⚠️ 布局外壳</h2>
 * `.wiki-graph` 需要 `flex:1; min-height:0; position:relative`；
 * `.wiki-graph-canvas` 需要 `position:absolute; inset:0`。
 * 缺这一条高度链 ⇒ 画布塌成 0，`clientWidth/Height` 全 0，SVG 看不见。
 * 画布样式（kb-04.css）只给了 `width/height:100%`，absolute 由本文件行内补齐
 * （⛔ 不改 `styles/kb/*.css`）。
 *
 * <h2>⚠️ viewBox 必须用画布实际尺寸</h2>
 * 真机 1920 全屏 → 画布 1604（1920−260 侧栏−56），viewBox 1604×947 与画布 1:1
 * ⇒ 节点 r=11 渲染成 22px。本仓浮层宽度不同，所以每次渲染现读
 * `canvas.clientWidth/clientHeight`，⛔ 不写死 1604。
 * `preserveAspectRatio="xMidYMid slice"`：默认 meet 在宽高比不匹配时会把
 * r=11 的节点缩成 3px 的 bbox（实测）。
 */

// ══════════════════════════════════════════════════════════════
// 常量 —— 照抄 design-04 script:15-34
// ══════════════════════════════════════════════════════════════

/** 节点类型 → 填充色（design-04 script:15）。 */
const NODE_COLOR: Record<string, string> = {
  summary: '#0052d9',
  entity: '#2ba471',
  concept: '#e37318',
  synthesis: '#0594fa',
  comparison: '#d54941',
  index: '#8c8c8c',
}

/** 节点类型 → t-tag 主题（design-04 script:19）。 */
const TYPE_THEME: Record<string, string> = {
  summary: 'primary',
  entity: 'success',
  concept: 'warning',
  synthesis: 'primary',
  comparison: 'danger',
  index: 'default',
}

/** 节点类型 → 中文（design-04 script:23）。 */
const TYPE_LABEL: Record<string, string> = {
  knowledge: '知识',
  summary: '摘要',
  entity: '实体',
  concept: '概念',
  synthesis: '综合',
  comparison: '对比',
  index: 'Index',
}

/** 图例里的 5 项（design-04 markup:255-259，⛔ 不含 index）。 */
const LEGEND_TYPES: Array<{ type: string; label: string; color: string }> = [
  { type: 'summary', label: '摘要', color: '#0052d9' },
  { type: 'entity', label: '实体', color: '#2ba471' },
  { type: 'concept', label: '概念', color: '#e37318' },
  { type: 'synthesis', label: '综合', color: '#0594fa' },
  { type: 'comparison', label: '对比', color: '#d54941' },
]

const GRAPH_EDGE_COLOR = '#aeb5c0'
const GRAPH_EDGE_WIDTH = '0.8'
const GRAPH_EDGE_OPACITY = '0.42'
/** 系统页类型：`index` 页不计入「扩张边缘」（design-04 script:30）。 */
const GRAPH_SYSTEM_PAGE_TYPES: Record<string, number> = { index: 1 }
const SVGNS = 'http://www.w3.org/2000/svg'
const ALL_TYPES = ['summary', 'entity', 'concept', 'synthesis', 'comparison', 'index']

/**
 * Wiki 图谱的默认视觉方案（= 上面前 5 个常量的组合）。
 *
 * <p>⛔ 数值逐字保留，`KbWikiGraph` 不传 `schema` 时行为与本文件改造前**完全一致**。
 */
const WIKI_SCHEMA: GraphTypeSchema = {
  colors: NODE_COLOR,
  labels: TYPE_LABEL,
  themes: TYPE_THEME,
  legend: LEGEND_TYPES,
  all: ALL_TYPES,
}

// ══════════════════════════════════════════════════════════════
// 数据形状
// ══════════════════════════════════════════════════════════════

export interface GraphEdge {
  source: string
  target: string
  /** 反向边也存在（真机后端返回两行）⇒ 两端都画箭头。 */
  bidir: boolean
}

/**
 * 图谱画布的输入数据（两套图谱共用）。
 *
 * <p>⭐ 关系图谱（实体-关系）由 `graphAdapter.buildSvgGraph()` 产出同一形状后注入，
 * 从而复用本文件的全部渲染与交互。
 */
export interface GraphData {
  total: number
  nodes: GraphNodeLike[]
  edges: GraphEdge[]
}

/**
 * 后端 `GET /wiki/graph` → 画布数据。
 *
 * <p>后端只给 `{nodes:[{id,title,type}], edges:[{source,target}]}`，
 * 而布局要 `linkCount`（源站由 `link_count` 列给出）。这里按
 * 「**有向边原始行**里出现次数」现算 —— 与源站 `graphFrontierCount` 同口径
 * （A→B 与 B→A 各记 1，所以双向边算 2 度）。
 */
function mapGraph(raw: WikiGraph): GraphData {
  const degree = new Map<string, number>()
  raw.nodes.forEach((n) => degree.set(n.id, 0))
  raw.edges.forEach((e) => {
    if (degree.has(e.source)) degree.set(e.source, (degree.get(e.source) ?? 0) + 1)
    if (degree.has(e.target)) degree.set(e.target, (degree.get(e.target) ?? 0) + 1)
  })
  const pairSet = new Set(raw.edges.map((e) => e.source + '→' + e.target))
  const nodes: GraphNodeLike[] = raw.nodes.map((n) => ({
    slug: n.id,
    title: n.title || n.id,
    type: n.type || 'entity',
    linkCount: degree.get(n.id) ?? 0,
  }))
  const edges: GraphEdge[] = raw.edges.map((e) => ({
    source: e.source,
    target: e.target,
    bidir: pairSet.has(e.target + '→' + e.source),
  }))
  return { total: nodes.length, nodes, edges }
}

// ══════════════════════════════════════════════════════════════
// 引擎（命令式 DOM 部分的状态容器）
// ══════════════════════════════════════════════════════════════

interface RenderedEdge {
  line: SVGLineElement
  source: string
  target: string
  bidir: boolean
}

interface RenderedNode {
  g: SVGGElement
  circle: SVGCircleElement
  text: SVGTextElement
  activeRing: SVGCircleElement
  expansionRing: SVGCircleElement
  node: SimNode
}

interface RenderedCluster {
  group: SVGGElement
  anchorSlug: string
  labelWidth: number
}

interface PanZoom {
  getScale: () => number
  getTranslate: () => { x: number; y: number }
  flyTo: (x: number, y: number, scale?: number, duration?: number) => void
}

interface Engine {
  mode: 'overview' | 'ego'
  centerSlug: string
  nodes: SimNode[]
  edges: GraphEdge[]
  /** 未去重的有向边原始行（扩张边缘计数用）。 */
  rawEdges: Array<{ source: string; target: string }>
  nodeEls: RenderedNode[]
  edgeEls: RenderedEdge[]
  clusterEls: RenderedCluster[]
  contour: { path: SVGPathElement; members: Array<{ x: number; y: number; radius: number }> } | null
  adjacency: Map<string, Set<string>>
  selectedSlug: string | null
  highlightSlug: string | null
  hoverTimer: number | null
  showArrows: boolean
  animId: number
  panZoom: PanZoom | null
  refreshLabels: () => void
  canvas: HTMLDivElement | null
}

function emptyEngine(): Engine {
  return {
    mode: 'overview',
    centerSlug: '',
    nodes: [],
    edges: [],
    rawEdges: [],
    nodeEls: [],
    edgeEls: [],
    clusterEls: [],
    contour: null,
    adjacency: new Map(),
    selectedSlug: null,
    highlightSlug: null,
    hoverTimer: null,
    showArrows: true,
    animId: 0,
    panZoom: null,
    refreshLabels: () => {},
    canvas: null,
  }
}

/** 图例 / 抽屉里用到的 tdesign symbol（design-04 markup:137-146 逐字照抄）。 */
function GraphIconSprite() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }}
    >
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-focus">
        <path d="M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z" fill="transparent" />
        <path
          d="M19 12a7 7 0 0 1-7 7m7-7a7 7 0 0 0-7-7m7 7h3m-10 7a7 7 0 0 1-7-7m7 7v3M5 12a7 7 0 0 1 7-7m-7 7H2m10-7V2M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-chart-bubble">
        <g fill="transparent">
          <path d="M13 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM18 6a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM20 14a1 1 0 1 1-2 0 1 1 0 0 1 2 0Z" />
        </g>
        <path d="M21 21H3V3" stroke="currentColor" strokeWidth="2" strokeLinecap="square" />
        <g stroke="currentColor" strokeWidth="2" strokeLinecap="square">
          <path d="M18 6a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM19 15a1 1 0 1 1 0-2 1 1 0 0 1 0 2ZM13 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
        </g>
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-browse-off">
        <g stroke="currentColor" strokeWidth="2" strokeLinecap="square">
          <path d="M13.176 8.176a4.009 4.009 0 0 1 2.649 2.649m5.46 4.46A11.463 11.463 0 0 0 22.959 12C21.478 7.36 17.13 4 12 4c-.63 0-1.249.05-1.852.148m-4.34 1.66A11.53 11.53 0 0 0 1.041 12c1.48 4.64 5.827 8 10.958 8 2.28 0 4.404-.663 6.192-1.808L5.807 5.808ZM8 12c0-1.104.448-2.104 1.172-2.828l5.656 5.657A4 4 0 0 1 8 12ZM21 21 3 3" />
        </g>
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-browse">
        <g>
          <path
            d="M12 4C6.869 4 2.523 7.36 1.042 12c1.48 4.64 5.827 8 10.958 8 5.13 0 9.477-3.36 10.957-8C21.477 7.36 17.131 4 12 4Z"
            fill="transparent"
          />
          <path d="M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z" fill="transparent" />
          <path
            d="M12 4C6.869 4 2.523 7.36 1.042 12c1.48 4.64 5.827 8 10.958 8 5.13 0 9.477-3.36 10.957-8C21.477 7.36 17.131 4 12 4Z"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="square"
          />
          <path
            d="M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="square"
          />
        </g>
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-help-circle">
        <path
          d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z"
          fill="transparent"
        />
        <path
          d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
        <path
          d="M12 14.25V14c0-1.105.988-1.959 1.855-2.642A3 3 0 1 0 9.171 8M12 17.25h.003v.004H12v-.004Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-close">
        <path
          d="M16.95 7.05 12 12m0 0-4.95 4.95M12 12l4.95 4.95M12 12 7.05 7.05"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-rollback">
        <path
          d="M5.75 7H14a6 6 0 0 1 0 12H6.5M8 10.5 4.5 7 8 3.5"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-info-circle">
        <path
          d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z"
          fill="transparent"
        />
        <path
          d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
        <path
          d="M12 16.5V11m0-3.5h-.004v-.004H12V7.5Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="kb-g-icon-search">
        <path
          d="M15.803 15.803A7.5 7.5 0 1 1 5.197 5.197a7.5 7.5 0 0 1 10.606 10.606Z"
          fill="transparent"
        />
        <path
          d="m15.803 15.804 5.303 5.303M15.803 15.803A7.5 7.5 0 1 1 5.197 5.197a7.5 7.5 0 0 1 10.606 10.606Z"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="square"
        />
      </symbol>
    </svg>
  )
}

// ══════════════════════════════════════════════════════════════
// 组件
// ══════════════════════════════════════════════════════════════

/**
 * 图谱节点类型的视觉方案（颜色 / 中文 / t-tag 主题 / 图例 / 全部类型）。
 *
 * <p>抽成参数是为了让**关系图谱**（实体-关系）复用同一套 SVG 引擎，
 * 而图例文案说「文档 / 实体 / 概念 / 关系 / 属性」而不是 Wiki 的
 * 「摘要 / 实体 / 概念 / 综合 / 对比」。
 *
 * <p>⛔ 不传时用 {@link WIKI_SCHEMA}（= 原模块常量，Wiki 图谱行为零变化）。
 */
export interface GraphTypeSchema {
  colors: Record<string, string>
  labels: Record<string, string>
  themes: Record<string, string>
  /** 图例项（顺序即渲染顺序）。 */
  legend: Array<{ type: string; label: string; color: string }>
  /** 全部类型（图例过滤的初始全选集合）。 */
  all: string[]
}

export interface KbWikiGraphProps {
  baseId: string
  /** WikiPanel 已经拉过的全量页面（抽屉正文/版本从这里取，省一次请求）。
   *  关系图谱没有 Wiki 页面 ⇒ 传空数组（配合 `renderNodeBody`）。 */
  pages: WikiPage[]
  /**
   * 外部直接给图谱数据 —— 给了就**跳过**内部 `wikiApi.getGraph(baseId)`。
   *
   * <p>⭐ 关系图谱（实体-关系）走这条：数据来自 `GET /knowledge-bases/{id}/graph`，
   * 由调用方转成同一份 `GraphData` 后注入 ⇒ 两套图谱共用同一个渲染引擎
   * （SVG + 力导向 + 集群轮廓 + 标签避让 + pan/zoom + 展开子图）。
   *
   * <p>⚠️ 传 `undefined` = 走 Wiki 内部加载（默认）；传 `null` = 外部暂无数据（空态）。
   */
  dataOverride?: GraphData | null
  /** 节点类型视觉方案（不传 = Wiki 的六类）。 */
  schema?: GraphTypeSchema
  /**
   * 抽屉正文渲染器（给了就用它，不再调 `wikiApi.getPage`）。
   *
   * <p>关系图谱的节点是实体，没有 Wiki 页面正文 ⇒ 这里渲染实体的属性 / 关系 / 证据块。
   */
  renderNodeBody?: (slug: string) => ReactNode
  /** 搜索框 placeholder（默认「搜索 Wiki 页面...」）。 */
  searchPlaceholder?: string
  /** 搜索无结果文案（默认「没有找到匹配的页面」）。关系图谱要换成「实体」。 */
  searchEmptyText?: string
  /** 概览状态卡的标题（默认「全库概览」）。 */
  statusTitle?: string
  /** 外部加载失败的原因（由调用方持有；内部加载时留空）。 */
  loadError?: string | null
  /** 外部是否正在加载（由调用方持有；内部加载时留空）。 */
  loadingOverride?: boolean
}

export function KbWikiGraph({
  baseId,
  pages,
  dataOverride,
  schema: schemaProp,
  renderNodeBody,
  searchPlaceholder = '搜索 Wiki 页面...',
  searchEmptyText = '没有找到匹配的页面',
  statusTitle = '全库概览',
  loadError,
  loadingOverride,
}: KbWikiGraphProps) {
  /**
   * 节点视觉方案。
   *
   * <p>⚠️ 必须是**稳定引用**（调用方用模块级常量或 useMemo 传），否则每次渲染
   * `renderGraph` 的依赖变化 ⇒ 整张图重建、力导向从 alpha=1 重跑（画面会跳）。
   */
  const schema = schemaProp ?? WIKI_SCHEMA
  /**
   * 是否由外部供数（关系图谱）。
   *
   * <p>⚠️ 判据是 `dataOverride !== undefined`，⛔ 不能用 `!!dataOverride`：
   * 外部「加载完成但 0 个节点」传 `null` 与「不接管」传 `undefined` 必须分开。
   */
  const external = dataOverride !== undefined
  const canvasRef = useRef<HTMLDivElement | null>(null)
  const hostRef = useRef<HTMLDivElement | null>(null)
  const engRef = useRef<Engine>(emptyEngine())
  /** unmount 时要执行的清理（interval / window 监听 / rAF）。 */
  const cleanupsRef = useRef<Array<() => void>>([])
  /** 下一次 renderGraph 是否保留上一轮坐标（design-04 的 preserveLayout）。 */
  const preserveRef = useRef(false)
  /** 下一次 renderGraph 要合并进来的子图（bloom / growFrontier）。 */
  const mergeRef = useRef<GraphData | null>(null)
  /** 下一次渲染完成后要执行的动作（搜索定位 / 深链）。 */
  const pendingFocusRef = useRef<string | null>(null)

  const [raw, setRaw] = useState<GraphData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [mode, setMode] = useState<'overview' | 'ego'>('overview')
  const [centerSlug, setCenterSlug] = useState('')
  const [selectedSlug, setSelectedSlug] = useState<string | null>(null)
  const [drawerSlug, setDrawerSlug] = useState<string | null>(null)
  const [showArrows, setShowArrows] = useState(true)
  const [filterTypes, setFilterTypes] = useState<Set<string>>(() => new Set(schema.all))
  /** 每次命令式渲染完成 +1：驱动状态卡 / 抽屉提示重算。 */
  const [graphVersion, setGraphVersion] = useState(0)
  const [renderToken, setRenderToken] = useState(0)
  const [frontier, setFrontier] = useState<string[]>([])

  const [searchOpen, setSearchOpen] = useState(false)
  const [searchKeyword, setSearchKeyword] = useState('')
  const [helpOpen, setHelpOpen] = useState(false)
  /** 抽屉页面（远程兜底：pages 里没有时才请求）。 */
  const [remotePage, setRemotePage] = useState<WikiPage | null>(null)

  /**
   * 抽屉是否开着 —— 用 ref 而不是 state 参与 {@link renderGraph}：
   * 源站开抽屉**不重建画布**（只 `refitForDrawer` 重算视口）。
   * 若把 `drawerSlug` 放进 renderGraph 依赖，每开一次抽屉就会重排节点、
   * 重新从 alpha=1 起跑力导向 ⇒ 画面会跳一下，与真机不符。
   */
  const drawerSlugRef = useRef<string | null>(null)
  drawerSlugRef.current = drawerSlug
  /** 箭头开关同理：源站只切 marker，不重建画布。 */
  const showArrowsRef = useRef(showArrows)

  // ── 加载图谱数据 ──────────────────────────────────────────────
  /* ⭐ 两条数据通路，互斥：
     ① 内部（Wiki 图谱）—— `wikiApi.getGraph(baseId)` → `mapGraph`；
     ② 外部（关系图谱）—— 调用方已转成同一份 `GraphData` 从 `dataOverride` 注入。
     ⛔ 外部接管时**不能**再发 `wikiApi.getGraph`：那个端点返回的是 Wiki 页面链接图，
        与实体-关系图是完全不同的两份数据（会画出张冠李戴的图）。 */
  useEffect(() => {
    if (external) return
    let alive = true
    setLoading(true)
    setError(null)
    wikiApi
      .getGraph(baseId)
      .then((g) => {
        if (!alive) return
        setRaw(mapGraph(g))
      })
      .catch((e: unknown) => {
        if (!alive) return
        setError(e instanceof ApiError ? e.message : String(e))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [baseId, external])

  /* 外部数据同步到内部 `raw`。用 effect 而不是直接用 `dataOverride`：
     下面 `applyFilter` / `nodeBySlug` / `searchHits` 全都读 `raw`，
     直接读 `dataOverride` 要改七八处，反而容易漏。 */
  useEffect(() => {
    if (!external) return
    setRaw(dataOverride)
    setError(loadError ?? null)
    setLoading(!!loadingOverride)
  }, [external, dataOverride, loadError, loadingOverride])

  // ── 过滤后的图（源站 applyFilter）──────────────────────────────
  const applyFilter = useCallback(
    (g: GraphData): GraphData => {
      const nodes = g.nodes.filter((n) => filterTypes.has(n.type))
      const keep = new Set(nodes.map((n) => n.slug))
      const edges = g.edges.filter((e) => keep.has(e.source) && keep.has(e.target))
      return { total: g.total, nodes, edges }
    },
    [filterTypes]
  )

  const data = useMemo<GraphData | null>(() => {
    if (!raw) return null
    return applyFilter(raw)
  }, [raw, applyFilter])

  const nodeBySlug = useMemo(
    () => new Map((raw?.nodes ?? []).map((n) => [n.slug, n] as const)),
    [raw]
  )

  // ══════════════════════════════════════════════════════════════
  // 子图构造（design-04 script:748-782）
  // ══════════════════════════════════════════════════════════════

  const buildAdjacency = useCallback((nodes: GraphNodeLike[], edges: GraphEdge[]) => {
    const adj = new Map<string, Set<string>>()
    nodes.forEach((n) => adj.set(n.slug, new Set<string>()))
    edges.forEach((e) => {
      if (!adj.has(e.source) || !adj.has(e.target)) return
      adj.get(e.source)!.add(e.target)
      adj.get(e.target)!.add(e.source)
    })
    return adj
  }, [])

  /** center + 一跳邻居的诱导子图（服务端 ego 的同口径客户端版）。 */
  const inducedGraph = useCallback(
    (center: string): GraphData => {
      if (!raw) return { total: 0, nodes: [], edges: [] }
      const members = new Set<string>([center])
      raw.edges.forEach((e) => {
        if (e.source === center) members.add(e.target)
        else if (e.target === center) members.add(e.source)
      })
      return {
        total: raw.total,
        nodes: raw.nodes.filter((n) => members.has(n.slug)),
        edges: raw.edges.filter((e) => members.has(e.source) && members.has(e.target)),
      }
    },
    [raw]
  )

  // ══════════════════════════════════════════════════════════════
  // 命令式渲染（design-04 script:787 renderGraph 的结构复刻）
  // ══════════════════════════════════════════════════════════════

  const renderGraph = useCallback(() => {
    const eng = engRef.current
    const canvas = canvasRef.current
    if (!canvas) return

    // 先清掉上一轮
    if (eng.animId) {
      window.clearInterval(eng.animId)
      eng.animId = 0
    }
    if (eng.hoverTimer) {
      window.clearTimeout(eng.hoverTimer)
      eng.hoverTimer = null
    }
    eng.refreshLabels = () => {}
    cleanupsRef.current.forEach((fn) => fn())
    cleanupsRef.current = []

    if (!data) return

    const preserve = preserveRef.current
    preserveRef.current = false
    const merge = mergeRef.current
    mergeRef.current = null

    const prior = new Map<string, { x: number; y: number; pinned?: boolean }>()
    if (preserve) {
      eng.nodes.forEach((n) => prior.set(n.slug, { x: n.x, y: n.y, pinned: n.pinned }))
    }

    /* ⭐ 子图来源（design-04 script:798）：
       `mode === 'ego' && centerSlug` ⇒ 只要 center + 一跳邻居的**诱导子图**；
       否则全库。⛔ 早先这里只用 `data`（全库），双击后节点数不变、
           「扩张边缘」也永远算不出来 —— 与真机差了整整一层。 */
    const source: GraphData =
      mode === 'ego' && centerSlug
        ? applyFilter(inducedGraph(centerSlug))
        : { total: data.total, nodes: data.nodes.slice(), edges: data.edges.slice() }
    let graph: GraphData = { total: source.total, nodes: source.nodes.slice(), edges: source.edges.slice() }
    if (merge) {
      const seen = new Set(graph.nodes.map((n) => n.slug))
      merge.nodes.forEach((n) => {
        if (!seen.has(n.slug)) {
          graph.nodes.push(n)
          seen.add(n.slug)
        }
      })
      const keyset = new Set(graph.edges.map((e) => [e.source, e.target].sort().join('|')))
      merge.edges.forEach((e) => {
        const key = [e.source, e.target].sort().join('|')
        if (keyset.has(key) || !seen.has(e.source) || !seen.has(e.target)) return
        keyset.add(key)
        graph.edges.push(e)
      })
    }

    canvas.innerHTML = ''
    eng.nodes = []
    eng.nodeEls = []
    eng.edgeEls = []
    eng.clusterEls = []
    eng.contour = null
    // 源站 graphFrontierCount 遍历**有向边原始行**（双向边是两行）⇒ 展开回两行计度
    eng.rawEdges = []
    graph.edges.forEach((e) => {
      eng.rawEdges.push({ source: e.source, target: e.target })
      if (e.bidir) eng.rawEdges.push({ source: e.target, target: e.source })
    })
    eng.adjacency = buildAdjacency(graph.nodes, graph.edges)
    eng.mode = mode
    eng.centerSlug = centerSlug
    eng.showArrows = showArrowsRef.current
    // ⚠️ 只用于**首次建边**的 marker 初值；运行期的开关读 `eng.showArrows`
    //（常量快照会让「隐藏箭头」在选中/适应屏幕后自动恢复）
    const showArrows = eng.showArrows

    if (!graph.nodes.length) return

    const width = canvas.clientWidth || 800
    const height = canvas.clientHeight || 600

    const svg = document.createElementNS(SVGNS, 'svg')
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height)
    // ⚠️ 必须 slice：默认 meet 在宽高比不一致时会把节点缩成极小 bbox
    svg.setAttribute('preserveAspectRatio', 'xMidYMid slice')
    svg.style.width = '100%'
    svg.style.height = '100%'
    svg.style.cursor = 'grab'
    svg.style.userSelect = 'none'
    svg.style.display = 'block'
    canvas.appendChild(svg)

    const rootG = document.createElementNS(SVGNS, 'g')
    rootG.setAttribute('class', 'graph-root')
    svg.appendChild(rootG)

    const edgeG = document.createElementNS(SVGNS, 'g')
    edgeG.setAttribute('class', 'edge-layer')
    edgeG.setAttribute('pointer-events', 'none')
    rootG.appendChild(edgeG)

    const nodeG = document.createElementNS(SVGNS, 'g')
    nodeG.setAttribute('class', 'node-layer')
    rootG.appendChild(nodeG)

    // defs：4 个箭头 marker + 节点阴影（源站 :4119-4178）
    const defs = document.createElementNS(SVGNS, 'defs')
    const marker = (id: string, d: string, refX: number, fill: string) => {
      const m = document.createElementNS(SVGNS, 'marker')
      m.setAttribute('id', id)
      m.setAttribute('viewBox', '0 0 10 6')
      m.setAttribute('refX', String(refX))
      m.setAttribute('refY', '3')
      m.setAttribute('markerWidth', '8')
      m.setAttribute('markerHeight', '6')
      m.setAttribute('orient', 'auto')
      const p = document.createElementNS(SVGNS, 'path')
      p.setAttribute('d', d)
      p.setAttribute('fill', fill)
      m.appendChild(p)
      defs.appendChild(m)
    }
    marker('arrow-end', 'M0,0 L10,3 L0,6 L2,3 Z', 10, GRAPH_EDGE_COLOR)
    marker('arrow-start', 'M10,0 L0,3 L10,6 L8,3 Z', 0, GRAPH_EDGE_COLOR)
    marker('arrow-end-hl', 'M0,0 L10,3 L0,6 L2,3 Z', 10, '#0052d9')
    marker('arrow-start-hl', 'M10,0 L0,3 L10,6 L8,3 Z', 0, '#0052d9')
    const shadow = document.createElementNS(SVGNS, 'filter')
    shadow.setAttribute('id', 'node-shadow')
    shadow.setAttribute('x', '-20%')
    shadow.setAttribute('y', '-20%')
    shadow.setAttribute('width', '140%')
    shadow.setAttribute('height', '140%')
    shadow.innerHTML =
      '<feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#000" flood-opacity="0.15"/>'
    defs.appendChild(shadow)
    svg.appendChild(defs)

    // 初始坐标 = buildGraphLayout 的结果（⛔ 不是预烘焙值）
    const layoutResult = buildGraphLayout(graph.nodes, graph.edges, width, height, prior)
    eng.nodes = graph.nodes.map((n) => {
      const placement = layoutResult.placements.get(n.slug)
      const p = prior.get(n.slug)
      return {
        x: placement ? placement.x : p ? p.x : 0,
        y: placement ? placement.y : p ? p.y : 0,
        vx: 0,
        vy: 0,
        slug: n.slug,
        title: n.title,
        type: n.type,
        linkCount: n.linkCount || 0,
        pinned: p ? !!p.pinned : false,
        labelWidth: Math.min(n.title.length, 15) * 11,
      }
    })
    const nodeMap = new Map<string, SimNode>(eng.nodes.map((n) => [n.slug, n]))

    // 边：无向去重，双向边两端带箭头（源站 :4181-4210）
    const pairSet = new Set(graph.edges.map((e) => e.source + '→' + e.target))
    const processed = new Set<string>()
    eng.edges = []
    graph.edges.forEach((e) => {
      const key = [e.source, e.target].sort().join('↔')
      if (processed.has(key)) return
      processed.add(key)
      const bidir = pairSet.has(e.target + '→' + e.source)
      const line = document.createElementNS(SVGNS, 'line')
      line.setAttribute('stroke', GRAPH_EDGE_COLOR)
      line.setAttribute('stroke-width', GRAPH_EDGE_WIDTH)
      line.setAttribute('stroke-opacity', GRAPH_EDGE_OPACITY)
      line.setAttribute('stroke-linecap', 'round')
      if (showArrows) line.setAttribute('marker-end', 'url(#arrow-end)')
      if (bidir && showArrows) line.setAttribute('marker-start', 'url(#arrow-start)')
      line.style.transition = 'stroke 0.2s, stroke-width 0.2s, stroke-opacity 0.2s'
      edgeG.appendChild(line)
      eng.edgeEls.push({ line, source: e.source, target: e.target, bidir })
      eng.edges.push({ source: e.source, target: e.target, bidir })
    })

    // 节点：expansionRing → activeRing → circle → rect → text（源站 :4212-4302）
    const adjacency = eng.adjacency
    eng.nodes.forEach((n) => {
      const g = document.createElementNS(SVGNS, 'g')
      g.style.cursor = 'pointer'
      g.setAttribute('data-graph-node', n.slug)

      const r = graphNodeRadius(n.linkCount)
      const visibleNeighbors = (adjacency.get(n.slug) || new Set<string>()).size
      const hiddenNeighbors = Math.max(0, n.linkCount - visibleNeighbors)
      const isEgoCenter = mode === 'ego' && centerSlug === n.slug
      // 虚线扩张环只在 ego 非中心节点、且还有未加载邻居时出现（源站 :4241）
      const showExpansionRing = mode === 'ego' && hiddenNeighbors > 0 && !isEgoCenter

      const expansionRing = document.createElementNS(SVGNS, 'circle')
      expansionRing.setAttribute('r', String(r + 3))
      expansionRing.setAttribute('fill', 'none')
      expansionRing.setAttribute('stroke', schema.colors[n.type] || '#8c8c8c')
      expansionRing.setAttribute('stroke-width', '1.5')
      expansionRing.setAttribute('stroke-dasharray', '3 3')
      expansionRing.setAttribute('pointer-events', 'none')
      expansionRing.style.opacity = showExpansionRing ? '0.55' : '0'
      expansionRing.style.transition = 'opacity 0.2s'
      expansionRing.classList.add('node-expansion-ring')
      g.appendChild(expansionRing)

      /* ⚠️ 选中环没有任何动画。
         源站 @keyframes node-active-pulse 挂在 scoped `.node-active-ring[data-v-…]`，
         而这些 circle 由 createElementNS 建立、不带 data-v ⇒ 规则不命中；
         真机实测 animationName === 'none'。⛔ 不要「补回脉冲」。 */
      const activeRing = document.createElementNS(SVGNS, 'circle')
      activeRing.setAttribute('r', String(r + 5))
      activeRing.setAttribute('fill', 'none')
      activeRing.setAttribute('stroke', schema.colors[n.type] || '#8c8c8c')
      activeRing.setAttribute('stroke-width', '2')
      activeRing.style.opacity = '0'
      activeRing.style.transition = 'opacity 0.2s'
      activeRing.classList.add('node-active-ring')
      g.appendChild(activeRing)

      const circle = document.createElementNS(SVGNS, 'circle')
      circle.setAttribute('r', String(r))
      circle.setAttribute('fill', schema.colors[n.type] || '#8c8c8c')
      circle.setAttribute('stroke', '#fff')
      circle.setAttribute('stroke-width', '1.5')
      circle.classList.add('node-circle')
      circle.style.transition = 'r 0.2s, stroke-width 0.2s, opacity 0.2s'
      g.appendChild(circle)

      const textBg = document.createElementNS(SVGNS, 'rect')
      g.appendChild(textBg)

      const text = document.createElementNS(SVGNS, 'text')
      text.setAttribute('text-anchor', 'middle')
      text.setAttribute('dy', String(r + 14))
      text.setAttribute('font-size', '11')
      text.setAttribute('fill', 'var(--td-text-color-secondary)')
      text.setAttribute('pointer-events', 'none')
      text.style.textShadow =
        '0 1px 3px var(--td-bg-color-container), 0 -1px 3px var(--td-bg-color-container), 1px 0 3px var(--td-bg-color-container), -1px 0 3px var(--td-bg-color-container)'
      text.textContent = n.title.length > 14 ? n.title.substring(0, 14) + '…' : n.title
      g.appendChild(text)

      /* Hover bloom 按钮（源站 WikiBrowser.vue:4304-4360）：
         节点右上 45° 的 ⊕，默认 opacity:0，hover 才显形。 */
      if (mode === 'ego' && !isEgoCenter && hiddenNeighbors > 0) {
        const bloomBtn = document.createElementNS(SVGNS, 'g')
        bloomBtn.setAttribute('class', 'node-bloom-btn')
        bloomBtn.style.opacity = '0'
        bloomBtn.style.transition = 'opacity 0.15s'
        bloomBtn.style.pointerEvents = 'none'
        bloomBtn.style.cursor = 'pointer'

        const btnOffset = r + 6
        const btnX = Math.SQRT1_2 * btnOffset
        const btnY = -Math.SQRT1_2 * btnOffset

        const btnBg = document.createElementNS(SVGNS, 'circle')
        btnBg.setAttribute('cx', String(btnX))
        btnBg.setAttribute('cy', String(btnY))
        btnBg.setAttribute('r', '8')
        btnBg.setAttribute('fill', 'var(--td-bg-color-container)')
        btnBg.setAttribute('stroke', 'var(--td-brand-color)')
        btnBg.setAttribute('stroke-width', '1.5')
        bloomBtn.appendChild(btnBg)

        const btnCrossV = document.createElementNS(SVGNS, 'line')
        btnCrossV.setAttribute('x1', String(btnX))
        btnCrossV.setAttribute('x2', String(btnX))
        btnCrossV.setAttribute('y1', String(btnY - 4))
        btnCrossV.setAttribute('y2', String(btnY + 4))
        btnCrossV.setAttribute('stroke', 'var(--td-brand-color)')
        btnCrossV.setAttribute('stroke-width', '1.8')
        btnCrossV.setAttribute('stroke-linecap', 'round')
        bloomBtn.appendChild(btnCrossV)

        const btnCrossH = document.createElementNS(SVGNS, 'line')
        btnCrossH.setAttribute('x1', String(btnX - 4))
        btnCrossH.setAttribute('x2', String(btnX + 4))
        btnCrossH.setAttribute('y1', String(btnY))
        btnCrossH.setAttribute('y2', String(btnY))
        btnCrossH.setAttribute('stroke', 'var(--td-brand-color)')
        btnCrossH.setAttribute('stroke-width', '1.8')
        btnCrossH.setAttribute('stroke-linecap', 'round')
        bloomBtn.appendChild(btnCrossH)

        bloomBtn.addEventListener('click', (e) => {
          e.stopPropagation()
          bloomNeighborsRef.current(n.slug)
        })

        g.addEventListener('mouseenter', () => {
          bloomBtn.style.opacity = '1'
          bloomBtn.style.pointerEvents = 'auto'
        })
        g.addEventListener('mouseleave', () => {
          bloomBtn.style.opacity = '0'
          bloomBtn.style.pointerEvents = 'none'
        })

        g.appendChild(bloomBtn)
      }

      /* 单击 / 双击（源站 :4427-4470：单击延迟 220ms，双击取消单击） */
      let pendingSingleClick: number | null = null
      g.addEventListener('click', (e) => {
        e.stopPropagation()
        if (e.shiftKey) {
          if (pendingSingleClick) {
            window.clearTimeout(pendingSingleClick)
            pendingSingleClick = null
          }
          bloomNeighborsRef.current(n.slug)
          return
        }
        if (pendingSingleClick) window.clearTimeout(pendingSingleClick)
        pendingSingleClick = window.setTimeout(() => {
          pendingSingleClick = null
          const eng2 = engRef.current
          eng2.selectedSlug = n.slug
          eng2.highlightSlug = null
          applyHighlightRef.current(n.slug)
          if (eng2.panZoom) {
            const w = canvas.clientWidth
            const h = canvas.clientHeight
            eng2.panZoom.flyTo(
              w / 2 - n.x * eng2.panZoom.getScale() - 240,
              h / 2 - n.y * eng2.panZoom.getScale()
            )
          }
          setSelectedSlug(n.slug)
          setDrawerSlug(n.slug)
        }, 220)
      })
      g.addEventListener('dblclick', (e) => {
        e.stopPropagation()
        if (pendingSingleClick) {
          window.clearTimeout(pendingSingleClick)
          pendingSingleClick = null
        }
        loadEgoRef.current(n.slug)
      })
      g.addEventListener('mouseenter', () => {
        const eng2 = engRef.current
        if (eng2.hoverTimer) {
          window.clearTimeout(eng2.hoverTimer)
          eng2.hoverTimer = null
        }
        if (!eng2.selectedSlug) {
          if (eng2.highlightSlug === n.slug) return
          eng2.highlightSlug = n.slug
          applyHighlightRef.current(n.slug)
        } else if (eng2.selectedSlug !== n.slug) {
          if (eng2.highlightSlug === n.slug) return
          eng2.highlightSlug = n.slug
          applyHighlightRef.current(eng2.selectedSlug, n.slug)
        }
      })
      g.addEventListener('mouseleave', () => {
        const eng2 = engRef.current
        if (eng2.hoverTimer) window.clearTimeout(eng2.hoverTimer)
        eng2.hoverTimer = window.setTimeout(() => {
          eng2.hoverTimer = null
          if (!eng2.selectedSlug) {
            eng2.highlightSlug = null
            clearHighlightRef.current()
          } else {
            eng2.highlightSlug = null
            applyHighlightRef.current(eng2.selectedSlug)
          }
        }, 60)
      })
      setupDrag(g, n, nodeMap)

      nodeG.appendChild(g)
      eng.nodeEls.push({ g, circle, text, activeRing, expansionRing, node: n })
    })

    // 标签宽度：写完后再读一次，避免逐节点回流（源站 :4479-4483）
    eng.nodeEls.forEach((item) => {
      item.node.labelWidth = item.text.getComputedTextLength() || item.node.labelWidth
    })
    eng.nodeEls.forEach((item) => {
      item.text.style.display = 'none'
    })

    // overview 满层级：集群轮廓 + 地标标题（源站 :3989-4086）
    if (mode === 'overview') {
      const members = eng.nodes.map((n) => ({
        x: n.x,
        y: n.y,
        radius: graphNodeRadius(n.linkCount),
      }))
      const path = document.createElementNS(SVGNS, 'path')
      path.classList.add('graph-cluster-outline')
      path.setAttribute('fill', 'var(--td-bg-color-secondarycontainer)')
      path.setAttribute('data-cluster', eng.nodes[0].slug)
      path.setAttribute('stroke', 'var(--td-component-border)')
      path.setAttribute('stroke-width', '1')
      path.setAttribute('vector-effect', 'non-scaling-stroke')
      path.setAttribute('pointer-events', 'visiblePainted')
      rootG.insertBefore(path, edgeG)
      eng.contour = { path, members }

      // 地标标题的锚点由布局给出（源站是服务端 DATA.anchors，等价于
      // buildGraphLayout → selectGraphAnchors 的结果）。
      // ⚠️ 必须**拼所有 cluster** 的 anchors：只用 clusters[0] 会在多组件图里漏掉其它组的地标。
      const anchors = layoutResult.clusters.reduce<string[]>(
        (all, c) => all.concat(c.anchors),
        []
      )
      anchors.forEach((slug) => {
        const anchor = nodeMap.get(slug)
        if (!anchor) return
        const label = landmarkTitle(anchor.title)
        const group = document.createElementNS(SVGNS, 'g')
        group.classList.add('graph-cluster-caption')
        group.setAttribute('data-representative', slug)
        group.setAttribute('role', 'button')
        group.setAttribute('tabindex', '0')
        group.setAttribute('aria-label', label + ' · 聚焦这组页面')
        const captionText = document.createElementNS(SVGNS, 'text')
        captionText.textContent = label.length > 20 ? label.slice(0, 20) + '…' : label
        captionText.setAttribute('text-anchor', 'middle')
        captionText.setAttribute('font-size', '13')
        captionText.setAttribute('font-weight', '600')
        captionText.setAttribute('fill', 'var(--td-text-color-primary)')
        const pill = document.createElementNS(SVGNS, 'rect')
        pill.classList.add('graph-caption-pill')
        group.appendChild(pill)
        group.appendChild(captionText)
        rootG.appendChild(group)
        const textWidth = captionText.getComputedTextLength() || captionText.textContent.length * 13
        pill.setAttribute('x', String(-textWidth / 2 - 12))
        pill.setAttribute('y', '-17')
        pill.setAttribute('width', String(textWidth + 24))
        pill.setAttribute('height', '24')
        pill.setAttribute('rx', '12')
        eng.clusterEls.push({ group, anchorSlug: slug, labelWidth: textWidth + 24 })
        group.addEventListener('click', (e) => {
          e.stopPropagation()
          focusCluster()
        })
        group.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            e.stopPropagation()
            focusCluster()
          }
        })
      })
    }

    // 布局：初始坐标 = buildGraphLayout；仍跑源站同一套 tick（alpha*=0.985）
    const placements = new Map<string, { center: Point; degree: number; stationary?: boolean }>()
    eng.nodes.forEach((n) => {
      const known = layoutResult.placements.get(n.slug)
      placements.set(n.slug, {
        center: known ? known.center : { x: 0, y: 0 },
        degree: (known && known.degree) || (eng.adjacency.get(n.slug) || new Set<string>()).size,
        stationary: known ? !!known.stationary : false,
      })
    })

    const previousViewport =
      preserve && eng.panZoom
        ? {
            scale: eng.panZoom.getScale(),
            x: eng.panZoom.getTranslate().x,
            y: eng.panZoom.getTranslate().y,
          }
        : null
    const availableWidth = Math.max(
      160,
      width - (drawerSlugRef.current ? 480 : Math.min(240, width * 0.25))
    )
    setupPanZoom(
      svg,
      rootG,
      previousViewport ||
        initialGraphViewport(eng.nodes, availableWidth, height, nodeMap.get(centerSlug) ?? null)
    )

    let alpha = 1.0
    let frame = 0
    /* ⚠️ 源站用 requestAnimationFrame；这里用 setInterval(16)：
       后台标签页 rAF 不派发 ⇒ 动画会卡在第 0 帧。 */
    const tick = () => {
      alpha *= 0.985
      if (alpha < 0.02) {
        if (eng.refreshLabels) eng.refreshLabels()
        if (eng.animId) window.clearInterval(eng.animId)
        eng.animId = 0
        return
      }
      stepGraphLayout(eng.nodes, eng.edges, placements, alpha, new Set())
      eng.nodeEls.forEach((item) => {
        item.g.setAttribute(
          'transform',
          'translate(' + item.node.x + ',' + item.node.y + ')'
        )
      })
      eng.edgeEls.forEach((e) => {
        const s = nodeMap.get(e.source)
        const t = nodeMap.get(e.target)
        if (s && t) setEdgePositions(e.line, s, t)
      })
      if (++frame % 6 === 0 && eng.refreshLabels) eng.refreshLabels()
    }
    eng.nodeEls.forEach((item) => {
      item.g.setAttribute('transform', 'translate(' + item.node.x + ',' + item.node.y + ')')
    })
    eng.edgeEls.forEach((e) => {
      const s = nodeMap.get(e.source)
      const t = nodeMap.get(e.target)
      if (s && t) setEdgePositions(e.line, s, t)
    })
    eng.animId = window.setInterval(tick, 16)

    eng.refreshLabels = makeLabelRefresher()
    eng.refreshLabels()

    if (eng.selectedSlug && nodeMap.has(eng.selectedSlug)) applyHighlight(eng.selectedSlug)
    setFrontierRef.current(frontierSlugs())

    // 搜索定位：渲染完成后把镜头飞过去（design-04 selectBySearch 的后半段）
    const focusSlug = pendingFocusRef.current
    if (focusSlug) {
      pendingFocusRef.current = null
      const live = nodeMap.get(focusSlug)
      if (live && eng.panZoom) {
        const w = canvas.clientWidth
        const h = canvas.clientHeight
        const s = eng.panZoom.getScale()
        eng.panZoom.flyTo(w / 2 - live.x * s - 240, h / 2 - live.y * s)
      }
      eng.selectedSlug = focusSlug
      eng.highlightSlug = focusSlug
      applyHighlight(focusSlug)
    }

    setGraphVersion((v) => v + 1)

    // ── 内部函数（闭包里绑定本轮 DOM）────────────────────────────
    function setEdgePositions(line: SVGLineElement, s: SimNode, t: SimNode) {
      const dx = t.x - s.x
      const dy = t.y - s.y
      const dist = Math.sqrt(dx * dx + dy * dy) || 1
      const ux = dx / dist
      const uy = dy / dist
      const margin = showArrows ? 4 : 0
      const rs = graphNodeRadius(s.linkCount) + margin
      const rt = graphNodeRadius(t.linkCount) + margin
      line.setAttribute('x1', String(s.x + ux * rs))
      line.setAttribute('y1', String(s.y + uy * rs))
      line.setAttribute('x2', String(t.x - ux * rt))
      line.setAttribute('y2', String(t.y - uy * rt))
    }

    function setEdgeMarkers(e: RenderedEdge, suffix?: string) {
      const sfx = suffix || ''
      // ⚠️ 同上：读可变引擎状态，否则「隐藏箭头」会被高亮/适应屏幕撤销
      if (!eng.showArrows) {
        e.line.removeAttribute('marker-end')
        e.line.removeAttribute('marker-start')
        return
      }
      e.line.setAttribute('marker-end', 'url(#arrow-end' + sfx + ')')
      if (e.bidir) e.line.setAttribute('marker-start', 'url(#arrow-start' + sfx + ')')
      else e.line.removeAttribute('marker-start')
    }

    /* 标签布局 —— 源站 :4686-4758 */
    function makeLabelRefresher() {
      return () => {
        const scale = eng.panZoom ? eng.panZoom.getScale() : 1
        if (eng.contour) {
          eng.contour.path.setAttribute('d', graphClusterOutline(eng.contour.members, scale))
        }
        const positions = new Map<string, SimNode>(eng.nodes.map((n) => [n.slug, n]))
        const groupLabels: LabelBox[] = []
        eng.clusterEls.forEach((item) => {
          const anchor = positions.get(item.anchorSlug)
          const shown = !!anchor && scale < 1.2
          item.group.style.display = shown ? '' : 'none'
          if (!shown || !anchor) return
          const labelY = anchor.y + graphNodeRadius(anchor.linkCount) + 18 / scale
          item.group.setAttribute(
            'transform',
            'translate(' + anchor.x + ',' + labelY + ') scale(' + 1 / scale + ')'
          )
          const x = anchor.x * scale
          const y = labelY * scale
          groupLabels.push({
            slug: 'anchor:' + item.anchorSlug,
            priority: 100000,
            required: true,
            left: x - item.labelWidth / 2 - 8,
            right: x + item.labelWidth / 2 + 8,
            top: y - 16,
            bottom: y + 5,
          })
        })
        const captioned = new Set(
          eng.clusterEls
            .filter((c) => c.group.style.display !== 'none')
            .map((c) => c.anchorSlug)
        )

        // 真机 overview：重要节点 = 按 linkCount 取前 max(1, min(12, ceil(n/30)))
        const important = new Set(
          eng.nodes
            .slice()
            .sort((a, b) => b.linkCount - a.linkCount)
            .slice(0, Math.max(1, Math.min(12, Math.ceil(eng.nodes.length / 30))))
            .map((n) => n.slug)
        )

        const candidates: LabelBox[] = []
        eng.nodes.forEach((n) => {
          if (captioned.has(n.slug)) return
          const required = n.slug === eng.selectedSlug || n.slug === eng.highlightSlug
          const showDetail = scale >= 0.5
          const landmark = important.has(n.slug)
          if (!required && !showDetail && !landmark) return
          const halfWidth = (n.labelWidth * 12) / 11 / 2 + 5
          const baseline = (n.y + graphNodeRadius(n.linkCount)) * scale + 16
          candidates.push({
            slug: n.slug,
            priority: n.linkCount,
            required,
            left: n.x * scale - halfWidth,
            right: n.x * scale + halfWidth,
            top: baseline - 15,
            bottom: baseline + 4,
          })
        })

        const visible = visibleGraphLabels(groupLabels.concat(candidates))
        eng.clusterEls.forEach((item) => {
          const shown = visible.has('anchor:' + item.anchorSlug)
          item.group.style.display = shown ? '' : 'none'
          item.group.setAttribute('tabindex', shown ? '0' : '-1')
        })
        eng.nodeEls.forEach((item) => {
          const shown = visible.has(item.node.slug)
          item.text.style.display = shown ? '' : 'none'
          if (!shown) return
          const fontSize = String(12 / scale)
          const dy = String(graphNodeRadius(item.node.linkCount) + 16 / scale)
          if (item.text.getAttribute('font-size') !== fontSize)
            item.text.setAttribute('font-size', fontSize)
          if (item.text.getAttribute('dy') !== dy) item.text.setAttribute('dy', dy)
        })
      }
    }

    /* applyHighlight —— 源站 :4866（选中环只切 opacity，无动画） */
    function applyHighlight(slug: string, hoverSlug?: string) {
      if (eng.refreshLabels) eng.refreshLabels()
      const neighbors = eng.adjacency.get(slug) || new Set<string>()
      const hoverNeighbors = hoverSlug
        ? eng.adjacency.get(hoverSlug) || new Set<string>()
        : new Set<string>()
      eng.nodeEls.forEach((item) => {
        const r = graphNodeRadius(item.node.linkCount)
        if (item.node.slug === slug || (hoverSlug && item.node.slug === hoverSlug)) {
          item.circle.setAttribute('r', String(r + 3))
          item.circle.setAttribute('stroke-width', '2.5')
          item.g.style.opacity = '1'
        } else if (neighbors.has(item.node.slug) || (hoverSlug && hoverNeighbors.has(item.node.slug))) {
          item.circle.setAttribute('r', String(r))
          item.circle.setAttribute('stroke-width', '1.5')
          item.g.style.opacity = '1'
        } else {
          item.circle.setAttribute('r', String(r))
          item.circle.setAttribute('stroke-width', '1.5')
          item.g.style.opacity = '0.2'
        }
        item.activeRing.style.opacity = item.node.slug === eng.selectedSlug ? '1' : '0'
      })
      eng.edgeEls.forEach((e) => {
        if (
          e.source === slug ||
          e.target === slug ||
          (hoverSlug && (e.source === hoverSlug || e.target === hoverSlug))
        ) {
          e.line.setAttribute('stroke-opacity', '0.85')
          e.line.setAttribute('stroke-width', '1.5')
          const focus =
            hoverSlug && (e.source === hoverSlug || e.target === hoverSlug) ? hoverSlug : slug
          const found = eng.nodeEls.filter((n) => n.node.slug === focus)[0]
          e.line.setAttribute('stroke', (found && schema.colors[found.node.type]) || '#0052d9')
          setEdgeMarkers(e, '-hl')
        } else {
          e.line.setAttribute('stroke', GRAPH_EDGE_COLOR)
          e.line.setAttribute('stroke-opacity', '0.08')
          e.line.setAttribute('stroke-width', GRAPH_EDGE_WIDTH)
          setEdgeMarkers(e)
        }
      })
    }

    /* clearHighlight —— 源站 :4939 */
    function clearHighlight() {
      if (eng.selectedSlug) {
        applyHighlight(eng.selectedSlug)
        return
      }
      if (eng.refreshLabels) eng.refreshLabels()
      eng.nodeEls.forEach((item) => {
        item.circle.setAttribute('r', String(graphNodeRadius(item.node.linkCount)))
        item.circle.setAttribute('stroke-width', '1.5')
        item.g.style.opacity = '1'
        item.activeRing.style.opacity = '0'
      })
      eng.edgeEls.forEach((e) => {
        e.line.setAttribute('stroke', GRAPH_EDGE_COLOR)
        e.line.setAttribute('stroke-width', GRAPH_EDGE_WIDTH)
        e.line.setAttribute('stroke-opacity', GRAPH_EDGE_OPACITY)
        setEdgeMarkers(e)
      })
    }

    /* Pan / Zoom —— 源站 :4648-4862 */
    function setupPanZoom(svgEl: SVGSVGElement, root: SVGGElement, viewport: { scale: number; x: number; y: number }) {
      let scale = viewport.scale || 1
      let tx = viewport.x || 0
      let ty = viewport.y || 0
      let flyId = 0
      let panning = false
      let panStartX = 0
      let panStartY = 0
      let dragStartX = 0
      let dragStartY = 0

      const applyTransform = () => {
        root.setAttribute('transform', 'translate(' + tx + ',' + ty + ') scale(' + scale + ')')
        if (eng.refreshLabels) eng.refreshLabels()
      }
      const onWheel = (e: WheelEvent) => {
        e.preventDefault()
        cancelAnimationFrame(flyId)
        const factor = e.deltaY > 0 ? 0.92 : 1.08
        const next = Math.max(0.005, Math.min(5, scale * factor))
        const rect = svgEl.getBoundingClientRect()
        const cx = e.clientX - rect.left
        const cy = e.clientY - rect.top
        tx = cx - (cx - tx) * (next / scale)
        ty = cy - (cy - ty) * (next / scale)
        scale = next
        applyTransform()
      }
      // ⚠️ mouseup 挂在 window 上，e.target 可能是 window/document（不是 Element）
      // ⇒ 必须判 `instanceof Element` 再 `.closest`，否则普通拖拽就会抛
      //    `target.closest is not a function`（真机复现过）。
      const insideNode = (t: EventTarget | null) =>
        t instanceof Element && !!t.closest('[data-graph-node], .graph-cluster-caption')
      const onDown = (e: MouseEvent) => {
        if (e.button !== 0) return
        if (insideNode(e.target)) return
        cancelAnimationFrame(flyId)
        panning = true
        panStartX = e.clientX - tx
        panStartY = e.clientY - ty
        dragStartX = e.clientX
        dragStartY = e.clientY
        svgEl.style.cursor = 'grabbing'
      }
      const onMove = (e: MouseEvent) => {
        if (!panning) return
        tx = e.clientX - panStartX
        ty = e.clientY - panStartY
        applyTransform()
      }
      const onUp = (e: MouseEvent) => {
        if (!panning) return
        panning = false
        svgEl.style.cursor = 'grab'
        const dx = e.clientX - dragStartX
        const dy = e.clientY - dragStartY
        if (Math.abs(dx) < 5 && Math.abs(dy) < 5 && !insideNode(e.target)) {
          /* 源站 :4835-4843：点空白先清选中 + 关抽屉 */
          if (eng.selectedSlug || drawerSlugRef.current) {
            eng.selectedSlug = null
            eng.highlightSlug = null
            setSelectedSlug(null)
            setDrawerSlug(null)
            clearHighlight()
          }
        }
      }
      svgEl.addEventListener('wheel', onWheel, { passive: false })
      svgEl.addEventListener('mousedown', onDown)
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)

      eng.panZoom = {
        getScale: () => scale,
        getTranslate: () => ({ x: tx, y: ty }),
        flyTo: (targetX: number, targetY: number, targetScale?: number, duration?: number) => {
          const dur = duration || 400
          cancelAnimationFrame(flyId)
          const sx = tx
          const sy = ty
          const ss = scale
          const ts = targetScale || scale
          const t0 = performance.now()
          const step = (now: number) => {
            let t = (now - t0) / dur
            if (t > 1) t = 1
            const ease = 1 - Math.pow(1 - t, 3)
            tx = sx + (targetX - sx) * ease
            ty = sy + (targetY - sy) * ease
            scale = ss + (ts - ss) * ease
            applyTransform()
            if (t < 1) flyId = requestAnimationFrame(step)
          }
          flyId = requestAnimationFrame(step)
        },
      }
      applyTransform()

      cleanupsRef.current.push(() => {
        cancelAnimationFrame(flyId)
        svgEl.removeEventListener('wheel', onWheel)
        svgEl.removeEventListener('mousedown', onDown)
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
      })
    }

    /* 节点拖拽 —— 源站 :4573 */
    function setupDrag(g: SVGGElement, node: SimNode, nm: Map<string, SimNode>) {
      let dragging = false
      let startX = 0
      let startY = 0
      let px = 0
      let py = 0
      let moved = false
      const point = (e: MouseEvent) => {
        const owner = g.ownerSVGElement
        if (!owner) return { x: 0, y: 0 }
        const rootEl = owner.querySelector('.graph-root') as SVGGElement | null
        if (!rootEl) return { x: 0, y: 0 }
        const pt = owner.createSVGPoint()
        pt.x = e.clientX
        pt.y = e.clientY
        const ctm = rootEl.getCTM() && rootEl.getCTM()!.inverse()
        const p = ctm ? pt.matrixTransform(ctm) : pt
        return { x: p.x, y: p.y }
      }
      const onStart = (e: MouseEvent) => {
        if (e.button !== 0) return
        e.stopPropagation()
        dragging = true
        moved = false
        px = e.clientX
        py = e.clientY
        node.pinned = true
        const p = point(e)
        startX = p.x - node.x
        startY = p.y - node.y
        const circle = g.querySelector('.node-circle')
        if (circle) {
          circle.setAttribute('stroke', schema.colors[node.type] || '#8c8c8c')
          circle.setAttribute('stroke-width', '2.5')
        }
        window.addEventListener('mousemove', onMove)
        window.addEventListener('mouseup', onEnd)
      }
      const onMove = (e: MouseEvent) => {
        if (!dragging) return
        if (Math.hypot(e.clientX - px, e.clientY - py) > 4) moved = true
        const p = point(e)
        node.x = p.x - startX
        node.y = p.y - startY
        node.vx = 0
        node.vy = 0
        g.setAttribute('transform', 'translate(' + node.x + ',' + node.y + ')')
        if (eng.refreshLabels) eng.refreshLabels()
        eng.edgeEls.forEach((edge) => {
          if (edge.source === node.slug || edge.target === node.slug) {
            const s = nm.get(edge.source)
            const t = nm.get(edge.target)
            if (s && t) setEdgePositions(edge.line, s, t)
          }
        })
      }
      const onEnd = () => {
        dragging = false
        const circle = g.querySelector('.node-circle')
        if (circle) {
          circle.setAttribute('stroke', '#fff')
          circle.setAttribute('stroke-width', '1.5')
        }
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onEnd)
      }
      g.addEventListener('mousedown', onStart)
      g.addEventListener(
        'click',
        (e) => {
          if (moved) {
            e.preventDefault()
            e.stopImmediatePropagation()
            moved = false
          }
        },
        true
      )
      cleanupsRef.current.push(() => {
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onEnd)
      })
    }

    /* 地标标题聚焦（源站 :4023-4047 的 focus()） */
    function focusCluster() {
      if (!eng.nodes.length || !eng.panZoom) return
      const minX = Math.min(...eng.nodes.map((n) => n.x))
      const maxX = Math.max(...eng.nodes.map((n) => n.x))
      const minY = Math.min(...eng.nodes.map((n) => n.y))
      const maxY = Math.max(...eng.nodes.map((n) => n.y))
      const w = canvas!.clientWidth
      const h = canvas!.clientHeight
      const available = Math.max(160, w - Math.min(240, w * 0.25))
      const scale = Math.min(
        1.75,
        Math.max(
          0.4,
          Math.min(
            (available - 160) / Math.max(100, maxX - minX),
            (h - 180) / Math.max(100, maxY - minY)
          )
        )
      )
      eng.panZoom.flyTo(
        available / 2 - ((minX + maxX) / 2) * scale,
        h / 2 - ((minY + maxY) / 2) * scale,
        scale
      )
    }

    /* 扩张边缘计数 —— 源站 graphFrontierCount（有向口径） */
    function directedDegree(slug: string) {
      let degree = 0
      eng.rawEdges.forEach((e) => {
        if (e.source === slug) degree += 1
        if (e.target === slug) degree += 1
      })
      return degree
    }
    function frontierSlugs() {
      if (mode !== 'ego') return []
      const result: string[] = []
      eng.nodes.forEach((n) => {
        if (n.slug === centerSlug) return
        if (GRAPH_SYSTEM_PAGE_TYPES[n.type]) return
        if ((n.linkCount || 0) > directedDegree(n.slug)) result.push(n.slug)
      })
      return result
    }

    // 把内部函数暴露给组件级回调（React 侧的事件处理）
    apiRef.current = {
      applyHighlight,
      clearHighlight,
      fitGraphToView: () => {
        if (!eng.panZoom || !eng.nodes.length) return
        clearHighlight()
        const minX = Math.min(...eng.nodes.map((n) => n.x))
        const maxX = Math.max(...eng.nodes.map((n) => n.x))
        const minY = Math.min(...eng.nodes.map((n) => n.y))
        const maxY = Math.max(...eng.nodes.map((n) => n.y))
        const w = canvas!.clientWidth
        const h = canvas!.clientHeight
        const padding = 100
        const available = Math.max(
          160,
          w - (drawerSlugRef.current ? 480 : Math.min(240, w * 0.25))
        )
        const scale = Math.max(
          0.005,
          Math.min(
            2,
            Math.min(
              Math.max(100, available - padding * 2) / Math.max(maxX - minX, 100),
              Math.max(100, h - padding * 2) / Math.max(maxY - minY, 100)
            )
          )
        )
        eng.panZoom.flyTo(
          available / 2 - ((minX + maxX) / 2) * scale,
          h / 2 - ((minY + maxY) / 2) * scale,
          scale,
          600
        )
      },
      refitForDrawer: () => {
        if (!eng.panZoom || !eng.nodes.length) return
        const w = canvas!.clientWidth
        const h = canvas!.clientHeight
        const available = Math.max(
          160,
          w - (drawerSlugRef.current ? 480 : Math.min(240, w * 0.25))
        )
        let minX = Infinity
        let maxX = -Infinity
        let minY = Infinity
        let maxY = -Infinity
        eng.nodes.forEach((n) => {
          if (n.x < minX) minX = n.x
          if (n.x > maxX) maxX = n.x
          if (n.y < minY) minY = n.y
          if (n.y > maxY) maxY = n.y
        })
        const fit = Math.min(
          Math.max(100, available - 120) / Math.max(100, maxX - minX),
          Math.max(100, h - 120) / Math.max(100, maxY - minY)
        )
        // ⚠️ 上界 0.85（源站 initialGraphViewport 同口径；曾误写成 2）
        const scale = Math.max(0.4, Math.min(0.85, fit))
        let cx = (minX + maxX) / 2
        let cy = (minY + maxY) / 2
        if (fit < 0.4 && mode === 'ego' && centerSlug) {
          const f = eng.nodes.find((n) => n.slug === centerSlug)
          if (f) {
            cx = f.x
            cy = f.y
          }
        }
        eng.panZoom.flyTo(available / 2 - cx * scale, h / 2 - cy * scale, scale, 260)
      },
      frontier: () => frontierSlugs(),
      nodeCount: () => eng.nodes.length,
    }
    // 声明在下方的事件桥（避免 useCallback 循环依赖）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, mode, centerSlug, renderToken, buildAdjacency, applyFilter, schema])

  // ── 箭头开关：只切 marker，不重建画布（源站 toggleArrows）────────
  const applyArrowToggle = useCallback(() => {
    const eng = engRef.current
    eng.showArrows = showArrows
    showArrowsRef.current = showArrows
    eng.edgeEls.forEach((e) => {
      if (!showArrows) {
        e.line.removeAttribute('marker-end')
        e.line.removeAttribute('marker-start')
        return
      }
      e.line.setAttribute('marker-end', 'url(#arrow-end)')
      if (e.bidir) e.line.setAttribute('marker-start', 'url(#arrow-start)')
      else e.line.removeAttribute('marker-start')
    })
    // 两端内缩随之变化（源站 setEdgePositions 的 margin）
    const nodeMap = new Map(eng.nodes.map((n) => [n.slug, n] as const))
    eng.edgeEls.forEach((e) => {
      const s = nodeMap.get(e.source)
      const t = nodeMap.get(e.target)
      if (!s || !t) return
      const dx = t.x - s.x
      const dy = t.y - s.y
      const dist = Math.sqrt(dx * dx + dy * dy) || 1
      const ux = dx / dist
      const uy = dy / dist
      const margin = showArrows ? 4 : 0
      const rs = graphNodeRadius(s.linkCount) + margin
      const rt = graphNodeRadius(t.linkCount) + margin
      e.line.setAttribute('x1', String(s.x + ux * rs))
      e.line.setAttribute('y1', String(s.y + uy * rs))
      e.line.setAttribute('x2', String(t.x - ux * rt))
      e.line.setAttribute('y2', String(t.y - uy * rt))
    })
  }, [showArrows])

  useEffect(() => {
    applyArrowToggle()
  }, [applyArrowToggle])

  // ── 命令式 API 引用（由 renderGraph 每轮重建）──────────────────
  const apiRef = useRef<{
    applyHighlight: (slug: string, hoverSlug?: string) => void
    clearHighlight: () => void
    fitGraphToView: () => void
    refitForDrawer: () => void
    frontier: () => string[]
    nodeCount: () => number
  } | null>(null)
  const setFrontierRef = useRef<(list: string[]) => void>(() => {})
  setFrontierRef.current = setFrontier

  // 事件桥：命令式 DOM 里的节点点击要能调到组件级动作，
  // 但那些动作会改 React state ⇒ 用 ref 转发，避免 renderGraph 依赖它们而反复重建。
  const applyHighlightRef = useRef<(slug: string, hoverSlug?: string) => void>(() => {})
  applyHighlightRef.current = (slug, hoverSlug) =>
    apiRef.current?.applyHighlight(slug, hoverSlug)
  const clearHighlightRef = useRef<() => void>(() => {})
  clearHighlightRef.current = () => apiRef.current?.clearHighlight()
  const bloomNeighborsRef = useRef<(slug: string) => void>(() => {})
  bloomNeighborsRef.current = (slug) => bloomNeighbors(slug)
  const loadEgoRef = useRef<(slug: string) => void>(() => {})
  loadEgoRef.current = (slug) => loadEgoGraph(slug)

  // ── 主渲染 effect ─────────────────────────────────────────────
  useEffect(() => {
    renderGraph()
    return () => {
      const eng = engRef.current
      if (eng.animId) {
        window.clearInterval(eng.animId)
        eng.animId = 0
      }
      cleanupsRef.current.forEach((fn) => fn())
      cleanupsRef.current = []
    }
  }, [renderGraph])

  // ══════════════════════════════════════════════════════════════
  // 组件级动作（design-04 script:1673-1771 / 1886）
  // ══════════════════════════════════════════════════════════════

  /** 返回概览（图例「返回概览」）。 */
  const loadGraph = useCallback(() => {
    preserveRef.current = false
    mergeRef.current = null
    engRef.current.selectedSlug = null
    engRef.current.highlightSlug = null
    setMode('overview')
    setCenterSlug('')
    setSelectedSlug(null)
    setDrawerSlug(null)
    setRenderToken((t) => t + 1)
  }, [])

  /** 以某节点为中心聚焦（双击 / 「展开邻居」）。 */
  const loadEgoGraph = useCallback((slug: string) => {
    if (!slug) return
    preserveRef.current = true
    mergeRef.current = null
    engRef.current.selectedSlug = slug
    engRef.current.highlightSlug = null
    setMode('ego')
    setCenterSlug(slug)
    setSelectedSlug(slug)
    setRenderToken((t) => t + 1)
  }, [])

  /** 把某节点的邻居叠加到画布（Shift+单击 / ⊕ / 「叠加邻居」）。 */
  const bloomNeighbors = useCallback(
    (slug: string) => {
      if (!slug) return
      if (mode !== 'ego') {
        loadEgoGraph(slug)
        return
      }
      preserveRef.current = true
      mergeRef.current = inducedGraph(slug)
      setRenderToken((t) => t + 1)
    },
    [mode, inducedGraph, loadEgoGraph]
  )

  /** 一键展开全部带虚线环的节点（图例「扩张边缘」）。 */
  const growFrontier = useCallback(() => {
    const list = apiRef.current?.frontier() ?? []
    if (!list.length) return
    const merged: GraphData = { total: raw?.total ?? 0, nodes: [], edges: [] }
    list.forEach((slug) => {
      const g = inducedGraph(slug)
      merged.nodes = merged.nodes.concat(g.nodes)
      merged.edges = merged.edges.concat(g.edges)
    })
    preserveRef.current = true
    mergeRef.current = merged
    setRenderToken((t) => t + 1)
  }, [inducedGraph, raw])

  /** 搜索选中（design-04 script:1886 selectBySearch）。 */
  const selectBySearch = useCallback(
    (slug: string) => {
      if (!nodeBySlug.has(slug)) return
      setSearchOpen(false)
      setSearchKeyword('')
      const eng = engRef.current
      const inGraph = eng.nodes.some((n) => n.slug === slug)
      eng.selectedSlug = slug
      eng.highlightSlug = slug
      setSelectedSlug(slug)
      setDrawerSlug(slug)
      if (!inGraph) {
        // 不在当前子图 ⇒ 先切 ego，渲染完成后由 pendingFocus 把镜头飞过去
        preserveRef.current = true
        pendingFocusRef.current = slug
        setMode('ego')
        setCenterSlug(slug)
        setRenderToken((t) => t + 1)
        return
      }
      apiRef.current?.applyHighlight(slug)
      const live = eng.nodes.find((n) => n.slug === slug)
      const canvas = canvasRef.current
      if (live && eng.panZoom && canvas) {
        const w = canvas.clientWidth
        const h = canvas.clientHeight
        const s = eng.panZoom.getScale()
        eng.panZoom.flyTo(w / 2 - live.x * s - 240, h / 2 - live.y * s)
      }
    },
    [nodeBySlug]
  )

  // ── 抽屉开合后的视口重算（源站 openDrawer / closeDrawer 都调）────
  // ⚠️ 只在**抽屉开合真的变了**时重算：无条件跟 graphVersion 跑会盖掉
  // 搜索定位的 flyTo（用户要求「点搜索结果镜头飞过去」）。
  const prevDrawerRef = useRef(false)
  useEffect(() => {
    const open = drawerSlug != null
    if (open === prevDrawerRef.current) return
    prevDrawerRef.current = open
    apiRef.current?.refitForDrawer()
  }, [drawerSlug])

  // ── 抽屉页面兜底：pages 里没有就按 slug 拉一次 ─────────────────
  /* ⚠️ 关系图谱（`renderNodeBody`）的节点是实体，**没有** Wiki 页面 ⇒
     既不该查 `pages`，也不该发 `wikiApi.getPage`（会 404，且抽屉会一直转圈）。 */
  useEffect(() => {
    setRemotePage(null)
    if (!drawerSlug) return
    if (renderNodeBody) return
    if (pages.some((p) => p.slug === drawerSlug)) return
    let alive = true
    wikiApi
      .getPage(baseId, drawerSlug)
      .then((p) => {
        if (alive) setRemotePage(p)
      })
      .catch(() => {
        /* 节点在图上但页面取不到：抽屉只显示标题 + 提示，不报错 */
      })
    return () => {
      alive = false
    }
  }, [drawerSlug, pages, baseId, renderNodeBody])

  // ── Esc：先关抽屉，再关帮助/搜索（⛔ 一次 Esc 不连场景一起关）────
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (helpOpen) {
        setHelpOpen(false)
        return
      }
      if (searchOpen) {
        setSearchOpen(false)
        return
      }
      if (drawerSlug) {
        setDrawerSlug(null)
        setSelectedSlug(null)
        engRef.current.selectedSlug = null
        engRef.current.highlightSlug = null
        apiRef.current?.clearHighlight()
        e.stopPropagation()
      }
    }
    // 捕获阶段：比外层弹窗的 Esc 更早，逐层退
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [helpOpen, searchOpen, drawerSlug])

  // ── 图例过滤 ──────────────────────────────────────────────────
  const toggleType = useCallback(
    (type: string) => {
      const next = new Set(filterTypes)
      if (next.has(type)) next.delete(type)
      else next.add(type)
      setFilterTypes(next)
      if (selectedSlug) {
        const node = nodeBySlug.get(selectedSlug)
        if (node && !next.has(node.type)) {
          engRef.current.selectedSlug = null
          engRef.current.highlightSlug = null
          setSelectedSlug(null)
          setDrawerSlug(null)
        }
      }
      preserveRef.current = true
    },
    [filterTypes, selectedSlug, nodeBySlug]
  )

  // ══════════════════════════════════════════════════════════════
  // 派生展示数据
  // ══════════════════════════════════════════════════════════════

  const drawerNode = drawerSlug ? nodeBySlug.get(drawerSlug) ?? null : null
  const drawerPage = drawerSlug
    ? (pages.find((p) => p.slug === drawerSlug) ?? remotePage)
    : null

  const neighborStatus = useMemo(() => {
    if (!drawerSlug) return null
    const node = nodeBySlug.get(drawerSlug)
    if (!node) return null
    const visible = (engRef.current.adjacency.get(drawerSlug) || new Set<string>()).size
    const total = node.linkCount || 0
    const hidden = Math.max(0, total - visible)
    const isCenter = mode === 'ego' && centerSlug === drawerSlug
    return {
      visible,
      total,
      hidden,
      isEgoCenter: isCenter,
      isOverview: mode === 'overview',
      fullyExplored: total === 0 || visible >= total || isCenter,
    }
    // graphVersion 参与依赖：每次命令式渲染后邻居口径会变
  }, [drawerSlug, nodeBySlug, mode, centerSlug, graphVersion])

  /** 邻居提示文案逐字取自 zh-CN.ts:5870-5884（design-04 script:1524）。 */
  const neighborHint = useMemo(() => {
    const st = neighborStatus
    if (!st) return ''
    if (st.total === 0) return '该节点没有链接邻居'
    if (st.visible >= st.total) {
      return '全部 ' + st.total + ' 个邻居已在图中（可点"展开邻居"聚焦到该节点子图）'
    }
    if (st.isEgoCenter) {
      return (
        '已显示邻居 ' +
        st.visible +
        '/' +
        st.total +
        '（还有 ' +
        st.hidden +
        ' 个无法连通：可能是死链、被过滤或已删除）'
      )
    }
    if (st.isOverview) {
      return (
        '已显示邻居 ' +
        st.visible +
        '/' +
        st.total +
        '（' +
        st.hidden +
        ' 个不在概览范围，点"展开邻居"以此为中心查看）'
      )
    }
    return '已显示邻居 ' + st.visible + '/' + st.total + '（还有 ' + st.hidden + ' 个未加载）'
  }, [neighborStatus])

  const centerNode = centerSlug ? nodeBySlug.get(centerSlug) ?? null : null

  const searchHits = useMemo(() => {
    const k = searchKeyword.trim().toLowerCase()
    return (raw?.nodes ?? [])
      .filter((n) => !k || n.title.toLowerCase().indexOf(k) >= 0)
      .slice(0, 50)
  }, [raw, searchKeyword])

  const drawerBodyHtml = useMemo(
    () => (drawerPage ? renderWikiMarkdown(drawerPage.content ?? '') : ''),
    [drawerPage]
  )

  // ══════════════════════════════════════════════════════════════
  // 渲染
  // ══════════════════════════════════════════════════════════════

  return (
    <div
      className="wiki-graph"
      ref={hostRef}
      style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, position: 'relative' }}
    >
      <GraphIconSprite />

      {/* 左下角提示（design-04 markup:208） */}
      <div className="graph-navigation-hint">拖拽空白处移动 · 滚轮缩放</div>

      {/* 搜索 + 操作帮助（design-04 markup:210-234） */}
      <div className="wiki-graph-search-container">
        <div className="wiki-graph-search-row">
          <div className="wiki-graph-search">
            <div className="t-select__wrap graph-search-select">
              <div className="t-select-input t-select">
                <div className="t-input__wrap">
                  <div className="t-input t-input--prefix t-input--suffix">
                    <span className="t-input__prefix t-input__prefix-icon">
                      <svg className="t-icon t-icon-search" aria-hidden="true">
                        <use href="#kb-g-icon-search" />
                      </svg>
                    </span>
                    <input
                      className="t-input__inner"
                      id="graphSearchInput"
                      placeholder={searchPlaceholder}
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      aria-label={searchPlaceholder.replace(/[.。…]+$/, '')}
                      value={searchKeyword}
                      onFocus={() => setSearchOpen(true)}
                      onChange={(e) => {
                        setSearchKeyword(e.target.value)
                        setSearchOpen(true)
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Escape') {
                          setSearchOpen(false)
                          ;(e.target as HTMLInputElement).blur()
                          return
                        }
                        if (e.key !== 'Enter') return
                        const first = searchHits[0]
                        if (!first) return
                        e.preventDefault()
                        selectBySearch(first.slug)
                      }}
                    />
                    <span className="t-input__suffix t-input__suffix-icon" aria-hidden="true">
                      <svg
                        className="t-fake-arrow t-select__right-icon"
                        width="16"
                        height="16"
                        viewBox="0 0 16 16"
                        fill="none"
                      >
                        <path
                          d="M3.75 5.7998L7.99274 10.0425L12.2361 5.79921"
                          stroke="black"
                          strokeOpacity="0.9"
                          strokeWidth="1.3"
                        />
                      </svg>
                    </span>
                  </div>
                </div>
              </div>
            </div>
            <div className="t-popup t-select__dropdown" hidden={!searchOpen}>
              <div className="t-popup__content">
                <ul className="t-select__list">
                  {searchHits.length === 0 ? (
                    <li className="t-select-option t-is-disabled">{searchEmptyText}</li>
                  ) : (
                    searchHits.map((n) => (
                      <li
                        key={n.slug}
                        className="t-select-option"
                        role="option"
                        aria-selected={false}
                        onMouseDown={(e) => {
                          e.preventDefault()
                          selectBySearch(n.slug)
                        }}
                      >
                        <span>{n.title}</span>
                      </li>
                    ))
                  )}
                </ul>
              </div>
            </div>
          </div>
          <div
            className="wiki-graph-help-trigger"
            role="button"
            tabIndex={0}
            title="操作帮助"
            onClick={(e) => {
              e.stopPropagation()
              setHelpOpen((v) => !v)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                setHelpOpen(true)
              }
            }}
          >
            <svg className="t-icon t-icon-help-circle" aria-hidden="true">
              <use href="#kb-g-icon-help-circle" />
            </svg>
          </div>
        </div>
      </div>

      {/* 操作帮助浮层（design-04 markup:236-251） */}
      <div className="t-popup graph-help-popup" hidden={!helpOpen} style={{ left: 16, top: 60 }}>
        <div className="t-popup__content">
          <div className="wiki-graph-help">
            <div className="help-section-title">画布操作</div>
            <div className="help-rows">
              <div className="help-row">
                <span className="help-key">单击</span>
                <span className="help-desc">打开节点详情</span>
              </div>
              <div className="help-row">
                <span className="help-key">双击</span>
                <span className="help-desc">以该节点为中心聚焦</span>
              </div>
              <div className="help-row">
                <span className="help-key">Shift + 单击</span>
                <span className="help-desc">叠加该节点邻居到画布</span>
              </div>
              <div className="help-row">
                <span className="help-key">悬浮 → ⊕</span>
                <span className="help-desc">同 Shift + 单击</span>
              </div>
              <div className="help-row">
                <span className="help-key">拖拽节点</span>
                <span className="help-desc">手动调整节点位置</span>
              </div>
              <div className="help-row">
                <span className="help-key">拖拽空白</span>
                <span className="help-desc">平移画布</span>
              </div>
              <div className="help-row">
                <span className="help-key">滚轮</span>
                <span className="help-desc">缩放画布</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* 图例 + 状态卡（design-04 markup:253-273） */}
      <div className={`wiki-graph-legend${drawerSlug ? ' legend-shifted' : ''}`}>
        <div className="legend-items">
          {schema.legend.map((t) => (
            <div
              key={t.type}
              className={`legend-item clickable${filterTypes.has(t.type) ? '' : ' disabled'}`}
              data-type={t.type}
              role="button"
              tabIndex={0}
              onClick={() => toggleType(t.type)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  toggleType(t.type)
                }
              }}
            >
              <span className="legend-dot" style={{ background: t.color }} />
              {t.label}
            </div>
          ))}
        </div>
        <div className="legend-divider" />
        <div className="legend-actions">
          <div
            className="legend-action"
            data-action="fit"
            title="Fit to View"
            role="button"
            tabIndex={0}
            onClick={() => apiRef.current?.fitGraphToView()}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                apiRef.current?.fitGraphToView()
              }
            }}
          >
            <span className="legend-action-icon">
              <svg className="t-icon t-icon-focus" aria-hidden="true">
                <use href="#kb-g-icon-focus" />
              </svg>
            </span>
            <span>适应屏幕</span>
          </div>
          <div
            className="legend-action"
            data-action="arrows"
            role="button"
            tabIndex={0}
            onClick={() => setShowArrows((v) => !v)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                setShowArrows((v) => !v)
              }
            }}
          >
            <span className="legend-action-icon">
              <svg className="t-icon" aria-hidden="true">
                <use href={showArrows ? '#kb-g-icon-browse-off' : '#kb-g-icon-browse'} />
              </svg>
            </span>
            <span>{showArrows ? '隐藏箭头' : '显示箭头'}</span>
          </div>
          {frontier.length > 0 && (
            <div
              className="legend-action"
              data-dynamic="grow"
              title={'一键展开当前所有 ' + frontier.length + ' 个带虚线环的节点'}
              role="button"
              tabIndex={0}
              onClick={growFrontier}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  growFrontier()
                }
              }}
            >
              <span className="legend-action-icon">
                <svg className="t-icon" aria-hidden="true">
                  <use href="#kb-g-icon-chart-bubble" />
                </svg>
              </span>
              <span>扩张边缘 ({frontier.length})</span>
            </div>
          )}
          {mode === 'ego' && (
            <div
              className="legend-action"
              data-dynamic="back"
              role="button"
              tabIndex={0}
              onClick={loadGraph}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  loadGraph()
                }
              }}
            >
              <span className="legend-action-icon">
                <svg className="t-icon" aria-hidden="true">
                  <use href="#kb-g-icon-rollback" />
                </svg>
              </span>
              <span>返回概览</span>
            </div>
          )}
        </div>
        <div className="wiki-graph-status-card">
          {mode === 'ego' && centerSlug ? (
            <>
              <div className="status-card-header">
                <svg className="t-icon" aria-hidden="true">
                  <use href="#kb-g-icon-focus" />
                </svg>
                <span className="status-card-title">当前焦点</span>
              </div>
              <div className="status-card-primary" title={centerNode?.title ?? centerSlug}>
                {centerNode?.title ?? centerSlug}
              </div>
              <div className="status-card-secondary">
                {(centerNode && schema.labels[centerNode.type]) || ''} ·{' '}
                {Math.max(0, engRef.current.nodes.length - 1)} 个相关节点
              </div>
            </>
          ) : (
            <>
              <div className="status-card-header">
                <svg className="t-icon" aria-hidden="true">
                  <use href="#kb-g-icon-chart-bubble" />
                </svg>
                <span className="status-card-title">{statusTitle}</span>
              </div>
              <div
                className="status-card-primary"
                title={engRef.current.nodes.length + ' / ' + (raw?.total ?? 0) + ' 个节点'}
              >
                {engRef.current.nodes.length} / {raw?.total ?? 0} 个节点
              </div>
              <div className="status-card-secondary">
                {loading
                  ? '正在加载图谱…'
                  : engRef.current.nodes.length >= (raw?.total ?? 0)
                    ? '已展示知识库全部节点'
                    : '双击任意节点聚焦到该节点的子图'}
              </div>
            </>
          )}
        </div>
      </div>

      {/* 画布：SVG 由 renderGraph 用 createElementNS 塞进来（design-04 markup:275） */}
      <div
        className="wiki-graph-canvas"
        id="graphRef"
        ref={canvasRef}
        style={{ position: 'absolute', inset: 0 }}
      />

      {/* 空状态（design-04 markup:276） */}
      <div className={`wiki-graph-empty${!loading && (error || (raw?.nodes.length ?? 0) === 0) ? ' is-open' : ''}`}>
        <p className="wiki-empty-desc">
          {error ? `图谱加载失败：${error}` : loading ? '正在加载图谱…' : '暂无图谱数据，请先上传文档'}
        </p>
      </div>

      {/* 480px 节点抽屉（design-04 markup:284-298，无遮罩） */}
      <div
        className={`t-drawer t-drawer--right t-drawer--open t-drawer--without-mask wiki-graph-drawer${
          drawerSlug ? '' : ' is-closed'
        }`}
        id="nodeDrawer"
        style={{
          position: 'absolute',
          inset: 0,
          zIndex: 1500,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
          overflow: 'hidden',
          display: drawerSlug ? 'block' : 'none',
        }}
      >
        <div
          className="t-drawer__content-wrapper t-drawer__content-wrapper--right"
          style={{ width: 480, transform: 'translateX(0)' }}
        >
          <div className="t-drawer__header" id="ndHeader">
            {drawerNode?.title ?? drawerPage?.title ?? drawerSlug ?? ''}
          </div>
          <div
            className="t-drawer__close-btn"
            id="ndClose"
            role="button"
            tabIndex={0}
            aria-label="关闭"
            onClick={(e) => {
              e.stopPropagation()
              /* 源站关抽屉只隐藏面板：选中环与邻居高亮都保留 */
              setDrawerSlug(null)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                setDrawerSlug(null)
              }
            }}
          >
            <svg
              fill="none"
              viewBox="0 0 24 24"
              width="1em"
              height="1em"
              className="t-icon t-icon-close"
              style={{ fill: 'none' }}
              aria-hidden="true"
            >
              <path
                stroke="currentColor"
                d="M16.9503 7.05029L12.0005 12M12.0005 12L7.05078 16.9498M12.0005 12L16.9503 16.9498M12.0005 12L7.05078 7.05029"
                strokeLinecap="square"
                strokeWidth="2"
              />
            </svg>
          </div>
          <div className="t-drawer__body narrow-scrollbar">
            <div className="wiki-reader-meta" id="ndMeta" style={{ marginBottom: 8 }}>
              <div
                className={`t-tag t-tag--${schema.themes[drawerNode?.type ?? ''] ?? 'default'} t-tag--light-outline t-size-s`}
              >
                <span>{schema.labels[drawerNode?.type ?? ''] ?? drawerNode?.type ?? ''}</span>
              </div>
              <span className="wiki-reader-meta-text">
                {drawerPage ? `v${drawerPage.version}` : ''}
              </span>
              {/* 源站按钮矩阵（:178-188）：
                  ego 非中心 → [叠加邻居(default)] [展开邻居(primary)]
                  其余        → [展开邻居(primary, margin-left:auto)] */}
              {mode === 'ego' && drawerSlug !== centerSlug && (
                <button
                  type="button"
                  className="t-button t-button--variant-outline t-button--theme-default t-button--shape-rectangle t-size-s"
                  style={{ marginLeft: 'auto' }}
                  disabled={!((neighborStatus?.hidden ?? 0) > 0)}
                  onClick={() => drawerSlug && bloomNeighbors(drawerSlug)}
                >
                  <span className="t-button__text">叠加邻居</span>
                </button>
              )}
              {!(mode === 'ego' && drawerSlug === centerSlug) && (
                <button
                  type="button"
                  className="t-button t-button--variant-outline t-button--theme-primary t-button--shape-rectangle t-size-s"
                  style={{ marginLeft: mode === 'ego' && drawerSlug !== centerSlug ? undefined : 'auto' }}
                  onClick={() => {
                    if (!drawerSlug) return
                    loadEgoGraph(drawerSlug)
                  }}
                >
                  <span className="t-button__text">展开邻居</span>
                </button>
              )}
            </div>
            <div className="wiki-drawer-neighbor-hint" id="ndHint" style={{ marginBottom: 16 }}>
              {neighborHint}
            </div>
            {renderNodeBody ? (
              /* 关系图谱：实体没有 Wiki 正文，调用方渲染属性 / 关系 / 证据块 */
              <div className="wiki-reader-body" id="ndBody">
                {renderNodeBody(drawerSlug ?? '')}
              </div>
            ) : drawerBodyHtml ? (
              <div
                className="wiki-reader-body"
                id="ndBody"
                dangerouslySetInnerHTML={{ __html: drawerBodyHtml }}
                onClick={(e) => {
                  /* 抽屉里的 wiki 链接：真机 handleGraphDrawerClick → handleGraphSearchSelect */
                  const a = (e.target as HTMLElement).closest(`[${WIKI_LINK_ATTR}]`)
                  if (!a) return
                  e.preventDefault()
                  const slug = a.getAttribute(WIKI_LINK_ATTR)
                  if (slug) selectBySearch(slug)
                }}
              />
            ) : (
              <div className="wiki-reader-body" id="ndBody">
                <p className="wiki-empty-desc">该页面正文暂不可用</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
