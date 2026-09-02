package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatMessageDto isApiErrorMessage 消息级错误标志 + api_retry 载荷字段定向测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>:
 * <ol>
 *   <li><b>isApiErrorMessage 默认 false/可空</b>（用户拍板 #13，CC baseCreate 默认 false messages.ts:357）
 *       — 前端可忽略；普通消息必须不触发 API 错误分支。</li>
 *   <li><b>createAssistantAPIErrorMessage 结构</b>（messages.ts:435-456）— content 空 → NO_CONTENT_MESSAGE
 *       + isApiErrorMessage=true + apiError/error/errorDetails，供 query.ts:1262 lastMessage.isApiErrorMessage
 *       （skip stop-hooks + return completed）与 :178 isWithheldMaxOutputTokens（apiError==='max_output_tokens'）消费。</li>
 *   <li><b>api_retry 事件载荷</b>（messages.ts:4596-4598 retryAttempt/maxRetries/retryInMs）— 重试退避期间
 *       ApiRetryEvent 事件载荷承载 retryAttempt/maxRetries/retryDelayMs（ApiRetryEventTest 覆盖），
 *       ChatMessageDto 消息级不携带重试载荷（CC api_retry 仅为流事件，query.ts 0 命中）。</li>
 * </ol>
 */
class ChatMessageDtoIsApiErrorMessageTest {

    @Test
    @DisplayName("默认构造（17 参兼容构造器）isApiErrorMessage=false + api_retry 载荷 null · CC baseCreate 默认 false (messages.ts:357)")
    void defaultIsApiErrorMessageFalse() {
        ChatMessageDto dto = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", "hello", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of());
        assertThat(dto.isApiErrorMessage()).isFalse();
        assertThat(dto.apiError()).isNull();
        assertThat(dto.error()).isNull();
        assertThat(dto.errorDetails()).isNull();
    }

    @Test
    @DisplayName("createAssistantApiErrorMessage：isApiErrorMessage=true + apiError/error='max_output_tokens'（claude.ts:2274/2289）")
    void assistantApiErrorMessageStructure() {
        ChatMessageDto dto = ApiErrorMessageFactory.createAssistantApiErrorMessage(
            "Claude's response exceeded the maximum", "max_output_tokens", "max_output_tokens", null);
        assertThat(dto.isApiErrorMessage()).isTrue();
        assertThat(dto.apiError()).isEqualTo("max_output_tokens");
        assertThat(dto.error()).isEqualTo("max_output_tokens");
        assertThat(dto.errorDetails()).isNull();
        assertThat(dto.content()).isEqualTo("Claude's response exceeded the maximum");
        assertThat(dto.role()).isEqualTo(Role.assistant);
    }

    @Test
    @DisplayName("createAssistantApiErrorMessage：content 空 → NO_CONTENT_MESSAGE（messages.ts:450）")
    void emptyContentUsesNoContentMessage() {
        ChatMessageDto dto = ApiErrorMessageFactory.createAssistantApiErrorMessage(
            "", "max_output_tokens", "max_output_tokens", null);
        assertThat(dto.content()).isEqualTo(ApiErrors.NO_CONTENT_MESSAGE);
        assertThat(dto.isApiErrorMessage()).isTrue();
    }

    @Test
    @DisplayName("isWithheldMaxOutputTokens 判定等价：type=assistant && apiError==='max_output_tokens'（query.ts:178）")
    void isWithheldMaxOutputTokensEquivalence() {
        ChatMessageDto dto = ApiErrorMessageFactory.createAssistantApiErrorMessage(
            "ctx exceeded", "max_output_tokens", "max_output_tokens", null);
        boolean isWithheld = dto.role() == Role.assistant
            && "max_output_tokens".equals(dto.apiError());
        assertThat(isWithheld).isTrue();
    }
}
