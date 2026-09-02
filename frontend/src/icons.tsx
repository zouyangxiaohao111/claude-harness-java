/**
 * Single source of truth for all inline SVG icons used across the app.
 * Every consumer (MenuBar, SessionList, etc.) imports Icon by name — no inline <svg> allowed.
 *
 * Each icon is a tiny React component accepting an optional `size` (defaults to its
 * native viewBox size) and an optional `className` to merge with `svg-icon`.
 */

import type { CSSProperties } from 'react'

interface IconProps {
  size?: number
  className?: string
  style?: CSSProperties
}

const baseProps = (size: number, className?: string, style?: CSSProperties) => ({
  width: size,
  height: size,
  viewBox: '0 0 14 14',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.5,
  className: className ? `svg-icon ${className}` : 'svg-icon',
  style,
})

function makeIcon(
  children: React.ReactNode,
  defaultSize: number,
  defaultViewBox = '0 0 14 14',
) {
  const Comp = ({ size, className, style }: IconProps) => {
    const finalSize = size ?? defaultSize
    return (
      <svg
        width={finalSize}
        height={finalSize}
        viewBox={defaultViewBox}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        className={className ? `svg-icon ${className}` : 'svg-icon'}
        style={style}
      >
        {children}
      </svg>
    )
  }
  return Comp
}

export const Icon = {
  /* 12x12 / 14x14 / 16x16 sizes preserved exactly from the original inline SVGs */

  Folder: makeIcon(
    <path d="M2 4.5C2 3.5 2.5 3 3.5 3H5L6 4.5H10.5C11.5 4.5 12 5 12 6V10C12 11 11.5 11.5 10.5 11.5H3.5C2.5 11.5 2 11 2 10V4.5Z" />,
    14,
  ),

  /** Small X — used by tab close and add-panel close. */
  Close: makeIcon(<path d="M3 3L9 9M9 3L3 9" />, 12, '0 0 12 12'),

  /** Large X — used by diff / settings / context menu. */
  CloseLg: makeIcon(<path d="M3 3L11 11M11 3L3 11" />, 14),

  Plus: makeIcon(<path d="M6 2V10M2 6H10" />, 12, '0 0 12 12'),
  PlusLg: makeIcon(<path d="M7 2V12M2 7H12" />, 14),

  ChevronDown: makeIcon(<path d="M3 4.5L6 7.5L9 4.5" />, 12, '0 0 12 12'),
  ChevronRight: makeIcon(<path d="M3 4L5 6.5L7 4" />, 10, '0 0 10 10'),

  /** 16x16 magnifier — top-bar search trigger. */
  Search: makeIcon(
    <>
      <circle cx="7" cy="7" r="4.5" />
      <path d="M11 11L14 14" />
    </>,
    16,
    '0 0 16 16',
  ),

  /** 14x14 magnifier — used inside the search input box. */
  SearchSm: makeIcon(
    <>
      <circle cx="6" cy="6" r="4" />
      <path d="M10 10L13 13" />
    </>,
    14,
  ),

  Gear: makeIcon(
    <>
      <circle cx="8" cy="8" r="2" />
      <path d="M8 1V3M8 13V15M1 8H3M13 8H15M3 3L4.5 4.5M11.5 11.5L13 13M3 13L4.5 11.5M11.5 4.5L13 3" />
    </>,
    16,
    '0 0 16 16',
  ),

  /** 12x12 check — model-dropdown "current" indicator. */
  Check: makeIcon(<path d="M2 6L5 9L10 3" />, 12, '0 0 12 12'),

  /** 14x14 check inside circle — toast success. */
  CheckBadge: makeIcon(
    <>
      <circle cx="7" cy="7" r="6" />
      <path d="M4.5 7L6.5 9L9.5 5" />
    </>,
    14,
  ),

  /** 10x10 check — tool-card done status. */
  CheckSm: makeIcon(<path d="M2 5L4 7L8 3" />, 10, '0 0 10 10'),

  /** 14x14 file/document with folded corner. */
  Doc: makeIcon(
    <>
      <path d="M2 2H8L12 6V12H2Z" />
      <path d="M8 2V6H12" />
    </>,
    14,
  ),

  /** 14x14 plus inside a circle — "new file" badge. */
  PlusCircle: makeIcon(
    <>
      <circle cx="7" cy="7" r="5" />
      <path d="M7 4V10M4 7H10" />
    </>,
    14,
  ),

  Send: makeIcon(<path d="M3 6H9M9 6L6 3M9 6L6 9" />, 12, '0 0 12 12'),

  /** 14x14 list-lines — tool-card header. */
  ListLines: makeIcon(
    <>
      <path d="M2 3L12 3M2 7L12 7M2 11L8 11" />
    </>,
    14,
  ),

  /** 12x12 plan-mode: target dot with cardinal cross. */
  PlanMode: makeIcon(
    <>
      <circle cx="6" cy="6" r="2" />
      <path d="M6 1V3M6 9V11M1 6H3M9 6H11" />
    </>,
    12,
    '0 0 12 12',
  ),

  /** 14x14 clock — reasoning "正在思考" header. */
  Clock: makeIcon(
    <>
      <circle cx="7" cy="7" r="5" />
      <path d="M7 4V7L9 8.5" />
    </>,
    14,
  ),

  /** 14x14 sun — settings appearance nav icon. */
  Sun: makeIcon(
    <>
      <path d="M7 1C3 1 1 3 1 7s2 6 6 6c4 0 6-2 6-6s-2-6-6-6z" />
      <path d="M7 1v12M1 7h12" />
    </>,
    14,
  ),

  /** 14x14 general-settings: target with cardinal cross. */
  GeneralSettings: makeIcon(
    <>
      <circle cx="7" cy="7" r="2.5" />
      <path d="M7 1V3M7 11V13M1 7H3M11 7H13" />
    </>,
    14,
  ),

  /** 14x14 advanced-settings: target with cardinal cross. */
  AdvancedSettings: makeIcon(
    <>
      <circle cx="7" cy="7" r="2" />
      <path d="M7 1V3M7 11V13M1 7H3M11 7H13" />
    </>,
    14,
  ),

  /** 14x14 model-list settings nav icon. */
  ModelList: makeIcon(
    <>
      <rect x="1" y="2" width="12" height="10" rx="1" />
      <path d="M4 5h6M4 7h6M4 9h3" />
    </>,
    14,
  ),

  /** 12x12 two vertical bars — diff side-by-side toggle. */
  Bars: makeIcon(<path d="M1 1H5V11H1ZM7 1H11V11H7Z" />, 12, '0 0 12 12'),

  /** 12x12 square — diff unified toggle. */
  Box: makeIcon(<path d="M1 1H11V11H1Z" />, 12, '0 0 12 12'),

  /** 14x14 arrow-up — context menu "promote to main". */
  ArrowUp: makeIcon(<path d="M7 2L7 12M4 5L7 2L10 5" />, 14),

  /** 14x14 cross — context menu "open in new tab". */
  CrossSquare: makeIcon(<path d="M2 7H12M7 2V12" />, 14),

  /** 14x14 copy — context menu "copy path". */
  Copy: makeIcon(
    <>
      <rect x="2" y="2" width="10" height="10" rx="1" />
      <path d="M5 7H9" />
    </>,
    14,
  ),

  /** 14x14 finder — context menu "reveal in finder". */
  Finder: makeIcon(
    <>
      <rect x="2" y="2" width="10" height="10" rx="1" />
      <path d="M4 7H10M7 4V10" />
    </>,
    14,
  ),

  /** Convenience pass-through for callers that want raw size override. */
  Raw: baseProps,
}

export type IconName = keyof typeof Icon
