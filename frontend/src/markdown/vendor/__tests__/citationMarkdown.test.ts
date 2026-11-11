import { describe, expect, it } from 'vitest'
import {
  KB_WEB_TAG_RE,
  stripIncompleteCitationTag,
  resolveCitationChunkId,
  preprocessCitationTags,
  extractCitationHtmlPlaceholders,
  restoreCitationHtmlPlaceholders,
  joinCitationTagsToPreviousLine,
  collapseStandaloneCitationParagraphs,
  preserveCitationTags,
  restoreCitationTags,
  stripCitationTagsForMarkdown,
  type CitationKnowledgeRef,
} from '../citationMarkdown.ts'

describe('citationMarkdown 导出契约', () => {
  it('11 个导出符号都可在本仓 import，且签名符合源站', () => {
    expect(KB_WEB_TAG_RE).toBeInstanceOf(RegExp)
    expect(typeof stripIncompleteCitationTag).toBe('function')
    expect(typeof resolveCitationChunkId).toBe('function')
    expect(typeof preprocessCitationTags).toBe('function')
    expect(typeof extractCitationHtmlPlaceholders).toBe('function')
    expect(typeof restoreCitationHtmlPlaceholders).toBe('function')
    expect(typeof joinCitationTagsToPreviousLine).toBe('function')
    expect(typeof collapseStandaloneCitationParagraphs).toBe('function')
    expect(typeof preserveCitationTags).toBe('function')
    expect(typeof restoreCitationTags).toBe('function')
    expect(typeof stripCitationTagsForMarkdown).toBe('function')

    // 类型签名可实例化（CitationKnowledgeRef 结构）
    const ref: CitationKnowledgeRef = {
      id: 'uuid',
      knowledge_title: '标题',
      chunk_type: 'faq',
      knowledge_base_id: 'kb-1',
    }
    expect(resolveCitationChunkId('uuid', {}, [ref])).toBe('uuid')
  })
})

describe('stripIncompleteCitationTag（流式半截标签）', () => {
  it('把未闭合的 "<kb" 藏起来', () => {
    expect(stripIncompleteCitationTag('答案正文<kb')).toBe('答案正文')
  })

  it('藏起 "<" / "<k" / "<we" 这些前缀形态', () => {
    expect(stripIncompleteCitationTag('正文<')).toBe('正文')
    expect(stripIncompleteCitationTag('正文<k')).toBe('正文')
    expect(stripIncompleteCitationTag('正文<we')).toBe('正文')
    expect(stripIncompleteCitationTag('正文<kb doc="x"')).toBe('正文')
  })

  it('完整标签与非引用标签原样保留', () => {
    expect(stripIncompleteCitationTag('正文<kb id="x"/>')).toBe('正文<kb id="x"/>')
    expect(stripIncompleteCitationTag('正文<div')).toBe('正文<div')
    expect(stripIncompleteCitationTag('')).toBe('')
  })
})

describe('preprocessCitationTags（完整标签进 HTML）', () => {
  it('完整 <kb id="x"/> 被抠成 citation span，restore 后能换回', () => {
    const refs: CitationKnowledgeRef[] = [
      { id: '0f8fad5b-d9cb-469f-a165-70867728950e', knowledge_title: '出水水质报告' },
    ]
    const source = '出水达标<kb doc="出水水质报告" chunk_id="1"/>。'
    const html = preprocessCitationTags(source, refs)
    const tagOnly = preprocessCitationTags('<kb doc="出水水质报告" chunk_id="1"/>', refs)

    expect(html).toContain('class="citation citation-kb"')
    expect(html).toContain('data-chunk-id="0f8fad5b-d9cb-469f-a165-70867728950e"')
    expect(html).toContain('data-doc="出水水质报告"')
    expect(html).not.toContain('<kb')

    // 占位符链路：抠出 → 还原，正文与标签内容都无损
    const extracted = extractCitationHtmlPlaceholders(source, refs)
    expect(extracted.content).not.toContain('<kb')
    expect(extracted.htmlSnippets).toHaveLength(1)
    expect(extracted.htmlSnippets[0]).toBe(tagOnly)
    expect(restoreCitationHtmlPlaceholders(extracted.content, extracted.htmlSnippets)).toBe(html)
  })

  it('<web url="..."/> 变成外链，domain 取末两段', () => {
    const html = preprocessCitationTags('见<web url="https://news.example.com/a/b" title="新闻"/>')
    expect(html).toContain('class="citation citation-web"')
    expect(html).toContain('href="https://news.example.com/a/b"')
    expect(html).toContain('>example.com</span>')
    expect(html).not.toContain('<web')
  })

  it('缺 doc / 缺 url 的标签被丢弃', () => {
    expect(preprocessCitationTags('甲<kb chunk_id="1"/>乙')).toBe('甲乙')
    expect(preprocessCitationTags('甲<web title="无地址"/>乙')).toBe('甲乙')
  })

  it('只有 id、没有 doc 的 <kb/> 也按源站语义丢弃', () => {
    expect(preprocessCitationTags('甲<kb id="a"/>乙')).toBe('甲乙')
  })
})

describe('标签位置（连续 / 紧贴中文 / 行首 / 行尾）', () => {
  it('连续多个标签各自成段，紧贴中文不吞字', () => {
    const source = '前<kb doc="A" chunk_id="1"/>中<kb doc="B" chunk_id="2"/>后<web url="https://e.com/x"/>'
    const { htmlSnippets, content } = extractCitationHtmlPlaceholders(source, [
      { id: 'a', knowledge_title: 'A' },
      { id: 'b', knowledge_title: 'B' },
    ])
    expect(htmlSnippets).toHaveLength(3)
    expect(content).toBe(
      '前@@CITATION_HTML_PLACEHOLDER_0@@中@@CITATION_HTML_PLACEHOLDER_1@@后@@CITATION_HTML_PLACEHOLDER_2@@',
    )
    expect(htmlSnippets[0]).toContain('data-doc="A"')
    expect(htmlSnippets[1]).toContain('data-doc="B"')
    expect(htmlSnippets[2]).toContain('class="citation citation-web"')
  })

  it('行首 / 行尾标签剥除后不破坏正文与缩进', () => {
    const source = '<kb doc="报告一" chunk_id="1"/>\n正文内容\n<kb doc="报告二" chunk_id="2"/>'
    const { text, sources } = stripCitationTagsForMarkdown(source)
    expect(text).toBe('正文内容')
    expect(sources).toEqual(['报告一', '报告二'])
  })
})

describe('stripCitationTagsForMarkdown', () => {
  it('剥掉 <kb/> 并回传文档名列表（去重、保序）', () => {
    const source = '结论<kb doc="A" chunk_id="1"/>补充<kb doc="A" chunk_id="1"/><kb doc="B" chunk_id="2"/>'
    expect(stripCitationTagsForMarkdown(source)).toEqual({ text: '结论补充', sources: ['A', 'B'] })
  })

  it('<web/> 转成 Markdown 链接，标题里的方括号被清掉', () => {
    const source = '参考<web url="https://e.com/p" title="标[题]"/>'
    expect(stripCitationTagsForMarkdown(source).text).toBe('参考[标 题](https://e.com/p)')
  })

  it('空输入返回空结果', () => {
    expect(stripCitationTagsForMarkdown('')).toEqual({ text: '', sources: [] })
  })
})

describe('resolveCitationChunkId', () => {
  const refs: CitationKnowledgeRef[] = [
    { id: 'uuid-faq-1', chunk_type: 'faq', knowledge_title: '常见问题' },
    { id: 'uuid-doc-1', chunk_type: 'text', knowledge_title: '运行台账' },
    { id: 'uuid-doc-2', chunk_type: 'text', knowledge_title: '监测记录' },
  ]

  it('已经真实 UUID 或空值直通', () => {
    expect(resolveCitationChunkId('0f8fad5b-d9cb-469f-a165-70867728950e', {}, refs))
      .toBe('0f8fad5b-d9cb-469f-a165-70867728950e')
    expect(resolveCitationChunkId('', {}, refs)).toBe('')
  })

  it('FAQ-N / DOC-N / 纯数字按序映射', () => {
    expect(resolveCitationChunkId('FAQ-1', {}, refs)).toBe('uuid-faq-1')
    expect(resolveCitationChunkId('DOC-2', {}, refs)).toBe('uuid-doc-2')
    expect(resolveCitationChunkId('2', {}, refs)).toBe('uuid-doc-1')
  })

  it('按 doc 标题命中优先，web_search 结果被排除', () => {
    expect(resolveCitationChunkId('1', { doc: '监测记录' }, refs)).toBe('uuid-doc-2')
    expect(resolveCitationChunkId('1', { doc: '外部网页' }, [
      { id: 'web-1', chunk_type: 'web_search', knowledge_title: '外部网页' },
    ])).toBe('1')
  })
})

describe('joinCitationTagsToPreviousLine', () => {
  it('把标签前的单个换行收成空格', () => {
    expect(joinCitationTagsToPreviousLine('正文\n<kb id="1"/>')).toBe('正文 <kb id="1"/>')
  })

  it('连续标签之间的换行收成空格', () => {
    expect(joinCitationTagsToPreviousLine('<kb id="1"/>\n\n<kb id="2"/>'))
      .toBe('<kb id="1"/> <kb id="2"/>')
  })

  it('代码围栏行不被并上去', () => {
    expect(joinCitationTagsToPreviousLine('```\n<kb id="1"/>')).toBe('```\n<kb id="1"/>')
  })
})

describe('collapseStandaloneCitationParagraphs', () => {
  it('把只含引用的段落并回上一段', () => {
    const html = '<p>正文</p>\n<p><span class="citation citation-kb">A</span></p>'
    expect(collapseStandaloneCitationParagraphs(html))
      .toBe('<p>正文 <span class="citation citation-kb">A</span></p>')
  })

  it('不含引用时原样返回', () => {
    const html = '<p>正文</p><p>另起一段</p>'
    expect(collapseStandaloneCitationParagraphs(html)).toBe(html)
  })
})

describe('preserveCitationTags / restoreCitationTags', () => {
  it('先占位再还原，标签与正文全部无损', () => {
    const source = '甲<kb id="1"/>乙<web url="https://e.com"/>丙'
    const { text, tags } = preserveCitationTags(source)
    expect(text).not.toContain('<kb')
    expect(tags).toEqual(['<kb id="1"/>', '<web url="https://e.com"/>'])
    expect(restoreCitationTags(text, tags)).toBe(source)
  })

  it('缺失的序号还原为空串，不抛错', () => {
    expect(restoreCitationTags('a\x00TAG5\x00b', [])).toBe('ab')
  })
})
