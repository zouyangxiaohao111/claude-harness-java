/**
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/markedLiteralTilde.ts，逻辑逐行照搬。
 *
 * 为什么需要：GFM 把单个 `~` 也当作删除线标记，中文技术文档里 `~` 常用来表示"约"或区间
 * （如 `2020~2035`），一旦被误判成删除线就会把正文当成删除文本吃掉。
 * 本扩展让单个 `~` 原样渲染为普通字符，只有成对的 `~~双波浪~~` 才是删除线。
 */
import { marked, type MarkedExtension } from 'marked'

// marked's GFM `del` tokenizer matches a lone `~` (e.g. the range
// `2020~2035`), turning it into strikethrough. Override it so a `~~`-prefixed
// run returns `false` and falls back to the built-in rule, while anything
// else returns `undefined` and renders the `~` as literal text.
export const literalSingleTildeExtension: MarkedExtension = {
  tokenizer: {
    del(src: string) {
      if (src.startsWith('~~')) return false
      return undefined
    },
  },
}

let registeredOnGlobalMarked = false

// Every `marked.use` call wraps the global tokenizer once more, so components
// that share the global `marked` must not register this per mount.
export function ensureLiteralSingleTildeOnGlobalMarked(): void {
  if (registeredOnGlobalMarked) return
  marked.use(literalSingleTildeExtension)
  registeredOnGlobalMarked = true
}
