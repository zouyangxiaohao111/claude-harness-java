/**
 * 产物类型判定（**纯逻辑** · 不依赖 React ⇒ 卡片组件与打开分发共用同一份口径）。
 *
 * <p><b>WHY 类型要看「真实归档文件」而不是标题</b>：后端 {@code kind} 字段是
 * {@code deliverable | source}（交付物 / 来源），**不是文件类型** ⇒ 想知道「这是个网页还是文档」
 * 只能看扩展名。但抽取出来的**标题常常没有扩展名**（实测：产物「农污查询理解与主体词改造方案（A」
 * 「思考块与补问交互设计稿 v2」「AGRI REACT 交互设计稿 v2」等 18 条交付物**全都没有后缀**）
 * ⇒ 只看标题 ⇒ 全部落进 {@code file} ⇒ 图标一个颜色都没有、html 产物还点不开。
 *
 * <p>真源是 {@code localPath}（归档副本的真实文件名，**带扩展名**）：
 * {@code .../artifacts/<sessionId>/122ae3f9cd31-agri-react-thinking-and-ask-user.html}
 * ⇒ 判定优先级 = <b>localPath 扩展名 → 标题扩展名</b>（标题只在没有路径、或路径也没后缀时兜底）。
 *
 * <p><b>目录例外</b>：目录**不会被归档**（后端只复制普通文件）⇒ 它的路径必然在归档目录之外，
 * 且**没有扩展名** ⇒ 只按扩展名判会落进 {@code file}（青色文件图标，误导）。
 * 后端在出站 DTO 上给了 {@code directory} 标记（**读时判定、不落库**）⇒ 这里优先认它。
 */
export type ArtifactKind = 'html' | 'docx' | 'pptx' | 'xlsx' | 'pdf' | 'md' | 'img' | 'file' | 'dir'

/**
 * 产物判定入参（只要这三样 ⇒ 卡片组件与打开分发直接传产物 DTO 即可，
 * ⛔ 不会各传 title+localPath 漏掉一个）。
 */
export interface ArtifactLike {
  title: string
  localPath?: string | null
  /** `localPath` 指向的是目录（后端读时判定）。 */
  directory?: boolean | null
}

/** 取一个路径/文件名上的小写扩展名（无扩展名 ⇒ 空串）。 */
function extOf(pathOrName: string): string {
  const name = pathOrName.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? ''
  const i = name.lastIndexOf('.')
  if (i <= 0 || i === name.length - 1) return ''
  return name.slice(i + 1).toLowerCase()
}

/**
 * 判定产物类型。
 *
 * @param a 产物（标题 + 归档副本路径 + 目录标记）
 */
export function artifactKindOf(a: ArtifactLike): ArtifactKind {
  // 目录优先于扩展名（目录没有扩展名 ⇒ 只看扩展名会落进 file ⇒ 图标/配色都误导）
  if (a.directory === true) return 'dir'
  const ext = (a.localPath ? extOf(a.localPath) : '') || extOf(a.title)
  if (ext === 'html' || ext === 'htm') return 'html'
  if (ext === 'docx' || ext === 'doc') return 'docx'
  if (ext === 'pptx' || ext === 'ppt') return 'pptx'
  if (ext === 'xlsx' || ext === 'xls') return 'xlsx'
  if (ext === 'pdf') return 'pdf'
  if (ext === 'md' || ext === 'markdown') return 'md'
  if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico', 'avif'].includes(ext)) return 'img'
  return 'file'
}

/** 可编辑（纯文本）扩展名白名单：二进制产物 PUT /content 会 400 ⇒ 前端不给「编辑」入口。 */
const TEXT_EXTS = new Set([
  'txt', 'text', 'log', 'csv', 'tsv', 'json', 'jsonl', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg',
  'xml', 'html', 'htm', 'md', 'markdown', 'css', 'scss', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx',
  'py', 'java', 'go', 'rs', 'sh', 'bash', 'sql', 'properties', 'env', 'gitignore',
])

/**
 * 该产物是否按文本处理（可就地编辑 + 保存写回归档副本）。
 *
 * @param a 产物（标题 + 归档副本路径 + 目录标记）
 */
export function isTextArtifact(a: ArtifactLike): boolean {
  // 目录不可编辑（后端 PUT /content 对目录直接 400「该产物是目录，无法编辑」）
  if (a.directory === true) return false
  const ext = (a.localPath ? extOf(a.localPath) : '') || extOf(a.title)
  return TEXT_EXTS.has(ext)
}
