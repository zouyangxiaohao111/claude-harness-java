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
 * IMP-13 · estimateMessageTokens block 口径对照测试（REQ-14）+ COMPACTABLE_TOOLS 成员集（D-21）
 * + IMAGE_MAX_TOKEN_SIZE=2000（D-20）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code microCompact.ts:164 estimateMessageTokens}
 * 是 block 级 token 估算（text→rough / tool_result→string|数组 / image≈2000 / thinking→文本 /
 * tool_use→name+input，最后 ×4/3 ceil 保守 padding）。旧 Java 实现是 {@code content.length()/4+4}
 * char 估算且 IMAGE=1024、COMPACTABLE_TOOLS 含 'Shell' 死条目（D-20/D-21），口径偏移会导致
 * microcompact/压缩预算按错误 token 数决策。本测试锁定 CC 口径不变量。
 */
@DisplayName("[IMP-13] TokenEstimator.estimateMessageTokens block 口径 + IMAGE=2000 + COMPACTABLE_TOOLS")
class TokenEstimatorEstimateMessageTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenEstimator estimator = new TokenEstimator();

    private ChatMessageDto msg(Role role, String content, List<ToolCallDto> toolCalls,
                               List<String> imagePasteIds, String reasoning, List<?> contentBlocks) {
        return new ChatMessageDto("m1", "s1", role, role == Role.assistant ? "assistant" : "user",
            content, reasoning, toolCalls, FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, contentBlocks, imagePasteIds);
    }

    private static int rough(String text) {
        return Math.round(text.length() / 4.0f);
    }

    private static int padded(int raw) {
        return (int) Math.ceil(raw * (4.0 / 3.0));
    }

    // ── IMAGE_MAX_TOKEN_SIZE = 2000（D-20，CC microCompact.ts:38） ──

    @Test
    @DisplayName("IMAGE_MAX_TOKEN_SIZE 必须 = 2000（CC microCompact.ts:38，旧值 1024 偏移）")
    void imageMaxTokenSize_is2000() {
        assertThat(TokenEstimator.IMAGE_MAX_TOKEN_SIZE).isEqualTo(2000);
    }

    // ── COMPACTABLE_TOOLS 成员集（D-21，CC microCompact.ts:41-50） ──

    @Test
    @DisplayName("COMPACTABLE_TOOLS 必须为 CC 9 成员（含 PowerShell，不含 Shell 死条目）")
    void compactableTools_matchesCcNineMembers() {
        assertThat(TokenEstimator.COMPACTABLE_TOOLS).hasSize(9);
        assertThat(TokenEstimator.COMPACTABLE_TOOLS).contains(
            "Read", "Bash", "PowerShell", "Grep", "Glob", "WebSearch", "WebFetch", "Edit", "Write");
        assertThat(TokenEstimator.COMPACTABLE_TOOLS)
            .as("'Shell' 非工具注册表工具名（D-21 死条目），必须删除")
            .doesNotContain("Shell");
    }

    // ── estimateMessageTokens block 口径（REQ-14，CC microCompact.ts:164-205） ──

    @Test
    @DisplayName("text 块 → rough(content) × 4/3 ceil")
    void textBlock_roughTimesFourThirds() {
        String content = "ABCDEFGH"; // rough = round(8/4) = 2
        assertThat(estimator.estimateMessageTokens(msg(Role.user, content, null, null, null, null)))
            .isEqualTo(padded(rough(content)));
        assertThat(padded(rough(content))).isEqualTo(3);
    }

    @Test
    @DisplayName("image 块（imagePasteIds）→ IMAGE_MAX_TOKEN_SIZE(2000) × 4/3 ceil")
    void imageBlock_constant2000TimesFourThirds() {
        assertThat(estimator.estimateMessageTokens(
                msg(Role.user, null, null, List.of("img-1"), null, null)))
            .isEqualTo(padded(2000));
        assertThat(padded(2000)).isEqualTo(2667);
    }

    @Test
    @DisplayName("tool_use 块（toolCalls）→ rough(name + input JSON) × 4/3 ceil")
    void toolUseBlock_namePlusInput() {
        ToolCallDto call = new ToolCallDto("call_1", "Bash", "{\"cmd\":\"ls\"}", null, false);
        // "Bash" + "{\"cmd\":\"ls\"}" = 4 + 12 = 16 chars → rough = round(16/4) = 4
        assertThat(estimator.estimateMessageTokens(
                msg(Role.assistant, null, List.of(call), null, null, null)))
            .isEqualTo(padded(rough("Bash" + "{\"cmd\":\"ls\"}")));
        assertThat(padded(rough("Bash" + "{\"cmd\":\"ls\"}"))).isEqualTo(6);
    }

    @Test
    @DisplayName("thinking 块（reasoning）→ rough(reasoning) × 4/3 ceil")
    void thinkingBlock_reasoningRough() {
        String reasoning = "thinking block"; // 14 chars → rough = round(14/4) = 4
        assertThat(estimator.estimateMessageTokens(
                msg(Role.assistant, "ABCDEFGH", null, null, reasoning, null)))
            .as("text + thinking 两块都应计入")
            .isEqualTo(padded(rough("ABCDEFGH") + rough(reasoning)));
        // rough(8)=2 + rough(14)=4 → 6 × 4/3 = 8.0 → ceil = 8
        assertThat(padded(rough("ABCDEFGH") + rough(reasoning))).isEqualTo(8);
    }

    @Test
    @DisplayName("contentBlocks（R32-b9）image 块 → 2000 × 4/3 ceil；text 块 → rough × 4/3 ceil")
    void contentBlocks_imageAndTextBlocks() {
        ObjectNode image = JSON.createObjectNode();
        image.put("type", "image");
        image.putObject("source").put("type", "base64").put("data", "AAAA");
        ObjectNode text = JSON.createObjectNode();
        text.put("type", "text");
        text.put("text", "ABCDEFGH");

        ObjectNode mix = JSON.createObjectNode();
        mix.put("type", "text");
        mix.put("text", "ABCD");
        List<?> blocks = List.of(image, text, mix);

        int expected = 2000 + rough("ABCDEFGH") + rough("ABCD");
        assertThat(estimator.estimateMessageTokens(
                msg(Role.user, null, null, null, null, blocks)))
            .isEqualTo(padded(expected));
        // 2000 + 2 + 1 = 2003 → ×4/3 = 2670.67 → ceil = 2671
        assertThat(padded(expected)).isEqualTo(2671);
    }

    @Test
    @DisplayName("null 消息 → 0（CC 空输入不计数）")
    void nullMessage_zero() {
        assertThat(estimator.estimateMessageTokens((ChatMessageDto) null)).isZero();
        assertThat(estimator.estimateMessageTokens((java.util.List<ChatMessageDto>) null)).isZero();
    }

    @Test
    @DisplayName("list 重载 = 全消息 raw 求和后仅一次 ×4/3 ceil（CC estimateMessageTokens(messages[]) 聚合口径 · microCompact.ts:164-205）")
    void listOverload_ccAggregation() {
        ChatMessageDto textMsg = msg(Role.user, "ABCDEFGH", null, null, null, null); // raw = 2
        ChatMessageDto imageMsg = msg(Role.user, null, null, List.of("img-1"), null, null); // raw = 2000
        // CC 聚合: 逐 block raw 求和(2002) → 仅一次 Math.ceil(2002×4/3) = 2670
        // （注意：3+2667=2670 与逐条 ceil 求和在本例巧合相等，公式必须为 CC 聚合口径）
        int expected = padded(rough("ABCDEFGH") + 2000);
        assertThat(estimator.estimateMessageTokens(List.of(textMsg, imageMsg)))
            .isEqualTo(expected);
        assertThat(expected).isEqualTo(2670);

        // 区分性断言：3 条 text 消息下 CC 聚合(8) ≠ 逐条 ceil 求和(9)，锁定聚合口径而非逐条 padding
        ChatMessageDto a = msg(Role.user, "ABCDEFGH", null, null, null, null); // raw 2
        ChatMessageDto b = msg(Role.user, "ABCDEFGH", null, null, null, null); // raw 2
        ChatMessageDto c = msg(Role.user, "ABCDEFGH", null, null, null, null); // raw 2
        assertThat(estimator.estimateMessageTokens(List.of(a, b, c)))
            .as("CC 聚合: ceil(6×4/3)=8；逐条 ceil 求和 3×ceil(2×4/3)=9 — 必须取 CC 聚合口径")
            .isEqualTo(padded(6));
        assertThat(padded(6)).isEqualTo(8);
        assertThat(padded(2) * 3).isNotEqualTo(8);
    }

    // ── tool_result block（CC microCompact.ts:138-157 tool_result token 计数，D-20 旧助手已删） ──

    @Test
    @DisplayName("tool_result block: string content → rough(content) × 4/3 ceil（CC microCompact.ts:143-145 string 分支）")
    void toolResultBlock_stringContentRough() {
        ObjectNode tr = JSON.createObjectNode();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "call_1");
        tr.put("content", "ABCDEFGH"); // rough = round(8/4) = 2
        int raw = rough("ABCDEFGH");
        assertThat(estimator.estimateMessageTokens(
                msg(Role.user, null, null, null, null, List.of(tr))))
            .isEqualTo(padded(raw));
        assertThat(padded(raw)).isEqualTo(3);
    }

    @Test
    @DisplayName("tool_result block: 数组 content → Σ(text→rough / image|document→2000)，未知类型忽略（CC microCompact.ts:148-156 数组分支）")
    void toolResultBlock_arrayContentSumsTextAndMedia() {
        ObjectNode tr = JSON.createObjectNode();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "call_1");
        ObjectNode text = JSON.createObjectNode();
        text.put("type", "text");
        text.put("text", "ABCDEFGH"); // rough 2
        ObjectNode image = JSON.createObjectNode();
        image.put("type", "image");
        image.putObject("source").put("type", "base64").put("data", "AAAA");
        ObjectNode doc = JSON.createObjectNode();
        doc.put("type", "document");
        doc.putObject("source").put("type", "base64").put("data", "BBBB");
        ObjectNode unknown = JSON.createObjectNode();
        unknown.put("type", "web_search_tool_result"); // 非 text/image/document → reduce 忽略
        tr.set("content", JSON.createArrayNode().add(text).add(image).add(doc).add(unknown));

        int raw = rough("ABCDEFGH") + 2000 + 2000; // 2 + 2000 + 2000 = 4002
        assertThat(estimator.estimateMessageTokens(
                msg(Role.user, null, null, null, null, List.of(tr))))
            .isEqualTo(padded(raw));
        assertThat(padded(raw)).isEqualTo(5336);
    }

    @Test
    @DisplayName("tool_result block: content 缺失 → 0（CC microCompact.ts:139-141 空 content 分支）")
    void toolResultBlock_missingContentZero() {
        ObjectNode tr = JSON.createObjectNode();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "call_1");
        assertThat(estimator.estimateMessageTokens(
                msg(Role.user, null, null, null, null, List.of(tr))))
            .isZero();
    }

    // ── MC-05b（OPD-CM5-A-12）：Role.tool 计入 · CC user 消息内 tool_result 块扁平表示 ──

    @Test
    @DisplayName("list 重载：system 消息跳过，user/assistant/tool 计入（tool = CC user 消息内 tool_result 块扁平表示，OPD-CM5-A-12/MC-05b）")
    void listOverload_skipsSystemCountsUserAssistantTool() {
        ChatMessageDto userMsg = msg(Role.user, "ABCDEFGH", null, null, null, null);        // raw 2
        ChatMessageDto assistantMsg = msg(Role.assistant, "ABCDEFGH", null, null, null, null); // raw 2
        ChatMessageDto systemMsg = msg(Role.system, "SYSTEM_PROMPT_LONG", null, null, null, null);
        ChatMessageDto toolMsg = msg(Role.tool, "tool result", null, null, null, null);      // raw = rough("tool result") = 3
        // system 被过滤；tool 计入（CC 中 tool_result 是 user 消息内块，Java 扁平为 Role.tool）
        assertThat(estimator.estimateMessageTokens(List.of(userMsg, systemMsg, toolMsg)))
            .as("system 被过滤，user(2)+tool(3) 计入 → padded(5)")
            .isEqualTo(padded(2 + rough("tool result")));
        assertThat(estimator.estimateMessageTokens(List.of(assistantMsg, systemMsg, toolMsg)))
            .isEqualTo(padded(2 + rough("tool result")));
        assertThat(estimator.estimateMessageTokens(List.of(userMsg, assistantMsg)))
            .as("user+assistant 均计入 → padded(4)")
            .isEqualTo(padded(4));
        // 全 system → 0（CC 循环全部 continue）
        assertThat(estimator.estimateMessageTokens(List.of(systemMsg, systemMsg))).isZero();
    }

    @Test
    @DisplayName("单消息重载：system → 0，user/assistant/tool 正常计入（tool = CC user 消息内 tool_result 块扁平表示，OPD-CM5-A-12/MC-05b）")
    void singleMessageOverload_filtersRoles() {
        assertThat(estimator.estimateMessageTokens(msg(Role.system, "SYSTEM", null, null, null, null)))
            .as("system 角色 → 0（CC microCompact.ts:168-170 continue）")
            .isZero();
        assertThat(estimator.estimateMessageTokens(msg(Role.tool, "tool result", null, null, null, null)))
            .as("tool 角色（CC tool_result 块扁平表示）计入 → padded(rough(\"tool result\"))")
            .isEqualTo(padded(rough("tool result")));
        assertThat(estimator.estimateMessageTokens(msg(Role.user, "ABCDEFGH", null, null, null, null)))
            .as("user 角色正常计入")
            .isEqualTo(padded(rough("ABCDEFGH")));
    }

    @Test
    @DisplayName("list 重载：user 消息内 tool_result block 仍计入（角色过滤在数组层、tool_result 累加在 block 层，CC microCompact.ts:177-180）")
    void listOverload_toolResultBlockInsideUserStillCounted() {
        ObjectNode tr = JSON.createObjectNode();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "call_1");
        tr.put("content", "ABCDEFGH"); // rough = 2
        ChatMessageDto userWithToolResult = msg(Role.user, null, null, null, null, List.of(tr));
        assertThat(estimator.estimateMessageTokens(List.of(userWithToolResult)))
            .as("user 消息内 tool_result block 经 block 层照常累计 raw 2 → padded(2)")
            .isEqualTo(padded(rough("ABCDEFGH")));
        assertThat(padded(rough("ABCDEFGH"))).isEqualTo(3);
        // 对照：同内容作独立 Role.tool 消息 → 按 CC tool_result 块扁平表示计入（OPD-CM5-A-12/MC-05b）
        ChatMessageDto standaloneTool = msg(Role.tool, "ABCDEFGH", null, null, null, null);
        assertThat(estimator.estimateMessageTokens(List.of(standaloneTool)))
            .as("Role.tool 消息按 CC tool_result 块计入 → padded(rough(\"ABCDEFGH\"))")
            .isEqualTo(padded(rough("ABCDEFGH")));
    }

    @Test
    @DisplayName("calculateToolResultTokens 不随角色过滤变化：Role.tool 消息仍按 raw 计数（MicroCompactor tokensSaved 消费面，CC microCompact.ts:481）")
    void calculateToolResultTokens_notFilteredByRole() {
        ChatMessageDto toolMsg = msg(Role.tool, "ABCDEFGH", null, null, null, null);
        assertThat(estimator.calculateToolResultTokens(toolMsg))
            .as("独立消费面不走聚合层角色过滤，直接 raw 计数")
            .isEqualTo(rough("ABCDEFGH"));
        assertThat(rough("ABCDEFGH")).isEqualTo(2);
        // 对照：同一 Role.tool 消息经 estimateMessageTokens 单消息重载 → 计入（OPD-CM5-A-12/MC-05b）
        assertThat(estimator.estimateMessageTokens(toolMsg))
            .as("estimateMessageTokens 单消息重载对 Role.tool 计入 → padded(rough(\"ABCDEFGH\"))")
            .isEqualTo(padded(rough("ABCDEFGH")));
    }
}
