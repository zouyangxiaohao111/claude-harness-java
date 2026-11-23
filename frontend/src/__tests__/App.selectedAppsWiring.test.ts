import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 「应用选择」最终接线（**源码级**断言 · 与 `App.knowledgeBaseWiring.test.ts` 同款）。
 *
 * <h2>WHY（2026-11-23 终稿：启动层级 + 星标强调）</h2>
 * <ul>
 *   <li>行开关 = **启动/停止** → 写会话 `enabledApps`（PATCH，照 coordinatorMode）；启动才进工具 schema。</li>
 *   <li>星标 = **本轮强调** → 只写发送体 `selectedApps`，⛔ 不改 schema、不持久化到会话。</li>
 *   <li>初始启动集 = 会话 `enabledApps ?? defaultOpen 集合`。</li>
 * </ul>
 * 这三条少一条都会静默错配（例如「强调」写成 PATCH ⇒ 缓存按星标分裂）。
 */

const APP_TSX = fileURLToPath(new URL('../App.tsx', import.meta.url))
const TYPES_TS = fileURLToPath(new URL('../api/types.ts', import.meta.url))

function src(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

describe('应用选择 · 启动/强调双动作接线（源码级）', () => {
  it('启动集读会话（enabledApps），行开关 PATCH 会话', () => {
    const app = src(APP_TSX)
    expect(app).toContain('enabledApps={activeSession?.enabledApps ?? null}')
    expect(app).toContain('onEnabledAppsChange={handleEnabledAppsChange}')
    expect(app).toMatch(/const handleEnabledAppsChange = useCallback\(async \(keys: string\[\] \| null\) => \{/)
    expect(app).toMatch(/sessionApi\.update\(activeSessionId, \{ enabledApps: keys \}\)/)
  })

  it('⭐ 星标强调只进发送体（selectedApps），⛔ 不 PATCH 会话', () => {
    const app = src(APP_TSX)
    expect(app).toMatch(/const appsSel = selectedAppsRef\.current/)
    expect(app).toMatch(/\.\.\.\(appsSel \? \{ selectedApps: appsSel \} : \{\}\)/)
    // 会话 PATCH 里不得出现 selectedApps
    expect(app).not.toContain('selectedApps: keys')
  })

  it('强调集合按会话隔离：切换会话从该会话记忆恢复（仅 UI），发送时用内存态', () => {
    const app = src(APP_TSX)
    // 切换会话 ⇒ 从该 sessionId 的记忆恢复
    expect(app).toMatch(/useEffect\(\(\) => \{\s*setSelectedApps\(loadRememberedApps\(activeSessionId\)\)\s*\}, \[activeSessionId\]\)/)
    // 变化时写该会话的记忆；清空则删
    expect(app).toMatch(/if \(keys\) saveRememberedApps\(activeSessionId, keys\)\s*else forgetRememberedApps\(activeSessionId\)/)
  })

  it('契约：SessionDto/SessionUpdateRequest 有 enabledApps；SendMessageRequest 有 selectedApps', () => {
    const types = src(TYPES_TS)
    const sessionDto = types.slice(types.indexOf('export interface SessionDto'), types.indexOf('export type SessionGroup'))
    expect(sessionDto).toContain('enabledApps?: string[] | null')
    expect(sessionDto).not.toMatch(/selectedApps\?:/)  // 强调不落会话（字段声明层面）

    const updateStart = types.indexOf('export interface SessionUpdateRequest')
    const updateLine = types.slice(updateStart, types.indexOf('\n', updateStart))
    expect(updateLine).toContain('enabledApps?: string[] | null')
    expect(updateLine).not.toContain('selectedApps')

    const sendBlock = types.slice(types.indexOf('export interface SendMessageRequest'), types.indexOf('export interface QueuePopResponse'))
    expect(sendBlock).toContain('selectedApps?: string[]')
  })
})
