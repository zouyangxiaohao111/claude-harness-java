package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ContentBlockDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * [content-blocks V331] 三家协议出站请求体不受 blocks 影响（回归）。
 *
 * <p><b>WHY</b>：content_blocks 只作渲染/顺序真源，⛔ 绝不进入任一 provider 的请求体。
 * 本测试用「同一段历史，assistant 消息带 blocks vs 不带 blocks」分别组装请求，断言：
 * <ul>
 *   <li>Anthropic messages（content 数组含 text/thinking/tool_use）：wire 逐字相同；</li>
 *   <li>OpenAI Chat Completions（content 字符串 + tool_calls + role=tool）：请求参数逐字相同；</li>
 *   <li>OpenAI Responses（output items：message/function_call/function_call_output）：input 逐字相同；</li>
 *   <li>且三者文本里都不出现 blocks / apiBlockIndex 字段。</li>
 * </ul>
 * 这锁定「一轮含多个工具调用 + 中间文本」的场景（多工具 + text 交错）。
 */
@DisplayName("[content-blocks V331] provider 请求体不受 blocks 影响")
class ContentBlocksProviderNeutralityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            "u-1", "sess-cb-wire", Role.user, "user", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto assistant(boolean withBlocks) {
        List<ToolCallDto> calls = new ArrayList<>();
        calls.add(new ToolCallDto("call_1", "Bash", "{\"cmd\":\"pwd\"}", null, null));
        calls.add(new ToolCallDto("call_2", "Read", "{\"file\":\"a.txt\"}", null, null));
        ChatMessageDto dto = new ChatMessageDto(
            "a-1", "sess-cb-wire", Role.assistant, "assistant", "intermediate text",
            "thinking body", calls, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false)
            .withThinkingSignature("sig-1");
        if (!withBlocks) {
            return dto;
        }
        // \u5230\u8fbe\u987a\u5e8f\uff1a\u5de5\u51771 \u2192 \u5de5\u51772 \u2192 \u4e2d\u95f4\u6587\u672c\uff08\u975e\u89c4\u8303\u987a\u5e8f\uff09
        return dto.withBlocks(List.of(
            ContentBlockDto.toolUse("call_1", "Bash", "{\"cmd\":\"pwd\"}", 0),
            ContentBlockDto.toolUse("call_2", "Read", "{\"file\":\"a.txt\"}", 1),
            ContentBlockDto.text("intermediate text", 2)));
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            "t-" + toolCallId, "sess-cb-wire", Role.tool, "tool", content,
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, "a-1",
            null, null, null, null, false, false);
    }

    private static List<ChatMessageDto> history(boolean withBlocks) {
        return List.of(user("run it"), assistant(withBlocks),
            toolResult("call_1", "/tmp"), toolResult("call_2", "file body"));
    }

    private static String anthropicWire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return String.valueOf(m.invoke(null, history));
    }

    @Test
    @DisplayName("Anthropic messages wire 不受 blocks 影响")
    void anthropicWireUnchanged() throws Exception {
        String without = anthropicWire(history(false));
        String with = anthropicWire(history(true));
        assertEquals(without, with, "blocks \u4e0d\u5f97\u6539\u53d8 Anthropic content \u6570\u7ec4");
        assertFalse(with.contains("apiBlockIndex"), "blocks \u4e0d\u5f97\u51fa\u73b0\u5728 wire \u4e0a");
        assertFalse(with.contains("blocks="), "blocks \u4e0d\u5f97\u51fa\u73b0\u5728 wire \u4e0a");
    }

    @Test
    @DisplayName("OpenAI Chat Completions 请求参数不受 blocks 影响")
    void openAiChatParamsUnchanged() {
        ArrayNode tools = JSON.createArrayNode();
        var without = OpenAiSdkProvider.buildRequestParams("gpt-x", null, history(false), tools,
            null, null, null, null, null, false, false, 0);
        var with = OpenAiSdkProvider.buildRequestParams("gpt-x", null, history(true), tools,
            null, null, null, null, null, false, false, 0);
        assertNotNull(with);
        assertEquals(String.valueOf(without), String.valueOf(with), "blocks \u4e0d\u5f97\u6539\u53d8 Chat \u8bf7\u6c42");
        assertFalse(String.valueOf(with).contains("apiBlockIndex"));
    }

    @Test
    @DisplayName("OpenAI Responses input items 不受 blocks 影响")
    void responsesInputUnchanged() {
        String without = String.valueOf(ResponsesSdkProvider.buildInput(history(false)));
        String with = String.valueOf(ResponsesSdkProvider.buildInput(history(true)));
        assertEquals(without, with, "blocks \u4e0d\u5f97\u6539\u53d8 Responses output items");
        assertFalse(with.contains("apiBlockIndex"));
    }
}
