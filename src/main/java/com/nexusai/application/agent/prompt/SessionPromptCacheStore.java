package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <b>会话级</b> prompt 缓存 store · CC 进程级 {@code STATE} 缓存容器的「按会话分区」映射。
 *
 * <p><b>⭐ 语义映射（必读 · 不是照抄）</b>：CC 是「一进程 = 一会话」，故其缓存全部是
 * <b>进程级</b>（模块级 lodash memoize / 全局 {@code STATE}）；nexusai 是 Web 服务端，
 * 同一 JVM 承载多会话 ⇒ CC 的「进程级」在本仓<b>必须</b>映射为<b>会话级（按 sessionId 分区）</b>。
 * ⛔ 不得做成真正的全局单例：那会让会话 A 的 claudeMd / 日期 / 分段值串到会话 B
 * （跨会话串味，用户铁律「会话态按 sessionId 分区」）。
 *
 * <p><b>CC 真源（缓存容器与读写清）</b>：
 * <ul>
 *   <li>容器声明 {@code systemPromptSectionCache: Map<string, string | null>}
 *       （Open-ClaudeCode/src/bootstrap/state.ts:203）</li>
 *   <li>初始化 {@code new Map()}（state.ts:399）· 全局单例 {@code const STATE}（state.ts:429）</li>
 *   <li>读 {@code getSystemPromptSectionCache()}（state.ts:1641-1643）·
 *       写 {@code setSystemPromptSectionCacheEntry(name, value)}（state.ts:1645-1650）·
 *       清 {@code clearSystemPromptSectionState()}（state.ts:1652-1653）</li>
 *   <li>命中/写回语义 {@code resolveSystemPromptSections}（constants/systemPromptSections.ts:43-58）</li>
 * </ul>
 *
 * <p><b>承载物 ①：分段缓存</b> —— {@link #sectionCache()}（{@code Map<段名, String|null>}，
 * 判据是<b>段名字符串</b>，不是对象身份；compute 返回 null 也缓存）。
 *
 * <p><b>承载物 ②：四个会话冻结值（步骤 5 的取值来源）</b> —— 本类<b>不</b>复制一份 Map
 * （同语义双实现=两份真相），而是用「会话级实例 + 实例级 memoize」承载，逐项对应 CC 的进程级 memoize：
 * <table border="1">
 *   <caption>四个冻结值 → 承载点 → CC 真源</caption>
 *   <tr><th>冻结值</th><th>本类承载点</th><th>CC 真源</th></tr>
 *   <tr><td>{@code sessionStartDate}</td><td>{@link #sessionStartDate()}（final 字段，
 *       首次使用即定）</td><td>{@code memoize(getLocalISODate)}（constants/common.ts:24）</td></tr>
 *   <tr><td>{@code gitStatus}</td><td>{@link #contextProvider} 内的 GitStatusProvider
 *       （实例级 memoize；另经 {@code SessionGitStatusRegistry} 已按会话级复用）</td>
 *       <td>{@code getGitStatus} 进程级 memoize（context.ts:36）</td></tr>
 *   <tr><td>{@code userContext}</td><td>{@link #contextProvider}
 *       {@code getUserContext()} 实例级 memoize</td><td>进程级 memoize（context.ts:155）</td></tr>
 *   <tr><td>{@code systemContext}</td><td>{@link #contextProvider}
 *       {@code getSystemContext()} 实例级 memoize</td><td>进程级 memoize（context.ts:116）</td></tr>
 * </table>
 * ⇒ 「provider 实例跨 run 存活」正是这四个值「跨 run 不重算」的<b>唯一</b>机制：
 * 只要还持有同一个 {@link SystemPromptContextProvider} 实例，它的两个 volatile 缓存字段
 * 就是上一次 run 算出的字节（键与运行时序无关）⇒ 头部字节跨 run 稳定。
 *
 * <p><b>承载物 ③：尾部投递状态（步骤 5 · 跨午夜）</b> —— {@link #lastEmittedDate()}
 * （CC {@code STATE.lastEmittedDate}，bootstrap/state.ts:205）。它与①②<b>方向相反</b>：
 * ①②是「头部怎么保持陈旧」，③是「尾部怎么把日期变更告知模型」——两者缺一不可
 * （只冻结头部 = 模型永久停在旧日期；只投递尾部 = 头部每轮被打掉）。详见该字段 javadoc。
 *
 * <p><b>生命周期</b>：跨 run <b>不销毁</b>（这正是对齐点 —— CC 的 STATE 活到进程结束）；
 * 会话终结时由 {@link SessionPromptCacheRegistry#evict(String)} 移除并 {@link #close()} 注销
 * provider 的缓存清理回调（CC 一进程一会话，会话结束即进程退出，无此动作；Java 常驻 JVM
 * 必须显式回收，与 {@code SessionGitStatusRegistry.evict} 同一口径）。
 * ⛔ <b>不在 /clear、/compact、worktree 进/出时移除本 store</b>：那些事件只清<b>缓存内容</b>
 * （CC {@code clearSystemPromptSections} 清的是 Map，STATE 本身继续存活）。
 *
 * <p><b>local-only 红线（CLAUDE.md BudgetTracker 架构）</b>：纯内存进程内状态，绝不序列化 /
 * 绝不经 STOMP / WebSocket / EventPublisher / outbound DTO 外发（它承载的 claudeMd 可能含项目私有内容）。
 *
 * <p><b>并发</b>：{@link #sectionCache()} 内部为 synchronizedMap（resolve 并行 compute 多线程写）；
 * {@link #contextProvider} 懒建走「volatile + 双重检查 + 首建一次」单飞（对齐 CC memoize 的
 * promise 共享语义 / {@code SessionGitStatusRegistry#getForSession} 的 computeIfAbsent 先例：
 * <b>首个调用方传的构造参数胜</b>）。
 */
public final class SessionPromptCacheStore {

    private static final Logger log = LoggerFactory.getLogger(SessionPromptCacheStore.class);

    /**
     * 会话 id（short 形态 sess-xxx）· ⛔ 未分区（无会话）时为 {@code null}，
     * 此时本 store 由调用方（{@code AgentState}）私有持有，不进注册表（防跨会话串味）。
     */
    private final String sessionId;

    /**
     * 会话冻结日期（{@code "YYYY-MM-DD"}）· 承载物 ② 之一，见类 javadoc 表格。
     *
     * <p>非 final + volatile：集合 <b>D-2</b> 的失效（CC {@code getSessionStartDate.cache.clear?.()}，
     * caches.ts:55）要求「清后下次读取取当天」——{@link #resetSessionStartDate(String)} 改本值即可
     * 对 provider 的下一次重算生效（provider 经 {@code store::sessionStartDate} 读取，见
     * {@link #contextProvider(Supplier)}）。⚠ 只清 D-2 <b>不会</b>让头部变：currentDate 被集合 B
     * 的 memoize 包住，必须同时清 B（CC 亦然）。
     */
    private volatile String sessionStartDate;

    /** 承载物 ① · 分段缓存（判据 = 段名字符串）· CC {@code STATE.systemPromptSectionCache}。 */
    private final SystemPromptSectionCache sectionCache = new SystemPromptSectionCache();

    /**
     * <b>承载物 ③（步骤 5 · 跨午夜投递状态）</b>· 上次已告知模型的本地日
     * （{@code "YYYY-MM-DD"}，null = 本会话尚未播报过）·
     * CC {@code STATE.lastEmittedDate}（bootstrap/state.ts:205 声明 / :401 初值 null /
     * 读 {@code getLastEmittedDate} :1658 / 写 {@code setLastEmittedDate} :1662）。
     *
     * <p><b>承载什么</b>：{@code getDateChangeAttachments}（utils/attachments.ts:1406-1443）的判据状态 ——
     * {@code currentDate = getLocalISODate()}，与 {@code lastEmittedDate} 比对：
     * <ul>
     *   <li>null（本会话首轮）⇒ <b>只记录不播报</b>（CC :1421-1425）</li>
     *   <li>相同 ⇒ 无动作（:1427-1429）</li>
     *   <li>不同（跨午夜）⇒ 记录新值 + 返回 {@code [{type:'date_change', newDate}]}（:1431-1443），
     *       由调用方在<b>尾部</b>追加告知模型</li>
     * </ul>
     *
     * <p><b>⛔ 与 {@link #sessionStartDate} 的分工（勿混）</b>：{@code sessionStartDate} 是
     * <b>头部</b>日期（{@code getUserContext → currentDate}，跨午夜<b>保留旧值</b>、永不更新）；
     * 本字段是<b>尾部</b>投递判据（跨午夜<b>更新为新值</b>，让模型知道日期变了）。
     * 两者方向相反且必须相反 —— 只更头部 = 打掉整段前缀缓存；只更尾部 = 模型仍以为是昨天。
     *
     * <p><b>清空点唯一</b>：{@link PromptCacheGroup#CLEAR_SESSION_ALL}（CC
     * {@code clearSessionCaches} 的 {@code setLastEmittedDate(null)}，caches.ts:69）——
     * 全仓 grep 已穷举，CC 侧 set(null) 仅此一处。
     */
    private volatile String lastEmittedDate;

    /**
     * 承载物 ② · 会话级 {@link SystemPromptContextProvider}（懒建，首个 run 构建后跨 run 复用）·
     * ⛔ 不得每 run 重建：重建即 memoize 归零，userContext/systemContext 又变回「每 run 重算」。
     */
    private volatile SystemPromptContextProvider contextProvider;

    SessionPromptCacheStore(String sessionId, String sessionStartDate) {
        this.sessionId = sessionId;
        this.sessionStartDate = sessionStartDate;
    }

    /** 会话 id（未分区时为 null）。 */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 会话冻结日期 · 承载物 ②。
     *
     * <p>取值时机 = store 首次被创建（= 该会话首个 run 的材料收集），此后<b>不再更新</b>：
     * 对齐 CC {@code memoize(getLocalISODate)}（constants/common.ts:24）——
     * 跨午夜保留旧日期（CC 走「头部保留旧日期 + 尾部 date_change 附件」，见 utils/attachments.ts:1405-1418）。
     */
    public String sessionStartDate() {
        return sessionStartDate;
    }

    /** 承载物 ① · 分段缓存（判据 = 段名字符串）。 */
    public SystemPromptSectionCache sectionCache() {
        return sectionCache;
    }

    /**
     * <b>承载物 ③</b>· 上次已告知模型的本地日（null = 本会话尚未播报过）·
     * CC {@code getLastEmittedDate()}（bootstrap/state.ts:1658）。
     *
     * <p>消费点 = {@code AgentLoopContext.maybeEmitDateChange}（跨午夜判据）。
     */
    public String lastEmittedDate() {
        return lastEmittedDate;
    }

    /**
     * <b>承载物 ③</b>· 记录已告知模型的本地日（含首轮「只记录不播报」的 null→日期 落位）·
     * CC {@code setLastEmittedDate(date)}（bootstrap/state.ts:1662）。
     *
     * <p>单写点 = {@code maybeEmitDateChange}（同 CC：attachments.ts:1423/:1431 两处）+ 本类的
     * {@link #clearGroups} 集合 E 分支（置 null）。
     *
     * @param date 本地日 {@code "YYYY-MM-DD"}；null = 复位（下次读取等同「本会话尚未播报」）
     */
    public void setLastEmittedDate(String date) {
        this.lastEmittedDate = date;
    }

    /**
     * 取（必要时首建）会话级 context provider · <b>首建一次，此后恒返回同一实例</b>。
     *
     * <p>{@code firstRunFactory} 只在首次调用时求值（后续 run 传的工厂<b>未被调用即丢弃</b>）——
     * 这是「首个 run 的构造参数胜」的显式化，与 CC 进程级 memoize 的「首次计算即定」
     * 及 {@code SessionGitStatusRegistry#getForSession} 的 computeIfAbsent 同一语义。
     * ⚠️ 故工厂 lambda <b>必须</b>只捕获会话级常量（claudemdEngine / 会话项目根 / sessionId /
     * 会话冻结日期），⛔ 不得捕获「本 run 独有」的可变值（否则首个 run 的值被冻结为全会话值）。
     *
     * @param firstRunFactory 首次构建用的工厂（非 null；见上「只捕获会话级常量」约束）
     * @return 会话级 provider（同一 store 恒同一实例）
     */
    public SystemPromptContextProvider contextProvider(
            Supplier<SystemPromptContextProvider> firstRunFactory) {
        SystemPromptContextProvider p = contextProvider;
        if (p != null) {
            // [步骤 5 · 数据流日志] 复用命中：本 run 的 userContext/systemContext/gitStatus/日期
            //   直接取上一 run 算出的字节（**不重算**）—— 这正是头部跨 run 逐字节稳定的机制。
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheStore] 会话级 context provider 复用命中（跨 run 不重算 ⇒ 头部字节稳定）: "
                    + "sessionId={} 会话冻结日期={} 段缓存条数={}",
                    sessionId, sessionStartDate, sectionCache.size());
            }
            return p;
        }
        synchronized (this) {
            if (contextProvider == null) {
                if (firstRunFactory == null) {
                    throw new IllegalArgumentException("firstRunFactory 不能为 null（首建会话级 provider 必需）");
                }
                contextProvider = firstRunFactory.get();
                log.info("[SessionPromptCacheStore] 会话级 context provider 首建完成"
                        + "（此后跨 run 复用同一实例 ⇒ userContext/systemContext/gitStatus 冻结）: sessionId={}",
                    sessionId);
            }
            return contextProvider;
        }
    }

    /** 会话级 provider 是否已建（诊断/日志用；未建 ⇒ 本会话尚未跑过材料收集）。 */
    public boolean hasContextProvider() {
        return contextProvider != null;
    }

    // ════════════════════════════════════════════════════════════════════
    // 失效入口 · 按集合（A/B/C/D）精确清 —— 集合成员由 PromptCacheGroup 单点定义
    // ════════════════════════════════════════════════════════════════════

    /**
     * 按<b>具名集合</b>清本会话的缓存 · 会话级失效的<b>唯一执行口</b>
     * （调用方一律经 {@link SessionPromptCacheRegistry#clearPromptCaches(String, java.util.Set, String)}
     * 或本方法，⛔ 不得直接 {@code sectionCache().clear()} 绕过集合语义）。
     *
     * <p><b>⛔ 为什么必须是「集合」而不是「清空」</b>：CC 的 4 个集合在<b>不同事件上被清不同的子集</b>
     * （{@code /clear} 清全集，worktree 进/出只清 A，{@code /compact} 清 A + B(仅主线程)）。
     * 若统一清，打掉前缀的频率会<b>高于</b> CC ⇒ 命中率反而更低（计划 §6）。集合成员与调用点的
     * 对应关系见 {@link PromptCacheGroup}。
     *
     * <p>逐集合执行并<b>逐集合打一行中文日志</b>（清了哪个集合 / 原因 / sessionId），
     * 便于与 CC 真源对排归因（本仓对齐判据要求「日志能指认清了哪个集合」）。
     *
     * <p>并发：集合 A 走 {@link SystemPromptSectionCache}（内部 synchronizedMap）；
     * 集合 B/C 走 provider 的 volatile 双检字段（清空即置 {@code *Computed=false}，
     * 与计算体互斥语义见 {@code SystemPromptContextProvider#getSystemContext()} 的
     * {@code synchronized(this)} 单飞）；集合 D-1 走 {@link GitStatusProvider#clearCache()} 的
     * 同一把监视器。本方法不持跨集合的锁 ⇒ 无锁序问题。
     *
     * @param groups 待清集合（null/空 ⇒ no-op，只打 debug）
     * @param reason 失效原因（进日志；调用方必须给可归因的具体来源，如
     *               {@code "executeBuiltin(/clear)"} / {@code "compact:main-thread"} /
     *               {@code "EnterWorktreeTool"}）
     */
    public void clearGroups(java.util.Set<PromptCacheGroup> groups, String reason) {
        if (groups == null || groups.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheStore] clearGroups 空集合 ⇒ no-op: sessionId={} reason={}",
                    sessionId, reason);
            }
            return;
        }
        SystemPromptContextProvider provider = this.contextProvider;
        for (PromptCacheGroup g : groups) {
            switch (g) {
                case A_SECTIONS -> {
                    sectionCache.clear();
                    log.info("[SessionPromptCacheStore] 清集合A 分段缓存: sessionId={} reason={}",
                        sessionId, reason);
                }
                case B_USER_CONTEXT -> {
                    if (provider != null) {
                        provider.clearUserContextCache();
                        log.info("[SessionPromptCacheStore] 清集合B userContext 冻结值"
                            + "(= CC getUserContext.cache.clear): sessionId={} reason={}",
                            sessionId, reason);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[SessionPromptCacheStore] 清集合B 跳过：provider 未建"
                            + "（本会话尚未跑过材料收集 ⇒ 无冻结值可清）: sessionId={} reason={}",
                            sessionId, reason);
                    }
                }
                case C_SYSTEM_CONTEXT -> {
                    if (provider != null) {
                        provider.clearSystemContextCache();
                        log.info("[SessionPromptCacheStore] 清集合C systemContext 冻结值"
                            + "(= CC getSystemContext.cache.clear): sessionId={} reason={}",
                            sessionId, reason);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[SessionPromptCacheStore] 清集合C 跳过：provider 未建: sessionId={} reason={}",
                            sessionId, reason);
                    }
                }
                case E_LAST_EMITTED_DATE -> {
                    // [步骤 5] 集合 E：尾部 date_change 投递状态复位 · CC setLastEmittedDate(null)
                    //   （commands/clear/caches.ts:69，注释「Clear last emitted date so it's
                    //   re-detected on next turn」）。⛔ 与 A/B/C/D 不同：它不在 provider 上，
                    //   是本 store 自己的字段 ⇒ 无 provider 也存在、也要清（不放进上面的 if）。
                    String previous = this.lastEmittedDate;
                    this.lastEmittedDate = null;
                    log.info("[SessionPromptCacheStore] 清集合E 尾部 date_change 投递状态已复位为 null"
                        + "(= CC setLastEmittedDate(null), caches.ts:69): sessionId={} {} -> null reason={}",
                        sessionId, previous, reason);
                }
                case D_GIT_STATUS_AND_DATE -> {
                    if (provider != null) {
                        provider.clearGitStatusCache();
                        log.info("[SessionPromptCacheStore] 清集合D-1 gitStatus 快照"
                            + "(= CC getGitStatus.cache.clear): sessionId={} reason={}",
                            sessionId, reason);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[SessionPromptCacheStore] 清集合D-1 跳过：provider 未建: sessionId={} reason={}",
                            sessionId, reason);
                    }
                    resetSessionStartDate(reason);
                }
            }
        }
    }

    /**
     * <b>集合 D-2</b> · 复位会话冻结日期为<b>当天</b> · 对齐 CC
     * {@code getSessionStartDate.cache.clear?.()}（caches.ts:55）——
     * CC 是 memoize 清空 ⇒ 下次读取重新 {@code getLocalISODate()}（constants/common.ts:24）。
     *
     * <p>⚠ <b>只清 D-2 不会让头部变</b>：currentDate 位于 {@code getUserContext()} 的
     * memoize（集合 B）内，头部字节要变必须同时清 B。这是 CC 的真实不对称
     * （{@code caches.ts:53-55} 三行各自独立），⛔ 不要在本方法里「顺手」清 B。
     *
     * <p>生产唯一触发点是 {@code /clear} 链（本仓该链已按 P0-0/N1 停用，见 CommandController）；
     * 会话级新建（界面「+」）走「新 sessionId ⇒ 新 store」自然获得新日期。
     *
     * @param reason 失效原因（日志用）
     */
    public void resetSessionStartDate(String reason) {
        String previous = this.sessionStartDate;
        this.sessionStartDate = localIsoDate();
        log.info("[SessionPromptCacheStore] 清集合D-2 会话冻结日期已复位为当天"
            + "(= CC getSessionStartDate.cache.clear): sessionId={} {} -> {} reason={}"
            + "（⚠ 只清 D-2 头部不会变，需同时清集合B）",
            sessionId, previous, this.sessionStartDate, reason);
    }

    /**
     * 本地 ISO 日期 · 对齐 CC {@code getLocalISODate}
     * （Open-ClaudeCode/src/constants/common.ts:4-15）：{@code CLAUDE_CODE_OVERRIDE_DATE} 可覆盖，
     * 否则本地 {@code YYYY-MM-DD}。
     *
     * <p>⚠ 与 {@code AgentState.localIsoDate()}（同一 CC 真源的另一份实现）语义必须一致 ——
     * 两处都是 CC 单函数的忠实翻译；本方法在 {@link #resetSessionStartDate(String)} 需要
     * 「CC 口径的当天」时被调用（clear 后下次读取取当天，caches.ts:55）。
     *
     * <p><b>[步骤 5] 提升为 public static 供跨午夜投递复用</b>：{@code AgentLoopContext.maybeEmitDateChange}
     * 需要<b>同一口径</b>的「当前本地日」做 {@code currentDate = getLocalISODate()} 比对
     * （CC attachments.ts:1419）。⛔ 不在那里再抄第 4 份实现 —— 本方法是 CC 单函数的唯一翻译，
     * 三处消费（本类 D-2 复位 / 本类 {@link #lastEmittedDate()} 的比对 / 跨午夜投递）共用。
     *
     * @return {@code YYYY-MM-DD}（{@code CLAUDE_CODE_OVERRIDE_DATE} 非空时直接返回该值）
     */
    public static String localIsoDate() {
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return java.time.LocalDate.now().toString();
    }

    /**
     * 终结本会话 store · 仅由 {@link SessionPromptCacheRegistry#evict(String)}（会话删除）调用。
     *
     * <p>做两件事：① 注销 provider 注册到 {@code SystemPromptInjection} 静态表的缓存清理回调
     * （{@code SystemPromptContextProvider#close}，register/unregister 成对，防静态表随会话数累积）；
     * ② 清分段缓存（store 即将被移除，清是为了不留悬挂引用）。
     * 幂等（provider 未建 / 已 close → no-op）。
     */
    public void close() {
        SystemPromptContextProvider provider = contextProvider;
        if (provider != null) {
            provider.close();
        }
        sectionCache.clear();
        log.info("[SessionPromptCacheStore] 会话级 prompt 缓存 store 已终结"
                + "（provider 回调已注销 + 分段缓存已清）: sessionId={} hadProvider={}",
            sessionId, provider != null);
    }
}
