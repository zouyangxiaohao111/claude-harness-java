/**
 * 中文 markdown 脏输入的「抢救层」（settled 全量渲染前跑一次）。
 *
 * 背景：模型输出的中文 markdown 常把块级标记与正文粘连（`##标题` 缺空格、
 * 表头粘在标题行尾、围栏粘在行尾不闭合），CommonMark 严格解析会把它们降级为
 * 字面文本。本模块在解析【之前】把源码修补成解析器能读懂的形态，然后重解析 ——
 * 不手搓 mdast 节点，正确性由解析器保证。
 *
 * 硬约束（来自设计文档 §3 的历史教训 —— 同类实现 patches.ts 曾因违反而被删）：
 * - C1 语法感知：块级判据来自解析器（passTable/passMarkers 读 mdast position）；
 *   围栏态用行级状态机（passFence）；禁止整文本正则。
 * - C2 只服务 settled：流式臂不调用本模块（设计文档 §5.3 方案 B）。
 * - C3 闸门不得自造块边界：块级判据必须来自真解析器（P1/P2/P3 读 mdast position），
 *   不得手搓块边界状态机。对可能改变块归属的编辑，须有不变量验证（运行时闸门或测试，
 *   取成本较低者）。仅 P0 例外 —— 围栏态是单布尔且需在解析前判决，故用行级状态机，
 *   但该状态机**只建模反引号围栏**（`~~~` 未建模，见文末已知残差），
 *   且闭合须满足「反引号数 >= 开围栏」。
 *
 * 出处：本实现移植自已全库验证的原型（1143 条真实消息：79 改善 / 0 回归 /
 * 干净对照 0 误伤），规则与判据逐条对应 prototype `engine.ts`。
 */

import { parseGfmWithMath } from './parse.ts'

/** 目标首字符类：CJK（汉/扩展A/假名/谚文）。
 *  刻意**窄于** `cjkFriendlyStrong.ts` 的 script-extension 判据 —— 不含注音符号、
 *  兼容汉字、半角片假名与扩展 B+ 星面汉字。改动会作废已跑通的语料验证基线，
 *  如需放宽请单独登记。 */
export const CJK_START = /[\u4e00-\u9fff\u3400-\u4dbf\u3040-\u30ff\uac00-\ud7af]/

/** 一条源码编辑。`start`/`end` 是 UTF-16 偏移（与 `String.prototype.slice`、
 *  mdast `position.*.offset` 同单位），且**相对产生它那一次 `applyEdits` 的 `src`**。
 *  注意：`repair()` 返回的是**各 pass 的子结果**（`RepairResult.fence`/`table`/`markers`），
 *  子结果内的偏移相对**该 pass 自己的输入**（p1 相对 p0 的输出、p2 相对 p1 的输出），
 *  子结果之间不可混用，也不可直接喂回原始 `src`。
 *  `pass`/`kind`/`line` 为诊断字段，供调试与日志记录，不参与应用逻辑。 */
export interface Edit {
  start: number
  end: number
  text: string
  pass: string
  kind: string
  line: string
}

/**
 * 把编辑从后往前应用到源码。
 * @param src 原始字符串
 * @param edits 调用方保证区间两两互不重叠（**本函数不做校验**，违反时后果未定义）。
 *   同 `start` 的多条按数组逆序落位。
 * @returns 应用后的新字符串（`src` 不变）
 */
export function applyEdits(src: string, edits: Edit[]): string {
  const sorted = [...edits].sort((a, b) => b.start - a.start)
  let out = src
  for (const e of sorted) out = out.slice(0, e.start) + e.text + out.slice(e.end)
  return out
}

/** 把一段原文拆成 [{相对偏移, 行}]。 */
export function relLines(raw: string): { off: number; line: string }[] {
  const out: { off: number; line: string }[] = []
  let off = 0
  for (const line of raw.split('\n')) {
    out.push({ off, line })
    off += line.length + 1
  }
  return out
}

/** GFM 分隔行：每格匹配 :?-+:?，且行内至少一个 `-`。 */
export function isSepRow(line: string): boolean {
  const t = line.trim()
  if (!t.includes('|')) return false
  if (!t.includes('-')) return false
  const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|')
  for (const c of cells) if (!/^\s*:?-+:?\s*$/.test(c)) return false
  return true
}

/** 解析树的最小结构：沿用本模块既有的结构式类型，不为类型引入 mdast 依赖。 */
type DocRoot = { children: { type: string }[] }

export interface PassResult {
  src: string
  edits: Edit[]
}

/** P1 的结果。树只在 P1 产生，故单独立子类型 —— 长在共享的 `PassResult` 上会让
 *  `passMarkers(x).root` 这类访问在类型上合法却恒为 `undefined`。 */
export interface TablePassResult extends PassResult {
  /** P1 parse 出的树，供 `repair` 在输入未变时复用 —— **纯优化，不影响任何输出**
   *  （见 `repair` 的短路条件）。**偏移基准是 `passTable` 的输入（即 p0.src）**，
   *  不可与 `out`/原文配对使用。 */
  root: DocRoot
}

export interface FencePassResult extends PassResult {
  aborted: boolean
}

/**
 * P0 · 围栏抢救：把「粘在内容行尾的三反引号」提为独立行（开围栏、闭围栏都提）。
 * 这不是"修复语法"，而是把源码还原成模型本意 —— 它想在那里断行。
 *
 * 平衡闸（fail-safe）：单趟收集编辑的同时维护围栏态；若全文**仍处于未闭合状态**，
 * 整篇放弃（丢弃已收集的编辑）。绝不制造一个会吞掉后文的围栏。
 * 状态机与 CommonMark 同精度：闭合判据要求反引号数 >= 开围栏数。
 * @param src 源码
 * @returns 修补后的源码、编辑列表、以及是否未提交任何提行。`aborted` 为 true
 *  表示「未提交任何提行」—— 既包括闸门因提行后仍不闭合而放弃，也包括原文本身
 *  就未闭合、无可提行的情形。
 */
export function passFence(src: string): FencePassResult {
  const edits: Edit[] = []
  let inFence = false
  let openLen = 0
  for (const { off, line } of relLines(src)) {
    // 行首的合法围栏（开围栏不限行尾；闭合要求行尾仅空白且长度 >= 开围栏）
    const open = /^ {0,3}(`{3,})/.exec(line)
    if (open !== null && !inFence) {
      inFence = true
      openLen = open[1]!.length
    } else if (inFence) {
      const close = /^ {0,3}(`{3,})\s*$/.exec(line)
      if (close !== null && close[1]!.length >= openLen) {
        inFence = false
        openLen = 0
      }
    }
    // 行尾粘连的反引号：提行后等同于"行首出现一个反引号串"，共用同一条长度规则
    const glued = /^(.*[^\s`])(`{3,})\s*$/.exec(line)
    if (glued !== null) {
      const len = glued[2]!.length
      const wasInFence = inFence
      if (!inFence) {
        inFence = true
        openLen = len
      } else if (len >= openLen) {
        inFence = false
        openLen = 0
      }
      edits.push({
        start: off + glued[1]!.length,
        end: off + glued[1]!.length,
        text: '\n',
        pass: 'fence',
        kind: wasInFence ? 'lift-close-fence' : 'lift-open-fence',
        line,
      })
    }
  }
  if (inFence) return { src, edits: [], aborted: true }
  return { src: applyEdits(src, edits), edits, aborted: false }
}

/** 表格规则：本行含 `|`、下一行是分隔行、且第一个 `|` 之前有非空内容 → 返回该 `|` 的下标。
 *  左半段若是 setext 下划线 / thematic break / 空 ATX 标题形状（`---`/`===`/`***`/`___`、
 *  `#`~`######`），返回 -1：在那之前插换行会把前一段文字变成标题或分割线、
 *  或凭空多出一个空标题 —— 宁可少救，不制造新错误。 */
export function tableSplit(line: string, next: string | undefined): number {
  if (next === undefined || !isSepRow(next)) return -1
  const idx = line.indexOf('|')
  if (idx <= 0) return -1
  const left = line.slice(0, idx)
  if (left.trim() === '') return -1
  // 左半段若会自成一类块级标记（setext 下划线 / thematic break / 空 ATX 标题）则拒绝：
  // 在其前插换行会把前一段文字变成标题或分割线，或凭空多出一个空标题。
  if (/^[-=*_ \t]+$/.test(left) || /^#{1,6}\s*$/.test(left)) return -1
  return idx
}

/** 顶层块：position 来自真解析器，必然存在。 */
interface Block {
  type: 'paragraph' | 'heading'
  position: { start: { offset: number; line: number }; end: { offset: number; line: number } }
  /** 行内子节点（P1 的切点守卫要用）。解析器恒提供，但 mdast 类型上 `position` 可选。 */
  children?: {
    type: string
    position?: { start: { offset: number }; end: { offset: number } }
  }[]
}

/** 取顶层段落（P2/P3 复用）。 */
function topParagraphs(root: DocRoot): Block[] {
  return root.children.filter((c) => c.type === 'paragraph') as Block[]
}

/** 切点是否**严格落在**该块某个行内子节点的 position 区间内部（见 passTable 守卫）。
 *  `position` 缺失时按"不在内部"处理 —— 宁可少救，不劈行内构造。
 *
 *  **`text` 节点不在设防范围**：无行内构造的段落整段就是一个 text 节点
 *  （实测 `'##一句话结论|a |b |\n|---|---|\n'` → `paragraph[0,24] > text[0,24]`），
 *  把它算进来会让 P1 对**所有普通段落**失效 —— 那正是本模块最需要救的形态。
 *  text 无语法可劈，切在它内部不会破坏任何行内构造；而每个有语法的行内构造
 *  （inlineCode / strong / link / …）都有自己的节点包住，仍会被拦下。 */
function cutInsideInline(block: Block, cut: number): boolean {
  for (const c of block.children ?? []) {
    if (c.type === 'text' || c.position === undefined) continue
    if (cut > c.position.start.offset && cut < c.position.end.offset) return true
  }
  return false
}

/**
 * P1 · 表格抢救：段落内某行含 `|` 且下一行是 GFM 分隔行时，在第一个 `|` 前插入 `\n`，
 * 让表格行脱离前面的正文/标题，成为能被解析器识别的独立块。
 *
 * 块级判据来自真解析器（mdast position）—— 符合 C3。
 * 该编辑只可能把一行拆成两行。实测 12 例对抗探针中未出现 prose→code（本模块最严重的
 * 失效模式），但拆行后左半段可能自成一类块级标记（setext 标题 `---`/`===`、
 * thematicBreak）—— 该情形已由 tableSplit 的 setext 守卫挡掉。
 * 不变量由测试守住（不新增 code 内容），未加运行时闸门（C3 允许二者取成本较低者）。
 *
 * 切点守卫：切点**严格落在某个行内子节点的 position 区间内**时跳过该行
 * （`用 `a|b` 分隔` 的竖线在反引号里、`**粗|体**` 的在强调里）—— 拆行会把行内构造
 * 劈成两半（inlineCode 消失变字面量、strong 断裂）。判据取自解析器的 position（符合 C3）。
 * 纯 text 节点不算（见 `cutInsideInline`：否则 P1 对所有普通段落失效）。
 * 这条守卫在"分隔行格数与表头行不匹配"时尤其必要：那时行内构造可能没被吸收进 table，
 * 整段仍是 paragraph，切点就会落在行内节点内部。
 *
 * @param headings P1e —— 是否也处理「容器是活标题」的情形
 *   （`## 标题|a |b |`：因带空格已是合法 ATX，整张表被吞进 heading）。实测仅 3 行 / 1 条消息。
 */
export function passTable(src: string, headings = false): TablePassResult {
  const root = parseGfmWithMath(src)
  const edits: Edit[] = []
  const targets = headings
    ? [...topParagraphs(root), ...(root.children.filter((c) => c.type === 'heading') as Block[])]
    : topParagraphs(root)
  for (const p of targets) {
    const a = p.position.start.offset
    const b = p.position.end.offset
    const raw = src.slice(a, b)
    const lines = relLines(raw)
    for (let i = 0; i < lines.length; i++) {
      const cur = lines[i]!
      let next: string | undefined = lines[i + 1]?.line
      if (next === undefined) {
        // 本块的最后一行：下一行在块外，直接去源码里取
        const nl = src.indexOf('\n', a + cur.off + cur.line.length)
        if (nl !== -1) {
          const nl2 = src.indexOf('\n', nl + 1)
          next = src.slice(nl + 1, nl2 === -1 ? undefined : nl2)
        }
      }
      const idx = tableSplit(cur.line, next)
      if (idx > 0 && !cutInsideInline(p, a + cur.off + idx)) {
        edits.push({
          start: a + cur.off + idx,
          end: a + cur.off + idx,
          text: '\n',
          pass: 'table',
          kind: p.type === 'heading' ? 'split-table-from-heading' : 'insert-newline',
          line: cur.line,
        })
      }
    }
  }
  return { src: applyEdits(src, edits), edits, root }
}

/** 一条「看到了但不处理」的诊断记录。**仅供诊断，不参与决策。**
 *  两类语义都在这里登记、用 `pass` 区分：`heading`/`bullet` 是判据拒绝了候选，
 *  `ordered` 是设计有意不做（非目标）。 */
export interface Reject {
  /** 产生该记录的 pass。 */
  pass: 'heading' | 'bullet' | 'ordered'
  /** 拒绝理由（例如 'not-cjk' / 'len>30' / 'out-of-scope'）。 */
  reason: string
  /** 原始行文本。 */
  line: string
  /** 该行在**本 pass 输入**中的全文行号（0 基）—— 注意 P0/P1 的插入会使其与用户原文错位，
   *  换算回用户原文时须减去**该行之前的编辑数**（当前实现里编辑全是插一个 `\n`，故恰好
   *  等于编辑条数；按"编辑数"理解会在未来出现多字符插入时失真）。 */
  lineIndex: number
}

export interface MarkerPassResult extends PassResult {
  rejects: Reject[]
}

/**
 * 标题规则（`##` + 无空格形态）。返回补好空格的行，或一个拒绝理由。
 * 拒绝即保持原样（继续裸露）—— 保守优先，宁可少救不可误伤。
 */
export function headingFix(line: string): { fixed: string } | { reject: string } {
  // `(?!#)` 强制 #{1,6} 取极大 hash 串：否则贪婪+回溯会把 '## 已合规' 拆成 hashes='#'
  // + rest='# 已合规'，误报 hash-count-1（该行明明有 2 个 #）。
  const m = /^(#{1,6})(?!#)(\S.*)$/.exec(line)
  if (m === null) return { reject: 'no-marker' }
  const hashes = m[1]!
  const rest = m[2]!
  if (hashes.length < 2) return { reject: 'hash-count-1' }
  if (rest.includes('**')) return { reject: 'contains-**' }
  // 中文右书名号结尾的行（真实语料命中 4 行）
  if (rest.includes('」')) return { reject: 'contains-」' }
  // 挡住「正文行被整行变成标题」的那道闸（真实语料命中 23 行）
  if (rest.includes('|')) return { reject: 'contains-|' }
  if (/#/.test(rest)) return { reject: 'contains-embedded-hash' }
  // 行内含三反引号串（真实语料命中 4 行）
  if (/`{3,}/.test(rest)) return { reject: 'contains-fence-run' }
  if (/[：。；！？]\s*$/.test(rest)) return { reject: 'ends-sentence-punct' }
  if ([...rest].length > 30) return { reject: 'len>30' }
  if (!CJK_START.test(rest[0]!) && !/^\d+[.、)）]/.test(rest)) return { reject: 'not-cjk-or-ordinal' }
  return { fixed: `${hashes} ${rest}` }
}

/** 列表规则：`-` 后紧跟 CJK 才补空格（`-9/9轮`、`-27个` 这类保持原样）。 */
export function bulletFix(line: string): { fixed: string } | { reject: string } {
  const m = /^-(\S.*)$/.exec(line)
  if (m === null) return { reject: 'no-marker' }
  const rest = m[1]!
  if (!CJK_START.test(rest[0]!)) return { reject: 'not-cjk' }
  return { fixed: `- ${rest}` }
}

/**
 * P2 + P3 · 在**顶层段落**内逐行抢救标题与列表标记。
 *
 * 为什么逐行（而非整段正则）：解析器给的 position 把段落切成物理行后，我们只改
 * **行首那一小段**（`##x` → `## x`），**绝不吞并同段落的其他行** ——
 * 这是第一份原型 8 条回归（整段正文被并进 <h2>）的根治。
 *
 * 该编辑只补一个空格、不改变行数，故无 P1 那类"拆行把前文变成 setext/空标题"的风险；
 * 块级判据来自真解析器（mdast position），符合 C3。
 *
 * 「有意不处理」的形态有两条（设计文档 §4 非目标），两者都只登记诊断（rejects）、不修：
 * 一、行内粘连的「后续列表项」（`-a- b- c` 一行多坨）：`-` 后跟中文也可能是破折号/负号，
 * 无本地判据可分。
 * 二、段内的有序列表形态（`1.内容`）：修它需「插空行 + 改写序号」，改写序号会动内容语义。
 *
 * @param providedRoot 已解析好的树（须与 `src` 逐字节对应）。缺省自己 parse；
 *   `repair` 在输入未变时复用 P1 的树，省掉一次全量 parse。
 */
export function passMarkers(src: string, providedRoot?: DocRoot): MarkerPassResult {
  const root = providedRoot ?? parseGfmWithMath(src)
  const edits: Edit[] = []
  const rejects: Reject[] = []
  for (const p of topParagraphs(root)) {
    const a = p.position.start.offset
    const b = p.position.end.offset
    const raw = src.slice(a, b)
    const lines = relLines(raw)
    // 块内相对行号 → 全文行号（每块算一次，不在内层循环里重算）
    const lineBase = src.slice(0, a).split('\n').length - 1
    for (let i = 0; i < lines.length; i++) {
      const { off, line } = lines[i]!
      const at = a + off
      const lineIndex = lineBase + i
      const h = headingFix(line)
      if ('fixed' in h) {
        // 纯插入一个空格：`h.fixed` 是 `${hashes} ${rest}`，空格恒在 hashes.length 处
        const ins = at + h.fixed.indexOf(' ')
        edits.push({ start: ins, end: ins, text: ' ', pass: 'marker', kind: 'heading', line })
        continue
      }
      if (h.reject !== 'no-marker') {
        rejects.push({ pass: 'heading', reason: h.reject, line, lineIndex })
      }
      const bl = bulletFix(line)
      if ('fixed' in bl) {
        const ins = at + 1
        edits.push({ start: ins, end: ins, text: ' ', pass: 'marker', kind: 'bullet', line })
        continue
      }
      if (bl.reject !== 'no-marker') {
        rejects.push({ pass: 'bullet', reason: bl.reject, line, lineIndex })
      }
      // 残差：段内的有序列表形态有意不处理（设计文档 §4 非目标），仅登记诊断
      if (/^\d+\.[^\s\d]/.test(line) || /^\d+\.\s/.test(line)) {
        rejects.push({ pass: 'ordered', reason: 'out-of-scope', line, lineIndex })
      }
    }
  }
  return { src: applyEdits(src, edits), edits, rejects }
}

export interface RepairResult {
  /** 修补后的源码（交给解析器重解析的就是它）。**权威取本字段**：
   *  恒等于 `markers.src`（P2/P3 是最后一环，其输出即链的终点）。 */
  out: string
  /** 各 pass 的原始结果：各自的偏移**相对各自的输入**，自洽可解释。 */
  fence: FencePassResult
  /** P1 的子结果。**刻意只暴露 `PassResult`（`src`/`edits`）**：`TablePassResult.root`
   *  的唯一消费者是 `repair` 自己的 `reuseRoot`（纯优化），不必外传 —— 外传会让
   *  fail-safe 路径被迫编一棵空树，凭空造出"形状正常却零块"的静默谎。 */
  table: PassResult
  markers: MarkerPassResult
}

/**
 * 生产调用参数（`MarkdownText.renderSettled` 用）。**单一来源** —— 测试与验收脚本须引用它，
 * 否则生产参数变更会静默作废验收数字：套件/脚本会继续按旧参数跑，两边都不报错
 * （本批刚把不变量套件对齐到生产配置，硬编码复述正是那次失配的成因）。
 */
export const PROD_REPAIR_OPTS = { tableHeader: true } as const

/**
 * 抢救链（顺序不可换：P0 先做，才决定后面看到的块结构）。
 *
 * P0 围栏 → P1 表格 → P2/P3 标记。三个 pass 各自读真解析器的 mdast position（符合 C3）；
 * 只在 settled 渲染前调用（C2）。各 pass 的诊断字段（`markers.rejects`）不参与决策
 * （注：`headingFix` 的 `(?!#)` 修正后，'## 已合规' 这类已合法标题不再进 rejects，属预期）。
 *
 * @param src 原始 markdown 源码
 * @param opts.tableHeader 是否启用 P1e（容器是活标题的表格），缺省 false
 * @returns 修补结果：`out` + 三个 pass 各自的子结果（偏移各自自洽，`fence.aborted` 不被吞）
 */
export function repair(src: string, opts: { tableHeader?: boolean } = {}): RepairResult {
  try {
    const p0 = passFence(src)
    const p1 = passTable(p0.src, opts.tableHeader === true)
    // 纯优化（输出逐字节不变）：P1 无编辑 ⇒ `applyEdits(p0.src, [])` 原样返回字符串，
    // 故 p1.src 与 p0.src 逐字节相同 ⇒ P1 的树与 passMarkers 的输入对应，可直接复用，
    // 省掉一次全量 parse（本函数因此通常只 parse 1 次）。
    // 注：`p1.edits.length === 0` 单独就已推出 `p1.src === p0.src`，故 `p0.src === src`
    // 是**冗余的额外收紧**（不是安全必要条件，作用只是「原文未被 P0 触碰」的显式复述）。
    // 保留而不简化：本条件只影响 parse 次数、不影响任何输出，少动一行少一分风险。
    const reuseRoot = p0.src === src && p1.edits.length === 0 ? p1.root : undefined
    const p2 = passMarkers(p1.src, reuseRoot)
    // `table` 显式取 `PassResult` 两字段（不透传 `p1`）：`TablePassResult.root` 的偏移基准是
    // `p0.src`、与 `out` 不配套，运行时的形状就不该与声明不一致（同 `RepairResult.table` 的 JSDoc）。
    return { out: p2.src, fence: p0, table: { src: p1.src, edits: p1.edits }, markers: p2 }
  } catch (err) {
    // fail-safe：抢救层任何异常都不该让消息渲染不出来 —— 退回原文，并显式告警而非静默
    // （静默回退会让「抢救层对所有消息永久失效」没有任何信号，front/CLAUDE.md 规则十二）。
    console.warn('[rescue] repair 失败，已回退原文', err)
    const fallback: PassResult = { src, edits: [] }
    return {
      out: src,
      fence: { src, edits: [], aborted: false },
      table: fallback,
      markers: { src, edits: [], rejects: [] },
    }
  }
}

/* 已知残差 R1–R7 见设计文档 §12：
 *   docs/zjkycode/specs/2026-09-12-markdown-dirty-input-rescue-design.md */
