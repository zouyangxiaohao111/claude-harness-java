package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;

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
        JsonNode body = buildAnthropicWire(List.of(toolMessage(Map.of("answer", "yes"))));
        JsonNode content = body.get("messages").get(0).get("content");
        assertEquals(1, content.size());
        assertEquals("tool_result", content.get(0).get("type").asText());
    }

    @Test
    void anthropicOmitsStructuredOutputWhenEmpty() throws Exception {
        JsonNode body = buildAnthropicWire(List.of(toolMessage(Map.of())));
        JsonNode content = body.get("messages").get(0).get("content");
        assertEquals(1, content.size());
    }

    @Test
    void openAiOmitsStructuredOutputTextPart() throws Exception {
        // IT-6: 同上; OpenAI tool content 回落标量 text, 不再出现 JSON text part.
        JsonNode content = invokeOpenAiSdk(List.of(toolMessage(Map.of("answer", "yes"))))
            .get(0).get("content");
        assertTrue(content.isTextual());
        assertEquals("plain result", content.asText());
    }

    @Test
    void openAiKeepsScalarContentWithoutStructuredOutput() throws Exception {
        JsonNode content = invokeOpenAiSdk(List.of(toolMessage(Map.of())))
            .get(0).get("content");
        assertTrue(content.isTextual());
        assertEquals("plain result", content.asText());
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
