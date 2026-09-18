/**
 * addFiles 通道（浏览器 HTML5 拖拽 / 原生 `<input type=file>` / 粘贴；Tauri 下 dialog 不可用时的回退）
 * 的附件投递决策 —— 从 `Composer.addFiles` 内联逻辑抽出的**可执行、可单测**的判定 + 投递编排。
 *
 * <b>为什么必须抽出来</b>：本通道拿到的只是 `File` 对象（**没有绝对路径**，见下），一个附件只有三条腿可走：
 * <ul>
 *   <li>{@code 'base64'}：dataURL 随消息直传。后端**只有两个消费方** —— image
 *       （`LlmAgentLoop.registerRunPromptImages` → image content block）与 pdf
 *       （`registerRunPromptPdfs` → document/页图 block）。给其它类型发 base64 ⇒ 后端零消费方
 *       ⇒ **静默丢弃**（`ChatService.resolveAttachments` 原样透传 base64 → `buildMediaAttachmentNotes`
 *       命中 `isMediaAttachmentType` 却因 contentId 为空而 `continue`，且该 `continue` **无任何日志**）。</li>
 *   <li>{@code 'upload'}：multipart 上传拿 contentId → 后端附件表落盘 → `buildMediaAttachmentNotes`
 *       注入「本地路径=…」说明，模型可据路径自行用工具读取。</li>
 *   <li>{@code 'reject'}：前端**同步拒绝**（提示 + **不生成 chip**）—— 响亮失败。
 *       只用于「原实现必然静默丢弃」的格子（见 {@link planAttachmentChannel}），故为**零回归**改动。</li>
 * </ul>
 *
 * ⛔ <b>本模块的恒定红线</b>：不允许产出「chip 已显示、模型却收不到」的结局。
 * 每个附件必须落进 (a) 真的进入投递通道，或 (b) 同步拒绝；异步失败必须**撤下 chip**并提示。
 *
 * <b>勘察结论：Tauri v2 下 `File` 没有真实路径，本通道无法改走 path 通道</b>（三处独立源码证据）：
 * <ol>
 *   <li>`@tauri-apps/api@2.11.0`：全包无 `File` 的 `path` 注入（grep `Object.defineProperty` 只命中
 *       `webviewWindow.js` 的 API 包装，无 File 原型改造）；</li>
 *   <li>`tauri@2.11.5` 的 webview 初始化脚本只有 `print.js` / `toggle-devtools.js` / `zoom-hotkey.js`，
 *       不含路径注入；</li>
 *   <li>`wry@0.55.1` `src/webview2/mod.rs:150-157`：注册拖拽 handler 时**显式**
 *       `SetAllowExternalDrop(false)`（注释逐字：「Disable file drops, so our handler can capture it」）
 *       ⇒ 外部文件拖入被 WebView2 原生层接管，DOM `drop` 事件根本拿不到 `File`；路径只从
 *       `onDragDropEvent` 出来（那是 `addPaths` 通道，不属本模块）。
 *       `File.path` 是 **Electron** 的 API（`webUtils.getPathForFile`），不是 Tauri 的。</li>
 * </ol>
 */

import type { AttachmentRequest } from '@/api/types'
import { md5Hex } from './md5'

/**
 * 附件类型 —— **直接取 API 契约的联合类型**（`AttachmentRequest['type']`），不另立字面量：
 * 多的那一份联合迟早与契约漂移，而漂移只会在联调时才暴露。
 * 判据与 `Composer.addFiles` 原内联实现逐字一致：image/pdf/video/audio 看 MIME，pdf 兼看扩展名。
 */
export type AttachmentFileType = AttachmentRequest['type']

/** 投递通道。`'reject'` = 同步拒绝（无 chip），不是「丢弃」。 */
export type AttachmentChannel = 'base64' | 'upload' | 'reject'

/** 拒绝原因分类（UI 文案由调用方拼，本模块不产用户可见字符串）。 */
export type AttachmentRejectReason = 'unsupported' | 'no-session'

/** 本通道拿到的最小文件形状（`File` 结构上满足它，测试可传假件；避免模块依赖 DOM 类型）。 */
export interface AttachmentFileLike {
  name: string
  type: string
  size: number
}

/** 5MB 直传上限（Anthropic image block 硬限；后端 `MediaLimitGuard` / `MediaLimitConstants` 同值）。 */
export const BASE64_LIMIT = 5 * 1024 * 1024

/**
 * 后端 base64 直传的**全部**消费方 —— 只有这两类能让内容真正进模型上下文。
 * 改动此表前必须先在后端找到新增消费方，否则就是给自己开一条静默丢弃通道。
 */
export const BASE64_CONSUMER_TYPES: readonly AttachmentFileType[] = ['image', 'pdf']

/**
 * `File` → 附件类型 · **逐字等同** `Composer.addFiles` 原内联三元表达式（不引入第三套判据：
 * `addPaths` 那套看扩展名，本函数这套看 MIME，两者各自独立，不在本批合并）。
 *
 * @param name         文件名（`File.name`）
 * @param rawMediaType `File.type`（可能为空串 ⇒ 回落 `application/octet-stream`，与原实现一致）
 */
export function classifyAttachmentFile(
  name: string,
  rawMediaType: string,
): { type: AttachmentFileType; mediaType: string } {
  const mediaType = rawMediaType || 'application/octet-stream'
  const type: AttachmentFileType = mediaType.startsWith('image/') ? 'image'
    : name.toLowerCase().endsWith('.pdf') ? 'pdf'
    : mediaType.startsWith('video/') ? 'video'
    : mediaType.startsWith('audio/') ? 'audio'
    : 'file'
  return { type, mediaType }
}

/**
 * (类型 × 大小) → 通道。**大小阈值与原实现逐字一致**（严格大于 `BASE64_LIMIT` 才走 upload）。
 *
 * - `image`/`pdf`：≤5MB 走 base64（有消费方，能真正送达）；>5MB 走 upload（拿 contentId → 路径说明）
 * - `video`/`audio`：**一律 upload** —— 这两类的 base64 后端零消费方（静默丢弃），
 *   而 upload 白名单（`AttachmentController.isAllowedType`）接受 `video/*`、`audio/*`
 * - `file`（docx/xlsx/zip/txt… Office MIME 不命中 media 前缀者）：**一律 upload（与大小无关）**
 *   - 原实现 ≤5MB ⇒ `reject`，唯一理由是「当时 upload 白名单（`AttachmentController.isAllowedType`）
 *     **不含 doc/docx/xls/xlsx** ⇒ 走 upload 必然 400」，而 base64 腿后端零消费方（静默丢弃）
 *     ⇒ 同步拒绝是当时唯一的「响亮失败」。
 *   - **前提已变**：用户裁定 upload 白名单放开到 doc/docx/xls/xlsx（后端批 ATT-UPLOAD-DOC）
 *     ⇒ 继续同步拒绝会**砍掉一条现在能送达的腿**（模型拿不到用户发的 Word/Excel）⇒ 改走 upload。
 *     改后 file 类**只有一条腿**，不再有「同一个 .docx 时好时坏」的双形态。
 *     ⚠️ **依赖声明**：本改动依赖后端批 ATT-UPLOAD-DOC 落地。**若那批未落地**，本格退化为
 *     「upload 400 → 撤 chip + onFailed + 释放去重键」（`deliverAttachmentFile` 既有守卫），
 *     即**响亮失败**，仍优于静默丢弃 —— 但用户会看到一次往返后的失败提示，而非立即拒绝。
 *
 * <b>⚠️ 实测纠正（2026-09-18 · 以 master `e92f4b36` 的 `AttachmentController` 源码为准，非注释）</b>：
 * 旧注释称 `file` 类 `>5MB` 腿「原来就可能成功」——**对 docx/xlsx/zip/txt 不成立**。实测
 * `isAllowedType:412-431` 的扩展名兜底只含 `png/jpg/jpeg/gif/webp/bmp/mp4/webm/mkv/mp3/wav/ogg/flac`，
 * Office MIME 亦不命中 `image/|video/|audio/` 前缀 ⇒ 一个大 `.docx` 走 upload **今天就是 400**
 * （`AttachmentController:172-175` → 「附件上传失败：不支持的文件类型」）；`verifyMagic` 的兜底腿
 * （`:528`）只认「无 mediaType ⇒ 试图片魔数」，也不认 OOXML。⇒ **交给 ATT-UPLOAD-DOC 放开。**
 *
 * <b>`'reject'` 通道现状（如实登记）</b>：本函数**当前已无任何输入会返回 `'reject'`**
 * （image/pdf → base64|upload，video/audio/file → upload）。该通道与
 * `AttachmentDeliveryDeps.onRejected` 的 `'unsupported'` 分支**有意保留**：它是本模块恒定的
 * 「chip 已显示就必须能送达，否则同步拒绝」红线的出口 —— 将来若新增一类「base64 无消费方 且
 * upload 白名单不收」的类型，落点就是这里（届时补 `verifyMagic`/白名单实证后恢复返回 `'reject'`）。
 * ⛔ 不要因为「暂时不可达」删掉它：删掉后新增类型只能落进 upload 腿静默 400。
 */
export function planAttachmentChannel(type: AttachmentFileType, size: number): AttachmentChannel {
  if (BASE64_CONSUMER_TYPES.includes(type)) {
    return size > BASE64_LIMIT ? 'upload' : 'base64'
  }
  // video/audio/file：base64 无消费方 ⇒ 只能 upload（白名单已覆盖这三类）
  return 'upload'
}

/**
 * ⭐ <b>去重键 = 身份，不是名字</b>（2026-09-18 批 ATT-DEDUP-KEY 的用户裁定：
 * 「图片应该是按照字节 md5 判断是否重复」）。
 *
 * <b>为什么必须换掉「文件名」</b>（有日志铁证）：WebView2 给剪贴板位图合成的名字**恒为
 * `image.png`** ⇒ 截图后第二个 Ctrl+V 被按名判重丢弃（日志：`addFiles 汇总 接受=0 … 重复=1`）；
 * 而绕道微信再复制时剪贴板变成「文件」、名字是唯一 UUID ⇒ 反而能粘上。同一个动作两种结局，
 * 判据却是名字 —— 名字根本不是身份。
 *
 * <b>三条腿各用什么键 / 为什么</b>：
 * <ul>
 *   <li><b>base64 腿</b>（图片与 PDF ≤5MB，见 {@link planAttachmentChannel}）：
 *       {@link contentDedupKey} = **内容字节 md5**。字节本来就要整读进内存做 base64
 *       ⇒ 哈希**零额外 I/O、零额外内存**（这正是用户裁定「按字节 md5」的落点）。
 *       实测代价（本机 Node 24 跑 `utils/md5.ts`，非 WebView2）：**≈84 MB/s** ⇒
 *       5MB（本腿上界）≈59ms、200KB 的常见截图 ≈2ms —— 每个附件一次，在 async 投递循环里。</li>
 *   <li><b>upload 腿</b>：{@link fileDedupKey} = 名 + 大小。⛔ 这里**不**算内容 md5，理由见该函数。</li>
 *   <li><b>path 腿</b>（`addPaths`，不在本模块）：**完整路径**，见 `pathAttachment.pathDedupKey`。
 *       ⛔ 不得为算 md5 而读全文件 —— 零拷贝是本仓明确设计。</li>
 * </ul>
 * 三条腿的键**带不同前缀**（`md5:` / `file:` / `path:`）故互不串扰：同一份文件经
 * 「粘贴」与「拖拽」进来会各自成键（这也是修缺陷的一部分 —— 旧实现两条腿共用一个
 * basename 命名空间，一份叫 `image.png` 的拖入文件会**挡住**后来的同名粘贴截图）。
 */
export const DEDUP_NS_CONTENT = 'md5:'
export const DEDUP_NS_FILE = 'file:'

/**
 * upload 腿去重键 = `名字 + 单个空格 + 大小`。分隔符不可省：否则 `("a1", 2)` 与 `("a", 12)`
 * 会拼成同一个串；大小恒为十进制数字，加空格后两类拼接不再可能相等。
 *
 * ⛔ <b>为什么不在这里算内容 md5（内存代价论证）</b>：upload 腿承载**任意大小**的文件 ——
 * `video` / `audio` / `file` 类与大小无关、一律走 upload（见 {@link planAttachmentChannel}），
 * 即这条腿上会出现几百 MB 的视频。要拿内容 md5 就必须先 `arrayBuffer()` 把**整个文件**读进内存：
 * <ol>
 *   <li>峰值内存多占「文件本身大小」一份（浏览器 FormData 上传本来是流式读盘，不整读）；</li>
 *   <li>哈希是同步计算 ⇒ 大文件上要**卡住主线程**：实测 `utils/md5.ts` ≈84 MB/s
 *       （本机 Node 24）⇒ 100MB 要 1.2 秒、1GB 要 12 秒，期间占位 chip 根本出不来
 *       （而占位 chip 存在的意义正是「大文件上传立刻给反馈」）；</li>
 *   <li>超过 ArrayBuffer 上限（Chrome ~2GB）时 `arrayBuffer()` 直接抛错 ⇒ 一个**判重**判断
 *       反倒把「能被上传」的文件变成「加不进来」。</li>
 * </ol>
 * ⇒ 用「名 + 大小」：**严格强于原键**（原键只有名字）—— 同名同大小视为同一份（覆盖
 * 「Tauri enter/drop 双触发」「同一份文件拖两次」），同名不同大小不再误判重复
 * （正是 `image.png` 那一类被测出来的假重复）。代价如实登记：同名同大小但内容不同的两个
 * 大文件会被判重复（base64 腿没有这个代价 —— 那里是内容身份）。
 */
export function fileDedupKey(name: string, size: number): string {
  return `${DEDUP_NS_FILE}${name} ${size}`
}

/** base64 腿去重键 = 内容字节 md5（**身份 = 内容**，用户裁定）。 */
export function contentDedupKey(bytes: Uint8Array): string {
  return `${DEDUP_NS_CONTENT}${md5Hex(bytes)}`
}

/** chip 生成参数（`uploading=true` 表示上传中，调用方应写占位 contentId 让 doSend 挡住未完成批次）。 */
export interface AcceptedAttachment {
  type: AttachmentFileType
  filename: string
  mediaType: string
  size: number
  /**
   * 本次投递占用的去重键。⭐ 调用方**必须**把它存进 chip，并在「移除该 chip / 清空 / 发送后」
   * **用它**释放 —— 键已不是文件名，仍按 filename 释放 ⇒ 移除了 chip 但键还在
   * ⇒ **同一张图再也加不回来**。
   */
  dedupKey: string
  /** base64 腿：dataURL（调用方按 type==='image' 决定是否用作 preview） */
  base64?: string
  /** upload 腿：true（contentId 由 onUploaded 异步回填） */
  uploading?: boolean
}

/** 投递副作用（由 React 组件注入；测试注入假件即可断言「有没有 chip」）。 */
export interface AttachmentDeliveryDeps<F extends AttachmentFileLike> {
  /** 当前会话 id；缺失 ⇒ upload 腿不可用（附件归属靠 sessionId，后端缺值直接 400）⇒ 响亮拒绝 */
  sessionId?: string
  /**
   * 去重键是否已被占用（键见 {@link fileDedupKey} / {@link contentDedupKey}，与 `addPaths` 同源语义：先到先得）。
   * ⛔ 参数是**键**，不是文件名 —— 调用方不得退回按名字判断。
   */
  isDuplicate: (key: string) => boolean
  /** 占用去重键（进入任一投递通道时调用） */
  reserve: (key: string) => void
  /** 释放去重键（异步失败回滚；原实现漏了这步 ⇒ 失败后同名文件再也加不进来） */
  release: (key: string) => void
  /** **生成 chip**：只有真正进入投递通道才调用 —— reject 分支绝不调用（红线） */
  onAccepted: (a: AcceptedAttachment) => void
  /**
   * 上传成功 → 回填真 contentId。
   * <b>第一个参数是去重键，不是文件名</b>：改键后**允许出现两个同名 chip**（两张都叫
   * `image.png` 的不同截图），按 filename 回填会**回填到错的那一个**。filename 仍一并回传，
   * 供调用方提示/日志。
   */
  onUploaded: (key: string, filename: string, contentId: string) => void
  /**
   * 异步失败（上传/读盘）→ 调用方必须**撤下 chip**并提示（红线：不留「有 chip 收不到」）。
   * 同 {@link AttachmentDeliveryDeps.onUploaded}：按**键**定位被撤的那个 chip，filename 供提示。
   */
  onFailed: (key: string, filename: string, reason: string) => void
  /** 同步拒绝 → 只提示，**不生成 chip** */
  onRejected: (filename: string, reason: AttachmentRejectReason) => void
  /**
   * File → dataURL **并**把**同一份字节**交回（去重键要按内容算，见 {@link contentDedupKey}）。
   * 交回字节而不是让本模块另读一次：base64 腿本来就要整读，多读一遍纯属浪费。
   * 读盘失败应 reject/throw，由本模块转成 onFailed。
   */
  encodeDataUrl: (file: F, mediaType: string) => Promise<{ dataUrl: string; bytes: Uint8Array }>
  /** multipart 上传（失败应 reject/throw，由本模块转成 onFailed） */
  upload: (file: F, sessionId: string) => Promise<{ contentId: string }>
}

/** 一个文件的处理结局。 */
export type AttachmentOutcome =
  | 'base64'              // 已生成 chip，base64 已就绪
  | 'upload'              // 已生成占位 chip，上传在飞（结果经 onUploaded / onFailed 落地）
  | 'reject-unsupported'  // 同步拒绝（无 chip）
  | 'reject-no-session'   // 同步拒绝（无 chip）
  | 'duplicate'           // **同一身份**已在列表内（不是丢弃：那份已经在附着列表里）
  | 'failed'              // 同步读盘失败（无 chip；上传失败经 onFailed 异步落地，不计入本结局）

/** 整批汇总（**只统计同步结局**；上传失败在上传完成后经 onFailed 落地，见模块头注）。 */
export interface AttachmentDeliverySummary {
  /** 实际进入投递通道的个数 —— 「已添加 N 个附件」只能用它，**不得用入口文件数**（原实现虚报） */
  accepted: number
  rejected: { filename: string; reason: AttachmentRejectReason }[]
  /** 被判「同一身份」的**文件名**（供提示用）—— 判据是去重键（内容/路径），不是名字 */
  duplicates: string[]
  failed: { filename: string; reason: string }[]
}

function errorText(err: unknown): string {
  const text = err instanceof Error ? err.message : String(err)
  return text.length > 200 ? `${text.slice(0, 200)}…` : text
}

/**
 * 投递单个文件。**顺序**（每一道都对应一条红线）：
 * 1. 分类 → 决策；`'reject'` ⇒ 同步拒绝（不 reserve、不 chip），返回
 * 2. `'upload'`：算键（名+大小）→ 去重 → 无 sessionId ⇒ 同步拒绝 → reserve → chip(占位)
 *    → 起上传（**不 await**，保持整批并行）；失败 → release + onFailed（撤 chip）
 * 3. `'base64'`：无 sessionId ⇒ 同步拒绝（**故意排在读盘之前**：没会话就没必要把文件读进内存）
 *    → 读盘（失败 ⇒ 不 reserve、不 chip，只 onFailed）→ 按**内容 md5** 去重 → reserve + chip
 *
 * <b>⚠️ 与改键前的两处顺序差异（如实登记，均为「内容身份」的必然代价）</b>：
 * <ul>
 *   <li>base64 腿的**去重检查只能在读盘之后**（键 = 内容 ⇒ 不读就不知道）⇒
 *       「同一张图粘两次」会**多读一次盘 + 多算一次 md5**才判出 duplicate（≤5MB，可忽略）。
 *       改键前那次「读盘前的廉价预判」因此取消。</li>
 *   <li>base64 腿的 `no-session` 检查前移到读盘之前 ⇒ 「无会话 + 内容重复」这个组合的结局
 *       由 `duplicate` 变为 `reject-no-session`（两者都**不生成 chip**，只是提示文案不同）。</li>
 * </ul>
 */
export async function deliverAttachmentFile<F extends AttachmentFileLike>(
  file: F,
  deps: AttachmentDeliveryDeps<F>,
): Promise<AttachmentOutcome> {
  const { type, mediaType } = classifyAttachmentFile(file.name, file.type)
  const channel = planAttachmentChannel(type, file.size)
  if (channel === 'reject') {
    deps.onRejected(file.name, 'unsupported')
    return 'reject-unsupported'
  }
  const sessionId = deps.sessionId
  if (channel === 'upload') {
    const key = fileDedupKey(file.name, file.size)
    if (deps.isDuplicate(key)) return 'duplicate'
    if (!sessionId) {
      deps.onRejected(file.name, 'no-session')
      return 'reject-no-session'
    }
    deps.reserve(key)
    deps.onAccepted({ type, filename: file.name, mediaType, size: file.size, uploading: true, dedupKey: key })
    void deps.upload(file, sessionId).then(
      (r) => deps.onUploaded(key, file.name, r.contentId),
      (err) => {
        deps.release(key)
        deps.onFailed(key, file.name, errorText(err))
      },
    )
    return 'upload'
  }
  if (!sessionId) {
    deps.onRejected(file.name, 'no-session')
    return 'reject-no-session'
  }
  let dataUrl: string
  let bytes: Uint8Array
  try {
    const read = await deps.encodeDataUrl(file, mediaType)
    dataUrl = read.dataUrl
    bytes = read.bytes
  } catch (err) {
    // 此时还没有内容键（字节没读回来）⇒ 用 file 键报回。该键必然**未被 reserve**
    //（本腿只在读盘成功后 reserve），故调用方按键撤 chip 会落空、只会用到 filename 做提示。
    deps.onFailed(fileDedupKey(file.name, file.size), file.name, errorText(err))
    return 'failed'
  }
  const key = contentDedupKey(bytes)
  // 同批内同内容：先完成先占（本循环顺序 await ⇒ 与 addPaths 的「先到先得」一致）
  if (deps.isDuplicate(key)) {
    return 'duplicate'
  }
  deps.reserve(key)
  deps.onAccepted({ type, filename: file.name, mediaType, size: file.size, base64: dataUrl, dedupKey: key })
  return 'base64'
}

/**
 * 投递一批文件（顺序处理，便于去重判定确定化；上传腿本身是并行的）。
 * 返回汇总供调用方生成提示 —— **拒绝/失败必须让用户看见**，不得静默。
 */
export async function deliverAttachmentFiles<F extends AttachmentFileLike>(
  files: readonly F[],
  deps: AttachmentDeliveryDeps<F>,
): Promise<AttachmentDeliverySummary> {
  const summary: AttachmentDeliverySummary = { accepted: 0, rejected: [], duplicates: [], failed: [] }
  for (const file of files) {
    const outcome = await deliverAttachmentFile(file, deps)
    if (outcome === 'base64' || outcome === 'upload') {
      summary.accepted += 1
    } else if (outcome === 'reject-unsupported') {
      summary.rejected.push({ filename: file.name, reason: 'unsupported' })
    } else if (outcome === 'reject-no-session') {
      summary.rejected.push({ filename: file.name, reason: 'no-session' })
    } else if (outcome === 'duplicate') {
      summary.duplicates.push(file.name)
    } else {
      summary.failed.push({ filename: file.name, reason: '读盘失败' })
    }
  }
  return summary
}
