/** 按文件名判定二进制预览格式（RightPreview 附件 / FileViewModal 项目文件共用） */
export const isDocxName = (name: string) => /\.docx$/i.test(name)
export const isXlsxName = (name: string) => /\.xlsx$/i.test(name)

/**
 * 文件类型分类 · 参考 DSH `@deepseek-ai/dsh-client-ui-primitives` 的 `FileTypeIcon`：
 * - 代码/配置：品牌色方牌 + 1~2 字符标记（颜色取自 DSH CodeFileIcon artwork）
 * - 其余：Word/Excel/PPT/PDF/图片/视频/文件夹/Git 仍用线性/徽章图标
 * 分类顺序：Git 文件 → 文件名规则（Dockerfile/Makefile/.env…）→ Markdown 文件名 → 扩展名 → other。
 */
export type FileKind =
  | 'folder'
  | 'git'
  | 'word'
  | 'excel'
  | 'ppt'
  | 'pdf'
  | 'image'
  | 'video'
  | 'markdown'
  | 'other'
  | 'code'
  | 'html'
  | 'css'
  | 'scss'
  | 'javascript'
  | 'typescript'
  | 'react'
  | 'vue'
  | 'svelte'
  | 'java'
  | 'kotlin'
  | 'python'
  | 'go'
  | 'rust'
  | 'c'
  | 'cpp'
  | 'csharp'
  | 'php'
  | 'ruby'
  | 'swift'
  | 'dart'
  | 'shell'
  | 'batch'
  | 'powershell'
  | 'yaml'
  | 'json'
  | 'xml'
  | 'sql'
  | 'toml'
  | 'env'
  | 'docker'
  | 'makefile'
  | 'graphql'
  | 'protobuf'

/** 品牌色方牌（代码/配置类）：bg 底色、fg 标记色、mark 1~3 字符。 */
export interface CodeTile {
  bg: string
  fg: string
  mark: string
}

/** 方牌配色 · 底色/品牌色取自 DSH CodeFileIcon；HTML 按用户定稿用 </>。 */
export const CODE_TILES: Partial<Record<FileKind, CodeTile>> = {
  html: { bg: '#E37933', fg: '#FFFFFF', mark: '</>' },
  css: { bg: '#1572B6', fg: '#FFFFFF', mark: '#' },
  scss: { bg: '#FBEFF5', fg: '#CC6699', mark: 'S' },
  javascript: { bg: '#F0DB4F', fg: '#323330', mark: 'JS' },
  typescript: { bg: '#007ACC', fg: '#FFFFFF', mark: 'TS' },
  react: { bg: '#20232A', fg: '#61DAFB', mark: 'R' },
  vue: { bg: '#ECF8F3', fg: '#41B883', mark: 'V' },
  svelte: { bg: '#FFF0EB', fg: '#FF3E00', mark: 'S' },
  java: { bg: '#F4F7FA', fg: '#0074BD', mark: 'J' },
  kotlin: { bg: '#F4F1FF', fg: '#7F52FF', mark: 'Kt' },
  python: { bg: '#F2F4F7', fg: '#3776AB', mark: 'Py' },
  go: { bg: '#E7F9FC', fg: '#00ADD8', mark: 'Go' },
  rust: { bg: '#F3E6DD', fg: '#2B2B2B', mark: 'Rs' },
  c: { bg: '#EEF4F8', fg: '#659AD2', mark: 'C' },
  cpp: { bg: '#E8F2F8', fg: '#00599C', mark: 'C+' },
  csharp: { bg: '#F3EEF7', fg: '#68217A', mark: 'C#' },
  php: { bg: '#F0EFF8', fg: '#777BB4', mark: 'P' },
  ruby: { bg: '#FCEDEC', fg: '#CC342D', mark: 'Rb' },
  swift: { bg: '#F05138', fg: '#FFFFFF', mark: 'Sw' },
  dart: { bg: '#E8F4FC', fg: '#0175C2', mark: 'Dt' },
  shell: { bg: '#303642', fg: '#7EE787', mark: '$_' },
  batch: { bg: '#2F3540', fg: '#C9D1D9', mark: '>_' },
  powershell: { bg: '#2671BE', fg: '#FFFFFF', mark: 'PS' },
  yaml: { bg: '#FCEEEF', fg: '#CB171E', mark: 'Y' },
  json: { bg: '#F4F4F4', fg: '#5A5A5A', mark: '{}' },
  xml: { bg: '#EAF3FA', fg: '#005FAD', mark: 'X' },
  sql: { bg: '#336791', fg: '#FFFFFF', mark: 'Db' },
  toml: { bg: '#F5F1EA', fg: '#9C4221', mark: 'T' },
  env: { bg: '#F2F4F7', fg: '#6B7280', mark: 'EN' },
  docker: { bg: '#E8F4FC', fg: '#2496ED', mark: 'Dk' },
  makefile: { bg: '#EEF0F2', fg: '#6D8086', mark: 'Mk' },
  graphql: { bg: '#FBEAF3', fg: '#E10098', mark: 'G' },
  protobuf: { bg: '#EAF3FA', fg: '#4285F4', mark: 'Pb' },
  code: { bg: '#EDF2FE', fg: '#4176E6', mark: '</>' },
}

/** 扩展名 → 类型（先于其它规则生效的只有下方文件名集合）。 */
const EXTENSION_KINDS: Record<string, FileKind> = {
  html: 'html', htm: 'html',
  css: 'css',
  scss: 'scss', sass: 'scss', less: 'scss',
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', es6: 'javascript',
  ts: 'typescript', mts: 'typescript', cts: 'typescript',
  jsx: 'react', tsx: 'react',
  vue: 'vue', svelte: 'svelte',
  java: 'java',
  kt: 'kotlin', kts: 'kotlin',
  py: 'python', pyi: 'python', pyw: 'python', pyx: 'python',
  go: 'go',
  rs: 'rust',
  c: 'c', h: 'c',
  cpp: 'cpp', cc: 'cpp', cxx: 'cpp', 'c++': 'cpp', hpp: 'cpp', hh: 'cpp',
  hxx: 'cpp', 'h++': 'cpp', ipp: 'cpp', tpp: 'cpp',
  cs: 'csharp', csx: 'csharp',
  php: 'php', php3: 'php', php4: 'php', php5: 'php', phps: 'php', phtml: 'php',
  rb: 'ruby', rake: 'ruby', gemspec: 'ruby',
  swift: 'swift',
  dart: 'dart',
  sh: 'shell', bash: 'shell', zsh: 'shell', fish: 'shell', ksh: 'shell', csh: 'shell', tcsh: 'shell',
  bat: 'batch', cmd: 'batch',
  ps1: 'powershell', psd1: 'powershell', psm1: 'powershell',
  yml: 'yaml', yaml: 'yaml',
  md: 'markdown', mdx: 'markdown', markdown: 'markdown',
  json: 'json', json5: 'json', jsonc: 'json',
  xml: 'xml', xsd: 'xml', xsl: 'xml', xslt: 'xml', dtd: 'xml', plist: 'xml',
  sql: 'sql',
  toml: 'toml',
  ini: 'env', cfg: 'env',
  // Office / 文档 / 媒体（非方牌，走原有字标/线条图标）
  pdf: 'pdf',
  doc: 'word', docx: 'word', rtf: 'word', odt: 'word', pages: 'word',
  xls: 'excel', xlsx: 'excel', xlsm: 'excel', xlsb: 'excel', xlt: 'excel',
  xltx: 'excel', xltm: 'excel', ods: 'excel', ots: 'excel', fods: 'excel',
  numbers: 'excel', csv: 'excel', tsv: 'excel',
  ppt: 'ppt', pptx: 'ppt', key: 'ppt',
  mp4: 'video', mov: 'video', m4v: 'video', webm: 'video', mkv: 'video',
  avi: 'video', mpg: 'video', mpeg: 'video',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', svg: 'image',
  webp: 'image', avif: 'image', bmp: 'image', ico: 'image', tif: 'image',
  tiff: 'image', heic: 'image', heif: 'image',
  gql: 'graphql', graphql: 'graphql',
  proto: 'protobuf',
  // 其余语言统一走通用代码方牌 </>（DSH CodeFileIcon 里单独的缩写图标这里不铺开）
  astro: 'code', clj: 'code', cljc: 'code', cljs: 'code', cmake: 'code',
  edn: 'code', erl: 'code', escript: 'code', ex: 'code', exs: 'code',
  hs: 'code', lhs: 'code', lua: 'code', m: 'code', mm: 'code', pch: 'code',
  pl: 'code', pm: 'code', pod: 'code', t: 'code', r: 'code', rmd: 'code',
  sc: 'code', scala: 'code', sol: 'code', wasm: 'code', wast: 'code', wat: 'code',
  zig: 'code', mak: 'makefile', mk: 'makefile',
}

/** 特殊文件名（无扩展名 / 固定名）。 */
const NAME_KINDS: Record<string, FileKind> = {
  dockerfile: 'docker',
  'docker-compose.yml': 'docker',
  'docker-compose.yaml': 'docker',
  'compose.yml': 'docker',
  'compose.yaml': 'docker',
  makefile: 'makefile',
  gnumakefile: 'makefile',
  bsdmakefile: 'makefile',
  'package.json': 'json',
  'package-lock.json': 'json',
  'npm-shrinkwrap.json': 'json',
  '.bashrc': 'shell',
  '.bash_profile': 'shell',
  '.profile': 'shell',
  '.zshrc': 'shell',
  '.zprofile': 'shell',
  '.env': 'env',
  'cmakelists.txt': 'code',
}

/** Git 元数据文件 · 单独一类（分支图标 + Git 橙）。 */
const GIT_FILE_NAMES = new Set([
  '.gitattributes', '.gitconfig', '.gitignore', '.gitmodules', '.mailmap', 'commit_editmsg',
])

/** README / CHANGELOG / CONTRIBUTING 按 Markdown 渲染。 */
const MARKDOWN_NAMES = new Set(['readme', 'changelog', 'contributing'])

/** 文件名 → 展示分类（大小写不敏感；未知回落 other）。 */
export function classifyFileType(name: string): FileKind {
  const base = name
    .slice(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
    .toLowerCase()
  if (GIT_FILE_NAMES.has(base)) return 'git'
  const named = NAME_KINDS[base]
  if (named) return named
  if (base.startsWith('.env.')) return 'env'
  if (base.startsWith('dockerfile.')) return 'docker'
  if (base.endsWith('.dockerfile')) return 'docker'
  if (MARKDOWN_NAMES.has(base)) return 'markdown'
  const dot = base.lastIndexOf('.')
  const ext = dot < 0 ? '' : base.slice(dot + 1)
  return EXTENSION_KINDS[ext] ?? 'other'
}

/** 非方牌类型的图标颜色（线条/徽章用 currentColor）。 */
export const FILE_KIND_COLORS: Record<FileKind, string> = {
  // 方牌类：保留各自标记色，供 RightPanel 的 span color 使用（方牌内部另有显式色）
  ...Object.fromEntries(
    Object.entries(CODE_TILES).map(([k, v]) => [k, v.fg]),
  ) as Record<string, string>,
  folder: '#F59E0B',
  git: '#F05032',
  word: '#2B579A',
  excel: '#217346',
  ppt: '#D24726',
  pdf: '#DC2626',
  image: 'rgb(139, 118, 246)',
  video: 'rgb(139, 118, 246)',
  markdown: '#14B8A6',
  other: '#9AA3B2',
} as Record<FileKind, string>
