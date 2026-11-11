import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 预览排版 · 作用域与接线。
 *
 * <h2>用户裁定（2026-11-11）</h2>
 * 「我记得 md 完整文件的渲染，我们知识库那边就很不错啊，为什么我们在线渲染就很差呢？」
 * ⇒ 预览统一用**知识库那套**（来源 `kb-02.css` 的 `.kb-scope .md-content`，视觉本源 Typora）；
 * ⇒ 并且「**先不要影响现有主对话页面 只是预览采用**」。
 *
 * <h2>这三件事必须被测试守住，因为它们都会「静默失效」</h2>
 * 1. **作用域**：`.markdown-prose` 若漏进对话正文，主对话就被改观感了 —— 用户明确禁止。
 * 2. **变量**：那批规则引用 33 个变量，全部只在 `.kb-scope` 里定义。预览不在该子树里，
 *    漏一个就**零报错**地退化成无边框/无背景/默认字号。
 * 3. **接线**：四个预览漏一个，那个入口就还是旧观感；用户只会说「有的地方好有的地方差」。
 */
const PROSE = fileURLToPath(new URL('../../../styles/markdown-prose.css', import.meta.url))
const APP = fileURLToPath(new URL('../../../App.tsx', import.meta.url))

function read(p: string): string {
  return readFileSync(p, 'utf8').replace(/\r\n?/g, '\n')
}

/**
 * 只留 CSS 规则体，剥掉全部块注释。
 * ⚠️ 不能只剥头部：注释里为了说明来源/理由会提到 `.kb-scope`、`var(--td-*)` 等被禁形态，
 *    留着会**假红**；而按「最后一个块注释结束符」切片又会把规则体一起切掉 → **假绿**。
 */
function cssBody(p: string): string {
  return read(p).replace(/\/\*[\s\S]*?\*\//g, '')
}

describe('预览排版 · 作用域', () => {
  it('⭐ 全部收窄到 .markdown-prose（不残留 .kb-scope 选择器）', () => {
    const body = cssBody(PROSE)
    // 注释里为了说明来源会提到 .kb-scope；真规则里出现就是作用域没收干净
    expect(body.match(/\.kb-scope\b/g) || [], '残留 .kb-scope 选择器 ⇒ 预览子树里不命中').toHaveLength(0)
    expect(body).toContain('.markdown-prose')
  })

  it('⭐⭐ 变量必须是字面量（预览不在 .kb-scope 里，取不到值就静默失去样式）', () => {
    const body = cssBody(PROSE)
    // 逐个确认 kb 那批被消费的变量都已在本文件内定义成字面值
    const required = [
      '--td-text-color-primary',
      '--td-text-color-secondary',
      '--td-component-stroke',
      '--td-bg-color-secondarycontainer',
      '--td-brand-color',
      '--app-text-base',
      '--app-font-family',
      '--app-radius-md',
    ]
    for (const v of required) {
      expect(body, `${v} 未定义 ⇒ 依赖它的边框/背景/字号会落空`).toContain(v + ':')
    }
  })

  it('⭐ 变量定义里不得再套 var(--td-*) 自引用（本文件不是 .kb-scope 子树）', () => {
    const body = cssBody(PROSE)
    // 取出本文件 `--td-*: value;` 的定义，值里若仍是 var(--td-…) 说明没解析成字面量
    const defs = body.match(/--td-[a-z-]+:\s*[^;]+;/g) || []
    expect(defs.length).toBeGreaterThan(5)
    for (const d of defs) {
      expect(d, `未解析成字面量：${d}`).not.toMatch(/:\s*var\(--td-/)
    }
  })

  it('⭐ 代码块样式由 chat-code-block 独占（prose 里显式复位 pre 皮肤）', () => {
    const body = cssBody(PROSE)
    expect(body).toMatch(/\.markdown-prose[^{]*chat-code-block__pre[^{]*\{[^}]*margin:\s*0/)
  })

  it('深色主题按本仓变量补', () => {
    const body = cssBody(PROSE)
    expect(body).toMatch(/\[data-theme='dark'\]\s*\.markdown-prose/)
  })
})

describe('预览排版 · 接线', () => {
  it('App.tsx 只引 markdown-prose（GitHub 那两套已删除）', () => {
    const s = read(APP)
    expect(s).toContain("import './styles/markdown-prose.css'")
    expect(s).not.toContain('github-markdown-scoped.css')
    expect(s).not.toContain('markdown-reader.css')
  })

  it('⭐ 四个预览全部带上 markdown-prose（漏一个那个入口就还是旧观感）', () => {
    const files = [
      '../../../components/modals/FileViewModal.tsx',
      '../../../components/right/ArtifactDock.tsx',
      '../../../components/modals/ArtifactPreviewModal.tsx',
      '../../../components/knowledge/KbDocPreview.tsx',
    ]
    for (const rel of files) {
      const p = fileURLToPath(new URL(rel, import.meta.url))
      expect(read(p), `${rel} 的 MarkdownTextV2 缺 markdown-prose 类`).toContain('markdown-prose')
    }
  })

  it('⭐⭐ 对话消息不带 markdown-prose（用户明确要求主对话页面不变）', () => {
    for (const rel of ['../../../components/center/MessageList.tsx']) {
      const p = fileURLToPath(new URL(rel, import.meta.url))
      expect(read(p), `${rel} 不该带预览排版类`).not.toContain('markdown-prose')
    }
  })
})
