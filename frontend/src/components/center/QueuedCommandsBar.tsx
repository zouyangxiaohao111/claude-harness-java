import type { QueuedCommand } from '@/hooks/useCommandQueue'

/**
 * F19 排队命令条（#3 · 对齐 CC PromptInputQueuedCommands.tsx）· 输入框上方展示排队命令。
 * <p>每条显示命令摘要 + 「编辑」按钮（popEditable → 填入输入框）；无排队命令 → 空态隐藏。
 * <p>优雅降级：后端 B5 未接时 queuedCommands 恒空，本组件不渲染（不报错）。
 */
interface QueuedCommandsBarProps {
  queuedCommands: QueuedCommand[]
  onEdit: () => void
}

const EditSvg = () => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '10px', height: '10px' }}>
    <path d="M8 2L10 4L4 10H2V8L8 2Z" />
  </svg>
)

export function QueuedCommandsBar({ queuedCommands, onEdit }: QueuedCommandsBarProps) {
  // 无排队命令 → 不渲染（空态隐藏，优雅降级）
  if (queuedCommands.length === 0) return null
  const hasEditable = queuedCommands.some((c) => c.isEditable)
  return (
    <div className="queued-commands-bar">
      {queuedCommands.map((c, i) => (
        <span key={i} className="queued-cmd" title={c.content}>
          {c.mode === 'bash' ? '$ ' : ''}{c.content}
        </span>
      ))}
      {hasEditable && (
        <button className="tool-chip" onClick={onEdit} title="编辑排队命令" style={{ marginLeft: 'auto' }}>
          <EditSvg />编辑
        </button>
      )}
    </div>
  )
}
