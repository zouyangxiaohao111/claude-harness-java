import type { QueuedCommand } from '@/hooks/useCommandQueue'

/**
 * F19 排队命令条（#3 · 对齐 CC PromptInputQueuedCommands.tsx）· 输入框上方展示排队命令。
 *
 * <p>[goal V253 形态重做 · 2026-11-02] 用户指出「现在的排队消息有点丑」。**真根因不是配色，
 * 是几何错位**：本组件原先挂在 `.composer-inner` 的<b>兄弟</b>位置 ⇒ 通栏占 32px..W-32px；
 * 而输入框在 `.composer-inner` 内（`margin-left:42px` + `max-width:85%`）⇒ 1200px 列下
 * <b>左缘差 42px、右缘差 128px</b>。一条满宽白底带 `border-bottom` 的横条压在缩进 85% 的
 * 圆角输入框上方 ⇒ 怎么调颜色都不会好看。
 *
 * <p>⇒ 修正 = <b>把本组件移进 `.composer-inner`</b>（Composer.tsx）+ 样式重做为独立圆角卡。
 * 移进去后还顺带吃上同一套 620px 容器查询断点。
 *
 * <p>形态分层（用户裁定「这个方向可以」）：告警横幅不动 · 排队 = 独立托盘 · 目标 = 输入框的头。
 *
 * <p>每个条目显示序号（发送有顺序）+ 内容摘要；系统项（不可拉回）显式标记「系统」+ 锁图标；
 * 最多 3 行 + 「还有 N 条…」（长队列不吞屏幕）；单条压成一行不摆 header。
 */
interface QueuedCommandsBarProps {
  queuedCommands: QueuedCommand[]
  onEdit: () => void
}

/** 最多直接展示的行数（其余折进「还有 N 条…」）。 */
const MAX_ROWS = 3

const ClockSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" width="12" height="12">
    <circle cx="7" cy="7" r="5" />
    <path d="M7 4.2V7l2 1.3" strokeLinecap="round" />
  </svg>
)

const PullSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6" width="11" height="11">
    <path d="M11 7H3M6 4L3 7l3 3" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

const LockSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" width="10" height="10">
    <rect x="3.2" y="6.2" width="7.6" height="5.4" rx="1.3" />
    <path d="M5.2 6.2V4.8a1.8 1.8 0 0 1 3.6 0v1.4" />
  </svg>
)

const MoreSvg = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.6" width="10" height="10">
    <path d="M7 10.5V3.5M3.5 7L7 3.5 10.5 7" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

export function QueuedCommandsBar({ queuedCommands, onEdit }: QueuedCommandsBarProps) {
  // 无排队命令 → 不渲染（空态隐藏，优雅降级）
  if (queuedCommands.length === 0) return null

  const hasEditable = queuedCommands.some((c) => c.isEditable)
  const visible = queuedCommands.slice(0, MAX_ROWS)
  const overflow = queuedCommands.length - visible.length
  const single = queuedCommands.length === 1

  const rows = visible.map((c, i) => (
    <div key={i} className={`qt-row${c.isEditable ? '' : ' system'}`} title={c.content}>
      <span className="qt-idx">{i + 1}</span>
      <span className="qt-text">
        {c.mode === 'bash' && <span className="bash">$</span>}
        {c.content}
      </span>
      {!c.isEditable && (
        <>
          <span className="qt-tag">系统</span>
          <span className="qt-lock" title="系统生成 · 不可拉回编辑">
            <LockSvg />
          </span>
        </>
      )}
    </div>
  ))

  // 单条 → 压成一行（不摆 header）
  if (single) {
    return (
      <div className="qtray single">
        <span className="qt-idx">1</span>
        <span className="qt-text" title={queuedCommands[0].content}>
          {queuedCommands[0].mode === 'bash' && <span className="bash">$</span>}
          {queuedCommands[0].content}
        </span>
        <span className="qt-hint">本轮结束后发送</span>
        {hasEditable && (
          <button className="qt-pull" onClick={onEdit} title="拉回排队命令到输入框">
            <PullSvg />
            <span>拉回</span>
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="qtray">
      <div className="qt-head">
        <span className="qt-ico">
          <ClockSvg />
        </span>
        <span className="qt-count">{queuedCommands.length} 条排队中</span>
        <span className="qt-hint">本轮结束后按顺序发送</span>
        {hasEditable && (
          <button className="qt-pull" onClick={onEdit} title="拉回排队命令到输入框">
            <PullSvg />
            <span>拉回编辑</span>
          </button>
        )}
      </div>
      <div className="qt-rows">{rows}</div>
      {overflow > 0 && (
        <div className="qt-more">
          <MoreSvg />
          还有 {overflow} 条…
        </div>
      )}
    </div>
  )
}
