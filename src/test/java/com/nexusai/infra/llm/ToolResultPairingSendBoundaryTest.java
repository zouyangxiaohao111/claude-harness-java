package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1] 「真机级」发送边界验证：悬挂 tool_use 经两个 provider 的真实序列化后，请求体里
 * <b>不再存在</b>缺配对的 assistant.tool_calls（OpenAI 通道）/ tool_use（Anthropic 通道）。
 *
 * <p><b>WHY（测试验证意图）</b>：实证 400 报文为
 * {@code An assistant message with 'tool_calls' must be followed by tool messages responding to each
 * 'tool_call_id'}，产生点是 provider 序列化后的 HTTP 请求体。故断言必须落在 wire（SDK 对象 →
 * ObjectMappers JSON）而不只落在 DTO 层，才能证明「fork 尾部 assistant(tool_calls) 无结果」这一
 * 真实断头被修复；同时覆盖 <b>两条通道</b>（CC 的 ensureToolResultPairing 在共享 API 层，
 * 不分 provider · claude.ts:1324）。
 *
 * <p><b>变异自证</b>：把任一 provider 的 {@code ToolResultPairingRepair.ensureToolResultPairing}
 * 调用回退为直传 history → 本类对应通道的 {@code everyToolCallIdHasResult} 断言红
 * （OpenAI 侧 assistant.tool_calls 无 role=tool 应答；Anthropic 侧 tool_use 无 tool_result）。
 */
class ToolResultPairingSendBoundaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────── fixture ───────────

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto assistant(String text, ToolCallDto... calls) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant", text,
            null, List.of(calls), null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, "tool", content,
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, null,
            null, null, null, null, false, false);
    }

    private static ToolCallDto call(String id) {
        return new ToolCallDto(id, "Bash", "{\"command\":\"pwd\"}", null, null);
    }

    /** 生产 SDK wire（OpenAI 通道）：buildSdkMessages → ObjectMappers JSON（同 R32B9/PdfDelivery 先例）。 */
    private static JsonNode openAiWire(List<ChatMessageDto> history) throws Exception {
        List<com.openai.models.ChatCompletionMessageParam> msgs =
            OpenAiSdkProvider.buildSdkMessages(history);
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(msgs));
    }

    /** 生产 SDK wire（Anthropic 通道）：buildMessageParams → _body().messages JSON（同 R32B9 先例）。 */
    private static JsonNode anthropicWire(List<ChatMessageDto> history) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params =
            AnthropicSdkProvider.buildMessageParams(
                "claude-opus-4", null, history, null, null, null, null, null, null);
        JsonNode body = JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
        return body.path("messages");
    }

    /** OpenAI wire 的 tool_use ↔ tool 结果 id 集合（assistant.tool_calls[].id vs tool.tool_call_id）。 */
    private static Set<String> openAiToolCallIds(JsonNode messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            if ("assistant".equals(m.path("role").asText())) {
                for (JsonNode tc : m.path("tool_calls")) {
                    if (!tc.path("id").asText().isEmpty()) {
                        ids.add(tc.path("id").asText());
                    }
                }
            }
        }
        return ids;
    }

    private static Set<String> openAiToolResultIds(JsonNode messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            if ("tool".equals(m.path("role").asText())) {
                ids.add(m.path("tool_call_id").asText());
            }
        }
        return ids;
    }

    /** Anthropic wire 的 tool_use ↔ tool_result id 集合（content 块数组）。 */
    private static Set<String> anthropicToolUseIds(JsonNode messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) {
                    ids.add(b.path("id").asText());
                }
            }
        }
        return ids;
    }

    private static Set<String> anthropicToolResultIds(JsonNode messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())) {
                    ids.add(b.path("tool_use_id").asText());
                }
            }
        }
        return ids;
    }

    // ─────────── OpenAI 通道 ───────────

    @Test
    @DisplayName("OpenAI wire：尾部 assistant(tool_calls) 无结果 → 请求体出现配对的合成 tool 消息（含占位文本）")
    void openAiWire_danglingTailAssistant_isRepaired() throws Exception {
        List<ChatMessageDto> history = List.of(user("提炼记忆"), assistant("", call("call_1")));

        JsonNode messages = openAiWire(history);
        Set<String> callIds = openAiToolCallIds(messages);
        Set<String> resultIds = openAiToolResultIds(messages);

        assertThat(callIds).containsExactly("call_1");
        // 核心断言：每个 assistant.tool_calls[].id 都有 role=tool 应答 —— 请求体不再缺配对
        assertThat(resultIds).containsAll(callIds);
        assertThat(messages.get(messages.size() - 1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.toString())
            .contains(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
    }

    @Test
    @DisplayName("OpenAI wire：多轮历史 + 尾部悬挂 → 历史 tool 消息逐条保留，仅尾部补齐")
    void openAiWire_danglingTailWithHistory_onlyTailRepaired() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("第一步"),
            assistant("", call("call_1")),
            toolResult("call_1", "pwd-result"),
            assistant("分析", call("call_2")),
            toolResult("call_2", "ls-result"),
            assistant("", call("call_3")));

        JsonNode messages = openAiWire(history);

        assertThat(openAiToolResultIds(messages)).containsExactly("call_1", "call_2", "call_3");
        assertThat(openAiToolCallIds(messages)).containsExactly("call_1", "call_2", "call_3");
        assertThat(messages.toString()).contains("pwd-result").contains("ls-result");
    }

    @Test
    @DisplayName("OpenAI wire：已完整配对 → 请求体零变化（无合成占位、消息数不变）")
    void openAiWire_fullyPaired_unchanged() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("hi"), assistant("", call("call_1")), toolResult("call_1", "ok"));

        JsonNode messages = openAiWire(history);

        assertThat(messages).hasSize(3);
        assertThat(messages.toString())
            .doesNotContain(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
        assertThat(openAiToolCallIds(messages)).isEqualTo(openAiToolResultIds(messages));
    }

    // ─────────── Anthropic 通道 ───────────

    @Test
    @DisplayName("Anthropic wire：尾部 assistant(tool_use) 无结果 → 末尾 user 消息补 tool_result 且 is_error=true")
    void anthropicWire_danglingTailAssistant_isRepairedWithIsError() throws Exception {
        List<ChatMessageDto> history = List.of(user("提炼记忆"), assistant("", call("toolu_1")));

        JsonNode messages = anthropicWire(history);
        Set<String> useIds = anthropicToolUseIds(messages);
        Set<String> resultIds = anthropicToolResultIds(messages);

        assertThat(useIds).containsExactly("toolu_1");
        assertThat(resultIds).containsAll(useIds);

        // 合成块语义对位 CC syntheticBlocks（messages.ts:5796-5801）：is_error: true + 占位文本
        JsonNode synthetic = null;
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())) {
                    synthetic = b;
                }
            }
        }
        assertThat(synthetic).isNotNull();
        assertThat(synthetic.path("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(synthetic.path("is_error").asBoolean()).isTrue();
        assertThat(synthetic.path("content").asText())
            .isEqualTo(ToolResultPairingRepair.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
    }

    @Test
    @DisplayName("Anthropic wire：已完整配对 → 真实 tool_result 不被打上 is_error（仅错误结果携带）")
    void anthropicWire_fullyPaired_realResultHasNoIsError() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("hi"), assistant("", call("toolu_1")), toolResult("toolu_1", "ok"));

        JsonNode messages = anthropicWire(history);

        assertThat(messages).hasSize(3);
        assertThat(anthropicToolResultIds(messages)).containsExactly("toolu_1");
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())) {
                    assertThat(b.has("is_error")).isFalse();
                }
            }
        }
    }
}
