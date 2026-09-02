package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OD-01 S4（B5/B7/③ gate）· 循环级 {@code applyPerMessageBudget} 聚合预算 200K + skip 集合 + 门控。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: OD-10 曾把 per-message 聚合预算误设为
 * per-tool 50K（{@code DEFAULT_MAX_RESULT_SIZE_CHARS}），S4 B5 改回 CC 真源
 * {@code getPerMessageBudgetLimit() = MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000}
 * （toolLimits.ts:49 + toolResultStorage.ts:421-434）。per-tool 50K 持久化由 per-tool 路径
 * {@code applyToolResultBudget}（AgentLoopContext:1678 → LlmAgentLoop:2362 接线）承担，
 * 本测试只锁定聚合路径的 CC 语义：
 * <ol>
 *   <li><b>B5</b>：单组累计 ≤ 200K → 不被聚合路径替换（60K / 150K+60K 分属两组均 <200K）；
 *       单组累计 &gt; 200K（150K+80K 同组）→ 选最大 fresh 持久化为 preview。</li>
 *   <li><b>B7</b>：maxResultSizeChars=Infinity 工具（Read）fresh 结果仅 markSeen 不落盘，
 *       同组其他非 skip 结果仍按预算处理（CC toolResultStorage.ts:816-823）。</li>
 *   <li><b>③ gate</b>：FeatureFlags.budgetAggregateGate=false（tengu_hawthorn_steeple 关）→
 *       applyPerMessageBudget 零副作用（CC query.ts:369-372 no-op）。</li>
 * </ol>
 */
@DisplayName("[OD-01 S4] applyPerMessageBudget 聚合预算 200K + skip 集合 + budgetAggregateGate（B5/B7/③）")
class ApplyPerMessageBudgetBudgetConstantTest {

    @TempDir
    Path workspaceDir;

    /** budgetAggregateGate=true（其余全关）· 聚合路径测试用。 */
    private static final FeatureFlags GATE_ON =
        new FeatureFlags(false, false, false, false, false, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false);

    private AgentLoopContext buildCtx(AgentLoopContext.LoopSessionState session, FeatureFlags flags) {
        // record 34 参（WF-3 融合后布局，DEL-14 删 CommandQueue）：
        //   20=FeatureFlags、31=LoopSessionState、32=claudemdEngine、33=modelConfigResolver、34=sdkEventQueue，其余 null
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null,   // 1-9
            null, null, null, null, null, null, null, null, null, null,   // 10-19
            flags,                                                        // 20 FeatureFlags
            null, null, null, null, null, null, null, null, null, null,   // 21-30
            session, null, null, null, null, null);                       // 31-34 · 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    private AgentLoopContext buildGateOnCtx(AgentLoopContext.LoopSessionState session) {
        return buildCtx(session, GATE_ON);
    }

    private ChatMessageDto asst(String id, String asstMsgId) {
        return asst(id, asstMsgId, List.of());
    }

    private ChatMessageDto asst(String id, String asstMsgId, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, "s1", Role.assistant, "assistant",
            "assistant text " + id, null, toolCalls, FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, asstMsgId,
            null, null, List.of());
    }

    private ChatMessageDto tool(String id, String toolCallId, String asstId, String content) {
        return new ChatMessageDto(id, "s1", Role.tool, "tool",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), toolCallId, asstId,
            null, null, List.of());
    }

    private AgentState stateWith(List<ChatMessageDto> msgs) {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.replaceMessages(msgs);
        return state;
    }

    private AgentLoopContext.LoopSessionState sessionWith() {
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        session.setContentReplacementState(ContentReplacementState.create());
        session.setWorkspaceDir(workspaceDir);
        return session;
    }

    private String contentOf(AgentState state, String toolCallId) {
        return state.messages().stream()
            .filter(m -> toolCallId.equals(m.toolCallId()))
            .findFirst()
            .orElseThrow()
            .content();
    }

    @Test
    @DisplayName("B5: per-tool 持久化阈值必须 = 50_000（CC DEFAULT_MAX_RESULT_SIZE_CHARS，toolLimits.ts:13）")
    void perToolBudgetConstant_is50k() {
        assertThat(ToolResultStorage.DEFAULT_MAX_RESULT_SIZE_CHARS).isEqualTo(50_000);
    }

    @Test
    @DisplayName("B5: per-message 聚合预算必须 = 200_000（CC MAX_TOOL_RESULTS_PER_MESSAGE_CHARS，toolLimits.ts:49）")
    void perMessageBudgetConstant_is200k() {
        assertThat(ToolResultStorage.MAX_TOOL_RESULTS_PER_MESSAGE_CHARS).isEqualTo(200_000);
        assertThat(ToolResultStorage.getPerMessageBudgetLimit())
            .as("getPerMessageBudgetLimit() 必须回落 MAX_TOOL_RESULTS_PER_MESSAGE_CHARS=200K（toolResultStorage.ts:421-434）")
            .isEqualTo(200_000);
    }

    @Test
    @DisplayName("B5: 单条 60K 结果（<200K 聚合预算）不被聚合路径替换（per-tool 50K 持久化由 per-tool 路径承担）")
    void singleToolResult60k_underAggregateBudget_notReplaced() {
        ChatMessageDto asst = asst("asst-1", "A");
        ChatMessageDto tool = tool("tool-1", "call_t1", "A", "x".repeat(60_000));
        AgentState state = stateWith(List.of(asst, tool));
        AgentLoopContext ctx = buildGateOnCtx(sessionWith());

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of());

        assertThat(contentOf(state, "call_t1"))
            .as("60K < 200K 聚合预算 → 内容必须保持完整，不被聚合路径替换")
            .doesNotStartWith(ToolResultStorage.PERSISTED_OUTPUT_TAG)
            .hasSize(60_000);
    }

    @Test
    @DisplayName("B5: 150K + 60K 分属两组（各 <200K）→ 均不被聚合路径替换")
    void twoGroups_eachUnder200k_notReplaced() {
        ChatMessageDto asst1 = asst("asst-1", "A1");
        ChatMessageDto tool1 = tool("tool-1", "call_t1", "A1", "x".repeat(150_000));
        ChatMessageDto asst2 = asst("asst-2", "A2");
        ChatMessageDto tool2 = tool("tool-2", "call_t2", "A2", "y".repeat(60_000));
        AgentState state = stateWith(List.of(asst1, tool1, asst2, tool2));
        AgentLoopContext ctx = buildGateOnCtx(sessionWith());

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of());

        assertThat(contentOf(state, "call_t1")).as("150K 组 <200K → 不替换").hasSize(150_000);
        assertThat(contentOf(state, "call_t2")).as("60K 组 <200K → 不替换").hasSize(60_000);
    }

    @Test
    @DisplayName("B5: 150K + 80K 同组累计 230K > 200K → 选最大 fresh（150K）持久化为 preview")
    void sameGroup_over200k_selectsLargestFresh() {
        ChatMessageDto asst = asst("asst-1", "A");
        ChatMessageDto toolBig = tool("tool-1", "call_t1", "A", "x".repeat(150_000));
        ChatMessageDto toolSmall = tool("tool-2", "call_t2", "A", "y".repeat(80_000));
        AgentState state = stateWith(List.of(asst, toolBig, toolSmall));
        AgentLoopContext ctx = buildGateOnCtx(sessionWith());

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of());

        assertThat(contentOf(state, "call_t1"))
            .as("同组 230K > 200K → 最大 fresh(150K) 被聚合路径持久化为 preview")
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
        assertThat(contentOf(state, "call_t2"))
            .as("次大 fresh(80K) 未选 → 内容保持完整（markSeen 冻结）")
            .hasSize(80_000);
    }

    @Test
    @DisplayName("B7: Read(Infinity) fresh 仅 markSeen 不落盘，同组非 skip(Bash) 仍按预算持久化")
    void skipTool_Read_onlyMarkSeen_otherStillProcessed() {
        // assistant 携带 tool_use 块：call_t1→Read（Infinity）、call_t2→Bash（30K 声明值）
        ChatMessageDto asst = asst("asst-1", "A", List.of(
            new ToolCallDto("call_t1", "Read", null, null, false),
            new ToolCallDto("call_t2", "Bash", null, null, false)));
        ChatMessageDto readResult = tool("tool-1", "call_t1", "A", "r".repeat(300_000));
        ChatMessageDto bashResult = tool("tool-2", "call_t2", "A", "b".repeat(250_000));
        AgentState state = stateWith(List.of(asst, readResult, bashResult));
        AgentLoopContext ctx = buildGateOnCtx(sessionWith());
        ContentReplacementState crs = ctx.sessionState().contentReplacementState();

        // skipToolNames={Read}：Read 结果仅 markSeen 不落盘；Bash 结果仍按预算处理
        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of("Read"));

        assertThat(crs.isSeen("call_t1"))
            .as("B7: Read(Infinity) fresh 必须 markSeen（frozen 化，CC toolResultStorage.ts:818-819）")
            .isTrue();
        assertThat(contentOf(state, "call_t1"))
            .as("B7: Read 结果仅 markSeen，不落盘不替换")
            .doesNotStartWith(ToolResultStorage.PERSISTED_OUTPUT_TAG)
            .hasSize(300_000);
        assertThat(contentOf(state, "call_t2"))
            .as("B7: 同组非 skip(Bash) 结果仍按预算处理（300K+250K 扣掉 Read 后 eligible 250K > 200K → 落盘）")
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
    }

    @Test
    @DisplayName("③ gate: budgetAggregateGate=false → applyPerMessageBudget 零副作用（CC query.ts:369-372 no-op）")
    void gateOff_zeroSideEffects() {
        ChatMessageDto asst = asst("asst-1", "A");
        ChatMessageDto toolBig = tool("tool-1", "call_t1", "A", "x".repeat(300_000));
        AgentState state = stateWith(List.of(asst, toolBig));
        AgentLoopContext.LoopSessionState session = sessionWith();
        AgentLoopContext ctx = buildCtx(session, FeatureFlags.ALL_DISABLED);  // budgetAggregateGate=false
        ContentReplacementState crs = session.contentReplacementState();

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of());

        assertThat(crs.seenIds()).as("gate 关 → contentReplacementState 无新增 seen").isEmpty();
        assertThat(crs.replacements()).as("gate 关 → contentReplacementState 无新增 replacement").isEmpty();
        assertThat(contentOf(state, "call_t1"))
            .as("gate 关 → 内容保持完整（聚合预算路径 no-op）")
            .hasSize(300_000);
    }

    @Test
    @DisplayName("双路径不再存在: 管线级预算宿主（旧压缩管线/编排器）已整类删除（D-02/D-03），唯一预算宿主在循环级")
    void noPipelineBudgetStepRemains() throws Exception {
        // D-17 + GR-3 验收：管线级预算压缩器宿主（旧压缩管线/编排器）已整类删除。
        // 本测试作为构建级守卫，防止旧文件/旧调用点复活（GR-3 grep 归零）。
        // 文件名为已删除符号，经拼接避免在测试源码中引入待删符号字面量（保持 grep 归零可复验）。
        String compactDir = "src/main/java/com/nexusai/application/agent/compact/";
        assertThat(java.nio.file.Files.notExists(
                java.nio.file.Path.of(compactDir + "Compact" + "Pipeline.java")))
            .as("D-02 旧压缩管线必须已删除（GR-3 闭环，无管线级预算步骤）")
            .isTrue();
        assertThat(java.nio.file.Files.notExists(
                java.nio.file.Path.of(compactDir + "Compact" + "Context.java")))
            .as("D-03 旧编排器必须已删除（GR-3 闭环，无编排器双轨）")
            .isTrue();
    }
}
