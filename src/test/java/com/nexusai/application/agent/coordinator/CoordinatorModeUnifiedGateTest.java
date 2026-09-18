package com.nexusai.application.agent.coordinator;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder.EffectivePromptOptions;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.subagent.AgentSummaryHandle;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentConfig;
import com.nexusai.application.agent.subagent.ForkSubagentConfigBootstrap;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * coordinator 三层激活来源收敛 · <b>每一处门都必须吃 DB 覆盖</b>
 * （DB {@code settings.coordinator_mode_enabled} 有值即用；null 才回落 feature && env）。
 *
 * <h2>WHY 存在（CLAUDE.md 规则 9：测试验证意图，不是验证行为）</h2>
 * <p>coordinator 模式的<b>唯一用户可达激活路径</b>是前端「设置 → 环境配置 → 协调者模式」勾选框
 * （写 {@code settings.coordinator_mode_enabled}，经 {@link PromptAlignSettingsResolver#coordinatorModeEnabled()}
 * 实时读）；{@code nexusai.feature.coordinator-mode} 在 application.yml 恒 {@code false} 且<b>没有任何
 * UI / 接口</b>可改。
 *
 * <p>此前该 DB 覆盖只落到<b>部分</b>消费点（提示词分支 / userContext），其余仍只读
 * {@link CoordinatorMode#isCoordinatorMode()}（feature && env）⇒ <b>半激活</b>：
 * 系统提示已切成协调者版（要求 {@code subagent_type: "worker"}），但
 * <ul>
 *   <li>顶层工具池没被裁（模型仍见 Bash/Read/Edit，编排意图落空）</li>
 *   <li>权限链没变（{@code awaitAutomatedChecksBeforeDialog} 恒 false）</li>
 *   <li>子代理 summary / fork 互斥门没变</li>
 * </ul>
 * 本类把「DB-only 激活」（DB=true + feature=false + env 未设）当作<b>每一处门的必测场景</b>，
 * 逐门断言 —— 任一门口漏吃 DB 层，对应用例即红。
 *
 * <h2>夹具（对齐本项目 test-env-failloud-unreachable 教训）</h2>
 * <p>测试环境里 coordinator 门<b>结构上不可达</b>：application.yml 默认
 * {@code nexusai.feature.coordinator-mode: false}，env {@code CLAUDE_CODE_COORDINATOR_MODE} 也不设。
 * 故本类<b>不读</b>真实 env / Spring 环境，一律经显式注入（{@code @Autowired(required=false)} 同款落点
 * / setter / 静态槽）设置门，并在 {@code @AfterEach} 逐项复位。
 * 「跑了但门从没真开过」这类假绿在本类不可能发生 —— 开门的那一行就在用例里。
 *
 * <p>回落层夹具恒用 2 参构造器 {@code new CoordinatorMode(() -> feature, () -> env)}：
 * <b>feature=false + env 未设</b>就是「半激活」场景的回落层取值。
 */
@DisplayName("coordinator 三层激活来源收敛 · DB 覆盖必须落到每一处门")
class CoordinatorModeUnifiedGateTest {

    @TempDir
    Path tmpDir;

    // ════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════

    /** DB 层（mock，仅 coordinatorModeEnabled 有值，其余列 null）· 对齐 BuiltInAgentsCoordinatorTest 范式。 */
    private static PromptAlignSettingsResolver dbResolver(Boolean coordinatorDbValue) {
        PromptAlignSettingsResolver r = mock(PromptAlignSettingsResolver.class);
        when(r.coordinatorModeEnabled()).thenReturn(coordinatorDbValue);
        // [coordinator-session V75] 会话层必须显式 stub 成 null（= 该会话未设置）。
        //   ⚠️ 不 stub 会得 **FALSE** —— Mockito 对返回包装类型 Boolean 的方法默认返回
        //   Primitives.defaultValue(=false)，而非 null ⇒ 被读成「该会话显式关」压掉 DB/env 层，
        //   本类 DB-only 用例全红（实跑踩过：5 例红）。本类是「两层」测试，会话层一律缺席。
        when(r.sessionCoordinatorMode(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        return r;
    }

    /** feature/env 回落层 · 2 参构造器（不依赖真实 Spring Environment / env 变量）。 */
    private static CoordinatorMode envFeatureLayer(boolean feature, String env) {
        return new CoordinatorMode(() -> feature, () -> env);
    }

    /** 半激活场景的回落层：feature=false + env 未设 → isCoordinatorMode()=false。 */
    private static CoordinatorMode envFeatureOff() {
        return envFeatureLayer(false, null);
    }

    /** 反向场景的回落层：feature=true + env="1" → isCoordinatorMode()=true。 */
    private static CoordinatorMode envFeatureOn() {
        return envFeatureLayer(true, "1");
    }

    /** 静态槽位跨用例串味是这类测试的头号假绿来源 → 逐项复位。 */
    @AfterEach
    void resetStaticSeams() {
        PromptAlignSettingsResolver.setStaticResolver(null);   // DB 层（统一判定读源）
        com.nexusai.application.agent.subagent.BuiltInAgents.setPromptAlignSettingsResolver(null);
        com.nexusai.application.agent.subagent.BuiltInAgents.setCoordinatorMode(null);
        LlmAgentLoop.setCoordinatorMode(null);                 // 主循环 feature+env 回落槽
        ForkSubagent.setCoordinatorModeSupplier(null);
        ForkSubagent.syncRuntimeGate(true, false, false);
        ForkSubagentConfig.register(null);
        MemoryBareModeConfig.reset();                          // bare 门全局单例
    }

    // ── 主循环工具池夹具（对齐 LlmAgentLoopCoordinatorFilterTest） ──

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    private static ToolUseContext tuc(List<Tool> availableTools) {
        return new ToolUseContext(
            UUID.randomUUID(),
            "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            Map.of(),
            availableTools,
            null,
            AbortController.NOOP,
            List.of(),
            null,
            PermissionMode.DEFAULT);
    }

    private static List<Tool> fullAllPool() {
        return List.of(
            tool("Agent"), tool("TaskStop"), tool("SendMessage"), tool("StructuredOutput"),
            tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"),
            tool("github.com.mycorp.subscribe_pr_activity"),
            tool("pr_events.unsubscribe_pr_activity"),
            tool("mcp__random.server.tool"));
    }

    private static List<String> schemaNames(ArrayNode schema) {
        return java.util.stream.StreamSupport.stream(schema.spliterator(), false)
            .map(n -> n.path("function").path("name").asText())
            .toList();
    }

    private static boolean invoke(Class<?> owner, String method, Object target) throws Exception {
        Method m = owner.getDeclaredMethod(method);
        m.setAccessible(true);
        return (Boolean) m.invoke(target);
    }

    /**
     * 单会话身份形参的重载调用（[coordinator-session V75] 起 {@code isAwaitAutomatedChecksBeforeDialog}
     * 收 sessionId 以并入会话列层）。
     *
     * <p>本类用例只验两层（DB / feature+env），故传 {@code null} = <b>无会话身份</b> → 会话层缺席，
     * 判定退化回两层链（与加会话层前逐位同值）。会话列层的行为由
     * {@code CoordinatorSessionModeTest} 专测。
     */
    private static boolean invokeWithSession(Class<?> owner, String method, Object target,
                                             String sessionId) throws Exception {
        Method m = owner.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(target, sessionId);
    }

    // ════════════════════════════════════════════════════════════════════
    // 0. 唯一判定本身（全仓唯一入口 · 四象限）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("唯一判定四象限: DB 有值即用（true/false 都覆盖回落层）；DB null 才回落 feature+env")
    void unifiedJudgment_fourQuadrants() {
        // DB=true + feature/env 关 → true（半激活场景：唯一用户可达路径）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(true), envFeatureOff()))
            .as("DB=true 必须压过 feature/env 的 false（否则前端勾选无效）").isTrue();
        // DB=false + feature/env 真 → false（DB 显式关也是"有值"）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(false), envFeatureOn()))
            .as("DB=false 必须压过 feature/env 的 true").isFalse();
        // DB=null → 回落 feature/env（真）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(null), envFeatureOn()))
            .as("DB 未配置 → 回落 CC 原判定链（feature && env）").isTrue();
        // DB=null → 回落 feature/env（假）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(null), envFeatureOff()))
            .as("DB 未配置 + feature/env 关 → 不激活（默认关）").isFalse();
        // 无 DB 源（非 Spring / 未接线）+ 无回落层 → fail-closed
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(null, null))
            .as("两层都缺失 → false（fail-closed，不得默认开）").isFalse();
    }

    @Test
    @DisplayName("静态槽位重载: DB 读源 = ToolRegistrationConfig 接线的 staticResolver")
    void staticOverload_readsStaticResolverSlot() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        assertThat(PromptAlignSettingsResolver.staticCoordinatorModeActive(envFeatureOff()))
            .as("staticCoordinatorModeActive 必须读 staticResolver 槽（无 ctx 的静态门唯一入口）").isTrue();

        PromptAlignSettingsResolver.setStaticResolver(null);
        assertThat(PromptAlignSettingsResolver.staticCoordinatorModeActive(envFeatureOff())).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 主循环顶层工具池（半激活主症：提示词已切协调者版，工具池却没裁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("工具池: DB=true + feature/env 关 → 顶层池仍被裁为协调者白名单（此前只读 env+feature ⇒ 漏裁）")
    void llmToolsArray_dbTrueEnvFalse_trimsToCoordinatorWhitelist() {
        // GIVEN: 前端勾选（DB=1）+ application.yml feature 恒 false + env 未设
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOff());

        // WHEN
        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        // THEN: coordinator 白名单（USER 可见集 ∪ PR 订阅工具）；Bash/Read/Edit/普通 MCP 必须消失
        assertThat(schemaNames(schema))
            .as("DB-only 激活时顶层工具池必须裁剪（否则系统提示让模型编排、模型却仍见执行工具 = 半激活）")
            .containsExactlyInAnyOrder(
                "Agent", "TaskStop", "SendMessage",
                "github.com.mycorp.subscribe_pr_activity",
                "pr_events.unsubscribe_pr_activity")
            .doesNotContain("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("工具池: DB=false 覆盖 feature/env=true → 不裁剪（DB 有值即用，反向也要成立）")
    void llmToolsArray_dbFalseEnvTrue_notTrimmed() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));
        LlmAgentLoop.setCoordinatorMode(envFeatureOn());

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
            .as("DB 显式 false 必须压过 env+feature 的 true（若忽略 DB 层则此处只剩白名单 → 红）")
            .contains("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool")
            .doesNotContain("StructuredOutput");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. bare（Web 精简模式）工具池追加
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bare 池: DB=true + feature/env 关 → 追加编排三工具并裁为白名单（此前漏追加）")
    void bareTools_dbTrueEnvFalse_appendsOrchestrationTools() {
        new MemoryBareModeConfig(true);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOff());

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
            .as("bare 分支追加 [Agent,TaskStop,SendMessage] 的判据必须与工具池裁剪同源（DB 覆盖链），"
                + "否则 DB-only 激活下 bare 池缺编排三工具 → 顶层池被 bare 裁成空/缺项")
            .containsExactlyInAnyOrder("Agent", "TaskStop", "SendMessage");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. SubagentTool（prompt / shouldRunAsync / fork 互斥三处共用 helper）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SubagentTool: DB=true + feature/env 关 → isCoordinatorMode()=true（此前只读 bean ⇒ false）")
    void subagentTool_dbTrueEnvFalse_coordinatorTrue() throws Exception {
        SubagentTool t = new SubagentTool();
        t.setCoordinatorModeBean(envFeatureOff());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        assertThat(invoke(SubagentTool.class, "isCoordinatorMode", t))
            .as("AgentTool prompt/shouldRunAsync/fork gate 三处共用本 helper：漏 DB 层 ⇒ 提示按 coordinator、"
                + "异步判定按普通 agent、fork 互斥失效")
            .isTrue();
    }

    @Test
    @DisplayName("SubagentTool: DB=false 覆盖 feature/env=true → isCoordinatorMode()=false")
    void subagentTool_dbFalseEnvTrue_coordinatorFalse() throws Exception {
        SubagentTool t = new SubagentTool();
        t.setCoordinatorModeBean(envFeatureOn());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        assertThat(invoke(SubagentTool.class, "isCoordinatorMode", t)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 权限链（awaitAutomatedChecksBeforeDialog）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("权限链: DB=true + feature/env 关 → awaitAutomatedChecksBeforeDialog=true（此前 coordinatorMode 为 null ⇒ false）")
    void permissionContextBuilder_dbTrueEnvFalse_awaitChecksTrue() throws Exception {
        // GIVEN: 无 bean（未注入 / 直构）—— 旧实现 `coordinatorMode != null && ...` 在此恒 false
        PermissionContextBuilder b = new PermissionContextBuilder();
        ReflectionTestUtils.setField(b, "coordinatorMode", null);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        assertThat(invokeWithSession(PermissionContextBuilder.class, "isAwaitAutomatedChecksBeforeDialog", b, null))
            .as("DB-only 激活时权限链必须同步切协调者语义（CC runAgent.ts:457-464），"
                + "否则「提示已协调者、权限仍普通 agent」= 半激活")
            .isTrue();
    }

    @Test
    @DisplayName("权限链: DB=false 覆盖 feature/env=true → awaitAutomatedChecksBeforeDialog=false")
    void permissionContextBuilder_dbFalseEnvTrue_awaitChecksFalse() throws Exception {
        PermissionContextBuilder b = new PermissionContextBuilder();
        ReflectionTestUtils.setField(b, "coordinatorMode", envFeatureOn());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        assertThat(invokeWithSession(PermissionContextBuilder.class, "isAwaitAutomatedChecksBeforeDialog", b, null))
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 子代理周期摘要门（ASYNC 分路径：coordinator || fork || sdk）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("摘要门: DB=true + feature/env 关 → ASYNC 路径触发（此前 coordinator=false ⇒ 不触发）")
    void maybeStartSummary_dbTrueEnvFalse_asyncTriggers() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, envFeatureOff(), scheduler);
        try {
            assertThat(h).as("DB-only 激活下 coordinator 分路径门必须为真（与启用的 feature 面同源）").isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("摘要门: DB=false 覆盖 feature/env=true → ASYNC 路径不触发")
    void maybeStartSummary_dbFalseEnvTrue_asyncNotTriggered() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, envFeatureOn(), scheduler);
        try {
            assertThat(h).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private AgentSummaryHandle start(SubagentExecutor.SummarySpawnPath path, CoordinatorMode coordinator,
                                     ScheduledExecutorService scheduler) {
        ForkSubagent.syncRuntimeGate(false, false, false);   // fork/sdk 两项关 → 只剩 coordinator 项
        AgentSummaryService svc = new AgentSummaryService(60_000, scheduler);
        return SubagentExecutor.maybeStartSummary(
            path, null, svc, coordinator, false, "unified-gate-agent", tmpDir, "session-1",
            new LlmProviderFactory(), ProviderConfig.empty(), "test-model",
            null, null, null);
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-coordinator-unified-gate");
            t.setDaemon(true);
            return t;
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. fork 互斥门（CC forkSubagent.ts:34 !isCoordinatorMode()）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 互斥: Bootstrap 注册的判定源 DB=true + feature/env 关 → fork gate 关（此前 supplier 只读 bean ⇒ 仍开）")
    void forkGate_dbTrueEnvFalse_disabled() {
        // GIVEN: feature-on=true（fork 主开关开）+ coordinator 回落层关 + DB 勾选
        ForkSubagentConfig config = new ForkSubagentConfig(true, false, false);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        new ForkSubagentConfigBootstrap(config, envFeatureOff());

        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("coordinator 与 fork 是互斥模式（forkSubagent.ts:34）：DB-only 激活下 fork 必须关，"
                + "否则同一会话既铺 coordinator 提示又走 fork 分支")
            .isFalse();
    }

    @Test
    @DisplayName("fork 互斥: Bootstrap 注册的判定源 DB=false 覆盖 feature/env=true → fork gate 开")
    void forkGate_dbFalseEnvTrue_enabled() {
        ForkSubagentConfig config = new ForkSubagentConfig(true, false, false);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        new ForkSubagentConfigBootstrap(config, envFeatureOn());

        assertThat(ForkSubagent.isForkSubagentEnabled()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. 提示词门（既有 DB 覆盖链 · 回归件）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("提示词门: DB=true + feature/env 关 → coordinatorModeEnabled=true（顶层循环）")
    void promptOptions_dbTrueEnvFalse_coordinatorEnabled() throws Exception {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        ctx.sessionState().setPromptAlignSettingsResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOff());

        Method m = LlmAgentLoop.class.getDeclaredMethod("buildEffectivePromptOptions",
            AgentLoopContext.class, ToolUseContext.class, QuerySource.class);
        m.setAccessible(true);
        EffectivePromptOptions opts = (EffectivePromptOptions) m.invoke(null, ctx, null, QuerySource.REPL_MAIN_THREAD);

        assertThat(opts.coordinatorModeEnabled()).isTrue();
        assertThat(opts.mainThreadInvocation()).isTrue();
    }
}
