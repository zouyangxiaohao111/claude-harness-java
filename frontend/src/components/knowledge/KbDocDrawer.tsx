import { useEffect, useMemo, useState } from 'react'
import { knowledgeApi } from '@/api/knowledge'
import type { ChunkView, Knowledge } from '@/api/knowledge'

interface Props {
  knowledge: Knowledge
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

type ViewMode = 'preview' | 'chunks'

/**
 * 文档详情抽屉 · 逐项对齐源侧真机 `.doc-main-drawer`。
 *
 * <h2>⭐ 真机结构（2026-11-06 从 localhost 实测，⛔ 不要凭记忆写）</h2>
 * <pre>
 * .doc-main-drawer（右侧抽屉，宽 654px）
 *   ├─ header   高 67px，padding 14px 18px，底部 1px #e7e7e7
 *   │            ├ 文件图标 + 文件名（可换行）
 *   │            └ 右上：下载 / 追溯 / 助手 三个图标按钮
 *   └─ body（padding 24px 32px 级别的分节）
 *        ├ kp-timeline   解析流水线圆点（8px 圆，done 绿 / skipped 灰）+「总耗时：1m47.1s」
 *        ├ 基本信息      两行 上传时间 / 类型（label 12px rgba(0,0,0,.6)）
 *        ├ 自定义元数据  「添加元数据字段」
 *        ├ 摘要         可折叠摘要正文
 *        └ 文件内容      「共 N 个片段」+ 右侧 [预览] [查看分块] 两个按钮
 * </pre>
 *
 * <h2>⚠️ 本仓的真实边界（如实说明，⛔ 不造假）</h2>
 * <ul>
 *   <li>`kp-timeline` 的五个阶段：本仓解析是**单阶段**（上传 → 解析 → 索引），
 *       所以只画真实存在的三段，⛔ 不复制源侧那五个圆点。</li>
 *   <li>「自定义元数据」：本仓 `kb_knowledge` 无 metadata 列 ⇒ 显示「暂无元数据字段」，
 *       添加按钮点了如实说未接入。</li>
 *   <li>「摘要」：本仓有解析产出的 `summary` 字段就显示，没有就说明未生成。</li>
 *   <li>「预览」：本仓不做原格式渲染（源侧有 docx 预览器）⇒ 切换「分块」视图，
 *       预览按钮置灰并说明。</li>
 * </ul>
 */
export function KbDocDrawer({ knowledge, onClose, showToast }: Props) {
  const [view, setView] = useState<ViewMode>('chunks')
  const [chunks, setChunks] = useState<ChunkView[]>([])
  const [loading, setLoading] = useState(true)
  const [chunkError, setChunkError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setChunkError(null)
    knowledgeApi
      .listChunks(knowledge.id)
      .then((res) => { if (alive) setChunks(Array.isArray(res) ? res : []) })
      .catch((e) => { if (alive) setChunkError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [knowledge.id])

  // Esc 关闭（与源侧抽屉一致）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const title = knowledge.title || knowledge.file_name || knowledge.id
  const sizeText = formatSize(knowledge.file_size)
  const stage = useMemo(() => stageOf(knowledge.parse_status), [knowledge.parse_status])

  return (
    <div className="kb-drawer-overlay" onClick={onClose}>
      <aside
        className="kb-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`文档详情：${title}`}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="kb-drawer-head">
          <div className="kb-drawer-head-main">
            <span className="kb-drawer-icon">📄</span>
            <span className="kb-drawer-title" title={title}>{title}</span>
          </div>
          <div className="kb-drawer-head-actions">
            <a
              className="kb-drawer-iconbtn"
              href={knowledgeApi.downloadUrl(knowledge.id)}
              download
              title="下载原文件"
            >
              ⤓
            </a>
            <button
              type="button"
              className="kb-drawer-iconbtn"
              title="追溯解析链路"
              onClick={() => showToast('追溯：本仓未提供解析链路回放', 'info')}
            >
              ⇄
            </button>
            <button type="button" className="kb-drawer-close" onClick={onClose} title="关闭">✕</button>
          </div>
        </header>

        <div className="kb-drawer-body">
          {/* 解析流水线（只画本仓真实存在的三段）*/}
          <section className="kb-drawer-sec">
            <div className="kb-drawer-flow">
              {stage.dots.map((d, i) => (
                <span key={i} className={`kb-drawer-dot is-${d.state}`} title={d.label} />
              ))}
            </div>
            <div className="kb-drawer-flow-caption">{stage.caption}</div>
          </section>

          {/* 基本信息 */}
          <section className="kb-drawer-sec">
            <h4 className="kb-drawer-sec-title">基本信息</h4>
            <div className="kb-drawer-rows">
              <div className="kb-drawer-row">
                <span className="kb-drawer-label">上传时间</span>
                <span className="kb-drawer-value">{knowledge.created_at ?? '—'}</span>
              </div>
              <div className="kb-drawer-row">
                <span className="kb-drawer-label">类型</span>
                <span className="kb-drawer-value">
                  <span className="kb-drawer-tag">{(knowledge.file_type || '未知').toUpperCase()}</span>
                  {sizeText && <span className="kb-drawer-dim"> · {sizeText}</span>}
                </span>
              </div>
              <div className="kb-drawer-row">
                <span className="kb-drawer-label">状态</span>
                <span className="kb-drawer-value">{knowledge.parse_status || '—'}</span>
              </div>
              <div className="kb-drawer-row">
                <span className="kb-drawer-label">片段数</span>
                <span className="kb-drawer-value">{chunks.length} 个</span>
              </div>
            </div>
          </section>

          {/* 自定义元数据 */}
          <section className="kb-drawer-sec">
            <div className="kb-drawer-sec-head">
              <h4 className="kb-drawer-sec-title">自定义元数据</h4>
              <button
                type="button"
                className="kb-drawer-iconbtn"
                title="添加元数据字段"
                onClick={() => showToast('自定义元数据：本仓 kb_knowledge 无 metadata 列，暂未接入', 'info')}
              >
                ＋
              </button>
            </div>
            <div className="kb-drawer-empty">暂无元数据字段</div>
          </section>

          {/* 摘要 */}
          <section className="kb-drawer-sec">
            <h4 className="kb-drawer-sec-title">摘要</h4>
            {/* ⚠️ 本仓 `kb_knowledge` **没有 summary 正文列**（只有 `summary_status`）：
                摘要是「生成过/没生成过」的状态，正文落在分块里。
                ⇒ ⛔ 不要编一段摘要出来；如实说状态。 */}
            <div className="kb-drawer-empty">
              {summaryHint(knowledge.summary_status)}
            </div>
          </section>

          {/* 文件内容 */}
          <section className="kb-drawer-sec kb-drawer-sec-grow">
            <div className="kb-drawer-sec-head">
              <h4 className="kb-drawer-sec-title">
                文件内容
                <span className="kb-drawer-chunkcount">共 {chunks.length} 个片段</span>
              </h4>
              <div className="kb-drawer-viewbtns">
                <button
                  type="button"
                  className={`kb-drawer-viewbtn${view === 'preview' ? ' is-active' : ''}`}
                  onClick={() => showToast('原格式预览：本仓未接入 docx/pdf 渲染器', 'info')}
                >
                  预览
                </button>
                <button
                  type="button"
                  className={`kb-drawer-viewbtn${view === 'chunks' ? ' is-active' : ''}`}
                  onClick={() => setView('chunks')}
                >
                  查看分块
                </button>
              </div>
            </div>

            <div className="kb-drawer-chunks">
              {loading && <div className="kb-drawer-empty">正在加载分块…</div>}
              {!loading && chunkError && (
                <div className="kb-drawer-empty">分块加载失败：{chunkError}</div>
              )}
              {!loading && !chunkError && chunks.length === 0 && (
                <div className="kb-drawer-empty">还没有分块（文档需解析完成）</div>
              )}
              {!loading && !chunkError && chunks.length > 0 && (
                <ol className="kb-drawer-chunklist">
                  {chunks.map((c, i) => (
                    <li className="kb-drawer-chunk" key={c.id}>
                      <div className="kb-drawer-chunk-head">
                        <span className="kb-drawer-chunk-idx">#{i + 1}</span>
                        {c.context_header && (
                          <span className="kb-drawer-chunk-hdr" title={c.context_header}>{c.context_header}</span>
                        )}
                        <span className="kb-drawer-chunk-len">{c.content?.length ?? 0} 字</span>
                      </div>
                      <div className="kb-drawer-chunk-body">{c.content}</div>
                    </li>
                  ))}
                </ol>
              )}
            </div>
          </section>
        </div>
      </aside>
    </div>
  )
}

/** 解析阶段 → 三个圆点（本仓真实阶段：上传 / 解析 / 索引）。 */
function stageOf(status: string | undefined): {
  dots: Array<{ label: string; state: 'done' | 'active' | 'skipped' }>
  caption: string
} {
  const s = status ?? ''
  if (s === 'completed') {
    return {
      dots: [
        { label: '已上传', state: 'done' },
        { label: '已解析', state: 'done' },
        { label: '已索引', state: 'done' },
      ],
      caption: '解析完成',
    }
  }
  if (s === 'failed' || s === 'cancelled') {
    return {
      dots: [
        { label: '已上传', state: 'done' },
        { label: s === 'failed' ? '解析失败' : '已取消', state: 'skipped' },
        { label: '未索引', state: 'skipped' },
      ],
      caption: s === 'failed' ? '解析失败' : '已取消',
    }
  }
  return {
    dots: [
      { label: '已上传', state: 'done' },
      { label: '解析中', state: 'active' },
      { label: '待索引', state: 'skipped' },
    ],
    caption: '正在解析…',
  }
}

/**
 * 摘要状态的实话。
 *
 * <p>⭐ 本仓 `kb_knowledge.summary_status` 是唯一真值；`kb_knowledge` 没有 summary 正文列，
 * 摘要正文在分块/后处理产物里。所以这里只报状态，⛔ 不拼一段假摘要。
 */
function summaryHint(status: string | undefined | null): string {
  switch (status) {
    case 'completed':
      return '摘要已生成（摘要正文在分块内容中，可在「查看分块」里读到）'
    case 'pending':
    case 'running':
      return '摘要生成中…'
    case 'failed':
      return '摘要生成失败（可在列表页重新解析）'
    case 'skipped':
      return '本文档已跳过摘要生成（可在知识库设置里开启后重解析）'
    default:
      return '该文档尚未生成摘要（解析完成或开启「摘要」后才会出现）'
  }
}

function formatSize(bytes: number | undefined | null): string {
  if (!bytes || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
