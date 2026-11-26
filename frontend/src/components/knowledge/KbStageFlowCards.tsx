/**
 * 阶段流程卡片 —— 「现在到哪一步」视图（与原有瀑布/甘特视图并存，用户自选）。
 *
 * <p>数据与 `KbProcessingTimeline` 同源（同一份 span 树），只换画法：
 * 五段主管线 + 后处理子任务 + 三个落库表，按 <b>S 形</b>排布
 * （Row1 左→右、Row2 右→左、Row3 左→右）。
 *
 * <p>⛔ 不要为未到达的节点画灰态占位：卡片**到达才长出**（`.on` 控制 opacity/位移）。
 */
import { useEffect, useState } from 'react'
import type { KnowledgeTraceNode } from '../../utils/knowledgeTrace'

export interface KbStageFlowCardsProps {
  /** 恒定五段（`stagesFromTrace` 的结果，未跑的段是 pending 占位）。 */
  stages: KnowledgeTraceNode[]
  /** postprocess 节点（含 summary/question/graph 等子任务）。 */
  postprocess?: KnowledgeTraceNode | null
  /** `kb_knowledge.parse_status`，用于区分「排队中」与「已上传」。 */
  parseStatus?: string
  /** 重试单个后处理子任务（只有 `kb_ingest_task` 有行的类型能重试）。 */
  onRetrySubtask?: (taskType: string) => Promise<void> | void
  /** 全流程重试（主阶段失败只能走这条）。 */
  onRetryAll?: () => Promise<void> | void
}

/** 卡片 id → 后端任务类型；不在这里的节点没有独立任务行（只能全流程重跑）。 */
const SUBTASK_TYPE: Record<string, string> = {
  question: 'question',
  summary: 'summary',
  wiki: 'wiki',
  autoTag: 'auto_tag',
}

/** 主阶段：失败后只能全流程重跑。 */
const MAIN_STAGE_IDS = new Set(['docreader', 'chunking', 'embedding', 'multimodal', 'postprocess'])

type CardState = 'pending' | 'running' | 'completed' | 'failed' | 'skipped'

interface CardDef {
  id: string
  title: string
  sub: string
  x: number
  y: number
  kind: 'external' | 'backend' | 'messagebus' | 'database'
}

export const CARD_W = 130
export const CARD_H = 60
export const CARD_VIEW_W = 1060
export const CARD_VIEW_H = 430

/** S 形：Row1 x 递增，Row2 x 递减，Row3 再递增。 */
const NODES: CardDef[] = [
  { id: 'uploader', title: '文档上传者', sub: '知识库 · 文档页', x: 30, y: 40, kind: 'external' },
  { id: 'uploadApi', title: '上传接口', sub: 'POST /knowledge/file', x: 200, y: 40, kind: 'backend' },
  { id: 'queue', title: '入库任务队列', sub: 'kb_ingest_task', x: 370, y: 40, kind: 'messagebus' },
  { id: 'worker', title: '解析线程池', sub: 'kb-ingest-parse', x: 540, y: 40, kind: 'backend' },
  { id: 'docreader', title: '① 文档解析', sub: 'docreader', x: 710, y: 40, kind: 'backend' },
  { id: 'chunking', title: '② 内容分块', sub: 'chunking', x: 880, y: 40, kind: 'backend' },
  { id: 'embedding', title: '③ 向量化', sub: 'embedding', x: 880, y: 190, kind: 'backend' },
  { id: 'multimodal', title: '④ 多模态识别', sub: 'multimodal', x: 710, y: 190, kind: 'backend' },
  { id: 'postprocess', title: '⑤ 后处理派发', sub: 'postprocess', x: 540, y: 190, kind: 'backend' },
  { id: 'question', title: '问句生成', sub: 'postprocess.question', x: 370, y: 190, kind: 'database' },
  { id: 'writer', title: '索引写入器', sub: 'KbIndexWriter', x: 200, y: 190, kind: 'backend' },
  { id: 'chunks', title: '分块表', sub: 'kb_chunk', x: 200, y: 340, kind: 'database' },
  { id: 'vec', title: '向量条目表', sub: 'kb_embedding', x: 370, y: 340, kind: 'database' },
  { id: 'fts', title: '全文索引表', sub: 'kb_chunk_fts', x: 540, y: 340, kind: 'database' },
]

/** [id, path, toId] —— Row2 的箭头指向左（S 形回折）。 */
const EDGES: Array<[string, string, string]> = [
  ['e1', 'M 160 70 L 194 70', 'uploadApi'],
  ['e2', 'M 330 70 L 364 70', 'queue'],
  ['e3', 'M 500 70 L 534 70', 'worker'],
  ['e4', 'M 670 70 L 704 70', 'docreader'],
  ['e5', 'M 840 70 L 874 70', 'chunking'],
  ['e6', 'M 945 100 L 945 186', 'embedding'],
  ['e7', 'M 880 220 L 846 220', 'multimodal'],
  ['e8', 'M 710 220 L 676 220', 'postprocess'],
  ['e9', 'M 540 220 L 506 220', 'question'],
  ['e10', 'M 370 220 L 336 220', 'writer'],
  ['e11', 'M 265 250 L 265 336', 'chunks'],
  ['e12', 'M 330 370 L 364 370', 'vec'],
  ['e13', 'M 500 370 L 534 370', 'fts'],
]

const STAGE_NAMES = ['docreader', 'chunking', 'embedding', 'multimodal', 'postprocess'] as const

/**
 * 后端状态值域是 `pending|running|completed|failed|skipped|cancelled`
 * （`KbSpanStatus`）。⛔ 直接透传会让 `STATE_STYLE[status]` 取到 undefined
 * ⇒ 渲染抛 `Cannot read properties of undefined`（实测把整个 Trace 抽屉打成错误页）。
 * 统一归一化，未知值一律按 pending。
 */
function normalize(status?: string | null): CardState {
  switch (status) {
    case 'running': return 'running'
    // ⭐ 线上真实值就是 `done`（`/knowledge/{id}/spans` 实测），不是 `completed`
    case 'done':
    case 'completed':
    case 'success': return 'completed'
    case 'failed': return 'failed'
    case 'skipped': return 'skipped'
    case 'cancelled': return 'skipped'
    default: return 'pending'
  }
}

/** 子任务 span 名形如 `postprocess.question` / `postprocess.graph.chunk[3]`。 */
function subState(pp: KnowledgeTraceNode | null | undefined, type: string): CardState {
  const kids = pp?.children ?? []
  const hit = kids.find((k) => (k.name || '').includes(type))
  if (!hit) return 'pending'
  return normalize(hit.status)
}

/**
 * 每张卡片的状态。
 *
 * <p>规则（不猜）：
 * - 五个阶段直接读各自 span 的 status；
 * - `uploader/uploadApi` 只要有 trace 就已完成（能拿到 span 说明已经上传过）；
 * - `queue/worker` 在「还没有任何阶段开始」时才算进行中；
 * - `writer` 跟 ③/④ 中较慢的一个，`chunks/vec/fts` 跟 ⑤ 完成。
 */
export function deriveCardStates(
  stages: KnowledgeTraceNode[],
  postprocess: KnowledgeTraceNode | null | undefined,
  parseStatus?: string,
): Record<string, CardState> {
  const byName = new Map<string, KnowledgeTraceNode>()
  for (const s of stages) if (s?.name) byName.set(s.name, s)
  const st = (n: string): CardState => normalize(byName.get(n)?.status)

  const doc = st('docreader')
  const ch = st('chunking')
  const emb = st('embedding')
  const mm = st('multimodal')
  const pp = st('postprocess')

  const anyStarted = STAGE_NAMES.some((n) => st(n) !== 'pending')
  const queued = !anyStarted && (parseStatus === 'pending' || parseStatus === 'unprocessed')
  const tailDone = (s: CardState): CardState =>
    s === 'completed' ? 'completed' : s === 'failed' ? 'pending' : 'pending'

  const q = subState(postprocess, 'question')
  const writer: CardState =
    emb === 'completed' || mm === 'completed'
      ? 'completed'
      : emb === 'running' || mm === 'running'
        ? 'running'
        : 'pending'

  return {
    uploader: 'completed',
    uploadApi: 'completed',
    queue: queued ? 'running' : anyStarted ? 'completed' : 'pending',
    worker: queued ? 'pending' : anyStarted ? 'completed' : 'pending',
    docreader: doc,
    chunking: ch,
    embedding: emb,
    multimodal: mm,
    postprocess: pp,
    question: q,
    writer,
    chunks: tailDone(ch),
    vec: tailDone(emb),
    fts: tailDone(pp),
  }
}

/**
 * 节点类型色（浅色主题 · 与架构图一致）。
 *
 * <p>⭐ 卡片底色/描边按<b>类型</b>着色，不是一律白底：backend 绿 · database 紫 ·
 * messagebus 橙 · external 灰蓝。状态只叠加「失败玫红 / 跳过灰虚线 / 运行中发光」。
 */
const KIND_COLOR: Record<string, { stroke: string; tint: string }> = {
  backend: { stroke: '#059669', tint: 'rgba(5,150,105,0.12)' },
  database: { stroke: '#7c3aed', tint: 'rgba(124,58,237,0.12)' },
  messagebus: { stroke: '#ea580c', tint: 'rgba(234,88,12,0.12)' },
  external: { stroke: '#64748b', tint: 'rgba(100,116,139,0.12)' },
}

const STATE_LABEL: Record<CardState, string> = {
  pending: '未开始',
  running: '执行中',
  completed: '已完成',
  failed: '失败',
  skipped: '已跳过',
}

function styleFor(kind: string, state: CardState): { stroke: string; fill: string; text: string } {
  const base = KIND_COLOR[kind] ?? KIND_COLOR.external
  if (state === 'failed') return { stroke: '#e11d48', fill: 'rgba(225,29,72,0.12)', text: '#1f2329' }
  if (state === 'skipped') return { stroke: '#cbd5e1', fill: '#fbfcfd', text: '#8b9099' }
  if (state === 'running' || state === 'completed') {
    return { stroke: base.stroke, fill: base.tint, text: '#1f2329' }
  }
  return { stroke: '#d7dce3', fill: '#ffffff', text: '#8b9099' }
}

const CSS = `
.kb-fc{opacity:0;transform:translateY(8px) scale(.96);transition:opacity .38s cubic-bezier(.2,.7,.3,1),transform .38s cubic-bezier(.2,.7,.3,1)}
.kb-fc.on{opacity:1;transform:none}
.kb-fc rect.card{filter:drop-shadow(0 1px 1px rgba(17,24,39,.05)) drop-shadow(0 6px 12px rgba(17,24,39,.06));transition:stroke .3s ease,fill .3s ease,stroke-width .2s ease}
.kb-fc{cursor:pointer}
.kb-fc:hover rect.card{stroke-width:2}
.kb-fc.run rect.card{animation:kbFcPulse 2.2s ease-in-out infinite}
@keyframes kbFcPulse{0%,100%{filter:drop-shadow(0 1px 1px rgba(17,24,39,.05)) drop-shadow(0 6px 12px rgba(17,24,39,.06))}50%{filter:drop-shadow(0 0 9px rgba(5,150,105,.45)) drop-shadow(0 0 18px rgba(5,150,105,.18))}}
.kb-fc.picked rect.card{stroke-width:2.4}
.kb-edge{opacity:0;transition:opacity .3s ease}
.kb-edge.on{opacity:1}
.kb-edge path{stroke:#c3cbd6;stroke-width:1.6;fill:none;stroke-dasharray:300;stroke-dashoffset:300;transition:stroke-dashoffset .6s cubic-bezier(.3,.8,.3,1)}
.kb-edge.on path{stroke-dashoffset:0}
.kb-edge.on path.emph{stroke:#34d399}
`

function fmtMs(ms?: number | null): string {
  if (ms == null || ms < 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

/**
 * S 形卡片视图。⛔ 不要在这里发起请求：数据全部来自上层已取到的 span 树。
 */
export function KbStageFlowCards({ stages, postprocess, parseStatus, onRetrySubtask, onRetryAll }: KbStageFlowCardsProps) {
  const states = deriveCardStates(stages, postprocess, parseStatus)
  const failed = NODES.filter((n) => states[n.id] === 'failed')
  const [busy, setBusy] = useState(false)
  const retryOne = async (id: string) => {
    const type = SUBTASK_TYPE[id]
    setBusy(true)
    try {
      if (type && onRetrySubtask) await onRetrySubtask(type)
      else if (onRetryAll) await onRetryAll()
    } finally {
      setBusy(false)
    }
  }
  const durById = new Map<string, number | undefined>()
  for (const s of stages) if (s?.name) durById.set(s.name, s.duration_ms)
  const qChild = (postprocess?.children ?? []).find((c) => (c.name || '').includes('question'))
  if (qChild) durById.set('question', qChild.duration_ms)

  // ── 逐张长出：按 S 形顺序 stagger，只对「新到达」的节点放动画 ──
  //   状态签名变化才触发（对象引用每次渲染都变，不能直接依赖 states）。
  const sig = NODES.map((n) => states[n.id]).join('|')
  const [revealed, setRevealed] = useState<Set<string>>(() => new Set())
  useEffect(() => {
    const arrived = NODES.filter((n) => (states[n.id] ?? 'pending') !== 'pending').map((n) => n.id)
    const todo = arrived.filter((id) => !revealed.has(id))
    // 已经显示的全部到达（如首次挂载的已完成文档）⇒ 一次性错峰淡入，不要等 14×110ms
    const timers = todo.map((id, i) =>
      window.setTimeout(() => {
        setRevealed((prev) => {
          const next = new Set(prev)
          next.add(id)
          return next
        })
      }, i * 110),
    )
    return () => timers.forEach((t) => window.clearTimeout(t))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig])

  const [picked, setPicked] = useState<string | null>(null)
  const pickedNode = picked ? NODES.find((n) => n.id === picked) ?? null : null

  return (
    <div className="kb-cards" style={{ padding: 8 }}>
      <style>{CSS}</style>
      <svg
        viewBox={`0 0 ${CARD_VIEW_W} ${CARD_VIEW_H}`}
        style={{ display: 'block', width: '100%', height: 'auto' }}
        role="img"
        aria-label="知识处理阶段流程"
      >
        <defs>
          <pattern id="kbFcDots" width="20" height="20" patternUnits="userSpaceOnUse">
            <circle cx="1" cy="1" r="1" fill="#e2e7ec" />
          </pattern>
          <marker id="kbFcArrow" viewBox="0 0 10 7" refX="9" refY="3.5" markerWidth="10" markerHeight="7" orient="auto">
            <polygon points="0 0,10 3.5,0 7" fill="#9aa4b2" />
          </marker>
        </defs>
        <rect width={CARD_VIEW_W} height={CARD_VIEW_H} fill="url(#kbFcDots)" />
        {EDGES.map(([id, d, to]) => {
          const on = revealed.has(to)
          return (
            <g key={id} className={`kb-edge${on ? ' on' : ''}`} data-to={to}>
              <path d={d} markerEnd="url(#kbFcArrow)" className={on ? 'emph' : ''} />
            </g>
          )
        })}
        {NODES.map((n) => {
          const s = states[n.id] ?? 'pending'
          const on = revealed.has(n.id)
          const col = styleFor(n.kind, s)
          const ms = fmtMs(durById.get(n.id))
          return (
            <g
              key={n.id}
              className={`kb-fc${on ? ' on' : ''}${s === 'running' ? ' run' : ''}${picked === n.id ? ' picked' : ''}`}
              data-node={n.id}
              data-state={s}
              onClick={() => setPicked((prev) => (prev === n.id ? null : n.id))}
            >
              <rect
                className="card"
                x={n.x}
                y={n.y}
                width={CARD_W}
                height={CARD_H}
                rx={8}
                fill={col.fill}
                stroke={col.stroke}
                strokeWidth={s === 'skipped' ? 1 : 1.25}
                strokeDasharray={s === 'skipped' ? '4 4' : undefined}
              />
              <text x={n.x + CARD_W / 2} y={n.y + 28} textAnchor="middle" fontSize={11} fontWeight={600} fill={col.text}>
                {n.title}
              </text>
              <text x={n.x + CARD_W / 2} y={n.y + 44} textAnchor="middle" fontSize={9} fill="#8b9099">
                {n.sub}
              </text>
              {ms && (
                <text x={n.x + CARD_W / 2} y={n.y + CARD_H + 15} textAnchor="middle" fontSize={9} fill="#8b9099">
                  {ms}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      {/* 点卡片 → 就地展开这一节点的摘要（不跳视图） */}
      {pickedNode && (
        <div
          style={{
            display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 10, marginTop: 6,
            padding: '8px 10px', border: '1px solid #e5e7eb', borderRadius: 8,
            background: '#fff', fontSize: 12, color: '#1f2329',
          }}
        >
          <strong>{pickedNode.title}</strong>
          <code style={{ fontSize: 11, color: '#8b9099' }}>{pickedNode.sub}</code>
          <span style={{ color: '#646a73' }}>{STATE_LABEL[states[pickedNode.id] ?? 'pending']}</span>
          {fmtMs(durById.get(pickedNode.id)) && (
            <span style={{ color: '#8b9099' }}>耗时 {fmtMs(durById.get(pickedNode.id))}</span>
          )}
          <span style={{ flex: 1 }} />
          <button
            type="button"
            onClick={() => setPicked(null)}
            style={{ padding: '2px 8px', borderRadius: 6, border: '1px solid #d7dce3', background: '#fff', fontSize: 12, cursor: 'pointer' }}
          >
            收起
          </button>
        </div>
      )}
      {failed.length > 0 && (
        <div
          style={{
            display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8,
            padding: '8px 6px 2px', fontSize: 12, color: '#646a73',
          }}
        >
          <span style={{ color: '#e11d48' }}>{failed.map((n) => n.title).join('、')} 失败</span>
          {failed.map((n) => {
            const canSingle = !!SUBTASK_TYPE[n.id] && !MAIN_STAGE_IDS.has(n.id)
            return (
              <button
                key={n.id}
                type="button"
                disabled={busy}
                onClick={() => void retryOne(n.id)}
                title={canSingle ? '只重跑这一个后处理子任务' : '主阶段没有独立任务行，只能整篇重解析'}
                style={{
                  padding: '3px 10px', borderRadius: 6, fontSize: 12, cursor: busy ? 'default' : 'pointer',
                  border: '1px solid #d7dce3', background: '#fff', color: '#1f2329', opacity: busy ? 0.6 : 1,
                }}
              >
                {canSingle ? `重试 ${n.title}` : '全流程重试'}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}