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
  sourceMessageId: null,
  updatedAt: null,
  directory: false,
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
    expect(artifactKindOf({ title: 'a.html' })).toBe('html')
    expect(artifactKindOf({ title: 'a.htm' })).toBe('html')
    expect(artifactKindOf({ title: 'a.docx' })).toBe('docx')
    expect(artifactKindOf({ title: 'a.pptx' })).toBe('pptx')
    expect(artifactKindOf({ title: 'a.xlsx' })).toBe('xlsx')
    expect(artifactKindOf({ title: 'a.pdf' })).toBe('pdf')
    expect(artifactKindOf({ title: 'a.md' })).toBe('md')
    expect(artifactKindOf({ title: 'a.png' })).toBe('img')
    expect(artifactKindOf({ title: 'a.jpeg' })).toBe('img')
    expect(artifactKindOf({ title: 'a.zip' })).toBe('file')
    expect(artifactKindOf({ title: 'noext' })).toBe('file')
  })

  it('大小写不敏感', () => {
    expect(artifactKindOf({ title: 'A.HTML' })).toBe('html')
    expect(artifactKindOf({ title: 'A.PDF' })).toBe('pdf')
  })

  it('[2026-11-01] localPath 的扩展名优先（标题常常没有后缀）', () => {
    expect(artifactKindOf({ title: '农污查询理解与主体词改造方案（A' })).toBe('file')
    expect(
      artifactKindOf({
        title: '农污查询理解与主体词改造方案（A',
        localPath: 'C:/Users/WIN/.nexusai/artifacts/sess-1/aaa-agri-react-thinking-and-ask-user.html',
      }),
    ).toBe('html')
    // 路径优先于标题：标题写错后缀也不影响判定
    expect(artifactKindOf({ title: '证据服务走查页面.html', localPath: 'C:/artifacts/s1/ddd-report.pdf' })).toBe('pdf')
    // svg 属图片（矢量）
    expect(artifactKindOf({ title: '柱状图渲染证据 SVG', localPath: 'C:/artifacts/s1/ccc-chart_bar_proof.svg' })).toBe('img')
  })

  it('[2026-11-01] directory 标记优先于扩展名（目录没有扩展名 ⇒ 不能落进 file）', () => {
    expect(artifactKindOf({ title: '农污软件设计包', directory: true })).toBe('dir')
    expect(
      artifactKindOf({
        title: '农污软件设计包',
        localPath: 'D:/code/ai_project/nexusai/.nexusai/worktrees/scene-fusion/docs/software-design-agri',
        directory: true,
      }),
    ).toBe('dir')
  })
})

describe('isTextArtifact', () => {
  it('二进制（docx/xlsx/pdf/pptx）不可编辑 ⇒ 不给「编辑」入口', () => {
    expect(isTextArtifact({ title: 'a.docx' })).toBe(false)
    expect(isTextArtifact({ title: 'a.xlsx' })).toBe(false)
    expect(isTextArtifact({ title: 'a.pdf' })).toBe(false)
    expect(isTextArtifact({ title: 'a.pptx' })).toBe(false)
    expect(isTextArtifact({ title: 'a.png' })).toBe(false)
  })

  it('文本（html/md/json…）可编辑', () => {
    expect(isTextArtifact({ title: 'a.html' })).toBe(true)
    expect(isTextArtifact({ title: 'a.md' })).toBe(true)
    expect(isTextArtifact({ title: 'a.json' })).toBe(true)
  })

  it('[2026-11-01] 目录不可编辑（后端 PUT /content 对目录直接 400「该产物是目录，无法编辑」）', () => {
    expect(isTextArtifact({ title: '农污软件设计包', directory: true })).toBe(false)
  })
})

describe('ArtifactCards', () => {
  it('横向药丸卡：容器按宽度自然换行（不是固定 3 列 ⇒ 窄栏也不挤成一条）', () => {
    render({ items: [art('1', 'a.html'), art('2', 'b.docx'), art('3', 'c.xlsx'), art('4', 'd.pdf')] })
    const wrap = container.querySelector('.artifact-cards')
    expect(wrap).toBeTruthy()
    expect(container.querySelectorAll('.artifact-card').length).toBe(4)
  })

  it('卡内顺序固定：图标 → 标题/大小竖排 → 操作区（标题在图标右侧，不换行成图标在上）', () => {
    render({ items: [art('1', 'a.html', { sizeBytes: 2048 })], onEdit: vi.fn(), onOpenInBrowser: vi.fn() })
    const card = container.querySelector('.artifact-card') as HTMLElement
    const kids = Array.from(card.children).map((n) => n.className)
    expect(kids[0]).toContain('ac-ico')
    expect(kids[1]).toContain('ac-text')
    expect(kids[2]).toContain('ac-actions')
    // 标题与大小在同一个竖排容器里（不是两个平级兄弟 ⇒ 才会上下叠）
    const text = card.querySelector('.ac-text') as HTMLElement
    expect(text.querySelector('.ac-name')?.textContent).toBe('a.html')
    expect(text.querySelector('.ac-size')?.textContent).toBe('2KB')
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

  it('[2026-11-01] 标题没有扩展名 ⇒ 按归档路径的真实扩展名上色（否则满屏无色图标）', () => {
    // 实测：抽取出来的 18 条交付物**标题全都没有后缀**（"思考块与补问交互设计稿 v2"…）
    // ⇒ 只看标题会全落进 k-file（无色）⇒ 必须看 localPath（…-agri-react-thinking-and-ask-user.html）
    render({
      items: [
        art('1', '思考块与补问交互设计稿 v2', { localPath: 'C:/artifacts/s1/aaa-agri-react-thinking.html' }),
        art('2', '农污软件设计包', { localPath: 'C:/artifacts/s1/bbb-agri-query-fix.md' }),
        art('3', '柱状图渲染证据 SVG', { localPath: 'C:/artifacts/s1/ccc-chart_bar_proof.svg' }),
      ],
    })
    expect(container.querySelector('.ac-ico.k-html')).toBeTruthy()
    expect(container.querySelector('.ac-ico.k-md')).toBeTruthy()
    expect(container.querySelector('.ac-ico.k-img')).toBeTruthy()
    // 没有一条落进 k-file（=无色兜底）
    expect(container.querySelector('.ac-ico.k-file')).toBeFalsy()
  })

  it('[2026-11-01] 路径优先于标题（真源是归档文件 ⇒ 标题写错后缀也不影响打开/配色）', () => {
    render({
      items: [art('1', '证据服务走查页面.html', { localPath: 'C:/artifacts/s1/ddd-report.pdf' })],
    })
    expect(container.querySelector('.ac-ico.k-pdf')).toBeTruthy()
    expect(container.querySelector('.ac-ico.k-html')).toBeFalsy()
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

  it('[2026-11-01] 目录卡：文件夹图标 + 大小行写「目录」+ 没有「编辑」/「在浏览器中打开」入口', () => {
    // 实测：真库里有 deliverable 的 local_path 指向目录（目录不会被归档 ⇒ size_bytes 恒 null）
    // ⇒ 只看扩展名会落进 k-file（青色文件图标）且大小行空白 ⇒ 用户完全看不出这是个目录
    render({
      items: [
        art('1', '农污软件设计包（docs/software-design-agri）', {
          localPath: 'D:/code/ai_project/nexusai/.nexusai/worktrees/scene-fusion/docs/software-design-agri',
          sizeBytes: null,
          directory: true,
        }),
      ],
      onEdit: vi.fn(),
      onOpenInBrowser: vi.fn(),
    })
    const card = container.querySelector('.artifact-card') as HTMLElement
    expect(card.querySelector('.ac-ico.k-dir')).toBeTruthy()
    expect(card.querySelector('.ac-ico.k-file')).toBeFalsy()
    // 大小行写「目录」，不是空、也不是大小
    expect(card.querySelector('.ac-size')?.textContent).toBe('目录')
    // 目录既不能编辑也不能在浏览器中打开 ⇒ 不给假入口
    expect(card.querySelector('.ac-act[aria-label="编辑"]')).toBeFalsy()
    expect(card.querySelector('.ac-act[aria-label="在浏览器中打开"]')).toBeFalsy()
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
