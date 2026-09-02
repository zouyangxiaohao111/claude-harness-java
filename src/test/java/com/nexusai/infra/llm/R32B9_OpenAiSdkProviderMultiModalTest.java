package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.openai.models.ChatCompletionMessageParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9 · Phase 5 · OpenAiSdkProvider role=tool / user / assistant 消息序列化.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 要求 LLM provider 的 role=tool 序列化分支能处理
 * {@code contentBlocks} 含 image 块;对齐 OpenAI 协议
 * ({@code role=tool, content=[text, image_url], tool_call_id}).
 *
 * <p>[OpenAI-SDK 迁移] 旧实现 buildRequestBody 已删除 → 断言目标迁移到生产实际路径
 * {@link OpenAiSdkProvider#buildSdkMessages} 的 SDK wire（与 Anthropic buildMessageParams 同构）。
 * <b>SDK 0.25.0 API 约束（grep javap 实证）</b>：
 * <ul>
 *   <li>tool 消息 content 数组仅支持 {@code ChatCompletionContentPartText} → image/document 块被跳过
 *       （受控残留 R-T-1，warn 日志）；acceptFeedback / text 块正常序列化；[IT-6] structuredOutput
 *       不再序列化（停发模型，走 structured_output attachment 通道）</li>
 *   <li>user 消息无 document part → document 块跳过（受控残留 R-U-1）；image → image_url / text → text</li>
 * </ul>
 */
class R32B9_OpenAiSdkProviderMultiModalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("role=tool 无 contentBlocks → 单 string content (向后兼容)")
    void roleToolNoContentBlocks() throws Exception {
        ChatMessageDto msg = toolMsg("Hello", null);
        JsonNode body = sdkWire(List.of(msg));

        JsonNode toolMsg = body.get(0);
        assertThat(toolMsg.get("role").asText()).isEqualTo("tool");
        assertThat(toolMsg.get("tool_call_id").asText()).isEqualTo("call-id-abc");
        assertThat(toolMsg.get("content").asText()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("role=tool + acceptFeedback → content 数组: text(content) + text(feedback)（R32-b9-fix Fix E 结构化注入）")
    void roleToolWithAcceptFeedback() throws Exception {
        ChatMessageDto msg = new ChatMessageDto(
            "call-1", "sess-1", Role.tool, "tool", "Image result",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "call-id-abc", null,
            "请重新执行", List.of(), List.of());
        JsonNode body = sdkWire(List.of(msg));

        JsonNode toolMsg = body.get(0);
        JsonNode content = toolMsg.get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("Image result");
        assertThat(content.get(1).get("type").asText()).isEqualTo("text");
        assertThat(content.get(1).get("text").asText()).isEqualTo("请重新执行");
    }

    @Test
    @DisplayName("role=tool + structuredOutput → 无 JSON text part（IT-6: 停发模型，structured_output 走 attachment 通道）")
    void roleToolWithStructuredOutput() throws Exception {
        ChatMessageDto msg = new ChatMessageDto(
            "call-1", "sess-1", Role.tool, "tool", "plain result",
            null, null, null, null, null, null,
            OffsetDateTime.now(), "call-id-abc", null,
            null, List.of(), List.of(), java.util.Map.of("answer", "yes"));
        JsonNode body = sdkWire(List.of(msg));

        // IT-6: CC normalizeAttachmentForAPI structured_output→[]（messages.ts:4258-4261）
        // → tool content 回落标量 text，不再出现 JSON text part。
        JsonNode content = body.get(0).get("content");
        assertThat(content.isTextual()).isTrue();
        assertThat(content.asText()).isEqualTo("plain result");
    }

    @Test
    @DisplayName("role=tool + image contentBlocks → image 块跳过（SDK 0.25.0 tool content 仅 text part · R-T-1），text 块正常渲染")
    void roleToolWithImageBlock_imageSkipped() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree(
            "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://example.com/x.png\"}}"));
        blocks.add(JSON.readTree("{\"type\":\"text\",\"text\":\"附加说明\"}"));

        ChatMessageDto msg = toolMsg("Image result", blocks);
        JsonNode body = sdkWire(List.of(msg));

        JsonNode content = body.get(0).get("content");
        assertThat(content.isArray()).isTrue();
        // text(content) + text(附加说明)；image_url 不得出现（R-T-1 受控残留）
        assertThat(content.size()).isEqualTo(2);
        for (JsonNode part : content) {
            assertThat(part.get("type").asText())
                .as("[OpenAI-SDK] R-T-1 · SDK 0.25.0 tool content 无法表达 image_url → 仅 text part")
                .isEqualTo("text");
        }
        assertThat(content.get(1).get("text").asText()).isEqualTo("附加说明");
    }

    @Test
    @DisplayName("role=tool + tool_reference contentBlocks → tool_reference 块跳过（OpenAI 协议无 tool_reference 原生块 · N/A 登记），text 块正常渲染、payload 无 tool_reference 字段")
    void roleToolWithToolReferenceBlock_skipped() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"tool_reference\",\"tool_name\":\"some_tool\"}"));
        blocks.add(JSON.readTree("{\"type\":\"text\",\"text\":\"附加说明\"}"));

        ChatMessageDto msg = toolMsg("Search result", blocks);
        JsonNode body = sdkWire(List.of(msg));

        JsonNode content = body.get(0).get("content");
        assertThat(content.isArray()).isTrue();
        // text(content) + text(附加说明)；tool_reference 不得序列化（OpenAI 无原生块 → 回退 content 文本）
        assertThat(content.size()).isEqualTo(2);
        for (JsonNode part : content) {
            assertThat(part.get("type").asText())
                .as("[X-1 / WF-8] OpenAI 协议无 tool_reference 原生块 → 仅 text part")
                .isEqualTo("text");
            assertThat(part.has("tool_reference")).isFalse();
            assertThat(part.has("tool_name")).isFalse();
        }
        assertThat(content.get(1).get("text").asText()).isEqualTo("附加说明");
        // 整条 tool 消息不得出现 tool_reference / tool_name 字段（N/A：序列化行为不变）
        assertThat(body.get(0).toString()).doesNotContain("tool_reference");
        assertThat(body.get(0).toString()).doesNotContain("tool_name");
    }

    @Test
    @DisplayName("assistant + toolCalls → tool_calls 数组回放（关闭 R1 多轮工具调用 400）")
    void assistantReplaysToolCalls() throws Exception {
        ChatMessageDto msg = new ChatMessageDto(
            "call-1", "sess-1", Role.assistant, "assistant", "invoking",
            null, List.of(new ToolCallDto("call_tool1", "read_file", "{\"path\":\"/a.txt\"}", null, false)),
            null, null, null, null, null, null, null, null,
            List.of(), List.of());
        JsonNode body = sdkWire(List.of(msg));

        JsonNode assistant = body.get(0);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        JsonNode tcs = assistant.get("tool_calls");
        assertThat(tcs).as("assistant tool_calls 必须回放（OpenAI 要求 tool 消息的 tool_call_id 存在前置 assistant.tool_calls）").isNotNull();
        assertThat(tcs.isArray()).isTrue();
        assertThat(tcs.get(0).get("id").asText()).isEqualTo("call_tool1");
        assertThat(tcs.get(0).get("type").asText()).isEqualTo("function");
        assertThat(tcs.get(0).get("function").get("name").asText()).isEqualTo("read_file");
        assertThat(tcs.get(0).get("function").get("arguments").asText()).isEqualTo("{\"path\":\"/a.txt\"}");
    }

    @Test
    @DisplayName("role=user + image contentBlocks → content 数组含 image_url（P-AL-01 页图送达 SDK 能力）")
    void roleUserWithImageRendersImageUrl() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree(
            "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://example.com/x.png\"}}"));
        ChatMessageDto msg = new ChatMessageDto(
            null, null, Role.user, "user", null, null, null, null, null, null,
            null, OffsetDateTime.now(), null, null, null, blocks, List.of());
        JsonNode body = sdkWire(List.of(msg));

        JsonNode content = body.get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(0).get("image_url").get("url").asText())
            .isEqualTo("http://example.com/x.png");
    }

    @Test
    @DisplayName("role=user + document contentBlocks → document 块跳过（SDK 0.25.0 无 document part · R-U-1），回落 string content")
    void roleUserWithDocumentBlock_dropped() throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree(
            "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"QUJDRA==\"}}"));
        ChatMessageDto msg = new ChatMessageDto(
            null, null, Role.user, "user", null, null, null, null, null, null,
            null, OffsetDateTime.now(), null, null, null, blocks, List.of());
        JsonNode body = sdkWire(List.of(msg));

        JsonNode userMsg = body.get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");
        assertThat(userMsg.get("content").asText())
            .as("[OpenAI-SDK] R-U-1 · SDK 0.25.0 无 document part → document 块跳过（受控残留，PDF 送达走 Anthropic 路径）")
            .isEqualTo("");
    }

    // ─────────── helpers ───────────

    private static ChatMessageDto toolMsg(String content, List<JsonNode> contentBlocks) {
        return new ChatMessageDto(
            "call-1", "sess-1", Role.tool, "tool", content,
            null, null, null, null, null, null,
            OffsetDateTime.now(), "call-id-abc", null,
            null, contentBlocks == null ? List.of() : contentBlocks, List.of());
    }

    /** [OpenAI-SDK 迁移] 生产 SDK wire：buildSdkMessages → ObjectMappers 序列化 JsonNode。 */
    private static JsonNode sdkWire(List<ChatMessageDto> history) throws Exception {
        List<ChatCompletionMessageParam> msgs = OpenAiSdkProvider.buildSdkMessages(history);
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(msgs));
    }
}
