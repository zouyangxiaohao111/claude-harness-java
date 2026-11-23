// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  APP_KIND_LABEL,
  fetchAppEntries,
  appSelectionStorageKey,
  forgetRememberedApps,
  loadRememberedApps,
  normalizeAppEntries,
  saveRememberedApps,
  normalizeMcpEntries,
  toAppEntry,
  toggleAppKey,
} from '../apps'
import type { AppDto } from '../apps'
import { api } from '../rest'
import { mcpApi } from '../mcp'
import type { McpServer } from '../types'

// 应用目录的两个数据源：后端 /api/v1/apps（主）与 mcpApi.list（后端未就绪时回落）
vi.mock('../rest', () => ({ api: vi.fn() }))
vi.mock('../mcp', () => ({ mcpApi: { list: vi.fn() } }))

/**
 * 输入框「应用选择」数据面（`selectedApps` 口径）。
 *
 * <h2>WHY（意图）</h2>
 * <ul>
 *   <li>「没动过选择器」与「显式全不选」必须可分：前者请求体**不带** `selectedApps`（后端不裁剪工具），
 *       后者带 `[]`（本轮 MCP 工具全不暴露）。两者合并 ⇒ 用户只是打开过输入框就丢了全部 MCP 工具。</li>
 *   <li>「不可用」的行要**留在列表里**并带原因（入口常驻、用状态表达），⛔ 不静默过滤 —— 否则用户
 *       以为应用没装。</li>
 * </ul>
 */

function srv(partial: Partial<McpServer> & { name: string }): McpServer {
  return {
    id: partial.name,
    command: 'npx',
    args: null,
    env: null,
    status: 'stopped',
    lastError: null,
    enabled: true,
    ...partial,
  } as McpServer
}

describe('toAppEntry / normalizeMcpEntries', () => {
  it('connected 才算可用；其余状态置灰且给中文原因', () => {
    expect(toAppEntry(srv({ name: 'notion', runtimeStatus: 'connected' })).usable).toBe(true)

    const cases: Array<[McpServer['runtimeStatus'], string]> = [
      ['needsAuth', '需要先授权'],
      ['failed', '连接失败'],
      ['pending', '等待审批'],
      ['disabled', '应用已禁用'],
      ['stopped', '未连接（启动后可用）'],
    ]
    for (const [rt, reason] of cases) {
      const o = toAppEntry(srv({ name: 'x', runtimeStatus: rt }))
      expect(o.usable, `runtimeStatus=${rt}`).toBe(false)
      // 置灰必须有原因，不许空着（⛔ 用户看不出为什么点不动）
      expect(o.unusableReason).toBe(reason)
    }
  })

  it('runtimeStatus 缺省时回落 status（旧后端兼容）', () => {
    expect(toAppEntry(srv({ name: 'a', status: 'running' })).usable).toBe(true)
    expect(toAppEntry(srv({ name: 'b', status: 'stopped' })).usable).toBe(false)
    // enabled=false 优先于 status
    expect(toAppEntry(srv({ name: 'c', status: 'running', enabled: false })).usable).toBe(false)
  })

  it('名字取 userFacingName，key 恒为 name（工具前缀口径）；类别标 mcp', () => {
    const o = toAppEntry(srv({ name: 'notion-mcp', userFacingName: 'Notion', runtimeStatus: 'connected' }))
    expect(o.key).toBe('notion-mcp')
    expect(o.name).toBe('Notion')
    // 应用条目不止 MCP（浏览器 / computer-use 后续加）；本轮条目必须自带类别，供过滤判据分流
    expect(o.kind).toBe('mcp')
  })

  it('目录去重（回落路径按名字兜底排序）', () => {
    const list = normalizeMcpEntries([
      srv({ name: 'b', runtimeStatus: 'stopped' }),
      srv({ name: 'a', runtimeStatus: 'connected' }),
      srv({ name: 'a', runtimeStatus: 'connected' }),
      srv({ name: 'c', runtimeStatus: 'connected' }),
    ])
    expect(list.map((x) => x.key)).toEqual(['a', 'b', 'c'])
  })
})

describe('toggleAppKey', () => {
  it('不可变切换：加 / 删都返回新数组', () => {
    const base = ['a']
    expect(toggleAppKey(base, 'b')).toEqual(['a', 'b'])
    expect(toggleAppKey(base, 'a')).toEqual([])
    // 原数组不被改（整表替换语义）
    expect(base).toEqual(['a'])
  })
})

describe('后端应用目录（GET /api/v1/apps）', () => {
  beforeEach(() => {
    vi.mocked(api).mockReset()
    vi.mocked(mcpApi.list).mockReset()
  })
  const dto = (over: Partial<AppDto> & { key: string; kind: AppDto['kind'] }): AppDto => ({
    name: over.key,
    ...over,
  })

  it('⭐ 顺序 = 后端返回顺序（内置 CU→浏览器 在最前，前端不重排）', () => {
    const list = normalizeAppEntries([
      dto({ key: 'computer-use', kind: 'computer-use', name: '桌面', toolCount: 5, usable: true, defaultOpen: false }),
      dto({ key: 'nexusai-in-chrome', kind: 'browser', name: '浏览器', toolCount: 12, usable: true, defaultOpen: true }),
      dto({ key: 'notion', kind: 'mcp', name: 'Notion', toolCount: 9, usable: true, defaultOpen: true }),
    ])
    expect(list.map((a) => a.key)).toEqual(['computer-use', 'nexusai-in-chrome', 'notion'])
    expect(list.map((a) => APP_KIND_LABEL[a.kind])).toEqual(['桌面', '浏览器', 'MCP'])
    expect(list[1].toolCount).toBe(12)
    // 用户裁定：浏览器/桌面 flag 默认 false ⇒ 默认 OFF，但**仍可点**（由后端 usable 决定）
    expect(list[0].defaultOpen).toBe(false)
    // 反向实验：换一个「非 kind 序」的后端顺序也必须原样保留（证明前端真的没重排）
    const odd = normalizeAppEntries([
      dto({ key: 'notion', kind: 'mcp', name: 'Notion' }),
      dto({ key: 'nexusai-in-chrome', kind: 'browser', name: '浏览器' }),
      dto({ key: 'computer-use', kind: 'computer-use', name: '桌面' }),
    ])
    expect(odd.map((a) => a.key)).toEqual(['notion', 'nexusai-in-chrome', 'computer-use'])
  })

  it('接口缺失（MCP 回落）时才按 kind/名字兜底排序', () => {
    const list = normalizeMcpEntries([
      srv({ name: 'zeta', runtimeStatus: 'connected' }),
      srv({ name: 'alpha', runtimeStatus: 'connected' }),
    ])
    expect(list.map((a) => a.key)).toEqual(['alpha', 'zeta'])
  })

  it('后端给了 tools 原始名清单 ⇒ 透传（面板可展开查看）', () => {
    const list = normalizeAppEntries([
      dto({ key: 'codegraph', kind: 'mcp', name: 'CodeGraph', usable: true, toolCount: 2, tools: ['mcp__codegraph__explore', 'mcp__codegraph__node'] }),
      dto({ key: 'computer-use', kind: 'computer-use', name: '桌面', usable: false, disabledReason: '功能未开启', tools: ['mcp__computer-use__screenshot'] }),
    ])
    expect(list.find((a) => a.key === 'codegraph')!.tools).toEqual(['mcp__codegraph__explore', 'mcp__codegraph__node'])
    // 不可用的应用也要带工具清单（用户要求「不可用行也可展开看工具」）
    expect(list.find((a) => a.key === 'computer-use')!.tools).toEqual(['mcp__computer-use__screenshot'])
    // 没给 tools 的条目 = 不可展开（⛔ 不造假空数组）
    expect(normalizeAppEntries([dto({ key: 'x', kind: 'mcp', name: 'X', tools: [] })])[0].tools).toBeUndefined()
  })

  it('usable=false ⇒ 原样显示后端给的原因（功能未开启 / 未连接），⛔ 不自造原因', () => {
    const list = normalizeAppEntries([
      dto({ key: 'computer-use', kind: 'computer-use', name: '桌面', usable: false, disabledReason: '功能未开启' }),
      dto({ key: 'weak-mcp', kind: 'mcp', name: '离线服务', usable: false, disabledReason: '未连接' }),
    ])
    for (const a of list) {
      expect(a.usable).toBe(false)
      expect(a.defaultOpen).toBe(false)
    }
    expect(list.find((a) => a.key === 'computer-use')!.unusableReason).toBe('功能未开启')
    expect(list.find((a) => a.key === 'weak-mcp')!.unusableReason).toBe('未连接')
  })

  it('fetchAppEntries 优先用后端目录（{apps:[…]} 形状也认）', async () => {
    vi.mocked(api).mockResolvedValue({
      apps: [dto({ key: 'nexusai-in-chrome', kind: 'browser', name: '浏览器', usable: true, defaultOpen: false })],
    })
    const list = await fetchAppEntries()
    expect(api).toHaveBeenCalledWith('/apps')
    expect(list.map((a) => a.key)).toEqual(['nexusai-in-chrome'])
    expect(mcpApi.list).not.toHaveBeenCalled()
  })

  it('后端未就绪（抛错）⇒ 回落 MCP 列表；⛔ 不伪造静态应用', async () => {
    vi.mocked(api).mockRejectedValue(new Error('404'))
    vi.mocked(mcpApi.list).mockResolvedValue([srv({ name: 'notion', runtimeStatus: 'connected' })])
    const list = await fetchAppEntries()
    expect(list.map((a) => a.key)).toEqual(['notion'])
    expect(list.every((a) => a.kind === 'mcp')).toBe(true)
    expect(list.some((a) => a.key === 'nexusai-in-chrome')).toBe(false)
  })

  it('后端就绪但返回空数组 ⇒ 就是空（不再回落，避免掩盖后端错误）', async () => {
    vi.mocked(api).mockResolvedValue([])
    const list = await fetchAppEntries()
    expect(list).toEqual([])
    expect(mcpApi.list).not.toHaveBeenCalled()
  })
})

describe('强调集合的 UI 记忆（按 sessionId 隔离）', () => {
  afterEach(() => { localStorage.clear() })

  it('按会话隔离：A 的强调不会出现在 B；无记忆为 null', () => {
    saveRememberedApps('sess-a', ['notion'])
    expect(loadRememberedApps('sess-a')).toEqual(['notion'])
    expect(loadRememberedApps('sess-b')).toBeNull()
  })

  it('无会话 / 空 sessionId ⇒ 不读不写（不造全局键）', () => {
    saveRememberedApps(null, ['x'])
    expect(loadRememberedApps(null)).toBeNull()
    expect(loadRememberedApps('')).toBeNull()
    expect(localStorage.getItem('nexusai.composer.selectedApps.v3:null')).toBeNull()
  })

  it('清空 ⇒ 删键（不是存一个空数组）', () => {
    saveRememberedApps('sess-a', ['notion'])
    forgetRememberedApps('sess-a')
    expect(loadRememberedApps('sess-a')).toBeNull()
    expect(localStorage.getItem(appSelectionStorageKey('sess-a'))).toBeNull()
  })
})
