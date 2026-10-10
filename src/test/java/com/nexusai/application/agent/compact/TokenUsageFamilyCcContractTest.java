package com.nexusai.application.agent.compact;

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
 * IMP-17 · token 用量族 CC 契约测试（REQ-26）+ 三口径分离断言（INV-3）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC token 用量族（utils/tokens.ts:7/46/55/79/226 +
 * tokenEstimation.ts:203/327/391）是 token 三口径（INV-3）的唯一权威来源：
 * <ol>
 *   <li>{@code tokenCountWithEstimation}（usage-walk + sibling 回溯）→ 阈值/blocking 输入口径
 *       （query.ts:637）</li>
 *   <li>{@code tokenCountFromLastAPIResponse} → compaction API 用量（compact.ts:629
 *       = postCompactTokenCount）</li>
 *   <li>{@code roughTokenCountEstimationForMessages} → 结果上下文消息载荷粗估（compact.ts:747
 *       = truePostCompactTokenCount）</li>
 * </ol>
 * 三者必须<b>独立实现互不混用</b>（旧 Java 以 len/4+4 单一 char 估算 / CompactConversation 以
 * 简化 content-only rough 混用口径）。本测试锁定 CC 用法族不变量：
 * usage-walk 精确用 usage + 切片 rough（非全量 len/4）；rough 用 round 非 int 截断；sibling 回溯
 * 把交错 tool_result 纳入估算切片。
 *
 * <p><b>RED teeth</b>: {@code Tokens} 类在实现前不存在（test-compile FAIL）；每用例断言
 * CC 公式精确值，任何口径偏移（int 截断 / 无 sibling 回溯 / 三口径互混）都会 FAIL。
 */
@DisplayName("[IMP-17] token 用量族（getTokenUsage/getTokenCountFromUsage/tokenCountFromLastAPIResponse/tokenCountWithEstimation/finalContextTokensFromLastResponse/rough）+ 三口径分离")
class TokenUsageFamilyCcContractTest {

    // ── 消息构造 helper ──
    private static ChatMessageDto msg(String id, Role role, String content, Integer input, Integer output,
                                      String assistantMessageId, List<?> blocks, String reasoning,
                                      List<String> images, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, "s1", role, role.name(), content, reasoning, toolCalls, FinishReason.stop,
            input, output, "刚刚", OffsetDateTime.now(), null, assistantMessageId,
            null, blocks, images);
    }

    /** assistant 消息（可携带 usage + 显式 lineage id，与 LlmAgentLoop 自 lineage 约定一致）。 */
    private static ChatMessageDto assistant(String id, String content, Integer input, Integer output) {
        return msg(id, Role.assistant, content, input, output, id, List.of(), null, null, List.of());
    }

    private static ChatMessageDto user(String id, String content) {
        return msg(id, Role.user, content, null, null, null, List.of(), null, null, List.of());
    }

    /** tool_result 消息（role=tool，assistantMessageId 指回父 assistant · CC 交错分块）。 */
    private static ChatMessageDto toolResult(String id, String content, String assistantMessageId) {
        return msg(id, Role.tool, content, null, null, assistantMessageId, List.of(), null, null, List.of());
    }

    // ════════════════════════════════════════════════════════════════════════
    // rough 家族（tokenEstimation.ts:203 round(len/4) · REQ-26）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("rough = round(len/4) 非 int 截断（tokenEstimation.ts:203）")
    void rough_usesRound_notIntTruncation() {
        // round(8/4) = 2
        assertThat(Tokens.roughTokenCountEstimation("ABCDEFGH")).isEqualTo(2);
        // 14 chars: 14/4 = 3.5 → round = 4（int 截断 3 会 FAIL，锁定 round 语义）
        assertThat(Tokens.roughTokenCountEstimation("12345678901234")).isEqualTo(4);
        // 15 chars: 15/4 = 3.75 → round = 4（int 截断 3 会 FAIL）
        assertThat(Tokens.roughTokenCountEstimation("123456789012345")).isEqualTo(4);
        assertThat(Tokens.roughTokenCountEstimation(null)).isZero();
    }

    @Test
    @DisplayName("roughTokenCountEstimationForMessages = Σ per-message（tokenEstimation.ts:327-339）")
    void roughForMessages_sumsPerMessage() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "ABCDEFGH"),          // 2
            assistant("a1", "12345678901234", null, null),  // 4（content 无 usage）
            user("u2", "ABCD")               // 1
        );
        assertThat(Tokens.roughTokenCountEstimationForMessages(msgs)).isEqualTo(7);
    }

    // ════════════════════════════════════════════════════════════════════════
    // getTokenUsage / getTokenCountFromUsage（tokens.ts:7/46）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getTokenUsage：assistant 且携带 usage → Usage；否则 null（tokens.ts:7-21）")
    void getTokenUsage_onlyForAssistantWithUsage() {
        ChatMessageDto asst = assistant("a1", "hello", 100, 20);
        Tokens.Usage usage = Tokens.getTokenUsage(asst);
        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).isEqualTo(100);
        assertThat(usage.outputTokens()).isEqualTo(20);

        // 非 assistant（user / tool）→ null
        assertThat(Tokens.getTokenUsage(user("u1", "hi"))).isNull();
        assertThat(Tokens.getTokenUsage(toolResult("t1", "res", "a1"))).isNull();
        // assistant 但无 usage（inputTokens/outputTokens null）→ null
        assertThat(Tokens.getTokenUsage(assistant("a2", "no usage", null, null))).isNull();
        assertThat(Tokens.getTokenUsage(null)).isNull();
    }

    @Test
    @DisplayName("getTokenCountFromUsage = input + cache_creation + cache_read + output（tokens.ts:46-53）")
    void getTokenCountFromUsage_sumsAllFour() {
        Tokens.Usage usage = new Tokens.Usage(100, 20, 5, 3);
        assertThat(Tokens.getTokenCountFromUsage(usage)).isEqualTo(128);
        assertThat(Tokens.getTokenCountFromUsage(null)).isZero();
    }

    @Test
    @DisplayName("getTokenCountFromUsage(anthropic=false) = input + output（deepseek input 已含 cache hit，A5-2）")
    void getTokenCountFromUsage_nonAnthropic_inputPlusOutput() {
        // WHY (A5-2): deepseek（openai 协议）input_tokens 已含 cache hit（input == H+M），
        //   4 项和把 cacheRead/cacheCreate 重复计入 → 展示/预算/决策阈值口径必须 input+output。
        Tokens.Usage usage = new Tokens.Usage(100, 20, 5, 3);
        assertThat(Tokens.getTokenCountFromUsage(usage, false))
            .as("100+20=120（忽略 cacheRead=5/cacheCreate=3）").isEqualTo(120);
        // anthropic=true 保持 4 项和（CC 原生）与 1 参一致
        assertThat(Tokens.getTokenCountFromUsage(usage, true)).isEqualTo(128);
        assertThat(Tokens.getTokenCountFromUsage(null, false)).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // tokenCountFromLastAPIResponse（tokens.ts:55-66）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tokenCountFromLastAPIResponse 回扫最近 usage → 全量 input+cache+output；无 → 0")
    void tokenCountFromLastAPIResponse_walksBackToLastUsage() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "q"),
            assistant("a1", "r1", 100, 20),   // 120
            user("u2", "q2"),
            assistant("a2", "r2", 50, 10)     // 60 ← 最近 usage
        );
        assertThat(Tokens.tokenCountFromLastAPIResponse(msgs)).isEqualTo(60);
        // 无 usage → 0
        assertThat(Tokens.tokenCountFromLastAPIResponse(List.of(user("u1", "q"), user("u2", "r")))).isZero();
        assertThat(Tokens.tokenCountFromLastAPIResponse(List.of())).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // finalContextTokensFromLastResponse（tokens.ts:79-112 · 排除 cache · REQ-23）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("finalContextTokensFromLastResponse = 最近 usage 的 input+output（排除 cache；无 iterations → 顶层）；无 → 0")
    void finalContextTokensFromLastResponse_excludesCache() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "q"),
            assistant("a1", "r1", 100, 20),   // input+output=120（cache 字段 0）
            user("u2", "q2"),
            assistant("a2", "r2", 50, 10)     // 60 ← 最近 usage
        );
        assertThat(Tokens.finalContextTokensFromLastResponse(msgs)).isEqualTo(60);
        assertThat(Tokens.finalContextTokensFromLastResponse(List.of(user("u1", "q")))).isZero();
        assertThat(Tokens.finalContextTokensFromLastResponse(List.of())).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // tokenCountWithEstimation（tokens.ts:226-261 · usage-walk + sibling 回溯）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tokenCountWithEstimation = 最近 usage + 其后消息 rough（非全量 len/4 · 口径对照）")
    void tokenCountWithEstimation_usagePlusRoughSlice() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "very long user question that would inflate a naive len/4 over all messages"),
            assistant("a1", "reply", 100, 20),   // usage total = 120
            user("u2", "ABCDEFGH")               // rough = 2
        );
        // CC 语义：120 + rough(u2) = 122 —— 而非（全量 len/4）估算整个列表
        assertThat(Tokens.tokenCountWithEstimation(msgs)).isEqualTo(122);
        assertThat(Tokens.tokenCountWithEstimation(msgs))
            .as("口径对照：不能等于全量 len/4（那会包含 u1 的超长文本）")
            .isNotEqualTo(Tokens.roughTokenCountEstimationForMessages(msgs));
    }

    @Test
    @DisplayName("tokenCountWithEstimation sibling 回溯：同 id 分块间交错 tool_result 纳入切片（tokens.ts:232-252）")
    void tokenCountWithEstimation_walksBackToFirstSibling() {
        // CC 并行工具调用流：[assistant(id=A,usage), tool_result, assistant(id=A,usage), tool_result, user]
        // 停在最后 assistant 只估算其后 1 个 tool_result；回溯到首个同 id sibling 把交错 tool_result 全部纳入。
        List<ChatMessageDto> msgs = List.of(
            assistant("A", "", 100, 20),           // 首个 sibling · usage total=120
            toolResult("t1", "ABCDEFGH", "A"),     // rough 2
            assistant("A", "ABCDEFGH", 100, 20),   // 最后 sibling · usage total=120（同 id）
            toolResult("t2", "ABCDEFGH", "A"),     // rough 2
            user("u1", "ABCDEFGH")                 // rough 2
        );
        // 回溯到 A 首个 sibling：120 + rough(t1 + A + t2 + u1) = 120 + (2+2+2+2) = 128
        assertThat(Tokens.tokenCountWithEstimation(msgs)).isEqualTo(128);
        // 若无 sibling 回溯（停在最后 A）：120 + rough(t2+u1) = 124 —— 区分性断言
        assertThat(Tokens.tokenCountWithEstimation(msgs)).isNotEqualTo(124);
    }

    @Test
    @DisplayName("tokenCountWithEstimation 无 usage → 全量 rough（tokens.ts:260）")
    void tokenCountWithEstimation_noUsage_fullRough() {
        List<ChatMessageDto> msgs = List.of(
            user("u1", "ABCDEFGH"), user("u2", "ABCDEFGH"), user("u3", "ABCDEFGH")
        );
        assertThat(Tokens.tokenCountWithEstimation(msgs))
            .isEqualTo(Tokens.roughTokenCountEstimationForMessages(msgs))
            .isEqualTo(6);
        assertThat(Tokens.tokenCountWithEstimation(List.of())).isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 三口径分离（INV-3 · 01 §11 / OD-05/OD-12）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("三口径分离：tokenCountWithEstimation / tokenCountFromLastAPIResponse / truePostCompactTokenCount 独立实现互不混用（INV-3）")
    void threeTokenMeasures_areIndependentlyComputed() {
        // 压缩前消息（含 usage）
        List<ChatMessageDto> preCompact = List.of(
            user("u1", "q"),
            assistant("a1", "reply", 100, 20),     // usage total=120
            user("u2", "ABCDEFGH")                 // rough 2
        );
        // 压缩后结果消息（boundary + summary + attachments · 无 usage）
        List<ChatMessageDto> postCompact = List.of(
            user("b1", "Conversation compacted"),
            user("s1", "12345678901234")           // rough 4
        );

        // 口径 1：usage-walk（阈值/blocking 输入）
        int withEstimation = Tokens.tokenCountWithEstimation(preCompact);
        // 口径 2：compaction API 用量（postCompactTokenCount）
        int fromLastApi = Tokens.tokenCountFromLastAPIResponse(preCompact);
        // 口径 3：结果上下文消息载荷粗估（truePostCompactTokenCount）
        int truePost = Tokens.roughTokenCountEstimationForMessages(postCompact);

        // 各自独立公式
        assertThat(withEstimation).isEqualTo(122);   // 120 + rough(u2)=2
        assertThat(fromLastApi).isEqualTo(120);      // 最近 usage total
        assertThat(truePost).isEqualTo(10);          // rough(boundary=6) + rough(summary=4)

        // 互不混用：三值各不相同（且三者实现路径独立）
        assertThat(withEstimation)
            .as("usage-walk 口径必须包含切片 rough，不能等于纯 API 用量（口径 2）")
            .isNotEqualTo(fromLastApi);
        assertThat(fromLastApi)
            .as("API 用量口径必须基于 usage，不能等于结果消息粗估（口径 3）")
            .isNotEqualTo(truePost);
        assertThat(withEstimation)
            .as("usage-walk 作用于压缩前消息，不能误用压缩后结果粗估（口径 3）")
            .isNotEqualTo(truePost);
        // truePost 必须为纯 rough（无 usage 参与）
        assertThat(Tokens.getTokenUsage(postCompact.get(0)))
            .as("结果消息不应携带 usage（口径 3 纯 rough 前提）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // rough 家族 block 口径（tokenEstimation.ts:391-435 · per-block + IMAGE=2000）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("roughTokenCountEstimationForMessage 的 contentBlocks 数组逐块（image=2000 · tokenEstimation.ts:400-412）")
    void roughForMessage_contentBlocks_arrayPerBlock() {
        com.fasterxml.jackson.databind.node.ObjectNode image =
            JSON.createObjectNode().put("type", "image");
        com.fasterxml.jackson.databind.node.ObjectNode text =
            JSON.createObjectNode().put("type", "text").put("text", "ABCDEFGH");
        ChatMessageDto msg = msg("m1", Role.user, null, null, null, null,
            List.of(image, text), null, null, List.of());
        // image → 2000，text → 2
        assertThat(Tokens.roughTokenCountEstimationForMessage(msg)).isEqualTo(2002);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();
}
