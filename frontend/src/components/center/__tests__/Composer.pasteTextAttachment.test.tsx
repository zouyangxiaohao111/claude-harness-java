// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { PASTE_TEXT_ATTACHMENT_LIMIT } from '@/utils/pasteTextAttachment'

/**
 * [PASTE-TXT] 大段文本粘贴 → txt 附件（对齐 DSH paste-as-text-attachment）。
 *
 * <p><b>WHY 必须渲染真实 Composer</b>：阈值/命名是纯函数（已由
 * `pasteTextAttachment.test.ts` 覆盖），但**接线**（onPaste 分支位置、preventDefault、
 * 把 File 交给 addFiles）只在组件里。纯函数全绿而分支没接上，正是「功能不存在但测试绿」。
 */

const uploadMock = vi.hoisted(() => ({
  fn: vi.fn(async (file: { name: string; size: number }, _sessionId?: string) => ({
    contentId: '42', filename: file.name, size: file.size,
  })),
}))

vi.mock('@tauri-apps/api/core', () => ({
  isTauri: () => false,
  invoke: vi.fn(async () => undefined),
}))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn() }))
vi.mock('@/api/chat', () => ({
  uploadAttachment: (file: { name: string; size: number }, sessionId: string) =>
    uploadMock.fn(file, sessionId),
}))

async function flush(rounds = 6): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  }
}

/** React 的 onPaste 从 nativeEvent.clipboardData 读；jsdom 的 ClipboardEvent 不完整 ⇒ 普通 Event + 挂载属性。 */
function pasteText(el: HTMLTextAreaElement, text: string): Event {
  const ev = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(ev, 'clipboardData', {
    value: {
      items: [] as unknown[],
      files: [] as File[],
      getData: (type: string) => (type === 'text/plain' ? text : ''),
    },
  })
  el.dispatchEvent(ev)
  return ev
}

const BASE_PROPS = {
  composerText: '',
  setComposerText: () => {},
  streaming: false,
  onStop: () => {},
  queuedCommands: [],
  popEditable: async () => null,
  boundProjectName: null,
  onSelectProject: () => {},
  currentModel: 'ds-openai/deepseek-v4-flash',
  permissionMode: 'default' as const,
  empty: false,
}

const chipCount = (c: HTMLElement) => c.querySelectorAll('.attach-preview > *').length

describe('[PASTE-TXT] 粘贴超阈值 → txt 附件', () => {
  let container: HTMLDivElement
  let root: Root
  let warn: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    uploadMock.fn.mockClear()
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => {
      root.render(
        <Composer
          {...BASE_PROPS}
          sendMessage={vi.fn()}
          showToast={vi.fn()}
          sessionId="sess-paste"
          localRead={false}
        />,
      )
    })
  })

  afterEach(() => {
    warn.mockRestore()
    act(() => root.unmount())
    container.remove()
  })

  it('恰好 10000 字 → 不转换（走浏览器默认粘贴，不产生附件）', async () => {
    const ev = pasteText(container.querySelector('textarea')!, 'x'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT))
    await flush()

    expect(ev.defaultPrevented, '未超阈值不得阻止默认粘贴').toBe(false)
    expect(uploadMock.fn).not.toHaveBeenCalled()
    expect(chipCount(container)).toBe(0)
  })

  it('10001 字 → 阻止文本粘贴、生成 txt 附件并走上传播', async () => {
    const ev = pasteText(container.querySelector('textarea')!, 'x'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT + 1))
    await flush()

    expect(ev.defaultPrevented, '超阈值必须阻止默认粘贴').toBe(true)
    expect(uploadMock.fn, 'txt 附件必须真的进入投递通道').toHaveBeenCalledTimes(1)
    const [file, sid] = uploadMock.fn.mock.calls[0] as unknown as [File, string]
    expect(sid).toBe('sess-paste')
    expect(file.type).toBe('text/plain')
    expect(file.name).toBe('x'.repeat(30) + '....txt')
    expect(chipCount(container), '附件 chip 必须出现（不能静默丢）').toBe(1)
    // 现场判据：只处理粘贴，留痕一次
    expect(warn.mock.calls.some((c: unknown[]) => String(c[0]).includes('转 txt 附件'))).toBe(true)
  })

  it('多行日志 → 文件名取首行（跳过空行），不是整段文本', async () => {
    const log = '\n\njava.io.FileNotFoundException: logs/info.log (Permission denied)\n\tat org.springframework.Foo'
      + '\n' + 'x'.repeat(PASTE_TEXT_ATTACHMENT_LIMIT)
    pasteText(container.querySelector('textarea')!, log)
    await flush()

    const [file] = uploadMock.fn.mock.calls[0] as unknown as [File]
    // 首行超 30 字符 → 截断 + `....`；冒号属非法文件名字符 → `_`
    expect(file.name).toBe('java.io.FileNotFoundException_....txt')
  })
})
