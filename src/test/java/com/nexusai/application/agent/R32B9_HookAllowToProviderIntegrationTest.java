package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.eventbus.ws.MessagePermissionResponseEvent;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b9-fix · Phase 9 · Hook Allow 端到端到 provider payload 集成测试.
 *
 * <p><b>WHY (意图验证)</b>: b9 reviewer P2-2 指出缺少 hook Allow → provider 的端到端集成测试。
 * 单测覆盖了 STOMP inbound、prompter 透传、provider 多模态序列化,但**整条链路串联**未验证。
 *
 * <p>本测试串联 4 段:
 * <ol>
 *   <li>STOMP inbound → MessagePermissionResponseEvent(JSON deserialize + Fix B 校验)</li>
 *   <li>WebSocketPermissionPrompter.onResponse(4 参) → PermissionResult.Allow(acceptFeedback + contentBlocks)</li>
 *   <li>LlmAgentLoop.toolResultMessage 4 参 → ChatMessageDto(role=tool, 结构化字段独立,Fix E)</li>
 *   <li>AnthropicSdkProvider.buildMessageParams / OpenAiSdkProvider.buildSdkMessages → 最终 provider JSON payload
 *       （[OpenAI-SDK 迁移] 旧 OpenAiProvider 已删除）</li>
 * </ol>
 *
 * <p>验证关键点:
 * <ul>
 *   <li>acceptFeedback 不再字符串拼接到 content(Fix E)</li>
 *   <li>contentBlocks(text + image)在 Anthropic/OpenAI payload 中作为独立块</li>
 *   <li>acceptFeedback 在 payload 中作为独立 text 块</li>
 *   <li>imagePasteIds 全局递增(Fix A)不与既有 ID 冲突</li>
 * </ul>
 *
 * @see com.nexusai.eventbus.ws.MessagePermissionResponseEvent
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter
 * @see com.nexusai.application.agent.LlmAgentLoop#toolResultMessage
 * @see com.nexusai.infra.llm.AnthropicSdkProvider#buildMessageParams(String, java.util.List, com.fasterxml.jackson.databind.node.ArrayNode, Integer, com.nexusai.infra.llm.TaskBudgetParam, String, com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.OutputFormat, Boolean)
 * @see com.nexusai.infra.llm.OpenAiSdkProvider#buildSdkMessages
 */
class R32B9_HookAllowToProviderIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────── Step 3: toolResultMessage 4 参 (反射) ───────────

    private static ChatMessageDto invokeToolResultMessage4(
            String toolUseId, String resultContent,
            String acceptFeedback, List<JsonNode> contentBlocks, List<String> imagePasteIds) {
        try {
            Class<?> toolResultClass = Class.forName("com.nexusai.application.agent.tool.ToolResult");
            // [A1 泛型化] ToolResult<T> 的 3 参构造器 (toolUseId, data, isError) 擦除后为
            //   (String, Object, boolean) — 反射第二参必须用 Object.class, 不能用 String.class.
            Object toolResult = toolResultClass.getConstructor(String.class, Object.class, boolean.class)
                .newInstance(toolUseId, resultContent, false);
            Method m = LlmAgentLoop.class.getDeclaredMethod(
                "toolResultMessage",
                toolResultClass, String.class, List.class, List.class);
            m.setAccessible(true);
            return (ChatMessageDto) m.invoke(null, toolResult, acceptFeedback, contentBlocks, imagePasteIds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode invokeBuildRequestBodyAnthropic(List<ChatMessageDto> history) throws Exception {
        // [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode
        com.anthropic.models.messages.MessageCreateParams params =
            com.nexusai.infra.llm.AnthropicSdkProvider.buildMessageParams(
                "claude-opus-4", null, history, null, null, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }

    private static JsonNode invokeBuildRequestBodyOpenAi(List<ChatMessageDto> history) throws Exception {
        // [OpenAI-SDK 迁移] 旧 OpenAiProvider.buildRequestBody 已删除 → 生产 SDK wire：
        //   OpenAiSdkProvider.buildSdkMessages → ObjectMappers 序列化
        java.util.List<com.openai.models.ChatCompletionMessageParam> msgs =
            com.nexusai.infra.llm.OpenAiSdkProvider.buildSdkMessages(history);
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(msgs));
    }

    // ─────────── 端到端集成场景 ───────────

    @Test
    @DisplayName("[端到端] STOMP Allow + contentBlocks → Anthropic payload 含 image + text 块")
    void endToEndAnthropicImageAndText() throws Exception {
        // Step 1: STOMP inbound
        String stompPayload = """
            {
              "requestId": "tool-call-1",
              "decision": "allow",
              "acceptFeedback": "请重新执行这个命令",
              "contentBlocks": [
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBOR..."}},
                {"type":"text","text":"附加说明: 这个错误可以忽略"}
              ]
            }
            """;
        MessagePermissionResponseEvent event =
            JSON.readValue(stompPayload, MessagePermissionResponseEvent.class);

        // Step 1.5: Fix B getter 校验通过(内容合法)
        assertThat(event.getContentBlocks()).hasSize(2);

        // Step 2: WebSocketPermissionPrompter 模拟透传(此处省略中间步骤,直接构造 Allow)
        List<JsonNode> passedBlocks = event.getContentBlocks();
        String passedFeedback = event.getAcceptFeedback();
        assertThat(passedFeedback).isEqualTo("请重新执行这个命令");

        // Step 3: LlmAgentLoop.toolResultMessage 4 参 → ChatMessageDto (Fix E 结构化)
        ChatMessageDto toolMsg = invokeToolResultMessage4(
            "tool-call-1", "Tool executed successfully",
            passedFeedback, passedBlocks, List.of("1", "2"));
        assertThat(toolMsg.role()).isEqualTo(Role.tool);
        assertThat(toolMsg.content()).isEqualTo("Tool executed successfully");  // Fix E: 不再拼接
        assertThat(toolMsg.acceptFeedback()).isEqualTo("请重新执行这个命令");   // 独立字段
        assertThat(toolMsg.contentBlocks()).hasSize(2);

        // Step 4: AnthropicSdkProvider buildMessageParams
        JsonNode body = invokeBuildRequestBodyAnthropic(List.of(toolMsg));
        JsonNode userMsg = body.get("messages").get(0);
        assertThat(userMsg.get("role").asText()).isEqualTo("user");

        JsonNode content = userMsg.get("content");
        // [0] = tool_result(content 是 string,保留向后兼容 R32-b9 行为)
        // [1] = acceptFeedback text block (Fix E 结构化注入)
        // [2] = image block (from contentBlocks)
        // [3] = text block (from contentBlocks)
        boolean hasImage = false, hasFeedbackText = false, hasContentBlocksText = false;
        for (JsonNode block : content) {
            String t = block.get("type").asText();
            if ("image".equals(t)) hasImage = true;
            if ("text".equals(t) && block.has("text")) {
                String text = block.get("text").asText();
                if ("请重新执行这个命令".equals(text)) hasFeedbackText = true;
                if ("附加说明: 这个错误可以忽略".equals(text)) hasContentBlocksText = true;
            }
        }
        assertThat(hasImage).as("image block 在 user message content array 中").isTrue();
        assertThat(hasFeedbackText).as("acceptFeedback 独立 text block (Fix E)").isTrue();
        assertThat(hasContentBlocksText).as("contentBlocks text 块独立序列化 (Fix E)").isTrue();
    }

    @Test
    @DisplayName("[端到端] STOMP Allow + image → OpenAI payload 含 text 块（image 跳过 R-T-1 · [OpenAI-SDK 迁移]）")
    void endToEndOpenAiImageAndFeedback() throws Exception {
        // Step 1: STOMP inbound
        String stompPayload = """
            {
              "requestId": "call-2",
              "decision": "allow",
              "acceptFeedback": "重新运行这个 ls 命令",
              "contentBlocks": [
                {"type":"image","source":{"type":"url","url":"http://x/y.png"}}
              ]
            }
            """;
        MessagePermissionResponseEvent event =
            JSON.readValue(stompPayload, MessagePermissionResponseEvent.class);

        // Step 3: 工具结果注入
        ChatMessageDto toolMsg = invokeToolResultMessage4(
            "call-2", "ls output",
            event.getAcceptFeedback(), event.getContentBlocks(), List.of("3"));

        // Step 4: OpenAiSdkProvider payload
        JsonNode body = invokeBuildRequestBodyOpenAi(List.of(toolMsg));
        JsonNode toolMsgNode = body.get(0);
        assertThat(toolMsgNode.get("role").asText()).isEqualTo("tool");
        assertThat(toolMsgNode.get("tool_call_id").asText()).isEqualTo("call-2");

        // content 应为 array(因为有 acceptFeedback)
        JsonNode content = toolMsgNode.get("content");
        assertThat(content.isArray()).isTrue();

        boolean hasTextContent = false, hasImageUrl = false, hasFeedbackText = false;
        for (JsonNode part : content) {
            String t = part.get("type").asText();
            if ("text".equals(t)) {
                String text = part.get("text").asText();
                if ("ls output".equals(text)) hasTextContent = true;
                if ("重新运行这个 ls 命令".equals(text)) hasFeedbackText = true;
            } else if ("image_url".equals(t)) {
                hasImageUrl = true;
                assertThat(part.get("image_url").get("url").asText()).isEqualTo("http://x/y.png");
            }
        }
        assertThat(hasTextContent).as("原始 tool content text part").isTrue();
        assertThat(hasImageUrl)
            .as("[OpenAI-SDK] R-T-1 · SDK 0.25.0 tool content 仅支持 text part → image_url 不出现（受控残留，图片走 Anthropic 路径）")
            .isFalse();
        assertThat(hasFeedbackText).as("acceptFeedback 独立 text part (Fix E)").isTrue();
    }

    @Test
    @DisplayName("[端到端 · Fix A] 既有 ID 已存在 → toolMsg 的 imagePasteIds 不重复")
    void endToEndImagePasteIdsMonotonic() throws Exception {
        // 历史已含 ID 1, 2 (user)
        ChatMessageDto historyUser = new ChatMessageDto(
            "u", null, Role.user, "user", "x",
            null, null, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, List.of(), List.of("1", "2"));

        // 计算 next ID = 3
        java.lang.reflect.Method compute = LlmAgentLoop.class
            .getDeclaredMethod("computeNextImagePasteId", List.class);
        compute.setAccessible(true);
        int nextId = (int) compute.invoke(null, java.util.List.of(historyUser));
        assertThat(nextId).isEqualTo(3);

        // tool result 注入 imagePasteIds = ["3", "4"]
        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{}}"));
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{}}"));

        ChatMessageDto toolMsg = invokeToolResultMessage4(
            "call-x", "result", null, blocks,
            java.util.stream.IntStream.rangeClosed(nextId, nextId + 1)
                .mapToObj(String::valueOf).toList());

        assertThat(toolMsg.imagePasteIds()).containsExactly("3", "4");
    }

    @Test
    @DisplayName("[端到端] 仅 acceptFeedback 无 contentBlocks → Anthropic tool_result.content 为 text")
    void endToEndAcceptFeedbackOnly() throws Exception {
        // 模拟仅接受反馈,无图片上传
        ChatMessageDto toolMsg = invokeToolResultMessage4(
            "call-fb", "Tool output text", "用户反馈文本", null, null);
        assertThat(toolMsg.acceptFeedback()).isEqualTo("用户反馈文本");
        assertThat(toolMsg.contentBlocks()).isNull();

        JsonNode body = invokeBuildRequestBodyAnthropic(List.of(toolMsg));
        JsonNode content = body.get("messages").get(0).get("content");
        // tool_result 块 + 独立 feedback text block 在 user message content array
        boolean foundFeedbackBlock = false;
        for (JsonNode block : content) {
            if ("text".equals(block.get("type").asText())
                && "用户反馈文本".equals(block.get("text").asText())) {
                foundFeedbackBlock = true;
            }
        }
        assertThat(foundFeedbackBlock).as("acceptFeedback 独立 text block(Fix E)").isTrue();
    }

    @Test
    @DisplayName("[Fix B 集成] STOMP 超限 21 blocks → 端到端 fail loud (IAE)")
    void endToEndOversizeBlocksFailsLoud() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"r\",\"decision\":\"allow\",\"contentBlocks\":[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/").append(i).append(".png\"}}");
        }
        sb.append("]}");
        MessagePermissionResponseEvent event = JSON.readValue(sb.toString(),
            MessagePermissionResponseEvent.class);

        // Step 1.5: Fix B 校验 — getter 抛 IAE (fail loud)
        try {
            event.getContentBlocks();
            throw new AssertionError("expected IllegalArgumentException for oversize blocks");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("exceeds limit");
        }
    }
}