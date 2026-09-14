package com.nexusai.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级 projectRoot 载体 · 对齐 CC {@code bootstrap/state.ts} per-session projectRoot（ODF-A1）。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：
 * <ul>
 *   <li>{@code State.projectRoot} state.ts:45-50 —— 注释即语义：「Stable project root - set once at
 *       startup (including by --worktree flag), never updated by mid-session EnterWorktreeTool. Use
 *       for project identity (history, skills, sessions) not file operations.」</li>
 *   <li>{@code getInitialState()} state.ts:261-279 —— 启动时 {@code realpathSync(cwd())} 冻结为
 *       {@code projectRoot}（:271 resolvedCwd = realpathSync(rawCwd)；:279 projectRoot: resolvedCwd）</li>
 *   <li>{@code getProjectRoot()} state.ts:511-513 —— 返回稳定 projectRoot，会话中不更新
 *       （mid-session EnterWorktreeTool 不得调用 setProjectRoot，skills/history 锚定启动时 cwd）</li>
 *   <li>{@code setProjectRoot()} state.ts:523-525 —— 仅 --worktree 启动 flag 使用</li>
 * </ul>
 *
 * <p><b>为什么引入</b>：旧 Java memory 路径解析链（AutoMemPaths/AgentMemoryDirectory/LlmAgentLoop
 * workspaceDir）恒读 {@code System.getProperty("user.dir")} 单例 → 同一 JVM 内不同 cwd 会话解析到
 * 同一 memory 目录（跨项目记忆污染），违反 CC per-session per-cwd 语义。本类提供按 sessionId 登记的
 * 会话级 projectRoot，生产链经注入 supplier 消费，不再直接读 user.dir。
 *
 * <p><b>查询面（[TL-W2 P11] 收紧后唯一读法）</b>：{@link #getForSession(String)} ——
 * 按 sessionId 直查全局冻结表，<b>miss ⇒ 回源 DB 并回填</b>（{@link #setDbResolver} 注入口，
 * 用户 2026-09-14 裁定 #9）；<b>DB 也查不到 → null</b>（绝不回落 config home / env / user.dir）。
 * 调用方持 sessionId 现算（REST / fork / hook / 启动线程皆可），<b>零 ThreadLocal</b>。
 *
 * <p><b>[TL-W2 P11] 已删除</b>：旧 {@code resolve()} / {@code setCurrent()} / {@code clearCurrent()}
 * + {@code CURRENT ThreadLocal} 读路径 —— 生产 0 调用方（全仓仅测试引用），且 {@code resolve()}
 * 第 3 级回落 {@code CLAUDE_PROJECT_DIR env ?? config home} 把 configHome 当「会话绑定项目」身份返回
 * （与 cwd 身份域红线 D-1 冲突，CwdResolution:163 明写「不读 SessionProjectRoot.resolve()」）。
 * 按死代码决策规则（CC 对应物 = 全局 state.projectRoot，本类已有 sessionId 直查主链）删除，
 * 消除同构陷阱（与 AutoMemPaths 回落失败模式同构）。
 *
 * <p><b>冻结语义</b>（OPD-SPR-03 · CC stable identity）：{@link #setForSession(String, String)} 首写胜，
 * 会话已冻结（已登记 projectRoot）时 rebind 不覆盖；{@link #clearSession(String)} 解除冻结后可再绑定。
 *
 * <p><b>生产接线</b>（IMP-B 闭环 ODF-A1 §8 阻塞项）：ProjectSessionBindingService.bind() →
 * {@link #setForSession(String, String)}（session.mainProjectId → ProjectRecord.path）；
 * unbind() → {@link #clearSession(String)}。
 *
 * <h2>R-DB · 会话 projectRoot DB 查询<b>已收口为一份</b>（[S2 · F-24] Step 1/2/3，2026-09-14）</h2>
 * <p>同一能力（sessionId → {@code sessions.main_project_id} → {@code projects.path}）此前有
 * <b>两条独立实现</b>，现只剩<b>一条</b>：
 * <ol>
 *   <li>✅ <b>本类的冻结表回源解析器（唯一留存）</b> —— 实现体 =
 *       {@code ToolRegistrationConfig#sessionProjectRootResolver}（{@code @Bean}，启动期经
 *       {@link #setDbResolver} 注入）。</li>
 *   <li>⛔ <b>B′ 兜底第二链已删除</b>（{@code LlmAgentLoop.tryResolveBoundProjectFromDb}，
 *       原 run() 入口 5 处调用；用户 2026-09-14 裁定「删，合并成一个」）。5 个调用点改走
 *       {@link #lookup(String)} 的封装出口（{@code LlmAgentLoop.applyBoundProjectFromLookup}）。</li>
 * </ol>
 *
 * <p><b>✅ 已消除的差异（Step 1 + Step 2 + Step 3，2026-09-14）</b>：
 * <ul>
 *   <li><b>差异 A（判据形态）已消除</b>：原「两份有效性判据拷贝」—— 现
 *       {@link #isValidProjectRoot} 为<b>唯一实现</b>（public），{@code CwdResolution.isValidDirectory}
 *       改为<b>委托</b>（application→common 既有方向，不成环）。由
 *       {@code SessionProjectRootValidityParityTest} 的相同断言反向守住（含 Path.of 抛
 *       InvalidPathException 的异常分支）。⚠️ 原残留「B′ 链只有 {@code boolean}，不区分
 *       unbound/unknown」<b>已随 Step 3 消除</b>（B′ 链本体删除，失败语义只剩本类的四态
 *       {@link Lookup}）。</li>
 *   <li><b>差异 B（归一化落点）已消除</b>：原「本类冻结 DB 原值（不 realpath/不 NFC）vs B′ 链冻结
 *       realpath+NFC」⇒ 实测可观测且会错（symlink ⇒ {@code projects/<slug>} 落不同目录；non-NFC ⇒
 *       slug 不同且 B′ 链直接判项目无效；首写胜 ⇒ 谁先冻结决定 slug）。现归一化责任<b>由回源器承担</b>
 *       （见 {@link DbResolver} 契约要求），且 Step 3 后回源器是<b>唯一</b>冻结来源。由
 *       {@code ProjectRootNormalizationDivergenceExperimentTest} 的<b>相等断言</b>守住
 *       （去掉回源器里的 {@code normalizeCwd} ⇒ 该断言翻红）。</li>
 *   <li><b>差异 C（两链并存本体）已消除（Step 3）</b>：原「同一份『查 session → main_project_id →
 *       projects.path』查询逻辑写了两遍 + B′ 链失败语义与本类四态不同（{@code boolean} vs
 *       {@link Lookup}）」⇒ 现全仓<b>只有本类回源器一处实现</b>；{@code LlmAgentLoop} 的 5 个失败
 *       出口统一经 {@link #lookup(String)}（memory 域保持 fail-soft：{@code projectRoot()==null ⇒
 *       保持无项目}，⛔ 不抛）。</li>
 * </ul>
 *
 * <p><b>⚠️ Step 3 收口的唯一行为差（有意 · 有读数）</b>：B′ 链自带 DB 直查 ⇒ 回源器<b>不可用</b>
 * （未接线 / 违约返回 null）时它仍能取到绑定；合并后唯一链取不到 ⇒ memory 域保持无项目
 * （fail-soft，⛔ 不抛）。生产里二者同源（{@code sessionProjectRootResolver} 的 {@code @Bean} 体
 * 就是 {@link #setDbResolver} 的注册者）⇒ 该差只在「装配异常 / 夹具分叉」形态可见。
 * 逐格读数与判据见 {@code BoundProjectResolutionMatrixTest}。
 * <p>另：Step 3 顺带修掉 B′ 链的一个<b>静默失效</b> —— 陈旧冻结条目（冻结后目录被删）下
 * B′ 查到了新鲜值却因 {@link #setForSession} <b>首写胜</b>写不进冻结表（无效值继续被
 * {@code getForSession} 返回给 memory 域）；收口后调用点 1 先 {@link #clearSession(String)}
 * 解除陈旧冻结再回源，冻结表终态 = 回源值（或无条目）。
 */
public final class SessionProjectRoot {

    private static final Logger log = LoggerFactory.getLogger(SessionProjectRoot.class);

    /** sessionId → projectRoot（会话绑定登记 · CC state.ts:269-279 启动冻结语义）。 */
    private static final ConcurrentHashMap<String, String> BY_SESSION = new ConcurrentHashMap<>();

    /**
     * 会话绑定查询结果 · <b>四态</b>（[批 4a] 用户裁定 #7/#13 的判据载体 + [S2 F-09/F-20] 新增解析失败态）：
     * <ul>
     *   <li>{@link #bound(String)} —— 会话有绑定项目根（且回源校验通过）</li>
     *   <li>{@link #unbound()} —— <b>会话存在</b>（DB 有行）但无绑定项目根 / 绑定失效
     *       ⇒ <b>「数据链路异常」</b>，cwd 域 fail-loud（web 会话必须绑定项目才能进行）</li>
     *   <li>{@link #unknown()} —— <b>无此会话</b>（合成 / 伪造 / 已删 id：MCP 入站调用、standalone
     *       fork / subagent、文档更新器等现造 id）⇒ 属「确无会话」，cwd 域走命名无会话出口
     *       （对齐批 4a 前 user.dir 行为，⛔ 不得对它 fail-loud：那会打死合成 id 的合法路径）</li>
     *   <li>{@link #resolutionFailure()} —— <b>无法判定</b>（[S2 F-09/F-20 2026-09-14 · 用户裁定 (A)]）：
     *       回源解析器<b>未接线</b>（{@link #setDbResolver} 未被调用 = 装配异常）或回源查询<b>抛错 /
     *       违约返回 null</b>。⛔ 与 {@link #unknown()} 严格区分：这一态<b>不是</b>「确无会话」，
     *       cwd 域必须 fail-loud（原实现把它静默投给 unknown ⇒ 用户裁定 #7 的 fail-loud 会因装配
     *       异常而全进程静默失效且零日志 —— 正是本批要治的失败模式）。</li>
     * </ul>
     *
     * <p>⚠️ {@code resolutionFailed} 必须是<b>独立字段</b>：三态记录（{@code projectRoot},
     * {@code sessionKnown}）下「解析失败」只能是 {@code (null, false)}，与 {@code unknown()} 逐字段
     * 相同 ⇒ 无法区分。故本 record 保持 {@code projectRoot}/{@code sessionKnown} 两个既有访问器
     * （兼容既有消费点）+ 新增第三字段。
     */
    public record Lookup(String projectRoot, boolean sessionKnown, boolean resolutionFailed) {
        /** 绑定命中。 */
        public static Lookup bound(String projectRoot) {
            return new Lookup(projectRoot, true, false);
        }

        /** 会话存在但无绑定（数据链路异常判据）。 */
        public static Lookup unbound() {
            return new Lookup(null, true, false);
        }

        /** 无此会话（确无会话判据）。 */
        public static Lookup unknown() {
            return new Lookup(null, false, false);
        }

        /**
         * 解析失败 / 无法判定（[S2 F-09/F-20] 新增第 4 态）· ⛔ 绝不与 {@link #unbound()} /
         * {@link #unknown()} 混同（访问器 {@link #resolutionFailed()} 为 {@code true} 是唯一可辨识标记）。
         *
         * <p>⚠️ 命名说明：工厂方法名<b>不能</b>叫 {@code resolutionFailed()} —— 会与 record 组件
         * 自动生成的同名访问器冲突（javac：records 中存取方法无效）。故工厂为 {@code resolutionFailure()}，
         * 访问器保持组件名 {@code resolutionFailed()}。
         */
        public static Lookup resolutionFailure() {
            return new Lookup(null, false, true);
        }
    }

    /**
     * DB 回源解析器 · 冻结表 cache miss 时的<b>回源</b>通道（用户裁定 #9：miss ⇒ 回查 DB 并回填）。
     *
     * <p>⛔ 生产不得在 {@code common} 层内直连 mapper：本类是无依赖静态载体，DB 访问必须经
     * {@link #setDbResolver} 显式注入。
     */
    @FunctionalInterface
    public interface DbResolver {
        /**
         * 回源查会话绑定（四态见 {@link Lookup}）；不得回落到 config home / env / user.dir。
         *
         * <p>⭐ <b>[S2 · F-24-merge Step 2 2026-09-14] 契约要求：{@link Lookup#bound(String)} 携带的
         * {@code projectRoot} <b>必须已 realpath + NFC 归一</b></b>。
         *
         * <p><b>WHY（实测，勿删）</b>：本类只做「查询 / 有效性（{@link #isValidProjectRoot}）/
         * 冻结」，<b>不做归一化</b> —— 归一是 {@code application} 层
         * {@code CwdResolution.normalizeCwd} 的职责，而 {@code common} 不能反向依赖
         * {@code application}（会成包依赖循环）。故归一化责任<b>由回源器承担</b>。
         *
         * <p>⛔ <b>违反本契约的后果（且是静默的）</b>：本类会冻结<b>未归一</b>值，而消费侧会把同一
         * DB 值另行归一 —— 两边产出不同字符串，实测可观测且会错：
         * <ul>
         *   <li>斜杠方向（Windows）：字符串/缓存键差异；</li>
         *   <li>symlink/junction：{@code AutoMemPaths.sanitizePath} 派生出的 {@code projects/<slug>}
         *       落到<b>不同目录</b>；</li>
         *   <li>non-NFC 名：slug 不同，且校验侧（{@code normalizeCwd} 后判目录存在）会直接判
         *       「项目无效」⇒ 同一 DB 行给出<b>相反结论</b>。</li>
         * </ul>
         * 另：{@code setForSession} 是<b>首写胜</b> ⇒ 谁先冻结决定终态 ⇒ slug 不确定。
         * <p>[F-24 Step 3 后] 本契约成为<b>唯一</b>归一化点的落点（B′ 链已删 ⇒ 无第二条写入本类的
         * 路径）。取证装置 = {@code ProjectRootNormalizationDivergenceExperimentTest}
         * （断言「回源器冻结值 = realpath 归一值」，去掉回源器里的 {@code normalizeCwd} 即翻红）。
         */
        Lookup resolve(String sessionId);
    }

    /** 回源解析器（volatile：启动期注册一次，测试可注销）。 */
    private static volatile DbResolver dbResolver;

    /** [S2 F-09/F-20] {@code lookup(null)} 的告警一次性开关（避免 130 个调用点刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NULL_SESSION_LOOKUP_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 注册 DB 回源解析器（生产启动期一次；{@code null} = 注销 —— 测试清理用）。
     *
     * <p><b>生产接线</b> = {@code ToolRegistrationConfig#sessionProjectRootResolver}
     * （全仓唯一 DB 查询实现：sessionId → {@code sessions.main_project_id → projects.path}）。
     * 未接线（纯 JUnit / 非 Spring 直构）→ {@link #lookup} 退化为纯内存查表 + unknown 判据，
     * 行为与改造前一致（夹具不需要 DB，也不会误触 fail-loud）。
     */
    public static void setDbResolver(DbResolver resolver) {
        dbResolver = resolver;
    }

    /**
     * 回源解析器是否已接线（[S2 F-20 2026-09-14]）· 供 Spring 上下文就绪断言与测试使用。
     *
     * <p><b>WHY</b>：{@code setDbResolver} 是{@code @Bean sessionProjectRootResolver} 方法体内的
     * 副作用（{@code ToolRegistrationConfig:1227}）。若装配顺序出问题导致它从未执行，则
     * {@link #lookup} 的每一次 miss 都走 {@link Lookup#resolutionFailed()} ⇒ cwd 域全量 fail-loud。
     * 该状态必须<b>可被断言</b>，不能只靠日志发现。
     */
    public static boolean isDbResolverWired() {
        return dbResolver != null;
    }

    private SessionProjectRoot() {}

    /**
     * 会话绑定 projectRoot · 首写胜（对齐 CC stable identity · OPD-SPR-03）：会话已冻结时不覆盖，
     * rebind 尝试仅记 debug；clearSession 解除冻结后可再绑定。
     */
    public static void setForSession(String sessionId, String projectRoot) {
        if (sessionId == null || projectRoot == null || projectRoot.isEmpty()) {
            return;
        }
        // [2026-08-24 cwd 污染修复] 绑定路径校验：绝对路径 + 目录存在，无效拒绝（不绑定污染 cwd——
        //   否则 CwdResolution 返回无效路径致工具全失败）
        if (!isValidProjectRoot(projectRoot)) {
            log.warn("[SessionProjectRoot] 拒绝绑定无效项目根（需绝对路径且目录存在）: sessionId={} "
                + "projectRoot={}", sessionId, projectRoot);
            return;
        }
        String prev = BY_SESSION.putIfAbsent(sessionId, projectRoot);
        if (prev == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionProjectRoot] 会话绑定 projectRoot: sessionId={} projectRoot={}", sessionId, projectRoot);
            }
        } else if (log.isDebugEnabled() && !prev.equals(projectRoot)) {
            log.debug("[SessionProjectRoot] 会话已冻结不覆盖（CC stable identity · OPD-SPR-03）: "
                + "sessionId={} frozen={} attempt={}", sessionId, prev, projectRoot);
        }
    }

    /**
     * 项目根有效性校验 · <b>全仓唯一实现</b>（[S2 · F-24-merge Step 1 2026-09-14] 差异 A 消除）。
     *
     * <p>[2026-08-24 cwd 污染修复] 绝对路径 + 目录存在；相对/不存在路径（如「抓包流程」）拒绝绑定，
     * 防 CwdResolution 返回无效 cwd 致工具全失败。
     *
     * <p><b>[S2 Step 1] private → public + 成为单一实现</b>：{@code CwdResolution.isValidDirectory}
     * （application 层）现<b>委托</b>本方法（application→common 是既有依赖方向，不成环；反向委托会
     * 形成包依赖循环，故只有这一条路）。⇒ 「同一能力两套判据」在本能力上被消除。
     * 由 {@code SessionProjectRootValidityParityTest} 以相同断言反向守住：⛔ 改本方法即等于改两处，
     * 不得再出现第二份拷贝。
     *
     * @param projectRoot 候选项目根（可 null/空白）
     * @return true = 绝对路径且指向存在的目录
     */
    public static boolean isValidProjectRoot(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(projectRoot);
            return p.isAbsolute() && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取会话绑定 projectRoot（null = 未冻结 · OPD-SPR-03 允许未冻结查询）。
     *
     * <p><b>miss ⇒ 回源 DB 并回填</b>（用户裁定 #9）：内存冻结表未命中 → {@link DbResolver} 回查
     * DB → 命中则 {@link #setForSession(String, String)} 回填后返回。DB 未给出绑定 → null
     * （既含「有会话但未绑定」也含「无此会话」，故绝不回落 config home / env / user.dir）。
     * <p>本方法把「有会话但未绑定」/「无此会话」/「解析失败」三种失败态**都压成 null**（历史兼容：
     * memory 域按「保持无项目」处理，不抛）；需要区分这三态（cwd 域的 fail-loud 判据）请用
     * {@link #lookup(String)} —— 尤其 {@link Lookup#resolutionFailed()}（[S2 F-09/F-20]）在
     * {@code getForSession} 上与「无此会话」不可分辨。
     *
     * <p>注：未绑定的会话（DB 无 {@code main_project_id}）每次读都会回源一次 DB ——
     * 不设负缓存，避免「先读 miss → 后 bind」被负缓存钉死（PK 查询，代价可忽略）。
     */
    public static String getForSession(String sessionId) {
        return lookup(sessionId).projectRoot();
    }

    /**
     * 多态查询（[批 4a] + [S2 F-09/F-20] 第 4 态）· {@link Lookup}：绑定 / 有会话但未绑定 /
     * 无此会话 / 解析失败。
     *
     * <p>内存冻结表 miss ⇒ 回源 DB 并回填（用户裁定 #9 · Redis miss → 回源 → 回填）。
     * cwd 域用 {@code sessionKnown()} 区分「数据链路异常（fail-loud）」与「确无会话（无会话出口）」，
     * 并用 {@code resolutionFailed()} 区分「无法判定（fail-loud + 纠错文案）」。
     */
    public static Lookup lookup(String sessionId) {
        if (sessionId == null) {
            // [S2 F-09/F-20 · 铁律「不许静默失效」] 原实现零日志返回 unknown()（= 静默把「漏传」当成
            //   「确无会话」）。null sessionId 无法判定 ⇒ 打一次性 ≥WARN（沿用本仓 AtomicBoolean 惯例，
            //   避免 130 个调用点刷屏）。⚠️ 不改返回值为 resolutionFailed()：那会让 CwdResolution 侧
            //   的 null 路由（getCwd(null) ⇒ 无会话出口）从「兼容路由」变成 fail-loud，违反批 4a
            //   裁定 #13 的零行为变化承诺；此处只做可观测性补强。
            if (NULL_SESSION_LOOKUP_WARNED.compareAndSet(false, true)) {
                log.warn("[SessionProjectRoot] lookup/getForSession 收到 null sessionId ⇒ 无法判定会话"
                    + "项目根，按「无此会话」返回（⛔ 此处本该有会话 ⇒ 属漏传，请修调用方；本告警只打印一次）");
            }
            return Lookup.unknown();
        }
        String cached = BY_SESSION.get(sessionId);
        if (cached != null) {
            return Lookup.bound(cached);
        }
        return refillFromDb(sessionId);
    }

    /**
     * 回源 DB + 回填（Redis miss → 回源 → 回填语义）。
     *
     * <p>回源值无效（非绝对路径 / 目录不存在 / 空白）⇒ <b>不回填</b>；若 DB 显示会话存在 ⇒
     * 「有会话但绑定失效」= 数据链路异常（{@link Lookup#unbound()}）。判据 = 唯一实现
     * {@link #isValidProjectRoot}（无效绑定目录不冒充项目根，否则 memory 域会按无效路径建目录
     * = 跨项目污染）。
     *
     * <p><b>冻结值 = 回源器给出的已归一值</b>（[S2 · F-24-merge Step 2 2026-09-14]）：本方法
     * <b>不做</b>归一化 —— 归一是 application 层 {@code CwdResolution.normalizeCwd} 的职责，本类在
     * {@code common} 不能反向依赖 application（包依赖循环）。故归一化责任由 {@link DbResolver}
     * 契约承担（见该接口 javadoc 的「契约要求」）。⛔ 原注释「冻结的是未归一值」已随 Step 2 变更。
     * 旧称的两个差异<b>均已消除</b>：
     * <ul>
     *   <li><b>差异 A 已消除</b>：{@link #isValidProjectRoot} 提为 public 成为唯一实现，
     *       {@code CwdResolution.isValidDirectory} 改为委托（由
     *       {@code SessionProjectRootValidityParityTest} 反向守住）。</li>
     *   <li><b>差异 B 已消除</b>：回源器（{@code ToolRegistrationConfig#sessionProjectRootResolver}）
     *       返回 {@code bound} 前统一 {@code normalizeCwd} + 用同一判据校验 ⇒ 冻结值恒为归一值
     *       （由 {@code ProjectRootNormalizationDivergenceExperimentTest} 的相等断言守住）。</li>
     *   <li><b>差异 C 已消除（Step 3）</b>：B′ 兜底第二链本体已删 ⇒ 本方法 + 回源器是<b>唯一</b>
     *       「sessionId → main_project_id → projects.path」实现（见类 javadoc 的 R-DB 段）。</li>
     * </ul>
     *
     * <p><b>[S2 · F-09/F-20 2026-09-14 · 用户裁定 (A)] 三类「无法判定」不再静默投给「确无会话」</b>：
     * <ol>
     *   <li>回源解析器<b>未接线</b>（原 {@code return Lookup.unknown()} <b>零日志</b>）⇒ ≥WARN +
     *       {@link Lookup#resolutionFailed()}</li>
     *   <li>回源查询<b>抛错</b>（原 catch 后 {@code return Lookup.unknown()}）⇒ ≥WARN +
     *       {@link Lookup#resolutionFailed()}</li>
     *   <li>回源器<b>违约返回 null</b>（{@link DbResolver} 契约要求返回三态 {@link Lookup}）⇒ ≥WARN +
     *       {@link Lookup#resolutionFailed()}</li>
     * </ol>
     * ⛔ 三者均<b>不得</b>回落 config home / env / user.dir，也不得冒充「无此会话」——「本该如何却没有」
     * 必须让 cwd 域 fail-loud 并留下可检索的 WARN。
     */
    private static Lookup refillFromDb(String sessionId) {
        DbResolver resolver = dbResolver;
        if (resolver == null) {
            // [S2 F-20] 未接线 = 装配异常（无法判定），⛔ 不是「确无会话」（原实现零日志静默投 unknown）
            log.warn("[SessionProjectRoot] DB 回源解析器未接线（setDbResolver 未被调用）⇒ 无法判定会话"
                + "项目根，按「解析失败」处理（⛔ 不得当作『确无会话』而回落进程 user.dir）: sessionId={}",
                sessionId);
            return Lookup.resolutionFailure();
        }
        Lookup fromDb;
        try {
            fromDb = resolver.resolve(sessionId);
        } catch (Exception e) {
            // [S2 F-09] DB 回源抛错 = 无法判定（fail-loud 依据），⛔ 不再与「确无会话」混同
            log.warn("[SessionProjectRoot] 会话 projectRoot 回源 DB 抛错 ⇒ 无法判定，按「解析失败」处理"
                + "（⛔ 不得当作『确无会话』）: sessionId={} err={}", sessionId, e.toString());
            return Lookup.resolutionFailure();
        }
        if (fromDb == null) {
            // [S2 F-09] 违约返回 null = 解析器实现缺陷（无法判定），⛔ 不是「无此会话」
            log.warn("[SessionProjectRoot] DB 回源解析器返回 null（违反 DbResolver 契约：须返回三态 "
                + "Lookup）⇒ 无法判定，按「解析失败」处理: sessionId={}", sessionId);
            return Lookup.resolutionFailure();
        }
        if (fromDb.resolutionFailed()) {
            // 解析器自身已判定「无法判定」⇒ 原样上浮（保留其可辨识语义，不降级成 unknown）
            log.warn("[SessionProjectRoot] DB 回源解析器自行判定「解析失败」⇒ 原样上浮: sessionId={}",
                sessionId);
            return fromDb;
        }
        String path = fromDb.projectRoot();
        if (path == null || path.isBlank()) {
            return fromDb.sessionKnown() ? Lookup.unbound() : Lookup.unknown();
        }
        if (!isValidProjectRoot(path)) {
            log.warn("[SessionProjectRoot] 回源 DB 得到无效项目根（需绝对路径且目录存在），按「有会话但绑定"
                + "失效」处理（不回填）: sessionId={} projectRoot={}", sessionId, path);
            return Lookup.unbound();
        }
        setForSession(sessionId, path);
        // 回读缓存作为唯一来源：并发下可能已被他线程回填（首写胜），返回缓存值保证「返回 == 缓存」。
        String cached = BY_SESSION.get(sessionId);
        String resolved = cached != null ? cached : path;
        log.info("[SessionProjectRoot] 冻结表 miss → 回源 DB 并回填: sessionId={} projectRoot={}",
            sessionId, resolved);
        return Lookup.bound(resolved);
    }

    /** 清除会话绑定（会话销毁/解绑项目时调用）。 */
    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            BY_SESSION.remove(sessionId);
        }
    }

    /** 测试钩子：清空全部会话登记（[TL-W2 P11] 原含 CURRENT ThreadLocal 清理，已随死代码删除）。
     *  <p>不回源解析器本身 —— 注册过 {@link #setDbResolver} 的测试须自行注销（否则跨用例污染）。
     *  <p>[S2 F-09/F-20] 一并复位一次性告警闸，避免「先跑的用例把闸用掉 ⇒ 后跑的用例捕不到 WARN」
     *  造成测试顺序依赖（本仓已登记该类假绿风险）。 */
    public static void reset() {
        BY_SESSION.clear();
        NULL_SESSION_LOOKUP_WARNED.set(false);
    }
}
