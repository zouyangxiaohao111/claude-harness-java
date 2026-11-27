/**
 * 正文链接的解析上下文（独立文件，供渲染器与消息列表共用）。
 *
 * <p><b>WHY 不放在 renderer.tsx</b>：多处测试会把 `@/markdown/vendor/renderer` 整体 mock
 * （只给 `MarkdownTextV2`），Provider 若也从那里导出，MessageList 一引用就报
 * `No "LocalLinkProvider" export is defined on the mock`。上下文与渲染实现解耦后，
 * 两边的依赖互不影响。
 */
import { createContext } from 'react'

/** 打开失败的形态（`opened` 不算失败）。 */
export type LinkOpenFailure = 'not-found' | 'too-large' | 'unsupported' | 'failed'

export interface LocalLinkOptions {
  /** 相对路径的解析基准（按顺序探测；用户 2026-11-27 裁定：交付物目录 → 项目根） */
  baseDirs: readonly string[]
  /** 打不开时的用户提示（App 注入 toast；不注入则只 console.warn） */
  onUnresolved?: (href: string, reason: LinkOpenFailure) => void
}

export const LocalLinkContext = createContext<LocalLinkOptions>({ baseDirs: [] })

/** 为对话正文注入相对路径解析基准（MessageList 使用；其它宿主可不包）。 */
export const LocalLinkProvider = LocalLinkContext.Provider