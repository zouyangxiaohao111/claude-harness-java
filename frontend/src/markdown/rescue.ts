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
 *
 * P1.5（拆粘连行 + 放宽补空格判据，2026-10-09）移植自先期验证原型，判据经
 * 真库全量评估（3393 行裸奔语料：拆行 899 行命中 = table 578 / fence 142 / numlist 70 /
 * hash2 66 / dash 62，各类样本逐条人工审过；补空格相对旧 headingFix 去掉
 * 长度 / 含双星 / 含右书名号 / 首字符 四道限制 —— 它们在真库上误杀真标题）。
 * 逐条对应原型，未加新规则；唯一例外见 `headingFix`（`：/；` 结尾的拒绝按 2026-10-09 用户裁定去掉）。
 */

import { parseGfmWithMath } from './parse.ts'
import { FENCE_LANGS } from './fenceLanguages.ts'

/** 目标首字符类：CJK（汉/扩展A/假名/谚文）。
 *  刻意**窄于** `cjkFriendlyStrong.ts` 的 script-extension 判据 —— 不含注音符号、
 *  兼容汉字、半角片假名与扩展 B+ 星面汉字。改动会作废已跑通的语料验证基线，
 *  如需放宽请单独登记。 */
export const CJK_START = /[\u4e00-\u9fff\u3400-\u4dbf\u3040-\u30ff\uac00-\ud7af]/

/**
 * 已知的围栏语言标识（**按长度降序**，用于 info-string 粘连判据的最长前缀匹配）。
 *
 * <p>为什么需要白名单：真实的手滑形态 ` ```tsxturnRunning={…} ` 里，语言名与代码首行之间
 * **没有任何分隔字符** —— 切点只能由「已知语言名到哪儿结束」来定，找 `<` / `{` 会切错位置。
 *
 * <p>词表本身在 {@link ./fenceLanguages.ts}（含维护规则），这里只按长度降序取用。
 */
const KNOWN_FENCE_LANGS: readonly string[] = FENCE_LANGS

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
 * P0 · 围栏抢救：把「粘在内容行尾 / 夹在行中间的三反引号」提为独立行
 * （开围栏、闭围栏都提）。这不是"修复语法"，而是把源码还原成模型本意 ——
 * 它想在那里断行。
 *
 * 平衡闸（fail-safe）：单趟收集编辑的同时维护围栏态；若全文**仍处于未闭合状态**，
 * 整篇放弃（丢弃已收集的编辑）。绝不制造一个会吞掉后文的围栏。
 * 状态机与 CommonMark 同精度：闭合判据要求反引号数 >= 开围栏数。
 *
 * <p><b>为什么必须同时处理「行中间」的围栏串（2026-11-05 修）</b>：
 * 旧实现只认「行尾粘连」（`^(.*[^\s`])(`{3,})\s*$`），于是
 * {@code ##你现在配的是什么```yaml# config/…} 这种**围栏后还跟着内容**的行被完全忽略。
 * 后果不是「少救一处」，而是**状态机整篇跑偏**：这个真正的开围栏没被看见 ⇒
 * 后面那行「行尾 ```」被当成**开围栏** ⇒ 全文判未闭合 ⇒ 平衡闸整篇放弃 ⇒
 * 本可救的其它提行全丢，版面被吞几十行（真库 msg a9ffc3f4：L13 之后 29 行被吞）。
 *
 * <p><b>行中间的围栏串只可能是开围栏</b>：CommonMark 的闭合围栏要求反引号后
 * 「只能有空白」，而后继还有内容 ⇒ 提行后它只会开启一个带 info string 的围栏。
 * 故：不在围栏内 ⇒ 开围栏（openLen = 反引号数）；已在围栏内 ⇒ **状态不变**
 * （那一行只是代码块的普通内容行，不会闭合当前围栏）。
 *
 * @param src 源码
 * @returns 修补后的源码、编辑列表、以及是否未提交任何提行。`aborted` 为 true
 *  表示「未提交任何提行」—— 既包括闸门因提行后仍不闭合而放弃，也包括原文本身
 *  就未闭合、无可提行的情形。
 */
export function passFence(src: string): FencePassResult {
  // 先按新规则（含行中间围栏）跑；若整篇判未闭合，**退回旧规则**（只认行尾粘连）再判一次。
  // WHY 必须退回：新规则会把「本是段落里的反引号串」也纳入围栏态，某些文档因此从
  //   「旧规则下能完整闭合」变成「整篇放弃」—— 那是**功能倒退**（实测语料 1 例：
  //   ec08aaed，旧链可正常救 0 处、新规则却让整篇降级）。退回后至少不比改动前更差。
  const lifted = liftFences(src, true)
  if (!lifted.aborted) return lifted
  // 第二档：允许把「围栏内、行中间、后面还跟着正文」的 ``` 判成**闭围栏**。
  // WHY 需要这一档（2026-11-11 真库 msg 86456756 实测）：模型偶发丢换行，把闭合围栏与
  //   下一句正文并到同一行（`…agri_ask_user``` ↑**没有 search_api**`）。按 CommonMark，
  //   围栏内的 ``` 只是代码内容 ⇒ 这一处不被认作闭合 ⇒ **围栏状态从此整体错位一位** ⇒
  //   后面每个 ``` 的开/闭角色全部反转 ⇒ 全文判未闭合 ⇒ 平衡闸把整篇的修复全丢掉
  //   （实测该消息：h2 0→3、table 1→2 的修复确实生效，但两个代码块仍各吞掉后文）。
  //   本档专门破这个死锁：只要**它能让全文达到平衡**才采用（`!aborted` 即平衡），
  //   否则退回旧规则 —— 绝不提交一个自己都判不平衡的结构。
  const aggressive = liftFences(src, true, true)
  if (!aggressive.aborted) return aggressive
  return liftFences(src, false)
}

/**
 * {@link passFence} 的提行实现。
 *
 * @param allowMidline 是否也处理「夹在行中间」的反引号串。false = 改动前的旧规则。
 * @param allowMidlineClose 是否允许把「围栏内、行中间、后接正文」的 ``` 判成闭围栏。
 *   仅由 {@link passFence} 的第二档启用（保守档失败时才试）。
 */
function liftFences(src: string, allowMidline: boolean, allowMidlineClose = false): FencePassResult {
  const edits: Edit[] = []
  let inFence = false
  let openLen = 0
  for (const { off, line } of relLines(src)) {
    // 行首的合法围栏（开围栏不限行尾；闭合要求行尾仅空白且长度 >= 开围栏）
    // ⚠️ 不在此 continue：同一行既可能是行首开围栏、又可能在行尾粘连闭围栏
    //   （`\`\`\`js code\`\`\``），原有的 open+glued 同处一行语义必须保留。
    const open = /^ {0,3}(`{3,})/.exec(line)
    if (open !== null && !inFence) {
      inFence = true
      openLen = open[1]!.length
      // [info-string 粘连] 把代码首行手滑写进了 info string：
      //   ` ```tsx<MarkdownTextV2 … ` / ` ```tsxturnRunning={…} ` / ` ```sqlSELECT … ` ——
      //   整行只开一个围栏，语言名成了 `tsxturnRunning`，真正的首行代码被当成 info string
      //   丢掉（唯一一行时 ⇒ 代码块**内容为零**，只渲染出一个空块）。
      //
      // 为什么这在 CommonMark 里**合法**、因此只能靠判据救：info string 允许任意字符。
      //   marked 判得完全正确，渲染器没有 bug —— 这条不是「修语法」，是还原作者本意。
      //
      // ⭐⭐ 判据只有一条：**语言名之后紧跟非空白字符**。
      //   合法 info string 的语言名与元数据之间**必须有空白**
      //   （` ```js title="x" `、` ```bash -e `、` ```js {1,3} `）⇒ rest 以空白开头或为空；
      //   粘连形态里语言名直接接代码（`tsx` + `turnRunning=`、`sql` + `SELECT`）⇒ rest 非空且首字符非空白。
      //   这个区分是**结构性**的，不依赖猜「余部像不像代码」——早期版本用
      //   `rest.startsWith('<') || 标识符紧跟 =([` 只能救 JSX 与赋值两种形态，
      //   实测 ` ```shellcd ` / ` ```sqlSELECT ` / ` ```scss$color ` 全部漏网。
      //
      //   切点为什么必须靠**语言名白名单**：真实形态里语言名与代码之间可能**没有任何分隔字符**
      //   （`tsxturnRunning={}` 的正确切点在 `tsx` 与 `turnRunning` 之间，那里什么都没有），
      //   所以只能由「已知语言名到哪儿结束」来定。词表见 ./fenceLanguages.ts（含维护规则）。
      // ⚠️ 只在 `!inFence` 分支 ⇒ 代码块**内部**内容行里出现的 ``` 不受影响。
      const infoStart = open[0].length
      const info = line.slice(infoStart)
      const langHit = KNOWN_FENCE_LANGS.find((l) => info.startsWith(l))
      if (langHit !== undefined) {
        const rest = info.slice(langHit.length)
        // rest 非空 且 首字符不是空白（空格 / tab 等）
        if (rest.length > 0 && !/^\s/.test(rest)) {
          const at = off + infoStart + langHit.length
          edits.push({
            start: at,
            end: at,
            text: '\n',
            pass: 'fence',
            kind: 'split-info-string',
            line,
          })
        }
      }
    } else if (inFence) {
      const close = /^ {0,3}(`{3,})\s*$/.exec(line)
      if (close !== null && close[1]!.length >= openLen) {
        inFence = false
        openLen = 0
      } else if (allowMidlineClose) {
        // [行内闭围栏] 围栏内出现「``` + 空白 + 正文」⇒ 判成闭围栏，并把 ``` **独占一行**。
        //   真库形态：`…agri_ask_user``` ↑**没有 search_api**`（模型丢换行导致）
        //   目标形态：`…agri_ask_user` / ``` / `↑**没有 search_api**`
        //   ⚠️ 必须插**两个**换行（``` 前后各一）。只插前面那个的话，``` 会带着后续正文
        //   留在新行开头（` ``` ↑**…**`）—— 那按 CommonMark 是**开围栏**（info string 是
        //   后面那句话），结果比不修更糟（实测把整张表格吞进了代码块）。
        //   第二个换行插在 `\S` 之前（吃掉中间的空格），否则新行以空格开头会变成缩进代码块。
        //   ⚠️ 三道保守闸：
        //   ① `.*?` 取**第一个**围栏串（同一行可能有多个）；
        //   ② 围栏串长度须 >= 开围栏长度（与 CommonMark 闭合要求一致）；
        //   ③ 余下正文里**不许再有反引号**（有多处围栏串的多半是代码内容，不是粘连）。
        //   ④ 余下正文须**像自然语言**：含 CJK，或以 ↑/→/*/#/> 这类正文标记开头。
        //      WHY：围栏内本就在讲 markdown 的文档（`写 ``` 来开代码块`）不该被拆。
        //      宁可少救（英文散文的粘连会被漏掉），也不制造新的错误。
        //   ⭐ 最终还是由外层平衡闸兜底：本档产出的结果若不闭合，passFence 会整档丢弃。
        const mc = /^(.*?[^\s`])(`{3,})([ \t]+)(\S.*)$/.exec(line)
        const trailer = mc?.[4] ?? ''
        const looksProse = /[\u4e00-\u9fff]/.test(trailer) || /^[↑→←↓*#>!-]/.test(trailer)
        if (mc !== null && mc[2]!.length >= openLen && !trailer.includes('`') && looksProse) {
          inFence = false
          openLen = 0
          const atFence = off + mc[1]!.length
          // 第二个编辑把「``` 与正文之间的空白」**替换**成换行（不是插入）：
          //   插入会留下 ` ```  ` 那样的行尾空格，且正文新行以空格开头会被当成缩进代码块。
          const wsStart = atFence + mc[2]!.length
          edits.push({
            start: atFence,
            end: atFence,
            text: '\n',
            pass: 'fence',
            kind: 'lift-midline-close-fence',
            line,
          })
          edits.push({
            start: wsStart,
            end: wsStart + mc[3]!.length,
            text: '\n',
            pass: 'fence',
            kind: 'lift-midline-close-fence',
            line,
          })
          continue
        }
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
      continue
    }
    if (!allowMidline) continue
    // 行中间夹着的反引号串（后面还有内容）—— 见上方 JSDoc。
    // ⚠️ CommonMark 规定：反引号围栏的 **info string 里不能再出现反引号**
    //   （spec「If the info string comes after a backtick fence, it may not contain any backtick characters」）。
    //   实测语料：`##① key为什么是英文```…如 \`applies_to_phase\` / …` 这类段落里的反引号串
    //   后面带着行内代码 ⇒ 提行后 **不构成围栏**、整段仍是段落，反而会把后文吞进段落。
    //   ⇒ info string 含反引号的一律不动（保持原样，不做任何猜测）。
    // ⚠️ 必须用**非贪婪** `.*?` 取**第一个**反引号串：同一行可能有多处
    //   （`##标题```info…​```后文` 是被压扁的一对围栏），贪婪会让匹配点滑到最后一处，
    //   于是检查的是「第二段的 info string」而非真正要提行的那一处（实测语料出现过）。
    const mid = /^(.*?[^\s`])(`{3,})(\S.*)$/.exec(line)
    if (mid !== null && !inFence && !mid[3]!.includes('`')) {
      inFence = true
      openLen = mid[2]!.length
      edits.push({
        start: off + mid[1]!.length,
        end: off + mid[1]!.length,
        text: '\n',
        pass: 'fence',
        kind: 'lift-midline-open-fence',
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

/** 顶层节点：本闸门只用到 `type` 与起始偏移（position 来自真解析器）。 */
type TopNode = { type: string; position?: { start: { offset: number } } }

/** P1 拆点闸门：拆在该 `|` 之前**是否真能拼出一张表** —— 判据是**拿真解析器裁定**：
 *  把这次拆分单独应用到源码、重解析，看**拆出的右半段起点**是否真出现一个顶层 `table` 节点
 *  （插入的 `\n` 占一位 ⇒ 右半段起点 = `probe.start + 1`）。真出现才保留这次拆分。
 *
 *  为什么需要：拆行只把表头行从前面劈开、**并不修复格数不匹配**；格数不匹配时右半段与分隔行
 *  仍不成表，而左半段已被拆走 ⇒ **净掉字**（实测 S1：`'## 参数 | 说明\n|---|---|\n|a|b|\n'` 拆后
 *  产物 `'## 参数 \n| 说明\n|---|---|\n|a|b|\n'`，heading 文本只剩 `参数`）。
 *
 *  历史（2026-09-18 批次 B → 本版）：闸门最初用「右半段按 `|` 朴素切分的格数 == 分隔行格数」
 *  近似，与 GFM 真解析器**两个方向都不一致**（真解析器认转义竖线 `\|`、行内代码与链接 title
 *  的 `|` 也各有语义）：
 *  - 过度拒绝：`'## 表格|指标 \| 单位|数值\n|---|---|\n'` 朴素切出 3 格 vs 2 格 ⇒ 判「不成表」不拆，
 *    而真解析器拆后出 `['heading','table']`（基线 a3ee1bb2 的结果）⇒ 真回归。
 *  - 漏网：`'## 标题|a \| b\n|---|---|\n'` 朴素切出 2 格 vs 2 格 ⇒ 放行，而 `\|` 是字面量、真解析器
 *    只认 1 格 ⇒ 拆完 `['heading','paragraph']` —— 正是本闸门要防的形态。
 *  改为直接问解析器后两个方向同时修好，且**不再残留任何格数近似**（符合 C1/C3：块级判据来自
 *  真解析器，不自造边界状态机）。
 *
 *  为什么不是「把右半段 + 分隔行两行当片段解析」：片段口径要成立必须额外论证「这两行的判定与
 *  前置块上下文无关」。实测（2026-09-18 本机，`parseGfmWithMath`）在 2142 个候选点（7 种行首前缀
 *  × 17 种右半段 × 7 种分隔行 × 3 种尾部）上两种口径**零分歧**，即片段口径**可用**；此处仍取
 *  真文档口径，只为免去该局部性假设（代价相同量级：每个候选行一次 parse，候选要求「本行 `|`
 *  前有非空内容 **且** 下一行是分隔行」，真实语料中极稀疏，无候选时零额外 parse）。
 *  注：批次 B 的朴素近似用的是 `line.slice(idx + 1)` —— 把**拆点那个 `|` 也算掉了**，右半段
 *  口径本身就错（真拆出的右半段从该 `|` 开始）：`'## 标题||a|b\n|---|---|\n'` 朴素按 `|a|b`
 *  算 2 格 ⇒ 放行，真解析器拆后是 `['heading','paragraph']`（右半段 `||a|b` 是 3 格）。
 *
 *  `probe` 是**已构造好的候选编辑**（与最终入列的同形），本函数不修改它。 */
function splitFormsTable(src: string, probe: Edit): boolean {
  const rightAt = probe.start + 1
  const root = parseGfmWithMath(applyEdits(src, [probe]))
  return (root.children as unknown as TopNode[]).some(
    (c) => c.type === 'table' && c.position?.start.offset === rightAt,
  )
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
        const at = a + cur.off + idx
        const edit: Edit = {
          start: at,
          end: at,
          text: '\n',
          pass: 'table',
          kind: p.type === 'heading' ? 'split-table-from-heading' : 'insert-newline',
          line: cur.line,
        }
        // 闸门（见 `splitFormsTable` 注释）：拆完**真能成表**才拆 —— 否则劈掉表头行而表格仍不成，净掉字。
        if (splitFormsTable(src, edit)) edits.push(edit)
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
  /** 拒绝理由（例如 'no-marker' / 'contains-|' / 'out-of-scope'）。 */
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

/** 裸奔标题行：`#{1,6}` 后**紧跟非空白**（拆行与补空格的共同入口判据）。
 *  `(?!#)` 强制 #{1,6} 取极大 hash 串：否则贪婪+回溯会把 '## 已合规' 拆成 hashes='#'
 *  + rest='# 已合规'，误报「单井号」（该行明明有 2 个 #）。 */
const GLUED_HEADING = /^(#{1,6})(?!#)(\S.*)$/

/**
 * 标题规则（`#{1,6}` + 无空格形态）。返回补好空格的行，或一个拒绝理由。
 * 拒绝即保持原样（继续裸露）—— 保守优先，宁可少救不可误伤。
 *
 * 判据 = 原型 `healHeading`（2026-10-09 实装，**整段替换**旧判据）：
 * 旧判据的四道限制（`[...rest].length > 30` / 含 `**` / 含 `」` / 首字符非 CJK 或编号）
 * 在真库裸奔语料上实测**误杀真标题**（`##你问的「⑧是AI还是机器」——**机器，纯代码…**` 长且含双星、
 * `##★一个遗留待你拍` 星号开头、`#五、完整的"来源→被谁读→得到什么"` 单井号），一并去除；
 * `？`/`！` 结尾也不再算句末标点（`###②审查选项要不要暴露给用户？` 是真标题）；
 * `：`/`；` 结尾同样不再拒绝 —— 2026-10-09 用户裁定（`##结果：**还没完**——…：`、
 * `##待你决策HTML功能在独立分支上，有三种整合方式：` 都要整行变标题）；
 * 此处与先期原型有一处**有意分歧**（原型保留 `/[：；]\s*$/` 拒绝）。
 * 只留三类拒绝（其余形态一律补空格）：
 * - `#6…`：单井号 + 紧跟数字 —— 引用编号不是标题；
 * - `。` 出现在**行中**：像正文（`##环境：已具备…可以跑。（风险点：…`）；
 *   [2026-11-02 用户裁定] **行尾句号不再拒绝** —— 用户原话「`##需要你做的这` / `###xxx`
 *   这种的都修复一下」，真库实测 19 条裸标题行是「句号收尾的标题」（`##①项目 MD文档没有
 *   预览根因：…。`），只有 4 条是真正文；旧判据把整类标题一并拒绝 ⇒ 主会话里裸露 `##`；
 * - 残余粘连（含 `|` / 三反引号串 / `#`）：该由 P1 / P1.5 先拆开再判 ——
 *   整行直接补空格会把表格、围栏或下一个标题一起并进标题文本。
 */
export function headingFix(line: string): { fixed: string } | { reject: string } {
  const m = GLUED_HEADING.exec(line)
  if (m === null) return { reject: 'no-marker' }
  const hashes = m[1]!
  const rest = m[2]!
  if (hashes.length === 1 && /^\d/.test(rest)) return { reject: 'single-hash-digit' }
  // 只拒「句号夹在行中」的；行尾句号是标题的正常收尾（见上方 2026-11-02 裁定）
  if (/。\s*\S/.test(rest)) return { reject: 'contains-。' }
  // 残余粘连三道（同一族：行内还有没拆的块级标记）
  if (rest.includes('|')) return { reject: 'contains-|' }
  if (/#/.test(rest)) return { reject: 'contains-embedded-hash' }
  if (/`{3,}/.test(rest)) return { reject: 'contains-fence-run' }
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

/** 位置 i 是否落在行内代码（反引号段）内 —— 落在其中即不是拆点（那是字面内容）。 */
function inCodeBefore(s: string, i: number): boolean {
  let n = 0
  for (let k = 0; k < i; k++) if (s[k] === '`') n += 1
  return n % 2 === 1
}

/** 单行的全部拆点（**相对本行起点**的列号，升序去重）。逐条照抄原型 `splitPoints`
 * （各类都带「误拆形态」约束，真库样本逐条人工审过）：
 * 1. 表格 `|`（行尾 `|` 且 body 含 `|` → 首个 `|` 前拆）；2. 围栏 ``` ``` ```；
 * 3. 行内第二个标题号；4. 数字列表 `1. `；5. 连字符列表 `- `。 */
function splitPoints(line: string, hashes: number): number[] {
  const body = line.slice(hashes)
  const pts: number[] = []
  // 1) 表格：行尾 | 且 body 含 | → 首个 | 前拆
  if (/\|\s*$/.test(line) && body.includes('|')) pts.push(hashes + body.indexOf('|'))
  // 2) 围栏：``` 前拆
  const f = body.indexOf('```')
  if (f >= 0) pts.push(hashes + f)
  // 3) 行内第二标题号：# 后不跟 [A-Za-z0-9]（排除 #0f5fd4 / #include 这类）、不在行内代码内
  const h2 = /#{1,6}(?!#)(?=[^\s\d#])/.exec(body)
  if (h2 !== null) {
    const after = body[h2.index + h2[0].length] ?? ''
    if (!/[A-Za-z0-9]/.test(after) && !inCodeBefore(body, h2.index)) pts.push(hashes + h2.index)
  }
  // 4) 数字列表：不在 rest 起始位（排除 ##2. MCP 这类编号标题）、前字符非 *（防拆断加粗）、
  //    不在行内代码内
  const reNl = /\d+\.\s/g
  let m: RegExpExecArray | null
  while ((m = reNl.exec(body)) !== null) {
    if (m.index > 0 && body[m.index - 1] !== '*' && !inCodeBefore(body, m.index)) {
      pts.push(hashes + m.index)
      break
    }
  }
  // 5) 连字符列表：非空白非 - 后紧跟 `- `（中文直连形态，排除 A-B / 破折号）
  const reDash = /[^\s-]-(?=\s)/g
  while ((m = reDash.exec(body)) !== null) {
    const p = m.index + 1
    if (!inCodeBefore(body, p)) {
      pts.push(hashes + p)
      break
    }
  }
  // 6) 粗体开头的新块：`##改了5个文件**1.解析层…**（…）` / `##两件必须告诉你的**①本机…**，…`
  //
  //    与「标题内强调」的区分是**必须**的：`##这版的基准是你给的文件**视觉100%沿用…**：…`
  //    （rescue.test.ts 的「不拆不该拆的」组）骨架完全相同，但它**不该拆** —— 那里
  //    `**…**` 是标题内部的强调，冒号后的内容仍属标题。
  //
  //    唯一可靠的分界：**`**` 之内是否以「新块标记」开头**。
  //    以 `1.` / `①` / `-` 开头 ⇒ 那是另一个块的起点（列表项/编号项），标题在它之前结束；
  //    否则 ⇒ 是标题里的强调。三个真库样本实测：两个应拆的 inner 均命中，被裁定的那条不命中。
  //
  //    其余闸门都保守取「拒」：`**` 紧贴井号（`##**重要**说明` 是正常标题）、标题段含
  //    句读/块级标点（`##结论：**…` 更像标题）、`**` 未闭合、闭合后无内容。
  const bi = body.indexOf('**')
  if (bi > 0 && !inCodeBefore(body, bi)) {
    const head = body.slice(0, bi)
    const close = body.indexOf('**', bi + 2)
    const inner = close > 0 ? body.slice(bi + 2, close) : null
    const ok =
      inner !== null &&
      close + 2 < body.length &&           // 闭合后还有内容（否则就是整行一个粗体）
      !/[。，、；：！？|#]/.test(head) &&   // 标题段不像含标点的正文
      !/`{3,}/.test(head) &&
      /^(\d+[.、)]|[①-⑳]|[-•])/.test(inner) // ★唯一分界：inner 以新块标记开头
    if (ok) pts.push(hashes + bi)
  }
  // 拆点必须落在正文里（> hashes）：拆点 = hashes 会拆出纯井号段（`####|a |b |` → `####` +
  // `|a |b |`），那是 ATX 空标题（凭空多一个空 h4）—— P1 的 tableSplit 对同一形状有同款闸
  //（`####|a |b |` / `#|a |b |` → 不拆），P1.5 必须一致，否则会把 P1 的闸绕过去。
  return [...new Set(pts)].filter((p) => p > hashes).sort((a, b) => a - b)
}

/**
 * P1.5 · 拆行抢救：把「标题号与正文整段粘连在同一行」的裸奔行按拆点插 `\n` 断开，
 * 并给拆出的**每段**补标题空格（`##已完成- ✅ …` → `## 已完成` + `- ✅ …`）。
 * 两处插入都是纯插入，逐条落进 `edits`（`kind` 区分：`split` / `heading`）。
 *
 * 为什么只作用于**顶层段落**内的行（与 passMarkers 同一架构判据，不是裸文本全行扫描）：
 * 块级归属由真解析器裁定（C1/C3）—— 围栏、缩进代码块、表格里的 `#` 行天然不在 targets 内，
 * 不必也不该自造块边界状态机（`##include <x>` 这种代码注释就是这么被挡住的）。
 * 拆点判据本身是**行内**启发式（第一个 `|`、``` ``` ```、第二个标题号、`1. `、`- ` ——
 * 行内没有「块边界」这回事），其正确性由不变量套件守（`rescue.invariant.test.ts` 的 A 组：
 * 「repair 只做插入」），符合 C3 的「运行时闸门或测试，取成本较低者」。
 *
 * 与 P1 的分工：行尾带 `|` 的粘连行 P1 也拆、且拆点相同（都在首个 `|` 前），P1 先跑 ⇒
 * 成表形态归 P1（它有「拆完真能成表」闸门）；本 pass 兜 P1 闸门放行的其余形态
 * （真库实测：带分隔行的 578 行全由 P1 覆盖，另 321 行是 fence/numlist/hash2/dash）。
 *
 * ⚠️ 本 pass **改变行数** ⇒ 它之后的任何 pass 都不许再用旧的 mdast 树（见 `repair` 的 `reuseRoot`）。
 * @param providedRoot 已解析好的树（须与 `src` 逐字节对应）。缺省自己 parse；
 *   `repair` 在 P1 未改文本时复用 P1 的树，省掉一次全量 parse。
 */
export function passGlue(src: string, providedRoot?: DocRoot): PassResult {
  const root = providedRoot ?? parseGfmWithMath(src)
  const edits: Edit[] = []
  for (const p of topParagraphs(root)) {
    const a = p.position.start.offset
    const b = p.position.end.offset
    for (const { off, line } of relLines(src.slice(a, b))) {
      const m = GLUED_HEADING.exec(line)
      if (m === null) continue
      const pts = splitPoints(line, m[1]!.length)
      if (pts.length === 0) continue
      const cuts = [0, ...pts]
      for (let k = 0; k < cuts.length; k++) {
        const segStart = cuts[k]!
        if (k > 0) {
          const at = a + off + segStart
          edits.push({ start: at, end: at, text: '\n', pass: 'split', kind: 'split', line })
        }
        // 拆出的每段**单独**过标题判据：判据必须打在段上 —— 整行判据会被段外的残余形态
        // （行尾的 ``` 、段里的 `|`）拒绝，而那正是要拆的原因。
        const segEnd = k + 1 < cuts.length ? cuts[k + 1]! : line.length
        const h = headingFix(line.slice(segStart, segEnd))
        if ('fixed' in h) {
          // 纯插入一个空格：`h.fixed` 是 `${hashes} ${rest}`，空格恒在 hashes.length 处
          const ins = a + off + segStart + h.fixed.indexOf(' ')
          edits.push({ start: ins, end: ins, text: ' ', pass: 'split', kind: 'heading', line })
        }
      }
    }
  }
  return { src: applyEdits(src, edits), edits }
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
   *  链的终点是 P4 快扫（`sweep.src`），即 P2/P3 之后再过一遍行级兜底。 */
  out: string
  /** 各 pass 的原始结果：各自的偏移**相对各自的输入**，自洽可解释。 */
  fence: FencePassResult
  /** P1 的子结果。**刻意只暴露 `PassResult`（`src`/`edits`）**：`TablePassResult.root`
   *  的唯一消费者是 `repair` 自己的 `reuseRoot`（纯优化），不必外传 —— 外传会让
   *  fail-safe 路径被迫编一棵空树，凭空造出"形状正常却零块"的静默谎。 */
  table: PassResult
  /** P1.5（拆行）的子结果。它**会改行数** —— 它的编辑一旦非空，P1 的树就不能再给下游用
   *  （见 `repair` 的 `reuseRoot`）。 */
  glue: PassResult
  markers: MarkerPassResult
  /** P4 快扫（纯行级正则兜底）的子结果。 */
  sweep: PassResult
}

/**
 * 生产调用参数（`MarkdownText.renderSettled` 用）。**单一来源** —— 测试与验收脚本须引用它，
 * 否则生产参数变更会静默作废验收数字：套件/脚本会继续按旧参数跑，两边都不报错
 * （本批刚把不变量套件对齐到生产配置，硬编码复述正是那次失配的成因）。
 */
export const PROD_REPAIR_OPTS = { tableHeader: true } as const

/**
 * P4 · 快扫：行级正则兜底，给「行首 `##`~`######` 后紧跟非空白」补一个空格。
 *
 * <p><b>为什么需要（前四个 pass 结构上救不到）</b>：P1/P1.5/P2 的块级判据都来自真解析器的
 * mdast position，而**被失控围栏吞进 `code` 节点的行不在任何段落的 position 里** ——
 * 那些行本来是标题（`##你现在配的是什么`、`##关键澄清：…`），却因为落进围栏而永远
 * 拿不到补空格。真库实测（20,673 条 assistant 消息 / 4,189 个命中行）：
 * 围栏外该修 3,247 行、**失控未闭合围栏内 940 行**，后者是纯 position 判据的盲区。
 *
 * <p><b>为什么只认 `#{2,6}`，不认单 `#`（与 `headingFix` 的唯一有意分歧）</b>：
 * 单井号在本仓语料里是**代码注释**的主力形态（`#include`、`#!/usr/bin/env bash`、
 * Python 的 `#规则1`）—— 实测合法代码块内单井号命中存在，而 `##` 及以上在合法代码块内
 * **0 命中**。这正是当年 `patches.ts` 被整体删除的形态（`#include` → `# include`），
 * ⛔ 不要为了「更全」把单井号放进来。
 *
 * <p><b>为什么不放最前面（顺序不可换）</b>：P1.5 拆行的判据是 `GLUED_HEADING`（要求井号
 * 后**紧跟 `\S`**）。先补空格会让 `##待讨论的6个点###①鉴权` 这类粘连行立刻变成合法 ATX，
 * 拆行链路整条失效。⇒ 必须**先拆行、再补空格**，本 pass 是链的最后一环。
 *
 * <p><b>为什么可以不用 position 判据（对 C3 的显式豁免）</b>：本 pass 只做**单行内**
 * 的「插一个空格」，不改行数、不改块边界归属 —— 它没有「自造块边界」的风险，
 * 与 P1/P1.5 那类会改变块结构的编辑不同。例外恰恰是它要修的形态本身：那一行**本来就
 * 是标题**，补空格只是把模型漏掉的空格还回去。
 *
 * <p>已知残差：若某段真代码里恰好有一行以 `##` 开头且后接非空白（如 Markdown 源码样例），
 * 本 pass 会改动它。实测语料 0 命中（合法代码块内 `##`+非空白 = 0），登记备查。
 */
export function passHeadingSweep(src: string): PassResult {
  const lines = relLines(src)
  // 先标出「合法闭合围栏内」的行区间 —— 那些是真代码，一律不动。
  // 未闭合围栏**不**进保护区：它的后半段本就是被吞掉的正文（这正是本 pass 要救的形态）。
  // 判据与 passFence 的围栏状态机同精度（闭合要求反引号数 >= 开围栏数）。
  const protectedLine = new Array<boolean>(lines.length).fill(false)
  {
    let openAt = -1
    let openLen = 0
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]!.line
      if (openAt === -1) {
        const open = /^ {0,3}(`{3,})/.exec(line)
        if (open !== null) {
          openAt = i
          openLen = open[1]!.length
        }
      } else {
        const close = /^ {0,3}(`{3,})\s*$/.exec(line)
        if (close !== null && close[1]!.length >= openLen) {
          for (let k = openAt; k <= i; k++) protectedLine[k] = true
          openAt = -1
          openLen = 0
        }
      }
    }
  }
  const edits: Edit[] = []
  for (let i = 0; i < lines.length; i++) {
    if (protectedLine[i] === true) continue
    const { off, line } = lines[i]!
    const m = /^(#{2,6})(?!#)(\S.*)$/.exec(line)
    if (m === null) continue
    const rest = m[2]!
    // 残余块级粘连不救：直接补空格会把**表格行 / 围栏**并进标题文本
    // （`####|a |b |` → `#### |a |b |` 会把表头吞成 h4 正文；`##标题```yaml…` 同理）。
    // 这两类该由 P1/P1.5 先拆开 —— ⛔ 不要在这里硬补，那只会把形态变得更糟。
    if (rest.includes('|') || /`{3,}/.test(rest)) continue
    edits.push({
      start: off + m[1]!.length,
      end: off + m[1]!.length,
      text: ' ',
      pass: 'sweep',
      kind: 'heading-space',
      line,
    })
  }
  return { src: applyEdits(src, edits), edits }
}

/**
 * 抢救链（顺序不可换：P0 先做，才决定后面看到的块结构）。
 *
 * P0 围栏 → P1 表格 → P1.5 拆行 → P2/P3 标记 → P4 快扫。前四个 pass 各自读真解析器的
 * mdast position（符合 C3）；P4 是纯行级兜底（豁免理由见其 JSDoc）。
 * 只在 settled 渲染前调用（C2）。各 pass 的诊断字段（`markers.rejects`）不参与决策
 * （注：`headingFix` 的 `(?!#)` 修正后，'## 已合规' 这类已合法标题不再进 rejects，属预期）。
 *
 * @param src 原始 markdown 源码
 * @param opts.tableHeader 是否启用 P1e（容器是活标题的表格），缺省 false
 * @returns 修补结果：`out` + 五个 pass 各自的子结果（偏移各自自洽，`fence.aborted` 不被吞）
 */
export function repair(src: string, opts: { tableHeader?: boolean } = {}): RepairResult {
  try {
    const p0 = passFence(src)
    const p1 = passTable(p0.src, opts.tableHeader === true)
    // P1.5 的树复用同 P1 的口径：只有 P1 没改文本时，P1 的树才与 `p1.src` 逐字节对应。
    const ps = passGlue(p1.src, p1.edits.length === 0 ? p1.root : undefined)
    // 纯优化（输出逐字节不变）：P1 与 P1.5 都无编辑 ⇒ `applyEdits(x, [])` 原样返回字符串，
    // 故 ps.src 与 p1.src 逐字节相同 ⇒ P1 的树与 passMarkers 的输入对应，可直接复用，
    // 省掉一次全量 parse（本函数因此通常只 parse 1 次）。
    // 注：`p1.edits.length === 0` 单独就已推出 `p1.src === p0.src`，故 `p0.src === src`
    // 是**冗余的额外收紧**（不是安全必要条件，作用只是「原文未被 P0 触碰」的显式复述）。
    // 保留而不简化：本条件只影响 parse 次数、不影响任何输出，少动一行少一分风险。
    //
    // ⚠️ `ps.edits.length === 0` 是**安全必要条件**（不是优化）：P1.5 拆行改了行数与偏移，
    // 旧树的位置与新文本不再对应 —— 漏掉这一条会让 passMarkers 拿着错位的 position 乱插空格
    // （「拆行 + 多段」用例在 rescue.test.ts 里钉住这条）。
    const reuseRoot =
      p0.src === src && p1.edits.length === 0 && ps.edits.length === 0 ? p1.root : undefined
    const p2 = passMarkers(ps.src, reuseRoot)
    // P4 快扫必须排在**所有** position 判据之后：它是纯行级兜底，专门覆盖前三者
    // 「行落进 code 节点 ⇒ 没有段落 position ⇒ 永远看不到」的盲区（见 passHeadingSweep）。
    const p4 = passHeadingSweep(p2.src)
    // `table` 显式取 `PassResult` 两字段（不透传 `p1`）：`TablePassResult.root` 的偏移基准是
    // `p0.src`、与 `out` 不配套，运行时的形状就不该与声明不一致（同 `RepairResult.table` 的 JSDoc）。
    return {
      out: p4.src,
      fence: p0,
      table: { src: p1.src, edits: p1.edits },
      glue: { src: ps.src, edits: ps.edits },
      markers: p2,
      sweep: p4,
    }
  } catch (err) {
    // fail-safe：抢救层任何异常都不该让消息渲染不出来 —— 退回原文，并显式告警而非静默
    // （静默回退会让「抢救层对所有消息永久失效」没有任何信号，front/CLAUDE.md 规则十二）。
    console.warn('[rescue] repair 失败，已回退原文', err)
    const fallback: PassResult = { src, edits: [] }
    return {
      out: src,
      fence: { src, edits: [], aborted: false },
      table: fallback,
      glue: fallback,
      markers: { src, edits: [], rejects: [] },
      sweep: fallback,
    }
  }
}

/* 已知残差 R1–R9 见设计文档 §12（R8/R9 为 2026-09-18 登记、尚待裁定）：
 *   docs/zjkycode/specs/2026-09-12-markdown-dirty-input-rescue-design.md */
