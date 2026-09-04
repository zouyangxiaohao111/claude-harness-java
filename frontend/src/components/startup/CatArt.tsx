/**
 * 启动体验 · 共享猫素材单一来源（loader 与后续 welcome 替球共用）
 *
 * 形状/path 取自本地动画参考 D:/code/ai_project/nexusai-backend/启动动画预览-v7.html：
 *  - 橙猫 body（双耳圆身）viewBox "0 0 733 673"
 *  - 独立尾巴 viewBox "0 0 158 564"
 * 颜色、path 保持与 v7 一致，避免两处样式漂移。
 */

import type { SVGProps } from 'react'

/** 橙猫主色（v7 --cat1） */
export const CAT_BODY_COLOR = '#FF7A3D'

/** 尾巴深橙（v7 --cat2） */
export const CAT_TAIL_COLOR = '#E65C00'

/** 橙猫 body（含双耳圆身）· 尺寸交给 className 控制（loader 用宽 ~74px） */
export function CatArtBody(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 733 673" {...props}>
      <path
        fill={CAT_BODY_COLOR}
        d="M111.002 139.5C270.502 -24.5001 471.503 2.4997 621.002 139.5C770.501 276.5 768.504 627.5 621.002 649.5C473.5 671.5 246 687.5 111.002 649.5C-23.9964 611.5 -48.4982 303.5 111.002 139.5Z"
      />
      <path fill={CAT_BODY_COLOR} d="M184 9L270.603 159H97.3975L184 9Z" />
      <path fill={CAT_BODY_COLOR} d="M541 0L627.603 150H454.397L541 0Z" />
    </svg>
  )
}

/** 独立猫尾巴（从猫身后探出摆动）· 宽高交给 className 控制 */
export function CatTail(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 158 564" {...props}>
      <path
        fill={CAT_TAIL_COLOR}
        d="M5.97602 76.066C-11.1099 41.6747 12.9018 0 51.3036 0V0C71.5336 0 89.8636 12.2558 97.2565 31.0866C173.697 225.792 180.478 345.852 97.0691 536.666C89.7636 553.378 73.0672 564 54.8273 564V564C16.9427 564 -5.4224 521.149 13.0712 488.085C90.2225 350.15 87.9612 241.089 5.97602 76.066Z"
      />
    </svg>
  )
}
