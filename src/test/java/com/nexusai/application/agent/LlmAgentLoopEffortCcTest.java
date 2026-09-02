package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [C-31] effort→LLM 管线集成测试（loop 消费点）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>验收 #1 消费点参数断言</b> — {@code LlmAgentLoop} ModelRequest 构造（:2762 等价
 *       query.ts:694 {@code options.effortValue: appState.effortValue}）必须把
 *       {@code AgentState.effortValue} 经 {@code ModelRequest.effortValue} → {@code ModelCaller}
 *       → provider 18-arg stream（effortValue 参数）真实透传。若消费点不读 state（或 ModelCaller
 *       不选 18-arg 分支），skill effort 仅数据形态存在（C-31 决策 FAIL）。</li>
 *   <li><b>fork 子代理语义</b> — SubagentExecutor runSubagentQueryLoop 子 AgentState 注入
 *       {@code agentDefinition.effort}（对齐 CC SkillTool.ts:208-212）后，fork LLM 请求必须携带
 *       effort。本测试以 2104 同款子 AgentState 构造 + 注入契约验证下游携带。</li>
 *   <li><b>effort=null 回落</b> — 未设置 effort 时回落既有级联（15-arg stream），18-arg 不被
 *       调用，既有 mock 测试契约不破坏。</li>
 * </ol>
 *
 * <p>镜像 {@link LlmAgentLoopTaskBudgetCcTest} 的 queryLoop 集成 harness（mock provider 捕获
 * stream 参数 + 最小 AgentLoopContext）。
 */
class LlmAgentLoopEffortCcTest {

    @Test
    @DisplayName("loop 消费点: state.effortValue → ModelRequest.effortValue → provider 18-arg stream（effortValue 参数）")
    void loop_readsStateEffortValue_passesToProviderStream() {
        String[] capturedEffort = {null};
        // 18-arg stream（16 参）：config/model/system/history/tools/override/taskBudget/effortValue +
        //   onChunk/onMsg/onTool/onReasoning/onFallback/abort/onError/onComplete → effortValue 在 arg(7)
        LlmProvider provider = mock(LlmProvider.class);
        org.mockito.Mockito.doAnswer(inv -> {
            // [IMP-SP-08] blocks 重载：effortValue@7 不变，onChunk@9/onMsg@10/onComplete@16
            capturedEffort[0] = inv.getArgument(7);
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(message("m1", Role.user, "question", null, null));
        // 写入侧等价：SkillToolImpl contextModifier（CC SkillTool.ts:823-836）同步写入
        state.setEffortValue("high");

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return agentLoopContextWithFactory(factory); }
            @Override public boolean isMainLoop() { return true; }
        };

        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "claude-sonnet-4-6", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(capturedEffort[0])
            .as("AgentState.effortValue 必须经 ModelRequest.effortValue 透传到 provider.stream（query.ts:694）")
            .isEqualTo("high");
    }

    @Test
    @DisplayName("fork 子代理: 子 AgentState 注入 effort（SubagentExecutor:2104 契约）→ fork LLM 请求携带")
    void forkSubAgentState_effortInjected_carriesToProviderStream() {
        String[] capturedEffort = {null};
        LlmProvider provider = mock(LlmProvider.class);
        org.mockito.Mockito.doAnswer(inv -> {
            // [IMP-SP-08] blocks 重载：effortValue@7 不变，onChunk@9/onMsg@10/onComplete@16
            capturedEffort[0] = inv.getArgument(7);
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("fork reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("fork reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 镜像 SubagentExecutor.runSubagentQueryLoop :2104-2112（fork 隔离子 AgentState 注入）──
        //   agentDefinition.effort = skill frontmatter effort 合并（SkillTool.ts:208-212 withEffort）
        String forkSessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID forkAgentId = UUID.randomUUID();
        AgentState state = new AgentState("fork-agent-system", forkSessionId, forkAgentId);
        // [C-31] 注入：agentDefinition.effort().isPresent() → state.setEffortValue(effort)
        state.setEffortValue("max");
        state.appendMessage(message("m1", Role.user, "fork question", null, null));

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return agentLoopContextWithFactory(factory); }
            @Override public boolean isMainLoop() { return false; }
        };

        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null,
                // [session-id-short] of(agentId, sessionId)：forkSessionId 是 short sessionId，forkAgentId 是 UUID agentId
                ToolUseContext.of(forkAgentId, forkSessionId),
                QuerySource.USER, "claude-sonnet-4-6", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(result.aborted()).isFalse();
        assertThat(capturedEffort[0])
            .as("fork 子 AgentState.effortValue（'max'）必须随 fork LLM 请求到达 provider")
            .isEqualTo("max");
    }

    @Test
    @DisplayName("effort=null（未设置）→ blocks stream 仍被调用且 effortValue 参数为 null（⊕C-1 唯一发送契约）")
    void loop_effortNull_passesNullEffortValue() {
        // [⊕C-1] blocks 17 参唯一发送契约 · effort=null → ModelCaller 仍走 blocks stream（effortValue 参数 null）
        LlmProvider provider = mock(LlmProvider.class);
        boolean[] streamCalled = {false};
        org.mockito.Mockito.doAnswer(inv -> {
            streamCalled[0] = true;
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(message("m1", Role.user, "question", null, null));
        // effortValue 保持 null（未设置）

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return agentLoopContextWithFactory(factory); }
            @Override public boolean isMainLoop() { return true; }
        };

        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null,
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "claude-sonnet-4-6", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(result.aborted()).isFalse();
        assertThat(streamCalled[0]).as("effort=null → blocks stream 被调用").isTrue();
        // [⊕C-1] 原 18-arg String stream 已删除：effort=null 语义 = blocks stream 的 effortValue 参数（arg 7）为 null
        verify(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), isNull(), isNull(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 构造最小 AgentLoopContext（与 LlmAgentLoopTaskBudgetCcTest 同形 · 无压缩组件位）。 */
    private static AgentLoopContext agentLoopContextWithFactory(LlmProviderFactory factory) {
        return new AgentLoopContext(
            null, null,
            null, null, null, null, null, null, null, null,
            factory, null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);  // 33 modelConfigResolver · 34 sdkEventQueue · 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    private static ChatMessageDto message(String id, Role role, String content,
                                          Integer inputTokens, Integer outputTokens) {
        return new ChatMessageDto(
            id, null, role, role.name(), content, null, List.of(),
            FinishReason.stop, inputTokens, outputTokens,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }
}
