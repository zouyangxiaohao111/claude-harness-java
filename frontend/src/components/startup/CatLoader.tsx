import { CatArtBody, CatTail } from './CatArt'

/**
 * CatLoader — 启动画面内容（暖米白背景 + 居中睡猫场景）
 *
 * 视觉基准：启动动画预览-v7.html 的 .splash/.catContainer/.wall 布局，
 * 样式统一收敛到 globals.css 的 cl-* 前缀（避免与业务样式冲突）。
 * 窗口放大（expandFromSplash）期间本组件保持挂载，放大完成后由 LaunchGate 切主界面。
 */
export function CatLoader() {
  return (
    <div className="cl-stage" role="status" aria-live="polite" aria-label="正在唤醒本地引擎">
      <div className="cl-scene">
        <div className="cl-catWrap">
          {/* 尾巴先画 → 被猫 body 遮住连接处，视觉上从猫身后探出 */}
          <CatTail className="cl-tail" aria-hidden="true" />
          <CatArtBody className="cl-catBody" aria-hidden="true" />
          <span className="cl-zzz" aria-hidden="true">
            <span className="cl-z1">z</span>
            <span className="cl-z2">z</span>
          </span>
        </div>

        {/* 4 条横线（去竖线）模拟墙 · 浅暖灰 */}
        <svg className="cl-wall" viewBox="0 0 500 126" aria-hidden="true">
          <line stroke="#D8CDBA" strokeWidth="7" x1="50" y1="3" x2="450" y2="3" />
          <line stroke="#D8CDBA" strokeWidth="7" x1="100" y1="85" x2="400" y2="85" />
          <line stroke="#D8CDBA" strokeWidth="7" x1="125" y1="122" x2="375" y2="122" />
          <line stroke="#D8CDBA" strokeWidth="7" x1="0" y1="43" x2="500" y2="43" />
        </svg>

        <div className="cl-boot">
          <span className="cl-bootText">正在唤醒本地引擎…</span>
          <span className="cl-caret" aria-hidden="true" />
        </div>
      </div>
    </div>
  )
}
