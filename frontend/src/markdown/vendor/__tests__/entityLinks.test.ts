import { describe, expect, it } from 'vitest'
import {
  ENTITY_LINK_CLASS,
  ENTITY_URI_SCHEME,
  parseEntityURI,
  renderEntityLinks,
  type EntityLinkLabels,
} from '../entityLinks'

/**
 * 意图：图谱实体 URI 有两个消费者 —— 解析（给代码判断它是哪个实体）和渲染（给用户看和点）。
 * 解析错会把别的实体当成它；渲染错会把 URI 原样漏给用户，或让 chip 丢了 class 而点不动。
 * 所以断言既覆盖「解析出什么」，也覆盖「页面上真的长成什么样」。
 */
const LABELS: EntityLinkLabels = { kb: '知识库', doc: '文档', chunk: '片段' }
const KB_ID = 'kb-0001'
const DOC_ID = '00000001-0000-4000-8000-000000000001'

describe('parseEntityURI', () => {
  it('合法 URI 解析出 kind / id（大小写不敏感）', () => {
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://kb/${KB_ID}`)).toEqual({ kind: 'kb', id: KB_ID })
    expect(parseEntityURI(`${ENTITY_URI_SCHEME.toUpperCase()}://DOC/${DOC_ID}`)).toEqual({
      kind: 'doc',
      id: DOC_ID,
    })
  })

  it('非法 URI 返回 null，不抛错', () => {
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://kb/`)).toBeNull()
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://user/1`)).toBeNull()
    expect(parseEntityURI(`see ${ENTITY_URI_SCHEME}://kb/${KB_ID}`)).toBeNull()
    expect(parseEntityURI('javascript:alert(1)')).toBeNull()
    expect(parseEntityURI(null)).toBeNull()
    expect(parseEntityURI(undefined)).toBeNull()
  })

  it('空字符串返回 null', () => {
    expect(parseEntityURI('')).toBeNull()
    expect(parseEntityURI('   ')).toBeNull()
  })

  it('ID 里的空格与斜杠不被接受（charset 之外）', () => {
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://kb/has space`)).toBeNull()
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://kb/a/b`)).toBeNull()
    // 连字符 / 下划线是合法字符，不能被误判成非法
    expect(parseEntityURI(`${ENTITY_URI_SCHEME}://chunk/a_b-c`)).toEqual({ kind: 'chunk', id: 'a_b-c' })
  })
})

describe('renderEntityLinks', () => {
  it('markdown 链接保留原文，变成带固定 class 的实体 chip', () => {
    const html = renderEntityLinks(
      `<p>存入 <a href="${ENTITY_URI_SCHEME}://kb/${KB_ID}">每日热榜</a> 即可</p>`,
      LABELS,
    )
    expect(html).toBe(
      `<p>存入 <span class="${ENTITY_LINK_CLASS}" role="link" tabindex="0" title="知识库" ` +
        `data-entity-kind="kb" data-entity-id="${KB_ID}">每日热榜</span> 即可</p>`,
    )
    // chip 必须是可点的 role=link，而不是普通 span
    expect(html).toContain(`class="${ENTITY_LINK_CLASS}" role="link" tabindex="0"`)
  })

  it('正文里裸 URI 显示真实 ID，kind 作 title，且不残留 scheme', () => {
    const html = renderEntityLinks(`<p>参考 ${ENTITY_URI_SCHEME}://doc/${DOC_ID}。</p>`, LABELS)
    expect(html).toContain(
      `title="文档" data-entity-kind="doc" data-entity-id="${DOC_ID}">${DOC_ID}</span>。`,
    )
    expect(html).not.toContain(`${ENTITY_URI_SCHEME}://`)
  })

  it('中文 label 既作 title 也作链接原文', () => {
    const html = renderEntityLinks(
      `<a href="${ENTITY_URI_SCHEME}://chunk/chunk-01">污染治理技术指南 第 3 段</a>`,
      LABELS,
    )
    expect(html).toContain('title="片段"')
    expect(html).toContain('>污染治理技术指南 第 3 段</span>')
  })

  it('空字符串 / 无 URI 的 HTML 原样返回', () => {
    expect(renderEntityLinks('', LABELS)).toBe('')
    const plain = '<p>普通文本，没有实体引用</p>'
    expect(renderEntityLinks(plain, LABELS)).toBe(plain)
  })

  it('code / pre / 普通外链内的 URI 一律不动', () => {
    const input =
      `<p><code>${ENTITY_URI_SCHEME}://kb/${KB_ID}</code></p>` +
      `<pre><code>${ENTITY_URI_SCHEME}://kb/${KB_ID}</code></pre>` +
      `<p><a href="https://example.com">${ENTITY_URI_SCHEME}://kb/${KB_ID}</a></p>`
    expect(renderEntityLinks(input, LABELS)).toBe(input)
  })

  it('label 里的 HTML 特殊字符被转义，不破坏属性', () => {
    const labels: EntityLinkLabels = { kb: '知识库 & "引用"', doc: '文档', chunk: '片段' }
    const html = renderEntityLinks(`<a href="${ENTITY_URI_SCHEME}://kb/${KB_ID}">x</a>`, labels)
    expect(html).toContain('title="知识库 &amp; &quot;引用&quot;"')
  })
})
