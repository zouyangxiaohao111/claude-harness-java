import { describe, expect, it } from 'vitest'
import { BASE64_LIMIT, DEDUP_NS_FILE, fileDedupKey } from '../attachmentDelivery'
import { DEDUP_NS_PATH, classifyAttachmentPath, pathDedupKey, planPathAttachmentChannel } from '../pathAttachment'

/**
 * `addPaths` 通道（Tauri 拖拽 `onDragDropEvent` / `plugin-dialog.open()` 拿**绝对路径**）
 * 的两个纯判据守卫：扩展名类型分类 + local-read 分支的「直传本地 path」决策。
 *
 * <b>为什么必须钉住</b>：本通道有两条腿，一条是「读盘进内存 → base64 / upload」，一条是
 * 「只传 path 字符串，后端同机读盘」。走错腿的代价不对称：
 * <ul>
 *   <li>图片被误判成 path ⇒ 模型**看不到图**（`image/*` 只有 base64 能变成 image content block）；</li>
 *   <li>非图片非 PDF 被误判成 base64 ⇒ 后端**零消费方** ⇒ 附件静默消失
 *       （2026-09-18 批 ATT-PATH-FE 修的正是「小 Word/Excel 消失」，见
 *       `ChatService.buildMediaAttachmentNotes` 对 contentId 为空的 `continue`）。</li>
 * </ul>
 *
 * <b>不变量①（本仓最硬的红线）</b>：图片与 PDF 的通道决策**逐字节不变** ——
 * `≤5MB → read 腿（base64）`、`>5MB → path`。
 *
 * <b>不变量②（本批修复）</b>：非图片且非 PDF（docx/xlsx/zip/txt/md/无扩展名…）在
 * `localRead=true` 下**一律 path**，与大小无关。
 *
 * ⚠️ 本模块判据看**扩展名**（`addPaths` 原内联表达式）；`attachmentDelivery.ts` 那套看 **MIME**
 * （`addFiles` 通道）。两套并存且各自独立，本测试**不**试图合并它们。
 */

const KB = 1024
const MB = 1024 * 1024

/**
 * 四档大小。取整以贴合 `stat().size` 的真实形态（`4.9 * MB` 本身是小数，实际读不到）。
 * `5MB` 是**边界**：判据是严格大于（`> BASE64_LIMIT`）⇒ 恰好 5MB 不算过线。
 */
const SIZES = [
  { label: '1KB', bytes: 1 * KB },
  { label: '4.9MB', bytes: Math.floor(4.9 * MB) },
  { label: '5MB（边界）', bytes: 5 * MB },
  { label: '5.1MB', bytes: Math.ceil(5.1 * MB) },
] as const

/** 不变量①：媒体类（图片 + PDF）。小写/大写扩展名都要命中（判据前先 `toLowerCase()`）。 */
const MEDIA_NAMES = ['a.png', 'a.PNG', 'b.jpg', 'b.JPEG', 'c.gif', 'd.webp', 'e.bmp', 'f.pdf', 'f.PDF'] as const

/** 不变量②：非图片非 PDF —— 无论多小都必须走 path。 */
const NON_MEDIA_NAMES = ['g.docx', 'h.xlsx', 'i.zip', 'j.txt', 'k.md', 'noext'] as const

describe('classifyAttachmentPath — 扩展名判据（逐字等同 addPaths 原内联表达式）', () => {
  it.each([
    ['a.png', 'image', 'image/*'],
    ['a.PNG', 'image', 'image/*'],
    ['b.jpg', 'image', 'image/*'],
    ['b.JPEG', 'image', 'image/*'],
    ['c.gif', 'image', 'image/*'],
    ['d.webp', 'image', 'image/*'],
    ['e.bmp', 'image', 'image/*'],
    ['f.pdf', 'pdf', 'application/pdf'],
    ['f.PDF', 'pdf', 'application/pdf'],
  ])('%s → type=%s mediaType=%s', (name, type, mediaType) => {
    const c = classifyAttachmentPath(name)
    expect(c.type).toBe(type)
    expect(c.mediaType).toBe(mediaType)
    expect(c.isImage).toBe(type === 'image')
    expect(c.isPdf).toBe(type === 'pdf')
  })

  it.each([
    ['g.docx', 'file'],
    ['h.xlsx', 'file'],
    ['i.zip', 'file'],
    ['j.txt', 'file'],
    ['k.md', 'file'],
    ['noext', 'file'],
    // 多重扩展名只看**最后一段**：`.tar.gz` / `.png.zip` 都不是图片（否则一个改名就能骗过判据）
    ['x.tar.gz', 'file'],
    ['y.png.zip', 'file'],
    // 点开头但无扩展名：`endsWith('.pdf')` 不命中 ⇒ file
    ['.hidden', 'file'],
  ])('%s → file（无媒体 MIME、无扩展名命中）', (name, type) => {
    const c = classifyAttachmentPath(name)
    expect(c.type).toBe(type)
    expect(c.isImage).toBe(false)
    expect(c.isPdf).toBe(false)
    expect(c.mediaType).toBe('application/octet-stream')
  })

  it('从绝对路径取 basename（Windows 反斜杠 / POSIX 斜杠都要认）', () => {
    expect(classifyAttachmentPath('C:\\win\\q.docx').name).toBe('q.docx')
    expect(classifyAttachmentPath('/abs/path/to/z.PDF').name).toBe('z.PDF')
    // basename 之后仍按扩展名判据分类
    expect(classifyAttachmentPath('/abs/path/to/z.PDF').type).toBe('pdf')
    expect(classifyAttachmentPath('C:\\win\\q.docx').type).toBe('file')
  })

  it('⚠️ 已实测记录：路径以分隔符结尾时 basename 为**空串**（`.pop()` 永不为 undefined ⇒ `?? p` 是死代码）', () => {
    // 这不是「期望行为」，而是原表达式的既成事实 —— 本测试把它钉住，防止有人「顺手修好」
    // 时误以为改了语义（改它会改变 filename，进而改变去重键）。
    expect(classifyAttachmentPath('trailing/').name).toBe('')
    expect(classifyAttachmentPath('trailing/').type).toBe('file')
  })
})

describe('不变量①：图片 / PDF 的通道决策逐字节不变（localRead=true）', () => {
  it.each(MEDIA_NAMES)('%s：≤5MB → read 腿（base64）；>5MB → path', (name) => {
    const cls = classifyAttachmentPath(name)
    for (const { label, bytes } of SIZES) {
      const expected = bytes > BASE64_LIMIT ? 'path' : 'read'
      expect(
        planPathAttachmentChannel(cls, bytes, true),
        `${name} @ ${label}(${bytes}B) 应当走 ${expected}`,
      ).toBe(expected)
    }
  })

  it('恰好 5MB 不算过线（判据是严格大于）—— 与 `> BASE64_LIMIT` 原表达式一致', () => {
    const cls = classifyAttachmentPath('a.png')
    expect(planPathAttachmentChannel(cls, BASE64_LIMIT, true)).toBe('read')
    expect(planPathAttachmentChannel(cls, BASE64_LIMIT + 1, true)).toBe('path')
  })
})

describe('不变量②：非图片且非 PDF 一律走 path（localRead=true）', () => {
  it.each(NON_MEDIA_NAMES)('%s：即使 ≤5MB 也必须走 path（修复前是 base64 ⇒ 后端静默丢弃）', (name) => {
    const cls = classifyAttachmentPath(name)
    for (const { label, bytes } of SIZES) {
      expect(
        planPathAttachmentChannel(cls, bytes, true),
        `${name} @ ${label}(${bytes}B) 必须走 path`,
      ).toBe('path')
    }
  })

  it('反向锚点：同样大小下，媒体类与 file 类的决策在 ≤5MB 档必须**不同**', () => {
    // 若有人把 `!isImage && !isPdf` 削成 `!isImage`（漏掉 PDF 守卫），本断言与上面两条会一起变红
    const small = 1 * KB
    expect(planPathAttachmentChannel(classifyAttachmentPath('a.png'), small, true)).toBe('read')
    expect(planPathAttachmentChannel(classifyAttachmentPath('f.pdf'), small, true)).toBe('read')
    expect(planPathAttachmentChannel(classifyAttachmentPath('g.docx'), small, true)).toBe('path')
  })
})

describe('localRead=false：任何类型都不走 path（回落 read 腿 → base64 / upload）', () => {
  it.each([...MEDIA_NAMES, ...NON_MEDIA_NAMES])('%s：localRead=false ⇒ 永不为 path', (name) => {
    const cls = classifyAttachmentPath(name)
    for (const { label, bytes } of SIZES) {
      expect(
        planPathAttachmentChannel(cls, bytes, false),
        `${name} @ ${label}(${bytes}B)：非 local-read 模式不得传本地 path`,
      ).toBe('read')
    }
  })
})

/**
 * ⭐ 批 ATT-DEDUP-KEY：`addPaths` 的去重键 = **完整路径**（旧键是 basename）。
 *
 * <b>修的两个缺陷（同一次改动，两个方向都要钉）</b>：
 * <ol>
 *   <li><b>同名不同目录被误丢</b>：旧键是 basename ⇒ 拖入 `D:\甲\报告.docx` 之后
 *       `D:\乙\报告.docx` 会被判重复丢弃（用户只看到「少了一个」，无任何提示）。</li>
 *   <li><b>与其他通道撞键</b>：旧实现 `addFiles` / `addPaths` 共用同一个 basename 命名空间
 *       ⇒ 一份叫 `image.png` 的拖入文件会**挡住**随后的同名粘贴截图（正是本批主症状的邻例）。</li>
 * </ol>
 * ⛔ 同时钉住「不得为算内容哈希而读全文件」：本函数**纯字符串**，签名里没有 File/IO
 * ⇒ 零拷贝设计（`stat` 只取 size、大文件只传路径）不会被判重代码破坏。
 */
describe('⭐ pathDedupKey：键 = 完整路径（同名不同目录不得互相覆盖 · 批 ATT-DEDUP-KEY）', () => {
  it('⭐ 同名不同目录 ⇒ 两个键（旧实现都是 basename「报告.docx」⇒ 第二个被误丢）', () => {
    const a = pathDedupKey('D:\\甲\\报告.docx')
    const b = pathDedupKey('D:\\乙\\报告.docx')
    expect(a).not.toBe(b)
    // 反向锚点：basename 确实相同 —— 说明「键不同」不是因为文件名不同，而是因为键不再是文件名
    expect(classifyAttachmentPath('D:\\甲\\报告.docx').name).toBe('报告.docx')
    expect(classifyAttachmentPath('D:\\乙\\报告.docx').name).toBe('报告.docx')
  })

  it('同一路径（Tauri enter/drop 双触发、重复拖放）⇒ 同一个键（去重仍然要生效）', () => {
    expect(pathDedupKey('D:\\甲\\报告.docx')).toBe(pathDedupKey('D:\\甲\\报告.docx'))
  })

  it('与 addFiles 通道的键**不撞**（命名空间不同 ⇒ 「拖入的 image.png」不再挡住「粘贴的 image.png」）', () => {
    const pathKey = pathDedupKey('D:\\甲\\image.png')
    expect(pathKey).not.toBe(fileDedupKey('image.png', 1024))
    expect(pathKey.startsWith(DEDUP_NS_FILE), '不得落进 addFiles 的 file: 命名空间').toBe(false)
    expect(pathKey.startsWith(DEDUP_NS_PATH)).toBe(true)
  })

  it('POSIX 路径同样按整串取键（不同目录不同键）', () => {
    expect(pathDedupKey('/home/u/甲/a.png')).not.toBe(pathDedupKey('/home/u/乙/a.png'))
  })
})
