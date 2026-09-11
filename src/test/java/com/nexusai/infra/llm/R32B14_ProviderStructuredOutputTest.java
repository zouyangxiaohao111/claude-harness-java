package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class R32B14_ProviderStructuredOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatMessageDto toolMessage(Map<String, Object> structuredOutput) {
        return new ChatMessageDto("m", null, Role.tool, "tool", "plain result", null,
            null, null, null, null, null, null, "toolu-provider", null, null,
            List.of(), List.of(), structuredOutput);
    }

    @Test
    void anthropicOmitsStructuredOutputTextBlock() throws Exception {
        // IT-6: CC normalizeAttachmentForAPI 对 'structured_output' 返回 [] (messages.ts:4258-4261)
        // → 模型不再收到 JSON text block; 结构化载荷走 attachment 通道 (toolExecution.ts:1272-1279).
        JsonNode content = anthropicToolWire(toolMessage(Map.of("answer", "yes"))).get("content");
        assertEquals(1, content.size());
        assertEquals("tool_result", content.get(0).get("type").asText());
    }

    @Test
    void anthropicOmitsStructuredOutputWhenEmpty() throws Exception {
        JsonNode content = anthropicToolWire(toolMessage(Map.of())).get("content");
        assertEquals(1, content.size());
    }

    @Test
    void openAiOmitsStructuredOutputTextPart() throws Exception {
        // IT-6: 同上; OpenAI tool content 回落标量 text, 不再出现 JSON text part.
        JsonNode content = openAiToolWire(toolMessage(Map.of("answer", "yes"))).get("content");
        assertTrue(content.isTextual());
        assertEquals("plain result", content.asText());
    }

    @Test
    void openAiKeepsScalarContentWithoutStructuredOutput() throws Exception {
        JsonNode content = openAiToolWire(toolMessage(Map.of())).get("content");
        assertTrue(content.isTextual());
        assertEquals("plain result", content.asText());
    }

    /**
     * [P1] owning assistant（tool_calls 含同 id）+ tool 消息 → 该 tool 消息在其真实协议位置上的 wire 节点。
     * 发送边界新增 {@link ToolResultPairingRepair}（CC {@code ensureToolResultPairing}，
     * messages.ts:5594-5951）后，无前置 tool_use 的孤立 tool 结果会按 CC 语义在发往 API 前被剥离
     * —— 孤立 fixture 已无法到达 wire，故补上 owning assistant（协议上 tool 消息本就必须应答
     * 前置 assistant.tool_calls）。
     */
    private static ChatMessageDto owningAssistant(ChatMessageDto toolMsg) {
        return new ChatMessageDto("asst-owner", null, Role.assistant, "assistant", "",
            null, List.of(new com.nexusai.model.session.dto.ToolCallDto(
                toolMsg.toolCallId(), "test_tool", "{}", null, false)),
            null, null, null, null, OffsetDateTime.now(), null, null, null, null, null, null);
    }

    private JsonNode anthropicToolWire(ChatMessageDto toolMsg) throws Exception {
        return buildAnthropicWire(List.of(owningAssistant(toolMsg), toolMsg)).get("messages").get(1);
    }

    private JsonNode openAiToolWire(ChatMessageDto toolMsg) throws Exception {
        return invokeOpenAiSdk(List.of(owningAssistant(toolMsg), toolMsg)).get(1);
    }

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private JsonNode buildAnthropicWire(List<ChatMessageDto> history) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-test", null, history, null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }

    /** [OpenAI-SDK 迁移] 生产 SDK wire：OpenAiSdkProvider.buildSdkMessages → ObjectMappers 序列化。 */
    private JsonNode invokeOpenAiSdk(List<ChatMessageDto> history) throws Exception {
        java.util.List<com.openai.models.ChatCompletionMessageParam> msgs =
            OpenAiSdkProvider.buildSdkMessages(history);
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(msgs));
    }
}
