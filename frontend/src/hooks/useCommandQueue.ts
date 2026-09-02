import { useCallback, useState } from 'react'
import { chatApi } from '../api/chat'

/**
 * F19 排队命令状态机（#3 · 纯前端 UI 骨架，数据等后端 B4/B5）。
 *
 * <p>对齐 CC messageQueueManager.ts（isQueuedCommandEditable/Visible/popAllEditable）。
 * <p><b>优雅降级</b>：后端 B5（队列出站事件）未接 → 本地 queuedCommands 恒空，
 *   排队条隐藏、不报错；后端 B4（pop 端点）未接 → popEditable 失败显式提示。
 *   后端两通道一接即自动生效。
 */
export interface QueuedCommand {
  /** 排队命令内容（prompt/bash 文本） */
  content: string
  /** 模式：'prompt' | 'bash'（可编辑判定用，对齐 CC messageQueueManager.ts:359-369） */
  mode: 'prompt' | 'bash' | string
  /** 是否可编辑（mode 可编辑 && !isMeta） */
  isEditable: boolean
  /** 是否仅可见不可编辑（channel 消息等） */
  isMeta?: boolean
}

export interface UseCommandQueue {
  /** 排队命令列表（后端 B5 未接时恒空） */
  queuedCommands: QueuedCommand[]
  /** 是否有可编辑排队命令（对齐 CC hasEditableCommand） */
  hasEditable: boolean
  /** 弹出所有可编辑排队命令并填入输入框（对齐 CC popAllEditable） */
  popEditable: (sessionId: string) => Promise<string | null>
  /** 预留：后端 B5 队列出站事件到达时写入本地（订阅通道接好后调用） */
  setQueued: (cmds: QueuedCommand[]) => void
  /** 清空（会话切换/断连） */
  clear: () => void
}

export function useCommandQueue(): UseCommandQueue {
  const [queuedCommands, setQueuedCommands] = useState<QueuedCommand[]>([])
  const hasEditable = queuedCommands.some((c) => c.isEditable)

  const popEditable = useCallback(async (sessionId: string): Promise<string | null> => {
    // 后端 B4 未接时 popEditableQueuedCommand 失败 → 显式提示（优雅降级，非静默）
    try {
      const res = await chatApi.popEditableQueuedCommand(sessionId)
      if (!res || !res.content) return null
      // 弹出后从本地队列移除所有可编辑项（对齐 CC popAllEditable 语义）
      setQueuedCommands((prev) => prev.filter((c) => !c.isEditable))
      return res.content
    } catch {
      return null
    }
  }, [])

  const setQueued = useCallback((cmds: QueuedCommand[]) => setQueuedCommands(cmds), [])
  const clear = useCallback(() => setQueuedCommands([]), [])

  return { queuedCommands, hasEditable, popEditable, setQueued, clear }
}
