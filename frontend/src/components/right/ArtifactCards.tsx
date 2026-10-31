import { memo } from 'react'
import type { ReactNode } from 'react'
import type { SessionArtifactDto } from '@/api/artifacts'
import { formatFileSize } from '@/utils/format'
import { artifactKindOf, isTextArtifact, type ArtifactKind } from '@/utils/artifactKind'

/**
 * [产物卡片] 右栏「产物」tab 与正文下方**同一套**卡片（同一份组件 / 同一套图标 / 同一个点击处理函数）。
 *
 * <p>设计稿：{@code .zjkycode/brainstorm/left-sidebar/content/artifact-cards-design.html}
 * （3 列网格 · 图标 42px · 文件名 2 行截断 · 大小 · 悬停上浮 1px + 阴影 · 右上角浮出操作按钮）。
 *
 * <p><b>WHY 图标按类型区分</b>：产物区是「最终交付物」的入口，用户扫一眼就要知道这是个网页 / 文档 /
 * 表格还是图片 —— 统一文件图标会让 8 个产物长得一模一样，扫不出差别。
 */
export type { ArtifactKind }

/** 折角页（docx/pptx/xlsx/pdf/file 共用底形） */
const PageShape = (
  <>
    <path d="M6 2.8h7.4L19 8.4v12.8H6z" />
    <path d="M13.2 2.9v5.6h5.4" />
  </>
)

/** 各类型图标（线宽 1.6 · 圆角端点 · 与设计稿「图标总表」逐字一致） */
const KIND_ICON: Record<ArtifactKind, ReactNode> = {
  html: (
    <>
      <circle cx="12" cy="12" r="8.4" />
      <path d="M3.6 12h16.8" />
      <path d="M12 3.6c2.3 2.4 3.4 5.3 3.4 8.4s-1.1 6-3.4 8.4c-2.3-2.4-3.4-5.3-3.4-8.4s1.1-6 3.4-8.4z" />
    </>
  ),
  docx: (<>{PageShape}<path d="M9 12.2h6M9 15.2h6M9 18.2h3.5" /></>),
  pptx: (<>{PageShape}<path d="M10 17.4v-4.2h1.4a1.3 1.3 0 0 1 0 2.6h-1.4M10 17.4v-4.2" /></>),
  xlsx: (<>{PageShape}<path d="M9.2 12.6v5.6M12.2 12.6v5.6M15.2 12.6v5.6M9.2 15h6M9.2 17h6" /></>),
  pdf: (<>{PageShape}<path d="M9.4 17.6c2.2-4.6 3.2-6.8 3.6-6.8.5 0 .4 1.4-.3 3.1-.7 1.7-1.2 2.5-2.6 3.7z" /></>),
  md: (
    <>
      <rect x="3.2" y="4.4" width="17.6" height="15.2" rx="3" />
      <path d="M7 15V9l3 3.4L13 9v6" />
      <path d="M16.4 9v6M14.6 12.8l1.8 2.2 1.8-2.2" />
    </>
  ),
  img: (
    <>
      <rect x="3.2" y="4.6" width="17.6" height="14.8" rx="2.6" />
      <circle cx="8.6" cy="9.6" r="1.6" />
      <path d="M3.6 16.4l4.6-4.2 3.4 3 3.2-2.8 5.2 4.6" />
    </>
  ),
  file: (<>{PageShape}<path d="M9 12.4h6M9 15.4h6M9 18.4h4" /></>),
}

/** 产物类型图标（底色分色由 CSS 的 `.ac-ico.k-<kind>` 决定）。 */
export function ArtifactKindIcon({ kind, size = 22 }: { kind: ArtifactKind; size?: number }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      {KIND_ICON[kind]}
    </svg>
  )
}

export interface ArtifactCardsProps {
  /** 产物列表（右栏 = 全部交付物；正文 = 最多 3 张） */
  items: SessionArtifactDto[]
  /** 点卡片 → 按类型分发打开（App 的 openArtifact · 右栏与正文共用同一个函数） */
  onOpen?: (a: SessionArtifactDto) => void
  /** 点「编辑」→ 打开可编辑预览（仅文本类有该入口） */
  onEdit?: (a: SessionArtifactDto) => void
  /** 点「在浏览器中打开」→ 用系统浏览器打开 raw 端点（仅 html 有该入口） */
  onOpenInBrowser?: (a: SessionArtifactDto) => void
  /** 右栏窄卡（正文宽卡）· 同一套组件，只是更紧凑的留白 */
  compact?: boolean
}

/**
 * 产物卡片网格（3 列 · 与设计稿一致）。
 *
 * <p>「在浏览器中打开」**只有 html 有**（其它类型不是网页，外链打开无意义）；
 * 「编辑」**只有文本类有**（二进制 PUT /content 会 400 ⇒ 不给假入口）。
 */
export const ArtifactCards = memo(function ArtifactCards({
  items,
  onOpen,
  onEdit,
  onOpenInBrowser,
  compact = false,
}: ArtifactCardsProps) {
  if (items.length === 0) return null
  return (
    <div className={`artifact-cards${compact ? ' compact' : ''}`}>
      {items.map((a) => {
        const kind = artifactKindOf(a.title)
        const editable = isTextArtifact(a.title)
        const showBrowser = kind === 'html' && !!onOpenInBrowser
        const showEdit = editable && !!onEdit
        return (
          <div
            key={a.id}
            className="artifact-card"
            title={a.localPath ?? a.title}
            onClick={() => onOpen?.(a)}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen?.(a) }
            }}
          >
            {(showBrowser || showEdit) && (
              <span className="ac-actions">
                {showBrowser && (
                  <button
                    type="button"
                    className="ac-act"
                    title="在浏览器中打开"
                    aria-label="在浏览器中打开"
                    onClick={(e) => { e.stopPropagation(); onOpenInBrowser?.(a) }}
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M10.5 13.5a3.5 3.5 0 0 0 5 0l3-3a3.54 3.54 0 0 0-5-5L12 7" />
                      <path d="M13.5 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.54 3.54 0 0 0 5 5l1.5-1.5" />
                    </svg>
                  </button>
                )}
                {showEdit && (
                  <button
                    type="button"
                    className="ac-act"
                    title="编辑（保存写回归档副本）"
                    aria-label="编辑"
                    onClick={(e) => { e.stopPropagation(); onEdit?.(a) }}
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M12 20h9" />
                      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
                    </svg>
                  </button>
                )}
              </span>
            )}
            <span className={`ac-ico k-${kind}`}>
              <ArtifactKindIcon kind={kind} />
            </span>
            <span className="ac-name" title={a.title}>{a.title}</span>
            <span className="ac-size">
              {a.sizeBytes != null ? formatFileSize(a.sizeBytes) : ''}
            </span>
          </div>
        )
      })}
    </div>
  )
})
