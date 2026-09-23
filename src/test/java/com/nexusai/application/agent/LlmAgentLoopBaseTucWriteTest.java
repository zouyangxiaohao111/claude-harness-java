package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.InitialPermissionModeResolver;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.SessionReadFileStateRegistry;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [F-19 · G5-fake-guards] {@code buildBaseToolUseContext} 的<b>写入端</b>：
 * {@code state.sessionId → TUC.sessionId}、{@code runExplicitCwd → TUC.effectiveCwd}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：本仓无 AsyncLocalStorage 且会话态禁经
 * ThreadLocal 传递（仓库铁律），所以「本回合 cwd」只能作为<b>值</b>从 {@code RunRequest} 一路显式
 * 传到 base TUC（{@code runExplicitCwd}），再派生到 per-turn TUC；{@code sessionId} 必须由
 * {@code state.sessionId()} 显式写进 TUC（{@link ToolUseContext} 的非空不变量：sessionId 为 null 时
 * compact ctor 抛 {@code IllegalArgumentException}）。CC <b>无对应物</b>（其 ToolUseContext 由
 * {@code runAgent} 一次性构造，判据只能从本仓架构约束导出，⛔ 不从 CC 抄）。
 *
 * <p><b>两层互补（各自的变异互不干扰）</b>
 * <ul>
 *   <li><b>Tier A（写点直断言）</b>：反射调 6 参 {@code buildBaseToolUseContext} 直传
 *       {@code sessionId}/{@code runExplicitCwd} ⇒ 守「字段被原样写进 TUC」。</li>
 *   <li><b>Tier B（端到端写侧）</b>：真实 {@code run(RunRequest.withBoundProject)} 后读
 *       {@code getCurrentToolUseContext()} ⇒ 守<b>调用点</b>
 *       （{@code doRun} 里 {@code runExplicitCwd = boundProject != null ? this.workspaceDir : null}
 *       那一行三元）与「per-turn TUC 保留 effectiveCwd」两件事。</li>
 * </ul>
 * 变异分辨力：把调用点三元改成恒 {@code null} ⇒ <b>Tier B 的 effectiveCwd 断言红、Tier A 仍绿</b>
 * （Tier A 直接传 anchor，不经过该三元）—— 这正是「A 守写点、B 守调用点」的实测证据。
 *
 * <p><b>⚠️ 签名漂移（复核记录）</b>：施工单写「反射取 <b>4 参</b>
 * {@code buildBaseToolUseContext(AgentState, Input, Config, Path)}」；S1（F-01/F-02）之后该重载已是
 * <b>6 参</b>：{@code (AgentState, Input, Config, Path runExplicitCwd, TeammateIdentity, AgentContext)}。
 * 本类按<b>精确参数类型表</b>取方法（⛔ 不用名字过滤 —— 同名 1 参便捷重载仍在，按名字取会静默测错
 * 重载，前车之鉴 R32B15Stage3_3）；arity 再变 ⇒ {@code getDeclaredMethod} 抛
 * {@code NoSuchMethodException} ⇒ 本类<b>变红</b>（不会静默测错）。
 */
@DisplayName("[F-19] buildBaseToolUseContext 写入端：sessionId / effectiveCwd")
class LlmAgentLoopBaseTucWriteTest {

    @TempDir
    Path tempDir;

    // ════════════════════════════ Tier A（写点直断言） ════════════════════════════

    /**
     * Tier A 主用例：sessionId / runExplicitCwd / agentId / systemPrompt / messages 五个字段的写点。
     *
     * <p><b>RED 条件</b>：把写点首参 {@code state.sessionId()} 改成 {@code null} ⇒
     * {@link ToolUseContext} compact ctor 的 sessionId 非空不变量抛
     * {@code IllegalArgumentException} ⇒ 本用例以<b>异常</b>变红。
     */
    @Test
    @DisplayName("Tier A 写点：sessionId 原样写入 · effectiveCwd=runExplicitCwd · agentId/systemPrompt/messages 直搬")
    void tierA_writePoint_sessionIdAndEffectiveCwd() throws Exception {
        Path anchor = canonical(Files.createDirectories(tempDir.resolve("tierA-anchor")));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("SYSPROMPT-A", sessionId, null /* 主线程 agentId=null */);
        // ⚠️ 弱断言修复（施工单 §7 复核补漏）：裸 AgentState 的 rawMessages() 为空 ⇒ 写点是
        //   `null ? List.of()`，两边都是 0 元素 ⇒ hasSameSizeAs 恒真、零鉴别力。此处夹具塞一条真消息，
        //   使「messages 直搬」具备可证伪性（下面同时断言非空）。
        state.appendMessage(userMessage("m1", "hello"));

        ToolUseContext base = invokeBuildBaseTuc(newLoop(), state, anchor);

        assertThat(base).as("sessionId 非 null ⇒ 必须返回完整 base TUC（非 null）").isNotNull();
        assertThat(base.sessionId())
            .as("写点：state.sessionId() → TUC.sessionId（原样搬运，⛔ 不得替换/派生）")
            .isEqualTo(sessionId);
        assertThat(base.effectiveCwd())
            .as("写点：runExplicitCwd → TUC.effectiveCwd（非空时不走构造器兜底，逐字节原样）")
            .isEqualTo(anchor);
        assertThat(base.agentId())
            .as("写点：state.agentId() → TUC.agentId。主线程（agentId=null）必须保持 null"
                + "（对齐 CC !context.agentId 主线程判定；effectiveAgentId 兜底已删，⚠️ 见本类"
                + "对照用例 LlmAgentLoopMainThreadToolsReachableTest）")
            .isNull();
        assertThat(base.renderedSystemPrompt())
            .as("写点：state.systemPrompt() → TUC.renderedSystemPrompt")
            .isEqualTo("SYSPROMPT-A");
        assertThat(base.messages())
            .as("写点：state.rawMessages() → TUC.messages（直搬，非空夹具）")
            .hasSize(state.rawMessages().size())
            .isNotEmpty();
    }

    /**
     * Tier A 对照：{@code runExplicitCwd=null} ⇒ 走 {@link ToolUseContext} 构造器兜底
     * {@link CwdResolution#getCwd(String)}（对齐 CC getCwd），<b>不等于</b>锚。
     *
     * <p><b>RED 条件</b>：若写点把 null 也塞成锚（例如误写
     * {@code runExplicitCwd != null ? runExplicitCwd : Path.of(anchor)}）⇒ 本用例红。
     */
    @Test
    @DisplayName("Tier A 对照：runExplicitCwd=null ⇒ effectiveCwd 回落 CwdResolution.getCwd(sessionId)，不等于锚")
    void tierA_nullRunExplicitCwd_fallsBackToCwdResolution() throws Exception {
        Path anchor = canonical(Files.createDirectories(tempDir.resolve("tierA-null-anchor")));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);

        ToolUseContext base = invokeBuildBaseTuc(newLoop(), state, null);

        assertThat(base.effectiveCwd())
            .as("runExplicitCwd=null ⇒ 构造器兜底 CwdResolution.getCwd(sessionId)"
                + "（override ?? sessionCwd ?? boundProject ?? user.dir）")
            .isEqualTo(Path.of(CwdResolution.getCwd(sessionId)));
        assertThat(base.effectiveCwd())
            .as("对照：null 入参不得被写成锚（排除「任何入参都得到锚」的假绿）")
            .isNotEqualTo(anchor);
    }

    /**
     * Tier A 守卫：{@code state.sessionId() == null} ⇒ 返回 {@code null}（生产首行守卫）。
     *
     * <p>该路径是 REPL / {@code RunRequest.user} 的既有行为（无工具上下文），与
     * {@code LlmAgentLoopMainThreadToolsReachableTest#repl_sessionIdNull_toolsStillAbsent} 同一契约，
     * 此处钉在<b>写点</b>层。
     */
    @Test
    @DisplayName("Tier A 守卫：sessionId=null ⇒ 返回 null（无会话不构造 TUC）")
    void tierA_nullSessionId_returnsNull() throws Exception {
        AgentState state = new AgentState("sys", null, null);
        assertThat(invokeBuildBaseTuc(newLoop(), state, canonical(tempDir)))
            .as("sessionId=null ⇒ base TUC 必须为 null（ToolUseContext compact ctor 对 null sessionId "
                + "抛 IllegalArgumentException ⇒ 守卫必须拦截）")
            .isNull();
    }

    /**
     * <b>[批 edit-gate-session-scope · B] readFileState 的「作用域写点」</b>：
     * {@code buildBaseToolUseContext} 必须按 {@code sessionId} 从会话注册表取表
     * （同会话跨 run 恒同一实例；不同会话不同实例）。
     *
     * <p><b>WHY（规则九 · 测意图）</b>：改造前实参为 {@code null} ⇒
     * {@link ToolUseContext} 紧凑构造器每次 run 新建一张表 ⇒ 同一会话两条消息互不可见对方
     * Read 过的文件 ⇒ 用户上一轮 Read 过、本轮 Edit 被门禁拒（errorCode 6/2/9）。
     * 本用例守的是「作用域」这一维：<b>同会话跨 run 必须是同一张表</b>。
     *
     * <p><b>RED 条件（变异分辨力）</b>：把注入实参改回 {@code null}
     * （{@code SessionReadFileStateRegistry.forSession(state.sessionId())} → {@code null}）
     * ⇒ 两个 loop 实例各得一张新表 ⇒ 第 2 条断言 {@code isSameAs} 红。
     * 反向：把 {@code forSession} 改成返回全局单例 ⇒ 第 3 条 {@code isNotSameAs} 红。
     * 两个 loop 是<b>不同实例</b>（{@code @Scope("prototype")} 的生产形态），
     * 故本用例不可能是「同一个 ctx 被传了两遍」的假绿。
     */
    @Test
    @DisplayName("Tier A 写点：readFileState 会话级 —— 同 sessionId 跨 loop 实例同一实例，异 sessionId 不同实例")
    void tierA_readFileState_isSessionScoped() throws Exception {
        Path anchor = canonical(Files.createDirectories(tempDir.resolve("tierA-readstate")));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        String otherSessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // 两次「run」= 两个**不同**的 LlmAgentLoop 实例（生产 prototype 语义）
            ToolUseContext run1 = invokeBuildBaseTuc(
                newLoop(), new AgentState("sys", sessionId, null), anchor);
            ToolUseContext run2 = invokeBuildBaseTuc(
                newLoop(), new AgentState("sys", sessionId, null), anchor);
            ToolUseContext other = invokeBuildBaseTuc(
                newLoop(), new AgentState("sys", otherSessionId, null), anchor);

            assertThat(run2)
                .as("两个 run 必须是不同的 TUC 对象（排除「同一 ctx 传两遍」的假绿）")
                .isNotSameAs(run1);
            assertThat(run2.readFileState())
                .as("readFileState 必须会话级：同 sessionId 跨 run 复用同一张表"
                    + "（改回 null ⇒ 本断言红）")
                .isSameAs(run1.readFileState());
            assertThat(other.readFileState())
                .as("按 sessionId 分区：不同会话不得共享（退化成全局单例 ⇒ 本断言红）")
                .isNotSameAs(run1.readFileState());
            assertThat(SessionReadFileStateRegistry.peek(sessionId))
                .as("该表必须真的注册在会话注册表里（而不是碰巧同一对象）")
                .isSameAs(run1.readFileState());
        } finally {
            // 静态表跨用例存活 ⇒ 本用例自建的会话必须自行回收（不依赖他类的 resetForTest）。
            SessionReadFileStateRegistry.evict(sessionId);
            SessionReadFileStateRegistry.evict(otherSessionId);
        }
    }

    // ════════════════════════ Tier B（端到端写侧 · 覆盖调用点） ════════════════════════

    /**
     * Tier B 主用例：真实 {@code run(RunRequest.withBoundProject)} ⇒ per-turn TUC 的 sessionId 与
     * effectiveCwd 都等于 run 携带的值。
     *
     * <p><b>RED 条件 A（打调用点三元）</b>：把 {@code LlmAgentLoop} 里
     * {@code runExplicitCwd = (params.boundProject() != null && ...) ? this.workspaceDir : null}
     * 改成恒 {@code null} ⇒ 本用例 effectiveCwd 断言红，而 <b>Tier A 三条仍绿</b>。
     */
    @Test
    @DisplayName("Tier B 端到端：run(withBoundProject) ⇒ per-turn TUC.sessionId=sessX 且 effectiveCwd=锚")
    void tierB_runWithBoundProject_perTurnTucCarriesSessionIdAndAnchor() throws Exception {
        Path anchor = canonical(Files.createDirectories(tempDir.resolve("tierB-anchor")));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(tucRef);

        AgentState state = loop.run(runRequest(sessionId).withBoundProject(anchor.toString()));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(tucRef.get())
            .as("per-turn TUC 必须被捕获（base TUC 完整构造 ⇒ toolExecContext 派生）").isNotNull();
        assertThat(tucRef.get().sessionId())
            .as("Tier B：state.sessionId → base TUC.sessionId → per-turn TUC.sessionId 全链原样保留")
            .isEqualTo(sessionId);
        assertThat(tucRef.get().effectiveCwd())
            .as("Tier B：run 携带的 boundProject → doRun 的 runExplicitCwd → base TUC.effectiveCwd → "
                + "per-turn TUC.effectiveCwd（per-turn 派生必须保留该值，不回落 CwdResolution）")
            .isEqualTo(anchor);
    }

    /**
     * Tier B 可选对照（sessionId 侧）：另一会话 ⇒ 另一 TUC.sessionId
     * （使「sessionId 只是被原样搬运」可被证伪 —— 排除「TUC.sessionId 是某个常量」）。
     */
    @Test
    @DisplayName("Tier B 对照：换 sessionId ⇒ per-turn TUC.sessionId 随之改变（非常量）")
    void tierB_differentSessionId_differentTucSessionId() {
        String sessionA = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionB = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<ToolUseContext> tucA = new AtomicReference<>();
        AtomicReference<ToolUseContext> tucB = new AtomicReference<>();

        newLoop(tucA).run(runRequest(sessionA));
        newLoop(tucB).run(runRequest(sessionB));

        assertThat(tucA.get().sessionId()).isEqualTo(sessionA);
        assertThat(tucB.get().sessionId()).isEqualTo(sessionB);
        assertThat(tucA.get().sessionId())
            .as("两个不同会话必须得到两个不同 TUC.sessionId（否则「原样搬运」不可证伪）")
            .isNotEqualTo(tucB.get().sessionId());
    }

    /**
     * Tier B 对照（修正后装置）：<b>新建 loop 实例</b> + 同形 RunRequest <b>不带</b>锚 ⇒
     * effectiveCwd 不等于锚（回落 CwdResolution）。
     *
     * <p><b>WHY 必须新建实例</b>：锚一旦落进 {@code workspaceDir} 就没有任何路径把它写回 null
     * （全类 {@code this.workspaceDir = } 仅 5 处赋值，无一处赋 null）；同实例第二次无锚 run 时
     * {@code runExplicitCwd} 三元取 {@code null} ⇒ base TUC 走构造器兜底 —— 这条腿本身没问题，
     * 但为了排除「锚残留」这一类混淆，用新实例做纯对照。
     */
    @Test
    @DisplayName("Tier B 对照：新建 loop + 无锚 run ⇒ effectiveCwd 不等于锚（回落 CwdResolution）")
    void tierB_runWithoutBoundProject_freshLoop_effectiveCwdNotAnchor() throws Exception {
        Path anchor = canonical(Files.createDirectories(tempDir.resolve("tierB-control-anchor")));
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        // 正向半边：同一装置在有锚时确实能检出锚（证明下面的否定断言具备判别力）
        AtomicReference<ToolUseContext> anchoredRef = new AtomicReference<>();
        newLoop(anchoredRef).run(runRequest(sessionId).withBoundProject(anchor.toString()));
        assertThat(anchoredRef.get().effectiveCwd())
            .as("正向半边：有锚 run 的 per-turn TUC.effectiveCwd 必须 = 锚（装置的判别力证明）")
            .isEqualTo(anchor);

        // 无锚的**新**实例（唯一变量 = 锚）
        AtomicReference<ToolUseContext> freshRef = new AtomicReference<>();
        newLoop(freshRef).run(runRequest(sessionId));

        assertThat(freshRef.get()).isNotNull();
        assertThat(freshRef.get().effectiveCwd())
            .as("无锚 run 不得得到锚值（锚不是「任何 run 都会产生」的常量）")
            .isNotEqualTo(anchor);
        assertThat(freshRef.get().effectiveCwd())
            .as("无锚 run ⇒ effectiveCwd 回落 CwdResolution.getCwd(sessionId)"
                + "（⛔ 不回落 configHome 冒充项目根）")
            .isEqualTo(Path.of(CwdResolution.getCwd(sessionId)));
    }

    // ── helpers ──────────────────────────────────────────────

    /**
     * 反射调 private 7 参 {@code buildBaseToolUseContext}。
     *
     * <p>⚠️ 必须按<b>精确参数类型表</b>取（0-arg 便捷重载 + 同名 1 参版本仍在）：按名字过滤取第一个
     * 会静默测错重载。arity 漂移 ⇒ {@link NoSuchMethodException} ⇒ 本类变红（fail loud）。
     *
     * <p>[批 rfs-replay-3b] arity 6 → 7：新增 {@code List<ChatMessageDto> dbTranscript}
     * （跨进程 readFileState replay 的原料）。本反射点按契约同步；传 null = 该路径无 DB 历史
     * ⇒ replay 整段软降级跳过（本类关心的是 sessionId / effectiveCwd 写入端，与 replay 无关）。
     */
    private static ToolUseContext invokeBuildBaseTuc(LlmAgentLoop loop, AgentState state, Path runExplicitCwd) {
        try {
            Method m = LlmAgentLoop.class.getDeclaredMethod("buildBaseToolUseContext",
                AgentState.class,
                InitialPermissionModeResolver.Input.class,
                InitialPermissionModeResolver.Config.class,
                Path.class,
                TeammateIdentity.class,
                AgentContext.class,
                List.class);
            m.setAccessible(true);
            return (ToolUseContext) m.invoke(loop, state,
                InitialPermissionModeResolver.Input.empty(),
                InitialPermissionModeResolver.Config.defaults(),
                runExplicitCwd,
                null,   // teammateIdentity：本路径不存在 teammate 承载体（见 doRun 注释）
                null,   // agentContext：主线程 / cron 无归因（等价 CC 主线程 undefined）
                null);  // dbTranscript：无 DB 历史 ⇒ replay 软降级跳过（rfs-replay-3b）
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "反射调用 7 参 buildBaseToolUseContext 失败（签名漂移？本类按精确参数表取方法，"
                    + "arity 变更必须同步本反射点）", e);
        }
    }

    /** 裸 loop（无 toolRegistry · mocked provider 工厂，Tier A 不需要驱动 run）。 */
    private static LlmAgentLoop newLoop() {
        return new LlmAgentLoop(mock(LlmProviderFactory.class));
    }

    /**
     * Tier B 夹具：真实 loop + mocked provider + 真实 ToolRegistry，捕获 per-turn TUC
     * （镜像 {@code RevFix2ProductionInputWiringTest#newLoop} 与
     * {@code LlmAgentLoopMainThreadToolsReachableTest#newLoop}）。
     *
     * <p>⚠️ provider 桩逐参对齐 {@code LlmProvider.stream} 的<b>当前</b> arity（3 个重载 = 19/19/20）：
     * 本 19 参桩命中<b>抽象 blocks 重载</b>（位置 3 = {@code anyList()}），位置索引
     * {@code getArgument(9/10/16)} = onChunk / onAssistantMessage / onComplete。
     */
    private static LlmAgentLoop newLoop(AtomicReference<ToolUseContext> tucRef) {
        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        doAnswer(inv -> {
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from F-19 Tier B");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return loop;
    }

    /** 同形 RunRequest（主线程 agentId=null；锚由 {@code withBoundProject} 单独施加，便于做对照）。 */
    private static RunRequest runRequest(String sessionId) {
        return RunRequest.session("hello", sessionId, null,
            ProviderConfig.empty(), "test-model", null, null);
    }

    /** 与生产 {@code LlmAgentLoop.normalizeSessionProjectRoot} 同形的归一（realpath + NFC）。 */
    private static Path canonical(Path p) throws java.io.IOException {
        return Path.of(ClaudePaths.normalizeNfc(p.toRealPath().toString()));
    }

    private static ChatMessageDto userMessage(String id, String text) {
        return new ChatMessageDto(id, null, Role.user, "user", text, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
