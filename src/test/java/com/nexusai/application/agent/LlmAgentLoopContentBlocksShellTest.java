package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [OD-D5] 发送层 contentBlocks[0].text 包壳（busy 带图消息）测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: busy 带图消息经 drain 的
 * {@code buildUserMessageWithImages} 产物 contentBlocks=[text(原文), ...image]（content 亦为原文，
 * 但 AnthropicSdkProvider:2195-2203 / OpenAiSdkProvider role=user contentBlocks 分支在 contentBlocks
 * 非空时<b>弃 content</b>，只序列化 contentBlocks）→ 若发送层 {@code wrapQueuedMessagesForApi} 仍只
 * 包 content 字段，模型看不到 busy 中文提醒壳（模型不知用户忙时插队的新消息待处理）。本测试验证壳
 * 进 contentBlocks[0].text、幂等（已带壳不二次）、原消息不被污染。
 */
@DisplayName("[OD-D5] 发送层 contentBlocks[0].text 包壳（busy 带图消息）")
class LlmAgentLoopContentBlocksShellTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static ChatMessageDto busyImageMsg(String id, String text) {
        ObjectNode textBlock = JsonNodeFactory.instance.objectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        JsonNode imgBlock = LlmAgentLoop.imageContentBlock("image/png", PNG_BASE64);
        return LlmAgentLoop.toMessage(Role.user, text, null, id,
            List.of(textBlock, imgBlock), List.of("1"), false)
            .withQueuedOrigin("busy-queued");
    }

    @Test
    @DisplayName("busy-queued + contentBlocks → 中文提醒壳进 blocks[0].text，image 块原样保留")
    void busyQueuedWithContentBlocks_wrapsBlocks0Text() {
        String raw = "帮我看看这张图里的代码";
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(
            List.of(busyImageMsg("msg-busy-img", raw)));

        ChatMessageDto wrapped = out.get(0);
        assertThat(wrapped.contentBlocks()).hasSize(2);
        JsonNode textBlock = (JsonNode) wrapped.contentBlocks().get(0);
        assertThat(textBlock.get("type").asText()).isEqualTo("text");
        assertThat(textBlock.get("text").asText())
            .as("busy 带图消息壳必须进 contentBlocks[0].text（contentBlocks 序列化弃 content）")
            .startsWith("<system-reminder>\n")
            .contains("用户在你工作时发来一条新消息")
            .contains(raw)
            .endsWith("\n</system-reminder>");
        // image 块原样保留（第 2 块不受影响）
        JsonNode imgBlock = (JsonNode) wrapped.contentBlocks().get(1);
        assertThat(imgBlock.get("type").asText()).isEqualTo("image");
        // 原消息不被污染（副本语义）：原 contentBlocks[0].text 仍为原文
        JsonNode origFirst = (JsonNode) busyImageMsg("msg-busy-img", raw).contentBlocks().get(0);
        assertThat(origFirst.get("text").asText()).isEqualTo(raw);
    }

    @Test
    @DisplayName("contentBlocks[0].text 已 <system-reminder> 开头 → 幂等跳过（防三层）")
    void contentBlocksIdempotent_skipWhenAlreadyWrapped() {
        ChatMessageDto pre = busyImageMsg("msg-pre", "原文本");
        ObjectNode first = ((ObjectNode) pre.contentBlocks().get(0)).deepCopy();
        first.put("text", "<system-reminder>\n已带壳\n</system-reminder>");
        ChatMessageDto preWrapped = pre.withContentBlocks(List.of(first, pre.contentBlocks().get(1)));
        List<ChatMessageDto> input = List.of(preWrapped);

        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(input);
        assertThat(out).as("幂等跳过：全量无命中返回原引用（零行为变化）").isSameAs(input);
        assertThat(((JsonNode) out.get(0).contentBlocks().get(0)).get("text").asText())
            .as("已 <system-reminder> 开头的 blocks[0].text 不再包第二层")
            .isEqualTo("<system-reminder>\n已带壳\n</system-reminder>");
    }

    @Test
    @DisplayName("contentBlocks 非空且 queuedOrigin=null → 零包壳（普通图片消息零变化）")
    void contentBlocksNullOrigin_noWrap() {
        ObjectNode textBlock = JsonNodeFactory.instance.objectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "普通图片消息");
        JsonNode imgBlock = LlmAgentLoop.imageContentBlock("image/png", PNG_BASE64);
        ChatMessageDto plain = LlmAgentLoop.toMessage(Role.user, "普通图片消息", null, "msg-plain",
            List.of(textBlock, imgBlock), List.of("9"), false);
        List<ChatMessageDto> input = List.of(plain);

        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(input);
        assertThat(out).as("无 queuedOrigin 命中返回原引用").isSameAs(input);
        assertThat(((JsonNode) out.get(0).contentBlocks().get(0)).get("text").asText())
            .as("普通图片消息 contentBlocks 零包壳")
            .isEqualTo("普通图片消息");
    }
}
