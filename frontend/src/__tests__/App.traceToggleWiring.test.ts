import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 轨迹视图必须有「回到对话」的代码路径（**源码级**断言）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 用户实测缺陷）</b>：{@code centerView} 只有两个值，而旧实现
 * {@code handleWorkspaceTrace} 在 trace 分支<b>只重试拉全量就 return</b> —— 全仓
 * {@code setCenterView} 调用点只有「初始 'chat'」与「进入 'trace'」两处，<b>没有任何一处切回
 * 'chat'</b>。于是用户进了轨迹就出不来（原话：「点击轨迹后没法点击回到会话，点击轨迹按钮没用」），
 * 而按钮 tooltip 明明写着「返回对话」。
 *
 * <p><b>为什么是源码断言而不是行为测试</b>：App.tsx 是一个巨型组件，用 jsdom 挂起来要把 settings /
 * chat socket / 会话列表全部 mock 掉，测试成本远大于被测逻辑（一个三元 toggle）。而这条接线的失效
 * 方式是<b>静默的</b>：删掉 'chat' 分支后编译通过、所有行为测试照样全绿，只有用户点下去没反应。
 * 本仓已有同款先例：{@code ErrorBoundaryWiring.test.ts} / {@code frontLogInstalled.test.ts}。
 *
 * <p>⚠️ 源码断言拦不住「保留 'chat' 字样但写进死代码分支」这类改法；它守的是「回路被整段删掉」
 * 这一实际发生过的回归形态（原始缺陷就是完全缺失该分支）。
 */

const APP_TSX = fileURLToPath(new URL('../App.tsx', import.meta.url))

function appSource(): string {
  return readFileSync(APP_TSX, 'utf8').replace(/\r\n?/g, '\n')
}

/** 取 `handleWorkspaceTrace` 的实现体（从声明行起的 12 行，足够覆盖整个 useCallback）。 */
function traceHandlerBody(src: string): string {
  const lines = src.split('\n')
  const i = lines.findIndex((l) => /const handleWorkspaceTrace\s*=/.test(l))
  return i < 0 ? '' : lines.slice(i, i + 12).join('\n')
}

describe('App 轨迹视图回路（源码级）', () => {
  it('handleWorkspaceTrace 存在', () => {
    expect(traceHandlerBody(appSource()), '找不到 handleWorkspaceTrace（接线被整体删除？）').not.toBe('')
  })

  it("⭐ 必须能切回 'chat'（否则进了轨迹就出不来）", () => {
    const body = traceHandlerBody(appSource())
    // 注：vitest 的 expect 是 chai 底子，`.toMatch()` 不支持 `.as()`（会抛 Invalid Chai property）
    expect(/'chat'/.test(body), "轨迹按钮在 trace 态的语义是「返回对话」；实现里必须有回到 'chat' 的路径").toBe(true)
  })

  it('切回路径由一个会翻转的 setState 驱动（不是只读判定后 return）', () => {
    const body = traceHandlerBody(appSource())
    expect(/setCenterView\s*\(/.test(body), '必须调用 setCenterView').toBe(true)
    // 函数式更新 `setCenterView((v) => ...)` 或直接传 'chat' 都可以，但必须真的写 state
    expect(
      /setCenterView\s*\(\s*(\(|'chat'|"chat")/.test(body),
      '不能只有 return 而没有写 state（正是原始缺陷的形态）',
    ).toBe(true)
  })
})
