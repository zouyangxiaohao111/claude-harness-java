// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BASE64_LIMIT } from '@/utils/attachmentDelivery'
import { Composer } from '../Composer'

/**
 * ⭐ <b>附件「入口接线」的真实渲染守卫</b>（批 ATT-DEDUP-KEY / ATT-DRAG-STALE）。
 *
 * <b>WHY 必须真的渲染一次 Composer</b>：本批修的两个缺陷都**不在**纯函数里，而在**接线**上 ——
 * 纯函数（`planPathAttachmentChannel` / md5 / 去重键构造）全都对，坏的是「谁在什么时候调用谁」：
 * <ol>
 *   <li><b>拖拽入口闭包冻结</b>：`onDragDropEvent` 只在挂载时订阅一次（依赖数组 `[]`），而
 *       `addPaths` 每次渲染新建、闭包捕获 `localRead`（`App` 初值 false，等配置回来才变 true）
 *       与 `sessionId`（初值 ''）⇒ 锁死首帧那一个 ⇒ 桌面拖入的非图片非 PDF 文件直落 base64 腿
 *       ⇒ 后端对 `type=file` 的 base64 **零消费方** ⇒ **静默丢弃**。
 *       ⛔ 现场证据：全份 `frontend.log` 里 `通道=path` 出现 **0 次**，2.4MB 的 .docx 记的是
 *       `通道=base64`。（同一个文件走「附件」对话框能送达 —— 那条入口每次渲染新建闭包。）</li>
 *   <li><b>去重键释放点</b>：键从「文件名」改成「内容 md5 / 完整路径」后，若移除 chip 仍按
 *       `filename` 释放，则「移除了 chip 但键还在」⇒ **同一张图再也加不回来**。这同样是接线，
 *       不是纯函数：`addedKeysRef.current.delete(a.dedupKey)` 这一行。</li>
 * </ol>
 * 本仓此前**没有任何测试覆盖附件入口接线**（`attachmentDelivery.test.ts` 测的是被调用的纯函数、
 * `pathAttachment.test.ts` 测的是判据；两者都无法察觉「调用点拿的是哪一帧的闭包」）—— 这正是
 * ATT-DRAG-STALE 能在 0.1.13 的主入口上漏过去的原因。
 *
 * <b>手法</b>：沿用本仓既有的 jsdom 真实渲染模式（`ErrorBoundary.render.test.tsx` /
 * `ProvidersPanel.headers.test.tsx`）：`createRoot` + `act`，**不引入任何新依赖**。
 * Tauri 的三处外部依赖（core / webview / plugin-fs）用 `vi.mock` 注入假件。
 */

const tauri = vi.hoisted(() => ({
  /** 被 `onDragDropEvent` 注册进来的回调（测试据此模拟真实拖放） */
  dropCb: null as null | ((e: { payload: { type: string; paths: string[] } }) => void),
  unlisten: null as null | (() => void),
  subscribeCount: 0,
}))

vi.mock('@tauri-apps/api/core', () => ({
  // 模拟桌面端（WebView 拦截浏览器 drop，路径只从 onDragDropEvent 出来）
  isTauri: () => true,
  invoke: vi.fn(async () => undefined),
}))

vi.mock('@tauri-apps/api/webview', () => ({
  getCurrentWebview: () => ({
    onDragDropEvent: (cb: (e: { payload: { type: string; paths: string[] } }) => void) => {
      tauri.subscribeCount += 1
      tauri.dropCb = cb
      const un = () => { tauri.unlisten?.() }
      tauri.unlisten = vi.fn()
      return Promise.resolve(un)
    },
  }),
}))

const fsMocks = vi.hoisted(() => ({
  stat: vi.fn(),
  readFile: vi.fn(),
  writeTextFile: vi.fn(async () => undefined),
}))

vi.mock('@tauri-apps/plugin-fs', () => ({
  stat: fsMocks.stat,
  readFile: fsMocks.readFile,
  writeTextFile: fsMocks.writeTextFile,
}))

/** upload 腿的假上传：按调用顺序发**不同**的 contentId（用来验证「回填到了哪一个 chip」）。 */
const uploadMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('@/api/chat', () => ({
  uploadAttachment: (file: { name: string; size: number }, sessionId: string) => uploadMock.fn(file, sessionId),
}))

/** 让整批 `addFiles` 的 promise 链落地（与 `attachmentDelivery.test.ts` 同款等待方式）。 */
async function flush(): Promise<void> {
  await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
}

const chipCount = (c: HTMLElement) => c.querySelectorAll('.attach-item').length

/** 取 `console.warn` 的留痕行（本仓 `[attach]` 观测日志都走它 —— 现场判据就是这些行）。 */
function warnLines(spy: { mock: { calls: unknown[][] } }): string[] {
  return spy.mock.calls.map((c) => String(c[0]))
}

/**
 * 手动放行的闸门：让假上传一直「在飞」，直到测试显式放行。
 * ⛔ 不用 `setTimeout` 排时序 —— 「两个 chip 同时在飞」这个前提必须**确定**成立，否则
 * 「按文件名回填/撤 chip」这组用例会时红时绿（那比不写更坏）。
 */
function gate(): { p: Promise<void>; open: () => void } {
  let open!: () => void
  const p = new Promise<void>((r) => { open = r })
  return { p, open }
}

/** 放行闸门并让 React 跟上状态更新。 */
async function release(g: { open: () => void }): Promise<void> {
  await act(async () => {
    g.open()
    await new Promise((r) => setTimeout(r, 0))
  })
}

/**
 * 造一张「截图」：真 `File`（真 name/type/size）+ 指定内容字节
 * （用来区分「同一张图」与「两张同名图」—— 批 ATT-DEDUP-KEY 的核心区分）。
 *
 * ⚠️ jsdom 25 的 `Blob` **未实现** `arrayBuffer()`（浏览器有，`Composer.encodeDataUrl` 依赖它）
 * ⇒ 就地补上这一个方法，其余仍是真 `File`（不改成假对象，避免夹具与真实 `File` 语义漂移）。
 */
function shot(name: string, bytes: number[]): File {
  const file = new File([new Uint8Array(bytes)], name, { type: 'image/png' })
  Object.defineProperty(file, 'arrayBuffer', {
    value: async () => new Uint8Array(bytes).buffer,
  })
  return file
}

interface Harness {
  container: HTMLDivElement
  root: Root
  render: (p: { localRead: boolean; sessionId: string }) => void
  sendMessage: ReturnType<typeof vi.fn>
  showToast: ReturnType<typeof vi.fn>
  unmount: () => void
}

const BASE_PROPS = {
  composerText: '',
  setComposerText: () => {},
  streaming: false,
  onStop: () => {},
  queuedCommands: [],
  popEditable: () => {},
  boundProjectName: null,
  onSelectProject: () => {},
  currentModel: 'ds-openai/deepseek-v4-flash',
  permissionMode: 'default' as const,
  empty: false,
}

function mount(): Harness {
  // React 19 的 act 环境标记（本仓其余 jsdom 渲染测试同款；缺了它 act 会告警且刷不动状态）
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  const sendMessage = vi.fn()
  const showToast = vi.fn()
  const render = (p: { localRead: boolean; sessionId: string }) => {
    act(() => {
      root.render(
        <Composer
          {...BASE_PROPS}
          sendMessage={sendMessage}
          showToast={showToast}
          sessionId={p.sessionId}
          localRead={p.localRead}
        />,
      )
    })
  }
  return { container, root, render, sendMessage, showToast, unmount: () => act(() => root.unmount()) }
}

/** 经隐藏的 `<input type=file>` 入口投递文件（Tauri 下浏览器 drop 被 WebView 拦截，故走这条）。 */
async function addViaInput(container: HTMLElement, files: File[]): Promise<void> {
  const input = container.querySelector('input[type=file]') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  await act(async () => { input.dispatchEvent(new Event('change', { bubbles: true })) })
  await flush()
}

function clickButton(container: HTMLElement, sel: string): void {
  const btn = container.querySelector(sel) as HTMLButtonElement | null
  if (!btn) throw new Error(`找不到按钮：${sel}`)
  act(() => btn.click())
}

describe('⭐ 拖拽入口：回调用**当前**的 addPaths（批 ATT-DRAG-STALE）', () => {
  let h: Harness
  let warn: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    tauri.dropCb = null
    tauri.subscribeCount = 0
    fsMocks.stat.mockReset()
    fsMocks.readFile.mockReset()
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    h = mount()
  })

  afterEach(() => {
    warn.mockRestore()
    h.unmount()
  })

  it('⭐ 首帧订阅 ⇒ 之后 drop 必须用**最新**闭包：非图片非 PDF 走 path（不读盘）', async () => {
    // 首帧：真实 localRead 还没回来（App 初值 false）、会话也未选中（store 初值 ''）
    h.render({ localRead: false, sessionId: '' })
    await flush()
    expect(tauri.dropCb, '挂载后应已订阅拖拽事件').toBeTruthy()
    expect(tauri.subscribeCount, '⛔ 只订阅一次（放进依赖数组会重复订阅/退订）').toBe(1)

    // 配置与会话到位 → 重渲染（addPaths 被重建，闭包里的 localRead / sessionId 更新）
    fsMocks.stat.mockResolvedValue({ size: 2_466_154 })   // 现场那个 2.4MB 的 .docx
    h.render({ localRead: true, sessionId: 'sess-1' })
    await flush()
    fsMocks.stat.mockClear()
    fsMocks.readFile.mockClear()

    // 桌面拖入：2.4MB 的 .docx（非图片非 PDF ⇒ localRead=true 时**必须**走 path，与大小无关）
    await act(async () => {
      tauri.dropCb!({ payload: { type: 'drop', paths: ['D:\\tmp\\报告.docx'] } })
    })
    await flush()

    // 判据①：走了 path 腿就**不会读盘**（base64 腿必然 readFile —— 现场日志记的正是「通道=base64」）
    expect(fsMocks.readFile, 'localRead=true 时非图片非 PDF 不得走 base64 腿（读盘即错腿）').not.toHaveBeenCalled()
    expect(fsMocks.stat, '应先 stat 拿 size（零拷贝：只取大小，不整读）').toHaveBeenCalledTimes(1)
    expect(chipCount(h.container), 'path 腿也要生成 chip（附件不能消失）').toBe(1)
    // 判据②：留痕必须是「通道=path」—— 现场证据就是这一行缺失（全份日志 0 次）
    const logs = warnLines(warn)
    expect(logs.some((s) => s.includes('通道=path')), `缺「通道=path」留痕；实际日志=${JSON.stringify(logs)}`).toBe(true)
  })

  it('反向锚点：首帧（localRead=false）时**确实**会走 base64 —— 证明上一条的红不是因为「本来就该 path」', async () => {
    // 只渲染首帧、不更新 localRead ⇒ 复用同一个假件，此时闭包里的 localRead 就是 false
    h.render({ localRead: false, sessionId: 'sess-1' })
    await flush()
    fsMocks.stat.mockResolvedValue({ size: 1024 })
    fsMocks.readFile.mockResolvedValue(new Uint8Array([1, 2, 3]))
    await act(async () => {
      tauri.dropCb!({ payload: { type: 'drop', paths: ['D:\\tmp\\报告.docx'] } })
    })
    await flush()
    // 非 local-read 模式：读盘 → base64 腿（后端对 file 类 base64 无消费方，但那是**另一个**通道语义）
    expect(fsMocks.readFile).toHaveBeenCalledTimes(1)
    const logs = warnLines(warn)
    expect(logs.some((s) => s.includes('通道=base64'))).toBe(true)
  })

  it('enter 不带路径进 addPaths，但必须留痕（现场靠它区分「drop 只给了一个」与「中途被丢」）', async () => {
    h.render({ localRead: true, sessionId: 'sess-1' })
    await flush()
    fsMocks.stat.mockResolvedValue({ size: 1024 })
    await act(async () => {
      tauri.dropCb!({ payload: { type: 'enter', paths: ['D:\\tmp\\报告.docx'] } })
    })
    await flush()
    expect(fsMocks.stat, 'enter 不进 addPaths').not.toHaveBeenCalled()
    expect(chipCount(h.container)).toBe(0)
    const logs = warnLines(warn)
    expect(logs.some((s) => s.includes('type=enter'))).toBe(true)
  })
})

describe('⭐ 附件去重键的接线（批 ATT-DEDUP-KEY）', () => {
  let h: Harness

  beforeEach(() => {
    fsMocks.stat.mockReset()
    fsMocks.readFile.mockReset()
    uploadMock.fn.mockReset()
    h = mount()
    h.render({ localRead: false, sessionId: 'sess-1' })
  })

  afterEach(() => h.unmount())

  it('⭐ 核心复现（用户可见形态）：两张**都叫 image.png** 但内容不同的截图 ⇒ 两个 chip 都要出现', async () => {
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container)).toBe(1)
    await addViaInput(h.container, [shot('image.png', [2, 2, 2])])
    // 改前（键 = 文件名）：第二张被判重复 ⇒ 这里只有 1 个 chip —— 正是「只能粘一张」
    expect(chipCount(h.container), '第二张同名截图不得被判重复').toBe(2)
  })

  it('⭐ 同名两个 upload chip 的 contentId 回填必须**按键**（按文件名会互相串）', async () => {
    // 「两张都叫 image.png 的大图」是改键后**新允许**的组合（改前第二个必被判重复）。
    // ⚠️ upload 腿的身份是「名 + 大小」（见 fileDedupKey 的内存代价论证），故两张必须**大小不同**
    //    才能共存 —— 同名同大小仍判重复，这是该腿如实登记的代价。
    const g = gate()
    const big = (contentId: string, size: number) => {
      const bytes = new Uint8Array(size)
      const f = new File([bytes], 'image.png', { type: 'image/png' })
      Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer })
      uploadMock.fn.mockImplementationOnce(async () => {
        await g.p
        return { contentId }
      })
      return f
    }
    await addViaInput(h.container, [big('cid-甲', BASE64_LIMIT + 1), big('cid-乙', BASE64_LIMIT + 2)])
    // ⚠️ 必须**同一批**投递且两个上传**同时在飞**（都停在 '__uploading__'）—— 「按文件名回填」的
    //   失效形态正是「先到的那次把 contentId 写进两个同名 chip，后到的再也找不到落点」。
    //   （分两批投递时，第二次回填时第一个已回填完，按文件名也恰好只命中剩下那个 ⇒ 抓不住。）
    expect(chipCount(h.container), '两张同名大图各自成 chip（都还在上传中）').toBe(2)
    expect(uploadMock.fn, '两个上传同时在飞').toHaveBeenCalledTimes(2)
    await release(g)
    clickButton(h.container, 'button.send-btn:not(.danger)')
    const reqs = h.sendMessage.mock.calls[0][0] as { filename: string; contentId?: string }[]
    expect(reqs).toHaveLength(2)
    // 两个 chip 必须各拿**自己的** contentId（按键回填）；按文件名回填时两者会相等 ⇒ 红
    expect(reqs.map((r) => r.contentId)).toEqual(['cid-甲', 'cid-乙'])
  })

  it('⭐ 同名两个 upload chip 中**失败**一个：只撤自己那一个（按文件名会把另一个也撤掉）', async () => {
    const g = gate()
    const make = (shouldFail: boolean, size: number) => {
      const bytes = new Uint8Array(size)
      const f = new File([bytes], 'image.png', { type: 'image/png' })
      Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer })
      uploadMock.fn.mockImplementationOnce(async () => {
        await g.p
        if (shouldFail) throw new Error('上传失败')
        return { contentId: 'cid-ok' }
      })
      return f
    }
    // 同上一例：必须**同一批**投递（两个 chip 同时在飞）—— 按文件名撤 chip 时会把两个一起撤掉
    await addViaInput(h.container, [make(true, BASE64_LIMIT + 1), make(false, BASE64_LIMIT + 2)])
    expect(chipCount(h.container), '两张同名大图各自成 chip（都还在上传中）').toBe(2)
    await release(g)
    // 失败的那个被撤下，成功的那个必须**留着**（旧接线按 filename 撤 ⇒ 两个都没了）
    expect(chipCount(h.container), '只撤失败的那一个').toBe(1)
    expect(h.showToast.mock.calls.some((c) => String(c[0]).includes('附件未能送达：image.png'))).toBe(true)
  })

  it('同一张图（同内容）加两次 ⇒ 只 1 个 chip（内容身份仍要去重）', async () => {
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container)).toBe(1)
  })

  it('⭐ 释放点①：移除 chip 后能重新加同一张图（键按 dedupKey 释放）', async () => {
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container)).toBe(1)
    clickButton(h.container, '.attach-remove')
    expect(chipCount(h.container)).toBe(0)
    // ⛔ 键没被释放（例如仍按 filename 删）⇒ 这一次会判重复 ⇒ chip 数停在 0
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container), '移除后同一张图必须能再加回来').toBe(1)
  })

  it('⭐ 释放点②：点「清空」后能重新加同一张图', async () => {
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    const clearBtn = [...h.container.querySelectorAll('button.tool-chip')].find((b) => b.textContent === '清空')
    expect(clearBtn, '有附件时应出现「清空」按钮').toBeTruthy()
    act(() => (clearBtn as HTMLButtonElement).click())
    expect(chipCount(h.container)).toBe(0)
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container), '清空后同一张图必须能再加回来').toBe(1)
  })

  it('⭐ 释放点③：发送后能重新加同一张图（sendMessage 收到附件，列表与键一起清空）', async () => {
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    clickButton(h.container, 'button.send-btn:not(.danger)')
    expect(h.sendMessage, '发送应把附件交给 sendMessage').toHaveBeenCalledTimes(1)
    expect(chipCount(h.container)).toBe(0)
    await addViaInput(h.container, [shot('image.png', [1, 1, 1])])
    expect(chipCount(h.container), '发送后同一张图必须能再加回来').toBe(1)
  })
})
