/**
 * marked 全局配置 · 照搬源站。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/chatMarkdownRenderer.ts 的
 * `configureMarkedForChatMarkdown()`。
 *
 * <h2>三个配置项各自的理由</h2>
 * <ul>
 *   <li><b>{@code breaks: true}</b> —— 单个换行即换行。聊天场景里用户和模型都习惯
 *       用单换行分段，不开这个会整段挤成一行。</li>
 *   <li><b>{@code gfm: true}</b> —— 表格 / 删除线 / 任务列表。知识库答案大量用表格。</li>
 *   <li><b>{@code ensureLiteralSingleTildeOnGlobalMarked()}</b> —— GFM 把单个 `~`
 *       也当删除线，而中文技术文档里 `~` 常表「约」（`3~5 天`）⇒ 必须保护。</li>
 *   <li><b>{@code markedKatex({ throwOnError: false })}</b> —— 公式渲染；
 *       {@code throwOnError:false} 让非法公式降级成原文而不是抛异常打断整篇渲染。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么必须「只配置一次」</h2>
 * `marked.use()` 每次调用都会**再包一层** tokenizer。组件每挂载一次就调一次的话，
 * 几百次挂载后 tokenizer 链会变得极长（性能崩塌），且行为可能漂移。
 * ⇒ 用模块级布尔守卫，进程内只配置一次。
 */
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'

import { ensureLiteralSingleTildeOnGlobalMarked } from './markedLiteralTilde'

let markedConfigured = false

/**
 * 配置全局 `marked` 实例（幂等）。
 *
 * ⚠️ 幂等靠模块级布尔量，**不是**靠 `marked` 自己 —— 它没有「已配置」状态。
 */
export function configureMarkedForChatMarkdown(): void {
  if (markedConfigured) return
  marked.use({ breaks: true, gfm: true })
  ensureLiteralSingleTildeOnGlobalMarked()
  marked.use(markedKatex({ throwOnError: false, nonStandard: true }))
  markedConfigured = true
}

/**
 * 仅供测试：重置「已配置」标记。
 *
 * <p>⛔ 生产代码不要调用 —— 配置一旦装上就不该撤销（撤销也没有对应 API）。
 */
export function __resetMarkedConfiguredForTest(): void {
  markedConfigured = false
}
