import { describe, expect, expectTypeOf, it } from 'vitest'
import { mergeSettings } from '@/api/settingsMerge'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

/**
 * [appName 通道 追加批] `mergeSettings` —— appSettings 的**唯一合并助手**。
 *
 * <b>WHY 这组断言必须存在（规则九 · 测试验证意图）</b>：
 *   `GET /settings` 是唯一透出只读 `appName` / `configHome` 的响应；`PUT /settings` 的响应契约是后端
 *   `SettingsDto`（**不含**这两个字段）。任何把 PUT 响应直接写进 state 的路径都会把 `appName` 抹成
 *   `undefined` ⇒ `PermissionBubble.selfDirName` 变 null ⇒ 自有根档位文案**静默**退化回 `.nexusai/`。
 *   这个失效**没有任何报错**，只有断言能钉住。
 *
 * ⭐ 不变量：`incoming` 不含 appName 时，结果除两个只读键外必须与 `incoming` 逐字相同
 *    （即「拿不到 appName 时行为与合并逻辑引入前一致」）。
 */

/**
 * AppSettings 的 18 个必填字段基底（测试夹具 · 用 `as AppSettings` 收口可变覆盖，
 * 与 SettingsModal.headersToggle.test.tsx 的夹具写法一致）。
 */
const base = (over: Partial<AppSettings> = {}): AppSettings => ({
  theme: 'dark',
  fontSize: 'medium',
  accent: null,
  animationsEnabled: true,
  mainModelName: null,
  fastModelName: null,
  weakModelName: null,
  mediumModelName: null,
  strongModelName: null,
  subagentModelName: null,
  autoCompactWindow: null,
  maxOutputTokens: null,
  fallbackModelName: null,
  multimodalModelName: null,
  ttsModelName: null,
  asrModelName: null,
  autoMemoryEnabled: null,
  autoMemoryDirectory: null,
  ...over,
}) as AppSettings

/** 一次真实 GET /settings 响应（含只读字段）的等价物 */
const GET = (over: Partial<AppSettings> = {}): AppSettings =>
  base({ appName: 'nexusai-scene', configHome: 'C:\\Users\\x\\.nexusai-scene', ...over })

/** 一次真实 PUT /settings 响应（后端 SettingsDto ⇒ **不含** appName / configHome） */
const PUT = (over: Partial<AppSettings> = {}): AppSettings =>
  base({ mainModelName: 'ds-openai/deepseek-v4-flash', fastModelName: 'ds-openai/deepseek-v4-flash', ...over })

describe('[appName 通道 追加批] mergeSettings · 只读字段以 prev 兜底、incoming 优先', () => {
  it('⭐ F1 纯核心：incoming 缺 appName ⇒ 取 prev（PUT 回流不得抹掉场景 appName）', () => {
    const merged = mergeSettings(GET(), PUT())
    expect(merged.appName).toBe('nexusai-scene')
    expect(merged.configHome).toBe('C:\\Users\\x\\.nexusai-scene')
  })

  it('⭐ incoming 带 appName ⇒ 取 incoming（GET 刷新时允许场景真变）', () => {
    const merged = mergeSettings(GET({ appName: 'nexusai' }), GET({ appName: 'nexusai-scene' }))
    expect(merged.appName).toBe('nexusai-scene')
    expect(merged.configHome).toBe('C:\\Users\\x\\.nexusai-scene')
  })

  it('取 incoming 的**非只读**字段（其余字段完全由 incoming 决定，不额外兜底遮挡）', () => {
    const merged = mergeSettings(GET({ theme: 'light' }), PUT({ theme: 'dark', fontSize: 'large' }))
    expect(merged.theme).toBe('dark')
    expect(merged.fontSize).toBe('large')
    expect(merged.mainModelName).toBe('ds-openai/deepseek-v4-flash')
  })

  it('prev=null（首载前）且 incoming 缺只读字段 ⇒ 两个只读键恒为 null（不是 undefined）', () => {
    const merged = mergeSettings(null, PUT())
    expect(merged.appName).toBeNull()
    expect(merged.configHome).toBeNull()
    expect('appName' in merged).toBe(true)
    expect('configHome' in merged).toBe(true)
  })

  it('incoming 显式 appName:null ⇒ 仍回落 prev（后端没有「把 appName 清空」的语义）', () => {
    const merged = mergeSettings(GET(), PUT({ appName: null, configHome: null }))
    expect(merged.appName).toBe('nexusai-scene')
    expect(merged.configHome).toBe('C:\\Users\\x\\.nexusai-scene')
  })

  it('⭐ 不变量：prev 拿不到 appName 时，结果除两个只读键外与 incoming 逐字段相同', () => {
    // 「拿不到 appName」（首装 / 后端未透出）的等价形态
    for (const prev of [null, {} as AppSettings, { appName: null } as AppSettings]) {
      const incoming = PUT()
      const merged = mergeSettings(prev, incoming)
      for (const [k, v] of Object.entries(incoming)) {
        expect(merged[k as keyof AppSettings], `字段 ${k} 被合并逻辑改动了`).toEqual(v)
      }
      expect(merged.appName).toBeNull()
      expect(merged.configHome).toBeNull()
    }
  })

  it('合并是纯函数：不改动入参对象（避免埋下共享引用）', () => {
    const prev = GET()
    const incoming = PUT()
    const prevSnapshot = JSON.stringify(prev)
    const incomingSnapshot = JSON.stringify(incoming)
    mergeSettings(prev, incoming)
    expect(JSON.stringify(prev)).toBe(prevSnapshot)
    expect(JSON.stringify(incoming)).toBe(incomingSnapshot)
    expect(mergeSettings(prev, incoming)).not.toBe(prev)
  })
})

describe('[appName 通道 追加批 · F2] UpdateSettingsRequest 在类型层不再接受只读字段', () => {
  /**
   * 编译期断言（`expectTypeOf` 在运行期是 no-op，由 `npx tsc --noEmit` 实际校验）：
   * 只读字段若哪天被放回 PUT 请求类型，本用例会**在 tsc 阶段**报错 —— 这正是要的
   * （后端会静默忽略回传值 ⇒ 运行期无信号，只有类型层能封死）。
   */
  it('appName / configHome 已从 PUT 请求类型剔除', () => {
    // 收紧后再取这两个键 ⇒ never。放宽回 Partial<AppSettings> 时这里会变成
    // 'appName' | 'configHome'，toBeNever 直接在 tsc 阶段报错（错误串里能看到实际类型）。
    expectTypeOf<Extract<keyof UpdateSettingsRequest, 'appName' | 'configHome'>>().toBeNever()
  })

  it('正向对照：可写字段仍在（否则上面的断言可能因整体收窄而恒真）', () => {
    expectTypeOf<
      Extract<keyof UpdateSettingsRequest, 'mainModelName' | 'allowDynamicHeaderValues' | 'autoCompactWindow'>
    >().not.toBeNever()
  })
})
