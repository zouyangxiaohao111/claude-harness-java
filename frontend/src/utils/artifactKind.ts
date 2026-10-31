/**
 * 产物类型判定（**纯逻辑** · 不依赖 React ⇒ 卡片组件与打开分发共用同一份口径）。
 *
 * <p><b>WHY 按标题扩展名判</b>：后端 {@code kind} 字段是 {@code deliverable | source}
 * （交付物 / 来源），**不是文件类型** ⇒ 想知道「这是个网页还是文档」只能看标题后缀。
 */
export type ArtifactKind = 'html' | 'docx' | 'pptx' | 'xlsx' | 'pdf' | 'md' | 'img' | 'file'

/** 扩展名 → 产物类型。 */
export function artifactKindOf(title: string): ArtifactKind {
  const ext = (title.split('.').pop() ?? '').toLowerCase()
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

/** 该产物是否按文本处理（可就地编辑 + 保存写回归档副本）。 */
export function isTextArtifact(title: string): boolean {
  const ext = (title.split('.').pop() ?? '').toLowerCase()
  return TEXT_EXTS.has(ext)
}
