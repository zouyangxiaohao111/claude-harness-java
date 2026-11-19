import type { ReactNode } from 'react'
import { CODE_TILES, type CodeTile, type FileKind } from '@/utils/fileType'

/**
 * 文件类型图标 · 参照 DSH `FileTypeIcon`：
 * 代码/配置 = 品牌色方牌 + 1~2 字符标记（非代码类仍用线条/徽章）：
 * 文件夹 / Git 分支 / Markdown 页 / Word·Excel·PPT·PDF 字标 / 图片 / 视频 / 通用文档。
 */
export function FileTypeIcon({ kind, size = 12, open = false }: { kind: FileKind; size?: number; open?: boolean }) {
  const tile = CODE_TILES[kind]
  if (tile) return tileIcon(tile, size)

  const stroke = {
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.4,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  }

  const svg = (children: ReactNode, viewBox = '0 0 14 14') => (
    <svg
      viewBox={viewBox}
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      style={{ display: 'block' }}
    >
      {children}
    </svg>
  )

  switch (kind) {
    case 'folder':
      // 与 DSH 树完全对齐的两套：收起 = IconFolderCloseRegular（线框 + 横线），
      // 展开 = IconFolderOpenRegular（实心打开态）。路径取自 dsh-client-ui-primitives。
      if (open) {
        return svg(
          <>
            <path
              fill="currentColor"
              opacity="0.16"
              d="M2.55912 7.93683C2.67584 7.49906 3.0723 7.19446 3.52536 7.19446H13.6491C14.3061 7.19446 14.7846 7.81725 14.6153 8.45209L13.4411 12.856C13.3244 13.2938 12.9279 13.5984 12.4748 13.5984H2.35113C1.69411 13.5984 1.21562 12.9756 1.38489 12.3407L2.55912 7.93683Z"
            />
            <path
              fill="currentColor"
              d="M13.6491 6.69446C14.6346 6.69453 15.3522 7.62895 15.0983 8.58118L13.9245 12.9845C13.7494 13.6412 13.1539 14.0988 12.4743 14.0988H2.35126C1.36574 14.0988 0.648153 13.1643 0.902044 12.212L2.07587 7.80774C2.25102 7.15128 2.84567 6.69455 3.52509 6.69446H13.6491ZM3.52509 7.69446C3.29865 7.69455 3.10004 7.84674 3.04169 8.06555L1.86786 12.4698C1.78345 12.7872 2.02285 13.0988 2.35126 13.0988H12.4743C12.7007 13.0988 12.8992 12.9463 12.9577 12.7277L14.1325 8.32336C14.2171 8.00598 13.9776 7.69453 13.6491 7.69446H3.52509Z"
            />
            <path
              fill="currentColor"
              d="M4.7666 1.90137C5.13227 1.90144 5.48571 2.03525 5.75977 2.27734L7.27246 3.61328C7.36379 3.69382 7.48174 3.73828 7.60352 3.73828H12.3994C13.2276 3.73841 13.8993 4.41005 13.8994 5.23828V6.7168C13.8183 6.70327 13.735 6.69436 13.6494 6.69434H12.8994V5.23828C12.8993 4.96233 12.6754 4.73841 12.3994 4.73828H7.60352C7.23781 4.73828 6.88446 4.60438 6.61035 4.3623L5.09766 3.02637C5.00636 2.94576 4.88838 2.90144 4.7666 2.90137H2.0498C1.77366 2.90137 1.5498 3.12523 1.5498 3.40137V9.78223L0.902344 12.2119C0.648452 13.1642 1.36604 14.0986 2.35156 14.0986H2.0498C1.2214 14.0986 0.549838 13.427 0.549805 12.5986V3.40137C0.549805 2.57294 1.22138 1.90137 2.0498 1.90137H4.7666Z"
            />
          </>,
          '0 0 16 16',
        )
      }
      return svg(
        <>
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth={1.25}
            d="M1.50439 3.11059C1.50439 2.55831 1.95211 2.1106 2.50439 2.1106H5.43389C5.67773 2.1106 5.91318 2.19969 6.09593 2.36113L7.71649 3.79265C7.89924 3.95409 8.1347 4.04319 8.3785 4.04319H13.4958C14.0481 4.04319 14.4958 4.4909 14.4958 5.04319V12.8894C14.4958 13.4417 14.0481 13.8894 13.4958 13.8894H2.50439C1.95211 13.8894 1.50439 13.4417 1.50439 12.8894V4.04319V3.11059Z"
          />
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth={1.25}
            d="M3.63501 7.66614H12.3647"
          />
        </>,
        '0 0 16 16',
      )
    case 'git':
      return svg(
        <>
          <circle {...stroke} cx="4" cy="3.6" r="1.3" />
          <circle {...stroke} cx="4" cy="10.4" r="1.3" />
          <circle {...stroke} cx="10" cy="5.4" r="1.3" />
          <path {...stroke} d="M4 4.9v4.2M10 6.7c0 2.2-3.1 1.9-5 2.5" />
        </>,
      )
    case 'markdown':
      return svg(
        <>
          <path {...stroke} d="M3.5 2.5h7v9h-7z" />
          <path {...stroke} d="M5.2 5.1v3.3M4.1 7.3 5.2 8.4l1.1-1.1" />
        </>,
      )
    case 'word':
      return badge('W', size, 7)
    case 'excel':
      return badge('X', size, 7)
    case 'ppt':
      return badge('P', size, 7)
    case 'pdf':
      return badge('PDF', size, 4.4)
    case 'image':
      return svg(
        <>
          <rect {...stroke} x="2" y="3" width="10" height="8" rx="1.4" />
          <circle {...stroke} cx="5" cy="5.6" r=".9" />
          <path {...stroke} d="M2.6 9.8 5.6 7l2 1.8 1.8-1.4 2 2.2" />
        </>,
      )
    case 'video':
      return svg(
        <>
          <rect {...stroke} x="2" y="3" width="10" height="8" rx="1.6" />
          <path {...stroke} d="M6 5.4 9 7 6 8.6V5.4Z" />
        </>,
      )
    default:
      return svg(
        <>
          <path {...stroke} d="M4 2h3l3 3v7H4V2Z" />
          <path {...stroke} d="M7 2v3h3" />
        </>,
      )
  }
}

/** 品牌色方牌 · 代码/配置文件（1~2 字符标记；3 字符如 </> 自动缩小字号）。 */
function tileIcon(tile: CodeTile, size: number) {
  const len = tile.mark.length
  const fontSize = len === 1 ? 7 : len === 2 ? 6.2 : 4.8
  const y = len === 1 ? 9.6 : 9.45
  return (
    <svg
      viewBox="0 0 14 14"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      style={{ display: 'block' }}
    >
      <rect x="1.3" y="1.3" width="11.4" height="11.4" rx="2.6" fill={tile.bg} />
      <text
        x="7"
        y={y}
        textAnchor="middle"
        fontSize={fontSize}
        fontWeight="700"
        fill={tile.fg}
        fontFamily="ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
        letterSpacing="-0.2"
      >
        {tile.mark}
      </text>
    </svg>
  )
}

/** 字标徽章（Word/Excel/PPT/PDF）· 圆角方底 + 字母，和线性图标一眼区分。 */
function badge(text: string, size: number, fontSize: number) {
  return (
    <svg
      viewBox="0 0 14 14"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      style={{ display: 'block' }}
    >
      <rect x="1.4" y="1.4" width="11.2" height="11.2" rx="2.6" fill="currentColor" opacity="0.16" />
      <text
        x="7"
        y={text.length > 1 ? 9.2 : 9.6}
        textAnchor="middle"
        fontSize={fontSize}
        fontWeight="700"
        fill="currentColor"
        fontFamily="ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
      >
        {text}
      </text>
    </svg>
  )
}
