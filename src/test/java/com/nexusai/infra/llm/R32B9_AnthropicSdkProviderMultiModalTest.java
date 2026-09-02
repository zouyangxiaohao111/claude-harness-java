package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9 · Phase 4 · AnthropicSdkProvider role=tool 多模态序列化.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 要求 LLM provider 的 role=tool 序列化分支能处理
 * {@code contentBlocks} 含 image block 多模态输入;对齐 Anthropic 协议
 * （CC 双形态: ToolSearchTool.ts:462-469 中 tool_reference 块嵌套进 tool_result.content 数组;
 * toolExecution.ts:1418-1438/1029-1046 中 image/text/document 块是 tool_result 的兄弟顶层块）. 验证:
 * <ul>
 *   <li>无 contentBlocks → 与 v 0.2.7 行为一致(单 string content, 不破坏向后兼容)</li>
 *   <li>有 contentBlocks + 1 image (base64) → image 为兄弟顶层块, tool_result.content 保持
 *       字符串 payload; image block 含 source.type=base64 + media_type + data</li>
 *   <li>有 contentBlocks + 1 image (URL) → image 兄弟顶层块; image block source 直接透传原 source 字段</li>
 *   <li>混合形状 [tool_reference, image] → tool_reference 嵌套 tool_result.content 数组,
 *       image 兄弟顶层块（双形态共存于同一条消息）</li>
 * </ul>
 *
 * <p>WHY 反射访问 {@code buildRequestBody}: 这是 private 方法,实际序列化逻辑隐藏,
 * 直接验证 JSON 输出字段 (避免 mock 整个 HTTP 栈).
 *
 * @see AnthropicSdkProvider#buildMessageParams(String, String, java.util.List, com.fasterxml.jackson.databind.node.ArrayNode, Integer, TaskBudgetParam, String, com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.OutputFormat, Boolean)
 */
class R32B9_AnthropicSdkProviderMultiModalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("role=tool 无 contentBlocks → 单 string content (向后兼容)")
    void roleToolNoContentBlocks() throws Exception {
        ChatMessageDto msg = toolMsg("Hello result", null);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode messages = body.get("messages");
        assertThat(messages).isNotNull();
        JsonNode userMsg = messages.get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");

        JsonNode content = userMsg.get("content");
        // 单块 content (object),type=tool_result
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(content.get(0).get("content").asText()).isEqualTo("Hello result");
    }

    @Test
    @DisplayName("role=tool + contentBlocks (1 base64 image) → tool_result + 独立 image block")
    void roleToolWithBase64Image() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree(
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBOR...\"}}"));

        ChatMessageDto msg = toolMsg("Tool executed", blocks);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.size()).isEqualTo(2);
        // [0] tool_result：content 保持字符串 payload（CC toolExecution.ts:1418-1438，image 不嵌套）
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(content.get(0).get("content").asText()).isEqualTo("Tool executed");
        // [1] image 兄弟顶层块
        assertThat(content.get(1).get("type").asText()).isEqualTo("image");
        JsonNode source = content.get(1).get("source");
        assertThat(source.get("type").asText()).isEqualTo("base64");
        assertThat(source.get("media_type").asText()).isEqualTo("image/png");
        assertThat(source.get("data").asText()).isEqualTo("iVBOR...");
    }

    @Test
    @DisplayName("role=tool + contentBlocks (1 URL image) → image block source 透传")
    void roleToolWithUrlImage() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree(
            "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://example.com/x.png\"}}"));

        ChatMessageDto msg = toolMsg("Tool executed", blocks);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");
        // image 兄弟顶层块（CC toolExecution.ts:1432-1438），tool_result.content 保持字符串
        assertThat(content.get(1).get("type").asText()).isEqualTo("image");
        // source 透传整块
        JsonNode source = content.get(1).get("source");
        assertThat(source.get("type").asText()).isEqualTo("url");
        assertThat(source.get("url").asText()).isEqualTo("http://example.com/x.png");
    }

    @Test
    @DisplayName("role=tool + contentBlocks [tool_reference, image] → toolRef 嵌套 content + image 兄弟块（CC 双形态）")
    void roleToolMixedToolReferenceAndImage() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"tool_reference\",\"tool_name\":\"Foo\"}"));
        blocks.add(JSON.readTree(
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBOR...\"}}"));

        ChatMessageDto msg = toolMsg("search matched", blocks);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.size()).isEqualTo(2);
        // [0] tool_result：tool_reference 嵌套进 content 数组（CC ToolSearchTool.ts:462-469），
        //     content 非空时 text 前置
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");
        JsonNode toolContent = content.get(0).get("content");
        assertThat(toolContent.isArray()).isTrue();
        assertThat(toolContent.size()).isEqualTo(2);
        assertThat(toolContent.get(0).get("type").asText()).isEqualTo("text");
        assertThat(toolContent.get(0).get("text").asText()).isEqualTo("search matched");
        assertThat(toolContent.get(1).get("type").asText()).isEqualTo("tool_reference");
        assertThat(toolContent.get(1).get("tool_name").asText()).isEqualTo("Foo");
        // [1] image 兄弟顶层块（CC toolExecution.ts:1432-1438），双形态共存于同一条消息
        assertThat(content.get(1).get("type").asText()).isEqualTo("image");
        JsonNode source = content.get(1).get("source");
        assertThat(source.get("type").asText()).isEqualTo("base64");
        assertThat(source.get("data").asText()).isEqualTo("iVBOR...");
    }

    // ─────────── helpers ───────────

    private static ChatMessageDto toolMsg(String content, List<JsonNode> contentBlocks) {
        return new ChatMessageDto(
            "call-1", "sess-1", Role.tool, "tool", content,
            null, null, null, null, null, null,
            OffsetDateTime.now(), "call-id-abc", null,
            null, contentBlocks == null ? List.of() : contentBlocks, List.of());
    }

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private static JsonNode buildParamsWire(List<ChatMessageDto> history) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-opus-4", null, history, null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }
}
