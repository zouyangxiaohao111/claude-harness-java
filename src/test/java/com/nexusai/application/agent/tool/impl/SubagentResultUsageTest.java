package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4] usage 对象补齐 RED-GREEN 双证测试 (P1 差异项 2).
 *
 * <p>规则九 (验证意图): 父 Agent 依赖 usage/totalTokens 做 token budget 决策 (CC agentToolUtils.ts:319/355
 * finalizeAgentTool). 旧 Java buildResultTrailer total_tokens 占位 "N/A" = 父 Agent 无法决策
 * (规则十二 Fail loud 约束). 本测试验证 SubagentResult 携带 usage + buildResultTrailer 渲染真实 token.
 *
 * <p>测试方式 (seam 模式): extractUsageFromMessages / extractTotalTokens 是 package-private static
 * seam, execute()/runSubagentQueryLoop 真实调用. RED 依据: 这些 seam + SubagentResult 新字段在 S4
 * 实施前不存在.
 */
@DisplayName("[S4] usage 对象补齐 (SubagentResult.totalTokens/usage / extractUsage / trailer 非 N/A)")
class SubagentResultUsageTest {

    private static final String SESSION = UUID.randomUUID().toString();

    private static ChatMessageDto assistant(String content, Integer in, Integer out) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.assistant, null, content, null,
            null, null, in, out, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** [DEC-04] 携带完整 usage 的 assistant 消息（provider 解析路径 · withUsage 填充）。 */
    private static ChatMessageDto assistantWithUsage(String content, AgentUsage usage) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.assistant, null, content, null,
            null, null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of()).withUsage(usage);
    }

    @Test
    @DisplayName("SubagentResult.completed 携带 totalTokens + usage (对齐 CC agentToolResultSchema totalTokens + usage)")
    void subagentResult_shouldCarryTotalTokensAndUsage_notPlaceholderNA() {
        // WHY: 父 Agent 依赖 SubagentResult.totalTokens()/usage() 做 token budget 决策 —
        //   若字段缺失, 父 Agent 只能猜 (CC agentToolUtils.ts:237/238-256 schema 必需字段).
        AgentUsage usage = AgentUsage.fromInputOutput(120, 30);
        SubagentExecutor.SubagentResult result =
            SubagentExecutor.SubagentResult.completed("结论", 5, 1000L, "agent-1", 150L, usage);

        assertThat(result.totalTokens()).as("totalTokens 必须透传 (CC :237/319)").isEqualTo(150L);
        assertThat(result.usage()).as("usage 必须非 null").isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(120L);
        assertThat(result.usage().outputTokens()).isEqualTo(30L);
        assertThat(result.status()).isEqualTo("completed");
    }

    @Test
    @DisplayName("extractUsageFromMessages 取末尾 assistant 消息 tokens (对齐 CC getLastAssistantMessage.usage)")
    void extractUsageFromMessages_shouldTakeLastAssistantMessageTokens() {
        // WHY: finalizeAgentTool (CC :319) 从 getLastAssistantMessage(message.usage) 提取 — 末尾 assistant
        //   消息即使无 text (纯 tool_use 块) 也带 usage. Java 必须同源, 否则 usage 依赖文本消息顺序错乱.
        List<ChatMessageDto> messages = List.of(
            assistant("第一条", 50, 10),
            assistant("", 60, 15),   // 末尾 assistant 无 text (纯 tool_use) 仍携带 usage
            new ChatMessageDto(UUID.randomUUID().toString(), SESSION, Role.user, null, "后置 user 消息",
                null, null, null, null, null, null, OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));

        AgentUsage usage = SubagentExecutor.extractUsageFromMessages(messages);

        assertThat(usage.inputTokens()).as("必须取末尾 assistant (非首条) 的 usage").isEqualTo(60L);
        assertThat(usage.outputTokens()).isEqualTo(15L);
    }

    @Test
    @DisplayName("extractTotalTokens = input + output 求和 (对齐 CC getTokenCountFromUsage)")
    void extractTotalTokens_shouldSumInputAndOutput() {
        List<ChatMessageDto> messages = List.of(assistant("结论", 100, 25));
        assertThat(SubagentExecutor.extractTotalTokens(messages)).isEqualTo(125L);
    }

    @Test
    @DisplayName("[DEC-04] extractUsageFromMessages 读取完整 usage 对象 (含 cache 字段, 对齐 CC message.usage 透传)")
    void extractUsageFromMessages_shouldReadFullUsageObject() {
        // WHY: finalizeAgentTool (CC :355) 透传 lastAssistantMessage.message.usage 对象, Java 旧实现
        //   只从 inputTokens/outputTokens 2 字段投影 (恒 null → 恒 0, DEC-04 症状). provider 解析的
        //   完整 AgentUsage 必须原样到达 SubagentResult, 否则 cache_creation/cache_read 丢失.
        AgentUsage full = new AgentUsage(120L, 30L, 50L, 60L,
            null, null, null); // 4 token 字段真实值 (cache_creation=50, cache_read=60)
        List<ChatMessageDto> messages = List.of(
            assistantWithUsage("无 cache 的首条", new AgentUsage(10L, 5L, 0L, 0L, null, null, null)),
            assistantWithUsage("", full));   // 末尾 assistant 携带完整 usage

        AgentUsage usage = SubagentExecutor.extractUsageFromMessages(messages);

        assertThat(usage.inputTokens()).as("必须取末尾 assistant 的完整 usage").isEqualTo(120L);
        assertThat(usage.outputTokens()).isEqualTo(30L);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(50L);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(60L);
    }

    @Test
    @DisplayName("[R32-06] extractUsageFromMessages 保留嵌套 usage 字段 (server_tool_use/service_tier/cache_creation 透传)")
    void extractUsageFromMessages_shouldPreserveNestedUsageFields() {
        // WHY: CC finalizeAgentTool (agentToolUtils.ts:355) 透传 message.usage 完整对象, 嵌套字段
        //   (server_tool_use/service_tier/cache_creation, agentToolUtils.ts:243-255) 必须原样到达
        //   SubagentResult. AnthropicSdkProvider 解析后经 ChatMessageDto.usage → extractUsageFromMessages
        //   透传链不得丢弃, 否则父 Agent 拿不到 server tool use 计数 / service tier (R32-06).
        AgentUsage full = new AgentUsage(
            100L, 30L, 40L, 20L,
            new AgentUsage.ServerToolUse(2L, 3L),
            "batch",
            new AgentUsage.CacheCreation(400L, 500L));
        List<ChatMessageDto> messages = List.of(assistantWithUsage("结论", full));

        AgentUsage usage = SubagentExecutor.extractUsageFromMessages(messages);

        assertThat(usage.serverToolUse()).as("server_tool_use 必须透传 (CC agentToolUtils.ts:243-248)").isNotNull();
        assertThat(usage.serverToolUse().webSearchRequests()).isEqualTo(2L);
        assertThat(usage.serverToolUse().webFetchRequests()).isEqualTo(3L);
        assertThat(usage.serviceTier()).as("service_tier 必须透传 (CC agentToolUtils.ts:249)").isEqualTo("batch");
        assertThat(usage.cacheCreation()).as("cache_creation 必须透传 (CC agentToolUtils.ts:250-255)").isNotNull();
        assertThat(usage.cacheCreation().ephemeral1hInputTokens()).isEqualTo(400L);
        assertThat(usage.cacheCreation().ephemeral5mInputTokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("[DEC-04] extractUsageFromMessages 无 usage 对象时回退 2 字段投影 (DB/旧消息)")
    void extractUsageFromMessages_shouldFallbackToInputOutputProjection() {
        // WHY: DB 持久化/旧构造消息只有 inputTokens/outputTokens (无 usage 对象), 回退投影不丢数据,
        //   与 CC 结果链 usage 非空不变量共存 (fromInputOutput 保留为投影工厂, 非兜底伪造).
        List<ChatMessageDto> messages = List.of(assistant("旧消息", 88, 12));

        AgentUsage usage = SubagentExecutor.extractUsageFromMessages(messages);

        assertThat(usage.inputTokens()).isEqualTo(88L);
        assertThat(usage.outputTokens()).isEqualTo(12L);
        assertThat(usage.cacheReadInputTokens()).isNull();
    }

    @Test
    @DisplayName("[DEC-04] extractTotalTokens 对齐 CC getTokenCountFromUsage 4 字段求和 (input+cache_creation+cache_read+output)")
    void extractTotalTokens_shouldSumAllFourFields() {
        // WHY: CC getTokenCountFromUsage (tokens.ts:46-53) = input + (cache_creation??0) + (cache_read??0) + output.
        //   旧 Java extractTotalTokens 只算 input+output → 带 cache 的真实 usage 低估 totalTokens,
        //   父 Agent token budget 决策错 (DEC-04).
        AgentUsage full = new AgentUsage(100L, 25L, 40L, 15L, null, null, null);
        List<ChatMessageDto> messages = List.of(assistantWithUsage("结论", full));

        assertThat(SubagentExecutor.extractTotalTokens(messages))
            .as("100+40+15+25=180 (CC getTokenCountFromUsage)")
            .isEqualTo(180L);
    }

    @Test
    @DisplayName("[DEC-04] SubagentResult.usage 必填非空 (null → requireNonNull 失败, 对齐 CC usage 非空 schema)")
    void subagentResult_shouldRejectNullUsage() {
        // WHY: CC agentToolResultSchema usage 必填非 nullable (EV-DPA-001), finalizeAgentTool 无 null 兜底.
        //   Java 旧工厂 null-coalesce 到 EMPTY (静默掩盖数据源缺失), 与 CC 契约相悖 → requireNonNull.
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
            SubagentExecutor.SubagentResult.completed("结论", 1, 100L, "agent-1", 10L, null));
    }

    @Test
    @DisplayName("buildResultTrailer 渲染真实 total_tokens, 不再占位 N/A (Fail loud 约束)")
    void buildResultTrailer_shouldRenderRealTotalTokens_notNA() {
        // WHY: trailer 是父 Agent 可读的 token 凭证, "N/A" = 信息丢失 (父 Agent 无法做 token budget
        //   决策). CC AgentTool.tsx:1369 <usage>total_tokens: ... 来自 finalizeAgentTool 的 totalTokens.
        String trailer = SubagentTool.buildResultTrailer(
            "general-purpose", "agent-42", 3, 500L, 1234L, false);

        assertThat(trailer)
            .as("trailer 必须含真实 total_tokens, 不含 N/A (规则十二)")
            .contains("total_tokens: 1234")
            .doesNotContain("N/A")
            .contains("tool_uses: 3")
            .contains("duration_ms: 500");
    }

    @Test
    @DisplayName("one-shot built-in agent 仍跳过 trailer (CC AgentTool.tsx:1356 skip 条件不变)")
    void buildResultTrailer_oneShotBuiltIn_shouldSkip() {
        // WHY: one-shot (Explore/Plan) 不走 SendMessage 续接, trailer 无意义 — skip 逻辑不能被
        //   totalTokens 改造破坏 (ATS-12 不变式).
        String trailer = SubagentTool.buildResultTrailer(
            "Explore", "agent-7", 0, 100L, 10L, false);
        assertThat(trailer).as("one-shot && !worktree → 空串").isEmpty();
    }
}
