/**
 * 围栏语言标识词表 —— 供 rescue 的「info-string 粘连」判据使用。
 *
 * <h2>它解决什么问题</h2>
 * 模型（和人）常把反引号与代码首行写在同一行：
 * ```
 * ```tsxturnRunning={!!thinking}
 * ```
 * 这段在 CommonMark 里**完全合法** —— info string 允许任意字符，于是 `tsxturnRunning={!!thinking}`
 * 整个被当成语言标识，代码块**内容为零**（渲染出来只剩一个空块）。marked 判得没错，
 * 渲染器没有 bug，所以只能由 rescue 还原作者本意。
 *
 * 切点在哪？` ```tsx ` 与 ` turnRunning={…} ` 之间 —— 但真实形态里**两者之间没有任何分隔字符**
 * （`tsx` 直接接 `turnRunning`）⇒ 无法靠找 `<` / `{` 定位，只能靠「已知语言名到哪儿结束」。
 * 这就是本词表存在的**唯一理由**。
 *
 * <h2>⚠️ 维护规则（改这张表前必读）</h2>
 * <ol>
 *   <li><b>只收「确定是语言标识」的词</b>。⛔ 不要放 `text` / `plain` / `code` 这类
 *       可能撞上正文的通用词 —— 它们会在 ` ```text<foo> ` 这种写法里造成误判。</li>
 *   <li><b>别名一并收</b>（`js` / `javascript` / `mjs`；`sh` / `shell` / `bash`）：
 *       长度降序保证最长前缀优先，所以 `shell` 一定先于 `sh` 命中。</li>
 *   <li><b>加入后必须跑</b> `npx vitest run src/markdown/__tests__/fenceLanguages.test.ts`
 *       —— 那里锁了「无重复 / 无空串 / 大小写」三条不变量。</li>
 *   <li>词表只影响**判据的切点定位**；余部是否「像代码开头」由 `rescue.ts` 的
 *       `looksLikeCode` 二次把关（以防 ` ```js title="x" ` 这类合法 info string 被误拆）。</li>
 * </ol>
 *
 * <p><b>排序说明</b>：源码里按可读性分组书写，导出时统一按长度降序 —— 见文件末尾的
 * {@link FENCE_LANGS}。⛔ 不要依赖本数组的书写顺序。
 */
const LANGS_RAW: readonly string[] = [
  // ---- 脚本 / 应用 ----
  'javascript', 'typescript', 'coffeescript', 'livescript',
  'js', 'mjs', 'cjs', 'ts', 'mts', 'cts', 'jsx', 'tsx',
  'python', 'python3', 'py3', 'py', 'cython', 'pyx',
  'ruby', 'rake', 'rb', 'erb',
  'php', 'blade', 'twig',
  'perl', 'pl',
  'lua', 'luau',
  'rscript', 'r',
  // ---- 编译型 ----
  'csharp', 'c#', 'cs', 'fsharp', 'fs',
  'objectivec', 'objc', 'obj-c',
  'java', 'kotlin', 'kts', 'scala',
  'groovy', 'gradle',
  'swift', 'dart',
  'golang', 'go', 'rust', 'rs',
  // ⚠️ `c++` 必须存在：缺了它 ` ```c++ ` 会被 `c` 命中、把 `++` 当成代码首行。
  //    这类「短词吃掉长词」是词表最典型的失效形态（实测打红过），
  //    fenceLanguages.test.ts 有专门的守护用例。
  'c++',
  // 单字符：仅在余部确实紧跟非空白时才会拆（见 rescue 的判据）。
  // ` ```cost = 5 ` / ` ```result = 1 ` 这类本身就是粘连形态，拆开是正确还原。
  'c',
  'vbnet', 'vba', 'vb',
  'fortran', 'f90', 'cobol', 'ada', 'pascal', 'delphi',
  'assembly', 'nasm', 'gas', 'asm',
  // ---- 函数式 ----
  'haskell', 'hs', 'elm', 'ocaml', 'ml',
  'erlang', 'elixir', 'ex', 'exs',
  'clojure', 'cljs', 'edn', 'clj',
  'scheme', 'racket', 'lisp', 'elisp',
  'prolog', 'smalltalk',
  // ----  shell / 终端  ----
  'powershell', 'pwsh', 'shell', 'shellscript', 'bash', 'zsh', 'fish',
  'nushell', 'sh', 'cmd', 'batch', 'bat', 'awk', 'sed', 'tcl',
  // ---- 标记 / 样式 ----
  'markdown', 'mdx', 'md', 'rst', 'asciidoc',
  'html', 'xhtml', 'xml', 'svg',
  'svelte', 'vue', 'astro', 'handlebars', 'mustache', 'ejs', 'pug', 'jade',
  'scss', 'sass', 'less', 'stylus', 'css',
  'latex', 'tex',
  // ---- 数据 / 配置 ----
  'json', 'json5', 'jsonc', 'hjson',
  'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'properties', 'dotenv', 'env',
  'csv', 'tsv',
  'graphql', 'gql',
  'protobuf', 'proto',
  'xpath', 'xsl', 'xslt', 'jq', 'regex', 'regexp',
  // ---- 数据库 ----
  'postgresql', 'postgres', 'plsql', 'tsql', 'sqlite', 'mysql', 'sql',
  'redis', 'mongodb',
  // ---- 基础设施 ----
  'dockerfile', 'docker', 'compose',
  'makefile', 'cmake', 'bazel', 'starlark',
  'nginx', 'apache', 'caddy',
  'verilog', 'systemverilog', 'vhdl',
  'solidity', 'vyper',
  // ---- 文档 / 图 ----
  'plaintext', 'mermaid', 'plantuml',
  // ---- 其它 ----
  'julia', 'nim', 'zig', 'crystal', 'octave', 'matlab', 'mathematica',
  'diff', 'patch', 'http', 'https', 'console', 'terminal',
]

/**
 * 供匹配使用的语言标识列表 —— **按长度降序**（最长前缀优先）。
 *
 * <p>为什么必须降序：判据是 `find(l => info.startsWith(l))`，若 `sh` 排在 `shell` 前面，
 * ` ```shellfoo ` 会被切断成 `sh` + `ellfoo`。降序保证 `shell` 先命中。
 * 同长条目之间无前缀关系，顺序无关。
 */
export const FENCE_LANGS: readonly string[] =
  LANGS_RAW.slice().sort((a, b) => b.length - a.length)
