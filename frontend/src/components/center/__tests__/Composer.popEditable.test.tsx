// @vitest-environment jsdom
import { act, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import type { PoppedQueueInput, QueuedCommand } from '@/hooks/useCommandQueue'
import type { AttachmentRequest } from '@/api/types'

/**
 * ⭐ 批 A5 接线守门测试：按 Esc / 点排队条「编辑」→ **全部**可编辑排队项回到输入框 + **附件一起还给你**。
 *
 * <b>缺陷背景</b>：本仓 `/queue/pop` 旧契约只有一个 `content` 槽位 ⇒ 后端「移除全部可编辑项、只回最旧
 * 一条」，另 N-1 条<b>不回填、不落库、不重投、不报错</b>；图片也因为没有承载位置而全丢。
 *
 * <b>WHY 必须真的渲染一次 Composer</b>：本批修的缺陷不在纯函数里，而在**接线**上 ——
 * 「谁把 `text` 写进输入框」「谁把 `attachments` 变回 chip」都只在组件里成立。
 *
 * <b>手法</b>：沿用本仓既有 jsdom 真实渲染模式（`Composer.attachmentEntry.test.tsx`）：`createRoot` +
 * `act`，不引入任何新依赖。
 *
 * <b>Host 组件的角色</b>：`Composer` 是受控组件（`composerText` 由 `App` 持有），
 * 故这里用一个最小 Host **逐字复刻 `App.tsx` 的 popEditable 调用点**
 * （`const res = await popEditable(); if (res) setComposerText(res.text); return res`）——
 * 于是「多行文本进输入框」这件事在本测试里是被真正断言的（textarea.value），而不是被假设的。
 */

const tauriMock = vi.hoisted(() => ({ isTauri: () => false }))
vi.mock('@tauri-apps/api/core', () => ({ isTauri: tauriMock.isTauri, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn(), chatApi: {} }))

async function flush(): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
}

const IMG: AttachmentRequest = { type: 'image', filename: 'shot.png', mediaType: 'image/png', base64: 'aGVsbG8=' }
const DOC: AttachmentRequest = { type: 'file', filename: 'report.docx', contentId: '42', mediaType: '' }

/** 排队命令（含 1 条不可编辑的 cron 系统项 —— 它绝不能被拼进输入框）。 */
const QUEUED: QueuedCommand[] = [
  { content: '第一条消息', mode: 'prompt', isEditable: true },
  { content: 'cron-原始命令<xml/>', mode: 'prompt', isEditable: false, isMeta: true },
  { content: '第二条消息', mode: 'prompt', isEditable: true },
]

interface Harness {
  container: HTMLDivElement
  root: Root
  textarea: () => HTMLTextAreaElement
  chipFilenames: () => string[]
  chipCount: () => number
  pressEscape: () => void
  clickEdit: () => void
  popEditable: ReturnType<typeof vi.fn>
  unmount: () => void
}

function mountHost(): Harness {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  const popEditable = vi.fn(async (): Promise<PoppedQueueInput | null> => ({ text: '', attachments: [] }))

  // 逐字复刻 App.tsx 的 popEditable 调用点（App 持 composerText，Composer 持 attachments）
  function Host() {
    const [text, setText] = useState('')
    return (
      <Composer
        composerText={text}
        setComposerText={setText}
        sendMessage={() => {}}
        showToast={() => {}}
        streaming={false}
        onStop={() => {}}
        queuedCommands={QUEUED}
        popEditable={async () => {
          const res = await popEditable()
          if (res) setText(res.text)
          return res
        }}
        boundProjectName={null}
        onSelectProject={() => {}}
        currentModel="ds-openai/deepseek-v4-flash"
        permissionMode="default"
        empty={false}
        sessionId="sess-1"
        localRead={false}
      />
    )
  }
  act(() => { root.render(<Host />) })

  return {
    container,
    root,
    textarea: () => container.querySelector('textarea') as HTMLTextAreaElement,
    chipCount: () => container.querySelectorAll('.attach-item').length,
    chipFilenames: () => Array.from(container.querySelectorAll('.attach-item .attach-file')).map((e) => e.textContent ?? ''),
    pressEscape: () => {
      act(() => {
        dispatchEscape(container)
      })
    },
    clickEdit: () => {
      const btn = Array.from(container.querySelectorAll('button')).find((b) => (b.textContent ?? '').includes('编辑'))
      if (!btn) throw new Error('排队条「编辑」按钮不存在')
      act(() => btn.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    },
    popEditable,
    unmount: () => act(() => root.unmount()),
  }
}

function dispatchEscape(container: HTMLElement): void {
  const ta = container.querySelector('textarea') as HTMLTextAreaElement
  ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
}

describe('批 A5 · Esc / 「编辑」拉回排队消息：全部文本 + 附件一起还', () => {
  let h: Harness

  beforeEach(() => { h = mountHost() })
  afterEach(() => {
    h.unmount()
    h.container.remove()
  })

  it('⭐ Esc：后端 join 好的多行文本进输入框 + 图片/文件 chip 一起还原', async () => {
    h.popEditable.mockResolvedValue({
      text: '第一条消息\n第二条消息',
      attachments: [IMG, DOC],
    })

    expect(h.chipCount()).toBe(0)
    expect(h.textarea().value).toBe('')

    h.pressEscape()
    await flush()

    expect(h.popEditable).toHaveBeenCalledTimes(1)
    // ① 多行文本进输入框（逐字 = 后端 `[...queuedTexts, currentInput].join('\n')` 的产物，
    //    前端不重拼、不截断；不可编辑的 cron 项文本一个字都不许出现）
    expect(h.textarea().value).toBe('第一条消息\n第二条消息')
    // ② 附件一起还：两个 chip 都回来了
    expect(h.chipCount()).toBe(2)
    // ③ 图片是图片（预览走 dataURL），文件是文件（显示文件名）
    const img = h.container.querySelector('.attach-item img') as HTMLImageElement | null
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,aGVsbG8=')
    expect(h.chipFilenames()).toEqual(['report.docx'])
  })

  it('⭐ 「编辑」按钮与 Esc 同一个入口（排队条按钮也还原附件）', async () => {
    h.popEditable.mockResolvedValue({ text: '第一条消息\n第二条消息', attachments: [IMG] })

    h.clickEdit()
    await flush()

    expect(h.popEditable).toHaveBeenCalledTimes(1)
    expect(h.textarea().value).toBe('第一条消息\n第二条消息')
    expect(h.chipCount()).toBe(1)
  })

  it('还原的 chip 可正常移除（去重键与移除点配对），移除后不影响输入框文本', async () => {
    h.popEditable.mockResolvedValue({ text: '第一条消息\n第二条消息', attachments: [IMG, DOC] })
    h.pressEscape()
    await flush()
    expect(h.chipCount()).toBe(2)

    const remove = h.container.querySelector('.attach-remove') as HTMLButtonElement
    act(() => remove.dispatchEvent(new MouseEvent('click', { bubbles: true })))

    expect(h.chipCount()).toBe(1)
    expect(h.textarea().value).toBe('第一条消息\n第二条消息')
  })

  it('无可拉回项（popEditable 返回 null）→ 输入框与 chip 均零改动（绝不半途清空）', async () => {
    h.popEditable.mockResolvedValue(null)

    h.pressEscape()
    await flush()

    expect(h.textarea().value).toBe('')
    expect(h.chipCount()).toBe(0)
  })
})
