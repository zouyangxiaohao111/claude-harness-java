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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * X-1 · AnthropicSdkProvider role=tool 序列化 tool_reference 块.
 *
 * <p><b>WHY (意图验证)</b>: {@link com.nexusai.application.agent.tool.impl.LspTool} 的
 * {@code shouldDefer} 可达性依赖 tool_reference 块真正到达 LLM/API wire。CC ToolSearchTool 命中时
 * 返回 {@code tool_result.content = [{ type:'tool_reference', tool_name }]}（CC ToolSearchTool.ts:462-469
 * 仅 2 字段）。此前 {@code appendSdkContentBlock} 仅 document/image/text 三分支、无 else 兜底，
 * tool_reference 块被静默丢弃。本测试验证 tool_reference 块序列化进 tool_result.content 数组，
 * 而非作为兄弟顶层块或丢失。
 *
 * <p>WHY 反射访问 buildMessageParams：这是 private 生产序列化路径，直接断言 JSON wire 字段，
 * 避免 mock 整个 HTTP 栈。
 */
class AnthropicSdkProviderToolReferenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("tool_reference 块序列化进 tool_result.content 数组（X-1 闭环）")
    void toolReferenceBlockReachesApiWire() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"tool_reference\",\"tool_name\":\"Foo\"}"));

        ChatMessageDto msg = toolMsg("", blocks);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).get("type").asText()).isEqualTo("tool_result");

        // tool_reference 嵌套在 tool_result.content 数组内（非兄弟顶层块）
        JsonNode toolContent = content.get(0).get("content");
        assertThat(toolContent.isArray()).isTrue();
        assertThat(toolContent.size()).isEqualTo(1);
        assertThat(toolContent.get(0).get("type").asText()).isEqualTo("tool_reference");
        assertThat(toolContent.get(0).get("tool_name").asText()).isEqualTo("Foo");
    }

    @Test
    @DisplayName("tool_reference + 文本主内容 并存于 tool_result.content 数组")
    void toolReferenceCoexistsWithTextContent() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"tool_reference\",\"tool_name\":\"Bar\"}"));

        ChatMessageDto msg = toolMsg("search matched", blocks);
        JsonNode body = buildParamsWire(List.of(msg));

        JsonNode toolContent = body.get("messages").get(0).get("content").get(0).get("content");
        assertThat(toolContent.isArray()).isTrue();
        assertThat(toolContent.size()).isEqualTo(2);
        assertThat(toolContent.get(0).get("type").asText()).isEqualTo("text");
        assertThat(toolContent.get(0).get("text").asText()).isEqualTo("search matched");
        assertThat(toolContent.get(1).get("type").asText()).isEqualTo("tool_reference");
        assertThat(toolContent.get(1).get("tool_name").asText()).isEqualTo("Bar");
    }

    @Test
    @DisplayName("tool_reference 缺 tool_name → fail-loud 跳过，不抛异常、不产生畸形块")
    void toolReferenceMissingToolNameFailsLoud() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"tool_reference\"}"));

        ChatMessageDto msg = toolMsg("", blocks);

        assertThatCode(() -> {
            JsonNode body = buildParamsWire(List.of(msg));
            JsonNode toolContent = body.get("messages").get(0).get("content").get(0).get("content");
            // tool_name 缺失 → 块被跳过（log.warn），tool_result.content 为空数组，不产生畸形块
            assertThat(toolContent.isArray()).isTrue();
            assertThat(toolContent.size()).isEqualTo(0);
        }).doesNotThrowAnyException();
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
