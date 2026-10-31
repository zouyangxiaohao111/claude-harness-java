// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import type { PermissionRequestItem } from '@/stores/chatStore'
import type { ComputerUseApproval } from '@/api/types'

/**
 * [桌面控制审批面板] 组件层。
 *
 * <b>WHY 这组断言必须存在</b>：
 *   1. <b>授权范围必须可见</b>：用户点「允许」= 授权模型控制这些应用 —— 他必须先看到
 *      「哪些应用」+「能控制到什么程度」。看不到就是盲授权。
 *   2. <b>⛔ fail-loud</b>：桌面控制的授权请求若<b>没带</b>授权信息，绝不能回落普通
 *      allow/deny 分支 —— 那样用户点「允许」时授权位会被静默置成全 false（以为授了、实际没授）。
 *      ⇒ 必须**不出现「允许」按钮**且**出现错误提示**。
 *   3. <b>整组授予</b>：点「允许」只回传 allow（不带逐位勾选）—— 与上游「整组授予」语义一致。
 *
 * 环境写法照 permissionSuggestions.test.tsx（jsdom + react-dom/client + act）。
 */

const CU_TOOL = 'mcp__computer-use__request_access'

/** 后端 ComputerUseApprovalPayload 的真实形状 */
const cuPayload: ComputerUseApproval = {
  apps: [
    { appId: 'chrome', displayName: 'Chrome', tier: 'read', tierLabel: '只能查看', alreadyGranted: false, notInstalled: false },
    { appId: 'notepad', displayName: '记事本', tier: 'full', tierLabel: '可以完全操作', alreadyGranted: true, notInstalled: false },
    { appId: 'missing', displayName: 'SomeApp', tier: 'click', tierLabel: '可以点击和滚动', alreadyGranted: false, notInstalled: true },
  ],
  reason: '帮我看看 Chrome 里的报表',
  requestedFlags: ['clipboardRead'],
  flagLabels: { clipboardRead: '允许读取剪贴板' },
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(el: React.ReactElement) {
  act(() => root.render(el))
}

function req(over: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return { kind: 'message', sessionId: 's1', requestId: 'r1', toolName: CU_TOOL, ...over }
}

function buttons(): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button'))
}

describe('[桌面控制] 授权面板渲染', () => {
  it('computerUse 存在 ⇒ 渲染应用清单 + 每个应用的授权档位 + 被请求的额外能力', () => {
    render(<PermissionBubble request={req({ computerUse: cuPayload })} onDecision={vi.fn()} />)

    // 应用名都渲染出来
    const text = container.textContent ?? ''
    expect(text).toContain('Chrome')
    expect(text).toContain('记事本')
    expect(text).toContain('SomeApp')

    // ⭐ 授权档位（用户裁定要显示）· 后端给的 tierLabel 原样渲染
    expect(text).toContain('只能查看')
    expect(text).toContain('可以完全操作')
    expect(text).toContain('可以点击和滚动')

    // 已授权 / 未安装 标注
    expect(text).toContain('已授权')
    expect(text).toContain('未安装')

    // 被请求的额外能力（只渲染被请求的）
    expect(text).toContain('允许读取剪贴板')

    // 模型给的理由
    expect(text).toContain('帮我看看 Chrome 里的报表')
  })

  it('⛔ 文案纪律：面板里不得出现内部术语或档位机器值', () => {
    render(<PermissionBubble request={req({ computerUse: cuPayload })} onDecision={vi.fn()} />)
    const text = container.textContent ?? ''
    // 机器档位值 read/click/full 不得出现在用户可见文案里
    expect(text).not.toMatch(/\b(read|click|full)\b/)
    expect(text).not.toMatch(/computer-use|MCP|对齐\s*CC/i)
    // 未被请求的授权位不得渲染（只渲染 requestedFlags 里被请求的）
    expect(text).not.toContain('允许写入剪贴板')
    expect(text).not.toContain('允许发送系统级快捷键组合')
  })

  it('⛔ 不得把后端的工具名 / 英文描述 / 决策归因透出到面板（内部文本）', () => {
    // 后端对 CU 请求给的 description / reason 是**内部英文**（工具描述 + "Computer use wants to
    // control ..." 归因串）⇒ 面板只能渲染 computerUse 里的内容，绝不能带出 request.description /
    // request.reason / toolName。
    render(<PermissionBubble
      request={req({
        computerUse: cuPayload,
        toolName: 'mcp__computer-use__request_access',
        description: 'Request user permission to control a set of applications for this session.',
        reason: { reason: 'Computer use wants to control Chrome. Reason: 帮我看报表' },
      })}
      onDecision={vi.fn()}
    />)
    const text = container.textContent ?? ''
    expect(text).not.toContain('Request user permission to control')
    expect(text).not.toContain('Computer use wants to control')
    expect(text).not.toContain('mcp__computer-use__request_access')
    // 但模型给用户看的那句理由（computerUse.reason）必须出现
    expect(text).toContain('帮我看看 Chrome 里的报表')
  })

  it('点「允许」⇒ 只回传 allow（整组授予，不带逐位勾选）', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ computerUse: cuPayload })} onDecision={onDecision} />)

    const allow = buttons().find((b) => b.textContent === '允许')!
    act(() => allow.click())

    expect(onDecision).toHaveBeenCalledTimes(1)
    const [id, decision] = onDecision.mock.calls[0]
    expect(id).toBe('r1')
    expect(decision).toBe('allow')
    // 不带 answers / annotations / permissionUpdates（无逐位勾选、无规则）
    expect(onDecision.mock.calls[0][2]).toBeUndefined()
    expect(onDecision.mock.calls[0][4]).toBeUndefined()
  })

  it('点「拒绝」⇒ 回传 deny', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ computerUse: cuPayload })} onDecision={onDecision} />)

    const deny = buttons().find((b) => b.textContent === '拒绝')!
    act(() => deny.click())

    expect(onDecision).toHaveBeenCalledWith('r1', 'deny')
  })
})

describe('[桌面控制] ⛔ fail-loud：授权信息缺失 ⇒ 不得回落普通分支放行', () => {
  it('computerUse 缺失但 toolName 是桌面控制请求 ⇒ 不出现「允许」按钮 + 显示错误', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ toolName: CU_TOOL })} onDecision={onDecision} />)

    // ⛔ 没有「允许」按钮（不得盲放行）
    expect(buttons().map((b) => b.textContent)).not.toContain('允许')
    // 有「拒绝」按钮（用户仍可拒绝以解除等待）
    expect(buttons().map((b) => b.textContent)).toContain('拒绝')
    // 有错误提示
    expect(container.querySelector('.pb-cu-error')).toBeTruthy()
    expect(container.textContent).toContain('无法确认这次要授权的范围')

    warn.mockRestore()
  })

  it('computerUse 为 null（后端显式给 null）⇒ 同样走 fail-loud，不回落普通分支', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<PermissionBubble request={req({ toolName: CU_TOOL, computerUse: null })} onDecision={vi.fn()} />)
    expect(buttons().map((b) => b.textContent)).not.toContain('允许')
    expect(container.querySelector('.pb-cu-error')).toBeTruthy()
    warn.mockRestore()
  })

  it('回归：非桌面控制请求（toolName 不匹配）⇒ 仍是普通 allow/deny 弹窗', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ toolName: 'Bash' })} onDecision={onDecision} />)
    const labels = buttons().map((b) => b.textContent)
    expect(labels).toContain('允许')
    expect(labels).toContain('拒绝')
    expect(container.querySelector('.pb-cu-error')).toBeFalsy()
  })
})
