// @vitest-environment jsdom
/**
 * 块级冻结的**默认值**判据（2026-11-18 用户拍板「默认开」）。
 *
 * <p>WHY 单独锁：`blockFreezeEnabled` 的优先级是「显式 prop → window 开关 → 默认 true」。
 * 若默认值回退成 false，多段/块级渲染不会生效，性能改造整体静默失效——而所有
 * html 等价性测试仍然是绿的（它们直接调增量函数）。这里从**组件**侧验：
 * 不设置任何开关时，文本增长的第二次渲染必须出现按块容器；
 * 显式 `window.__NEXUS_MD_BLOCK_FREEZE__ = false` 时必须回到单容器。
 */
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MarkdownTextV2 } from '../renderer'

const T1 = '# 标题\n\n第一段正文，内容是 markdown。\n\n- 列表项 A\n- 列表项 B\n'
const T2 = `${T1}\n第二段追加内容，用来触发「文本增长」路径。\n`

let container: HTMLDivElement
let root: Root

function render(text: string) {
  act(() => {
    root.render(<MarkdownTextV2 text={text} streaming />)
  })
}

/** 按块渲染的容器（`display: contents`，块级冻结的唯一 DOM 特征）。 */
const blockContainers = (): number =>
  Array.from(container.querySelectorAll('.md-content > div'))
    .filter((el) => (el as HTMLElement).style.display === 'contents').length

describe('MarkdownTextV2 块级冻结默认值', () => {
  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    delete (window as { __NEXUS_MD_BLOCK_FREEZE__?: boolean }).__NEXUS_MD_BLOCK_FREEZE__
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
    delete (window as { __NEXUS_MD_BLOCK_FREEZE__?: boolean }).__NEXUS_MD_BLOCK_FREEZE__
  })

  it('不设置任何开关：文本增长的帧走块级渲染（默认开）', () => {
    render(T1)
    expect(blockContainers()).toBe(0)          // 首帧：全量单容器（历史消息也走这条）
    render(T2)                                  // 追加 ⇒ 增长帧
    expect(blockContainers()).toBeGreaterThan(0)
    // 内容完整（块级拼接没有丢块）
    expect(container.querySelector('.md-content')?.textContent).toContain('第二段追加内容')
  })

  it('显式 window 开关 = false：增长帧回到全量单容器（可临时回滚）', () => {
    ;(window as { __NEXUS_MD_BLOCK_FREEZE__?: boolean }).__NEXUS_MD_BLOCK_FREEZE__ = false
    render(T1)
    render(T2)
    expect(blockContainers()).toBe(0)
    expect(container.querySelector('.md-content')?.textContent).toContain('第二段追加内容')
  })
})
