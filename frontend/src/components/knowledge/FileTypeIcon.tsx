import type { CSSProperties } from 'react'

/**
 * 文档文件类型图标 · 逐字对齐源站。
 *
 * <p><b>源站三件</b>：
 * <ul>
 *   <li>`views/chat/components/ArtifactFileIcon.vue` —— 外壳（32×38）；</li>
 *   <li>`utils/artifactFileIcon.ts` —— SVG 结构（sheet / fold / lines / 底部扩展名色块）；</li>
 *   <li>`components/css/artifact-file-icon.less` —— `kind-*` → 颜色（Word 蓝 / Excel 绿 /
 *       PPT 橙 / PDF 红 / 图片·视频 紫 / 代码·音频 青），描边 opacity .35。</li>
 * </ul>
 *
 * <p><b>为什么独立组件</b>：文档列表行、网格卡、抽屉头部三处都要用，各写一份必然漂移。
 */

export type FileIconKind =
  | 'file-pdf'
  | 'file-word'
  | 'file-excel'
  | 'file-powerpoint'
  | 'file'
  | 'code'
  | 'image'
  | 'sound'
  | 'video'

/** 扩展名 → kind（源站 `utils/files.ts:18-41` 的 `getFileIcon`，逐档照抄）。 */
export function fileIconKind(input: string | null | undefined): FileIconKind {
  const ext = (input ?? '').trim().toLowerCase().replace(/^\./, '')
  if (!ext) return 'file'
  if (ext === 'pdf') return 'file-pdf'
  if (ext === 'doc' || ext === 'docx') return 'file-word'
  if (ext === 'xls' || ext === 'xlsx' || ext === 'csv') return 'file-excel'
  if (ext === 'ppt' || ext === 'pptx') return 'file-powerpoint'
  if (['txt', 'md', 'markdown', 'json', 'log', 'yaml', 'yml', 'xml'].includes(ext)) return 'file'
  if ([
    'py', 'pyc', 'pyo', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'go', 'rs', 'java',
    'c', 'cc', 'cpp', 'h', 'hpp', 'sh', 'bash', 'rb', 'php', 'sql', 'html', 'htm',
  ].includes(ext)) return 'code'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return 'image'
  if (['mp3', 'wav', 'm4a', 'flac', 'ogg', 'aac'].includes(ext)) return 'sound'
  if (['mp4', 'mov', 'webm', 'mkv', 'avi'].includes(ext)) return 'video'
  return 'file'
}

/** kind → 主色（源站 `.artifact-file-icon-style()` 颜色表，逐个照抄）。 */
const KIND_COLOR: Record<FileIconKind, string> = {
  'file-word': '#5381c4',
  'file-excel': '#419781',
  'file-powerpoint': '#c18a4e',
  'file-pdf': '#c56868',
  image: '#9273bd',
  video: '#9273bd',
  code: '#5d95ad',
  sound: '#5d95ad',
  file: '#646a73',
}

/**
 * 文件类型图标。
 *
 * @param ext  扩展名（如 `pdf` / `docx`）；底部色块里的文字由它生成
 * @param size 宽度（源站 32×38，高度按比例）
 */
export function FileTypeIcon({ ext, size = 32, style }: {
  ext: string | null | undefined
  size?: number
  style?: CSSProperties
}) {
  const kind = fileIconKind(ext)
  const raw = (ext ?? '').trim().toUpperCase()
  // 源站只插值受限的扩展名标签（`/^[a-z0-9]{1,4}$/i`），其余显示 FILE
  const label = /^[A-Z0-9]{1,4}$/.test(raw) ? raw : 'FILE'
  const height = Math.round((size * 38) / 32)
  return (
    <svg
      className={`artifact-file-icon-svg kind-${kind}`}
      viewBox="0 0 32 38"
      fill="none"
      aria-hidden="true"
      style={{
        display: 'block',
        flexShrink: 0,
        width: size,
        height,
        color: KIND_COLOR[kind],
        ...style,
      }}
    >
      <path
        className="file-sheet"
        d="M6 1.5h13L28.5 11v22A3.5 3.5 0 0 1 25 36.5H6A3.5 3.5 0 0 1 2.5 33V5A3.5 3.5 0 0 1 6 1.5Z"
        fill="var(--td-bg-color-container, #fff)"
        stroke="currentColor"
        strokeOpacity={0.35}
        strokeWidth={1.2}
      />
      <path
        className="file-fold"
        d="M19 1.5V8a3 3 0 0 0 3 3h6.5"
        stroke="currentColor"
        strokeOpacity={0.35}
        strokeWidth={1.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        className="file-lines"
        d="M8 14h9M8 18h14"
        stroke="currentColor"
        strokeOpacity={0.35}
        strokeWidth={1.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <rect x="5" y="23" width="24" height="11" rx="3" fill="currentColor" />
      <text
        x="17"
        y="30.8"
        textAnchor="middle"
        fill="#fff"
        fontFamily="ui-sans-serif, system-ui, sans-serif"
        fontSize={7}
        fontWeight={650}
        letterSpacing="0.2px"
      >{label}</text>
    </svg>
  )
}
