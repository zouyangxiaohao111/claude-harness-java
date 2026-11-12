import { useMemo } from 'react'
import { useSubagentStore, type SubagentIdentity } from '@/stores/subagentStore'

/**
 * 「子代理运行中」悬浮胶囊（输入框上方右缘，与「停止所有」并排）。
 *
 * <h2>WHY</h2>
 * 子代理走独立 AutonomousAgentLoop，主会话对话流里看不到它在跑 —— 此前只有左栏一个状态点。
 * 用户在主区工作时完全不知道后台还有子代理 ⇒ 这里给一个常驻胶囊，点开直达右栏「运行」tab
 * （{@code runSection} 第一块就是子代理列表）。
 *
 * <h2>计数口径</h2>
 * 与右栏「运行」徽标（{@code RightPanel.runActiveCount}）同源：{@code bySession[sessionId]}
 * 里 {@code status === 'running'}，按 {@code taskId ?? name} 去重（store 用 tool_use_id 与
 * task_id 双键指向同一对象，不去重会重复计数）。
 */

/** 稳定空 map（selector 兜底 · 避免每次新对象触发 zustand 无限重渲染） */
const EMPTY_IDENTITY_MAP: Record<string, SubagentIdentity> = {}

export function SubagentRunningHint({
  sessionId,
  onOpen,
}: {
  /** 当前会话 id（空串 = 未进入会话，不渲染） */
  sessionId: string
  /** 点击 → 打开右栏并切到「运行」tab */
  onOpen: () => void
}) {
  const identities = useSubagentStore((s) => s.bySession[sessionId] ?? EMPTY_IDENTITY_MAP)

  const runningCount = useMemo(() => {
    const seen = new Set<string>()
    for (const id of Object.values(identities)) {
      if (id.status !== 'running') continue
      seen.add(id.taskId ?? id.name)
    }
    return seen.size
  }, [identities])

  if (!sessionId) return null

  const hasRunning = runningCount > 0

  // 常驻入口：无运行中的任务时灰显「运行」（用户随时知道入口在哪、不会以为功能没了）；
  // 有子代理在跑时高亮 + 计数 + 脉冲点。
  return (
    <button
      type="button"
      className={`subagent-running-hint${hasRunning ? '' : ' idle'}`}
      onClick={onOpen}
      title={hasRunning
        ? `${runningCount} 个子代理在后台运行 · 点开查看执行详情`
        : '查看运行中的任务与子代理'}
      aria-label={hasRunning ? `${runningCount} 个子代理运行中，点开查看` : '打开运行面板'}
    >
      <span className={`srh-dot${hasRunning ? '' : ' idle'}`} aria-hidden="true" />
      <span>{hasRunning ? '子代理运行中' : '运行'}</span>
      {hasRunning && <span className="srh-count">{runningCount}</span>}
      <span className="srh-arrow" aria-hidden="true">›</span>
    </button>
  )
}
