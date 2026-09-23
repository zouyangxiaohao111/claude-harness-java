/**
 * appSettings 的**唯一合并助手**（★ 根修：不要「到处记得合并」）——
 * 把一次 settings 响应（incoming）合进旧值（prev）。
 *
 * <p><b>为什么必须有它</b>：`GET /settings` 是**唯一**透出只读 `appName` / `configHome` 的响应；
 * `PUT /settings` 的响应契约是后端 `SettingsDto`（**不含**这两个字段 —— 见
 * `SettingsController.update` 的返回类型是 `SettingsDto` 而非 `SettingsResponse`）。
 * 若把 PUT 响应直接写进 `appSettings` state，只读字段会被抹成 `undefined` ⇒
 * `PermissionBubble.selfDirName` 变 null ⇒ 自有根档位文案**静默**退化回 `.nexusai/`
 * （场景发行 `appName=nexusai-scene` 时就是错的，且**无任何报错**）。
 *
 * <p><b>为什么是「助手」而不是「每处记得合并」</b>：写 appSettings 的路径不止一条
 * （GET 首载 / 设置弹窗保存 / 档位一键套用），逐处手写合并必然漏 —— 本仓刚因
 * 「同一修法只落地 1/2 处」返工过。故收敛成本函数，并由 `App.applyAppSettings` 做
 * **state 的唯一写入口**：所有路径只经它写入。
 *
 * <p><b>为什么单独一个模块（而不是并进 {@link ./settings}）</b>：本模块是<b>纯函数</b>，
 * 与 REST 传输无关；而 `@/api/settings` 是渲染真 App 的测试会**整体 mock** 的传输边界
 * （见 `App.serverRunningReconcile.test.tsx`）。把纯逻辑放进被整体 mock 的模块里，
 * 会让「合并」在那些测试里一并变成 mock —— 接线守门测试就失真了。
 *
 * <p><b>规则</b>：只读字段 `incoming` 优先、`prev` 兜底（GET 刷新时允许场景**真变**）；
 * 其余字段完全由 `incoming` 决定 —— 不在这里重列字段清单，避免与 `AppSettings` 漂移。
 *
 * @param prev 现有 state（首次加载前为 null）
 * @param incoming 一次 GET / PUT 的响应体
 * @returns 合并后的新 state（`appName` / `configHome` 恒为 `string | null`）
 */
import type { AppSettings } from './types'

export function mergeSettings(prev: AppSettings | null, incoming: AppSettings): AppSettings {
  return {
    ...incoming,
    appName: incoming.appName ?? prev?.appName ?? null,
    configHome: incoming.configHome ?? prev?.configHome ?? null,
  }
}
