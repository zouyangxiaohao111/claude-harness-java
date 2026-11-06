import { describe, expect, it } from 'vitest'
import {
  extractHeadings,
  extractWikiLinks,
  parseWikiLink,
  renderWikiMarkdown,
} from '../utils/wikiMarkdown'
import { splitSlug, healthTone, WIKI_PAGE_TYPE_LABEL } from '../api/wiki'

/**
 * Wiki Markdown 渲染 · W3。
 *
 * <p><b>WHY</b>：页面正文是<b>模型生成</b>的。两个真风险：
 * ① `[[slug|名]]` 是自定义语法，marked 不认识 ⇒ 不处理就是满屏噪音；
 * ② 模型输出可能带 HTML ⇒ 直接塞进 innerHTML 就是 XSS。
 */
describe('[W3] Wiki Markdown 渲染', () => {
  describe('⭐ [[slug|名]] 解析', () => {
    it('标准两段式 slug 解析出 slug + 显示名', () => {
      expect(parseWikiLink('entity/acme|Acme 公司')).toEqual({
        slug: 'entity/acme',
        label: 'Acme 公司',
      })
    })

    it('⭐ 没有显示名时用 slug 末段', () => {
      expect(parseWikiLink('entity/acme-foo')).toEqual({
        slug: 'entity/acme-foo',
        label: 'acme-foo',
      })
    })

    it('⭐⭐ 不是两段式 ⇒ null（⛔ 不猜）', () => {
      expect(parseWikiLink('acme')).toBeNull()
      expect(parseWikiLink('entity/')).toBeNull()
      expect(parseWikiLink('/acme')).toBeNull()
      expect(parseWikiLink('')).toBeNull()
    })
  })

  describe('⭐⭐ XSS 防护', () => {
    it('⭐⭐ 模型输出的 <script> 必须变成文本', () => {
      const html = renderWikiMarkdown('正常文本 <script>alert(1)</script>')
      expect(html).not.toContain('<script>')
      expect(html).toContain('&lt;script&gt;')
    })

    it('⭐⭐ <img onerror> 不能透传', () => {
      const html = renderWikiMarkdown('<img src=x onerror="alert(1)">')
      // ⚠️ 断言要判「有没有活的标签/属性」，不能只看字符串含不含 onerror ——
      //    转义后它作为**普通文本**出现（`&lt;img ... onerror=&quot;...`）是安全的品种。
      expect(html).not.toContain('<img')
      expect(html).not.toMatch(/<img[^>]*onerror/i)
      // 且确实被当成文本转义了
      expect(html).toContain('&lt;img')
    })

    it('⭐⭐ 链接里的 javascript: 协议必须被剥掉', () => {
      const html = renderWikiMarkdown('[点我](javascript:alert(1))')
      expect(html).not.toContain('javascript:')
    })

    it('⭐ 正常 http 链接保留并加 noopener', () => {
      const html = renderWikiMarkdown('[文档](https://example.com/x)')
      expect(html).toContain('href="https://example.com/x"')
      expect(html).toContain('rel="noopener noreferrer"')
    })

    it('⭐ wiki 链接的 slug 也要转义（防属性注入）', () => {
      const html = renderWikiMarkdown('[[entity/x" onmouseover="alert(1)|名字]]')
      expect(html).not.toContain('onmouseover="alert')
    })
  })

  describe('[[wiki]] 渲染', () => {
    it('渲染成带 data-wiki-slug 的链接', () => {
      const html = renderWikiMarkdown('见 [[entity/acme|Acme]] 页面')
      expect(html).toContain('data-wiki-slug="entity/acme"')
      expect(html).toContain('Acme</a>')
    })

    it('⭐⭐ 代码块里的 [[...]] 不替换（是示例文本）', () => {
      const md = '```\n[[entity/acme|Acme]]\n```'
      const html = renderWikiMarkdown(md)
      expect(html).not.toContain('data-wiki-slug')
      expect(html).toContain('[[entity/acme|Acme]]')
    })

    it('⭐⭐ 行内代码里的也不替换', () => {
      const html = renderWikiMarkdown('语法是 `[[entity/x|名]]` 这样')
      expect(html).not.toContain('data-wiki-slug="entity/x"')
      expect(html).toContain('[[entity/x|名]]')
    })

    it('非法形态原样保留', () => {
      const html = renderWikiMarkdown('[[not-a-slug]] 不算链接')
      expect(html).not.toContain('data-wiki-slug')
      expect(html).toContain('[[not-a-slug]]')
    })
  })

  describe('标题提取', () => {
    it('提取 h1-h6', () => {
      const hs = extractHeadings('# 一级\n正文\n## 二级\n### 三级')
      expect(hs.map((h) => h.level)).toEqual([1, 2, 3])
      expect(hs[0].text).toBe('一级')
    })

    it('⭐ 代码块里的 # 不算标题', () => {
      const hs = extractHeadings('# 真标题\n```\n# 假标题\n```')
      expect(hs).toHaveLength(1)
      expect(hs[0].text).toBe('真标题')
    })

    it('中文标题生成锚点', () => {
      const hs = extractHeadings('## 农村污水治理')
      expect(hs[0].anchor).toBe('农村污水治理')
    })
  })

  describe('链接提取', () => {
    it('去重保序', () => {
      const links = extractWikiLinks('[[entity/a|A]] 与 [[entity/b|B]] 还有 [[entity/a|A]]')
      expect(links).toEqual(['entity/a', 'entity/b'])
    })

    it('跳过非法形态', () => {
      expect(extractWikiLinks('[[noslug]] [[entity/a|A]]')).toEqual(['entity/a'])
    })
  })
})

describe('[W3] Wiki API 辅助', () => {
  it('⭐⭐ splitSlug 必须拆成两段（后端路径是 {type}/{name}）', () => {
    expect(splitSlug('entity/acme')).toEqual(['entity', 'acme'])
    expect(splitSlug('concept/农村污水')).toEqual([
      'concept',
      encodeURIComponent('农村污水'),
    ])
  })

  it('⭐ 无斜杠时兜底 type=entity', () => {
    expect(splitSlug('acme')).toEqual(['entity', 'acme'])
  })

  it('健康分档位', () => {
    expect(healthTone(90)).toBe('ok')
    expect(healthTone(60)).toBe('warn')
    expect(healthTone(20)).toBe('bad')
  })

  it('页面类型有中文标签', () => {
    expect(WIKI_PAGE_TYPE_LABEL.entity).toBe('实体')
    expect(WIKI_PAGE_TYPE_LABEL.summary).toBe('文档摘要')
  })
})
