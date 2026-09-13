// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { FORBIDDEN_HEADER_NAMES, KvEditor, hasMalformedPlaceholder } from '../KvEditor'

/**
 * KvEditor · 行式键值对编辑器。
 *
 * WHY（规则 9）：`Provider.extraHeaders` 这一列、后端 DTO、前端 TS 类型全都早就存在，
 * 但前端表单没有输入框、请求构造器也不发这个字段 —— 用户根本改不动它。本用例锁死
 * 「用户能改、改完能拿到正确形状的产物」这条链路，并把三项行内校验（空名 / 禁止清单 /
 * 占位符拼写）钉住：它们的判据源都在后端 `DynamicHeaderExpander`，前端只是提前提示，
 * 一旦前端提示漏了，用户会一路提交到后端才吃 400。
 *
 * 变异自证：把 `toRecord` 里的 `if (r.name.trim() === '') continue` 删掉 → 「空名行不入产物」红；
 * 把 forbiddenNames 默认值换成 `[]` → 「禁止清单标红」红；把 hasMalformedPlaceholder 的
 * `split(SESSION_ID_TOKEN).join('')` 换成不挖 token 的直判 → 「精确占位符不报错」红。
 */

/** React 受控 input 需绕过 value tracker 才能触发 onChange（等价 testing-library 的做法）。 */
function typeInto(el: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  setter?.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('KvEditor · 行式键值对编辑器', () => {
  let container: HTMLDivElement
  let root: Root
  /** 受控父组件持有的当前值（onChange 的产物）。 */
  let current: Record<string, string> | null
  let onChange: Mock<(next: Record<string, string> | null) => void>

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    current = null
    onChange = vi.fn((next: Record<string, string> | null) => {
      current = next
      draw()
    })
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
  })

  /** 以受控方式渲染（父组件拿到 onChange 产物后回灌 value）。 */
  function draw() {
    act(() => {
      root.render(<KvEditor value={current} onChange={onChange} />)
    })
  }

  function nameInputs(): HTMLInputElement[] {
    return Array.from(container.querySelectorAll<HTMLInputElement>('input[placeholder="请求头名称"]'))
  }
  function valueInputs(): HTMLInputElement[] {
    return Array.from(container.querySelectorAll<HTMLInputElement>('input[placeholder="值"]'))
  }
  function addRowBtn(): HTMLButtonElement {
    const btn = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === '+ 添加一行')
    if (!btn) throw new Error('未找到「+ 添加一行」按钮')
    return btn as HTMLButtonElement
  }
  function rowHints(): string[] {
    return Array.from(container.querySelectorAll('.fm-field-hint.error')).map((el) => el.textContent ?? '')
  }

  it('新增一行后改名改值 → 产物是 Record<string,string>', () => {
    draw()
    act(() => addRowBtn().click())
    expect(nameInputs()).toHaveLength(1)

    act(() => typeInto(nameInputs()[0], 'x-tenant'))
    act(() => typeInto(valueInputs()[0], 'acme'))

    expect(onChange).toHaveBeenLastCalledWith({ 'x-tenant': 'acme' })
    expect(current).toEqual({ 'x-tenant': 'acme' })
  })

  it('value 回显成行，且保持原有顺序', () => {
    current = { 'x-a': '1', 'x-b': '2' }
    draw()
    expect(nameInputs().map((i) => i.value)).toEqual(['x-a', 'x-b'])
    expect(valueInputs().map((i) => i.value)).toEqual(['1', '2'])
  })

  it('删除一行 → 该行从产物里消失', () => {
    current = { 'x-a': '1', 'x-b': '2' }
    draw()
    const del = Array.from(container.querySelectorAll('button[title="删除此行"]'))
    expect(del).toHaveLength(2)

    act(() => (del[0] as HTMLButtonElement).click())
    expect(current).toEqual({ 'x-b': '2' })
    expect(nameInputs().map((i) => i.value)).toEqual(['x-b'])
  })

  it('空 name 的行标红，且不进入产物', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(valueInputs()[0], 'orphan'))

    expect(nameInputs()[0].className).toContain('invalid')
    expect(rowHints().join('|')).toContain('名称不能为空')
    // 只有一行空 name 时产物为 {}（**不是 null**）：{}=显式清空、null/缺字段=不触碰，
    // 两者语义不同，且「删光所有 header」必须可表达（详见 toRecord 的 docstring）
    expect(current).toEqual({})
  })

  it('空 name 行与有效行混排 → 只有有效行进入产物', () => {
    current = { 'x-a': '1' }
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(valueInputs()[1], 'ignored'))

    expect(current).toEqual({ 'x-a': '1' })
  })

  it('重名 → 后行覆盖前行 + 前一行给出提示', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'x-dup'))
    act(() => typeInto(valueInputs()[0], 'first'))
    act(() => typeInto(nameInputs()[1], 'x-dup'))
    act(() => typeInto(valueInputs()[1], 'second'))

    // 产物只剩后行；两行仍都在界面上（不静默吞掉用户输入）
    expect(current).toEqual({ 'x-dup': 'second' })
    expect(nameInputs()).toHaveLength(2)
    expect(rowHints().join('|')).toContain('名称重复，仅最后一行生效')
  })

  it('命中禁止清单 → 标红 + 提示，且不静默丢弃（后端才是最终判据）', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'Authorization'))

    expect(nameInputs()[0].className).toContain('invalid')
    expect(rowHints().join('|')).toContain('该请求头由系统管理')
  })

  it('禁止清单大小写不敏感，且清单本体覆盖两个 SDK 的凭据头名', () => {
    // 护栏：T2 桩实测证明 putHeader 能顶掉 SDK 依 apiKey 注入的凭据头，顺序不是安全网
    expect(FORBIDDEN_HEADER_NAMES).toContain('x-api-key')
    expect(FORBIDDEN_HEADER_NAMES).toContain('authorization')
    // 条数与后端 DynamicHeaderExpander.FORBIDDEN_HEADER_NAMES 同步（改动需两边一起改）
    expect(FORBIDDEN_HEADER_NAMES).toHaveLength(14)
    // expect 是 JDK HttpRequest 的受限头：漏了它会让「测试连接」把异常吞成误导性失败
    expect(FORBIDDEN_HEADER_NAMES).toContain('expect')

    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'X-Api-Key'))
    expect(nameInputs()[0].className).toContain('invalid')
  })

  it('禁止清单覆盖 JDK 受限头 expect（大小写不敏感）', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'Expect'))

    expect(nameInputs()[0].className).toContain('invalid')
    expect(rowHints().join('|')).toContain('该请求头由系统管理')
  })

  it('名称含空格 → 标红（RFC 7230 token）', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'x tenant'))
    expect(nameInputs()[0].className).toContain('invalid')
    expect(rowHints().join('|')).toContain('名称含非法字符')
  })

  it('占位符拼写错误 → 标红 + 给出正确写法', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'x-session'))
    act(() => typeInto(valueInputs()[0], '${SESSION_ID}'))

    expect(valueInputs()[0].className).toContain('invalid')
    expect(rowHints().join('|')).toContain('${session_id}')
  })

  it('精确占位符不报错（挖掉精确 token 后不再残留）', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'x-session'))
    act(() => typeInto(valueInputs()[0], 'sess-${session_id}'))

    expect(valueInputs()[0].className).not.toContain('invalid')
    expect(rowHints()).toEqual([])
    expect(current).toEqual({ 'x-session': 'sess-${session_id}' })
  })

  it('hasMalformedPlaceholder 与后端同语义（大小写敏感 · 全部替换而非首个）', () => {
    expect(hasMalformedPlaceholder('${session_id}')).toBe(false)
    expect(hasMalformedPlaceholder('a-${session_id}-b')).toBe(false)
    expect(hasMalformedPlaceholder('${session_id}${session_id}')).toBe(false)
    expect(hasMalformedPlaceholder('${SESSION_ID}')).toBe(true)
    expect(hasMalformedPlaceholder('${session_id}-${oops}')).toBe(true)
    expect(hasMalformedPlaceholder('no placeholders')).toBe(false)
  })

  it('全部行删光 → 产物为 {}（显式清空；这是「删光 header」唯一可表达的形态）', () => {
    current = { 'x-a': '1' }
    draw()
    act(() => (container.querySelector('button[title="删除此行"]') as HTMLButtonElement).click())
    expect(current).toEqual({})
  })

  it('仅大小写不同的重名 → 归一后只剩最后一行，且提示不是假话', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'X-A'))
    act(() => typeInto(valueInputs()[0], '1'))
    act(() => typeInto(nameInputs()[1], 'x-a'))
    act(() => typeInto(valueInputs()[1], '2'))

    // HTTP 头名大小写不敏感 ⇒ X-A / x-a 实为同一个头；若产物里两个都在，就与「仅最后一行生效」矛盾。
    // 归一后保留**最后一行**的原始大小写作键。
    expect(current).toEqual({ 'x-a': '2' })
    expect(rowHints().join('|')).toContain('名称重复，仅最后一行生效')
  })

  it('值含非 ASCII（中文）→ 标红 + 文案说明是可见 ASCII 限制，不只是换行', () => {
    draw()
    act(() => addRowBtn().click())
    act(() => typeInto(nameInputs()[0], 'x-note'))
    act(() => typeInto(valueInputs()[0], '中文值'))

    expect(valueInputs()[0].className).toContain('invalid')
    const hints = rowHints().join('|')
    expect(hints).toContain('可见 ASCII')
    expect(hints).toContain('不得包含换行符')
  })
})
