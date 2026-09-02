package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.openai.models.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-08-15 error-recovery REWORK] OpenAI 出站 system 消息过滤测试。
 *
 * <p><b>WHY</b>: 对齐 CC {@code normalizeMessagesForAPI}（Open-ClaudeCode/src/utils/messages.ts:2066-2072）
 * —— 模型上下文永不包含 system 消息：非 local_command 的 system 消息一律过滤
 * （仅 local_command 转 user 保留）。Java {@code ChatMessageDto} 无 local_command 概念
 * （全仓 grep 实证），故 {@link OpenAiSdkProvider#toSdkMessage} 的 {@code case system}
 * 直接返回 null（出站过滤），由调用侧 {@code buildRequestBody} / {@code buildSdkMessages}
 * 判空跳过。系统提示本身经 {@code buildRequestParams} 的 {@code systemPrompt} 参数
 * 以 SDK system 消息发送，不受本过滤影响。
 */
@DisplayName("[2026-08-15 error-recovery] OpenAI 出站 system 消息过滤 (对齐 CC normalizeMessagesForAPI:2066-2072)")
class OpenAiSdkProviderSystemFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatMessageDto message(Role role, String text) {
        return new ChatMessageDto(
            "m-" + role, "s1", role, null, text, null,
            null, null, null, null, null, java.time.OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 生产 SDK wire：buildSdkMessages → ObjectMappers 序列化（同 R32B9/R32C1 模式）。 */
    private static JsonNode sdkWire(List<ChatMessageDto> history) throws Exception {
        return JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(OpenAiSdkProvider.buildSdkMessages(history)));
    }

    @Test
    @DisplayName("buildSdkMessages: history 含 Role.system → system 被过滤, 仅剩 user")
    void buildSdkMessages_systemMessage_filteredOut() throws Exception {
        List<ChatMessageDto> history = List.of(
            message(Role.system, "system 注入提示"),   // 非 local_command system → 出站过滤
            message(Role.user, "用户请求"));
        JsonNode msgs = sdkWire(history);

        assertThat(msgs)
            .as("system 消息必须被过滤, 不得进入模型上下文 (对齐 CC normalizeMessagesForAPI:2066-2072)")
            .hasSize(1);
        assertThat(msgs.get(0).get("role").asText())
            .as("剩余消息必须是 user").isEqualTo("user");
        assertThat(msgs.get(0).get("content").asText())
            .as("user 消息 content 完整保留").isEqualTo("用户请求");
    }

    @Test
    @DisplayName("buildRequestParams: history 内 system 被过滤, systemPrompt 参数仍作为 SDK system 消息发送")
    void buildRequestParams_historySystemFiltered_systemPromptKept() throws Exception {
        List<ChatMessageDto> history = List.of(
            message(Role.system, "history 注入提示"),
            message(Role.user, "用户请求"));
        ChatCompletionCreateParams params = OpenAiSdkProvider.buildRequestParams(
            "gpt-test", "SYSTEM_PROMPT", history, null, null, false, null, null, null, false);

        JsonNode messages = JSON.readTree(com.openai.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params.messages()));
        assertThat(messages)
            .as("systemPrompt + user 共 2 条; history 内 system 被过滤").hasSize(2);
        assertThat(messages.get(0).get("role").asText())
            .as("首条为 systemPrompt 的 SDK system 消息").isEqualTo("system");
        assertThat(messages.get(0).get("content").asText())
            .as("systemPrompt 内容送达").isEqualTo("SYSTEM_PROMPT");
        assertThat(messages.get(1).get("role").asText())
            .as("次条为 user history 消息").isEqualTo("user");
        assertThat(messages.get(1).get("content").asText())
            .as("user 消息 content 完整保留").isEqualTo("用户请求");
        assertThat(messages.toString())
            .as("history 内 system 内容不得出现在任何消息中").doesNotContain("history 注入提示");
    }
}
