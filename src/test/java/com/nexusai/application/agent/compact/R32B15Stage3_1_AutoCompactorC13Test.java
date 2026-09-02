package com.nexusai.application.agent.compact;

import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 Stage 3.1 C13 · AutoCompactor CC 语义断言测试 ·
 * 对齐 CC services/compact/compact.ts 单流程 5 事件模型 (INV-1) + autoCompact.ts 熔断器 (INV-5)。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 本文件旧版断言 AutoCompactor 内部
 * "3 触发点" (SESSION_START / POST_COMPACT / COMPACT_END) 由 AutoCompactor 自行 emit，
 * 固化旧 Java 事件模型 (E3 问题)。D-04 事件收敛后 AutoCompactor <b>不再单独 emit</b> 压缩进度
 * 事件 —— 事件由 {@link CompactConversation} 单路径 emit (INV-1，CC compact.ts:406/429/587/719/760)。
 * <b>[GR-3]</b> 旧编排器已删除：原单流程 5 事件顺序断言已归 {@link CompactConversationTest}
 * （compactConversation 内部恰 5 事件，CC 顺序）。本测试保留 AutoCompactor 自身的 CC 语义断言：
 * <ol>
 *   <li>熔断器语义: 连续失败 ≥3 停止 (INV-5，autoCompact.ts:260-265)</li>
 *   <li>递归守卫 querySource: session_memory/compact → false (INV-6)</li>
 *   <li>L4 真实 LLM 摘要 (StreamCompactSummary，非硬编码 CompactCallback)</li>
 * </ol>
 *
 * <p>[IMP2-24 T-1/T-4/T-6/T-8] AutoCompactor.onCompactProgress 字段+setter /
 * setContextWindow / setModel / AutoCompactResult.isEffective 死面已删（set-only/零消费）；
 * 进度事件单链经 CompactConversationContext（5 事件归属见 CompactConversationTest），
 * 窗口统一 CompactThresholdSystem.getContextWindowForModel（CompactThresholdSystemTest 覆盖）。
 *
 * <p><b>说明</b>: 熔断器/守卫/SM/PTL 全量覆盖在 {@link AutoCompactorCcContractTest} (IMP-07)，
 * 本测试聚焦 AutoCompactor 自身 CC 语义（D-04 事件归属见 CompactConversationTest）。
 */
class R32B15Stage3_1_AutoCompactorC13Test {

    private static final String MODEL = "claude-sonnet-4-5-20250929";

    /** 恒定高 token 桩 (确定性越过阈值) · 对齐 AutoCompactorCcContractTest 约定，非按消息数线性计数的旧式假桩 */
    private static final TokenCounter HIGH_TOKEN = msgs -> 200_000;

    /** 真实 TokenEstimator 计数 · 对齐 CC microCompact.ts:164 estimateMessageTokens block 口径 */
    private static final TokenCounter REAL_COUNTER = new TokenEstimator()::estimateMessageTokens;

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
        // [sm-cursor-sessionize] 本文件 AutoCompactor 未设 sessionId → 游标落在 "unknown" 键
        com.nexusai.application.agent.memory.SessionMemoryService.setLastSummarizedMessageId(null, null);
    }

    @Test
    @DisplayName("熔断器: LLM 失败 → consecutiveFailures+1，连续 3 次 → 停止尝试 (INV-5)")
    void llmFailureIncrementsCircuitBreaker() {
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> { throw new RuntimeException("LLM failure"); });

        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(1);

        auto.tryAutoCompact(largeMessages(50));
        auto.tryAutoCompact(largeMessages(50));
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(3);
        assertThat(auto.getTracking().isCircuitBreakerOpen()).isTrue();

        // ≥3 停止: 后续尝试短路返回 no-op，不再执行 L4
        AutoCompactor.AutoCompactResult r = auto.tryAutoCompact(largeMessages(50));
        assertThat(r.wasCompacted()).isFalse();
        assertThat(auto.getTracking().getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("递归守卫: querySource=session_memory/compact → shouldAutoCompact=false (INV-6)")
    void recursiveGuardByQuerySource() {
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        assertThat(auto.shouldAutoCompact(big, "session_memory", 0)).isFalse();
        assertThat(auto.shouldAutoCompact(big, "compact", 0)).isFalse();
        // 非守卫源 user 超阈 → true (阈值真实生效)
        assertThat(auto.shouldAutoCompact(big, "user", 0)).isTrue();
    }

    @Test
    @DisplayName("L4 真实摘要: StreamCompactSummary 生产 → wasCompacted + 摘要文本进入 compacted messages")
    void realSummaryViaStreamCompactSummary() {
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN, realSummaryCallback());

        AutoCompactor.AutoCompactResult result = auto.tryAutoCompact(largeMessages(2000));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source()).isEqualTo("AUTO");
        assertThat(result.messages()).anySatisfy(m ->
            assertThat(m.content()).contains("real summary text"));
        // 无 recordFailure (熔断不触发)
        assertThat(auto.getTracking().getConsecutiveFailures()).isZero();
    }

    // ── 帮手 ──

    /** 真实 L4 摘要回调 (StreamCompactSummary) · 非硬编码 CompactCallback。 */
    private static AutoCompactor.CompactCallback realSummaryCallback() {
        return new StreamCompactSummary(
            () -> llmProviderReturning("<analysis>draft</analysis><summary>real summary text</summary>"),
            () -> MODEL,
            ProviderConfig::empty);
    }

    /** LlmProvider 桩: 流式回调返回 canned 摘要。 */
    private static LlmProvider llmProviderReturning(String text) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                                         List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                                         List<ChatMessageDto> h, com.fasterxml.jackson.databind.node.ArrayNode t,
                                         Integer maxOutputTokensOverride,
                                         com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                         String effortValue, String querySource,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                                         Consumer<String> orc,
                                         Runnable osf, com.nexusai.application.agent.tool.AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oa.accept(new AssistantMessage(text, "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return text;
            }
        };
    }

    private static List<ChatMessageDto> smallMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", java.time.OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        }
        return list;
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        return smallMessages(count);
    }
}
