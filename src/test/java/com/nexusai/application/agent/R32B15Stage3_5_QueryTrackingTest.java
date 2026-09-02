package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [H7-arch Phase 5-2 A2-d] queryTracking 初始化/递增 + fork 链透传契约测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: A2 对齐 CC {@code query.ts:346-363} 每轮
 * stamp {@code {chainId, depth}} 到 ToolUseContext（主循环 loop 局部递增）+ {@code state.toolUseContext}
 * (query.ts:1715-1727)（当前轮 TUC 供 SubagentTool 读 parentTUC）。若 stamp 未接通（
 * toolExecContext 不传 queryTracking / getCurrentToolUseContext 重建 fresh TUC）→ 本测试失败。
 *
 * <p><b>RED teeth</b>:
 * <ol>
 *   <li>主循环 ≥2 轮：getCurrentToolUseContext().queryTracking() 非 null、depth 递增（0→1）、
 *       同 run 内 chainId 稳定 —— 若 buildStreamingExecutor 未把 loop 局部 queryTracking 透传
 *       到 toolExecContext（槽位仍硬编码 null）→ 每轮 TUC.queryTracking 为 null → RED。</li>
 *   <li>跨 run chainId 不同 —— 若 loop 未在首轮生成新 chainId（复用同一 uuid）→ RED。</li>
 *   <li>fork 链：父 TUC stamp depth=n → {@code with(SubagentContextOverrides)} 产生新 chainId +
 *       depth=n+1（CC forkedAgent.ts:451-455）→ 子 loop 首轮再 +1（净 +2）。</li>
 * </ol>
 *
 * <p><b>测试构造</b>: 真实 LlmAgentLoop + mocked LlmProviderFactory + 空 ToolRegistry（未知工具
 * → StreamingToolExecutor 产 error result，不真执行）。provider 第 1 轮产 tool_calls（触发工具轮
 * → needsFollowUp → 第 2 轮）、第 2 轮产纯文本 stop（退出）。每轮 provider 回调时读
 * {@code loop.getCurrentToolUseContext().queryTracking()} 观察已 stamp 的值。
 *
 * @see LlmAgentLoop#buildStreamingExecutor
 * @see LlmAgentLoop#getCurrentToolUseContext
 * @see ToolUseContext#with
 */
class R32B15Stage3_5_QueryTrackingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单次 run 的观察结果。 */
    private record RunObservation(
            List<Map<String, Object>> perTurnSnapshots,
            ToolUseContext finalTuc,
            Map<String, Object> finalQueryTracking,
            String firstChainId) {}

    /**
     * 驱动真实 loop 跑 2 轮（第 1 轮 tool_calls → 工具轮 → 第 2 轮纯文本退出）。
     *
     * <p>空 ToolRegistry：buildStreamingExecutor 每轮都跑（toolRegistry 非 null）→ toolExecContext
     * 每轮 stamp；工具轮里未知工具产 error result（不真执行），handleToolCallsTurn 返回 "continue"。
     */
    private static RunObservation runTwoTurns() {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        // [H7-arch Phase 5-2 P3-⑤] setToolRegistry 已删（工具隔离走 base TUC availableTools）：
        // 经构造器 4 注入 registry（buildBaseToolUseContext 快照为 base TUC availableTools）。
        // 注册 dummy "Bash" 使 availableTools 非空 → buildStreamingExecutor 真实构建 +
        // handleToolCallsTurn 不 hit「无工具 → exit」守卫（第 1 轮 Bash tool_calls → continue）。
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);

        List<Map<String, Object>> snapshots = new ArrayList<>();
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            snapshots.add(tuc != null ? tuc.queryTracking() : null);
            if (snapshots.size() == 1) {
                // 第 1 轮：tool_calls（未知工具）→ markNeedsFollowUp → 工具轮 → continue → 第 2 轮
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("Let me check", "tool_calls",
                    List.of(new ToolUseBlock("toolu_qt_1", "Bash", input))));
            } else {
                // 第 2 轮：纯文本 stop → needsFollowUp=false → 退出
                onMsg.accept(new AssistantMessage("Done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(factory.getProvider(any(), any())).thenReturn(provider);

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        AgentState state = loop.run(RunRequest.session(
            "question", sessionId, agentId, ProviderConfig.empty(), "test-model", null, null));
        assertThat(state).as("run() 必须返回非 null state").isNotNull();

        ToolUseContext finalTuc = loop.getCurrentToolUseContext();
        Map<String, Object> finalQt = finalTuc != null ? finalTuc.queryTracking() : null;
        String firstChainId = snapshots.isEmpty() || snapshots.get(0) == null
            ? null : (String) snapshots.get(0).get("chainId");
        return new RunObservation(snapshots, finalTuc, finalQt, firstChainId);
    }

    @Test
    @DisplayName("主循环 2 轮: queryTracking 非 null、depth 递增(0→1)、同 run chainId 稳定、final depth>=1")
    void mainLoop_twoTurns_stampsQueryTracking() {
        RunObservation obs = runTwoTurns();

        assertThat(obs.perTurnSnapshots()).as("provider 回调必须发生 2 次（2 轮 loop）").hasSize(2);
        assertThat(obs.perTurnSnapshots().get(0))
            .as("第 1 轮必须 stamp queryTracking {chainId, depth:0}（CC query.ts:346-363 首轮）")
            .isNotNull();
        assertThat(obs.perTurnSnapshots().get(0).get("depth")).isEqualTo(0);
        assertThat(obs.perTurnSnapshots().get(0).get("chainId")).isNotNull();
        assertThat(obs.perTurnSnapshots().get(1))
            .as("第 2 轮必须 stamp queryTracking（同链 depth+1）")
            .isNotNull();
        assertThat(obs.perTurnSnapshots().get(1).get("depth")).isEqualTo(1);
        assertThat(obs.perTurnSnapshots().get(1).get("chainId"))
            .as("同 run 内 chainId 必须稳定（CC 仅 depth 递增）")
            .isEqualTo(obs.perTurnSnapshots().get(0).get("chainId"));

        assertThat(obs.finalQueryTracking())
            .as("getCurrentToolUseContext() 必须读到已 stamp 的 TUC（A2-c 不再重建 fresh TUC）")
            .isNotNull();
        assertThat((Integer) obs.finalQueryTracking().get("depth")).isGreaterThanOrEqualTo(1);
        assertThat(obs.finalQueryTracking().get("chainId"))
            .as("final 链 ID 必须仍是本 run 的链（深度 1）")
            .isEqualTo(obs.firstChainId());
    }

    @Test
    @DisplayName("跨 run: 每次 run()（每次 query()）生成新 chainId")
    void crossRun_chainIdDiffers() {
        RunObservation run1 = runTwoTurns();
        RunObservation run2 = runTwoTurns();

        assertThat(run1.firstChainId()).as("run1 必须生成 chainId").isNotNull();
        assertThat(run2.firstChainId()).as("run2 必须生成 chainId").isNotNull();
        assertThat(run2.firstChainId())
            .as("跨 run 必须生成不同 chainId（CC query.ts:346-363 首轮 deps.uuid() 每次 query() 新建）")
            .isNotEqualTo(run1.firstChainId());
    }

    @Test
    @DisplayName("fork 链: 父 TUC stamp depth=n → with(SubagentContextOverrides) 产生新 chainId + depth=n+1")
    void fork_withSubagentOverrides_depthPlusOneNewChain() {
        RunObservation obs = runTwoTurns();
        assertThat(obs.finalTuc()).as("必须能从主循环读到已 stamp 的父 TUC").isNotNull();
        assertThat(obs.finalQueryTracking()).isNotNull();

        int parentDepth = (Integer) obs.finalQueryTracking().get("depth");
        String parentChainId = (String) obs.finalQueryTracking().get("chainId");

        ToolUseContext child = obs.finalTuc().with(
            new ToolUseContext.SubagentContextOverrides(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(child.queryTracking())
            .as("fork 子 ctx 必须携带 queryTracking（CC forkedAgent.ts:451-455）")
            .isNotNull();
        assertThat(child.queryTracking().get("depth"))
            .as("fork 必须 depth = parent + 1")
            .isEqualTo(parentDepth + 1);
        assertThat(child.queryTracking().get("chainId"))
            .as("fork 必须生成新 chainId（独立子查询链）")
            .isNotEqualTo(parentChainId);
    }
}
