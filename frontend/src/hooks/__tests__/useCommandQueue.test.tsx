// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  isPromptInputModeEditable,
  isQueuedCommandEditable,
  useCommandQueue,
  type PoppedQueueInput,
  type QueuedCommand,
  type UseCommandQueue,
} from '../useCommandQueue'
import type { AttachmentRequest } from '@/api/types'

/**
 * ⭐ 批 A5 前端守门测试：`useCommandQueue.popEditable` 新契约 + 「可编辑」判据单点。
 *
 * <b>缺陷背景</b>：用户按 Esc / 点排队框「编辑」想拉回排队消息时，本仓只把**最旧一条**填回输入框，
 * 其余 N-1 条静默消失、附件全丢；且谓词只判 `mode=prompt`，**cron / channel 等 `isMeta=true` 的
 * 系统项也会被弹出并拼进用户输入框**（原始 XML 灌进用户输入）。
 *
 * <b>鉴别力（为什么会变红）</b>：
 * <ul>
 *   <li>把「可编辑」判据退回 `mode === 'prompt'`（丢掉 `!isMeta`）→
 *       `isQueuedCommandEditable` 用例 + `pop_keepsNonEditableInLocalQueue` 红</li>
 *   <li>前端自作主张只取第一行（或再拼一次 `\n`）→ `pop_returnsBackendJoinedText` 红</li>
 *   <li>丢掉 attachments（回到只回文本）→ `pop_returnsAttachments` 红</li>
 *   <li>本地队列清空整条（而不是只移除可编辑项）→ `pop_keepsNonEditableInLocalQueue` 红</li>
 * </ul>
 */

const popMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('@/api/chat', () => ({
  chatApi: {
    popEditableQueuedCommand: (sessionId: string, currentInput?: string) =>
      popMock.fn(sessionId, currentInput),
  },
}))

const IMG: AttachmentRequest = { type: 'image', filename: 'shot.png', mediaType: 'image/png', base64: 'aGVsbG8=' }
const DOC: AttachmentRequest = { type: 'file', filename: 'report.docx', contentId: '42' }

/** 与 `useChatSocket` 的 `queue.changed` 归一**同一条**判据 —— 手写字面量会让本测试失去鉴别力。 */
function cmd(mode: string, content: string, isMeta = false): QueuedCommand {
  return { content, mode, isMeta, isEditable: isQueuedCommandEditable({ mode, isMeta }) }
}

const SID = 'sess-1'

function Probe({ sink, sid = SID }: { sink: (q: UseCommandQueue) => void; sid?: string }) {
  sink(useCommandQueue(sid))
  return null
}

let container: HTMLDivElement
let root: Root
let latest: UseCommandQueue

function mountHook(sid: string = SID): void {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root.render(<Probe sid={sid} sink={(q) => { latest = q }} />)
  })
}

describe('批 A5 · 可编辑判据（CC messageQueueManager.ts:343-361 的前端孪生）', () => {
  it('task-notification 不可编辑；其余 mode 可编辑', () => {
    expect(isPromptInputModeEditable('task-notification')).toBe(false)
    expect(isPromptInputModeEditable('prompt')).toBe(true)
    expect(isPromptInputModeEditable(undefined)).toBe(true)
  })

  it('⭐ mode=prompt 但 isMeta=true（cron / channel 系统项）不可编辑 —— 附带缺陷的判据本身', () => {
    expect(isQueuedCommandEditable({ mode: 'prompt', isMeta: true })).toBe(false)
    expect(isQueuedCommandEditable({ mode: 'prompt', isMeta: false })).toBe(true)
    expect(isQueuedCommandEditable({ mode: 'task-notification', isMeta: false })).toBe(false)
  })
})

describe('批 A5 · useCommandQueue.popEditable 新契约', () => {
  beforeEach(() => {
    popMock.fn.mockReset()
    mountHook()
  })
  afterEach(() => {
    act(() => root.unmount())
    container.remove()
  })

  it('⭐ 草稿随请求上送 + 后端 join 好的多行文本原样带回（前端不重拼、不截断）', async () => {
    // 逐字模拟后端 `[...queuedTexts, currentInput].filter(Boolean).join('\n')` 的产物
    popMock.fn.mockResolvedValue({ text: '第一条\n第二条\n我的草稿', attachments: [] })

    let res: PoppedQueueInput | null = null
    await act(async () => { res = await latest.popEditable('sess-1', '我的草稿') })

    // 草稿必须上送（省掉它 = 后端按 Esc 静默清掉用户草稿）
    expect(popMock.fn).toHaveBeenCalledWith('sess-1', '我的草稿')
    // 多行文本原样透传 —— 换行保留（前端若只取第一行 / 再次 join，本断言红）
    expect(res!.text).toBe('第一条\n第二条\n我的草稿')
  })

  it('⭐ attachments 随响应回传（图片 base64 + 上传腿 contentId 都在）', async () => {
    popMock.fn.mockResolvedValue({ text: '带图', attachments: [IMG, DOC] })

    let res: PoppedQueueInput | null = null
    await act(async () => { res = await latest.popEditable('sess-1') })

    expect(res!.attachments).toHaveLength(2)
    expect(res!.attachments[0]).toEqual(IMG)
    expect(res!.attachments[1]).toEqual(DOC)
  })

  it('⭐ 本地队列只移除【可编辑项】，cron / task-notification 留在本地队列（CC :480-481 push(...nonEditable)）', async () => {
    popMock.fn.mockResolvedValue({ text: '用户消息', attachments: [] })
    act(() => {
      latest.setQueued(SID, [
        cmd('prompt', '用户消息'),
        cmd('prompt', 'cron-原始命令', true),
        cmd('task-notification', '子agent通知'),
      ])
    })

    await act(async () => { await latest.popEditable('sess-1') })

    expect(latest.queuedCommands.map((c) => c.content)).toEqual(['cron-原始命令', '子agent通知'])
  })

  it('契约不匹配（旧后端只回 {content}）→ 返回 null 而非空串 —— 绝不静默清空输入框', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    popMock.fn.mockResolvedValue({ content: '旧契约' })
    act(() => { latest.setQueued(SID, [cmd('prompt', '用户消息')]) })

    let res: PoppedQueueInput | null = { text: '占位', attachments: [] }
    await act(async () => { res = await latest.popEditable('sess-1') })

    expect(res).toBeNull()
    expect(latest.queuedCommands.map((c) => c.content)).toEqual(['用户消息'])  // 队列不动
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('请求失败 → null + 留痕（不静默）+ 本地队列不动', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    popMock.fn.mockRejectedValue(new Error('Network Error'))
    act(() => { latest.setQueued(SID, [cmd('prompt', '用户消息')]) })

    let res: PoppedQueueInput | null = { text: '占位', attachments: [] }
    await act(async () => { res = await latest.popEditable('sess-1') })

    expect(res).toBeNull()
    expect(latest.queuedCommands.map((c) => c.content)).toEqual(['用户消息'])
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})

describe('[2026-10-23] 排队框按会话隔离（多会话并行时不得串台）', () => {
  beforeEach(() => { popMock.fn.mockReset(); mountHook('sess-A') })
  afterEach(() => { act(() => root.unmount()); container.remove() })

  it('⭐ B 会话的 queue.changed 不得覆盖 A 会话的排队框；切到 B 显示 B 自己的快照', () => {
    act(() => { latest.setQueued('sess-A', [cmd('prompt', 'A 的排队消息')]) })
    expect(latest.queuedCommands.map((c) => c.content), 'A 自己的快照').toEqual(['A 的排队消息'])

    // 另一个会话（正在跑）推送快照 —— 旧实现直接 setQueued(commands) 会把它盖到 A 上
    act(() => { latest.setQueued('sess-B', [cmd('task-notification', 'B 的系统通知', true)]) })
    expect(latest.queuedCommands.map((c) => c.content), 'A 的排队框不得被 B 覆盖')
      .toEqual(['A 的排队消息'])

    // 切到 B → 显示 B 自己的快照（A 的那份仍在，切回还能看到）
    act(() => { root.render(<Probe sid="sess-B" sink={(q) => { latest = q }} />) })
    expect(latest.queuedCommands.map((c) => c.content)).toEqual(['B 的系统通知'])
    act(() => { root.render(<Probe sid="sess-A" sink={(q) => { latest = q }} />) })
    expect(latest.queuedCommands.map((c) => c.content)).toEqual(['A 的排队消息'])
  })

  it('来源会话为空 → 丢弃（不猜测归属、不落到当前会话）', () => {
    act(() => { latest.setQueued('', [cmd('prompt', '无主快照')]) })
    expect(latest.queuedCommands).toHaveLength(0)
  })
})
