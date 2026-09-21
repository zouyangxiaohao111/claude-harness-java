package com.nexusai.application.agent.prompt;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 头部 prompt 缓存的 <b>4 个可独立失效的集合</b>（A / B / C / D）·
 * CC §1.3「按缓存分裂的 4 个不同集合」的单点承载。
 *
 * <p><b>[步骤 5 追加] 第 5 项 {@link #E_LAST_EMITTED_DATE} 不是头部缓存</b>，而是
 * <b>尾部投递层</b>的「上次已告知模型的日期」（CC {@code STATE.lastEmittedDate}）——
 * 它与 A/B/C/D <b>不属于同一批</b>（那四个是「头部字节从哪来」，它是「尾部要不要补一条
 * date_change」），单列在此只为让「它<b>只</b>被 /clear 清」这一 CC 事实与其它清空点
 * <b>共用同一套集合接线</b>（否则会散落成一处裸调用）。语义与清空点见该枚举常量的 javadoc。
 *
 * <p><b>WHY 单点承载（本类的存在理由）</b>：CC 的失效纪律<u>不是</u>「哪个事件清缓存」，而是
 * 「<b>哪个事件清<u>哪几个集合</u></b>」——同一批事件里 {@code /clear} 清全集，而
 * {@code worktree 进/出} 与 {@code 同会话 resume} <b>刻意只清 A</b>，让 B/C/D（claudeMd /
 * 日期 / gitStatus）在那两个时刻<b>保持陈旧</b>。若把 4 个集合实现成「统一清」，打掉前缀的
 * 频率会<b>高于</b> CC，命中率反而更低（计划 §6「做多了比 CC 还差」）。
 * ⇒ 集合成员与调用点的对应关系必须只有<b>一处</b>权威（本类），
 * 调用点（{@code PromptCacheGroup.CLEAR_SESSION_ALL} / {@code POST_COMPACT_SECTIONS} / …）
 * 只<b>引用</b>具名集合，⛔ 不得在各处现拼集合。
 *
 * <h2>CC 真源（逐条实测 file:line · 由实施者 grep 穷举，非计划转抄）</h2>
 * <table border="1">
 *   <caption>4 个集合 → CC 承载物 → 全部清空调用点</caption>
 *   <tr><th>集合</th><th>本仓承载物</th><th>CC 承载物</th><th>CC 清空点</th></tr>
 *   <tr>
 *     <td><b>A</b> 分段缓存</td>
 *     <td>{@link SystemPromptSectionCache}（会话级 Map&lt;段名,String&gt;）</td>
 *     <td>{@code STATE.systemPromptSectionCache}（bootstrap/state.ts:203/:399/:429，
 *         读 :1641 写 :1645 清 :1652）+ beta header latches</td>
 *     <td>{@code clearSystemPromptSections()}（constants/systemPromptSections.ts:65-68）
 *         <b>共 6 处</b>：<br>
 *         ① {@code services/compact/postCompactCleanup.ts:62}（<b>无守卫</b>，子代理压缩也走）<br>
 *         ② {@code setup.ts:346}（仅 {@code USER_TYPE==='ant'}+内网仓判定）<br>
 *         ③ {@code tools/EnterWorktreeTool/EnterWorktreeTool.ts:99}<br>
 *         ④ {@code tools/ExitWorktreeTool/ExitWorktreeTool.ts:143}<br>
 *         ⑤ {@code utils/sessionRestore.ts:364}（{@code restoreWorktreeForResume}）<br>
 *         ⑥ {@code utils/sessionRestore.ts:388}（{@code exitRestoredWorktree}）</td>
 *   </tr>
 *   <tr>
 *     <td><b>B</b> userContext 冻结值</td>
 *     <td>{@link SystemPromptContextProvider#getUserContext()} 实例级 memoize
 *         （claudeMd + currentDate）</td>
 *     <td>{@code getUserContext} 进程级 memoize（context.ts:155-189）</td>
 *     <td>{@code getUserContext.cache.clear?.()} <b>共 6 处</b>：<br>
 *         ① {@code commands/clear/caches.ts:52}（{@code clearSessionCaches}，/clear）<br>
 *         ② {@code commands/compact/compact.ts:63}（SM 优先路径）<br>
 *         ③ {@code commands/compact/compact.ts:117}（传统路径）<br>
 *         ④ {@code commands/compact/compact.ts:203}（reactive / prompt-too-long 路径）<br>
 *         ⑤ {@code services/compact/postCompactCleanup.ts:59}（<b>仅 {@code isMainThreadCompact}</b>，
 *            子代理 compact 刻意不清）<br>
 *         ⑥ {@code context.ts:32}（{@code setSystemPromptInjection}，ant-only cache breaker）</td>
 *   </tr>
 *   <tr>
 *     <td><b>C</b> systemContext 冻结值</td>
 *     <td>{@link SystemPromptContextProvider#getSystemContext()} 实例级 memoize（gitStatus 块 +
 *         cacheBreaker）</td>
 *     <td>{@code getSystemContext} 进程级 memoize（context.ts:116-150）</td>
 *     <td>{@code getSystemContext.cache.clear?.()} <b>共 2 处</b>：<br>
 *         ① {@code commands/clear/caches.ts:53}<br>
 *         ② {@code context.ts:33}（同 setter 双清）</td>
 *   </tr>
 *   <tr>
 *     <td><b>D</b> gitStatus + sessionStartDate</td>
 *     <td>D-1 {@link GitStatusProvider} 实例级 memoize；
 *         D-2 {@link SessionPromptCacheStore#sessionStartDate()}</td>
 *     <td>D-1 {@code getGitStatus} 进程级 memoize（context.ts:36）；<br>
 *         D-2 {@code getSessionStartDate = memoize(getLocalISODate)}（constants/common.ts:24）</td>
 *     <td>D-1 {@code getGitStatus.cache.clear?.()} <b>仅 1 处</b>：{@code caches.ts:54}；<br>
 *         D-2 {@code getSessionStartDate.cache.clear?.()} <b>仅 1 处</b>：{@code caches.ts:55}</td>
 *   </tr>
 * </table>
 *
 * <h2>⭐ 关键不对称（本类的核心不变量）</h2>
 * <ul>
 *   <li>{@code /clear}（{@code clearSessionCaches}）是<b>唯一</b>的全集清理 ⇒ {@link #CLEAR_SESSION_ALL}；</li>
 *   <li>{@code /compact}（{@code runPostCompactCleanup}）= A（<b>无守卫</b>，子代理也走）
 *       + B（<b>仅主线程</b>）⇒ {@link #POST_COMPACT_SECTIONS} / {@link #POST_COMPACT_USER_CONTEXT}；</li>
 *   <li>{@code /compact} 命令三条成功链另各清一次 B ⇒ {@link #COMPACT_COMMAND_USER_CONTEXT}；</li>
 *   <li>worktree 进 / 出 = <b>只清 A</b> ⇒ {@link #WORKTREE_SECTIONS}（那两刻 claudeMd / 日期 /
 *       gitStatus 刻意陈旧）；</li>
 *   <li>{@code setSystemPromptInjection} = B + C（<b>不清 A/D</b>）⇒ {@link #INJECTION_CHANGE}
 *       （⚠ 该常量当前停<b>语义留痕</b>态、全仓零调用点：CC 侧唯一调用点是 /clear 的
 *       {@code caches.ts:66}，而 B/C 在该处已被 {@link #CLEAR_SESSION_ALL} 覆盖 ⇒
 *       <b>对齐 CC 的 stub 现实，不是漏接线</b>，详见该常量 javadoc）；</li>
 *   <li>每轮路径（{@code query.ts} / {@code services/api/claude.ts}）<b>零命中</b>（已反向穷举）
 *       ⇒ 「会话边界才允许清」是结构性保证。</li>
 * </ul>
 *
 * <h2>⛔⛔ 映射陷阱（必读 · 照抄 CC 会让本 bug 原样复发）</h2>
 * <p>CC 的 {@code utils/sessionRestore.ts:364/:388} 属于集合 A 的清空点，它们发生在
 * <b>同一次会话内</b>（{@code /resume} 中途切 worktree、退出恢复）。<b>但本仓的「一次 run」
 * 与 CC 的「一次 query」不是同一个粒度</b>：nexusai 每发一次消息（= 每起一个 run）都会重建
 * AgentState；若把 CC 的 {@code sessionRestore} 清空点原样搬到「同会话再次进入」这条路径上，
 * 就等于<b>每个 run 清一次</b> ⇒ 头部每轮重建 ⇒ DeepSeek 前缀缓存命中塌回 ~20%（本次要修的
 * 故障本身）。
 * <p><b>因此本仓的映射规则是</b>：
 * <ol>
 *   <li>CC {@code sessionRestore} 的两个清空点在 nexusai <b>无对应物</b>（本仓 {@code /resume}
 *       是「恢复被后台化的 agent transcript」＝ {@code ResumeService.resumeAgentBackground}，
 *       <b>不是</b>会话级 worktree 切换）⇒ <b>不接线</b>，并在该入口写明「⛔ 不得清任何集合」；</li>
 *   <li>凡是「同 run / 同会话再次进入」语义的路径，<b>一律不得清任何集合</b>；</li>
 *   <li>跨 run 稳定由 {@link SessionPromptCacheStore}（会话级、跨 run 不销毁）保证，
 *       清空只允许发生在<b>显式事件</b>（/clear、/compact、worktree 进/出、注入变更）。</li>
 * </ol>
 *
 * <h2>CC 进程级 → 本仓会话级</h2>
 * <p>CC 是「一进程 = 一会话」，故上表所有清空都是<b>进程级</b>（无 session 维度）；本仓一 JVM
 * 多会话 ⇒ 一律映射为<b>会话级（按 sessionId 分区）</b>，由
 * {@link SessionPromptCacheRegistry#clearPromptCaches(String, Set, String)} 单点寻址。
 * ⛔ 不得退化为「广播清全部会话」——那会在会话 A 压缩时打掉会话 B 的头部（跨会话串味）。
 */
public enum PromptCacheGroup {

    /**
     * <b>集合 A</b> · 分段缓存（{@link SystemPromptSectionCache}，判据 = 段名字符串）。
     * CC：{@code clearSystemPromptSections()}（systemPromptSections.ts:65-68，分段 Map + beta header latches）。
     */
    A_SECTIONS,

    /**
     * <b>集合 B</b> · userContext 冻结值（claudeMd + currentDate）。
     * CC：{@code getUserContext.cache.clear?.()}（context.ts:155-189 的 memoize）。
     */
    B_USER_CONTEXT,

    /**
     * <b>集合 C</b> · systemContext 冻结值（gitStatus 块 + cacheBreaker）。
     * CC：{@code getSystemContext.cache.clear?.()}（context.ts:116-150 的 memoize）。
     */
    C_SYSTEM_CONTEXT,

    /**
     * <b>集合 D</b> · gitStatus 快照（D-1，{@link GitStatusProvider}）与
     * 会话冻结日期（D-2，{@link SessionPromptCacheStore#sessionStartDate()}）。
     * CC：{@code getGitStatus.cache.clear?.()}（caches.ts:54）+
     * {@code getSessionStartDate.cache.clear?.()}（caches.ts:55）—— CC 侧各<b>仅 1 处</b>，
     * 且<u>都只在 {@code /clear}</u>。
     */
    D_GIT_STATUS_AND_DATE,

    /**
     * <b>集合 E</b> · 尾部 date_change <b>投递状态</b>（{@link SessionPromptCacheStore#lastEmittedDate()}）·
     * CC：{@code STATE.lastEmittedDate}（bootstrap/state.ts:205/:401，读 :1658 写 :1662）。
     *
     * <p><b>⛔ 它<b>不是</b>头部缓存</b>：上面 A/B/C/D 四个集合承载的都是「头部字节从哪来」，
     * 而本项承载的是<b>尾部投递层</b>的「上次已告知模型的日期」——用于跨午夜时判断是否需要
     * 在尾部追加 {@code date_change} 附件（{@code getDateChangeAttachments}，utils/attachments.ts:1406-1443）。
     * 单列一项是为了把「它只被 /clear 清」这一 CC 事实<b>显式化</b>（CC 侧唯一的
     * {@code setLastEmittedDate(null)} 就在 {@code clearSessionCaches}，caches.ts:69）——
     * ⛔ 不并进 A/B/C/D 中任一项，否则其它清空点（worktree / compact）会连带清掉它，
     * 让「同一天内被切 worktree 后重复播报日期变更」发生（CC 无此行为）。
     */
    E_LAST_EMITTED_DATE;

    // ════════════════════════════════════════════════════════════════════
    // 具名集合常量 · **一个 CC 清空点一个常量**（调用点只引用具名常量，⛔ 不现拼集合）
    //
    // 这样命名的理由：本仓的对齐判据是「与 CC 清空点逐条对排」，若把两个 CC 行合成一个
    // 常量（如「/compact 主线程 = A+B」），调用点就无法自证「我清的是哪一行」，
    // 且容易与另一处 A 清空点重复清（双日志、掩盖真实差异）。
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>{@code /clear}</b> · 全集 A+B+C+D —— CC <b>唯一</b>的全集清理
     * （{@code clearSessionCaches}，commands/clear/caches.ts:52-55 相邻四行）。
     *
     * <p>对等物：CC {@code commands/clear/conversation.ts:127} 的
     * {@code clearSessionCaches(preservedAgentIds)}，以及 <b>启动期</b>
     * {@code --continue}/{@code --resume} 两条（main.tsx:3111 / main.tsx:3362）。
     * ⚠ 计划 §1.3 把「/resume 恢复」写作「只清 A」<b>不精确</b>：<b>启动期</b> resume 走的是
     * {@code clearSessionCaches} = 全集；只有 {@code sessionRestore.ts}（同会话中途切 worktree）
     * 才是只清 A，而那一支在本仓<b>无对应物</b>（见类 javadoc 映射陷阱）。
     */
    public static final Set<PromptCacheGroup> CLEAR_SESSION_ALL =
        Collections.unmodifiableSet(EnumSet.of(
            A_SECTIONS, B_USER_CONTEXT, C_SYSTEM_CONTEXT, D_GIT_STATUS_AND_DATE,
            // [步骤 5] 集合 E（尾部 date_change 投递状态）也在 clearSessionCaches 内被清：
            //   CC commands/clear/caches.ts:69 `setLastEmittedDate(null)`（注释「Clear last emitted
            //   date so it's re-detected on next turn」）。这是 lastEmittedDate 的<b>唯一</b>清空点
            //   （全仓 grep 已穷举：caches.ts:69 唯一 set(null)，另两处是 attachments.ts:1423/:1431 的
            //   正常写入）⇒ 它只随 /clear（及启动期 --continue/--resume 的 clearSessionCaches）复位。
            E_LAST_EMITTED_DATE));

    /**
     * <b>postCompactCleanup 的分段缓存</b> · 只有 A —— CC
     * {@code clearSystemPromptSections()}（services/compact/postCompactCleanup.ts:62）。
     *
     * <p>⭐ 该行在 {@code isMainThreadCompact} 守卫<b>之外</b>（<b>无守卫</b>）⇒
     * <b>子代理 compact 也执行</b>（CC 刻意如此：分段缓存是「同一份提示的重算」，
     * 子代理压缩后主线程重算是无害且正确的）。
     */
    public static final Set<PromptCacheGroup> POST_COMPACT_SECTIONS =
        Collections.unmodifiableSet(EnumSet.of(A_SECTIONS));

    /**
     * <b>postCompactCleanup 的 userContext</b> · 只有 B —— CC
     * {@code getUserContext.cache.clear?.()}（services/compact/postCompactCleanup.ts:59）。
     *
     * <p>⛔ <b>该行在 {@code isMainThreadCompact} 守卫之内</b>（:51 {@code if (isMainThreadCompact) { ... }}）
     * ⇒ 子代理 compact <b>刻意不清</b>主会话的 userContext。
     * WHY（CC 源码注释 :36-40 的原话）：子代理（{@code agent:*}）与主线程同进程、共享模块级状态
     * （context-collapse store / getMemoryFiles one-shot hook flag / getUserContext cache），
     * 子代理压缩时重置这些会<b>破坏主线程</b>。
     * ⛔ 不含 C：CC 明确不清 {@code getSystemContext}（compact 后 systemContext/gitStatus
     * 命中缓存不重算，SP-07 △-6 已登记）。
     * ⛔ 不含 D：{@code getGitStatus}/{@code getSessionStartDate} 的 clear 只出现在 /clear。
     */
    public static final Set<PromptCacheGroup> POST_COMPACT_USER_CONTEXT =
        Collections.unmodifiableSet(EnumSet.of(B_USER_CONTEXT));

    /**
     * <b>{@code /compact} 命令自身的 userContext 显式清</b> · 只有 B —— CC
     * {@code getUserContext.cache.clear?.()}（commands/compact/compact.ts:63 / :117 / :203，
     * SM 优先 / 传统 / reactive 三条成功链各一行）。
     *
     * <p>与 {@link #POST_COMPACT_USER_CONTEXT} <b>同集合但不同调用点</b>（CC 就是这样两处都调，
     * 幂等双清），故单独命名以便日志能指认「是命令侧还是清理序列侧」。
     */
    public static final Set<PromptCacheGroup> COMPACT_COMMAND_USER_CONTEXT =
        Collections.unmodifiableSet(EnumSet.of(B_USER_CONTEXT));

    /**
     * <b>worktree 进 / 出</b> · 只有 A —— CC {@code EnterWorktreeTool.ts:99} /
     * {@code ExitWorktreeTool.ts:143} 都只调 {@code clearSystemPromptSections()}。
     *
     * <p>⭐ 那两刻 CC <b>刻意</b>让 claudeMd（B）/ 日期（D-2）/ gitStatus（D-1）保持陈旧
     * —— 用「头部陈旧」换「前缀稳定」，变更经尾部投递层告知（计划 §1.5）。
     * ⛔ 不得在此处顺手清 B/C/D（会把打掉前缀的频率弄得高于 CC）。
     */
    public static final Set<PromptCacheGroup> WORKTREE_SECTIONS =
        Collections.unmodifiableSet(EnumSet.of(A_SECTIONS));

    /**
     * <b>system prompt 注入变更</b>（ant-only cache breaker）· B + C ——
     * CC {@code setSystemPromptInjection}（context.ts:29-34）双清
     * {@code getUserContext.cache} + {@code getSystemContext.cache}。
     * ⛔ 不含 A/D。
     *
     * <h2>⭐ 本常量当前是「语义留痕」态：全仓零调用点（已复验 · 不是漏接线）</h2>
     * <p><b>CC 侧实测事实（本轮实施者 grep 复验，⛔ 不采信上一轮派单书的旧说法）</b>：
     * <ul>
     *   <li>{@code setSystemPromptInjection} 在 CC 参考副本里的<b>唯一调用点</b> =
     *       {@code Open-ClaudeCode/src/commands/clear/caches.ts:66}
     *       （在 {@code clearSessionCaches} 内，即 <b>/clear 路径</b>，传 {@code null}）。</li>
     *   <li>⛔ <b>不是 break-cache</b>：{@code Open-ClaudeCode/src/commands/break-cache/index.js}
     *       全文 = {@code export default { isEnabled: () => false, isHidden: true, name: 'stub' }}
     *       —— 它是个 <b>stub</b>（命令不可用、恒不执行），<b>不调用</b>该 setter。
     *       （上一轮派单书曾写「break-cache 是唯一调用者」，<b>已裁定为错</b>，此处按实测事实书写。）</li>
     *   <li>而 {@code clearSessionCaches} 在同一函数内<b>已经</b>在 {@code :52/:53} 清过
     *       {@code getUserContext.cache} / {@code getSystemContext.cache} ⇒ setter 那一步的
     *       B+C 效果被 {@link #CLEAR_SESSION_ALL}（/clear 全集）<b>完全覆盖</b>（幂等重复）。</li>
     * </ul>
     * <p><b>本仓侧</b>：注入值的<b>真实失效通道</b>是
     * {@code SystemPromptInjection.setSystemPromptInjection} 内的
     * {@code CACHE_CLEAR_HOOKS} <b>逐个通知已注册 provider</b>（{@link SystemPromptInjection} 的
     * register/unregister 回调表，等价 CC 的 {@code memoize.cache.clear}），
     * ⛔ <b>不经</b>本集合常量；/clear 的 B/C 又已由 {@link #CLEAR_SESSION_ALL} 覆盖。
     * ⇒ 本常量保留下来只为<b>语义可对排</b>（CC「setter = B+C、不含 A/D」这条集合关系仍需
     * 有一处权威承载，将来若接线 ant-only cacheBreaker 可直接引用）。
     * <p><b>⛔ 显式标注：这是对齐 CC 的 stub 现实（CC 的 break-cache 命令本身即 stub、
     * {@code isEnabled()} 恒 false），不是漏接线。</b>⛔ 不得因「零调用点」而删除本常量，
     * 也不得把它硬接进某条清空链（那会造出一条 CC 没有的清空点）。
     */
    public static final Set<PromptCacheGroup> INJECTION_CHANGE =
        Collections.unmodifiableSet(EnumSet.of(B_USER_CONTEXT, C_SYSTEM_CONTEXT));

    /**
     * <b>工具注册表变化</b>（本仓特有 · CC <b>无</b>对应清空点）· 只有 A。
     *
     * <p>登记为<b>有意偏离（已复核）</b>：CC 的 6 个 A 清空点里没有「工具注册」这一支
     * （6 处已 grep 穷举：postCompactCleanup:62 / setup:346 / EnterWorktree:99 /
     * ExitWorktree:143 / sessionRestore:364 / sessionRestore:388）。CC 的工具池变化由
     * ①「请求 tools 数组本身变化」②{@code mcp_instructions} 段的每轮重算承担
     * （2.1.88 口径 = {@code cacheBreak=true}，prompts.ts:513-521；
     * ⚠️ 2.1.278 已删该 system 段 → 改走 {@code mcp_instructions_delta} 尾部附件）
     * ⇒ CC <b>从不清分段缓存</b>。
     * <p>本仓把它归入 A（工具清单影响的是提示组装面），⛔ 明确<b>不</b>碰 B/C/D。
     * <p><b>[C4-A2 · 2026-09-21 改准] 本仓现状已不同于上面那句</b>：{@code mcp_instructions} 已从
     * {@code cacheBreak=true} 改为<b>可缓存段</b>（对齐 2.1.278 的 0 个 cacheBreak 段）⇒
     * 本仓已注册的 10 条动态 section 里<b>无一条</b>每轮重算，其中 {@code mcp_instructions} 与 MCP 相关，
     * 现在<b>只能靠本集合（A）的清空来刷新</b>。⚠️ 但本清空点仅在 {@code ToolRegistrationConfig}
     * @Bean 构建期触发（运行期 MCP 中途连接<b>不</b>经此），故运行期 MCP 指令刷新实际只剩
     * {@code /clear} 与 {@code /compact} —— 已按「已知分歧」登记，MCP delta 通道（A1）留作后续批次。
     */
    public static final Set<PromptCacheGroup> TOOL_REGISTRATION_SECTIONS =
        Collections.unmodifiableSet(EnumSet.of(A_SECTIONS));

    /**
     * 集合的可读名（日志用）· 中文 + 英文集合字母，便于日志与 CC 真源逐条对排。
     *
     * @param groups 集合
     * @return 形如 {@code "[A 分段缓存, B userContext]"} 的可读串
     */
    public static String describe(Set<PromptCacheGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        // EnumSet 迭代序 = 枚举声明序（A→B→C→D）；传入 HashSet 等其它实现时按 EnumSet 归一后再迭代，
        // 保证日志顺序稳定可比对（集合本身无序，日志要可比）。
        for (PromptCacheGroup g : EnumSet.copyOf(groups)) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(g.label());
            first = false;
        }
        return sb.append(']').toString();
    }

    /** 单集合的中文可读名（日志用）。 */
    public String label() {
        return switch (this) {
            case A_SECTIONS -> "A 分段缓存";
            case B_USER_CONTEXT -> "B userContext(claudeMd+日期)";
            case C_SYSTEM_CONTEXT -> "C systemContext(gitStatus块)";
            case D_GIT_STATUS_AND_DATE -> "D gitStatus+sessionStartDate";
            case E_LAST_EMITTED_DATE -> "E lastEmittedDate(尾部date_change投递状态)";
        };
    }
}
