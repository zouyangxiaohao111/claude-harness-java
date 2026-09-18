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

/** chip 生成参数（`uploading=true` 表示上传中，调用方应写占位 contentId 让 doSend 挡住未完成批次）。 */
export interface AcceptedAttachment {
  type: AttachmentFileType
  filename: string
  mediaType: string
  size: number
  /** base64 腿：dataURL（调用方按 type==='image' 决定是否用作 preview） */
  base64?: string
  /** upload 腿：true（contentId 由 onUploaded 异步回填） */
  uploading?: boolean
}

/** 投递副作用（由 React 组件注入；测试注入假件即可断言「有没有 chip」）。 */
export interface AttachmentDeliveryDeps<F extends AttachmentFileLike> {
  /** 当前会话 id；缺失 ⇒ upload 腿不可用（附件归属靠 sessionId，后端缺值直接 400）⇒ 响亮拒绝 */
  sessionId?: string
  /** 去重键是否已被占用（键 = 文件名，与 addPaths 同源语义：先到先得） */
  isDuplicate: (filename: string) => boolean
  /** 占用去重键（进入任一投递通道时调用） */
  reserve: (filename: string) => void
  /** 释放去重键（异步失败回滚；原实现漏了这步 ⇒ 失败后同名文件再也加不进来） */
  release: (filename: string) => void
  /** **生成 chip**：只有真正进入投递通道才调用 —— reject 分支绝不调用（红线） */
  onAccepted: (a: AcceptedAttachment) => void
  /** 上传成功 → 回填真 contentId */
  onUploaded: (filename: string, contentId: string) => void
  /** 异步失败（上传/读盘）→ 调用方必须**撤下 chip**并提示（红线：不留「有 chip 收不到」） */
  onFailed: (filename: string, reason: string) => void
  /** 同步拒绝 → 只提示，**不生成 chip** */
  onRejected: (filename: string, reason: AttachmentRejectReason) => void
  /** File → dataURL（读盘失败应 reject/throw，由本模块转成 onFailed） */
  encodeDataUrl: (file: F, mediaType: string) => Promise<string>
  /** multipart 上传（失败应 reject/throw，由本模块转成 onFailed） */
  upload: (file: F, sessionId: string) => Promise<{ contentId: string }>
}

/** 一个文件的处理结局。 */
export type AttachmentOutcome =
  | 'base64'              // 已生成 chip，base64 已就绪
  | 'upload'              // 已生成占位 chip，上传在飞（结果经 onUploaded / onFailed 落地）
  | 'reject-unsupported'  // 同步拒绝（无 chip）
  | 'reject-no-session'   // 同步拒绝（无 chip）
  | 'duplicate'           // 同名已在列表内（不是丢弃：那份已经在附着列表里）
  | 'failed'              // 同步读盘失败（无 chip；上传失败经 onFailed 异步落地，不计入本结局）

/** 整批汇总（**只统计同步结局**；上传失败在上传完成后经 onFailed 落地，见模块头注）。 */
export interface AttachmentDeliverySummary {
  /** 实际进入投递通道的个数 —— 「已添加 N 个附件」只能用它，**不得用入口文件数**（原实现虚报） */
  accepted: number
  rejected: { filename: string; reason: AttachmentRejectReason }[]
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
 * 2. 去重（同名已在列表 ⇒ 'duplicate'）
 * 3. 无 sessionId ⇒ 同步拒绝（不 chip）
 * 4. `'upload'`：reserve → chip(占位) → 起上传（**不 await**，保持整批并行）；失败 → release + onFailed（撤 chip）
 * 5. `'base64'`：先读盘（失败 ⇒ 不 reserve、不 chip，只 onFailed），读回后**再查一次重名**
 *    （同名同批的第二个不该再生成一个 chip），最后 reserve + chip
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
  if (deps.isDuplicate(file.name)) {
    return 'duplicate'
  }
  const sessionId = deps.sessionId
  if (!sessionId) {
    deps.onRejected(file.name, 'no-session')
    return 'reject-no-session'
  }
  if (channel === 'upload') {
    deps.reserve(file.name)
    deps.onAccepted({ type, filename: file.name, mediaType, size: file.size, uploading: true })
    void deps.upload(file, sessionId).then(
      (r) => deps.onUploaded(file.name, r.contentId),
      (err) => {
        deps.release(file.name)
        deps.onFailed(file.name, errorText(err))
      },
    )
    return 'upload'
  }
  let dataUrl: string
  try {
    dataUrl = await deps.encodeDataUrl(file, mediaType)
  } catch (err) {
    deps.onFailed(file.name, errorText(err))
    return 'failed'
  }
  // 读盘期间可能已有同名项占位（同批重名：先完成先占）⇒ 与 addPaths 的「先到先得」一致
  if (deps.isDuplicate(file.name)) {
    return 'duplicate'
  }
  deps.reserve(file.name)
  deps.onAccepted({ type, filename: file.name, mediaType, size: file.size, base64: dataUrl })
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
