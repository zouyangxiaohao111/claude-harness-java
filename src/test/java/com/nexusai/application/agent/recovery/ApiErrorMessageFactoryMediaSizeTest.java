package com.nexusai.application.agent.recovery;

import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P-11 生产生产者] {@link ApiErrorMessageFactory#createMediaSizeErrorApiMessage} 单测。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * 消息级 media 恢复链的闭环依赖 errorDetails 命中 CC {@code isMediaSizeError} 子串
 * （errors.ts:133-139）。本测试验证生产生产者把异常级媒体错误转回消息级时，分支条件与
 * CC {@code getAssistantMessageFromError} 媒体分支（errors.ts:577-586 / :612-639）逐字对齐：
 * image-exceeds/many-image 需 status 400 + 子串 AND，PDF 无 status 闸。若条件漂移，产出的
 * errorDetails 将无法使 {@code isMediaSizeErrorMessage} 命中，恢复链断链 —— 断言错误类型/
 * 非媒体错误返回 null 是 RED teeth。
 */
class ApiErrorMessageFactoryMediaSizeTest {

    @Test
    @DisplayName("image exceeds + maximum (400) → 非 null 媒体错误消息（isApiErrorMessage=true + errorDetails=原文 + content=getImageTooLargeErrorMessage 等价）· CC errors.ts:612-623")
    void imageExceedsMaximum_400_producesMessage() {
        String raw = "image exceeds 5 MB maximum: 5316852 bytes > 5242880 bytes";
        LlmApiException e = LlmApiException.imageError(400, Collections.emptyMap(), raw);

        ChatMessageDto msg = ApiErrorMessageFactory.createMediaSizeErrorApiMessage(e);

        assertThat(msg).as("status 400 + 'image exceeds'+'maximum' 必须命中 image-exceeds 分支").isNotNull();
        assertThat(msg.isApiErrorMessage())
            .as("isApiErrorMessage=true · CC messages.ts:453")
            .isTrue();
        assertThat(msg.errorDetails())
            .as("errorDetails=API 错误原文 · CC errors.ts:621 errorDetails: error.message")
            .isEqualTo(raw);
        assertThat(msg.content())
            .as("content=getImageTooLargeErrorMessage() non-interactive 等价 · CC errors.ts:619/186-190")
            .isEqualTo(ApiErrorMessageFactory.IMAGE_TOO_LARGE_MESSAGE);
    }

    @Test
    @DisplayName("many-image dimension (400) → 非 null 且 error='invalid_request' · CC errors.ts:626-639")
    void manyImageDimension_400_producesMessage() {
        String raw = "image dimensions exceed the limit for many-image requests (2000px)";
        LlmApiException e = LlmApiException.imageError(400, Collections.emptyMap(), raw);

        ChatMessageDto msg = ApiErrorMessageFactory.createMediaSizeErrorApiMessage(e);

        assertThat(msg).as("status 400 + 'image dimensions exceed'+'many-image' 必须命中 many-image 分支").isNotNull();
        assertThat(msg.isApiErrorMessage()).isTrue();
        assertThat(msg.errorDetails()).isEqualTo(raw);
        assertThat(msg.error())
            .as("many-image 分支 error='invalid_request' · CC errors.ts:636")
            .isEqualTo("invalid_request");
        assertThat(msg.content())
            .as("many-image 分支 content · CC errors.ts:633-635")
            .isEqualTo(ApiErrorMessageFactory.MANY_IMAGE_DIMENSION_MESSAGE);
    }

    @Test
    @DisplayName("PDF page limit（无 status 闸）→ 非 null 且 errorDetails 命中 PDF 正则 · CC errors.ts:577-586")
    void pdfPageLimit_producesMessage() {
        String raw = "prompt is too long: input too large, maximum of 5 PDF pages";
        LlmApiException e = new LlmApiException(400, Collections.emptyMap(), raw);

        ChatMessageDto msg = ApiErrorMessageFactory.createMediaSizeErrorApiMessage(e);

        assertThat(msg).as("message 命中 'maximum of N PDF pages' 正则必须转消息级").isNotNull();
        assertThat(msg.isApiErrorMessage()).isTrue();
        assertThat(msg.errorDetails()).isEqualTo(raw);
        assertThat(msg.error()).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("异常级 media（非 CC 子串，如 413 image_too_large: image dimensions exceed limit）→ null（无法消息级闭环）")
    void nonCcMediaError_returnsNull() {
        // 既有 P-11 测试用例的错误体：含 'image dimensions exceed' 但缺 'many-image'、status 413 非 400
        LlmApiException e = LlmApiException.imageError(413, Collections.emptyMap(),
            "image_too_large: image dimensions exceed limit");

        ChatMessageDto msg = ApiErrorMessageFactory.createMediaSizeErrorApiMessage(e);

        assertThat(msg)
            .as("非 CC 子串（缺 many-image）/ 非 400 → 不转消息级，回落异常级直 surface")
            .isNull();
    }

    @Test
    @DisplayName("image exceeds + maximum 但 status 非 400 → null（CC 分支硬性 status 400 闸 · errors.ts:614-617）")
    void imageExceedsMaximum_Non400_returnsNull() {
        LlmApiException e = LlmApiException.imageError(413, Collections.emptyMap(),
            "image exceeds 5 MB maximum: too big");

        assertThat(ApiErrorMessageFactory.createMediaSizeErrorApiMessage(e))
            .as("status 413 非 400 → 不满足 errors.ts:614-617 → 不转消息级")
            .isNull();
    }

    @Test
    @DisplayName("null 错误 / 无文本 → null")
    void nullInput_returnsNull() {
        assertThat(ApiErrorMessageFactory.createMediaSizeErrorApiMessage(null)).isNull();
        assertThat(ApiErrorMessageFactory.createMediaSizeErrorApiMessage(
            new RuntimeException())).isNull();
    }

    @Test
    @DisplayName("产出的消息 errorDetails 必须使 ErrorClassifier.isMediaSizeErrorMessage 命中（闭环闸）")
    void producedMessage_closesMessageLevelPredicate() {
        String raw = "image exceeds 5 MB maximum: 5316852 bytes > 5242880 bytes";
        ChatMessageDto msg = ApiErrorMessageFactory.createMediaSizeErrorApiMessage(
            LlmApiException.imageError(400, Collections.emptyMap(), raw));

        assertThat(ErrorClassifier.isMediaSizeErrorMessage(msg))
            .as("isMediaSizeErrorMessage（errors.ts:147-153）对生产者的产物必须命中 —— 否则 LlmAgentLoop:4802 isMediaError 断链")
            .isTrue();
    }
}
