package com.nexusai.application.agent.agent;

import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.UnresolvedProjectRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       cwd，并发 agent 各自隔离 —— <b>本仓无对应物</b>（见「[S2 F-07] 删除 override 通道」）</li>
 *   <li>{@code state.ts:500-502} {@code getOriginalCwd()} 返回 {@code STATE.originalCwd}
 *       （启动=realpath cwd，随 worktree/resume 重锚）</li>
 *   <li>{@code state.ts:527-533} {@code STATE.cwd} 单一可变，{@code setCwdState} 做 NFC 归一化</li>
 * </ul>
 *
 * <p><b>分层解析</b>（{@link #getCwd(String)}，对齐 CC pwd/getCwd 的<b>分层回落</b>语义；
 * ⚠️ 本仓<b>已删</b> CC 的 override 层，故本仓为<b>两层</b>会话层 —— 见「[S2 F-07]」段）：
 * <pre>
 * getCwd(sessionId)  // sessionId 必填（null/空白 ⇒ 走 getCwdForNonSession()，见下）
 *   0. [批 6] sessionId == SessionKeys.NO_SESSION（显式「确无会话」哨兵）⇒ 直接走命名出口
 *      getCwdForNonSession() + ≥WARN（不查 DB、不打「伪造 id」告警）
 *   1. sessionCwd = SessionCwdHolder.get(sessionId)（对齐 CC 单一 STATE.cwd；
 *      worktree 入口与 bash cd 共用此层，后者覆盖前者 [Fix-R1]）→ 非空返回 normalizeCwd
 *   2. boundProject = SessionProjectRoot.getForSession(sessionId)（D-1 裁决：仅绑定，
 *      不读 resolve() 回落链——身份域红线；miss 时该方法自身回源 DB 并回填）→ 非空返回 normalizeCwd
 *   3. 两层全 MISS ⇒ 按 <b>DB 给出的是哪种答案</b> 分流（[批 4a] 裁定 #7/#13 的判据载体 +
 *      [S2 F-09/F-20] 第 4 态 + <b>[cwd3 步骤 2] 第 5 态 sessionless</b>）：
 *      <ul>
 *        <li><b>解析失败 / 无法判定</b>（回源解析器未接线 · 回源抛错 · 违约返回 null）⇒
 *            <b>抛（fail-loud）</b>，文案与「未绑定」<b>可辨识</b>（[S2 F-09/F-20] 用户裁定 (A)：
 *            仍 fail-loud，但保留语义与纠错文案）</li>
 *        <li><b>会话存在（DB 有行）但无绑定项目根</b> ⇒ <b>抛（fail-loud）</b>
 *            —— 数据链路异常，⛔ 不得回落进程 user.dir 冒充会话项目根</li>
 *        <li><b>本环境确无会话</b>（{@link SessionProjectRoot.Lookup#sessionlessEnvironment()}：sessionId
 *            为 null/空白，或显式 {@link SessionKeys#NO_SESSION} 哨兵，或夹具声明本环境无 DB）⇒
 *            走命名出口 {@link #getCwdForNonSession()}（≥WARN 留痕）。这是<b>合法形态</b>
 *            （铁律出口 (b)：「本条路径结构上不属于任何会话」）</li>
 *        <li><b>DB 明确答「无此会话」</b>（{@link SessionProjectRoot.Lookup#unknown()}：查了 DB、
 *            DB 说没有这一行 —— 已删 / 未登记 / 来源不明 id）⇒ <b>[cwd3 步骤 2] 抛（fail-loud）</b>。
 *            ⛔ 旧行为（回落进程 user.dir）已删：确无会话的调用方必须走上一档的命名出口/哨兵</li>
 *      </ul>
 * </pre>
 *
 * <p><b>[S2 · F-09/F-20 2026-09-14 · 用户裁定 (A)] 新增第 4 态「解析失败」，三类吞点不再静默</b>：
 * ① {@code SessionProjectRoot.refillFromDb} 的「解析器未接线」/「回源抛错」/「违约返回 null」三处
 * 原为 {@code return Lookup.unknown()}（其中未接线那处<b>零日志</b>）⇒ 现统一 ≥WARN +
 * {@link SessionProjectRoot.Lookup#resolutionFailed()}；② 本类 {@link #safeLookup} 的
 * {@code catch(Exception)} 收窄为 {@link RuntimeException} 且强制 WARN、返回 resolutionFailed 而非
 * unknown；③ {@link #safeGet} 同样收窄 + 带层名 WARN。⚠️ 判据是<b>同一个</b>「解析失败态」
 * （F-09 与 F-20 共用，不产生两套判据）。
 *
 * <p><b>[S2 · F-09 反转既有裁定记录 · 必须显式保留]</b> 本项**反转**批 4a 用户已裁定的
 * {@code CwdResolutionTest} scenario4（「无解析器 / 合成 id ⇒ 不抛」）：落地后「解析器未接线」
 * 从「确无会话」变为「解析失败」⇒ 该场景**改为 fail-loud**。推翻的是「未接线 = 无会话」这一
 * 批 4a 前提，理由 = 未接线是**装配异常**（本该有却没有），把它当选票投给「无会话」会让裁定 #7 的
 * fail-loud 因装配异常全进程静默失效（原实现零日志，无人能发现）。旧行为 = 返回进程 user.dir 且不抛；
 * 新行为 = 抛 IllegalStateException（reason 明确指向「无法判定」而非「无此会话」）。
 * ⚠️ 生产不受影响：{@code ToolRegistrationConfig:1227} 已接线（见
 * {@link SessionProjectRoot#isDbResolverWired()}），故该分支生产不可达、只在测试/装配故障时暴露。
 *
 * <p><b>[S2 · F-07 2026-09-14 · 用户裁定 #8] 删除 override 通道（CURRENT_OVERRIDE ThreadLocal）</b>：
 * 原 L1 层 {@code ThreadLocal CURRENT_OVERRIDE} + {@code runWithCwdOverride/setCurrentOverride/
 * clearCurrentOverride} 三方法 + 2 个读点（{@code getCwd} 的 L1、{@code getCwdForNonSession} 首层）
 * 全部删除。理由（裁定 #8）：该通道<b>生产 0 写入点</b>（死管线 —— 全仓非本类命中均为 javadoc/注释），
 * CC 的对应能力（{@code cwd.ts:12-14 runWithCwdOverride} 的 AsyncLocalStorage 覆盖）在本仓已由
 * <b>另一实现</b>承接：{@code SubagentExecutor.withEffectiveCwd}（定义 :433 / 调用 :1918）+
 * {@code AgentMemoryDirectory.withEffectiveCwd}（定义 :183 / 调用 :1933），即「TUC record 字段
 * {@code effectiveCwd} 派生」而非线程本地覆盖 —— 本仓无 AsyncLocalStorage，且工具真正执行处是
 * tool-exec 平台线程池 / STREAM_EXECUTOR 虚拟线程，该 ThreadLocal 结构上不可达（批 1 实测：唯一
 * 真正生效的读点是 run 线程构造 base TUC 时的 effectiveCwd 快照，已由 {@code RunRequest.boundProject}
 * 承接）。依 {@code dead-code-decision-rule}「对应物存在但已由另一实现承接 ⇒ 删除」。
 *
 * <p>⚠️ <b>删除后不构成「出口变强」</b>：因生产 0 写入点，{@code getCwdForNonSession()} 的行为
 * 在删除前后<b>逐字节相同</b>（= {@code normalizeCwd(process user.dir)}）；变的只是「不再存在一条
 * 可被误用的注入缝」。⛔ 不得据此宣称安全性提升。
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
 *
 * <p><b>[cwd3 步骤 2 2026-09-15 · 已删除的历史反证，⛔ 勿再回填]</b> 本段原文写着
 * 「但『DB 无此会话』仍走无会话出口 —— 原因是实测（批 4a）：合成 sessionId 的生产路径多且合法
 * （MCP 入站 / standalone fork/subagent / 文档更新器），一刀切 fail-loud 会把它们打死」。
 * <b>该反证已被本批实测证伪</b>：批 4a 把两类混为一谈 —— ①「本环境确无会话」（真正合法的合成 id，
 * 已在批 6 全部改为显式 {@link SessionKeys#NO_SESSION} 哨兵或显式传参 ⇒ 现在由
 * {@code sessionless} 态独占表达，仍走无会话出口）与 ②「DB 明确答无此会话」（本该有会话却没有
 * = 数据链路异常）。现在 ② fail-loud，① 不变 ⇒ 「一刀切打死合法路径」不成立。判据见
 * {@link SessionProjectRoot.Lookup} 的 javadoc 与交付报告的门禁读数。
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
 * <p><b>失败语义</b>（[批 4a] 由「恒非 null」改为 fail-loud；[cwd3 步骤 2] 再收一类）：{@link #getCwd(String)}
 * 各层 safeGet（层内异常回 null）逐层回落；<b>DB 认得该会话却两层全 MISS（含绑定失效）⇒ 抛</b>；
 * <b>DB 明确答「无此会话」⇒ 抛</b>（[cwd3 步骤 2] 原为回落 user.dir，已删）；
 * <b>解析失败 / 无法判定 ⇒ 抛</b>；<b>本环境确无会话（sessionless）⇒ 无会话出口</b>
 * （≥WARN）。只有 {@link #getCwdForNonSession()} 恒非 null（进程 user.dir）。
 *
 * <p><b>OD-1 决策</b>：{@code CwdOverride}（0 生产调用）已<b>合并入本类并删除</b>，无别名/双轨。
 *
 * <p>线程安全：{@link SessionCwdHolder} / {@link SessionProjectRoot} 内部 ConcurrentHashMap，
 * 本类<b>零线程本地状态</b>（同 JVM 多会话各自按显式 sessionId 解析，与执行线程无关）。
 */
public final class CwdResolution {

    private static final Logger log = LoggerFactory.getLogger(CwdResolution.class);

    /** null/空白 sessionId 的告警一次性开关（warnNullSession，见该方法 javadoc）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NULL_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 「DB 明确答无此会话」路径的告警一次性开关（warnUnknownSession，见该方法 javadoc）。
     *
     * <p>[cwd3 · S2.6] <b>按入口拆成两闸</b>（原为单闸跨 {@link #getCwd(String)} 与
     * {@link #getOriginalCwdLayer(String)} 共用 ⇒ 可见度上限 = <b>1 行日志/进程</b>，先触发的那个
     * 入口会把另一入口的告警吃掉）。反向实验：令 {@code getCwd} 先触发 ⇒
     * {@code getOriginalCwdLayer} 仍须有自己的 WARN。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean UNKNOWN_SESSION_WARNED_GET_CWD =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 同上，{@link #getOriginalCwdLayer(String)} 入口专属闸（[cwd3 S2.6] 拆闸）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean UNKNOWN_SESSION_WARNED_GET_ORIGINAL_CWD =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * [cwd3 S2.6] 「本环境确无会话」（{@link SessionProjectRoot.Lookup#sessionlessEnvironment()}）路径的
     * ≥WARN 一次性开关 · <b>同样按入口拆两闸</b>（同 {@link #UNKNOWN_SESSION_WARNED_GET_CWD} 的理由）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SESSIONLESS_WARNED_GET_CWD =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 同上，{@link #getOriginalCwdLayer(String)} 入口专属闸。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SESSIONLESS_WARNED_GET_ORIGINAL_CWD =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** [批 6] 显式「确无会话」哨兵路径的告警一次性开关（warnNoSessionSentinel）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NO_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private CwdResolution() {}

    /**
     * 测试钩子：复位全部一次性告警闸（⛔ 生产路径不得调用）。
     *
     * <p><b>WHY 必须存在</b>：这些闸是「每进程一次」，而断言「哪个入口打了哪条 WARN」的用例
     * （{@code CwdResolutionTest#warnGatesAreIndependentPerEntry}）必须从确定状态出发 ——
     * 否则先跑的用例把闸用掉，后跑的必然假红/假绿（本仓已登记该类测试顺序依赖风险，
     * 先例 = {@code SessionProjectRoot.reset()}）。
     */
    public static void resetWarnGatesForTesting() {
        NULL_SESSION_WARNED.set(false);
        UNKNOWN_SESSION_WARNED_GET_CWD.set(false);
        UNKNOWN_SESSION_WARNED_GET_ORIGINAL_CWD.set(false);
        SESSIONLESS_WARNED_GET_CWD.set(false);
        SESSIONLESS_WARNED_GET_ORIGINAL_CWD.set(false);
        NO_SESSION_WARNED.set(false);
    }

    /**
     * 统一入口：解析 sessionId 对应的当前工作目录（对齐 CC pwd/getCwd）。
     *
     * <p>两层回落（[S2 F-07] 已删 override 层）；各层 safeGet 异常回 null；<b>全 MISS ⇒ 抛</b>
     * （无 user.dir 兜底，见类注释）。
     *
     * <p><b>[CRON-D5 F2 返工] 双键解析</b>：sessionCwd/SessionCwdHolder 层以派生 UUID 串为键
     * （BashTool/EnterWorktreeTool 以 {@code ctx.sessionId()} 登记），boundProject/
     * SessionProjectRoot 层以原始会话键 {@code "sess-xxx"} 为键（bind / resolveSessionProjectRoot 以
     * streamSessionId 登记）。传入的 sessionId 可能是任一形态（cron 后台线程经 QueueItem 透传派生 UUID；
     * REST 入口显式传入的原始键），故每层先试原键、MISS 再试另一形态（{@link #alternateKeyOf}，严格超集，
     * 仅补缺失解析路径，两形态键域不重叠无错配）。
     *
     * @param sessionId 会话 ID（必填 —— null/空白 ⇒ 交由 {@link #getCwdForNonSession()} 按无会话解析）
     * @return 归一化 cwd；<b>会话存在却解析不出项目根 / DB 明确答无此会话 / 无法判定 ⇒ 抛</b>；
     *         本环境确无会话（sessionless）⇒ 无会话出口值（进程 user.dir）
     * @throws IllegalStateException 三种数据链路异常之一：① 会话存在（DB 有行）但
     *         sessionCwd/boundProject（含 DB 回源）全 MISS，或 boundProject 无效（非绝对路径/目录不存在）；
     *         ② [cwd3 步骤 2] DB 明确答「无此会话」（{@link SessionProjectRoot.Lookup#unknown()}）；
     *         ③ 解析失败 / 无法判定（回源器未接线 · 回源抛错 · 违约返回 null）
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
        // L1: sessionCwd（对齐 CC 单一 STATE.cwd · worktree 入口与 cd 共用 [Fix-R1] · F2 双键）
        //   [S2 F-07] 原「L1: override」层（CURRENT_OVERRIDE ThreadLocal）已按用户裁定 #8 删除。
        String sessionCwd = resolveSessionCwd(sessionId);
        if (sessionCwd != null) {
            return normalizeCwd(sessionCwd);
        }
        // L2: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键）
        //     getForSession/lookup 内部 miss 时回源 DB 并回填（批 4a #9）⇒ 后端重启后首条消息前也能命中。
        SessionProjectRoot.Lookup bound = resolveBoundProject(sessionId);
        // [S2 · F-09/F-20 2026-09-14 · 用户裁定 (A)] 第 4 态「解析失败」= 仍 fail-loud 抛，
        //   但**保留可辨识语义与纠错文案**（⛔ 不与「有会话但未绑定」共用同一句 —— 那是 DB 给出了
        //   明确答案；本态是「DB 没给出答案」）。判据来源见 SessionProjectRoot.Lookup#resolutionFailed()。
        if (bound.resolutionFailed()) {
            throw unresolvedProjectRoot(sessionId,
                "项目根**无法判定**（非「无此会话」也非「未绑定」）—— 判据来源 = DB 回源解析器未接线，"
                    + "或回源查询抛错 / 违约返回 null（详见同刻 ≥WARN 日志 [SessionProjectRoot]）");
        }
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
        // ⭐ [cwd3 步骤 2 第 5 态] 「本环境确无会话」（sessionId 为 null/空白，或回源器/本类
        //   lookup 判定为哨兵/无会话形态）⇒ 命名无会话出口（进程 user.dir）+ ≥WARN。
        //   ⛔ 与本块**下方**「DB 明确答无此会话」严格区分：本态是「本来就不该有会话」= 合法；
        //   下方是「本该有会话、DB 却说没有」= 数据链路异常 ⇒ fail-loud。
        if (bound.sessionless()) {
            warnSessionlessEnvironment("getCwd", "getCwdForNonSession", SESSIONLESS_WARNED_GET_CWD);
            return getCwdForNonSession();
        }
        // [cwd3 步骤 2 · flip] **DB 明确答「无此会话」⇒ fail-loud 抛**（⛔ 不再回落进程 user.dir）。
        //   WHY：`unknown` 现在只剩「查过 DB 且 DB 说没有这一行」一个含义 ⇒ 属数据链路异常。
        //   确无会话的调用方必须显式传 {@link SessionKeys#NO_SESSION} 哨兵（走上方 sessionless 出口）。
        //   旧行为（回落 user.dir）会把工具/权限/transcript 全锚到后端启动目录（已造成过真实误删）。
        //   RED（RE-2a-1）：本段改回 `warnUnknownSession + return getCwdForNonSession()` ⇒
        //   CwdResolutionTest#dbAnsweredNoSuchSession_failsLoud 红。
        warnUnknownSession("getCwd", sessionId, UNKNOWN_SESSION_WARNED_GET_CWD);
        throw unresolvedProjectRoot(sessionId,
            "DB 明确答「无此会话」（已删 / 未登记 / 来源不明 id）—— 数据链路异常，⛔ 不回落进程 user.dir；"
                + "若本调用确无会话，必须显式传 SessionKeys." + SessionKeys.NO_SESSION + " 哨兵");
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
     * @return 归一化原始 cwd；<b>有 sessionId 却两层全 MISS / DB 明确答无此会话 / 无法判定 ⇒ 抛</b>；
     *         本环境确无会话（sessionless）⇒ 无会话出口值（进程 user.dir）
     * @throws IllegalStateException 同 {@link #getCwd(String)} 的三类数据链路异常（fail-loud）
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
        String originalCwd = safeGet("originalCwd", () -> SessionCwdHolder.getOriginalCwd(sessionId));
        if (originalCwd == null || originalCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                originalCwd = safeGet("originalCwd(alt)", () -> SessionCwdHolder.getOriginalCwd(alt));
            }
        }
        if (originalCwd != null && !originalCwd.isBlank()) {
            return normalizeCwd(originalCwd);
        }
        // L2: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键 ·
        //   miss 回源 DB + 回填，批 4a #9）
        SessionProjectRoot.Lookup bound = resolveBoundProject(sessionId);
        // [S2 · F-09/F-20] 第 4 态「解析失败」⇒ fail-loud（见 getCwd 同段注释）
        if (bound.resolutionFailed()) {
            throw unresolvedProjectRoot(sessionId,
                "项目根**无法判定**（非「无此会话」也非「未绑定」）—— 判据来源 = DB 回源解析器未接线，"
                    + "或回源查询抛错 / 违约返回 null（详见同刻 ≥WARN 日志 [SessionProjectRoot]）");
        }
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
        // ⭐ [cwd3 步骤 2 第 5 态] 「本环境确无会话」⇒ 命名出口 + ≥WARN（见 getCwd 同段注释）。
        if (bound.sessionless()) {
            warnSessionlessEnvironment("getOriginalCwdLayer", "getOriginalCwdLayerForNonSession",
                SESSIONLESS_WARNED_GET_ORIGINAL_CWD);
            return getOriginalCwdLayerForNonSession();
        }
        // [cwd3 步骤 2 · flip] DB 明确答「无此会话」⇒ fail-loud（见 getCwd 同段注释与 RED 配方）。
        warnUnknownSession("getOriginalCwdLayer", sessionId, UNKNOWN_SESSION_WARNED_GET_ORIGINAL_CWD);
        throw unresolvedProjectRoot(sessionId,
            "DB 明确答「无此会话」（已删 / 未登记 / 来源不明 id）—— 数据链路异常，⛔ 不回落进程 user.dir；"
                + "若本调用确无会话，必须显式传 SessionKeys." + SessionKeys.NO_SESSION + " 哨兵");
    }

    /**
     * <b>无会话出口</b> · 只对「<b>确无会话</b>」开放（用户裁定 #13）。
     *
     * <p>命名自解释：调用它 = 「本处确实没有会话标识，不需要会话项目根」。适用对象为
     * 启动期 bean / MCP transport / 进程级默认 supplier 等<b>结构上拿不到 sessionId</b> 的场景。
     *
     * <p>解析层 = 进程 {@code user.dir}（JVM 启动目录，经 {@link #normalizeCwd} realpath+NFC）。
     * <b>不读</b> sessionCwd / boundProject 会话层。
     * <p>[S2 F-07] 原首层 override 已按用户裁定 #8 删除 ⇒ 本出口恒等于
     * {@code normalizeCwd(process user.dir)}。
     *
     * <p>⛔ 有 sessionId 的调用方<b>不得</b>用它绕开 fail-loud（那是数据链路异常，应当暴露）。
     *
     * @return 恒非 null 的归一化 cwd（进程 user.dir）
     */
    public static String getCwdForNonSession() {
        // [S2 F-07] 原首层 override（CURRENT_OVERRIDE ThreadLocal）已按用户裁定 #8 删除 ⇒
        //   本出口现恒等于 normalizeCwd(process user.dir)（生产 0 写入点使删除前后逐字节同值）。
        if (log.isDebugEnabled()) {
            log.debug("[CwdResolution] 无会话解析 cwd（进程 user.dir）: user.dir={}",
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
    /**
     * boundProject 有效性校验 · <b>委托 {@link SessionProjectRoot#isValidProjectRoot(String)}</b>
     * （[S2 · F-24-merge Step 1 2026-09-14] 差异 A 消除）。
     *
     * <p>[2026-08-24 cwd 污染修复] 相对/不存在路径是无效绑定（如「绑定『抓包流程』」），返回会污染
     * 工具 cwd 致 Bash/Glob/Read 全失败；仅绝对路径且目录存在才算有效。
     * <p>[cwd-consistency 2026-08-25] private→public：LlmAgentLoop 冻结 projectRoot 前校验。
     *
     * <p><b>[S2 Step 1] 本方法不再自带实现</b>：原为 `isValidProjectRoot` 的**第二份拷贝**
     * （同 `p.isAbsolute() && Files.isDirectory(p)` + 同 catch），是「同一能力两套判据」的实例。
     * 现唯一实现下沉到 {@code common} 的 {@link SessionProjectRoot#isValidProjectRoot}，
     * 本方法只做委托（`application→common` 既有方向，不成环）。保留本名是因为它有 130+ 调用点。
     * <p>⛔ 不要在本方法里重新写判据 —— 那会再次制造两份拷贝
     * （`SessionProjectRootValidityParityTest` 会红）。
     */
    public static boolean isValidDirectory(String path) {
        return SessionProjectRoot.isValidProjectRoot(path);
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
     * safeGet：<b>层内</b>读取异常回 null（对齐 CC getCwd catch → getOriginalCwd 兜底语义）。
     *
     * <p>[S2 · F-09 2026-09-14] 收窄为 {@link RuntimeException} 且<b>强制 ≥WARN（带层名）</b>：
     * 原 {@code catch (Exception)} 回 null 是**零日志**的静默降级 —— 任一层的读取炸掉都会被
     * 当成「该层 MISS」而悄悄落到下一层（甚至落到 user.dir）。现改为可观测：异常不再无声。
     * ⛔ 仍回 null（保留分层回落语义）：本方法守的是「层内异常不得中断整条回落链」，
     * 而「全层 MISS 之后怎么办」由 {@link #getCwd(String)} 的 fail-loud 判据承接。
     *
     * <p>⚠️ <b>真实守护范围（照实声明）</b>：{@link SessionCwdHolder} 的两个读点
     * （{@code get}/{@code getOriginalCwd}）当前**不抛**，测试也无注入点 ⇒ 本 catch 与新增的
     * WARN 目前**零覆盖**（同 {@link #safeLookup}，反向实验实测无红）。其价值仅为「将来这两个
     * 读点开始抛时不再静默」的回归预防；本次改动的可观测收益是把**原零日志**的静默降级变成
     * 带层名的 ≥WARN，⛔ 不等于「新增了行为守卫」。
     *
     * @param layer 层名（仅用于日志定位，如 {@code "sessionCwd"} / {@code "originalCwd"}）
     * @param s     层读取器（null → 直接 null）
     */
    private static String safeGet(String layer, Supplier<String> s) {
        if (s == null) {
            return null;
        }
        try {
            return s.get();
        } catch (RuntimeException e) {
            log.warn("[CwdResolution] {} 层读取抛未预期异常 ⇒ 该层按 MISS 处理（继续回落下一层）: err={}",
                layer, e.toString());
            return null;
        }
    }

    /** sessionCwd 层解析（F2 双键：原键 → 另一形态；空/异常 → null）。
     *  <p>抽自 getCwd/getOriginalCwdLayer 两份同构代码，避免双键逻辑双轨。 */
    private static String resolveSessionCwd(String sessionId) {
        String sessionCwd = safeGet("sessionCwd", () -> SessionCwdHolder.get(sessionId));
        if (sessionCwd == null || sessionCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                sessionCwd = safeGet("sessionCwd(alt)", () -> SessionCwdHolder.get(alt));
            }
        }
        return (sessionCwd != null && !sessionCwd.isBlank()) ? sessionCwd : null;
    }

    /** boundProject 层多态解析（F2 双键）· {@code lookup} 内部 miss 回源 DB 并回填（批 4a #9）。
     *  <p>[S2 F-09 2026-09-14] 合并规则扩到四态：<b>任一键「解析失败」⇒ 结果解析失败</b>
     *  （⛔ 不得被另一键的 unknown 冲掉），且「解析失败」优先于「无此会话」。 */
    private static SessionProjectRoot.Lookup resolveBoundProject(String sessionId) {
        SessionProjectRoot.Lookup bound = safeLookup(sessionId);
        if (bound.projectRoot() != null && !bound.projectRoot().isBlank()) {
            return bound;
        }
        // 仅在「原键无绑定」时才查另一形态键（保持既有惰性：有绑定时不多一次 DB 往返）
        SessionProjectRoot.Lookup altLookup = null;
        String alt = alternateKeyOf(sessionId);
        if (alt != null) {
            altLookup = safeLookup(alt);
            // 合并两键结果：只要任一键给出绑定 → 绑定
            if (altLookup.projectRoot() != null && !altLookup.projectRoot().isBlank()) {
                return altLookup;
            }
        }
        // 无可用绑定 ⇒ 「会话存在」优先（任一键证明会话存在 → 会话存在）
        if (bound.sessionKnown()) {
            return bound;
        }
        if (altLookup != null && altLookup.sessionKnown()) {
            return altLookup;
        }
        // [S2 F-09] 无绑定且两键皆不证明会话存在 ⇒ 任一键「解析失败」必须浮出（优先于 unknown）
        if (bound.resolutionFailed()) {
            return bound;
        }
        if (altLookup != null && altLookup.resolutionFailed()) {
            return altLookup;
        }
        // [cwd3 步骤 2] 「本环境确无会话」（sessionless）也必须浮出（优先于 unknown）：
        //   任一键给出 sessionless ⇒ 本环境确无会话。⛔ 不浮出会被压成 unknown ⇒ cwd 域
        //   fail-loud 抛 ⇒ 夹具/哨兵路径全被打死。
        //   优先级：原键的 sessionless 胜过另一形态键的 sessionless（原键是调用方真正传的那个）。
        if (bound.sessionless()) {
            return bound;
        }
        if (altLookup != null && altLookup.sessionless()) {
            return altLookup;
        }
        return bound;
    }

    /**
     * safeLookup：层内异常 → <b>「解析失败」</b>（[S2 F-09 2026-09-14] 收窄 + 强制 ≥WARN）。
     *
     * <p><b>WHY 改</b>：原 {@code catch (Exception) → Lookup.unknown()} 会把「解析本身炸了」静默
     * 投给「确无会话」⇒ 用户裁定 #7 的 fail-loud 被整体旁路（正是根因 3 的落点）。现收窄为
     * {@link RuntimeException}（{@code SessionProjectRoot.lookup} 全函数声明上不抛 checked）
     * 并强制 WARN；返回值改用可辨识的 {@link SessionProjectRoot.Lookup#resolutionFailed()}。
     *
     * <p>⚠️ <b>本守卫的真实守护范围（反向实验实测，2026-09-14 · 必须照实声明）</b>：本 catch
     * <b>当前零覆盖</b> —— 把它的返回值改回 {@code Lookup.unknown()} 后
     * {@code SessionProjectRootTest / CwdResolutionTest / PathGuardCwdResolutionTest} 全绿
     * （实测 36 tests / 0 failures）。原因是 {@code SessionProjectRoot.lookup} 自身结构上不抛
     * （{@code refillFromDb} 已在其内部自吞全部 {@code Exception}），且测试无法注入会抛的 lookup。
     * ⇒ 它的价值**仅为**「将来有人给 {@code lookup} 加抛点时不被静默吞成 unknown」的回归预防；
     * ⛔ 不得声称它对当下行为有任何守护力（本仓已连续多次栽在「声称守护 X、实际守不住」）。
     * 真正承重的那一半是 {@code SessionProjectRoot.refillFromDb} 的 catch —— 它由
     * {@code SessionProjectRootTest.dbResolverThrowing_reportsResolutionFailureWithWarn} 守护
     * （实测该处置回 unknown ⇒ 必红）。
     */
    private static SessionProjectRoot.Lookup safeLookup(String sessionId) {
        try {
            return SessionProjectRoot.lookup(sessionId);
        } catch (RuntimeException e) {
            log.warn("[CwdResolution] SessionProjectRoot.lookup 抛出未预期异常 ⇒ 按「解析失败」处理"
                + "（⛔ 不得静默当成『确无会话』）: sessionId={} err={}", sessionId, e.toString());
            return SessionProjectRoot.Lookup.resolutionFailure();
        }
    }

    /**
     * 「有 sessionId 却解析不出项目根」的统一 fail-loud 出口（用户裁定 #7/#13）。
     *
     * <p>⛔ 不得改回回落 {@code user.dir}：user.dir 是<b>进程级常量</b>（后端启动目录），
     * 不是会话项目根；回落会让工具/权限/transcript 全锚到后端目录（已造成过真实误删，
     * 见 {@code SessionService} 删除清理的顺序修复注释）。
     *
     * <p><b>[S2 · F-03b 2026-09-14 · 用户裁定 #12 (B2)] 返回类型
     * {@link IllegalStateException} → {@link UnresolvedProjectRootException}</b>：新类型
     * {@code extends IllegalStateException} ⇒ 既有 4 处 {@code isInstanceOf(IllegalStateException)}
     * 断言与全部 {@code catch (ISE)} 吞点<b>行为不变</b>，同时让 REST 边界可经
     * {@code GlobalExceptionHandler} 单点译 <b>400</b>（原为 500）。⛔ 不是 409。
     */
    private static UnresolvedProjectRootException unresolvedProjectRoot(String sessionId, String reason) {
        String msg = "[CwdResolution] 会话 " + sessionId + " 项目根解析失败（数据链路异常 —— web 会话必须"
            + "绑定项目才能进行）: " + reason + "。请检查 sessions.main_project_id → projects.path；"
            + "确无会话的调用方请显式走 getCwdForNonSession()/getOriginalCwdLayerForNonSession()。";
        log.error(msg);
        return new UnresolvedProjectRootException(msg);
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
     * 「DB 明确答无此会话」（已删 / 未登记 / 来源不明的 sessionId）的 ≥WARN 留痕 · <b>抛前告警</b>
     * （[cwd3 步骤 2] 起本分支 <b>fail-loud 抛</b>，见 {@link #getCwd(String)} 同段注释）。
     *
     * <p><b>[cwd3 步骤 2 已删除的历史反证，⛔ 勿再回填]</b> 旧 javadoc 写着
     * 「仍不得改成 fail-loud：已删会话的历史调用点会成批炸（批 4a 实测）」。
     * <b>该反证在本批被实测证伪</b>：批 4a 当时把「已删会话」与「本环境确无会话」（MCP 入站 /
     * standalone fork·子代理 / 文档更新器等合成 id）混为一谈，而后者已在批 6 全部改为显式
     * {@link SessionKeys#NO_SESSION} 哨兵或显式传参 ⇒ 「已删会话」不再有合法的历史调用点。
     * 逐条读数见交付报告的门禁记录。
     *
     * <p>⚠️ 可见度：本 WARN 是<b>一次性</b>闸（按入口各一），但失败本身<b>每次</b>都会经
     * {@link #unresolvedProjectRoot} 打 ERROR（含 sessionId）⇒ 不存在「只报一次就静默」的问题。
     */
    private static void warnUnknownSession(String method, String sessionId,
                                           java.util.concurrent.atomic.AtomicBoolean gate) {
        if (gate.compareAndSet(false, true)) {
            log.warn("[CwdResolution] {} 的 sessionId={} 在 DB 中不存在（已删 / 未登记 / 来源不明 id）⇒ "
                + "**fail-loud 抛**（[cwd3 步骤 2] ⛔ 不再回落进程 user.dir）。⚠️ 确无会话的调用方必须显式传"
                + " SessionKeys.NO_SESSION 哨兵或显式传参；命中本告警请查是否存在漏传 / 陈旧 id"
                + "（本告警按入口各打印一次；失败本身每次都打 ERROR）",
                method, sessionId);
        }
    }

    /**
     * [cwd3 步骤 2] 「<b>本环境确无会话</b>」（{@link SessionProjectRoot.Lookup#sessionlessEnvironment()}）路径的
     * ≥WARN 留痕（按入口各一闸）。
     *
     * <p><b>为什么这一态也要 ≥WARN</b>（规则十二「缺值策略 (b)：跳过但 ≥WARN」）：它不是错误
     * （「本来就不该有会话」是合法形态），但它是<b>信息缺失</b> —— 调用方拿不到会话项目根，
     * 只能退回进程 {@code user.dir}（后端启动目录）。必须可观测，否则「本该有会话却走了无会话出口」
     * 的漏传会静默。
     *
     * <p>与 {@link #warnUnknownSession} 的指向不同：本 WARN 指向「这里是否本该有会话」，
     * 后者指向「会话已删 / id 来源不明」。
     */
    private static void warnSessionlessEnvironment(String method, String namedExit,
                                                   java.util.concurrent.atomic.AtomicBoolean gate) {
        if (gate.compareAndSet(false, true)) {
            log.warn("[CwdResolution] {} 判定「本环境确无会话」（sessionless）⇒ 按无会话解析（进程 "
                + "user.dir，与 {}() 同义）。⚠️ 若此处本该有会话，属数据链路异常（请查调用方是否漏传 / "
                + "传了陈旧 id；本告警按入口各打印一次）", method, namedExit);
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
