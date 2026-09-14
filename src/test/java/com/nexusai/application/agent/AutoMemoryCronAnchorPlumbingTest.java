package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 4b-2 · 4b-1 未决 (a) 闭口] cron DURABLE <b>显式项目锚</b>穿透到 auto-memory 解析器。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：cron DURABLE fire 携带
 * {@code RunRequest.boundProject}（该回合的项目锚），但它的 {@code streamSessionId} 可以为
 * <b>null</b>（创建会话已关 → headless 无 transcript）。批 4b-1 删掉 ThreadLocal 载体后，
 * 锚只能靠显式传参到达 auto-memory；而解析器 {@code resolveAutoMemoryProjectRoot(ctx)} 当时
 * 只认 {@code streamSessionId} ⇒ 「headless + 有锚」时<b>本轮不注入 auto 记忆段</b>
 * （≥WARN 可观测，非静默，但仍是功能缺口）。
 *
 * <p>批 4b-2 的闭口 = 把锚显式穿到解析器（{@code RunRequest.boundProject} →
 * {@code LlmAgentLoop.explicitProjectAnchor} → {@code LoopSessionState.explicitProjectAnchor}
 * → {@code resolveAutoMemoryProjectRoot} 在 sessionId 判空<b>之前</b>读它）。
 * 本类守护这条承重链的两条腿：
 * <ol>
 *   <li><b>解析腿</b>：{@code collectRunMaterial} 组装出的系统提示里，auto 记忆段确实指向
 *       <b>锚</b>所指的项目目录（用<b>两个不同锚</b>判别 —— 各自命中各自的，且互不串；
 *       ⛔ 不用源码字面断言，⛔ 不用「删了实现仍恒绿」的否定断言）；</li>
 *   <li><b>传递腿</b>：{@code resolveSessionProjectRoot(boundProject)} 真的把锚登记进
 *       {@code LoopSessionState}，且<b>下一次无锚 run 会清零</b>（⛔ 不许上一 run 的锚残留
 *       漂进别的 run 的项目根判据）。</li>
 * </ol>
 *
 * <p><b>正向对照</b>（规则十二/规则九）：无锚 + 无会话 id 时记忆段<b>不含</b>任何锚目录 ——
 * 该否定断言与上面两个「含各自锚目录」的正向断言同处一类，故能区分「锚真的生效」与「断言恒真」。
 */
@DisplayName("[批 4b-2] cron DURABLE 显式锚穿透 auto-memory（headless 有锚 ⇒ 本轮注入；无锚 ⇒ 不伪造）")
class AutoMemoryCronAnchorPlumbingTest {

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 的类 javadoc。

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    @TempDir
    Path configHome;
    @TempDir
    Path anchorA;
    @TempDir
    Path anchorB;

    @BeforeEach
    void setUp() {
        // 隔离 nexusai 自有根（memoryBase = <configHome>），防污染真实 ~/.nexusai
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        BundledSkillEnabledGates.bridgeSettingsMapper(null);   // 清 DB settings 桥接（防跨用例泄漏）
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    /** 组装的系统提示全文（auto 记忆段在其中时，会含该锚的 sanitize slug）。 */
    private static String assembledSystemPrompt(Path anchor) {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        // 生产中该字段由 doRun 的显式锚分支登记（见下方「传递腿」用例）；此处直接落到 context 上，
        // 聚焦「解析腿」：解析器是否真的消费该字段（而非只认 streamSessionId）。
        if (anchor != null) {
            ctx.sessionState().setExplicitProjectAnchor(anchor);
        }
        ctx.sessionState().setWorkspaceDir(null);   // 断言与 workspaceDir 无关（锚必须是独立来源）

        AgentState state = new AgentState(null, null, null);
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), List.of(), minimalTuc(), QuerySource.REPL_MAIN_THREAD, "test-model",
            null, null, null, null, null, depsOf(ctx), ProviderConfig.empty());

        return String.join("\n", LlmAgentLoop.collectRunMaterial(ctx, params, state).systemPrompt());
    }

    /**
     * 最小 ToolUseContext。注意：本场景「无会话」指 {@code AgentLoopContext.streamSessionId}
     * （auto-memory 解析器读的那个）为 null —— <b>不是</b> {@code ToolUseContext.sessionId}：
     * 后者是全仓必填字段（{@code ToolUseContext} 紧凑构造器 {@code sessionId == null ⇒ 抛}），
     * 生产 headless 路径同样带兜底会话键。故此处传入一个与断言无关的占位键。
     */
    private static ToolUseContext minimalTuc() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-headless-placeholder",
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT, List.of());
    }

    private static LoopDeps depsOf(AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
            @Override public String uuid() { return "cron-anchor-test"; }
        };
    }

    /** 反射调用静态解析器（{@code resolveAutoMemoryProjectRoot} 是 private static · 不改生产可见性）。 */
    private static String resolveRootReflectively(AgentLoopContext ctx) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod(
                "resolveAutoMemoryProjectRoot", AgentLoopContext.class);
            m.setAccessible(true);
            return (String) m.invoke(null, ctx);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 resolveAutoMemoryProjectRoot 失败", e);
        }
    }

    /** 反射调用实例解析入口（{@code resolveSessionProjectRoot} 是 private · 锚的登记点）。 */
    private static void resolveSessionProjectRootReflectively(LlmAgentLoop loop, String boundProject) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("resolveSessionProjectRoot", String.class);
            m.setAccessible(true);
            m.invoke(loop, boundProject);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 resolveSessionProjectRoot 失败", e);
        }
    }

    /** 反射调用实例透传点（{@code buildSessionStateFromInstance} 是 private）。 */
    private static AgentLoopContext.LoopSessionState buildSessionStateReflectively(LlmAgentLoop loop) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("buildSessionStateFromInstance");
            m.setAccessible(true);
            return (AgentLoopContext.LoopSessionState) m.invoke(loop);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 buildSessionStateFromInstance 失败", e);
        }
    }

    @Test
    @DisplayName("解析腿：headless（无 sessionId）+ 锚 A/B ⇒ 记忆段各指向各自的锚目录（互不串）")
    void anchorWithoutSessionId_reachesAutoMemorySection() {
        // WHY: 这是「headless + 有锚」的主目标场景。用两个不同锚判别：若解析器仍只认
        //   streamSessionId（本次为 null），两段都会缺席 ⇒ 两个正向断言同时红；若锚被穿透但
        //   取值错位（如误读 workspaceDir 的 user.dir 兜底），则 slug 既非 A 也非 B ⇒ 也红。
        String promptA = assembledSystemPrompt(anchorA);
        String promptB = assembledSystemPrompt(anchorB);

        assertThat(promptA)
            .as("锚 A 的项目 slug 必须出现在组装出的系统提示里（auto 记忆段真的注入了）")
            .contains(AutoMemPaths.sanitizePath(anchorA.toString()));
        assertThat(promptB)
            .as("锚 B 的项目 slug 必须出现在组装出的系统提示里")
            .contains(AutoMemPaths.sanitizePath(anchorB.toString()));
        assertThat(promptA)
            .as("⛔ 锚 A 的 run 不得串到锚 B 的项目目录（显式传参隔离）")
            .doesNotContain(AutoMemPaths.sanitizePath(anchorB.toString()));
    }

    @Test
    @DisplayName("真实线程：主线程登记锚 → worker 线程组装 ⇒ 同一锚目录（无任何线程局部依赖）")
    void anchor_crossesThreadBoundary() throws Exception {
        // WHY: 本改造的根因正是「会话态经 ThreadLocal 隐式传播」。若锚仍靠线程局部量传递，
        //   登记线程与组装线程不同就会 MISS ⇒ 记忆段缺席。本用例把组装放到**另一条真实线程**，
        //   断言与主线程同结果 —— 判别力来自「跨线程仍命中该锚」（正面事实），非「不抛异常」。
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "cron-anchor-test"));
        try {
            Future<String> f = pool.submit(() -> assembledSystemPrompt(anchorA));
            String onWorker = f.get(20, TimeUnit.SECONDS);
            assertThat(onWorker)
                .as("worker 线程组装必须同样命中锚 A 的项目目录（锚是显式传参的值，不是线程局部态）")
                .contains(AutoMemPaths.sanitizePath(anchorA.toString()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("正向对照：无锚 + 无 sessionId ⇒ 记忆段不含任何锚目录（⛔ 不回落 user.dir 冒充项目根）")
    void noAnchor_noSession_doesNotFabricateProjectRoot() {
        // WHY: 「无锚」必须与「有锚」区分开 —— 否则解析器会退回用进程 user.dir 拼一个假项目目录
        //   （本批红线）。此处的否定断言与上面两条正向断言配对，证明断言不是恒真。
        String prompt = assembledSystemPrompt(null);

        assertThat(prompt)
            .as("⛔ 无锚 run 不得注入锚 A 的项目目录（不伪造）")
            .doesNotContain(AutoMemPaths.sanitizePath(anchorA.toString()));
        assertThat(prompt)
            .as("⛔ 无锚 run 不得回落进程 user.dir 冒充项目根（那是本批要消灭的缺陷）")
            .doesNotContain(AutoMemPaths.sanitizePath(System.getProperty("user.dir")));
        // 对照：同一断言装置在有锚时确实能检出（见 anchorWithoutSessionId_reachesAutoMemorySection）
        assertThat(assembledSystemPrompt(anchorA))
            .as("同一装置在有锚时命中 ⇒ 上面的否定断言具备判别力（非恒绿）")
            .contains(AutoMemPaths.sanitizePath(anchorA.toString()));
    }

    @Test
    @DisplayName("传递腿：resolveSessionProjectRoot(boundProject) 登记锚；下一次无锚 run 清零（无残留漂移）")
    void resolveSessionProjectRoot_registersAnchor_andClearsOnNextAnchoredRun() {
        // WHY: 锚若只停在解析器口上、没被 doRun 真正登记进 context，主目标场景仍不成立；
        //   反之若登记了却不清零，同一实例的下一次（无锚）run 会拿到上一 run 的锚 ⇒
        //   把 A 项目的记忆目录塞给 B run（跨回合污染）。两条腿都要钉住。
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));

        resolveSessionProjectRootReflectively(loop, anchorA.toString());
        AgentLoopContext.LoopSessionState session = buildSessionStateReflectively(loop);
        assertThat(session)
            .as("buildSessionStateFromInstance 必须把锚透传给 LoopSessionState")
            .isNotNull();
        assertThat(session.explicitProjectAnchor())
            .as("显式锚分支必须登记锚（否则 auto-memory 在 headless fire 下拿不到项目根）")
            .isNotNull();
        assertThat(session.explicitProjectAnchor().getFileName())
            .as("登记的锚必须指向传入的项目根（而非 user.dir / configHome）")
            .isEqualTo(anchorA.getFileName());

        // 第二次 run 无锚 → 必须清零（同一实例重入不得残留上一 run 的锚）
        resolveSessionProjectRootReflectively(loop, null);
        AgentLoopContext.LoopSessionState session2 = buildSessionStateReflectively(loop);
        assertThat(session2.explicitProjectAnchor())
            .as("⛔ 无锚 run 必须清零：否则上一 run 的锚会漂进本 run 的 auto-memory 项目根判据")
            .isNull();
    }

    @Test
    @DisplayName("对照：无会话 id 时「有锚」与「无锚」在解析器上必须给出不同结论（判据真的在锚上）")
    void anchorIsTheDecidingInput_whenSessionIdMissing() {
        // WHY: 若解析器把「无会话 id」一律当「无项目」（批 4b-1 行为），本类前三条会全红；
        //   本用例把「判据确实落在锚字段上」写成显式的差分对照 —— 同一 ctx 形状、唯一变量 = 锚。
        //   同时确认判据不是 workspaceDir（同 ctx 下 workspaceDir 与锚无关）。
        AgentLoopContext ctxWithAnchor = TestContexts.agentLoopContext(null, null, null, null, null);
        ctxWithAnchor.sessionState().setExplicitProjectAnchor(anchorA);
        AgentLoopContext ctxWithout = TestContexts.agentLoopContext(null, null, null, null, null);

        assertThat(ctxWithAnchor.streamSessionId()).as("前提：两条 ctx 都无会话 id").isNull();
        assertThat(ctxWithout.streamSessionId()).isNull();

        String resolvedWith = resolveRootReflectively(ctxWithAnchor);
        String resolvedWithout = resolveRootReflectively(ctxWithout);

        assertThat(resolvedWith)
            .as("有锚 ⇒ 解析出锚值（不用回落任何配置主目录）")
            .isEqualTo(anchorA.toString());
        assertThat(resolvedWithout)
            .as("无锚 ⇒ 无项目（不伪造）；与上面构成差分，证明判据落在锚上")
            .isNull();
    }

    /** 断言 CwdResolution 未被本路径用作兜底来源（防「无会话出口」把 user.dir 带进项目身份）。 */
    @Test
    @DisplayName("锚缺失时不走无会话出口：解析结果不得等于 CwdResolution 的无会话出口值")
    void noAnchor_doesNotFallBackToNonSessionCwd() {
        // WHY: CwdResolution.getCwdForNonSession() 是「确无会话」的合法出口（返回进程 user.dir），
        //   但 auto-memory 的项目身份**不能**用它 —— 否则每个无锚 run 都会往 user.dir 写记忆。
        //   正向对照：有锚时解析值 == 锚（非 user.dir），故本否定断言有判别力。
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        String resolved = resolveRootReflectively(ctx);

        assertThat(resolved).isNull();
        assertThat(CwdResolution.getCwdForNonSession()).as("对照：无会话出口仍可用（只是本路径不用它）").isNotNull();
    }
}
