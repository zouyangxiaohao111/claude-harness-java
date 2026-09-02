package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-01 · L4 真实摘要集成测试（验收标准 1）· 不再熔断。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-01 的全域根因是 L4 no-op
 * （ToolRegistrationConfig CompactCallback 返回 null → recordFailure → 连续失败
 * 3 次熔断）。本测试把 AutoCompactor 的 CompactCallback 换成真实
 * {@link StreamCompactSummary}（CC streamCompactSummary 语义），走 L4 摘要生产：
 * <ol>
 *   <li>L4 走真实 streamCompactSummary（非 no-op）</li>
 *   <li>摘要判据 A9 生效（compact.ts:493-506 仅 null/'' 拒绝；生产路径 = CompactConversation
 *       内联判据 :274-283，CompactConversationTest 覆盖）</li>
 *   <li>无 recordFailure（熔断不触发，tracking 连续失败 = 0）</li>
 * </ol>
 */
class CompactL4RealSummaryIntegrationTest {

    private static final String MODEL = "claude-sonnet-4-5-20250929";

    @Test
    @DisplayName("L4 真实摘要集成: streamCompactSummary 产物 → wasCompacted + 摘要入流 + 无 recordFailure")
    void l4RealSummaryThroughAutoCompactor() {
        // ── L4 生产组件：StreamCompactSummary（真实 CC 语义）──
        StreamCompactSummary streamCompactSummary = new StreamCompactSummary(
            () -> llmProviderReturning("<analysis>draft</analysis><summary>real summary text</summary>"),
            () -> MODEL,
            ProviderConfig::empty);

        // ── AutoCompactor 用真实 callback（不再 no-op）──
        // 恒定高 token 计数：L1-L3（Budget/Snip/Micro）无法把 token 降到阈值下 → needsAutoCompact=true
        // → 真实触发 L4（compactCallback → streamCompactSummary）。
        // 注意：阈值 = 默认窗口 200_000 − reserved 20_000 − 13_000 = 167_000；恒定 200_000 > 阈值，L4 必达
        // （[IMP2-24 T-4/T-9] setContextWindow 通道已删，窗口默认经 CompactThresholdSystem.getContextWindowForModel）。
        TokenCounter tokenCounter = msgs -> 200_000;
        AutoCompactor autoCompactor = new AutoCompactor(tokenCounter, streamCompactSummary);

        // 触发 L4（恒定高 token → L1-L3 降不下来 → 真实走 L4 LLM 摘要）
        AutoCompactor.AutoCompactResult result =
            autoCompactor.tryAutoCompact(largeMessageList(2000));

        // 1. L4 真实摘要：wasCompacted = true
        assertThat(result.wasCompacted()).isTrue();
        // 2. 摘要内容进入 compacted messages
        assertThat(result.messages()).anySatisfy(m ->
            org.assertj.core.api.Assertions.assertThat(m.content())
                .contains("real summary text"));
        // 3. 无 recordFailure：连续失败 = 0（熔断不触发）
        assertThat(autoCompactor.getTracking().getConsecutiveFailures()).isZero();
    }

    // ── 帮手 ──

    /** LlmProvider 桩：流式回调返回 canned 摘要。 */
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
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp) {
                oa.accept(new AssistantMessage(text, "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return text;
            }
        };
    }

    private static List<ChatMessageDto> largeMessageList(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", java.time.OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }
}
