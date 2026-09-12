package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [E-1a] fork 收敛前置机制单测（与具体循环解耦的部分）。
 *
 * <p><b>WHY 本测试存在（意图 · CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>usage 累加器</b>：fork 的 usage 必须「全程累计」而非「末尾单轮」—— CC
 *       {@code accumulateUsage}（forkedAgent.ts:557-566）逐条累加；既有
 *       {@code SubagentExecutor.extractUsageFromMessages} 是 last-only，不能复用（复用 ⇒ 累计
 *       退化为末尾单轮，token 上报系统性偏低）。</li>
 *   <li><b>产出消息切片</b>：fork 的产出面 = {@code ForkedAgentResult.messages()}（只有产出），
 *       主循环的 {@code finalState.rawMessages()} = 初始 + 产出 —— 不切片会让
 *       {@code lastAssistantMessage} 取到<b>父上下文</b>的末尾 assistant（E-1b 的真实坑）。</li>
 *   <li><b>canUseTool fail-loud</b>：后台 fork 来源漏注入受限 canUseTool ⇒ 静默回落全权限
 *       （INV-6 破坏）—— 必须 fail loud，不得静默降级。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：把累加器改回「取末尾一条」→ 用例 1/2 RED；切片改回全量 → 用例 4 RED；
 * 去掉 queryLoop 入口的 fork canUseTool 守卫 → 用例 5 RED。
 */
@DisplayName("[E-1a] fork 收敛前置机制：usage 累加 / 产出切片 / fork canUseTool fail-loud")
class E1aForkConvergenceHelpersTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. usage 累加器（AgentUsage.accumulateFromMessages · 对齐 CC accumulateUsage）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("usage 累加: 多轮逐字段相加（input/output/cacheRead/cacheCreate 全字段）")
    void accumulateUsage_multiTurn_sumsEveryField() {
        List<ChatMessageDto> messages = List.of(
            userMessage("u1", "q"),                       // 无 usage → 累加 0
            assistantWithUsage("a1", 100, 20, 7L, 3L),
            assistantWithUsage("a2", 200, 40, 11L, 5L));

        AgentUsage total = AgentUsage.accumulateFromMessages(messages);

        assertThat(total.inputTokens()).as("input 逐轮相加（100+200）").isEqualTo(300L);
        assertThat(total.outputTokens()).as("output 逐轮相加（20+40）").isEqualTo(60L);
        assertThat(total.cacheReadInputTokens()).as("cacheRead 逐轮相加（7+11）").isEqualTo(18L);
        assertThat(total.cacheCreationInputTokens()).as("cacheCreate 逐轮相加（3+5）").isEqualTo(8L);
        assertThat(total.totalTokens(false)).as("非 anthropic 求和口径 = input+output = 360")
            .isEqualTo(360L);
    }

    @Test
    @DisplayName("usage 累加: 单轮 = 该轮值（不放大不缩小）")
    void accumulateUsage_singleTurn_equalsThatTurn() {
        AgentUsage total = AgentUsage.accumulateFromMessages(
            List.of(assistantWithUsage("a1", 42, 8, 1L, 2L)));

        assertThat(total.inputTokens()).isEqualTo(42L);
        assertThat(total.outputTokens()).isEqualTo(8L);
        assertThat(total.cacheReadInputTokens()).isEqualTo(1L);
        assertThat(total.cacheCreationInputTokens()).isEqualTo(2L);
    }

    @Test
    @DisplayName("usage 累加: 无 usage / null cache 字段 / null 列表 → 累加 0（不 NPE）")
    void accumulateUsage_nullSafety() {
        // 无任何 usage（旧/DB 水合消息）
        assertThat(AgentUsage.accumulateFromMessages(List.of(userMessage("u1", "q"))))
            .as("无 usage → 全零 EMPTY")
            .isEqualTo(AgentUsage.EMPTY);
        // cache 字段 null（provider 未上报 cache）→ 按 0 累加，input/output 照常
        AgentUsage partial = AgentUsage.accumulateFromMessages(
            List.of(assistantWithUsage("a1", 10, 4, null, null)));
        assertThat(partial.inputTokens()).isEqualTo(10L);
        assertThat(partial.outputTokens()).isEqualTo(4L);
        assertThat(partial.totalTokens(true)).as("null cache 字段按 0（CC ?? 0）").isEqualTo(14L);
        // null / 空列表
        assertThat(AgentUsage.accumulateFromMessages(null)).isEqualTo(AgentUsage.EMPTY);
        assertThat(AgentUsage.accumulateFromMessages(List.of())).isEqualTo(AgentUsage.EMPTY);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 产出消息切片（AgentLoopContext.producedMessages）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("产出切片: 初始以 assistant 结尾时，切片不得包含父上下文的 assistant（lastAssistantMessage 取错消息根因）")
    void producedMessages_initialEndsWithAssistant_sliceExcludesParentAssistant() {
        // 初始前缀 = [user, assistant(父的回复)]（fork 前缀/forkContextMessages 的真实形态之一）
        List<ChatMessageDto> all = new ArrayList<>(List.of(
            userMessage("u0", "父问题"),
            assistantText("a-parent", "父的回复"),
            // ↓ 产出（本 query 产出）
            userMessage("u1", "fork 提示"),
            assistantText("a-child", "fork 产出")));
        int initialCount = 2;   // 循环启动前 state.rawMessages().size()

        List<ChatMessageDto> produced = AgentLoopContext.producedMessages(all, initialCount);

        assertThat(produced).as("产出面只含本 query 产出的消息").hasSize(2);
        assertThat(produced).extracting(ChatMessageDto::id).containsExactly("u1", "a-child");
        assertThat(lastAssistantId(all))
            .as("不切片 → 取到父上下文的末尾 assistant（若产出为空则完全取错）")
            .isEqualTo("a-child");
        assertThat(lastAssistantId(List.of(all.get(0), all.get(1))))
            .as("父前缀自身的末尾 assistant —— 切片后不得被当成产出")
            .isEqualTo("a-parent");
        // 产出为空（循环未产出任何消息）→ 切片 = 空（不得把父前缀的 assistant 当产出）
        assertThat(AgentLoopContext.producedMessages(all, all.size())).isEmpty();
        assertThat(AgentLoopContext.producedMessages(all, 99)).as("越界 initialCount → 空").isEmpty();
        assertThat(AgentLoopContext.producedMessages(null, 0)).as("null → 空").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. fork canUseTool fail-loud（queryLoop 入口守卫）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fail-loud: 后台 fork 来源 + canUseTool=null → queryLoop 入口抛 IllegalStateException（不静默全权限）")
    void queryLoop_backgroundForkSource_nullCanUseTool_failsLoud() {
        for (QuerySource forkSource : List.of(QuerySource.COMPACT, QuerySource.SESSION_MEMORY,
            QuerySource.EXTRACT_MEMORIES, QuerySource.AUTO_DREAM)) {
            AgentState state = new AgentState("sys", sessionId(), null);
            state.appendMessage(userMessage("u1", "q"));
            QueryParams params = QueryParams.forLoop(
                state.rawMessages(), null,
                ToolUseContext.of(UUID.randomUUID(), sessionId()),
                forkSource, "test-model", null, null, null, null, null,
                minimalDeps(), null);

            assertThatThrownBy(() -> LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>()))
                .as("fork 来源 %s 漏注入 canUseTool 必须 fail loud（INV-6 · 对齐 ProductionForkedQuery 同判据）",
                    forkSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canUseTool");
        }
    }

    @Test
    @DisplayName("值域: 主线程/子代理来源不在屏蔽档（回落 permissionGate 现状不变）；仅 4 个后台 fork 来源在内")
    void isBackgroundForkSource_scopeIsExactlyFourBackgroundForks() {
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.COMPACT)).isTrue();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.SESSION_MEMORY)).isTrue();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.EXTRACT_MEMORIES)).isTrue();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.AUTO_DREAM)).isTrue();
        // 子代理（FORK/SUBAGENT/HOOK_AGENT/WORKFLOW）与主线程来源**不在**屏蔽档 ——
        //   它们在 CC 侧走同一 query() 且确享这些能力，改门会变更现网行为（本批红线）。
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.FORK)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.SUBAGENT)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.HOOK_AGENT)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.WORKFLOW)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.USER)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.REPL_MAIN_THREAD)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(QuerySource.MARBLE_ORIGAMI)).isFalse();
        assertThat(QuerySource.isBackgroundForkSource(null)).isFalse();
    }

    // ── 测试工具 ──

    /** 最小 deps（canUseTool 守卫在 queryLoop 入口，早于任何 provider/工具使用 → 无需真实 bean）。 */
    private static com.nexusai.application.agent.loop.LoopDeps minimalDeps() {
        return new com.nexusai.application.agent.loop.LoopDeps() {
            @Override public AgentLoopContext context() {
                return com.nexusai.application.agent.TestContexts
                    .agentLoopContext(null, null, null, null, null);
            }
        };
    }

    private static String sessionId() {
        return "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String lastAssistantId(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.assistant) {
                return messages.get(i).id();
            }
        }
        return null;
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto assistantText(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto assistantWithUsage(String id, long input, long output,
                                                     Long cacheRead, Long cacheCreate) {
        return assistantText(id, "reply").withUsage(
            new AgentUsage(input, output, cacheCreate, cacheRead, null, null, null));
    }
}
