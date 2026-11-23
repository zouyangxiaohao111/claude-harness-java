// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import type { McpServer } from '@/api/types'
import { McpServerRow } from '@/components/mcp/McpServerRow'

/**
 * MCP 行「启用开关固定最右」布局约束（13 寸窄宽必现 bug，2026-11-23）。
 *
 * <p><b>WHY</b>：`.cli-card` 是 `flex-wrap: wrap`（展开区需要独占一行）。窄宽下长命令
 * 会把开关挤到第二行 —— 头部必须包在 nowrap 的 `.mcp-card-main` 里，开关 `flex:none`
 * + `margin-left:auto`，命令区 `min-width:0` 省略、hover 用 title 看全。
 *
 * <p>jsdom 不做布局计算，所以「不换行」只能靠 **DOM 结构 + CSS 源码** 双断言：
 * 渲染断结构（开关是否在 nowrap 容器内），源码断样式（nowrap / flex:none / min-width:0）。
 */
// jsdom 环境下 import.meta.url 不是 file: 方案 ⇒ 用 cwd 相对路径（vitest 的 cwd = front/）
const CSS = path.resolve(process.cwd(), 'src/styles/globals.css')
const CUSTOM_DIALOG = path.resolve(process.cwd(), 'src/components/market/CustomConnectorDialog.tsx')

const LONG = 'npx -y @modelcontextprotocol/server-filesystem /very/long/path/that/keeps/going/forever/and/ever'

const SERVER = {
  id: 'mcp-long',
  name: 'long-command-server',
  type: 'stdio',
  command: LONG,
  enabled: true,
  autoStart: true,
  status: 'running',
  runtimeStatus: 'connected',
  approvalStatus: 'approved',
} as unknown as McpServer

let container: HTMLDivElement | undefined
let root: Root | undefined

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  if (root) act(() => root!.unmount())
  container?.remove()
})

describe('McpServerRow · 开关右对齐结构', () => {
  it('点击区与开关包在同一个 .mcp-card-main（nowrap 行）里', () => {
    act(() => {
      root!.render(
        <McpServerRow server={SERVER} open={false} onToggleOpen={() => undefined} onToggleEnabled={() => undefined}>
          <div />
        </McpServerRow>,
      )
    })
    const main = container!.querySelector('.mcp-card-main')
    expect(main, '缺 .mcp-card-main：开关会与长命令抢同一行并换行').toBeTruthy()
    expect(main!.querySelector('.cli-card-hit')).toBeTruthy()
    expect(main!.querySelector('.mcp-card-tail')).toBeTruthy()
    // 命令单行省略 + hover title 看全
    const meta = main!.querySelector('.cli-card-meta') as HTMLElement
    expect(meta.getAttribute('title')).toContain(LONG)
  })

  it('CSS：nowrap 行 / 开关 flex:none+margin-left:auto / 左侧 min-width:0', () => {
    const css = readFileSync(CSS, 'utf8').replace(/\r\n?/g, '\n')
    const mainBlock = css.slice(css.indexOf('.mcp-card-main {'), css.indexOf('.mcp-card-main {') + 500)
    expect(mainBlock).toMatch(/flex-wrap:\s*nowrap/)
    const hitBlock = css.slice(css.indexOf('.mcp-card-main .cli-card-hit'), css.indexOf('.mcp-card-main .cli-card-hit') + 200)
    expect(hitBlock).toMatch(/min-width:\s*0/)
    const tailBlock = css.slice(css.indexOf('.mcp-card-main .mcp-card-tail'), css.indexOf('.mcp-card-main .mcp-card-tail') + 200)
    expect(tailBlock).toMatch(/flex:\s*0 0 auto/)
    expect(tailBlock).toMatch(/margin-left:\s*auto/)
  })

  it('自定义连接器列表复用同一行组件（⛔ 不另写一套简化列表）', () => {
    const src = readFileSync(CUSTOM_DIALOG, 'utf8')
    expect(src).toContain('<McpServerRow')
    expect(src).toContain('<McpServerForm')
  })
})
