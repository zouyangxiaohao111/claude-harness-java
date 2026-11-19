// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'

/**
 * 待发附件预览接线：点击 PDF/Word 文件卡片 ⇒ 打开预览弹层。
 *
 * <p>预览渲染组件（PdfPreview / DocxPreview / ExcelPreview）在 jsdom 里无法跑真实解析，
 * 这里只验证**接线**：卡片可点、弹层挂载、标题 = 文件名、Esc 可关。
 */
vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => true, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }),
}))
vi.mock('@tauri-apps/plugin-fs', () => ({
  stat: vi.fn(),
  readFile: vi.fn(),
  writeTextFile: vi.fn(async () => undefined),
}))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn() }))
vi.mock('@/components/preview/PdfPreview', () => ({ PdfPreview: () => <div className="mock-pdf" /> }))
vi.mock('@/components/preview/DocxPreview', () => ({ DocxPreview: () => <div className="mock-docx" /> }))
vi.mock('@/components/preview/ExcelPreview', () => ({ ExcelPreview: () => <div className="mock-xlsx" /> }))

async function flush(): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
}

describe('待发附件预览（点击文件卡片）', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => {
      root.render(
        <Composer
          composerText=""
          setComposerText={() => {}}
          sendMessage={() => {}}
          showToast={() => {}}
          streaming={false}
          onStop={() => {}}
          queuedCommands={[]}
          popEditable={async () => null}
          boundProjectName={null}
          onSelectProject={() => {}}
          currentModel="ds-openai/deepseek-v4-flash"
          permissionMode="default"
          empty={false}
          sessionId="sess-1"
          localRead={false}
        />,
      )
    })
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
  })

  async function addFile(name: string, type: string, bytes: number[]): Promise<void> {
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File([new Uint8Array(bytes)], name, { type })
    // jsdom 25 的 Blob 未实现 arrayBuffer（Composer.encodeDataUrl 依赖）
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new Uint8Array(bytes).buffer })
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    act(() => { input.dispatchEvent(new Event('change', { bubbles: true })) })
    await flush()
  }

  it('点击 PDF 卡片：弹层出现，标题为文件名，Esc 关闭', async () => {
    await addFile('report.pdf', 'application/pdf', [1, 2, 3])
    const card = container.querySelector('.attach-file-card') as HTMLElement
    expect(card, 'PDF 应渲染为文件卡片').toBeTruthy()

    act(() => { card.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    // 弹层经 createPortal 挂到 document.body（Composer 祖先有 containment，fixed 会被裁）
    expect(document.querySelector('.attach-preview-modal'), '点击后应挂载预览弹层').toBeTruthy()
    expect(document.querySelector('.attach-preview-title')?.textContent).toBe('report.pdf')

    act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })) })
    expect(document.querySelector('.attach-preview-modal'), 'Esc 后弹层关闭').toBeNull()
  })

  it('点删除按钮不触发预览', async () => {
    await addFile('plan.pdf', 'application/pdf', [4, 5])
    const remove = container.querySelector('.attach-file-remove') as HTMLButtonElement
    act(() => { remove.dispatchEvent(new MouseEvent('click', { bubbles: true })) })
    expect(document.querySelector('.attach-preview-modal'), '删除按钮不得冒泡成预览').toBeNull()
    expect(container.querySelector('.attach-file-card'), 'chip 应被移除').toBeNull()
  })
})
