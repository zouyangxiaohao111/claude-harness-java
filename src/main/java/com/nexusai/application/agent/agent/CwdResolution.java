package com.nexusai.application.agent.agent;

import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.function.Supplier;

/**
 * 工作目录域统一入口 · 对齐 CC {@code utils/cwd.ts} {@code pwd()}/{@code getCwd()}
 * 与 {@code bootstrap/state.ts} 四层 STATE 语义。
 *
 * <p><b>CC 真源（自验，不信注释）</b>：
 * <ul>
 *   <li>{@code cwd.ts:19-21} {@code pwd() = cwdOverrideStorage.getStore() ?? getCwdState()}（override ?? STATE.cwd）</li>
 *   <li>{@code cwd.ts:26-32} {@code getCwd() = try pwd() catch → getOriginalCwd()}（失败回 originalCwd）</li>
 *   <li>{@code cwd.ts:12-14} {@code runWithCwdOverride(cwd, fn)} 用 AsyncLocalStorage 在异步上下文覆盖
 *       cwd，并发 agent 各自隔离</li>
 *   <li>{@code state.ts:500-502} {@code getOriginalCwd()} 返回 {@code STATE.originalCwd}
 *       （启动=realpath cwd，随 worktree/resume 重锚）</li>
 *   <li>{@code state.ts:527-533} {@code STATE.cwd} 单一可变，{@code setCwdState} 做 NFC 归一化</li>
 * </ul>
 *
 * <p><b>分层解析</b>（{@link #getCwd(String)}，对齐 CC pwd/getCwd 三层）：
 * <pre>
 * getCwd(sessionId)  // sessionId 必填（null/空白 ⇒ 走 getCwdForNonSession()，见下）
 *   0. [批 6] sessionId == SessionKeys.NO_SESSION（显式「确无会话」哨兵）⇒ 直接走命名出口
 *      getCwdForNonSession() + ≥WARN（不查 DB、不打「伪造 id」告警）
 *   1. override = ThreadLocal CURRENT_OVERRIDE（对齐 CC cwdOverrideStorage AsyncLocalStorage；
 *      runWithCwdOverride 设置，退出 clear）→ 非空返回 normalizeCwd(override)
 *   2. sessionCwd = SessionCwdHolder.get(sessionId)（对齐 CC 单一 STATE.cwd；
 *      worktree 入口与 bash cd 共用此层，后者覆盖前者 [Fix-R1]）→ 非空返回 normalizeCwd
 *   3. boundProject = SessionProjectRoot.getForSession(sessionId)（D-1 裁决：仅绑定，
 *      不读 resolve() 回落链——身份域红线；miss 时该方法自身回源 DB 并回填）→ 非空返回 normalizeCwd
 *   4. 三层全 MISS ⇒ 按 <b>DB 是否认得该会话</b> 分流（[批 4a] 用户裁定 #7/#13 的判据载体）：
 *      <ul>
 *        <li><b>会话存在（DB 有行）但无绑定项目根</b> ⇒ <b>抛 IllegalStateException（fail-loud）</b>
 *            —— 数据链路异常，⛔ 不得回落进程 user.dir 冒充会话项目根</li>
 *        <li><b>DB 无此会话</b>（合成/伪造/已删 id：MCP 入站调用 / standalone fork / subagent /
 *            文档更新器现造 id）⇒ 属「确无会话」⇒ 走命名出口 {@link #getCwdForNonSession()}
 *            （≥WARN 留痕）。⛔ 不得对它 fail-loud：那会打死合成 id 的合法路径（实测：5 处生产
 *            合成 id 生产点 + 100+ 测试类）</li>
 *      </ul>
 * </pre>
 *
 * <p><b>[批 4a 2026-09-14 · 用户裁定 #7/#13] 删除 user.dir 兜底 + 新增显式无会话出口</b>：
 * <ul>
 *   <li>{@link #getCwdForNonSession()} / {@link #getOriginalCwdLayerForNonSession()} ——
 *       <b>命名自解释</b>的无会话出口，<b>只对「确无会话」开放</b>（启动期 bean / MCP transport /
 *       进程级默认 supplier 等确实拿不到 sessionId 的场景）。</li>
 *   <li>旧的 {@code getCwd(null)} / {@code getOriginalCwdLayer(null)} 仍按无会话解析（<b>行为零变化</b>
 *       —— 130 个既有调用点不受影响），但打一条 ≥WARN 留痕并指向命名出口（仅打印一次）。</li>
 * </ul>
 *
 * <p><b>与 CC 的有意偏离（用户已裁定知悉）</b>：CC 在「会话缺失时项目根」用<b>回落</b>
 * （{@code sessionStorage.ts:203-205 getSessionProjectDir() ?? getProjectDir(getOriginalCwd())}）；
 * 用户裁定的理由是「web 会话<b>必须</b>绑定项目才能进行，没有就代表数据链路异常」。
 * ⛔ 不得因「对齐 CC」把「会话存在却无项目根」的 fail-loud 改回回落。
 * 但「DB 无此会话」仍走无会话出口 —— 原因是实测（批 4a）：合成 sessionId 的生产路径多且合法
 * （MCP 入站 / standalone fork/subagent / 文档更新器），一刀切 fail-loud 会把它们打死。
 *
 * <p><b>[CRON-D5 F2 返工] 双键解析</b>：第 2/3 层（sessionCwd/boundProject）的 Map 键形态不同——
 * sessionCwd 层以派生 UUID 串为键（BashTool/EnterWorktreeTool 经 {@code ctx.sessionId()}），
 * boundProject 层以原始键 {@code "sess-xxx"} 为键（bind / resolveSessionProjectRoot 经 streamSessionId）。
 * 传入 sessionId 可能是任一形态（cron 后台线程透传派生 UUID / REST 入口显式传入的原始键），每层先试原键、
 * MISS 再试另一形态（{@link #alternateKeyOf}，严格超集仅补缺失路径，两形态键域不重叠无错配）。
 *
 * <p><b>[Fix-R1] 合并存储</b>：worktree 入口（{@code EnterWorktreeTool.ts:95 setCwd}）与 bash
 * {@code cd}（{@code Shell.ts:407 setCwd}）在 CC 端<b>均写同一 {@code STATE.cwd}</b>，后者覆盖前者
 * ——cd 后 getCwd() 返回 cd 子目录。Java 端二者均写 {@link SessionCwdHolder}（sessionCwd 层），
 * {@code WorktreeCwdTracker} 仅记录 worktree 基路径供退出恢复，<b>不作</b> getCwd 优先层。否则活跃
 * worktree 内 cd 后 getCwd() 会返回 worktree 基路径而非 cd 子目录，违反 INV-2。
 *
 * <p><b>getOriginalCwdLayer</b>（{@link #getOriginalCwdLayer(String)}，对齐 CC getOriginalCwd）：
 * <ol>
 *   <li>{@code SessionCwdHolder.getOriginalCwd(sessionId)}（<b>[INV-3]</b> worktree 入口重锚层，对齐 CC
 *       {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())} 写 {@code STATE.originalCwd}=worktreePath；
 *       Exit clear 回落下一层，对齐 CC {@code ExitWorktreeTool.ts:129 setOriginalCwd(originalCwd)} 恢复）→ 非空返回 normalizeCwd</li>
 *   <li>{@code SessionProjectRoot.getForSession(sessionId)}（boundProject，启动锚）→ 非空返回 normalizeCwd</li>
 *   <li>{@code user.dir} 兜底</li>
 * </ol>
 * <b>D-1 裁决：不读 resolve()</b>（resolve() 回落 CLAUDE_PROJECT_DIR env / config home 属身份域，会使 user.dir
 * 成死代码且身份域泄入工作目录域）。worktree 重锚由 {@link SessionCwdHolder#getOriginalCwd} 独立槽承裁
 * （非 cwd 槽 {@code resolve()}），与 cwd 槽（{@link SessionCwdHolder#get}）双独立，对齐 CC
 * {@code STATE.cwd}/{@code STATE.originalCwd} 双字段。
 *
 * <p><b>归一化</b>（{@link #normalizeCwd(String)}，对齐 CC setCwdState NFC + Shell.ts setCwd realpathSync）：
 * realpath 解符号链接 + NFC 归一化；realpath 失败（目录被删/不存在）回原值 + NFC（不抛，对齐 CC catch 兜底）。
 *
 * <p><b>失败语义</b>（[批 4a] 由「恒非 null」改为 fail-loud）：{@link #getCwd(String)} 各层 safeGet
 * （层内异常回 null）逐层回落；<b>DB 认得该会话却三层全 MISS（含绑定失效）⇒ 抛</b>（不再回落 user.dir）；
 * <b>DB 无此会话</b>（合成/伪造 id）⇒ 无会话出口。只有 {@link #getCwdForNonSession()} 恒非 null
 * （override ?? 进程 user.dir）。
 *
 * <p><b>OD-1 决策</b>：{@code CwdOverride}（0 生产调用）已<b>合并入本类并删除</b>，无别名/双轨。
 *
 * <p>线程安全：{@link SessionCwdHolder} / {@link SessionProjectRoot} 内部 ConcurrentHashMap；
 * override 层 ThreadLocal（同 JVM 多线程=多 agent 各自隔离，对齐 CC AsyncLocalStorage 跨 async 边界语义）。
 */
public final class CwdResolution {

    private static final Logger log = LoggerFactory.getLogger(CwdResolution.class);

    /**
     * 当前线程显式 cwd override（对齐 CC {@code cwdOverrideStorage} AsyncLocalStorage · cwd.ts:4）。
     * 由 {@link #runWithCwdOverride(String, Supplier)} / {@link #setCurrentOverride(String)} 设置，
     * 退出 {@link #clearCurrentOverride()} 清除。子代理若跨线程需入口 set/finally clear。
     */
    private static final ThreadLocal<String> CURRENT_OVERRIDE = new ThreadLocal<>();

    /** null/空白 sessionId 的告警一次性开关（warnNullSession，见该方法 javadoc）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NULL_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 「DB 无此会话」路径的告警一次性开关（warnUnknownSession，见该方法 javadoc）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean UNKNOWN_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** [批 6] 显式「确无会话」哨兵路径的告警一次性开关（warnNoSessionSentinel）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NO_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private CwdResolution() {}

    /**
     * 统一入口：解析 sessionId 对应的当前工作目录（对齐 CC pwd/getCwd）。
     *
     * <p>三层回落；各层 safeGet 异常回 null；<b>全 MISS ⇒ 抛</b>（无 user.dir 兜底，见类注释）。
     *
     * <p><b>[CRON-D5 F2 返工] 双键解析</b>：sessionCwd/SessionCwdHolder 层以派生 UUID 串为键
     * （BashTool/EnterWorktreeTool 以 {@code ctx.sessionId()} 登记），boundProject/
     * SessionProjectRoot 层以原始会话键 {@code "sess-xxx"} 为键（bind / resolveSessionProjectRoot 以
     * streamSessionId 登记）。传入的 sessionId 可能是任一形态（cron 后台线程经 QueueItem 透传派生 UUID；
     * REST 入口显式传入的原始键），故每层先试原键、MISS 再试另一形态（{@link #alternateKeyOf}，严格超集，
     * 仅补缺失解析路径，两形态键域不重叠无错配）。
     *
     * @param sessionId 会话 ID（必填 —— null/空白 ⇒ 交由 {@link #getCwdForNonSession()} 按无会话解析）
     * @return 归一化 cwd；<b>DB 认得该会话却解析不出项目根 ⇒ 抛 IllegalStateException</b>；
     *         DB 无此会话 ⇒ 无会话出口值（进程 user.dir）
     * @throws IllegalStateException 会话存在（DB 有行）但 override/sessionCwd/boundProject（含 DB 回源）
     *         全 MISS，或 boundProject 无效（非绝对路径/目录不存在）—— 数据链路异常，fail-loud
     */
    public static String getCwd(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            warnNullSession("getCwd", "getCwdForNonSession");
            return getCwdForNonSession();
        }
        // [批 6] 显式「确无会话」哨兵（SessionKeys.NO_SESSION）⇒ 直接走命名出口：
        //   ⛔ 不落进下方「DB 查无此会话（合成/伪造/已删 id）」的 unknown 分支 —— 那条会打
        //   「伪造 sessionId」告警并多一次 DB 查询，而本值恰恰是**有意声明无会话**，非伪造。
        if (SessionKeys.isNoSession(sessionId)) {
            warnNoSessionSentinel("getCwd", "getCwdForNonSession");
            return getCwdForNonSession();
        }
        // L1: override（对齐 CC cwdOverrideStorage.getStore()）
        String override = safeGet(() -> CURRENT_OVERRIDE.get());
        if (override != null && !override.isBlank()) {
            return normalizeCwd(override);
        }
        // L2: sessionCwd（对齐 CC 单一 STATE.cwd · worktree 入口与 cd 共用 [Fix-R1] · F2 双键）
        String sessionCwd = resolveSessionCwd(sessionId);
        if (sessionCwd != null) {
            return normalizeCwd(sessionCwd);
        }
        // L3: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键）
        //     getForSession/lookup 内部 miss 时回源 DB 并回填（批 4a #9）⇒ 后端重启后首条消息前也能命中。
        SessionProjectRoot.Lookup bound = resolveBoundProject(sessionId);
        String boundProject = bound.projectRoot();
        if (boundProject != null) {
            if (isValidDirectory(boundProject)) {
                return normalizeCwd(boundProject);
            }
            // [2026-08-24 cwd 污染修复] boundProject 无效（相对/不存在/非目录，如绑定「抓包流程」）→
            //   返回会污染工具 cwd 致 Bash/Glob/Read 全失败。[批 4a #7/#13] 不再回落 user.dir
            //   （那是「有会话却解析不出」= 数据链路异常），fail-loud。
            throw unresolvedProjectRoot(sessionId,
                "boundProject 无效（需绝对路径且目录存在）: " + boundProject);
        }
        if (bound.sessionKnown()) {
            // [批 4a #7/#13 用户裁定] 「有会话却解析不出项目根」= 数据链路异常 ⇒ fail-loud。
            //   判据 = DB 确认该会话存在（sessions 有行）⇒ 排除「合成/伪造 id」（那类走下面的
            //   无会话出口，⛔ 不得 fail-loud —— 会把 MCP 入站 / standalone 子代理/ fork 打死）。
            throw unresolvedProjectRoot(sessionId,
                "会话存在但无绑定项目根（sessions.main_project_id 为空 / projects.path 失效）");
        }
        // 无此会话（合成/伪造/已删 id）⇒ 确无会话 ⇒ 命名出口（行为与批 4a 前一致：进程 user.dir）
        warnUnknownSession("getCwd", sessionId);
        return getCwdForNonSession();
    }

    /**
     * 原始工作目录层（对齐 CC {@code getOriginalCwd()} state.ts:500-502）。
     *
     * <p>分层回落（对齐 CC {@code STATE.originalCwd} 随 worktree/resume 重锚语义）：
     * <ol>
     *   <li><b>[INV-3]</b> {@link SessionCwdHolder#getOriginalCwd(String)}——worktree 入口重锚层
     *       （对齐 CC {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}=worktreePath；
     *       使 worktree 会话内 CLAUDE.md 扫描/存档锚走 worktreePath 非 boundProject）。非空返回 normalizeCwd。</li>
     *   <li>{@link SessionProjectRoot#getForSession(String)}（boundProject，启动锚，对齐 CC 启动=realpath cwd）→ 非空返回 normalizeCwd</li>
     *   <li>两层全 MISS ⇒ <b>抛 IllegalStateException（fail-loud）</b>；{@code user.dir} 兜底只对
     *       「确无会话」开放（{@link #getOriginalCwdLayerForNonSession()}）</li>
     * </ol>
     *
     * <p><b>D-1 裁决</b>：不读 {@code SessionProjectRoot.resolve()}（回落链涉身份域 env/config home，
     * 会使 user.dir 成死代码且身份域泄入工作目录域，违反 R4 红线）。originalCwd 槽是 {@link SessionCwdHolder}
     * 的<b>独立新槽</b>（非 cwd 槽 {@code resolve()}），D-1 红线不变。
     *
     * <p>用于 CLAUDE.md 扫描根 / 会话存档锚（CC claudemd.ts:851 getOriginalCwd）。
     *
     * @param sessionId 会话 ID（必填 —— null/空白 ⇒ 交由 {@link #getOriginalCwdLayerForNonSession()}
     *                  按无会话解析）
     * @return 归一化原始 cwd；<b>有 sessionId 却两层全 MISS ⇒ 抛 IllegalStateException</b>
     * @throws IllegalStateException 有 sessionId 但 originalCwd/boundProject（含 DB 回源）全 MISS，
     *         或 boundProject 无效 —— 数据链路异常，fail-loud
     */
    public static String getOriginalCwdLayer(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            warnNullSession("getOriginalCwdLayer", "getOriginalCwdLayerForNonSession");
            return getOriginalCwdLayerForNonSession();
        }
        // [批 6] 显式「确无会话」哨兵 ⇒ 命名出口（同 getCwd，见上）
        if (SessionKeys.isNoSession(sessionId)) {
            warnNoSessionSentinel("getOriginalCwdLayer", "getOriginalCwdLayerForNonSession");
            return getOriginalCwdLayerForNonSession();
        }
        // [INV-3] L1: originalCwd 重锚层（worktree 入口 setOriginalCwd(worktreePath)，Exit clear 回落 ·
        //   F2 双键，见 {@link #alternateKeyOf}）
        String originalCwd = safeGet(() -> SessionCwdHolder.getOriginalCwd(sessionId));
        if (originalCwd == null || originalCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                originalCwd = safeGet(() -> SessionCwdHolder.getOriginalCwd(alt));
            }
        }
        if (originalCwd != null && !originalCwd.isBlank()) {
            return normalizeCwd(originalCwd);
        }
        // L2: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键 ·
        //   miss 回源 DB + 回填，批 4a #9）
        SessionProjectRoot.Lookup bound = resolveBoundProject(sessionId);
        String boundProject = bound.projectRoot();
        if (boundProject != null) {
            if (isValidDirectory(boundProject)) {
                return normalizeCwd(boundProject);
            }
            // [批 4a #7/#13] 不再回落 user.dir（有会话却解析不出 = 数据链路异常），fail-loud。
            throw unresolvedProjectRoot(sessionId,
                "boundProject 无效（需绝对路径且目录存在）: " + boundProject);
        }
        if (bound.sessionKnown()) {
            // [批 4a #7/#13 用户裁定] 见 getCwd 同段注释：判据 = DB 确认会话存在。
            throw unresolvedProjectRoot(sessionId,
                "会话存在但无绑定项目根（sessions.main_project_id 为空 / projects.path 失效）");
        }
        // 无此会话（合成/伪造/已删 id）⇒ 确无会话 ⇒ 命名出口（行为与批 4a 前一致：进程 user.dir）
        warnUnknownSession("getOriginalCwdLayer", sessionId);
        return getOriginalCwdLayerForNonSession();
    }

    /**
     * <b>无会话出口</b> · 只对「<b>确无会话</b>」开放（用户裁定 #13）。
     *
     * <p>命名自解释：调用它 = 「本处确实没有会话标识，不需要会话项目根」。适用对象为
     * 启动期 bean / MCP transport / 进程级默认 supplier 等<b>结构上拿不到 sessionId</b> 的场景。
     *
     * <p>解析层 = override（显式 cwd 覆盖仍生效，保持与旧 {@code getCwd(null)} 逐字节同行为）
     * → 进程 {@code user.dir}（JVM 启动目录）。<b>不读</b> sessionCwd / boundProject 会话层。
     *
     * <p>⛔ 有 sessionId 的调用方<b>不得</b>用它绕开 fail-loud（那是数据链路异常，应当暴露）。
     *
     * @return 恒非 null 的归一化 cwd（override ?? 进程 user.dir）
     */
    public static String getCwdForNonSession() {
        String override = safeGet(() -> CURRENT_OVERRIDE.get());
        if (override != null && !override.isBlank()) {
            return normalizeCwd(override);
        }
        if (log.isDebugEnabled()) {
            log.debug("[CwdResolution] 无会话解析 cwd（override 空 → 进程 user.dir）: user.dir={}",
                System.getProperty("user.dir"));
        }
        String userDir = System.getProperty("user.dir");
        return normalizeCwd(userDir != null ? userDir : "");
    }

    /**
     * <b>无会话出口</b>（originalCwd 版）· 只对「<b>确无会话</b>」开放（用户裁定 #13）。
     *
     * <p>与 {@link #getOriginalCwdLayer(String)} 的差别同 {@link #getCwdForNonSession()}：
     * 不读 originalCwd / boundProject 会话层，恒取进程 {@code user.dir}（JVM 启动目录）。
     * 现状 {@code getOriginalCwdLayer(null)} 本就无 override 层，本方法保持同行为。
     *
     * @return 恒非 null 的归一化原始 cwd（进程 user.dir）
     */
    public static String getOriginalCwdLayerForNonSession() {
        if (log.isDebugEnabled()) {
            log.debug("[CwdResolution] 无会话解析 originalCwd（进程 user.dir）: user.dir={}",
                System.getProperty("user.dir"));
        }
        String userDir = System.getProperty("user.dir");
        return normalizeCwd(userDir != null ? userDir : "");
    }

    /**
     * 会话标识的"另一形态"键 · CRON-D5 F2 双键解析辅助。
     *
     * <p>[session-id-short] 双层键已统一 short（sess-xxx），本方法退化为恒等（返回入参或 null）。
     * 阶段 1 保留为 {@code @Deprecated} 兼容层：进程内旧 sessionCwd 派生 UUID 键条目仍可经
     * canonicalUuid 分支命中（:210 与新数据无关但无害）；阶段 2（DB 迁移 + transcript 改名完成）
     * 后整段删除。
     *
     * @param sessionId 原键（{@code "sess-xxx"} / 存量派生 UUID 串 / 任意串）
     * @return 另一形态键；无法派生（hash 兜底/随机 UUID/null/空白）→ null
     */
    @Deprecated
    private static String alternateKeyOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (sessionId.startsWith("sess-")) {
            // [session-id-short] 新数据短键与派生键已合一；仅存量派生 UUID 键条目需经此回退命中
            return SessionKeys.canonicalUuid(sessionId).toString();
        }
        // 存量派生 UUID 串 → 原始键（boundProject 层以 "sess-xxx" 为键）；不可逆 → null
        return SessionKeys.originalKey(sessionId);
    }

    /**
     * realpath + NFC 归一化（对齐 CC {@code setCwdState(cwd.normalize('NFC'))} state.ts:532 +
     * Shell.ts setCwd realpathSync）。
     *
     * <p>realpath 失败（目录被删/不存在/权限不足）回原值 + NFC（不抛，对齐 CC catch 兜底）。
     *
     * @param cwd 待归一化路径
     * @return 归一化路径（null/空 原样返回）
     */
    /** boundProject 有效性校验 · [2026-08-24 cwd 污染修复] 相对/不存在路径是无效绑定
     *  （如「抓包流程」），返回会污染工具 cwd 致 Bash/Glob/Read 全失败；仅绝对路径且目录存在
     *   才算有效。 */
    /** [cwd-consistency 2026-08-25] private→public：LlmAgentLoop 冻结 projectRoot 前校验（与 getCwd 一致化）。 */
    public static boolean isValidDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(path);
            return p.isAbsolute() && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalizeCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return cwd;
        }
        try {
            Path real = Path.of(cwd).toRealPath();
            return Normalizer.normalize(real.toString(), Normalizer.Form.NFC);
        } catch (Exception e) {
            // realpath 失败回原值 + NFC（目录被删/不存在场景，对齐 CC catch 不抛）
            if (log.isDebugEnabled()) {
                log.debug("[CwdResolution] realpath 失败回原值+NFC: cwd={} reason={}", cwd, e.toString());
            }
            return Normalizer.normalize(cwd, Normalizer.Form.NFC);
        }
    }

    /**
     * 在当前线程覆盖 cwd 执行 fn（对齐 CC {@code runWithCwdOverride(cwd, fn)} cwd.ts:12-14）。
     *
     * <p>对齐 CC AsyncLocalStorage.run：override 在 fn 内及同线程调用链生效，fn 返回/异常后 finally clear。
     * 子代理若跨线程需入口 set/finally clear。
     *
     * @param cwd override 工作目录
     * @param fn  待执行逻辑
     * @param <T> 返回类型
     * @return fn 返回值
     */
    public static <T> T runWithCwdOverride(String cwd, Supplier<T> fn) {
        setCurrentOverride(cwd);
        try {
            return fn.get();
        } finally {
            clearCurrentOverride();
        }
    }

    /**
     * 设置当前线程 cwd override（null 等价清除）。
     */
    public static void setCurrentOverride(String cwd) {
        if (cwd == null) {
            CURRENT_OVERRIDE.remove();
        } else {
            CURRENT_OVERRIDE.set(cwd);
        }
    }

    /** 清除当前线程 override（会话处理结束 finally 调）。 */
    public static void clearCurrentOverride() {
        CURRENT_OVERRIDE.remove();
    }

    /**
     * safeGet：异常回 null（对齐 CC getCwd catch → getOriginalCwd 兜底语义）。
     */
    private static String safeGet(Supplier<String> s) {
        if (s == null) {
            return null;
        }
        try {
            return s.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** sessionCwd 层解析（F2 双键：原键 → 另一形态；空/异常 → null）。
     *  <p>抽自 getCwd/getOriginalCwdLayer 两份同构代码，避免双键逻辑双轨。 */
    private static String resolveSessionCwd(String sessionId) {
        String sessionCwd = safeGet(() -> SessionCwdHolder.get(sessionId));
        if (sessionCwd == null || sessionCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                sessionCwd = safeGet(() -> SessionCwdHolder.get(alt));
            }
        }
        return (sessionCwd != null && !sessionCwd.isBlank()) ? sessionCwd : null;
    }

    /** boundProject 层三态解析（F2 双键）· {@code lookup} 内部 miss 回源 DB 并回填（批 4a #9）。 */
    private static SessionProjectRoot.Lookup resolveBoundProject(String sessionId) {
        SessionProjectRoot.Lookup bound = safeLookup(sessionId);
        if (bound.projectRoot() == null || bound.projectRoot().isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                SessionProjectRoot.Lookup altLookup = safeLookup(alt);
                // 合并两键结果：只要任一键给出绑定 → 绑定；任一键证明会话存在 → 会话存在
                if (altLookup.projectRoot() != null && !altLookup.projectRoot().isBlank()) {
                    return altLookup;
                }
                if (altLookup.sessionKnown() && !bound.sessionKnown()) {
                    return altLookup;
                }
            }
        }
        return bound;
    }

    /** safeLookup：异常 → 无此会话（对齐 safeGet 的「层内异常不抛」语义）。 */
    private static SessionProjectRoot.Lookup safeLookup(String sessionId) {
        try {
            return SessionProjectRoot.lookup(sessionId);
        } catch (Exception e) {
            return SessionProjectRoot.Lookup.unknown();
        }
    }

    /**
     * 「有 sessionId 却解析不出项目根」的统一 fail-loud 出口（用户裁定 #7/#13）。
     *
     * <p>⛔ 不得改回回落 {@code user.dir}：user.dir 是<b>进程级常量</b>（后端启动目录），
     * 不是会话项目根；回落会让工具/权限/transcript 全锚到后端目录（已造成过真实误删，
     * 见 {@code SessionService} 删除清理的顺序修复注释）。
     */
    private static IllegalStateException unresolvedProjectRoot(String sessionId, String reason) {
        String msg = "[CwdResolution] 会话 " + sessionId + " 项目根解析失败（数据链路异常 —— web 会话必须"
            + "绑定项目才能进行）: " + reason + "。请检查 sessions.main_project_id → projects.path；"
            + "确无会话的调用方请显式走 getCwdForNonSession()/getOriginalCwdLayerForNonSession()。";
        log.error(msg);
        return new IllegalStateException(msg);
    }

    /**
     * null/空白 sessionId 的 ≥WARN 留痕（用户裁定 #13：命名为自解释出口，null 只是兼容路由）。
     *
     * <p>只打印一次：本路由的调用点有 130 个，逐次打印会淹没日志；一次性告警足以把
     * 「漏传 sessionId」暴露出来，且不影响「确无会话」的合法路径（那类调用点应改用命名出口）。
     */
    private static void warnNullSession(String method, String namedExit) {
        if (NULL_SESSION_WARNED.compareAndSet(false, true)) {
            log.warn("[CwdResolution] {} 收到 null/空白 sessionId ⇒ 按「无会话」解析（进程 user.dir）。"
                + "确无会话请显式改用 {}()；若此处本该有会话，属数据链路异常（本告警仅打印一次）",
                method, namedExit);
        }
    }

    /**
     * 「会话不存在」（已删 / 未登记 / 来源不明的 sessionId）的 ≥WARN 留痕（只打印一次）。
     *
     * <p>该路径按「确无会话」解析（进程 user.dir，= 批 4a 前行为）—— DB 无此会话即无项目身份可言。
     *
     * <p><b>[批 6 收口]</b> 上述「合法来源」已全部改为<b>显式传参或 {@link SessionKeys#NO_SESSION}
     * 哨兵</b>（MCP 入站 / standalone fork·子代理 / plan provider）⇒ 走到本分支<b>只可能是</b>
     * 已删会话或真正的来源不明 id（属数据链路异常信号）。哨兵走
     * {@link #warnNoSessionSentinel}，不落此处。
     * ⛔ 仍不得改成 fail-loud：已删会话的历史调用点会成批炸（批 4a 实测）。
     */
    private static void warnUnknownSession(String method, String sessionId) {
        if (UNKNOWN_SESSION_WARNED.compareAndSet(false, true)) {
            log.warn("[CwdResolution] {} 的 sessionId={} 在 DB 中不存在（已删 / 未登记 / 来源不明 id）⇒ 按"
                + "「确无会话」解析（进程 user.dir）。⚠️ 批 6 后合法无会话路径应改用 SessionKeys.NO_SESSION"
                + "哨兵或显式传参 ⇒ 命中本告警请查是否存在漏传 / 陈旧 id（本告警仅打印一次）",
                method, sessionId);
        }
    }

    /**
     * [批 6] 显式「确无会话」哨兵（{@link SessionKeys#NO_SESSION}）的 ≥WARN 留痕（只打印一次）。
     *
     * <p>本分支是**有意声明**「确无会话」（用户裁定 #13 分类 (1)），不是缺陷 ⇒ 告警只作可观测性，
     * 不指向任何待治项（与 {@link #warnUnknownSession} 的指向不同）。
     */
    private static void warnNoSessionSentinel(String method, String namedExit) {
        if (NO_SESSION_WARNED.compareAndSet(false, true)) {
            log.warn("[CwdResolution] {} 收到显式「确无会话」哨兵 {} ⇒ 按无会话解析（进程 user.dir，"
                + "与 {}() 同义）。本路径为有意声明（批 6：MCP 入站 / standalone fork·子代理 / "
                + "无会话 plan provider），非缺陷（本告警仅打印一次）",
                method, SessionKeys.NO_SESSION, namedExit);
        }
    }
}
