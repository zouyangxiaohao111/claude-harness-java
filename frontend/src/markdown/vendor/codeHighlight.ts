/**
 * 代码高亮 · 逐字照抄源站。
 *
 * 来源：WeKnora（pinned 80e8345）frontend/src/utils/mermaidShared.ts:171 `highlightCode`。
 *
 * <h2>⚠️ 为什么用 highlight.js 而不是本仓原有的 shiki</h2>
 * 用户 2026-11-11 裁定：「所有跟 V1 相同的我都不认可，你都 V2 了 就抄源侧就行」。
 * V1 用 shiki，源站用 highlight.js ⇒ V2 照抄源站用 highlight.js。
 * （代价：多一个依赖、配色与 V1 不同；这是明确选择的取舍。）
 *
 * <h2>兜底策略（源站原样）</h2>
 * 语言已知 → 精确高亮；语言未知/高亮失败 → `highlightAuto` 自动识别。
 * ⇒ 任何代码块都能出高亮，不会掉回纯白。
 */
import hljs from 'highlight.js'

/**
 * 高亮一段代码。
 *
 * @param text 源码
 * @param lang 语言标识（fence info 的第一段）
 * @returns html = 高亮后的 HTML 片段；language = 实际使用的语言
 */
export function highlightCode(
  text: string,
  lang?: string | null,
): { html: string; language: string } {
  const language = (lang || '').trim()
  if (language && hljs.getLanguage(language)) {
    try {
      const result = hljs.highlight(text, { language })
      return { html: result.value, language: result.language || language }
    } catch {
      // fall through
    }
  }
  const auto = hljs.highlightAuto(text, language ? [language] : undefined)
  return { html: auto.value, language: auto.language || language || 'plaintext' }
}
