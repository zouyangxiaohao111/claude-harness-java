/**
 * 代码块外壳（对齐 deepseek-harness CodeBlock）：banner（语言徽标 + actions[运行/复制]）
 * + 正文 pre。
 * - 复制：Clipboard API（execCommand 兜底），成功后短暂「已复制」。
 * - 运行：仅 lang==='html' 且传入 onRunHtml 时渲染「运行」按钮（nexusai html 沙箱预览）。
 * - 高亮：settled（streaming=false）时用 highlightToHtml（shiki 静态 span 树，走被许可
 *   dangerouslySetInnerHTML）；streaming 期间 lang 传入但不高亮（正文纯文本 pre），
 *   与「流式不高亮、收口后精排」对齐。懒加载语法就绪后 useSyncExternalStore 触发重渲。
 */
import { useCallback, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { grammarLoadCount, highlightToHtml, subscribeGrammarLoaded } from './highlight.ts'
import { writeClipboard } from './clipboard.ts'

export interface CodeBlockProps {
  /** 源码文本（原样渲染；末尾单个换行在展示层 trim 掉）。 */
  code: string
  /** 语言提示（fence info 或固定调用方 id）；未知 = 纯文本。 */
  lang?: string | undefined
  /** true=流式（不高亮，正文纯文本 pre）；false/缺省=settled（shiki 高亮）。 */
  streaming?: boolean
  /** html 代码块「运行」回调（仅 lang=html 且传此才渲染运行按钮）。 */
  onRunHtml?: ((code: string) => void) | undefined
  /** 复制按钮静止文案。 */
  copyLabel?: string | undefined
  /** 复制成功后的短暂文案。 */
  copiedLabel?: string | undefined
  /** 追加到容器上的额外 class（保留语义 className 以兼容宿主选择器）。 */
  className?: string | undefined
}

export function CodeBlock({
  code,
  lang,
  streaming = false,
  onRunHtml,
  copyLabel = '复制',
  copiedLabel = '已复制',
  className,
}: CodeBlockProps) {
  const trimmed = code.endsWith('\n') ? code.slice(0, -1) : code
  // 懒加载语法就绪后重渲染：普通 fence 先在纯文本 fallback，注册完成再补高亮。
  const loaded = useSyncExternalStore(subscribeGrammarLoaded, grammarLoadCount, grammarLoadCount)
  const html = useMemo(
    () => (streaming ? undefined : highlightToHtml(trimmed, lang)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [trimmed, lang, streaming, loaded],
  )
  const rootRef = useRef<HTMLDivElement>(null)
  const [copied, setCopied] = useState(false)

  const onCopy = useCallback(() => {
    if (copied) return
    const text = rootRef.current?.querySelector('pre')?.textContent ?? trimmed
    void writeClipboard(text).then((ok) => {
      if (!ok) return
      setCopied(true)
      window.setTimeout(() => { setCopied(false) }, 1200)
    })
  }, [copied, trimmed])

  const isHtmlRun = onRunHtml !== undefined && (lang ?? '').trim().toLowerCase() === 'html'
  const bannerLang = (lang ?? '').trim() || ''

  const rootClass = ['md-code-block', className].filter(Boolean).join(' ')
  const body = html === undefined
    ? <pre><code>{trimmed}</code></pre>
    : (
      // shiki 输出是它根据 code 生成的静态 span 树（无用户 HTML 透传），
      // 是 shiki 官方认可的 innerHTML 消费路径。
      <div dangerouslySetInnerHTML={{ __html: html }} />
    )

  return (
    <div ref={rootRef} className={rootClass}>
      <div className="md-code-banner">
        {bannerLang
          ? <span className="md-code-lang">{bannerLang}</span>
          : <span className="md-code-lang empty" aria-hidden="true" />}
        <span className="md-code-actions">
          {isHtmlRun && (
            <button type="button" className="md-code-run" onClick={() => onRunHtml?.(trimmed)}>
              运行
            </button>
          )}
          <button type="button" className="md-code-copy" onClick={onCopy}>
            {copied ? copiedLabel : copyLabel}
          </button>
        </span>
      </div>
      {body}
    </div>
  )
}
