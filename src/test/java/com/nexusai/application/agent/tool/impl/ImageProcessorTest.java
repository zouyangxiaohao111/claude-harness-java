package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-C5] image 独立块送达端到端验证（TR-D1 W1/R1 HIGH 修复）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：Java 原 image 分支 tool_result 载荷 =
 * {@code JsonNode.toString()}（含 image_base64 全量 base64 JSON 文本），模型可能看到
 * base64 文本而非图像（域 D H-4）。CC 真源 FileReadTool.ts:652-669 image case 把图像放进
 * <b>独立 image block</b>（tool_result.content 数组内 {@code {type:'image', source:{type:'base64',
 * data, media_type}}}）。本测试验证完整链路：
 * <ol>
 *   <li>ReadFileTool 读 .png → image data（read_file_output_type=image）</li>
 *   <li>{@code toolResultMessage} 经 per-tool mapper → tool_result 块 content = 独立 image block</li>
 *   <li>AnthropicSdkProvider wire：tool_result.content 为数组且嵌套 image 块
 *       （对齐 CC 双形态：mapper 块数组嵌套 tool_result.content）</li>
 * </ol>
 * 若未来有人把 image 载荷改回 JsonNode.toString() / 把 image 块降级为兄弟顶层块，本测试必红。
 */
@DisplayName("[IMP-C5] image 独立块送达（ReadFileTool → toolResultMessage → Provider wire）")
class ImageProcessorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] TINY_PNG = new byte[]{
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    private static ToolUseBlock call(String path) {
        com.fasterxml.jackson.databind.node.ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-img-1", "Read", input);
    }

    @Test
    @DisplayName("image 分支 tool_result 块 content = 独立 image block（非 base64 JSON 文本）")
    void mapperProducesIndependentImageBlock(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("tiny.png"), TINY_PNG);
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));

        ToolResult result = (ToolResult) tool.execute(call("tiny.png"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("image");

        var block = tool.mapToToolResultBlockParam(result, "call-img-1", false);
        assertThat(block.content()).isInstanceOf(List.class);
        List<?> blocks = (List<?>) block.content();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(com.nexusai.application.agent.tool.ContentBlockParam.ImageBlockParam.class);
        var img = (com.nexusai.application.agent.tool.ContentBlockParam.ImageBlockParam) blocks.get(0);
        assertThat(img.source().mediaType()).isEqualTo(data.get("image_media_type").asText());
        assertThat(img.source().data()).isEqualTo(data.get("image_base64").asText());
    }

    @Test
    @DisplayName("Provider wire：tool_result.content 嵌套 image 块（CC FileReadTool.ts:654-669 双形态）")
    void providerWireNestsImageBlockInsideToolResult(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("tiny.png"), TINY_PNG);
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));

        ToolResult result = (ToolResult) tool.execute(call("tiny.png"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        // toolResultMessage → per-tool mapper 构造 tool_result 块（块数组 → contentBlocks + payload=""）
        ChatMessageDto msg = LlmAgentLoop.toolResultMessage(
            result, "call-img-1", false, tool, null, null, List.of(), List.of(), Map.of());

        JsonNode body = buildParamsWire(List.of(msg));
        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.size()).as("image 独立块送达：单条消息应只含 1 个 tool_result 块（image 嵌套其内）").isEqualTo(1);
        JsonNode toolResult = content.get(0);
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("call-img-1");
        // CC 真源：image 块嵌套进 tool_result.content 数组（FileReadTool.ts:654-669）
        JsonNode toolContent = toolResult.get("content");
        assertThat(toolContent.isArray()).as("tool_result.content 必须是块数组（CC image case）").isTrue();
        assertThat(toolContent.size()).isEqualTo(1);
        JsonNode imageBlock = toolContent.get(0);
        assertThat(imageBlock.get("type").asText()).isEqualTo("image");
        JsonNode source = imageBlock.get("source");
        assertThat(source.get("type").asText()).isEqualTo("base64");
        assertThat(source.get("media_type").asText()).isEqualTo("image/png");
        assertThat(source.get("data").asText()).isNotEmpty();
    }

    private static JsonNode buildParamsWire(List<ChatMessageDto> history) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-opus-4", null, history, null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }
}
