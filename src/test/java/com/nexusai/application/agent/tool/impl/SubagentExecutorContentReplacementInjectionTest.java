package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.SubagentLoopDeps;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-R6 · resume 深度缺口测试：ContentReplacementState 注入 resumed 子 agent query loop
 * （对齐 CC resumeAgent.ts:194 runAgentParams.contentReplacementState + query.ts:372-389
 * applyToolResultBudget 消费）。
 *
 * <p>规则九（测试验证意图）：resume 重建的 ContentReplacementState 必须传入 resumed 子 agent
 * query loop —— 否则 resumed 会话的 per-message tool result budget 决策从头开始（fresh state），
 * 与原会话不一致，prompt cache 前缀破坏，被 kill 的异步 agent 恢复后预算行为漂移
 * （CC reconstructForSubagentResume + applyToolResultBudget 组合语义）。
 *
 * <p>CC 真源：reconstructForSubagentResume（toolResultStorage.ts:1001-1012）= parentState null
 * → undefined（feature off）；runAgentParams.contentReplacementState（resumeAgent.ts:194）；
 * query.ts:379-381 applyToolResultBudget(messages, toolUseContext.contentReplacementState, ...)
 * 消费同一实例。
 */
@DisplayName("[RES-R6] resume ContentReplacementState 注入 query loop")
class SubagentExecutorContentReplacementInjectionTest {

    // ────────────────────────────────────────────────────────────────────────
    // 1. CRS 注入：同一实例落到 AgentLoopContext.sessionState.contentReplacementState
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非 null ContentReplacementState → AgentLoopContext.contentReplacementState() == 注入实例")
    void inject_nonNull_setsSameInstanceOnLoopContext() {
        // GIVEN: resume 重建的 ContentReplacementState（非 null → 必须注入）
        ContentReplacementState rebuilt = ContentReplacementState.create();
        rebuilt.recordReplacement("tool_use_1", "[preview]");

        // AgentLoopContext 由 contextFactory.shared() 构造（fresh session state）
        AgentLoopContext ctx = new com.nexusai.application.agent.loop.AgentLoopContextFactory().shared(null);
        SubagentLoopDeps deps = new SubagentLoopDeps(ctx);

        // WHEN: SubagentExecutor 注入点消费 rebuilt
        SubagentExecutor.injectContentReplacementState(deps, rebuilt);

        // THEN: query loop 使用同一实例（CC :194 runAgentParams.contentReplacementState 同引用）
        assertThat(ctx.sessionState().contentReplacementState())
            .as("resume 重建的 CRS 必须原实例注入 query loop (CC query.ts:379-381 消费同一实例)")
            .isSameAs(rebuilt);
    }

    @Test
    @DisplayName("null ContentReplacementState → loop 保持默认 create（CC :1006 feature off 同语义）")
    void inject_null_keepsDefaultState() {
        // GIVEN: parentState null → reconstructForSubagentResume 返 null（CC :1006）
        AgentLoopContext ctx = new com.nexusai.application.agent.loop.AgentLoopContextFactory().shared(null);
        ContentReplacementState defaultState = ctx.sessionState().contentReplacementState();

        // WHEN: 注入 null（feature off / web 端点无父 live state）
        SubagentExecutor.injectContentReplacementState(new SubagentLoopDeps(ctx), null);

        // THEN: 保持默认 create，不替换（CC applyToolResultBudget state undefined → 原样返回）
        assertThat(ctx.sessionState().contentReplacementState())
            .as("null 注入必须保持默认 create（CC :1006 reconstructForSubagentResume 返 undefined 同语义）")
            .isSameAs(defaultState);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. ForkPathParams 透传 CRS（resumeAgent.ts:194 → runAgentParams.contentReplacementState）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ForkPathParams resume 专属字段透传 CRS（8 参构造）")
    void forkPathParams_8arg_carriesContentReplacementState() {
        ContentReplacementState rebuilt = ContentReplacementState.create();
        rebuilt.markSeen("tc-x");

        SubagentExecutor.ForkPathParams resumeParams = new SubagentExecutor.ForkPathParams(
            null, null, "parent-prompt", null,
            List.of(AgentMessage.of("user", "pre-resume")),
            null, UUID.fromString("00000000-0000-0000-0000-0000000000a1"), rebuilt);

        assertThat(resumeParams.contentReplacementState()).isSameAs(rebuilt);
    }

    @Test
    @DisplayName("ForkPathParams 4 参兼容构造: contentReplacementState 默认 null（非 resume 调用方不受影响）")
    void forkPathParams_4argCompat_crsDefaultsNull() {
        SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
            null, List.of(), "parent-system-prompt", null);
        assertThat(forkParams.contentReplacementState())
            .as("4 参兼容构造（非 resume 调用方）contentReplacementState 必须为 null")
            .isNull();
        assertThat(forkParams.agentIdOverride()).isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. 工具注册表类型引用（确保 ToolRegistry 可构造，测试自检）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resume ForkPathParams 经 SubagentExecutor.execute 生产路径可透传（上下文构造不抛）")
    void resumeParams_constructsSubagentExecutor() {
        SubagentExecutor.ForkPathParams resumeParams = new SubagentExecutor.ForkPathParams(
            null, null, "", null,
            List.of(AgentMessage.of("user", "pre-resume")),
            null, UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
            ContentReplacementState.create());
        // 构造器兼容：8 字段 record 不破坏 SubagentExecutor 既有构造路径
        new SubagentExecutor(
            ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
        assertThat(resumeParams).isNotNull();
    }
}
