/**
 * `addPaths` 通道（Tauri `onDragDropEvent` 拖拽 / `plugin-dialog.open()`）的附件判据 ——
 * 从 `Composer.addPaths` 内联逻辑抽出的**可执行、可单测**的纯判定。
 *
 * <b>为什么单独一个模块（而不是并进 `attachmentDelivery.ts`）</b>：两条通道的**判据来源不同**，
 * 各自独立且都合法 ——
 * <ul>
 *   <li>`attachmentDelivery.ts`：`addFiles` 通道，拿到的只有 `File` 对象（**无绝对路径**），判据看 **MIME**
 *       （`File.type`），因为它拿不到可靠扩展名来源；</li>
 *   <li>本模块：`addPaths` 通道，拿到的只有**路径字符串**（Tauri 拖拽被 WebView2 接管，DOM 层拿不到
 *       `File`），判据只能看 **扩展名**。</li>
 * </ul>
 * 两套判据**并存**、不合并（合并必然要在某一侧降级，从而改变某条腿的语义）。
 * 放在不同文件是为了让「这是两套判据」在结构上可见 —— ⛔ 不要顺手统一。
 *
 * <b>两条不变量</b>（`__tests__/pathAttachment.test.ts` 逐格钉住）：
 * <ol>
 *   <li>图片与 PDF 的通道决策**逐字节不变**：`≤5MB → read 腿（base64）`、`>5MB → path`。
 *       图片只有 base64 能变成 image content block（模型直接看到图）；PDF 有自己的内联 document
 *       block 链（后端 `registerBase64Pdf` / `PdfAttachmentProcessor`）。</li>
 *   <li>非图片且非 PDF **一律 path**（与大小无关）：docx/xlsx/zip/txt… 的 base64 后端**零消费方**
 *       ⇒ 静默丢弃（批 ATT-PATH-FE 修的「小 Word/Excel 消失」）。</li>
 * </ol>
 */

import { BASE64_LIMIT, type AttachmentFileType } from './attachmentDelivery'

/** `addPaths` 的扩展名分类结果。 */
export interface PathAttachmentClass {
  /** basename（`C:\a\b.docx` → `b.docx`）—— chip 显示名与投递字段，**不再是去重键**（见 {@link pathDedupKey}）。 */
  name: string
  type: AttachmentFileType
  mediaType: string
  /** 是否图片 —— 与 {@link PathAttachmentClass.isPdf} 一起决定 local-read 分支能否走 path。 */
  isImage: boolean
  isPdf: boolean
}

/** `addPaths` 去重键的命名空间前缀（与 `attachmentDelivery` 的 `md5:` / `file:` 互不串扰）。 */
export const DEDUP_NS_PATH = 'path:'

/**
 * `addPaths` 通道的去重键 = **完整路径**（`C:\a\b.docx` 与 `C:\other\b.docx` 是两个键）。
 *
 * <b>修的两个缺陷（同一次改动）</b>：
 * <ol>
 *   <li><b>同名不同目录被误丢</b>：旧键是 basename ⇒ 拖入 `D:\甲\报告.docx` 之后，
 *       `D:\乙\报告.docx` 会被判重复而丢弃（用户只会看到「少了一个」）。</li>
 *   <li><b>与 `addFiles` 通道撞键</b>：旧实现两条腿共用同一个 basename 命名空间 ⇒
 *       一份叫 `image.png` 的拖入文件会**挡住**随后的同名粘贴截图（而那正是本批要修的主症状）。</li>
 * </ol>
 *
 * ⛔ <b>为什么这里绝不算内容 md5</b>：本通道的**零拷贝设计**是「大文件不进前端内存」的全部依据 ——
 * `stat` 只取 `size`，`>5MB` 的附件**只传路径字符串**给后端同机读盘（见
 * `planPathAttachmentChannel`）。为了判重去 `readFile` 整读一遍，就把这个设计直接破坏了
 * （一个 2GB 的视频会被读进 WebView 内存只为算个哈希）。⇒ 用路径本身作键：**零 I/O、零内存**。
 * 代价如实登记：同一份文件从两个不同路径（复制品）拖入会被视为两份 —— 这是**可接受**的，
 * 因为对 path 通道而言「同一路径」就是后端读盘时的同一份文件。
 */
export function pathDedupKey(path: string): string {
  return `${DEDUP_NS_PATH}${path}`
}

/**
 * 路径（或裸文件名）→ 附件类型 · **逐字等同** `Composer.addPaths` 原内联表达式。
 *
 * 判据只认**最后一段扩展名**（先 `toLowerCase()`），因此 `.PNG` / `.PDF` 命中，而 `.tar.gz` /
 * `.png.zip` 不命中（否则一个改名就能骗过判据）。
 *
 * ⚠️ 原实现写的是 `p.split(/[\\/]/).pop() ?? p`，但 `split` 恒返回非空数组 ⇒ `.pop()` **永不为
 * `undefined`** ⇒ `?? p` 是**死代码**。此处逐字保留：改掉它会改变「路径以分隔符结尾」时的
 * `filename`（实测为**空串**）—— 那是 chip 显示名与投递字段的行为变更，不属本批。
 * （去重键已于批 ATT-DEDUP-KEY 改为**完整路径**（{@link pathDedupKey}），故本处不再影响判重。）
 */
export function classifyAttachmentPath(pathOrName: string): PathAttachmentClass {
  const name = pathOrName.split(/[\\/]/).pop() ?? pathOrName
  const lower = name.toLowerCase()
  const isImage = /\.(png|jpe?g|gif|webp|bmp)$/.test(lower)
  const isPdf = lower.endsWith('.pdf')
  const type: AttachmentFileType = isImage ? 'image' : isPdf ? 'pdf'
    : /\.(mp4|webm|mov)$/.test(lower) ? 'video'
    : /\.(mp3|wav|ogg|m4a)$/.test(lower) ? 'audio' : 'file'
  const mediaType = isImage ? 'image/*' : isPdf ? 'application/pdf'
    : type === 'video' ? 'video/*' : type === 'audio' ? 'audio/*' : 'application/octet-stream'
  return { name, type, mediaType, isImage, isPdf }
}

/**
 * local-read 分支的通道决策：`'path'` = 只传本地绝对路径（后端同机读盘）；`'read'` = 回落
 * 原读盘腿（调用方再按**实读字节数** `bytes.length > BASE64_LIMIT` 决定 base64 还是 upload）。
 *
 * ⚠️ 这里返回 `'read'` 而不是直接给 `'base64'`：读盘腿的最终去向取决于**实读字节数**，而它可能与
 * `stat` 出的 `size` 不同（`stat` 与 `readFile` 之间文件可能被改写）。本函数只做「走不走 path」
 * 这一个判定，不假装知道 I/O 结果。
 *
 * 判据 **逐字等同** 原内联 `info.size > BASE64_LIMIT || (!isImage && !isPdf)`（外层 `if (localRead)`
 * 合并进来，使「非 local-read 模式不传 path」这条不变量可在纯函数层面直接断言）。
 */
export function planPathAttachmentChannel(
  cls: Pick<PathAttachmentClass, 'isImage' | 'isPdf'>,
  size: number,
  localRead: boolean,
): 'path' | 'read' {
  if (localRead && (size > BASE64_LIMIT || (!cls.isImage && !cls.isPdf))) {
    return 'path'
  }
  return 'read'
}
