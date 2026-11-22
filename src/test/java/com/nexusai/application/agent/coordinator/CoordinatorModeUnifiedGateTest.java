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
 * coordinator 激活来源收敛 · <b>全局 DB 层已移除，每一处门都必须吃会话门 / feature+env</b>
 * （[2026-11-19 用户裁定] {@code settings.coordinator_mode_enabled} 不再参与判定；
 * 会话列 → env 两层，见 {@link PromptAlignSettingsResolver#coordinatorModeActive}；
 * exe 2.1.328 已删除 feature 门，回落层只看 {@code CLAUDE_CODE_COORDINATOR_MODE}）。
 *
 * <h2>WHY 存在（CLAUDE.md 规则 9：测试验证意图，不是验证行为）</h2>
 * <p>coordinator 模式的用户激活路径是输入框「协调者」chip（写 {@code sessions.coordinator_mode}）；
 * 设置页全局开关已删除，{@code settings.coordinator_mode_enabled} 即使有历史值也必须被忽略 ——
 * 本类里的 {@code dbResolver} 夹具现在专门用来证明「喂它也不生效」。
 *
 * <p>此前该 DB 覆盖只落到<b>部分</b>消费点（提示词分支 / userContext），其余仍只读
 * {@link CoordinatorMode#isCoordinatorMode()}（feature && env）⇒ <b>半激活</b>：
 * 系统提示已切成协调者版（要求 {@code subagent_type: "worker"}），但
 * <ul>
 *   <li>顶层工具池没被裁（模型仍见 Bash/Read/Edit，编排意图落空）</li>
 *   <li>权限链没变（{@code awaitAutomatedChecksBeforeDialog} 恒 false）</li>
 *   <li>子代理 summary / fork 互斥门没变</li>
 * </ul>
 * 本类把「feature+env 真」（静态门唯一可达激活源）当作<b>每一处门的必测场景</b>，逐门断言；
 * 同时用 {@code dbResolver(true/false)} 反证全局 DB 值已被忽略 —— 任一门口又把 DB 层接回来，对应用例即红。
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
@DisplayName("coordinator 激活来源收敛 · 全局 DB 层已移除，门只看会话 / feature+env")
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
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(),
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
    @DisplayName("唯一判定: 全局 settings 层已移除 —— DB 值不再参与，只有 feature+env 回落")
    void unifiedJudgment_dbLayerRemoved() {
        // [2026-11-19] 全局 coordinator_mode_enabled 已从判定链移除：DB=true 不再能激活
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(true), envFeatureOff()))
            .as("DB=true 已不参与判定（按会话激活）→ 回落 feature/env=false").isFalse();
        // DB=false 也不再压过 feature/env
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(false), envFeatureOn()))
            .as("DB=false 已不参与判定 → feature/env=true 生效").isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(null), envFeatureOn()))
            .as("回落 CC 原判定链（feature && env）").isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(dbResolver(null), envFeatureOff()))
            .as("feature/env 关 → 不激活（默认关）").isFalse();
        // 无回落层 → fail-closed
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(null, null))
            .as("两层都缺失 → false（fail-closed，不得默认开）").isFalse();
    }

    @Test
    @DisplayName("静态槽位重载: staticResolver 槽位已不参与判定（全局层移除）")
    void staticOverload_ignoresStaticResolverSlot() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        assertThat(PromptAlignSettingsResolver.staticCoordinatorModeActive(envFeatureOff()))
            .as("staticResolver 里的 DB 值必须被忽略（否则设置页删掉的全局开关还活着）").isFalse();

        PromptAlignSettingsResolver.setStaticResolver(null);
        assertThat(PromptAlignSettingsResolver.staticCoordinatorModeActive(envFeatureOn()))
            .as("无 DB 槽时只看 feature+env（真）").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 主循环顶层工具池（半激活主症：提示词已切协调者版，工具池却没裁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("工具池: feature/env 真 → 顶层池裁为协调者白名单（全局 DB 层已移除）")
    void llmToolsArray_envFeatureOn_trimsToCoordinatorWhitelist() {
        // GIVEN: 会话级无覆盖、全局 DB 已不参与 → 唯一能激活静态门的只剩 feature+env
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOn());

        // WHEN
        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        // THEN: coordinator 白名单（USER 可见集 ∪ PR 订阅工具）；Bash/Read/Edit/普通 MCP 必须消失
        //   [2026-11-20 按 CC 收口] StructuredOutput 虽在白名单（候选），但属 SPECIAL_TOOLS，
        //   交互式会话模型不可见（只在 jsonSchema && 非交互/后台时 post-hoc 追加）。
        assertThat(schemaNames(schema))
            .as("协调者激活时顶层工具池必须裁剪（否则系统提示让模型编排、模型却仍见执行工具 = 半激活）")
            .containsExactlyInAnyOrder(
                "Agent", "TaskStop", "SendMessage",
                "github.com.mycorp.subscribe_pr_activity",
                "pr_events.unsubscribe_pr_activity")
            .doesNotContain("StructuredOutput", "Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool");
    }

    @Test
    @DisplayName("工具池: DB=false 不再能压过 feature/env=true（全局层移除后的反向验证）")
    void llmToolsArray_dbFalseNoLongerOverrides() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));
        LlmAgentLoop.setCoordinatorMode(envFeatureOn());

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(fullAllPool()), QuerySource.USER);

        assertThat(schemaNames(schema))
            .as("DB=false 已不参与判定 ⇒ 工具池仍按协调者裁剪（若仍见 Bash 说明全局层没拆干净）")
            .doesNotContain("Bash", "Read", "Edit", "WebSearch", "mcp__random.server.tool");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. bare（Web 精简模式）工具池追加
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bare 池: feature/env 真 → 追加编排三工具并裁为白名单")
    void bareTools_envFeatureOn_appendsOrchestrationTools() {
        new MemoryBareModeConfig(true);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOn());

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
    @DisplayName("SubagentTool: feature/env 真 → isCoordinatorMode()=true")
    void subagentTool_envFeatureOn_coordinatorTrue() throws Exception {
        SubagentTool t = new SubagentTool();
        t.setCoordinatorModeBean(envFeatureOn());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        assertThat(invoke(SubagentTool.class, "isCoordinatorMode", t))
            .as("AgentTool prompt/shouldRunAsync/fork gate 三处共用本 helper：任何一处漏判都会各自半激活")
            .isTrue();
    }

    @Test
    @DisplayName("SubagentTool: DB=false 不再能压过 feature/env=true")
    void subagentTool_dbFalseNoLongerOverrides() throws Exception {
        SubagentTool t = new SubagentTool();
        t.setCoordinatorModeBean(envFeatureOn());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        assertThat(invoke(SubagentTool.class, "isCoordinatorMode", t))
            .as("全局层移除后，DB=false 不影响判定").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 权限链（awaitAutomatedChecksBeforeDialog）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("权限链: feature/env 真 → awaitAutomatedChecksBeforeDialog=true")
    void permissionContextBuilder_envFeatureOn_awaitChecksTrue() throws Exception {
        PermissionContextBuilder b = new PermissionContextBuilder();
        ReflectionTestUtils.setField(b, "coordinatorMode", envFeatureOn());
        // 全局 DB 值已不参与判定（这里故意给 true，验证它不再决定结果）
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        assertThat(invokeWithSession(PermissionContextBuilder.class, "isAwaitAutomatedChecksBeforeDialog", b, null))
            .as("协调者激活时权限链必须同步切协调者语义（CC runAgent.ts:457-464），"
                + "否则「提示已协调者、权限仍普通 agent」= 半激活")
            .isTrue();
    }

    @Test
    @DisplayName("权限链: DB=false 不再能压过 feature/env=true")
    void permissionContextBuilder_dbFalseNoLongerOverrides() throws Exception {
        PermissionContextBuilder b = new PermissionContextBuilder();
        ReflectionTestUtils.setField(b, "coordinatorMode", envFeatureOn());
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        assertThat(invokeWithSession(PermissionContextBuilder.class, "isAwaitAutomatedChecksBeforeDialog", b, null))
            .as("全局层移除后 DB=false 不影响判定 → 仍为 true").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 子代理周期摘要门（ASYNC 分路径：coordinator || fork || sdk）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("摘要门: feature/env 真 → ASYNC 路径触发")
    void maybeStartSummary_envFeatureOn_asyncTriggers() {
        // 全局 DB 值已不参与判定：即使给 true 也不应影响（真实激活源 = feature/env）
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, envFeatureOn(), scheduler);
        try {
            assertThat(h).as("协调者激活下 coordinator 分路径门必须为真（与启用的 feature 面同源）").isNotNull();
        } finally {
            if (h != null) h.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("摘要门: DB=false 不再能压过 feature/env=true → ASYNC 路径仍触发")
    void maybeStartSummary_dbFalseNoLongerOverrides() {
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));
        ScheduledExecutorService scheduler = newScheduler();
        AgentSummaryHandle h = start(SubagentExecutor.SummarySpawnPath.ASYNC, envFeatureOn(), scheduler);
        try {
            assertThat(h).as("全局层移除后 DB=false 不影响判定").isNotNull();
        } finally {
            if (h != null) h.stop();
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
            null, null, null, null,
            null);   // [usage-gateway] 末参 = 账本注册表（测试不记账）
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
    @DisplayName("fork 互斥: feature/env 真 → fork gate 关")
    void forkGate_envFeatureOn_disabled() {
        // GIVEN: feature-on=true（fork 主开关开）+ coordinator 回落层开 + 全局 DB 值（应被忽略）
        ForkSubagentConfig config = new ForkSubagentConfig(true, false, false);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(true));

        new ForkSubagentConfigBootstrap(config, envFeatureOn());

        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("coordinator 与 fork 是互斥模式（forkSubagent.ts:34）：协调者激活下 fork 必须关，"
                + "否则同一会话既铺 coordinator 提示又走 fork 分支")
            .isFalse();
    }

    @Test
    @DisplayName("fork 互斥: DB=false 不再能压过 feature/env=true → fork gate 仍关")
    void forkGate_dbFalseNoLongerOverrides() {
        ForkSubagentConfig config = new ForkSubagentConfig(true, false, false);
        PromptAlignSettingsResolver.setStaticResolver(dbResolver(false));

        new ForkSubagentConfigBootstrap(config, envFeatureOn());

        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("全局层移除后 DB=false 不影响判定 → 仍关").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. 提示词门（既有 DB 覆盖链 · 回归件）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("提示词门: feature/env 真 → coordinatorModeEnabled=true（顶层循环）")
    void promptOptions_envFeatureOn_coordinatorEnabled() throws Exception {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        // DB 值已不参与判定（给 true 只为验证它被忽略）
        ctx.sessionState().setPromptAlignSettingsResolver(dbResolver(true));
        LlmAgentLoop.setCoordinatorMode(envFeatureOn());

        Method m = LlmAgentLoop.class.getDeclaredMethod("buildEffectivePromptOptions",
            AgentLoopContext.class, ToolUseContext.class, QuerySource.class);
        m.setAccessible(true);
        EffectivePromptOptions opts = (EffectivePromptOptions) m.invoke(null, ctx, null, QuerySource.REPL_MAIN_THREAD);

        assertThat(opts.coordinatorModeEnabled()).isTrue();
        assertThat(opts.mainThreadInvocation()).isTrue();
    }
}
