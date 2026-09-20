import { useCallback, useState } from 'react'
import { chatApi } from '../api/chat'
import type { AttachmentRequest } from '../api/types'

/**
 * F19 排队命令状态机（#3 · 纯前端 UI 骨架，数据等后端 B4/B5）。
 *
 * <p>对齐 CC messageQueueManager.ts（isQueuedCommandEditable/Visible/popAllEditable）。
 * <p><b>优雅降级</b>：后端 B5（队列出站事件）未接 → 本地 queuedCommands 恒空，
 *   排队条隐藏、不报错；后端 B4（pop 端点）未接 → popEditable 失败显式提示（console.warn）。
 *   后端两通道一接即自动生效。
 */

/**
 * 不可拉回编辑的 mode 集合 —— CC
 * `NON_EDITABLE_MODES = new Set<PromptInputMode>(['task-notification'])`
 * （utils/messageQueueManager.ts:343-345）。
 *
 * ⛔ **单一来源**：本判据与后端 `NotificationQueue.isPromptInputModeEditable` 是同一套语义的
 * 两侧实现（web 无法共享代码）；后端取值域当前只有 `prompt` / `task-notification`
 * （见 `NotificationQueue` 的 8 处生产构造点）。两处若各写一份迟早漂移 ⇒ 前端所有「可编辑」
 * 判断（本 hook 的本地队列过滤、`useChatSocket` 的 `queue.changed` 归一）都必须走下面两个函数。
 */
export const NON_EDITABLE_MODES: ReadonlySet<string> = new Set(['task-notification'])

/** CC `isPromptInputModeEditable(mode) = !NON_EDITABLE_MODES.has(mode)`（:347-351）。 */
export function isPromptInputModeEditable(mode?: string): boolean {
  return !NON_EDITABLE_MODES.has(String(mode ?? ''))
}

/**
 * 该排队命令能否被「拉回输入框编辑」—— CC
 * `isQueuedCommandEditable(cmd) = isPromptInputModeEditable(cmd.mode) && !cmd.isMeta`（:359-361）。
 *
 * <p><b>WHY 必须同时判 isMeta</b>：本仓 `mode='prompt'` 但 `isMeta=true` 的有三类系统项 ——
 * cron 命令（`TestJob:373`）、cron missed 启动通知（`CronIdleExecutor:235`）、外部 channel 入站消息
 * （`ChannelNotification:154`，正文是原始 XML）。它们不是用户在输入框里敲的字，一旦被拼进输入框，
 * 用户就会「拉回」一段自己从未写过的系统文本（CC 同文件 :353-357 注释即为此）。
 */
export function isQueuedCommandEditable(cmd: { mode?: string; isMeta?: boolean }): boolean {
  return isPromptInputModeEditable(cmd.mode) && !cmd.isMeta
}

export interface QueuedCommand {
  /** 排队命令内容（prompt/bash 文本） */
  content: string
  /** 模式：'prompt' | 'task-notification' | ...（可编辑判定用，对齐 CC messageQueueManager.ts:359-361） */
  mode: 'prompt' | 'bash' | string
  /** 是否可编辑（mode 可编辑 && !isMeta）· 由 {@link isQueuedCommandEditable} 单点判定 */
  isEditable: boolean
  /** 是否系统生成（cron/channel 等 · 可见但不可编辑） */
  isMeta?: boolean
}

/** `popEditable` 结果（批 A5 新契约）= 后端 `QueuePopResponse`。
 *  - `text`：全部可编辑排队项 + 草稿，`\n` join → 填输入框；
 *  - `attachments`：弹出项附件 → 还原为待发 chip（图片连 base64 一起还给用户）。 */
export type PoppedQueueInput = {
  text: string
  attachments: AttachmentRequest[]
}

export interface UseCommandQueue {
  /** 排队命令列表（后端 B5 未接时恒空） */
  queuedCommands: QueuedCommand[]
  /** 是否有可编辑排队命令（对齐 CC hasEditableCommand） */
  hasEditable: boolean
  /**
   * 弹出全部可编辑排队命令（对齐 CC popAllEditable）。
   *
   * @param sessionId    目标会话
   * @param currentInput 输入框当前草稿（CC `popAllEditable` 的 currentInput 实参，参与 `\n` join）
   * @return `{text, attachments}`；失败/无响应 → null（调用方保持现状，不放任输入框被清空）
   */
  popEditable: (sessionId: string, currentInput?: string) => Promise<PoppedQueueInput | null>
  /** 预留：后端 B5 队列出站事件到达时写入本地（订阅通道接好后调用） */
  setQueued: (cmds: QueuedCommand[]) => void
  /** 清空（会话切换/断连） */
  clear: () => void
}

export function useCommandQueue(): UseCommandQueue {
  const [queuedCommands, setQueuedCommands] = useState<QueuedCommand[]>([])
  const hasEditable = queuedCommands.some((c) => c.isEditable)

  const popEditable = useCallback(async (sessionId: string, currentInput?: string): Promise<PoppedQueueInput | null> => {
    try {
      const res = await chatApi.popEditableQueuedCommand(sessionId, currentInput)
      if (!res) return null
      // 契约守卫（批 A5 换形状：旧 `{content}` → 新 `{text, attachments}`）：后端仍是旧版时
      //   `res.text` 为 undefined，若放行到 App 会 `setComposerText('')` **静默清空用户草稿** —— 即
      //   把「N-1 条排队消息消失」换成「草稿消失」。故显式拒绝并留痕（fail loud，不降级为该副作用）。
      if (typeof res.text !== 'string') {
        console.warn('[queue] /queue/pop 响应缺 text 字段（契约不匹配 · 前端期望 {text, attachments}）：', res)
        return null
      }
      // 弹出后从本地队列移除【可编辑项】，不可编辑项留在本地队列
      //   （对齐 CC :480-481 `commandQueue.push(...nonEditable)` —— 不是清空整条队列）。
      //   ⛔ 判据与后端谓词同源：`isQueuedCommandEditable`（若这里写 `!c.isEditable` 也等价，
      //   但两处判据会漂移 —— 本 hook 的 isEditable 字段即由该函数生成，见 useChatSocket）。
      setQueuedCommands((prev) => prev.filter((c) => !isQueuedCommandEditable(c)))
      return { text: res.text ?? '', attachments: res.attachments ?? [] }
    } catch (err) {
      // 优雅降级但**不静默**：后端 B4 未接 / 网络失败 → 输入框保持原样（绝不半途清空），
      //   同时留痕（原实现 `catch { return null }` 连一行日志都没有 ⇒ 现场无从判断「按了 Esc 没反应」的原因）
      console.warn('[queue] 拉回排队命令失败（输入框保持原样）:', err)
      return null
    }
  }, [])

  const setQueued = useCallback((cmds: QueuedCommand[]) => setQueuedCommands(cmds), [])
  const clear = useCallback(() => setQueuedCommands([]), [])

  return { queuedCommands, hasEditable, popEditable, setQueued, clear }
}
