import { useEffect, useMemo, useState } from 'react'
import { knowledgeApi } from '@/api/knowledge'
import type { Knowledge } from '@/api/knowledge'

interface Props {
  baseId: string
  showToast: (msg: string, type?: 'success' | 'info') => void
}

interface GalleryImage {
  id: string
  url: string
  title: string
  sourceTitle: string
}

/**
 * 画廊 · 对齐源侧 `views/knowledge/gallery/ImageGallery.vue` 的真机结构。
 *
 * <h2>真机取到的结构（⛔ 不要按想象改）</h2>
 * <pre>
 * .image-gallery
 *   .ig-toolbar
 *     .ig-toolbar__summary   全部图片 · 共 N 张
 *     .ig-toolbar__trailing  搜索框 + 两个筛选按钮 + 排序 · 创建时间
 *   .ig-scroll
 *     .wk-empty-state        图标 → 「还没有可浏览的图片」→「文档中的图片在解析完成后会出现在这里」
 * </pre>
 *
 * <h2>⚠️ 本仓的真实边界</h2>
 * 本仓的文档解析产出的图**没有单独的图片表**（`kb_knowledge` 只有原文/分块），
 * 也没有「文档内嵌图片抽取」这条链路。所以这里<b>如实显示空态</b>，
 * ⛔ 不伪造图片、不拿文档封面冒充图片。
 */
export function KbGalleryPanel({ baseId, showToast }: Props) {
  const [docs, setDocs] = useState<Knowledge[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    knowledgeApi
      .listKnowledge(baseId, { page: 1, page_size: 200 })
      .then((res) => { if (alive) setDocs(res.data ?? []) })
      .catch(() => { if (alive) setDocs([]) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [baseId])

  /**
   * 从文档的 `image_urls` 之类的字段里取。
   *
   * <p>⚠️ 本仓 `Knowledge` 没有图片字段 ⇒ 恒空。
   * 留这段是为了「有字段就展示」，而不是永远写死空态 ——
   * 一旦后端补上 `images`，这里立刻能显示。
   */
  const images = useMemo<GalleryImage[]>(() => {
    const out: GalleryImage[] = []
    for (const d of docs) {
      const raw = (d as unknown as { images?: unknown }).images
      if (!Array.isArray(raw)) continue
      for (const it of raw) {
        if (typeof it === 'string') {
          out.push({ id: `${d.id}-${out.length}`, url: it, title: d.title || '', sourceTitle: d.title || '' })
        } else if (it && typeof it === 'object') {
          const o = it as { id?: string; url?: string; title?: string }
          if (o.url) out.push({ id: o.id || `${d.id}-${out.length}`, url: o.url, title: o.title || '', sourceTitle: d.title || '' })
        }
      }
    }
    if (!keyword.trim()) return out
    const k = keyword.trim().toLowerCase()
    return out.filter((i) => (i.title + i.sourceTitle).toLowerCase().includes(k))
  }, [docs, keyword])

  /* 空态/加载态标记：源站 `.ig-scroll.is-empty`（kb-shared.css）才让 `.wk-empty-state` 撑满高度 */
  const isEmpty = !loading && images.length === 0

  return (
    <div className="image-gallery">
      <div className="ig-toolbar">
        <div className="ig-toolbar__summary">
          <span className="ig-toolbar__title">全部图片</span>
          <span className="ig-toolbar__count">共 {images.length} 张</span>
        </div>
        <div className="ig-toolbar__trailing">
          {/* 源站 `t-input class="ig-search"`（ImageGallery.vue:691）⇒ 三层手写结构 */}
          <div className="t-input__wrap ig-search">
            <div className="t-input t-input--prefix">
              <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
              <input
                className="t-input__inner"
                placeholder="搜索图片…"
                aria-label="搜索图片"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
          </div>
          <button type="button" className="ig-tool-btn" title="筛选" onClick={() => showToast('图片筛选：本仓暂未接入图片抽取', 'info')}>
            ⚗
          </button>
          <button type="button" className="ig-tool-btn" title="视图" onClick={() => showToast('视图切换：当前只有网格视图', 'info')}>
            ▦
          </button>
          <button type="button" className="ig-tool-btn ig-sort-trigger" title="排序">
            <span className="ig-sort-trigger__label">排序 · 创建时间</span>
          </button>
        </div>
      </div>

      {/* ⚠️ `.ig-grid` 源站带 `grid-auto-rows:1px`，靠 JS 给每张卡片算 `grid-row-end` 做瀑布流；
          本仓没有那段测量脚本 ⇒ 行内把 auto-rows 复位成 auto，否则卡片会互相叠压。 */}
      <div className={`ig-scroll${isEmpty ? ' is-empty' : ''}`}>
        {loading ? (
          <div className="ig-loading">
            <div className="wk-empty-state">
              <div className="wk-empty-state__title">正在加载…</div>
            </div>
          </div>
        ) : images.length === 0 ? (
          /* 源站空态 = components/EmptyState.vue 的 `.wk-empty-state` 四段 */
          <div className="wk-empty-state">
            <div className="wk-empty-state__icon">▧</div>
            <div className="wk-empty-state__title">还没有可浏览的图片</div>
            <div className="wk-empty-state__desc">文档中的图片在解析完成后会出现在这里</div>
            <div className="wk-empty-state__actions">
              <span style={{ fontSize: 12, color: 'var(--td-text-color-placeholder)', lineHeight: 1.6 }}>
                当前版本未做「文档内嵌图片抽取」，因此这里恒为空（不是加载失败）。
              </span>
            </div>
          </div>
        ) : (
          <div className="ig-grid" style={{ gridAutoRows: 'auto' }}>
            {images.map((img) => (
              /* 源站元素是 `<button class="ig-card">`（ImageGallery.vue:900-938）⇒ 同元素。
                 ⚠️ 源站点击会 `openViewer(idx)`；本仓没有图片查看器 ⇒ ⛔ 不挂 onClick
                 （保持与改造前一致：卡片不可点，不新增交互、也不造假提示）。 */
              <button
                type="button"
                className="ig-card"
                key={img.id}
                title={img.title || img.sourceTitle}
              >
                {/* 源站 `:style="{ aspectRatio: imageRatios[img.id] || 4/3 }"`（ImageGallery.vue:908） */}
                <div className="ig-card__thumb" style={{ aspectRatio: '4 / 3' }}>
                  <img src={img.url} alt={img.title || img.sourceTitle} loading="lazy" draggable={false} />
                </div>
                <div className="ig-card__meta">
                  <div className={`ig-card__caption${img.title ? '' : ' is-empty'}`}>
                    {img.title || '未命名图片'}
                  </div>
                  <div className="ig-card__source" title={img.sourceTitle}>
                    <span aria-hidden="true">▤</span>
                    <span>{img.sourceTitle || '—'}</span>
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
