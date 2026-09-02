package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-19 · token 助手族 CC 契约测试（X-1..X-4 · tokens.ts:123-199）+ △-1 双端同源断言。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code utils/tokens.ts} 的 4 个助手
 * （{@code messageTokenCountFromLastAPIResponse} / {@code getCurrentUsage} /
 * {@code doesMostRecentAssistantMessageExceed200k} / {@code getAssistantMessageContentLength}）
 * 是 token 用量族在「仅 output」「四元组」「200k 阈值」「spinner 字符长度」四个侧面的 CC 语义
 * 权威（探查 X-1..X-4，目标端 0 实现命中）。本测试锁定其 CC 公式精确值：
 * <ol>
 *   <li>{@code messageTokenCountFromLastAPIResponse} 尾向回扫最近 usage → 仅 {@code output_tokens}
 *       （非 input 非全量）</li>
 *   <li>{@code getCurrentUsage} 尾向回扫最近 usage → 4 字段对象（cache 缺省 0）；无 → null</li>
 *   <li>{@code doesMostRecentAssistantMessageExceed200k} 按<b>最后一条 assistant 消息</b>（非 usage
 *       回扫）判 200_000 阈值</li>
 *   <li>{@code getAssistantMessageContentLength} 只统计 text/thinking/redacted_thinking/tool_use
 *       （signature_delta 等非模型输出块排除）</li>
 * </ol>
 *
 * <p><b>△-1 双端同源</b>: {@code CompactConversation.tokenCountWithEstimation}（本地简化版，
 * 无 sibling 回溯）收敛到 {@code Tokens.tokenCountWithEstimation}（canonical，usage-walk +
 * 同 id sibling 回溯）后，两入口对同一交错消息列表必须给出同一值（含回溯语义）。
 *
 * <p><b>RED teeth</b>: 4 助手在实现前不存在（test-compile FAIL）；每用例断言 CC 公式精确值，
 * 任何口径偏移（output 误用 input / 阈值误用 usage-walk / 漏计 thinking / 双端不同源）都会 FAIL。
 */
@DisplayName("[IMP2-19] token 助手族（messageTokenCountFromLastAPIResponse/getCurrentUsage/doesMostRecentAssistantMessageExceed200k/getAssistantMessageContentLength）+ △-1 双端同源")
class TokenHelpersCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 消息构造 helper ──
    private static ChatMessageDto msg(String id, Role role, String content, Integer input, Integer output,
                                      String assistantMessageId, List<?> blocks, String reasoning,
                                      List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, "s1", role, role.name(), content, reasoning, toolCalls, FinishReason.stop,
            input, output, "刚刚", OffsetDateTime.now(), null, assistantMessageId,
            null, blocks, List.of());
    }

    private static ChatMessageDto assistant(String id, String content, Integer input, Integer output) {
        return msg(id, Role.assistant, content, input, output, id, List.of(), null, List.of());
    }

    private static ChatMessageDto user(String id, String content) {
        return msg(id, Role.user, content, null, null, null, List.of(), null, List.of());
    }

    private static ChatMessageDto toolResult(String id, String content, String assistantMessageId) {
        return msg(id, Role.tool, content, null, null, assistantMessageId, List.of(), null, List.of());
    }

    private static ObjectNode block(String type, String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        if (text != null) {
            node.put(type.equals("redacted_thinking") ? "data" : type.equals("thinking") ? "thinking" : "text", text);
        }
        return node;
    }

    // ════════════════════════════════════════════════════════════════════════
    // X-1 · messageTokenCountFromLastAPIResponse（tokens.ts:123-136 · 仅 output）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("messageTokenCountFromLastAPIResponse 尾向回扫最近 usage → 仅 output_tokens；无 → 0（tokens.ts:123-136）")
    void messageTokenCountFromLastAPIResponse_returnsOnlyOutputTokens() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "q"),
            assistant("a1", "r1", 100, 20),   // output=20
            user("u2", "q2"),
            assistant("a2", "r2", 50, 10)     // output=10 ← 最近 usage
        );
        // 仅 output（20/10 若误用 input 或全量 input+cache+output 会 FAIL）
        assertThat(Tokens.messageTokenCountFromLastAPIResponse(msgs)).isEqualTo(10);
        // 无 usage → 0
        assertThat(Tokens.messageTokenCountFromLastAPIResponse(
            List.of(user("u1", "q"), user("u2", "r")))).isZero();
        assertThat(Tokens.messageTokenCountFromLastAPIResponse(List.of())).isZero();
        assertThat(Tokens.messageTokenCountFromLastAPIResponse(null)).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // X-2 · getCurrentUsage（tokens.ts:138-157 · 4 字段对象）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getCurrentUsage 尾向回扫最近 usage → 4 字段 Usage；无 → null（tokens.ts:138-157）")
    void getCurrentUsage_returnsFourFieldUsageOrNull() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "q"),
            assistant("a1", "r1", 100, 20),
            user("u2", "q2"),
            assistant("a2", "r2", 50, 10)     // ← 最近 usage
        );
        Tokens.Usage usage = Tokens.getCurrentUsage(msgs);
        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).isEqualTo(50);
        assertThat(usage.outputTokens()).isEqualTo(10);
        // cache 缺省 0（Java DTO 无 cache 字段 · S4-2b）
        assertThat(usage.cacheReadInputTokens()).isZero();
        assertThat(usage.cacheCreationInputTokens()).isZero();

        // 无 usage → null（非 0 值记录）
        assertThat(Tokens.getCurrentUsage(List.of(user("u1", "q"), user("u2", "r")))).isNull();
        assertThat(Tokens.getCurrentUsage(List.of())).isNull();
        assertThat(Tokens.getCurrentUsage(null)).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // X-3 · doesMostRecentAssistantMessageExceed200k（tokens.ts:159-168 · findLast assistant）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("doesMostRecentAssistantMessageExceed200k 按最后一条 assistant 的 usage 全量判 200k 阈值（tokens.ts:159-168）")
    void doesMostRecentAssistantMessageExceed200k_usesLastAssistantNotUsageWalk() {
        // 最后 assistant usage 全量 > 200_000 → true
        List<ChatMessageDto> over = List.of(
            user("u1", "q"),
            assistant("a1", "r1", 250_000, 1_000)   // 全量 251_000 > 200k
        );
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(over)).isTrue();

        // 阈值边界：恰好 200_000 不超（CC > THRESHOLD，非 ≥）
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(
            List.of(assistant("a2", "r2", 200_000, 0)))).isFalse();
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(
            List.of(assistant("a3", "r3", 199_999, 1)))).isFalse();

        // 最后 assistant 无 usage → false（即使更早 assistant 超阈值）
        List<ChatMessageDto> lastNoUsage = List.of(
            assistant("a4", "huge", 300_000, 100),   // 超阈值但非最后
            user("u2", "q2"),
            assistant("a5", "no usage", null, null)  // 最后 assistant 无 usage
        );
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(lastNoUsage)).isFalse();

        // 无 assistant → false
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(
            List.of(user("u1", "q"), user("u2", "r")))).isFalse();
        assertThat(Tokens.doesMostRecentAssistantMessageExceed200k(List.of())).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // X-4 · getAssistantMessageContentLength（tokens.ts:183-199 · spinner 字符长度）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAssistantMessageContentLength 逐 block：text/thinking/redacted_thinking/tool_use，signature 类排除（tokens.ts:183-199）")
    void getAssistantMessageContentLength_countsModelOutputBlocksOnly() {
        ObjectNode toolUse = JSON.createObjectNode();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "Read");
        toolUse.set("input", JSON.createObjectNode().put("path", "/a/b"));
        // jsonStringify(input) = {"path":"/a/b"} → 15 chars

        List<?> blocks = List.of(
            block("text", "ABCDEFGH"),                    // 8
            block("thinking", "XYZ"),                     // 3
            block("redacted_thinking", "dd"),             // 2（data）
            toolUse,                                       // jsonStringify(input).length = 15
            block("text", null),                           // 0
            JSON.createObjectNode().put("type", "signature_delta") // 非模型输出块 → 0
        );
        ChatMessageDto asst = msg("a1", Role.assistant, null, null, null, "a1", blocks, null, List.of());
        assertThat(Tokens.getAssistantMessageContentLength(asst)).isEqualTo(8 + 3 + 2 + 15);

        // Java 扁平兜底（无 contentBlocks）：content + reasoning + toolCalls(arguments)
        ChatMessageDto flat = msg("a2", Role.assistant, "hello", null, null, "a2", List.of(), "think",
            List.of(new ToolCallDto("c1", "Read", "{\"path\":\"/a/b\"}", null, null)));
        assertThat(Tokens.getAssistantMessageContentLength(flat))
            .isEqualTo("hello".length() + "think".length() + "{\"path\":\"/a/b\"}".length());

        // 非 assistant → 0
        assertThat(Tokens.getAssistantMessageContentLength(user("u1", "hi"))).isZero();
        assertThat(Tokens.getAssistantMessageContentLength(null)).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // △-1 · 双端同源（CompactConversation.tokenCountWithEstimation 收敛到 Tokens canonical）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-1 双端同源：CompactConversation.tokenCountWithEstimation == Tokens.tokenCountWithEstimation（含 sibling 回溯）")
    void compactConversationTokenCountWithEstimation_isSameSourceAsTokens() {
        // 并行工具调用交错流：同 id 分块 + 交错 tool_result —— 无 sibling 回溯会欠估（欠估值 124）
        List<ChatMessageDto> msgs = List.of(
            assistant("A", "", 100, 20),           // 首个 sibling · usage total=120
            toolResult("t1", "ABCDEFGH", "A"),     // rough 2
            assistant("A", "ABCDEFGH", 100, 20),   // 最后 sibling（同 id）
            toolResult("t2", "ABCDEFGH", "A"),     // rough 2
            user("u1", "ABCDEFGH")                 // rough 2
        );
        // 双入口同源（回溯语义一致 → 128；本地简化版会返回 124 → FAIL）
        assertThat(CompactConversation.tokenCountWithEstimation(msgs))
            .isEqualTo(Tokens.tokenCountWithEstimation(msgs))
            .isEqualTo(128);

        // 无 usage → 全量 rough，双入口同源
        List<ChatMessageDto> noUsage = List.of(user("u1", "ABCDEFGH"), user("u2", "ABCDEFGH"));
        assertThat(CompactConversation.tokenCountWithEstimation(noUsage))
            .isEqualTo(Tokens.tokenCountWithEstimation(noUsage))
            .isEqualTo(4);
    }
}
