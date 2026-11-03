// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueuedCommandsBar } from '../QueuedCommandsBar'
import type { QueuedCommand } from '@/hooks/useCommandQueue'

/**
 * [goal V253 形态重做] 排队托盘渲染契约测试。
 *
 * <p>WHY: 「排队条有点丑」的落地形态（用户裁定）。核心不变量：
 * <ol>
 *   <li><b>单条压成一行</b>（不摆 header）· 多条才有 header</li>
 *   <li><b>有序号</b>（发送有顺序）</li>
 *   <li><b>系统项显式标记</b>（isEditable=false ⇒ 「系统」标签 + 锁）—— 用户不该以为
 *       系统通知也能拉回</li>
 *   <li><b>最多 3 行 + 「还有 N 条…」</b>（长队列不吞屏幕）</li>
 *   <li>正文 <b>不用等宽字体</b>（等宽只留给 <code>$</code> 前缀的 bash）</li>
 * </ol>
 */
describe('[goal V253] QueuedCommandsBar 形态', () => {
  let container: HTMLDivElement
  let root: Root

  const cmd = (content: string, over: Partial<QueuedCommand> = {}): QueuedCommand => ({
    content,
    mode: 'prompt',
    isEditable: true,
    ...over,
  })

  function renderBar(commands: QueuedCommand[], onEdit = () => {}) {
    root = createRoot(container)
    act(() => {
      root.render(<QueuedCommandsBar queuedCommands={commands} onEdit={onEdit} />)
    })
  }

  beforeEach(() => {
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => { root?.unmount() })
    container.remove()
  })

  it('空队列 ⇒ 不渲染（零高度）', () => {
    renderBar([])
    expect(container.firstChild).toBeNull()
  })

  it('单条 ⇒ 压成一行（无 header）', () => {
    renderBar([cmd('第一条')])
    expect(container.querySelector('.qtray.single')).not.toBeNull()
    expect(container.querySelector('.qt-head')).toBeNull()
    expect(container.textContent).toContain('第一条')
  })

  it('多条 ⇒ 有 header（条数 + 时机说明）', () => {
    renderBar([cmd('第一条'), cmd('第二条')])
    expect(container.querySelector('.qtray.single')).toBeNull()
    expect(container.querySelector('.qt-head')).not.toBeNull()
    expect(container.textContent).toContain('2 条排队中')
    expect(container.textContent).toContain('本轮结束后按顺序发送')
  })

  it('有序号（发送有顺序）', () => {
    renderBar([cmd('第一条'), cmd('第二条')])
    const idx = Array.from(container.querySelectorAll('.qt-idx')).map((e) => e.textContent)
    expect(idx).toEqual(['1', '2'])
  })

  it('系统项（isEditable=false）⇒ 「系统」标签 + 锁图标', () => {
    renderBar([
      cmd('用户排的'),
      cmd('定时任务触发', { isEditable: false, mode: 'task-notification' }),
    ])
    expect(container.textContent).toContain('系统')
    expect(container.querySelector('.qt-lock')).not.toBeNull()
    expect(container.querySelector('.qt-row.system')).not.toBeNull()
  })

  it('全部不可编辑 ⇒ 不渲染「拉回」按钮（系统项拉不回来）', () => {
    renderBar([cmd('定时任务触发', { isEditable: false, mode: 'task-notification' })])
    expect(container.querySelector('.qt-pull')).toBeNull()
  })

  it('有可编辑项 ⇒ 渲染「拉回编辑」并可点', () => {
    const onEdit = vi.fn()
    renderBar([cmd('用户排的'), cmd('系统项', { isEditable: false })], onEdit)
    const btn = container.querySelector('.qt-pull') as HTMLElement
    expect(btn).not.toBeNull()
    act(() => { btn.click() })
    expect(onEdit).toHaveBeenCalledTimes(1)
  })

  it('超过 3 条 ⇒ 只显示 3 行 + 「还有 N 条…」', () => {
    renderBar(Array.from({ length: 5 }, (_, i) => cmd(`第${i + 1}条`)))
    expect(container.querySelectorAll('.qt-row')).toHaveLength(3)
    expect(container.textContent).toContain('还有 2 条…')
    expect(container.textContent).toContain('5 条排队中')
  })

  it('bash 模式 ⇒ $ 前缀单独标记（正文不是等宽字体）', () => {
    renderBar([cmd('npm run test', { mode: 'bash' })])
    expect(container.querySelector('.qt-text .bash')).not.toBeNull()
    // 旧实现的 .queued-cmd（等宽字体类）已不存在 ⇒ 正文不再用等宽
    expect(container.querySelector('.queued-cmd')).toBeNull()
  })
})
