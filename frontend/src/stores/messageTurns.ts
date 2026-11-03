import type { ChatMessageDto } from '../api/types'

/**
 * [轮判据] 轮属性判据的【唯一真源】。
 *
 * <p>WHY 单文件：MessageList 的渲染分组（components/center/MessageList.tsx 的 groups）、渲染层折叠
 * （同文件折叠行的 {@link orderedTurnKeys}）与通用工具 {@link capTailTurns} **必须用同一套判据** ——
 * 几处各写一份必然漂移，届时「渲染出来的轮」与「被折叠/裁剪的轮」不是同一个东西，切点会落在一轮中间。
 *
 * <p>⚠️ store 侧已<b>不再</b>用它裁剪：用户 2026-10-29 裁定「不应该挤掉而是折叠显示」⇒ chatStore 的
 * 追加路径只增不裁，默认窗口（最近 3 轮）由渲染层折叠行表达。
 *
 * <p>⚠️ `dropTextInBriefTurns`（utils/sendUserMessage.ts:146）是 MessageList 渲染前的展示变换，
 * 但它只把 assistant 的 content 置空、**不改条数也不改顺序**（走 messages.map）⇒ 轮键集与分组结果
 * 不受它影响，故本模块的判据**不必**先跑它（跑不跑结果相同）。
 */

/** isMeta = true 但属「实时展示行」的两种 subtype（tool_use_summary / stop_hook_summary）。 */
export function isLiveDisplayRow(m: ChatMessageDto): boolean {
  return (m.author === 'attachment' && m.subtype === 'tool_use_summary')
    || m.subtype === 'stop_hook_summary'
}

/**
 * 系统注入行（2026-11-02 用户裁定）。
 *
 * <p><b>WHY</b>：用户实测「`<system-reminder> The following skills are available…` 这种 hook
 * 不能算用户消息，应该融合在已完成中、显示为系统提示；主会话也是一样」。
 *
 * <p>这些行是后端注入的 meta 消息（`skill_listing` 技能清单、`edited_text_file` 外部改了文件、
 * `date_change`、`changed_files`、`mcp_instructions_delta`、hook 回执 …），在 DTO 里都是
 * `role=user` + `isMeta=true` + `author='attachment'`。此前两种错误表现：
 * <ul>
 *   <li><b>主会话</b>：{@link isDialogueRow} 直接把 isMeta 行丢掉 ⇒ 用户完全看不到（但模型确实收到了）；</li>
 *   <li><b>子代理视图</b>：transcript 契约没有 isMeta 字段 ⇒ role=user 被当普通提问 ⇒ 几 KB 的
 *       技能清单渲染成一条用户气泡。</li>
 * </ul>
 *
 * <p>现在统一为「弱化系统提示行」：不是轮次起点（{@link isUserTurnRow} 已按 isMeta 拦住），
 * 但参与轮次归属 ⇒ 收在该轮的「已完成」折叠区里。
 *
 * <p>⛔ 排除两类已有专属渲染的（{@link isLiveDisplayRow}）与 compact 摘要 —— 它们各有语义，
 * 走本分支会重复或错位。
 */
export function isSystemInjectedRow(m: ChatMessageDto): boolean {
  if (m.role !== 'user') return false
  if (m.isCompactSummary === true) return false
  if (isLiveDisplayRow(m)) return false
  // ① 精确判据：后端出站带 isMeta + author（新写入的 transcript / GET /messages 均满足）
  if (m.isMeta) return m.author === 'attachment' || m.author === 'system'
  // ② 兜底判据（正文特征）· 2026-11-02 用户实测「子代理里 `<system-reminder>` 还是用户气泡」
  //
  //    WHY 必须有这一道：**历史 transcript 里没有 isMeta**。子代理 transcript 的
  //    `chatMessageToMap` 在 2026-11-02 之前只写 subtype（实测 `agent-*.jsonl` 里
  //    `{"role":"user","subtype":"skill_listing","isMeta":null}`）⇒ 后端契约补齐后，
  //    重启只对**新写入**生效，已有的执行记录永远缺这个字段 ⇒ 右栏仍会把技能清单
  //    渲染成用户气泡。与 {@link isNotificationInjectedUserRow} 同一取舍：
  //    根治在后端，前端必须能兜住旧数据 —— 否则同一份数据在新旧版本上表现不一致。
  //
  //    判据必须**成对**：正文以 `<system-reminder>` 开头 **且**以 `</system-reminder>` 结尾
  //    （2026-11-02 用户裁定）。首尾各一才说明整条正文就是一个完整的注入块。
  //
  //    ⛔ 为什么不能只判开头：用户正文里引用半截标签（`<system-reminder> 是什么意思`）或
  //    模型自己复述该标签时，只判开头会把**真实内容**误收进系统提示 —— 与「不丢用户消息」
  //    冲突。成对判据把这条误伤面消掉：半截标签结尾对不上，照常当普通消息渲染。
  const text = (m.content ?? '').trim()
  return text.startsWith('<system-reminder>') && text.endsWith('</system-reminder>')
}

/** 非用户提问的 subtype（后台通知 / 调度 / 队列 / 离开摘要 / 子代理回执 / 压缩边界 / 实时展示行）：
 *  这些即使 role=user 也**不得**作为轮次起点 —— 否则后台通知会自成一轮，长过程里多出一个
 *  `N nexus` 头（用户裁定：后台通知不单独分轮，只按用户消息算轮次）。 */
const NON_USER_TURN_SUBTYPES = new Set<string>([
  'tool_use_summary', 'stop_hook_summary',
  'task_notification', 'task_started', 'task_progress',
  'scheduled_task_fire', 'cron', 'queue', 'away_summary',
  'compact_boundary', 'microcompact_boundary', 'snip_boundary', 'snip_marker',
  'subagent_result', 'subagent_receipt', 'subagent_start', 'subagent_stop',
])

/**
 * 后台通知 / 子代理回执的正文特征（**前端兜底防御**）。
 *
 * <p><b>WHY 需要按内容判（2026-11-02 用户实测「nexus 回复被切成多段」）</b>：
 * 后端空闲路径（{@code CronIdleExecutor.java:1526} 取 {@code cmd.value()} 原文 →
 * {@code LlmAgentLoop.java:3766} 入队时 {@code isMeta=false}）会把**整段 `<task-notification>` XML**
 * 当作普通 prompt 注入。该行既没有 {@code subtype} 也没有 {@code isMeta} ⇒
 * {@link isUserTurnRow} 的两道防护全部穿透，被当成**轮次起点** ⇒ 它后面的 assistant 段归属新轮
 * ⇒ 用户看到「一个回复被切成两段、冒出第二个 nexus 头」。
 *
 * <p>根治点在后端（按 mode 置 isMeta），但**前端必须能兜住旧后端 / 其他注入路径** ——
 * 否则同一份数据在不同后端版本上表现不一致。故这里补一道按正文开头的判据。
 *
 * <p>只在 {@code role=user} 上生效：用户自己**打字**发一段含 `<task-notification>` 的文本
 * 是极端罕见场景，且即便命中，效果只是「不把它当轮次起点」（仍会作为一行的内容显示），
 * 不会丢消息 —— 代价可接受。
 */
const NOTIFICATION_CONTENT_PREFIXES: ReadonlyArray<string> = [
  '<task-notification>',
  'A background agent completed a task:',
  '<subagent-result>',
  '<subagent-receipt>',
]

/** 该 user 行是否是「后台通知注入」（按正文特征判定，见 {@link NOTIFICATION_CONTENT_PREFIXES}）。 */
export function isNotificationInjectedUserRow(m: ChatMessageDto): boolean {
  if (m.role !== 'user') return false
  const text = (m.content ?? '').trimStart()
  if (text === '') return false
  return NOTIFICATION_CONTENT_PREFIXES.some((p) => text.startsWith(p))
}

/** 是否「**真正的用户提问行**」（= 轮次起点）：role=user、非 meta、非 system/attachment 作者、
 *  非 compact summary、非后台/系统 subtype。
 *
 *  <p>渲染分组（MessageList）与窗口裁剪（{@link capTailTurns}）**共用**此判据：用户裁定
 *  「只按用户消息算轮次」，后台通知 / cron / 队列 / 离开摘要 / 子代理回执都不开新轮。 */
export function isUserTurnRow(m: ChatMessageDto): boolean {
  if (m.role !== 'user') return false
  if (m.isMeta) return false
  if (m.author === 'system' || m.author === 'attachment') return false
  if (m.isCompactSummary === true) return false
  if (m.subtype != null && NON_USER_TURN_SUBTYPES.has(m.subtype)) return false
  // [2026-11-02] 兜底：后端空闲路径注入的通知行既无 subtype 也无 isMeta ⇒ 按正文特征再拦一道
  if (isNotificationInjectedUserRow(m)) return false
  // [2026-11-02 用户实测「子代理里 <system-reminder> 还是用户气泡且切开回复」]
  //   历史 transcript 的行**没有 isMeta**（旧写入只落 subtype）⇒ 上面 isMeta 那道拦不住，
  //   注入行会被当成轮次起点：既渲染成用户气泡，又把子代理的回复切成两段（多个 nexus 头）。
  //   与 isSystemInjectedRow 用同一判据（单点真源），此处只负责「不推进轮次」。
  if (isSystemInjectedRow(m)) return false
  // [2026-11-02 用户实测「执行中出现 2–3 个 nexus 头，F5 后消失」] 锚点自洽判据。
  //
  // <b>WHY</b>：运行态下有些**注入行**（任务通知 / 回执 / 提醒）会以 role=user 推给前端，
  // 且丢失了 DB 里的 `is_meta=1` 标记 ⇒ 上面的 isMeta 防护被穿透。它们往往带着
  // `userMessageId` 指向**它所属的那一轮**（而不是自身 id）—— 这正是「它不是提问」的可靠信号。
  //
  // 反证：wire 契约里**真正的用户提问**恒满足 `userMessageId === id`（见 chatStore.appendMetaUser
  // 与 GET /messages 出站链）。故：
  //   · userMessageId 缺省(null) → 老数据，保持原有行为（算提问）；
  //   · userMessageId === id      → 真正的提问，算；
  //   · userMessageId !== id      → 属于别的 flow 的注入行，**不算轮次起点**。
  //
  // F5 后数据归位（注入行带回 is_meta=1）⇒ 与运行态表现一致，不会一刷新就变样。
  if (m.userMessageId != null && m.userMessageId !== m.id) return false
  return true
}

/** 该条是否参与「对话流」（渲染 + 轮归属）。判据与 MessageList groups 的三条 continue 逐条同源。 */
export function isDialogueRow(m: ChatMessageDto): boolean {
  if (m.role === 'tool') return false
  // [transcript-only] 仅 transcript 可见的 user 消息不进普通对话流（TraceView 不过滤，本模块也不涉及）
  if (m.role === 'user' && m.isVisibleInTranscriptOnly === true) return false
  // isMeta 默认跳过；tool_use_summary / stop_hook_summary 两个实时展示行放行，
  // [2026-11-02] 系统注入行（技能清单 / 外部改文件 / hook 回执…）也放行 —— 它们要显示成
  //   「已完成」折叠区里的系统提示，而不是被整条丢弃（用户裁定，见 {@link isSystemInjectedRow}）。
  if (m.isMeta && !isLiveDisplayRow(m) && !isSystemInjectedRow(m)) return false
  return true
}

/**
 * 轮键：一条消息归属哪一轮。与 MessageList groups 的 push 键同源（cap 与渲染的【单一真源】）。
 *
 * <p>规则（三段）：
 * <ol>
 *   <li><b>有当前轮</b>（调用方传入 {@code prevTurnKey} = 最近一条**真实用户提问**的 id）→ 一律归它。
 *       ⚠️ 段自身的 {@code userMessageId} **不优先** —— 它可能指向后台/系统消息
 *       （task_notification / cron / 队列 / 子代理回执），让段自成一键就会多出一个 `N nexus` 头；</li>
 *   <li><b>无当前轮</b>（窗口头部无提问行）→ 用自身 {@code userMessageId}；</li>
 *   <li>两者皆无 → 回落自身 {@code id}（自成一键，保窗口裁剪安全）。</li>
 * </ol>
 *
 * <p>⚠️ 调用方必须【按序扫描】，并用 {@link isUserTurnRow} 推进 {@code prevTurnKey}
 * （只有真正的用户提问才是轮次起点；无锚行 / 后台行不得推进）。
 *
 * @param prevTurnKey 前向扫描中最近一条真实用户提问的 id（无 → null/undefined）
 */
export function turnKeyOf(m: ChatMessageDto, prevTurnKey?: string | null): string {
  return prevTurnKey ?? m.userMessageId ?? m.id ?? ''
}

/**
 * 窗口头部「无锚前缀」的轮键兜底 · 2026-11-02 用户实测「切会话/F5 后出现多个 nexus 头」。
 *
 * <p><b>病根</b>：分页窗口的起点**不一定落在用户提问行上**。实测 `GET /messages/page?turns=3`
 * 的头部可能是注入行（`isMeta=true`、`userMessageId=null`，如 `edited_text_file` / `skill_listing`），
 * 也可能是上一轮的 assistant 段（提问行已被截断到窗口外）。此时前向扫描既没有 `turnId`
 * 也没有 `prevKey` ⇒ {@link turnKeyOf} 的第三档回落会把**它自己的 id** 当成轮键 ⇒
 * 头部这些行自成一「轮」：
 * <ul>
 *   <li>多冒一个 `nexus` 头（用户看到「分割多个 nexus」）；</li>
 *   <li>{@link orderedTurnKeys} 多出一个键 ⇒ 渲染层「执行中只留最后 1 轮」的窗口算错
 *       ⇒ 历史锁不住（用户实测「用户消息之后又没法锁住历史」）。</li>
 * </ul>
 *
 * <p><b>修法</b>：头部无锚行归到**它之后第一个能确定轮的行**的键（正序找）。
 * 语义上正确 —— 它们时间上属于那一段对话，只是发起它的提问行不在本窗口里。
 * 窗口头部若直接是提问行，或首行自带 `userMessageId`，本函数返回 null（无需兜底）。
 *
 * @param dialogueRows 已按 `isDialogueRow` 过滤、保持时间序的行
 * @returns 头部无锚时应使用的轮键；不需要兜底 → null
 */
export function headAnchorKeyOf(dialogueRows: ChatMessageDto[]): string | null {
  const first = dialogueRows[0]
  if (first === undefined) return null
  // 首行本身能定键（真提问行 / 自带 userMessageId）→ 不需要兜底
  if (isUserTurnRow(first) || first.userMessageId != null) return null
  for (const m of dialogueRows) {
    if (isUserTurnRow(m)) return m.id
    if (m.userMessageId != null) return m.userMessageId
  }
  return null
}

/**
 * 按渲染口径给【每条对话行】算出它所属的轮键（与 MessageList groups 前向趟逐条同源）。
 *
 * <p>返回值是 Map（插入序 = 消息时间序），供 {@link orderedTurnKeys}（轮的出现顺序）与
 * 「某条消息属于哪一轮」（历史提问跳转需先展开目标所在轮）使用。
 * 非对话行（tool 行 / 纯 meta 行）不产生条目 —— 它们不参与轮分组。
 */
export function turnKeyByRowId(msgs: ChatMessageDto[]): Map<string, string> {
  const out = new Map<string, string>()
  let turnId: string | null = null
  // [2026-11-02] 头部无锚兜底：先算出该用哪个键，避免注入行/截断段自成一「轮」。
  let prevKey: string | null = headAnchorKeyOf(msgs.filter(isDialogueRow))
  for (const m of msgs) {
    if (!isDialogueRow(m)) continue
    if (isUserTurnRow(m)) turnId = m.id
    const key = turnKeyOf(m, turnId ?? prevKey)
    prevKey = key
    out.set(m.id, key)
  }
  return out
}

/**
 * 已加载消息里的轮键，**按出现顺序**（= 时间序）去重。
 *
 * <p>渲染层折叠的判据：比「默认展开窗口」更早的**头部连续若干轮**折叠成一行。
 * 与 MessageList groups 的键序列同源（同一前向趟），因此「折叠的轮」与「渲染分组的轮」恒为同一批。
 */
export function orderedTurnKeys(msgs: ChatMessageDto[]): string[] {
  const keys: string[] = []
  const seen = new Set<string>()
  for (const key of turnKeyByRowId(msgs).values()) {
    if (seen.has(key)) continue
    seen.add(key)
    keys.push(key)
  }
  return keys
}

/**
 * 默认保留（并展开）的轮数 · 2026-11-02 用户裁定「默认显示 2 轮次」。
 *
 * <p><b>单一来源</b>：App 首屏 `listTurns({turns})`、store 的内存裁剪
 * （{@link ChatState.pruneFoldedTurns}）、渲染层的展开窗口（MessageList）**必须同值** ——
 * 三处各写一份必然漂移，届时「内存里剩几轮」与「屏幕上显示几轮」不是同一个数。
 *
 * <p>沿革：原值 3（2026-10-29 定）；2026-11-02 用户实测长会话卡顿，同时裁定
 * 「收起来代表内存删除、展开重新拉后端、F5 也一样」⇒ 降到 2 并配套真删。
 */
export const FOLD_DEFAULT_TURNS = 2

/**
 * 保留【最近 maxTurns 个整轮】（LRU：新消息进 → 最老的一整轮出）。
 *
 * <p>⚠️ 通用工具，<b>追加路径不得使用</b>：用户 2026-10-29 裁定「已加载的轮永远不因新消息而消失」
 *   ⇒ chatStore 已不再调用本函数（折叠由渲染层负责）。保留给「确需丢弃」的显式场景。
 *
 * <p>⚠️ **整轮保留** —— 绝不把一轮切半（user 还在而 assistant 已被裁），否则渲染出半截轮。
 * 归属规则：**每一行（含 tool 行 / 纯 meta 行）都属于「它最近的前置对话行」所在的那一轮**。
 *   · 因此非对话行不会自成一「轮」（不可见的占位不会顶掉真实轮），
 *   · 也不会无主残留（否则尾部不断追加的 meta 行永远不被裁 = 又一处无界增长）。
 * 头部若存在「其前不存在任何对话行」的行，视为无主，随更早的轮一起丢弃。
 *
 * @param msgs     按时间序的消息数组
 * @param maxTurns 保留的最大轮数（<= 0 视为不裁剪）
 * @returns 未超上限时**原样返回同一引用**（避免无谓重渲/重排）；超限时返回尾段切片
 */
export function capTailTurns<T extends ChatMessageDto>(msgs: T[], maxTurns: number): T[] {
  if (maxTurns <= 0 || msgs.length === 0) return msgs
  // ① 正向一趟：给每一行算出「它所属的轮键」—— 对话行取自己的键（有锚 = 自身锚；无锚 = 最近前置锚，
  //   前向无锚则自身 id · 见 turnKeyOf）；**非对话行（tool 行 / 纯 meta 行）沿用最近一个前置对话行的键**。
  //   这样它们既不会自成一「轮」（否则不可见的占位会顶掉真实轮），也不会无主残留（否则尾部不断追加的
  //   meta 行永远不被裁，又变成无界增长）。
  const rowKey: (string | null)[] = new Array(msgs.length).fill(null)
  let cur: string | null = null          // 最近前置【对话行】的键（非对话行沿用）
  let anchor: string | null = null       // 最近前置【有锚】对话行的 userMessageId（无锚行归组用）
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i]!
    if (isDialogueRow(m)) {
      // [只按用户提问算轮次] 只有真正的用户提问行才开新轮（后台通知 / 压缩边界 / 实时展示行都不算）
      if (isUserTurnRow(m)) anchor = m.id
      cur = turnKeyOf(m, anchor)
    }
    rowKey[i] = cur
  }
  // ② 反向一趟：从尾部取最多 maxTurns 个不同的键 = 要保留的轮
  const keep = new Set<string>()
  for (let i = msgs.length - 1; i >= 0 && keep.size < maxTurns; i--) {
    const k = rowKey[i]
    if (k) keep.add(k)
  }
  // ③ 切点 = 第一行「所属轮仍在保留集内」的下标（往下走到碰到更早的轮为止）
  let cutAt = msgs.length
  for (let i = msgs.length - 1; i >= 0; i--) {
    const k = rowKey[i]
    if (k !== null && !keep.has(k)) break
    cutAt = i
  }
  // ④ 头部无主行（其前没有任何对话行）本就该随更早的轮一起丢弃
  while (cutAt < msgs.length && rowKey[cutAt] === null) cutAt++
  if (cutAt === 0 || cutAt >= msgs.length) return msgs
  return msgs.slice(cutAt)
}