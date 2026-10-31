// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ArtifactCards } from '@/components/right/ArtifactCards'
import { artifactKindOf, isTextArtifact } from '@/utils/artifactKind'
import type { SessionArtifactDto } from '@/api/artifacts'

/**
 * 意图（为什么不只是「断言调了什么」）：
 * - 产物区是「最终交付物」的入口，用户扫一眼就要知道这是个网页 / 文档 / 表格还是图片
 *   ⇒ **不同类型必须画不同图标**（统一文件图标 ⇒ 8 个产物长得一模一样，扫不出差别）。
 * - 「在浏览器中打开」**只有 html 有**：其它类型不是网页，外链打开无意义
 *   ⇒ 给 docx/pdf 也挂这个按钮 = 假入口，用户点了只会得到一个下载。
 * - 「编辑」**只有文本类有**：二进制产物 PUT /content 会 400
 *   ⇒ 给二进制挂「编辑」= 假入口，用户点完必然报错。
 * - 正文最多 3 张（超出走「查看所有产物 (N)」）——但截断发生在调用方，这里只保证组件本身不截。
 */

const art = (id: string, title: string, extra: Partial<SessionArtifactDto> = {}): SessionArtifactDto => ({
  id,
  kind: 'deliverable',
  title,
  localPath: `/home/u/.nexusai/artifacts/s1/${title}`,
  url: null,
  sizeBytes: 1024,
  summary: null,
  updatedAt: null,
  ...extra,
})

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(props: Parameters<typeof ArtifactCards>[0]) {
  act(() => {
    root.render(<ArtifactCards {...props} />)
  })
}

/** 每张卡的图标 svg path 子集（用于判定「是不是同一个图标」） */
function iconPaths(card: Element): string[] {
  const svg = card.querySelector('.ac-ico svg')
  return svg ? Array.from(svg.querySelectorAll('path,circle,rect')).map((n) => n.outerHTML) : []
}

describe('artifactKindOf', () => {
  it('按扩展名判类型（后端 kind 字段是 deliverable/source，不是文件类型）', () => {
    expect(artifactKindOf('a.html')).toBe('html')
    expect(artifactKindOf('a.htm')).toBe('html')
    expect(artifactKindOf('a.docx')).toBe('docx')
    expect(artifactKindOf('a.pptx')).toBe('pptx')
    expect(artifactKindOf('a.xlsx')).toBe('xlsx')
    expect(artifactKindOf('a.pdf')).toBe('pdf')
    expect(artifactKindOf('a.md')).toBe('md')
    expect(artifactKindOf('a.png')).toBe('img')
    expect(artifactKindOf('a.jpeg')).toBe('img')
    expect(artifactKindOf('a.zip')).toBe('file')
    expect(artifactKindOf('noext')).toBe('file')
  })

  it('大小写不敏感', () => {
    expect(artifactKindOf('A.HTML')).toBe('html')
    expect(artifactKindOf('A.PDF')).toBe('pdf')
  })
})

describe('isTextArtifact', () => {
  it('二进制（docx/xlsx/pdf/pptx）不可编辑 ⇒ 不给「编辑」入口', () => {
    expect(isTextArtifact('a.docx')).toBe(false)
    expect(isTextArtifact('a.xlsx')).toBe(false)
    expect(isTextArtifact('a.pdf')).toBe(false)
    expect(isTextArtifact('a.pptx')).toBe(false)
    expect(isTextArtifact('a.png')).toBe(false)
  })

  it('文本（html/md/json…）可编辑', () => {
    expect(isTextArtifact('a.html')).toBe(true)
    expect(isTextArtifact('a.md')).toBe(true)
    expect(isTextArtifact('a.json')).toBe(true)
  })
})

describe('ArtifactCards', () => {
  it('3 列网格（设计稿：右栏与正文都是 3 列）', () => {
    render({ items: [art('1', 'a.html'), art('2', 'b.docx'), art('3', 'c.xlsx'), art('4', 'd.pdf')] })
    const grid = container.querySelector('.artifact-cards')
    expect(grid).toBeTruthy()
    expect(container.querySelectorAll('.artifact-card').length).toBe(4)
  })

  it('不同类型画不同图标（否则用户扫一眼分不出网页/文档/表格）', () => {
    render({
      items: [
        art('1', 'a.html'),
        art('2', 'b.docx'),
        art('3', 'c.xlsx'),
        art('4', 'd.pdf'),
        art('5', 'e.md'),
        art('6', 'f.png'),
        art('7', 'g.zip'),
      ],
    })
    const cards = Array.from(container.querySelectorAll('.artifact-card'))
    const sigs = cards.map((c) => iconPaths(c).join('|'))
    // html / docx / xlsx / pdf / md / img / file 七个类型 ⇒ 七个互不相同的图标签名
    expect(new Set(sigs).size).toBe(7)
  })

  it('图标按类型分底色（k-html / k-docx …）', () => {
    render({ items: [art('1', 'a.html'), art('2', 'b.docx'), art('3', 'c.png')] })
    expect(container.querySelector('.ac-ico.k-html')).toBeTruthy()
    expect(container.querySelector('.ac-ico.k-docx')).toBeTruthy()
    expect(container.querySelector('.ac-ico.k-img')).toBeTruthy()
  })

  it('「在浏览器中打开」**只有 html 有**（其它类型不是网页 ⇒ 外链打开无意义）', () => {
    const onOpenInBrowser = vi.fn()
    render({
      items: [art('1', 'a.html'), art('2', 'b.docx'), art('3', 'c.xlsx'), art('4', 'd.pdf')],
      onOpenInBrowser,
    })
    const cards = Array.from(container.querySelectorAll('.artifact-card'))
    const browserBtns = cards.map((c) => c.querySelector('.ac-act[aria-label="在浏览器中打开"]'))
    expect(browserBtns[0]).toBeTruthy() // html
    expect(browserBtns[1]).toBeFalsy() // docx
    expect(browserBtns[2]).toBeFalsy() // xlsx
    expect(browserBtns[3]).toBeFalsy() // pdf
  })

  it('「编辑」只有文本类有（二进制 PUT /content 会 400 ⇒ 不给假入口）', () => {
    render({
      items: [art('1', 'a.html'), art('2', 'b.docx'), art('3', 'c.pdf'), art('4', 'd.md')],
      onEdit: vi.fn(),
    })
    const cards = Array.from(container.querySelectorAll('.artifact-card'))
    const editBtns = cards.map((c) => c.querySelector('.ac-act[aria-label="编辑"]'))
    expect(editBtns[0]).toBeTruthy() // html
    expect(editBtns[1]).toBeFalsy() // docx
    expect(editBtns[2]).toBeFalsy() // pdf
    expect(editBtns[3]).toBeTruthy() // md
  })

  it('点卡片 → 走统一的打开回调（右栏与正文共用同一个函数）', () => {
    const onOpen = vi.fn()
    render({ items: [art('1', 'a.html'), art('2', 'b.docx')], onOpen })
    const cards = Array.from(container.querySelectorAll('.artifact-card'))
    act(() => { (cards[1] as HTMLElement).click() })
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(onOpen.mock.calls[0][0].title).toBe('b.docx')
  })

  it('点右上角按钮不触发卡片本身的打开（事件冒泡会被拦）', () => {
    const onOpen = vi.fn()
    const onOpenInBrowser = vi.fn()
    render({ items: [art('1', 'a.html')], onOpen, onOpenInBrowser })
    const btn = container.querySelector('.ac-act[aria-label="在浏览器中打开"]') as HTMLElement
    act(() => { btn.click() })
    expect(onOpenInBrowser).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('空列表不渲染任何东西（避免右栏出现空网格占位）', () => {
    render({ items: [] })
    expect(container.querySelector('.artifact-cards')).toBeFalsy()
  })
})
