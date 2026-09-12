// @vitest-environment jsdom
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { JsonBlock } from '../JsonBlock'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import type { PermissionRequestItem } from '@/stores/chatStore'

/**
 * [权限弹窗参数区回归] JsonBlock 的折叠 / 展开 / 收起 / 截断。
 *
 * <b>WHY 这组断言必须存在</b>：JsonBlock 是 PermissionBubble 工具参数区的唯一渲染路径，
 * 它的三个判别点都<b>没有编译期约束</b>，改错了 tsc 与其余用例照样全绿：
 *   1. <b>折叠短路</b> —— `if (!open) return ''` 决定了折叠态<b>不计算</b> body。工具参数
 *      可能极大（Bash 命令 + 长 heredoc），若不短路，每次折叠态渲染都要跑一次
 *      `JSON.stringify` 并生成超长字符串，等于把「折叠」这个性能开关做废。用 `toJSON`
 *      计数器直接钉住「没被 stringify」，比只断言 DOM 里没有 body 更严 —— 后者在
 *      「渲染了但内容为空」时也会通过。
 *   2. <b>`defaultOpen`</b> —— PermissionBubble 依赖它保持「与旧恒展开 `<pre>` 视觉一致」。
 *      若默认值被改回 false，弹窗会静默变成默认折叠（用户未要求的行为变更），
 *      而 DOM 断言若不覆盖 mounted-with-defaultOpen 就抓不到。
 *   3. <b>截断边界</b> —— 条件是 `s.length > MAX_CHARS`（严格大于）。写成 `>=` 会让恰好
 *      20000 字符的正常参数多出一条「已截断」脚注。
 * 另钉住 `payload = null` 的真实行为（渲染字符串 "null"、不崩）：这是「JsonBlock 故意
 * 不设 null 守卫、由调用方先挡」的书面依据，改动此处即破坏 PermissionBubble 的契约。
 *
 * 环境写法照 components/center/__tests__/ansiOutput.test.tsx（jsdom + react-dom/client
 * createRoot + act，不引入未安装的 @testing-library/react）。
 */

/** 共享 jsdom 挂载台（下方两个 describe 复用：JsonBlock 本体 + 它的调用方契约）。 */
let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

const bodyEl = () => container.querySelector<HTMLPreElement>('.json-block-body')
const bodyText = () => bodyEl()?.textContent ?? null
const toggle = () => container.querySelector<HTMLButtonElement>('.json-block-toggle')
const toggleText = () => toggle()?.textContent ?? null
const render = async (node: ReactNode) => { await act(async () => { root.render(node) }) }
const clickToggle = async () => {
  const btn = toggle()
  expect(btn).not.toBeNull()
  await act(async () => { btn!.click() })
}

describe('JsonBlock 折叠 / 展开 / 截断', () => {
  it('默认折叠：不渲染 body，且【不计算】body（折叠短路）', async () => {
    let stringifyCalls = 0
    const payload = { toJSON: () => { stringifyCalls += 1; return { command: 'ls' } } }
    await render(<JsonBlock label="工具参数" payload={payload} />)

    expect(bodyEl()).toBeNull()
    expect(container.textContent).toBe('▸ 工具参数')
    // 判别点：折叠态必须短路。去掉 useMemo 里的 `if (!open) return ''` 后此处变红。
    expect(stringifyCalls).toBe(0)
  })

  it('defaultOpen：挂载即渲染 pretty-print body（弹窗视觉与旧 <pre> 一致的前提）', async () => {
    await render(<JsonBlock label="工具参数" payload={{ command: 'ls' }} defaultOpen />)
    expect(bodyText()).toBe(JSON.stringify({ command: 'ls' }, null, 2))
    expect(toggleText()).toBe('▾ 工具参数')
  })

  it('展开：渲染 pretty-print body，且此后【才】计算', async () => {
    let stringifyCalls = 0
    const payload = { toJSON: () => { stringifyCalls += 1; return { b: 2 } } }
    await render(<JsonBlock label="工具参数" payload={payload} />)

    await clickToggle()
    expect(stringifyCalls).toBe(1)
    expect(bodyText()).toBe(JSON.stringify({ b: 2 }, null, 2))
    expect(toggleText()).toBe('▾ 工具参数')
  })

  it('可再收起：body 消失、标记回到 ▸（展开必须可逆）', async () => {
    await render(<JsonBlock label="工具参数" payload={{ a: 1 }} />)
    await clickToggle()
    expect(bodyEl()).not.toBeNull()

    await clickToggle()
    expect(bodyEl()).toBeNull()
    expect(toggleText()).toBe('▸ 工具参数')
  })

  it('超 20000 字符：截断到 20000 + 脚注报出全量长度', async () => {
    const payload = { s: 'x'.repeat(20_001) }
    const full = JSON.stringify(payload, null, 2)
    expect(full.length).toBeGreaterThan(20_000)

    await render(<JsonBlock label="工具参数" payload={payload} defaultOpen />)
    expect(bodyText()).toBe(`${full.slice(0, 20_000)}\n… 已截断，共 ${full.length} 字符`)
  })

  it('恰好 20000 字符：不截断、无脚注（边界须为 > 而非 >=）', async () => {
    const payload = 'x'.repeat(19_998) // stringify 补一对引号 → 恰好 20000
    const full = JSON.stringify(payload, null, 2)
    expect(full.length).toBe(20_000)

    await render(<JsonBlock label="工具参数" payload={payload} defaultOpen />)
    expect(bodyText()).toBe(full)
    expect(bodyText()).not.toContain('已截断')
  })

  it('payload 为 null：无 null 守卫 → body 渲染字符串 "null"、不崩（故调用方必须先挡）', async () => {
    await render(<JsonBlock label="工具参数" payload={null} defaultOpen />)
    expect(bodyText()).toBe('null')
  })

  it('payload 为 undefined：JSON.stringify 返回 undefined → 回落 String()（守 ?? 分支）', async () => {
    await render(<JsonBlock label="工具参数" payload={undefined} defaultOpen />)
    expect(bodyText()).toBe('undefined')
  })

  it('循环引用：JSON.stringify 抛错 → 静默回落 String()，不把弹窗带崩', async () => {
    const circular: { self?: unknown } = {}
    circular.self = circular
    await render(<JsonBlock label="工具参数" payload={circular} defaultOpen />)
    expect(bodyText()).toBe('[object Object]')
  })
})

/**
 * [调用方契约] PermissionBubble 的 ToolInput → JsonBlock 入参整形。
 *
 * <b>WHY 这组断言必须存在</b>：`toolInput` 可能是**可解析的 JSON 字符串节点**（后端
 * `MessagePermissionRequestEvent.toolInput` / `BridgePermissionRequestEvent.displayInput`
 * 均为 JsonNode，可序列化成字符串节点）。JsonBlock 对任何入参一律 `JSON.stringify`，
 * 所以若调用方把字符串**原样**传下去，`'{"command":"ls"}'` 会显示成带转义引号的
 * `"{\"command\":\"ls\"}"` —— 内容没丢但不可读，属**行为回退**（改造前的
 * `typeof toolInput === 'string' ? toolInput : JSON.stringify(...)` 两分支正是为此）。
 * 本组断言钉住「先 tryParse」这一层：删掉整形、把分支写回恒 `payload={toolInput}` 即变红
 * （已用反向实验证实）。
 */
describe('PermissionBubble 工具参数区（JsonBlock 调用方契约）', () => {
  /** 造一条最小普通权限请求（无 questions → 走 ToolInput 分支）。 */
  function renderBubble(toolInput: unknown) {
    const request: PermissionRequestItem = {
      kind: 'message', sessionId: 'sess-json', requestId: 'req-1', toolName: 'Bash', toolInput,
    }
    return render(<PermissionBubble request={request} onDecision={() => {}} />)
  }

  it('toolInput 为可解析 JSON 字符串：按解析后的对象渲染，与直接传对象等价', async () => {
    await renderBubble('{"command":"ls"}')
    // 判别点：等价于传对象 —— 若整形被删，此处会变成带反斜杠的 "{\"command\":\"ls\"}"
    expect(bodyText()).toBe(JSON.stringify({ command: 'ls' }, null, 2))
  })

  it('toolInput 为不可解析字符串：回落裸串（stringify 后带引号，但内容不丢）', async () => {
    await renderBubble('not-json')
    expect(bodyText()).toBe(JSON.stringify('not-json', null, 2))
  })
})
