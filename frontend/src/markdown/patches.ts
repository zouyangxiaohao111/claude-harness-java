/**
 * 对话正文的「脏输入修正」预处理（自 MessageList 迁出的纯函数）。
 *
 * 只在 settled 全量渲染前对整段文本跑一次（见 MarkdownText.renderSettled）；
 * 流式增量渲染（尾窗 verbatim-slice）**不跑**——这些变换是全局长度改写，
 * 会破坏 IncrementalMarkdownParser 的「前缀原样」不变量。代价是
 * `##核心`/粘连表/未闭合代码在流式预览与结束态之间允许一次「终跳」
 * （收口时 blk→正式消息本身就是整树 remount），属有意取舍（测试已 pin）。
 */

/** marked 18 实测对【无空格】ATX 标题（`##核心`）不解析（输出 <p> 字面文本），
 *  必须 `## 核心` 才渲染 <h2> → 预处理给标题补空格（排除代码块 shebang `#!`）。 */
export function fixHeadings(src: string): string {
  return src.replace(/^(#{1,6})([^\s#!][^\n]*)$/gm, (_m, hashes: string, text: string) => {
    return `${hashes} ${text}`
  })
}

/** 修复「标题与表格表头粘连」行（模型偶发 `##标题|表头|` 后跟 `|---|` 分隔行）→ 拆为标题 + 表头，
 *  使解析器能识别为表格（实测：粘连格式会整段当纯文本渲染）。 */
export function fixAdheredTables(src: string): string {
  return src.replace(/^(#{1,6}[^|\n]*)\|([^\n]*\|.*)\n(\s*\|[-:|\s]+\|.*\n)/gm, (_m, heading: string, header: string, sepRow: string) => {
    return `${heading}\n|${header}\n${sepRow}`
  })
}

/** 未闭合代码块（AI/系统偶发 ` ```text ```` 无闭合 → 一路吃到文本末尾成整块代码）→
 *  降级为普通文本（剔除 ``` 标记）。仅奇数个 ```（存在未闭合）时触发；合法闭合代码块（偶数）不处理。 */
export function fixUnclosedCodeBlocks(src: string): string {
  const count = (src.match(/```/g) ?? []).length
  return count % 2 === 1 ? src.replace(/```[a-zA-Z0-9_-]*\s*/g, '') : src
}

/** settled 全量渲染前的文本修正链（顺序不可乱：unclosed → adhered → headings）。 */
export function applySettledPatches(src: string): string {
  return fixHeadings(fixAdheredTables(fixUnclosedCodeBlocks(src)))
}
