package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
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
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [D10 双视图 · 批次 4a] {@code AgentState.rawMessages()} / {@code AgentState.modelView()} 契约测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图而非行为）</b>：改造前 {@code AgentState} 只有一个
 * {@code messages()} getter，它在 {@code LlmAgentLoop} 循环入口被 {@code replaceMessages(compactTarget)}
 * <b>破坏性写回</b> —— 入口前读它拿到全量、入口后拿到「已剥离 compact boundary + 已投影 snip」的
 * 有损视图，判定只能靠数行号（审计 §八 「靠自觉」）。本测试钉死的是**为什么必须两个名字**：
 *
 * <ol>
 *   <li><b>同一时刻并存</b>：{@code rawMessages()} 保留 pre-boundary 历史，{@code modelView()}
 *       不含它 —— 二者在同一 state 上同时成立、互不影响（改造前不可能：写回后全量已在内存消失）。</li>
 *   <li><b>派生不写回</b>：调用 {@code modelView()} 不改变 {@code rawMessages()}（无副作用）；
 *       派生结果与 state 后续 append 解耦（是快照语义，同 CC {@code query.ts:523} 的
 *       {@code messagesForQuery} 局部数组）。</li>
 *   <li><b>模型面行为不变</b>：驱动真实 loop，断言**真正发给 provider 的消息链**仍不含
 *       pre-boundary / 被 snip 消息（与改造前逐条一致），而 {@code rawMessages()} 保活。</li>
 *   <li><b>消费点选边生效</b>：模型面消费点（{@code AgentLoopContext.toolExecContext} 的
 *       per-turn {@code ToolUseContext.messages}，CC {@code query.ts:744-746}）拿到的是
 *       {@code modelView()} 而不是全量。</li>
 * </ol>
 *
 * <p><b>RED teeth（变异验证靶点）</b>：
 * <ul>
 *   <li>{@code AgentState.modelView()} 改回 {@code return rawMessages();}（取消投影）→
 *       {@link #modelView_excludesPreBoundaryHistory_rawKeepsIt()} 与
 *       {@link #loop_modelFaceUnchanged_rawRetainsPreBoundary()} 必须 fail。</li>
 *   <li>把 {@code toolExecContext} 的 {@code state.modelView()} 改回 {@code state.rawMessages()}
 *       → {@link #toolExecContext_perTurnMessagesIsModelView_notRaw()} 必须 fail。</li>
 *   <li>恢复入口 {@code state.replaceMessages(...)} 破坏性写回 → 同上两条必须 fail。</li>
 * </ul>
 */
class AgentStateDualViewTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. 核心：两个视图**同一时刻并存**
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("核心：rawMessages() 保留 pre-boundary 历史，modelView() 不含它（同一时刻并存、互不影响）")
    void modelView_excludesPreBoundaryHistory_rawKeepsIt() {
        // GIVEN: [pre1, compact_boundary, post1]
        AgentState state = newState();
        state.replaceMessages(List.of(
            userMessage("pre1", "被摘要覆盖的旧问题"),
            compactBoundary(),
            userMessage("post1", "压缩后的新问题")));

        // WHEN: 同一时刻分别取两个视图
        List<String> rawIds = ids(state.rawMessages());
        List<String> modelIds = ids(state.modelView());

        // THEN: 全量保活 pre1；模型视图从最后一个 boundary（含）向后派生，不含 pre1
        assertThat(rawIds)
            .as("[D10 双视图 · 视图①] rawMessages() 是全量（= CC query() 的 messages 形参，一直活着）："
                + "pre-boundary 历史必须在内存里可读")
            .containsExactly("pre1", compactBoundaryOf(state).get(0), "post1");
        assertThat(modelIds)
            .as("[D10 双视图 · 视图②] modelView() 不含 pre-boundary（= CC query.ts:523 messagesForQuery）")
            .doesNotContain("pre1")
            .contains("post1");

        // AND: 二者并存不冲突 —— 再次取仍然各自成立（派生是纯函数，无「取过一次就变样」的时序语义）
        assertThat(ids(state.rawMessages()))
            .as("取 modelView() 之后 rawMessages() 仍逐条不变（派生无副作用 / 不写回）")
            .isEqualTo(rawIds);
        assertThat(ids(state.modelView()))
            .as("重复派生幂等")
            .isEqualTo(modelIds);
    }

    @Test
    @DisplayName("派生不写回：modelView() 不改变 state；派生结果与后续 append 解耦（快照语义）")
    void modelView_isDerivedSnapshot_notWrittenBack() {
        AgentState state = newState();
        state.replaceMessages(List.of(userMessage("pre1", "old"), compactBoundary(),
            userMessage("post1", "new")));

        List<ChatMessageDto> before = state.modelView();
        state.appendMessage(userMessage("post2", "later"));

        assertThat(ids(state.rawMessages()))
            .as("派生 modelView() 不得改写 state（改造前 replaceMessages 会当场砍掉 pre1）")
            .containsExactly("pre1", compactBoundaryOf(state).get(0), "post1", "post2");
        assertThat(ids(before))
            .as("先前派生出的列表是快照，不被后续 append 污染（同 CC messagesForQuery 局部数组语义）")
            .doesNotContain("post2");
        assertThat(ids(state.modelView()))
            .as("重新派生能看到新 append 的消息")
            .contains("post2");
    }

    @Test
    @DisplayName("无 boundary / 无 snip 时 modelView() 内容与 rawMessages() 逐条相同（零行为变化前提）")
    void modelView_noBoundaryNorSnip_equalsRaw() {
        AgentState state = newState();
        state.replaceMessages(List.of(userMessage("a", "q1"), userMessage("b", "q2")));

        assertThat(ids(state.modelView()))
            .as("无 compact boundary / 无 snip_boundary → 投影原样返回（BoundaryReader 既有无 boundary 短路）")
            .isEqualTo(ids(state.rawMessages()));
    }

    @Test
    @DisplayName("snip：modelView() 剔除 removedUuids，rawMessages() 保活（回放门作用在模型视图上）")
    void modelView_excludesSnipped_rawKeepsThem() {
        AgentState state = newState();
        state.replaceMessages(List.of(
            userMessage("u0", "会被 snip 掉的 1"),
            userMessage("u1", "会被 snip 掉的 2"),
            snipBoundary("snip-1", List.of("u0", "u1")),
            userMessage("u2", "保留")));

        assertThat(ids(state.modelView()))
            .as("modelView() = snip 投影面（N2 回放门恒定执行）")
            .containsExactly("snip-1", "u2");
        assertThat(ids(state.rawMessages()))
            .as("rawMessages() 全量保活被 snip 消息（改造前被入口写回砍掉，只剩 DB）")
            .containsExactly("u0", "u1", "snip-1", "u2");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 消费点选边：模型面消费点拿到的必须是 modelView()
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("消费点[toolExecContext]：per-turn ToolUseContext.messages = modelView()（CC query.ts:744-746）")
    void toolExecContext_perTurnMessagesIsModelView_notRaw() {
        AgentState state = newState();
        state.replaceMessages(List.of(
            userMessage("pre1", "pre-boundary"),
            compactBoundary(),
            userMessage("post1", "post-boundary")));
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-dual-view");

        AgentLoopContext ctx = TestContexts.agentLoopContext(null,
            Mockito.mock(LlmProviderFactory.class), null, null, null);
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(ctx, baseTuc, state, null);

        assertThat(perTurn).as("baseTuc / state 齐备 → 派生 per-turn TUC").isNotNull();
        List<String> tucIds = perTurn.messages().stream()
            .map(m -> ((ChatMessageDto) m).id()).toList();
        assertThat(tucIds)
            .as("模型面消费点必须取 modelView()：per-turn TUC 是工具（SnipTool/SubagentTool fork 上下文）"
                + "的上下文来源，含 pre-boundary 会让被摘要内容经工具通道回流")
            .doesNotContain("pre1")
            .contains("post1");
        assertThat(tucIds)
            .as("与 state.modelView() 同源")
            .isEqualTo(ids(state.modelView()));
        assertThat(ids(state.rawMessages()))
            .as("rawMessages() 不受影响（全量保活）")
            .contains("pre1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 端到端：模型面行为不变 + 全量保活
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端：真正发给 provider 的消息链不含 pre-boundary（模型面行为不变），rawMessages() 保活")
    void loop_modelFaceUnchanged_rawRetainsPreBoundary() {
        AgentState state = newState();
        state.replaceMessages(List.of(
            userMessage("pre1", "被摘要覆盖的旧问题"),
            compactBoundary(),
            userMessage("post1", "压缩后的新问题")));

        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams callerParams0 = forLoopParams(ctx, state);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams0.deps().context(), callerParams0, state), state, new ArrayList<>());

        assertThat(histories).as("LLM 至少被调用一次").isNotEmpty();
        List<String> sentIds = histories.get(histories.size() - 1).stream()
            .map(m -> m.id()).toList();
        assertThat(sentIds)
            .as("模型面行为不变（回归底线）：发给模型的链仍从最后一个 compact boundary 起，不含 pre1")
            .doesNotContain("pre1")
            .contains("post1");
        assertThat(ids(state.rawMessages()))
            .as("同一时刻 rawMessages() 仍保活 pre1 —— 这正是改造的意义（改造前此处只剩 post1）")
            .contains("pre1");
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static AgentState newState() {
        return new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    private static List<String> ids(List<ChatMessageDto> messages) {
        List<String> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            out.add(m.id());
        }
        return out;
    }

    /** 取出 state 里那个 compact boundary 的 id（boundary 由 CompactBoundaryMessage 生成，id 不可预知）。 */
    private static List<String> compactBoundaryOf(AgentState state) {
        return state.rawMessages().stream()
            .filter(m -> m.subtype() != null && "compact_boundary".equals(m.subtype()))
            .map(ChatMessageDto::id)
            .toList();
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto compactBoundary() {
        return CompactBoundaryMessage.createCompactBoundaryMessage("auto", 100, null, null, null)
            .toChatMessageDto();
    }

    /** snip_boundary（subtype + snipMetadata.removedUuids 承载，CC snipCompact.ts:99-106）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("removedUuids", removedUuids);
        return new ChatMessageDto(
            id, "s", Role.system, "system", "snip boundary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, "snip_boundary",
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    /** provider 正常完成并捕获每次请求的 history（模型面证据）。 */
    private static LlmProviderFactory capturingProviderFactory(List<List<ChatMessageDto>> sink) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            sink.add(new ArrayList<>(inv.getArgument(3)));
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("response");
            onMsg.accept(new AssistantMessage("response", "end_turn", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
