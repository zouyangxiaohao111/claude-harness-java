package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.UnresolvedProjectRootException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [批 P9 2026-09-15] <b>CLAUDE_PROJECT_DIR 的生产接线守护</b> ——
 * 「每个 command hook 拿到的 {@code CLAUDE_PROJECT_DIR} = 该会话的项目根」。
 *
 * <h2>为什么需要本类（= 被它治的那个缺陷）</h2>
 * <p>原实现：{@link CommandHookExecutor} 的 {@code projectRootResolver} 是 {@code final} 字段、
 * <b>无 setter</b>，全仓 {@code backend/src/main} <b>0 处</b> {@code new CommandHookExecutor(...)}、
 * 无 {@code @Bean} 工厂 ⇒ Spring 取唯一 public 无参构造器
 * （{@link CommandHookExecutor#CommandHookExecutor()}，体为 {@code this(null, null, null, null, null)}），
 * 它把 {@code projectRootResolver} 传 {@code null} ⇒ 构造器三元恒取 {@code defaultProjectRoot()}
 * = {@code System.getProperty("user.dir")} = <b>后端启动目录</b>。于是<b>每一个</b> command hook 的
 * {@code CLAUDE_PROJECT_DIR} 都不是会话项目根，而是后端进程的启动目录 —— 与 {@code cwd3} / 批 4a
 * 已定案的「{@code user.dir} 冒充项目根」同一缺陷类，而这条是<b>活的、恒被消费的</b>。
 *
 * <h2>为什么既有测试没抓到它（本类补的就是这个缺口）</h2>
 * <p>{@code HookExecutorProjectRootPropagationTest} 用 {@code StubCaptureExecutor extends
 * CommandHookExecutor} <b>覆写 {@code execute}</b> ⇒ 真实 spawn 前的 {@code buildEnv} 链接线
 * <b>从不执行</b>，故 {@code defaultProjectRoot} 这条生产接线零覆盖。{@code CommandHookExecutorTest}
 * 的 {@code buildEnv} 用例则<b>显式注入</b> {@code () -> "C:/proj"} ⇒ 也绕开了「缺省 resolver 是什么」。
 * 本类走<b>真实 {@code execute} → {@code buildEnv}</b>，只把进程启动换成捕获式 launcher。
 *
 * <h2>槽判定（本类断言的语义来源）</h2>
 * <p>{@code CLAUDE_PROJECT_DIR} 用的是 CC 的 <b>{@code getProjectRoot()}</b>（第三个槽），
 * 非 {@code getCwd()}（bash {@code cd} 可覆盖）、非 {@code getOriginalCwd()}（{@code EnterWorktreeTool}
 * <b>会</b>更新）。CC 真源 {@code claude-code-best/src/utils/hooks.ts:896-900} 逐字：
 * 「Set {@code CLAUDE_PROJECT_DIR} to the <b>stable project root (not the worktree path)</b>.
 * {@code getProjectRoot()} is <b>never updated when entering a worktree</b>…」
 * + {@code src/bootstrap/state.ts:496-508}「Use for <b>project identity</b> (history, skills, sessions)
 * <b>not file operations</b>」。
 *
 * <p>本仓对应槽 = {@link SessionProjectRoot}（{@code sessions.main_project_id} → {@code projects.path}）。
 * 逐槽排除证据：
 * <ul>
 *   <li>{@code CwdResolution.getCwd(sessionId)} —— ⛔ <b>排除</b>：L1 = {@code SessionCwdHolder}，
 *       {@code EnterWorktreeTool.java:388} 写它（注释明写「worktree 入口写 SessionCwdHolder cwd 槽
 *       （与 bash cd 同槽，对齐 CC setCwd 均写 STATE.cwd）」）⇒ 语义 = CC {@code getCwd()}。</li>
 *   <li>{@code CwdResolution.getOriginalCwdLayer(sessionId)} —— ⛔ <b>排除</b>：L1 =
 *       {@code SessionCwdHolder.getOriginalCwd}，{@code EnterWorktreeTool.java:392} <b>写它</b>
 *       （{@code setOriginalCwd(worktreePath)}）⇒ 语义 = CC {@code getOriginalCwd()}（<b>会</b>被
 *       EnterWorktreeTool 更新）。</li>
 *   <li>{@link SessionProjectRoot} —— ✅ <b>选定</b>：{@code EnterWorktreeTool} 只<b>读</b>不写
 *       （{@code EnterWorktreeTool.java:450}），写入方 = 会话绑定（{@code ProjectSessionBindingService:80}）
 *       与首 run 冻结（{@code LlmAgentLoop:11347}）⇒ 「启动时设定 · EnterWorktreeTool 不更新」，
 *       与 CC {@code getProjectRoot()} 逐条同义。</li>
 * </ul>
 *
 * <h2>⚠️ 断言「抛」的三条（unbound / unknown / resolutionFailure）的<b>真实终点</b></h2>
 * <p>⛔ 这<b>不是</b>「生产会 400」的断言。{@code executeOnce} 直调 {@code executor.execute}，
 * <b>不经过 {@code HookRegistry}</b>；生产真实终点 = {@code HookRegistry:4596
 * catch (Exception e)} 按 CC {@code hooks.ts:2698-2729} 语义降级为 {@code NON_BLOCKING_ERROR}
 * （该 hook 不运行 + WARN + {@code hookNonBlockingError} attachment），
 * <b>⛔ 不到 {@code GlobalExceptionHandler}、不 400、也不打断 hook 链</b>。
 * <p>⇒ 这三条守的是「<b>我们这层</b>确实抛了（铁律出口 (a)），而不是又发明一个静默分支」；
 * 它们<b>不能</b>证明生产终点的可见性 —— 后者由 {@code HookRegistry} 的 CC 语义决定（本批有意不改）。
 */
@DisplayName("[批 P9] CLAUDE_PROJECT_DIR 生产接线：会话项目根（CC getProjectRoot）· ⛔ 非 user.dir")
class CommandHookProjectDirEnvWiringTest {

    /** 捕获 ProcessSpec 的 launcher · 不启动真实进程（镜像 CommandHookExecutorTest.FakeLauncher）。 */
    static class CapturingLauncher implements CommandHookExecutor.ProcessLauncher {
        final CommandHookExecutor.HookProcess process;
        volatile CommandHookExecutor.ProcessSpec lastSpec;

        CapturingLauncher(CommandHookExecutor.HookProcess process) {
            this.process = process;
        }

        @Override
        public CommandHookExecutor.HookProcess launch(CommandHookExecutor.ProcessSpec spec) {
            this.lastSpec = spec;
            return process;
        }
    }

    /** 显式 hookCwd（12 参重载）· 值本身不影响本类断言，只为让 {@code resolveSpawnCwd} 不被调用。 */
    private static final String STUB_HOOK_CWD = System.getProperty("java.io.tmpdir");

    private ch.qos.logback.classic.Logger hookedLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // dbResolver 是进程级 static 槽；BY_SESSION 是冻结表。两者都须在用例前干净
        // （否则跨类污染 → 断言读到他类的冻结值）。
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
        CwdResolution.resetWarnGatesForTesting();
        hookedLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CommandHookExecutor.class);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        hookedLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (hookedLogger != null && appender != null) {
            hookedLogger.detachAppender(appender);
            appender.stop();
        }
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    /** 已捕获的 ≥WARN 消息（默认日志级别下「本该有会话却漏传」必须在此可见）。 */
    private List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (ch.qos.logback.classic.spi.ILoggingEvent e : appender.list) {
            if (e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) {
                out.add(e.getFormattedMessage());
            }
        }
        return out;
    }

    /**
     * 期望的 env 值 · 镜像 {@code buildEnv} 对路径做的 {@code toHookPath} 处理
     * （{@code hooks.ts:887-893}：Windows + bash ⇒ POSIX 转换；PowerShell / 非 Windows ⇒ 原样）。
     * 本类 hook 不指定 shell ⇒ {@link CommandHook#DEFAULT_SHELL} = {@code bash}。
     */
    private static String expectedHookPath(String p) {
        return CommandHookExecutor.isWindows() ? CommandHookExecutor.windowsPathToPosixPath(p) : p;
    }

    /**
     * 生产接线构造 · 对齐 {@link CommandHookExecutor#CommandHookExecutor()}（体逐字为
     * {@code this(null, null, null, null, null)}）。
     *
     * <p>⚠️ 唯一差别 = 进程启动器换成捕获式（真实 {@code DefaultProcessLauncher} 会 spawn 真进程）。
     * {@code projectRootResolver} 传 {@code null} 与无参构造器<b>逐字相同</b> ⇒ 本方法构造出的 resolver
     * 缺省接线 = Spring 生产接线。{@code envResolver = k -> null} / {@code pathExists = p -> true}
     * 同 {@code CommandHookExecutorTest.newExecutor}（让 {@code resolveGitBashPath} 走 pathExists 短路）。
     */
    private static CommandHookExecutor productionWiring(CapturingLauncher launcher) {
        return new CommandHookExecutor(launcher, k -> null, p -> true,
            null /* ← 生产接线：resolver 缺省（无参构造器同传 null） */, id -> "C:/plugin-data/" + id);
    }

    private static CommandHook echoHook() {
        return new CommandHook("echo hi", null, null, null, null, null, null, null);
    }

    /**
     * 走真实 {@code execute} ⇒ 真实 {@code buildEnv}。
     *
     * <p>用 <b>12 参重载 + 显式 hookCwd</b>（= 生产形态：{@code HookRegistry.executeOneConfiguredHook}
     * 把 {@code resolveSpawnCwd(event)} 的结果作为 hookCwd 显式传入）。这样本类<b>只</b>测
     * {@code CLAUDE_PROJECT_DIR} 这条值传播 —— spawn cwd 是另一个槽（{@code CwdResolution.getCwd}，
     * CC {@code getCwd()}），它有独立的 fail-loud 语义，不是本批的范围。
     * ⛔ 不用 10 参重载（它会顺带调 {@code CwdResolution.getCwd(sessionId)}，在 unbound 场景下
     * fail-loud 抛，把本类要测的 env 分支挡在后面）。
     */
    private static void executeOnce(CommandHookExecutor executor, HookEvent event, String hookCwd) {
        executor.execute(echoHook(), event, "h", "{}",
            null, null, null, null, false, AbortController.NOOP,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, hookCwd);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 断言 1（本批主守护）· 生产接线 ⇒ CLAUDE_PROJECT_DIR = 会话项目根，⛔ 非 user.dir
    // ════════════════════════════════════════════════════════════════════════

    /**
     * <b>守护什么</b>：生产 resolver 缺省下，{@code CLAUDE_PROJECT_DIR} / {@code NEXUSAI_PROJECT_DIR}
     * 必须由 {@code hookEvent.sessionId()} 经 {@link SessionProjectRoot}（CC {@code getProjectRoot()}
     * 槽）解析，⛔ 不是 {@code System.getProperty("user.dir")}。
     *
     * <p><b>造现场</b>：会话绑定到一个全新临时目录，而 JVM 的 {@code user.dir} 是模块目录
     * （两者必然不同）⇒ <b>只有真的去解析会话根才会通过</b>。
     *
     * <p><b>RED（改之前必红）</b>：当前实现恒取 {@code defaultProjectRoot()} ⇒ 实际值
     * {@code toHookPath(user.dir)} vs 期望 {@code toHookPath(boundRoot)} ⇒ 首断言红。
     *
     * <p><b>反向实验</b>：把 {@code resolveSessionProjectRoot} 改回
     * {@code return System.getProperty("user.dir")}（即复活 {@code defaultProjectRoot}）⇒ 必红。
     */
    @Test
    @DisplayName("生产接线：CLAUDE_PROJECT_DIR = 该会话项目根（CC getProjectRoot 槽），⛔ 非 user.dir")
    void productionWiring_projectDir_isSessionBoundRoot_notUserDir(@TempDir Path tmp) throws Exception {
        String boundRoot = CwdResolution.normalizeCwd(
            Files.createDirectories(tmp.resolve("bound-project")).toString());
        String sid = "sess-p9-bound-" + UUID.randomUUID();
        // 生产回源器的等价物（ToolRegistrationConfig#sessionProjectRootResolver：返回已归一值）。
        SessionProjectRoot.setDbResolver(id -> sid.equals(id)
            ? SessionProjectRoot.Lookup.bound(boundRoot)
            : SessionProjectRoot.Lookup.sessionlessEnvironment());

        String userDir = System.getProperty("user.dir");
        assertThat(expectedHookPath(boundRoot))
            .as("前置：现场必须有效 —— 会话项目根与 user.dir 不同，否则本断言无法区分两种实现")
            .isNotEqualTo(expectedHookPath(userDir));

        CapturingLauncher launcher = new CapturingLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("", "", 0));
        executeOnce(productionWiring(launcher), HookEvent.toolPre("Bash", null, sid, null),
            STUB_HOOK_CWD);

        assertThat(launcher.lastSpec).as("hook 必须真的走到 spawn 前（buildEnv 已执行）").isNotNull();
        assertThat(launcher.lastSpec.env().get("CLAUDE_PROJECT_DIR"))
            .as("WHY：CLAUDE_PROJECT_DIR 必须是**该会话**的项目根（CC getProjectRoot · hooks.ts:900）——"
                + "hook 脚本用它定位项目（读 .claude/ 配置、跑项目脚本）")
            .isEqualTo(expectedHookPath(boundRoot));
        assertThat(launcher.lastSpec.env().get("NEXUSAI_PROJECT_DIR"))
            .as("双注入（决策 D1/D6）：两键必须同值")
            .isEqualTo(expectedHookPath(boundRoot));
        assertThat(launcher.lastSpec.env().get("CLAUDE_PROJECT_DIR"))
            .as("⛔ 本批修的缺陷本体：绝不得是后端启动目录（user.dir）冒充项目根")
            .isNotEqualTo(expectedHookPath(userDir));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 断言 2 · 确无会话（sessionId null）⇒ 命名出口 + ≥WARN（铁律出口 (b)）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * <b>守护什么</b>：{@code sessionId} 为 null 时（结构上确无会话）走<b>命名出口</b>
     * {@link CwdResolution#getOriginalCwdLayerForNonSession()}（= 进程 {@code user.dir}，
     * 批 P5 已加 warn-once ≥WARN），且本处<b>自己也留一条 ≥WARN</b> —— ⛔ 不得 {@code log.debug} 静默失效。
     *
     * <p><b>反向实验</b>：把该分支的 {@code log.warn} 降为 {@code log.debug} ⇒ 本断言红。
     */
    @Test
    @DisplayName("确无会话（sessionId null）：走无会话命名出口 + 本处 ≥WARN（⛔ 不静默）")
    void noSession_fallsBackToNonSessionExit_withWarn() {
        CapturingLauncher launcher = new CapturingLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("", "", 0));
        // sessionId = null ⇒ 结构上确无会话（HookEvent 构造器允许 null sessionId）
        executeOnce(productionWiring(launcher), HookEvent.toolPre("Bash", null, null, null),
            STUB_HOOK_CWD);

        assertThat(launcher.lastSpec).isNotNull();
        assertThat(launcher.lastSpec.env().get("CLAUDE_PROJECT_DIR"))
            .as("确无会话 ⇒ 命名出口值（进程 user.dir 的 hook-path 形态）")
            .isEqualTo(expectedHookPath(System.getProperty("user.dir")));
        assertThat(warnings())
            .as("WHY：铁律「不许静默失效」出口 (b) —— 「本该有会话却走了无会话出口」必须在默认日志级别可见")
            .anyMatch(m -> m.contains("[CommandHookExecutor] CLAUDE_PROJECT_DIR 无会话态"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 断言 3-5 · 会话存在却解析不出项目根（三态）⇒ fail-loud 抛 + log.error 留痕
    //
    // ⚠️⚠️ 读这三条断言前必读「真实终点」（⛔ 勿读成「生产会 400」）：
    //   本断言在 **`CommandHookExecutor.execute` 层面**断言「抛」—— `executeOnce` 直调
    //   `executor.execute(...)`，**不经过 `HookRegistry`**。
    //   生产真实终点 = `HookRegistry:4564 try → :4574 executeConfiguredCommand → :4596
    //   catch (Exception e)` 按 CC `hooks.ts:2698-2729`（runHook catch）语义**降级为
    //   NON_BLOCKING_ERROR**：该 hook **不运行** + 一条 `log.warn("…视为 non_blocking_error")`
    //   + 一个 `hookNonBlockingError` attachment。⛔ **不会到 GlobalExceptionHandler、不会 400、
    //   也不会打断 hook 链** —— 那是 CC 的 hook 失败语义（规则三），本批有意不改。
    //   ⇒ 本三条断言的**真实价值** = 守住「我们这层确实抛了（铁律出口 (a)），而不是又发明一个
    //     静默分支」；⛔ 它**不能**证明生产上用户会看到 400。详见 `resolveSessionProjectRoot` 的
    //     javadoc「抛出的真实终点」段。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 三态共用的断言体 · 三者**必须行为一致**（都抛）。
     *
     * <p>WHY 三态都要测：{@link CommandHookExecutor#describeLookupFailure} 把三态分成三种可读描述，
     * 说明它们是**三个不同的判据**；改造前「跳过注入」的行为一致，改造后「抛」也必须一致 ——
     * 只测一条会让另外两条被单点变异悄悄放过。
     *
     * @param lookupFactory 造该态的回源器；<b>{@code null} = 不装回源器</b>（真实「未接线」形态，
     *                      用于 {@code resolutionFailure} —— ⛔ 若装一个返回 {@code null} 的
     *                      lambda，走的是「违约返回 null」子分支而非「未接线」分支，两者只是
     *                      恰好同归 {@code resolutionFailure()}，注释会与代码不符）
     * @param reasonSubstring 该态在 log.error / 异常消息里的可辨识描述片段
     */
    private void assertThrowsForLookupState(java.util.function.Function<String, SessionProjectRoot.Lookup> lookupFactory,
                                           String reasonSubstring) {
        String sid = "sess-p9-throw-" + UUID.randomUUID();
        if (lookupFactory == null) {
            SessionProjectRoot.setDbResolver(null);            // 真实「未接线」：dbResolver == null
        } else {
            SessionProjectRoot.setDbResolver(id -> sid.equals(id)
                ? lookupFactory.apply(id)
                : SessionProjectRoot.Lookup.sessionlessEnvironment());
        }

        CapturingLauncher launcher = new CapturingLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("", "", 0));

        assertThatThrownBy(() -> executeOnce(productionWiring(launcher),
            HookEvent.toolPre("Bash", null, sid, null), STUB_HOOK_CWD))
            .as("WHY：铁律出口 (a) —— 「本该有却没有」（web 会话必须绑定项目才能进行）必须抛，"
                + "⛔ 不是再发明一个「跳过注入」静默分支把它吞掉")
            .isInstanceOf(UnresolvedProjectRootException.class)
            .hasMessageContaining("[CommandHookExecutor]")
            .hasMessageContaining(sid)
            .hasMessageContaining(reasonSubstring);

        assertThat(launcher.lastSpec)
            .as("抛 ⇒ 进程**不拉起**（hook 不运行；对比改造前：hook 照常运行但 CLAUDE_PROJECT_DIR 缺失）")
            .isNull();
        assertThat(warnings())
            .as("抛前必须 ≥WARN/ERROR 留痕（⛔ 不许静默抛）")
            .anyMatch(m -> m.contains("[CommandHookExecutor] CLAUDE_PROJECT_DIR 无法解析会话项目根"));
    }

    /**
     * <b>守护什么</b>：{@code Lookup.unbound()}（会话存在但 {@code main_project_id} 为空 /
     * {@code projects.path} 失效）= 数据链路异常（批 4a 裁定）⇒ <b>抛</b>。
     *
     * <p><b>该态的生产可达性</b>（已实测，非假设）：`ProjectController:81-85`
     * {@code @DeleteMapping("/api/v1/sessions/{sessionId}/project") unbind} →
     * {@code ProjectSessionBindingService.unbind → SessionProjectRoot.clearSession:79} ⇒
     * 冻结表 miss → 回源 DB → {@code main_project_id} 空 ⇒ 本态。⛔ 前端零调用方，但 API 可达。
     *
     * <p><b>反向实验</b>：把该分支的 {@code throw} 改回 {@code return null;}（= 旧的「跳过注入」）
     * ⇒ 本断言红（`assertThatThrownBy` 捕不到异常）。
     */
    @Test
    @DisplayName("unbound（会话存在但无绑定项目根）⇒ 抛 UnresolvedProjectRootException + log.error")
    void unboundSession_throwsFailLoud() {
        assertThrowsForLookupState(id -> SessionProjectRoot.Lookup.unbound(),
            "会话存在但无绑定项目根");
    }

    /**
     * <b>守护什么</b>：{@code Lookup.unknown()}（DB 明确答「无此会话」—— 已删 / 未登记 /
     * 来源不明 id）⇒ <b>抛</b>（与 {@code CwdResolution.getCwd} 对 {@code unknown} 的 fail-loud
     * 一致，`cwd3` 批定案）。
     *
     * <p><b>反向实验</b>：三态共用一个分支 ⇒ 把 `throw` 改回 `return null` 即同时打红三条。
     */
    @Test
    @DisplayName("unknown（DB 明确答无此会话）⇒ 抛 + log.error")
    void unknownSession_throwsFailLoud() {
        assertThrowsForLookupState(id -> SessionProjectRoot.Lookup.unknown(),
            "DB 明确答「无此会话」");
    }

    /**
     * <b>守护什么</b>：{@code Lookup.resolutionFailure()}（<b>无法判定</b> = 回源解析器未接线 /
     * 查询抛错 / 违约返回 null，[S2 F-09/F-20] 第 4 态）⇒ <b>抛</b>。
     *
     * <p><b>造法</b>：{@code setDbResolver(null)} = 真实「未接线」装配异常形态（`refillFromDb`
     * 的 `resolver == null` 分支，非伪造 Lookup）—— 本类 {@code @BeforeEach} 已置 null，此处
     * <b>显式再置一次</b>以免依赖「JUnit 自动注册扩展」的装/清顺序。
     *
     * <p><b>反向实验</b>：同 {@link #unknownSession_throwsFailLoud()}。
     */
    @Test
    @DisplayName("resolutionFailure（回源未接线 = 无法判定）⇒ 抛 + log.error")
    void resolutionFailure_throwsFailLoud() {
        // lookupFactory = null ⇒ 走真实「未接线」分支（refillFromDb 的 resolver == null），
        // 非「违约返回 null」子分支；两者同归 resolutionFailure() 但路径不同。
        assertThrowsForLookupState(null, "DB 回源解析器未接线");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 断言 4 · 显式「确无会话」哨兵同 null 路由
    // ════════════════════════════════════════════════════════════════════════

    /**
     * <b>守护什么</b>：{@link SessionKeys#NO_SESSION} 哨兵 = 「本处确实没有会话上下文」
     * （批 6 命名的显式出口）⇒ 与 {@code sessionId == null} <b>同路由</b>（命名无会话出口），
     * ⛔ 不被当成「会话已删」（{@code unknown}）而被<b>抛</b>掉 —— 那是「本该有却没有」的
     * 数据链路异常态，与「本来就不该有会话」是**正相反**的两件事。
     *
     * <p><b>反向实验</b>：把哨兵路由改走 {@code unknown} 腿（= 让
     * {@code SessionProjectRoot.lookup} 对哨兵不短路）⇒ 本断言红（变成抛异常而非命名出口值）。
     */
    @Test
    @DisplayName("显式「确无会话」哨兵 no-session：与 sessionId null 同路由（命名出口）")
    void noSessionSentinel_sameRouteAsNull() {
        SessionProjectRoot.setDbResolver(id -> SessionProjectRoot.Lookup.sessionlessEnvironment());

        CapturingLauncher launcher = new CapturingLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("", "", 0));
        executeOnce(productionWiring(launcher),
            HookEvent.toolPre("Bash", null, SessionKeys.NO_SESSION, null), STUB_HOOK_CWD);

        assertThat(launcher.lastSpec).isNotNull();
        assertThat(launcher.lastSpec.env().get("CLAUDE_PROJECT_DIR"))
            .as("哨兵与 null 同为「确无会话」⇒ 同走命名出口，不得当成「会话已删」而跳过")
            .isEqualTo(expectedHookPath(System.getProperty("user.dir")));
        assertThat(warnings())
            .as("哨兵路径也必须留 ≥WARN")
            .anyMatch(m -> m.contains("[CommandHookExecutor] CLAUDE_PROJECT_DIR 无会话态"));
    }
}
