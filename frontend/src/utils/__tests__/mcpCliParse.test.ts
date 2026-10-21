import { describe, expect, it } from 'vitest'
import { parseMcpCommandLine } from '../mcpCliParse'

/**
 * MCP 命令行解析单测。
 *
 * 重点：`claude mcp add-json` 形态（2026-10-21 用户实测缺失）。
 * 修复前它被当成「服务器名=add-json / command=claude / args=整串」，表单填出来完全没法用。
 */
describe('parseMcpCommandLine · add-json', () => {
  /** 用户原文（Windows 路径 + 单引号包裹整段 JSON） */
  const addJsonLine =
    `claude mcp add-json --scope user open-design ` +
    `'{"command":"D:\\\\apps\\\\OpenDesign\\\\Open Design.exe",` +
    `"args":["D:\\\\apps\\\\OpenDesign\\\\resources\\\\app\\\\prebundled\\\\daemon\\\\daemon-cli.mjs","mcp"],` +
    `"env":{"OD_DATA_DIR":"C:\\\\Users\\\\WIN\\\\AppData\\\\Roaming\\\\Open Design\\\\namespaces\\\\release-stable-win\\\\data",` +
    `"OD_SIDECAR_CLIENT_ENDPOINT":"\\\\\\\\.\\\\pipe\\\\open-design-sidecar-219e2f",` +
    `"OD_MCP_BOOTSTRAP_ARGS":"[\\"--headless\\"]","ELECTRON_RUN_AS_NODE":"1"}}'`

  it('识别名字/作用域/command/args/env，而不是把 add-json 当服务器名', () => {
    const r = parseMcpCommandLine(addJsonLine)
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.form.name).toBe('open-design')
    expect(r.form.type).toBe('stdio')
    expect(r.form.scope).toBe('user')
    expect(r.form.command).toBe('D:\\apps\\OpenDesign\\Open Design.exe')
    expect(r.form.argsStr).toContain('daemon-cli.mjs')
    expect(r.form.argsStr.endsWith('mcp')).toBe(true)
    // env 必须逐行 KEY=VALUE（值里的引号原样保留）
    expect(r.form.envStr).toContain('ELECTRON_RUN_AS_NODE=1')
    expect(r.form.envStr).toContain('OD_MCP_BOOTSTRAP_ARGS=["--headless"]')
  })

  it('支持 --scope=user 内联写法与 http 形态', () => {
    const r = parseMcpCommandLine(
      `claude mcp add-json --scope=user remote '{"type":"http","url":"https://example.com/mcp","headers":{"Authorization":"Bearer x"}}'`,
    )
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.form.scope).toBe('user')
    expect(r.form.type).toBe('http')
    expect(r.form.url).toBe('https://example.com/mcp')
    expect(r.form.headersJson).toContain('Bearer x')
  })

  it('JSON 非法 / 缺 command 时明确报错（不静默填半截表单）', () => {
    const bad = parseMcpCommandLine(`claude mcp add-json foo '{not json}'`)
    expect(bad.ok).toBe(false)
    const noCmd = parseMcpCommandLine(`claude mcp add-json foo '{"args":["x"]}'`)
    expect(noCmd.ok).toBe(false)
    if (!noCmd.ok) expect(noCmd.error).toContain('command')
  })
})
