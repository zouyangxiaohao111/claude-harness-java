package com.nexusai.application.agent;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder.EffectivePromptOptions;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.prompt.SystemPromptParts;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [批次 F 返工] LlmAgentLoop 私有 helper 的 SP-02/03/04 门控回落与合并顺序测试。
 *
 * <p>被测方法（private static，反射调用）：
 * <ul>
 *   <li>{@code buildEffectivePromptOptions(AgentLoopContext)} —— 三分支门控位求值
 *       （LlmAgentLoop.java:3544-3557）：coordinator 门 null → 回落
 *       {@code CoordinatorMode.isCoordinatorMode()}（feature+env 双真）；agent/proactive 门
 *       null → false（CC 3P 默认不激活）。</li>
 *   <li>{@code mergeCoordinatorUserContext(AgentLoopContext, ToolUseContext, SystemPromptParts)}
 *       —— coordinator userContext 并入（LlmAgentLoop.java:3575-3603，CC QueryEngine.ts:302-306）：
 *       gate 假 → 返回原 userContext 引用（零变化）；gate 真 → LinkedHashMap 基座 + putAll
 *       workerToolsContext，键序 = 基座键在前、workerToolsContext 恒末尾。</li>
 * </ul>
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：批次 F 核心新行为——coordinator 分支在生产可被 DB 门激活，
 * 门控回落语义（null→isCoordinatorMode / null→false）与合并键序是行为契约；若回落写反
 * （coordinator null→false）则 DB 未配时分支失活、若合并顺序颠倒则 workerToolsContext 覆盖
 * 基座键。反射钉死，防回归。
 */
class LlmAgentLoopPromptOptionsGateTest {

    @BeforeEach
    @AfterEach
    void resetCoordinator() {
        // null → 复位默认（feature 恒关 → isCoordinatorMode()=false），防跨测试污染
        LlmAgentLoop.setCoordinatorMode(null);
    }

    // ── 构造辅助 ──

    private static AgentLoopContext ctxWithoutResolver() {
        return TestContexts.agentLoopContext(null, null, null, null, null);
    }

    private static AgentLoopContext ctxWithResolver(PromptAlignSettingsResolver r) {
        AgentLoopContext ctx = ctxWithoutResolver();
        ctx.sessionState().setPromptAlignSettingsResolver(r);
        return ctx;
    }

    /** mock resolver：四门控全部指定（null = 模拟 DB 无值回落）。 */
    private static PromptAlignSettingsResolver resolverWith(
            Boolean agent, Boolean proactive, Boolean coordinator, Boolean scratchpad) {
        PromptAlignSettingsResolver r = mock(PromptAlignSettingsResolver.class);
        when(r.agentMainThreadEnabled()).thenReturn(agent);
        when(r.proactiveEnabled()).thenReturn(proactive);
        when(r.coordinatorModeEnabled()).thenReturn(coordinator);
        when(r.scratchpadEnabled()).thenReturn(scratchpad);
        return r;
    }

    private static EffectivePromptOptions invokeBuildOptions(AgentLoopContext ctx) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("buildEffectivePromptOptions", AgentLoopContext.class);
        m.setAccessible(true);
        return (EffectivePromptOptions) m.invoke(null, ctx);
    }

    private static Map<String, String> invokeMergeCoordinatorUserContext(
            AgentLoopContext ctx, ToolUseContext tuc, SystemPromptParts parts) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod(
            "mergeCoordinatorUserContext", AgentLoopContext.class, ToolUseContext.class, SystemPromptParts.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) m.invoke(null, ctx, tuc, parts);
        return result;
    }

    private static ToolUseContext tucWithMcp(Map<String, McpClientRuntime> mcp) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-f", PermissionMode.DEFAULT, Map.of(), List.of(), null,
            AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, mcp);
    }

    // ════════════════════════════════════════════════════════════════
    // buildEffectivePromptOptions 门控回落
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门控回落: resolver null（未接线）→ coordinator 回落 isCoordinatorMode()=true，agent/proactive 回落 false")
    void buildOptions_resolverNull_coordinatorFallsBackTrue() throws Exception {
        // WHY: DB 未配（resolver null）→ coordinator 门必须回落 CoordinatorMode.isCoordinatorMode()
        //   （CC feature('COORDINATOR_MODE') && env 双真，:63-65）；agent/proactive 回落 false
        //   （CC 3P 默认不激活）。变异点：coordinator null→false → DB 未配时 coordinator 分支失活 → 红。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1"));

        EffectivePromptOptions opts = invokeBuildOptions(ctxWithoutResolver());

        assertThat(opts.coordinatorModeEnabled())
            .as("coordinator 门 null → 回落 isCoordinatorMode()=true").isTrue();
        assertThat(opts.agentMainThreadEnabled()).as("agent 门 null → false").isFalse();
        assertThat(opts.proactiveEnabled()).as("proactive 门 null → false").isFalse();
        assertThat(opts.mainThreadAgentDefinition()).as("主循环无 /init → def supplier 恒 null").isNull();
    }

    @Test
    @DisplayName("门控回落: coordinator 门 null + isCoordinatorMode()=false → coordinatorModeEnabled=false（默认回落假）")
    void buildOptions_resolverNull_coordinatorFallsBackFalse() throws Exception {
        // WHY: feature 恒关（默认）→ isCoordinatorMode()=false → 门控回落 false，coordinator 分支不触发。
        //   变异点：coordinator 门 null 误判 true → 默认部署 coordinator 分支误激活 → 红。
        EffectivePromptOptions opts = invokeBuildOptions(ctxWithoutResolver());

        assertThat(opts.coordinatorModeEnabled()).as("coordinator 门 null + isCoordinatorMode()=false → false").isFalse();
    }

    @Test
    @DisplayName("门控回落: DB 有值覆盖回落链 —— coordinator=false 压过 isCoordinatorMode()=true，agent/proactive=true 生效")
    void buildOptions_resolverGates_overrideFallback() throws Exception {
        // WHY: DB settings 列（V56）非 null 时覆盖原判定链（PromptAlignSettingsResolver 实时读源）。
        //   变异点：DB 值被忽略（恒回落）→ coordinator 误判 true → 红。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1")); // 回落链本应 true
        PromptAlignSettingsResolver r = resolverWith(true, true, false, null);

        EffectivePromptOptions opts = invokeBuildOptions(ctxWithResolver(r));

        assertThat(opts.coordinatorModeEnabled()).as("DB coordinator=false 覆盖 isCoordinatorMode()=true").isFalse();
        assertThat(opts.agentMainThreadEnabled()).as("DB agentMainThread=true 生效").isTrue();
        assertThat(opts.proactiveEnabled()).as("DB proactive=true 生效").isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    // mergeCoordinatorUserContext 合并顺序
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("合并: gate 假（DB coordinator=false）→ 返回原 userContext 引用，零变化（不新建 map）")
    void mergeCoordinator_gateFalse_returnsOriginalReference() throws Exception {
        // WHY: gate 假 → 必须返回原引用（CC 未进入 coordinator 分支，userContext 原样，零行为变化）。
        //   变异点：gate 假仍新建 map → 引用变更（缓存 key 语义/性能）→ 红。
        PromptAlignSettingsResolver r = resolverWith(null, null, false, null);
        AgentLoopContext ctx = ctxWithResolver(r);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("claudeMd", "md-content");
        SystemPromptParts parts = new SystemPromptParts(List.of(), base, Map.of());

        Map<String, String> merged = invokeMergeCoordinatorUserContext(ctx, tucWithMcp(Map.of()), parts);

        assertThat(merged).as("gate 假 → 同一引用").isSameAs(parts.userContext());
        assertThat(merged).as("零变化，无 workerToolsContext 键").doesNotContainKey("workerToolsContext");
    }

    @Test
    @DisplayName("合并: gate null + isCoordinatorMode()=false → 回落 gate 假，返回原引用（CC 3P 默认关）")
    void mergeCoordinator_gateNullFallbackFalse_returnsOriginalReference() throws Exception {
        // WHY: DB 未配（null）→ 回落 isCoordinatorMode()；feature 恒关 → gate 假 → 原引用。
        //   变异点：coordinator 门 null 误判 true → 误触发合并 → 红。
        AgentLoopContext ctx = ctxWithResolver(resolverWith(null, null, null, null));
        Map<String, String> base = new LinkedHashMap<>();
        base.put("claudeMd", "md-content");
        SystemPromptParts parts = new SystemPromptParts(List.of(), base, Map.of());

        Map<String, String> merged = invokeMergeCoordinatorUserContext(ctx, tucWithMcp(Map.of()), parts);

        assertThat(merged).isSameAs(parts.userContext());
        assertThat(merged).doesNotContainKey("workerToolsContext");
    }

    @Test
    @DisplayName("合并: gate 真 → 新 map，键序 = 基座键在前 + workerToolsContext 恒末尾（LinkedHashMap 保持序，CC 展开合并语义）")
    void mergeCoordinator_gateTrue_baseKeysFirst_workerToolsLast() throws Exception {
        // WHY: CC userContext = {...base, ...getCoordinatorUserContext(...)}（QueryEngine.ts:302-306）
        //   —— 基座键保留原序、coordinator 键追加末尾；若顺序颠倒则 workerToolsContext 覆盖基座键。
        //   变异点：putAll 方向反了（coordCtx 先）→ 键序颠倒 → 红。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1"));
        AgentLoopContext ctx = ctxWithResolver(resolverWith(null, null, null, false));
        Map<String, String> base = new LinkedHashMap<>();
        base.put("claudeMd", "md-content");
        base.put("currentDate", "2026-08-30");
        SystemPromptParts parts = new SystemPromptParts(List.of(), base, Map.of());

        Map<String, String> merged = invokeMergeCoordinatorUserContext(
            ctx, tucWithMcp(Map.of("github", new McpClientRuntime("github", "t1", "i1"))), parts);

        assertThat(merged).as("gate 真 → 新建 map，非原引用").isNotSameAs(parts.userContext());
        assertThat(merged.keySet().stream().toList())
            .as("键序 = 基座键原序 + workerToolsContext 末尾（CC {...base, ...coord} 展开序）")
            .containsExactly("claudeMd", "currentDate", "workerToolsContext");
        assertThat(merged).as("基座键原值保留").containsEntry("claudeMd", "md-content").containsEntry("currentDate", "2026-08-30");
        assertThat(merged.get("workerToolsContext"))
            .as("workerToolsContext 内容含已连接 MCP server 名（coordinatorMode.ts:99-102）")
            .contains("github");
    }

    @Test
    @DisplayName("合并: DB coordinator=1 + isCoordinatorMode()=false → workerToolsContext 仍并入（DB 门为权威，内层复检不击穿）")
    void mergeCoordinator_dbGateTrue_featureEnvOff_workerToolsStillMerged() throws Exception {
        // WHY: 批次 F 起 coordinator 分支门 = resolver.coordinatorModeEnabled()（DB settings 列，
        //   DB 有值覆盖 feature/env 链，实施计划.md:71）。prompt 分支经 buildEffectivePromptOptions
        //   已吃 DB 覆盖值；若 userContext 并入仍被内层 isCoordinatorMode()（feature+env 双真）
        //   复检击穿 → DB coordinator=1 + feature/env OFF → coordinator 提示注入但
        //   workerToolsContext 未合并（半激活，SP-02 返工）。变异点：三参重载误用
        //   isCoordinatorMode()（而非 caller 已解析 coordinatorActive）→ workerToolsContext 缺失 → 红。
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> "1")); // feature OFF → isCoordinatorMode()=false
        AgentLoopContext ctx = ctxWithResolver(resolverWith(null, null, true, false)); // DB coordinator=1
        Map<String, String> base = new LinkedHashMap<>();
        base.put("claudeMd", "md-content");
        SystemPromptParts parts = new SystemPromptParts(List.of(), base, Map.of());

        Map<String, String> merged = invokeMergeCoordinatorUserContext(
            ctx, tucWithMcp(Map.of("github", new McpClientRuntime("github", "t1", "i1"))), parts);

        assertThat(merged).as("DB 门真 → 合并（非原引用）").isNotSameAs(parts.userContext());
        assertThat(merged).as("workerToolsContext 已并入").containsKey("workerToolsContext");
        assertThat(merged.get("workerToolsContext"))
            .as("workerToolsContext 内容含已连接 MCP server 名（coordinatorMode.ts:99-102）")
            .contains("github");
    }
}
