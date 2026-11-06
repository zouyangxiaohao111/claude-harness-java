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

  return (
    <div className="kb-gallery">
      <div className="kb-gallery-toolbar">
        <div className="kb-gallery-summary">
          <span className="kb-gallery-title">全部图片</span>
          <span className="kb-gallery-count">共 {images.length} 张</span>
        </div>
        <div className="kb-gallery-trailing">
          <input
            className="kb-input kb-gallery-search"
            placeholder="搜索图片…"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <button type="button" className="kb-gallery-toolbtn" title="筛选" onClick={() => showToast('图片筛选：本仓暂未接入图片抽取', 'info')}>
            ⚗
          </button>
          <button type="button" className="kb-gallery-toolbtn" title="视图" onClick={() => showToast('视图切换：当前只有网格视图', 'info')}>
            ▦
          </button>
          <button type="button" className="kb-gallery-toolbtn kb-gallery-sort" title="排序">
            排序 · 创建时间
          </button>
        </div>
      </div>

      <div className="kb-gallery-scroll">
        {loading ? (
          <div className="kb-gallery-empty">
            <div className="kb-gallery-empty-title">正在加载…</div>
          </div>
        ) : images.length === 0 ? (
          <div className="kb-gallery-empty">
            <div className="kb-gallery-empty-icon">▧</div>
            <div className="kb-gallery-empty-title">还没有可浏览的图片</div>
            <div className="kb-gallery-empty-desc">文档中的图片在解析完成后会出现在这里</div>
            <div className="kb-gallery-empty-actions">
              <span className="kb-gallery-empty-note">
                当前版本未做「文档内嵌图片抽取」，因此这里恒为空（不是加载失败）。
              </span>
            </div>
          </div>
        ) : (
          <div className="kb-gallery-grid">
            {images.map((img) => (
              <figure className="kb-gallery-card" key={img.id} title={img.title || img.sourceTitle}>
                <img src={img.url} alt={img.title || img.sourceTitle} loading="lazy" />
                <figcaption>{img.sourceTitle}</figcaption>
              </figure>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
