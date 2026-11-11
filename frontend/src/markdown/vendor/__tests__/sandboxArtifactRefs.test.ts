// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'

import { escapeHTML, renderArtifactFileIcon } from '../vendorStubs'
import {
  artifactIndexFromEventTarget,
  installArtifactLinkRenderer,
  isArtifactRefHref,
  normalizeSandboxArtifactRefs,
  renderArtifactReference,
  resolveArtifactRef,
} from '../sandboxArtifactRefs'

/**
 * 意图：这些用例只验证「不依赖真实沙箱」的那部分机制 —— 引用识别、解析失败
 * 时的容错、转义、以及无产物引用时的原样透传。沙箱下载链路由桩接管，本批
 * 不测（本仓尚无该能力）。
 */

const LABELS = { previewHint: '点击预览', missingHint: '文件不可用', deletedHint: '文件已删除' }

describe('isArtifactRefHref', () => {
  it('sandbox: 与 sandbox:// 都算产物引用', () => {
    expect(isArtifactRefHref('sandbox://x.html')).toBe(true)
    expect(isArtifactRefHref('sandbox:x.html')).toBe(true)
  })

  it('普通外链与 22 位 resource 句柄：只认句柄形态，不认 http', () => {
    expect(isArtifactRefHref('http://x')).toBe(false)
    expect(isArtifactRefHref('https://example.com/a.png')).toBe(false)
    expect(isArtifactRefHref('resource://abcdefghijklmnopqrstuv')).toBe(true)
    // 长度不对的 handle 不算（正则要求恰好 22 位 [A-Za-z0-9_-]）
    expect(isArtifactRefHref('resource://short')).toBe(false)
  })

  it('空串与纯空格返回 false', () => {
    expect(isArtifactRefHref('')).toBe(false)
    expect(isArtifactRefHref('   ')).toBe(false)
  })
})

describe('resolveArtifactRef', () => {
  it('无效输入返回 null 且不抛', () => {
    expect(() => resolveArtifactRef('http://x', undefined)).not.toThrow()
    expect(resolveArtifactRef('http://x', undefined)).toBeNull()
    expect(resolveArtifactRef('', [{ index: 0, file_name: 'a.png' }])).toBeNull()
    // artifacts 为空 / 非产物 href ⇒ null，不抛
    expect(resolveArtifactRef('sandbox:a.png', [])).toBeNull()
    expect(resolveArtifactRef('sandbox:a.png', null)).toBeNull()
  })

  it('按文件名命中，目录前缀被剥掉', () => {
    const artifacts = [{ index: 0, file_name: 'report.html' }]
    expect(resolveArtifactRef('sandbox://out/report.html', artifacts)).toEqual(artifacts[0])
    // 百分号编码的文件名先解码再比
    expect(resolveArtifactRef('sandbox:%E6%8A%A5%E5%91%8A.pdf', [
      { index: 1, file_name: '报告.pdf' },
    ])).toEqual({ index: 1, file_name: '报告.pdf' })
  })

  it('handle 形式按句柄命中，对不上就返回 null', () => {
    const artifacts = [{ index: 0, file_name: 'a.png', handle: 'resource://abcdefghijklmnopqrstuv' }]
    expect(resolveArtifactRef('resource://abcdefghijklmnopqrstuv', artifacts)).toEqual(artifacts[0])
    expect(resolveArtifactRef('resource://ABCDEFGHIJKLMNOPQRSTUV', artifacts)).toBeNull()
  })
})

describe('escapeHTML', () => {
  it('五个 HTML 特殊字符全部转义', () => {
    expect(escapeHTML('<')).toBe('&lt;')
    expect(escapeHTML('>')).toBe('&gt;')
    expect(escapeHTML('&')).toBe('&amp;')
    expect(escapeHTML('"')).toBe('&quot;')
    expect(escapeHTML("'")).toBe('&#x27;')
  })

  it('混排时逐字符转义（源站实现还转义 = / `，此处照搬）', () => {
    expect(escapeHTML(`<a href="x">O'Reilly & Co</a>`)).toBe(
      '&lt;a href&#x3D;&quot;x&quot;&gt;O&#x27;Reilly &amp; Co&lt;&#x2F;a&gt;',
    )
  })
})

describe('normalizeSandboxArtifactRefs', () => {
  it('无产物引用的文本原样返回（同一字符串）', () => {
    const md = '# 标题\n\n普通段落，含 [链接](https://example.com) 与 ![图](https://e.com/a.png)。'
    expect(normalizeSandboxArtifactRefs(md)).toBe(md)
  })

  it('空串 / 无 ]( 的文本原样返回', () => {
    expect(normalizeSandboxArtifactRefs('')).toBe('')
    expect(normalizeSandboxArtifactRefs('纯文本 sandbox:a.png')).toBe('纯文本 sandbox:a.png')
  })

  it('带空格的文件名被压成单个 token，且代码块内示例不动', () => {
    const md = '![图表](sandbox:腾讯控股(00700) 成交量.html)\n\n```\n![示例](sandbox:a b.html)\n```'
    const out = normalizeSandboxArtifactRefs(md)
    // 括号配对生效：整个带空格的文件名被当成一个目标，空格被 encode 成 %20
    expect(out).toContain('sandbox:%E8%85%BE%E8%AE%AF%E6%8E%A7%E8%82%A1(00700)%20')
    expect(out).not.toContain('sandbox:腾讯控股(00700) 成交量.html')
    // 围栏内的示例保持原样
    expect(out).toContain('![示例](sandbox:a b.html)')
  })
})

describe('renderArtifactReference（不依赖真实沙箱的部分）', () => {
  it('非产物引用返回 null，交回默认渲染', () => {
    expect(renderArtifactReference({ href: 'https://x/a.png', labels: LABELS })).toBeNull()
    expect(renderArtifactReference({ href: '', labels: LABELS })).toBe('')
  })

  it('流式期间解析不到 ⇒ 骨架屏；结束后解析不到 ⇒ 置灰卡片', () => {
    const streaming = renderArtifactReference({ href: 'sandbox:a.png', labels: LABELS, streaming: true })
    expect(streaming).toContain('streaming-image-loading')

    const done = renderArtifactReference({ href: 'sandbox:a.png', labels: LABELS, artifacts: [] })
    expect(done).toContain('artifact-ref-card--pending')
    expect(done).toContain('文件不可用')
    // 非 ready 卡片不可点击
    expect(done).not.toContain('data-artifact-index')
  })

  it('命中已删除的产物 ⇒ 置灰 deleted 卡片', () => {
    const html = renderArtifactReference({
      href: 'sandbox:a.png',
      labels: LABELS,
      artifacts: [{ index: 0, file_name: 'a.png', deleted_at: '2026-01-01T00:00:00Z' }],
    })
    expect(html).toContain('artifact-ref-card--deleted')
    expect(html).toContain('文件已删除')
  })

  it('图片类产物内联渲染，SVG 例外走卡片', () => {
    const png = renderArtifactReference({
      href: 'sandbox:a.png',
      labels: LABELS,
      artifacts: [{ index: 3, file_name: 'a.png' }],
    })
    expect(png).toContain('class="markdown-image artifact-ref-image"')
    expect(png).toContain('data-artifact-index="3"')

    const svg = renderArtifactReference({
      href: 'sandbox:a.svg',
      labels: LABELS,
      artifacts: [{ index: 4, file_name: 'a.svg' }],
    })
    expect(svg).toContain('artifact-ref-card')
    expect(svg).toContain('点击预览')
  })
})

describe('artifactIndexFromEventTarget', () => {
  it('与卡片无关的 target 返回 null', () => {
    const div = document.createElement('div')
    expect(artifactIndexFromEventTarget(null)).toBeNull()
    expect(artifactIndexFromEventTarget(div)).toBeNull()
  })

  it('点击卡片内的子元素时向上找到卡片下标', () => {
    const card = document.createElement('span')
    card.className = 'artifact-ref-card'
    card.setAttribute('data-artifact-index', '7')
    const name = document.createElement('span')
    card.appendChild(name)
    document.body.appendChild(card)
    expect(artifactIndexFromEventTarget(name)).toBe(7)
    card.remove()
  })
})

describe('installArtifactLinkRenderer（真正接到 marked 上）', () => {
  it('只接管产物链接，普通链接仍走默认渲染', async () => {
    const { Marked, Renderer } = await import('marked')

    // 阴性对照：不装渲染器时 sandbox 链接会被当普通 <a> 输出
    const plain = new Marked({ renderer: new Renderer() })
    expect(plain.parse('[下载](sandbox:chart.html)')).toContain('<a href="sandbox:chart.html"')

    const renderer = new Renderer()
    installArtifactLinkRenderer(renderer, () => ({
      labels: LABELS,
      artifacts: [{ index: 2, file_name: 'chart.html' }],
    }))
    const instance = new Marked({ renderer })
    expect(instance.parse('[下载](sandbox:chart.html)')).toContain('artifact-ref-card')
    expect(instance.parse('[外链](https://example.com)')).toContain('<a href="https://example.com"')
  })
})

describe('renderArtifactFileIcon（桩）', () => {
  it('返回固定 svg，且不插值文件名', () => {
    const icon = renderArtifactFileIcon('恶意<script>.html')
    expect(icon).toContain('<svg')
    expect(icon).not.toContain('<script>')
    expect(icon).toContain('FILE')
  })
})
