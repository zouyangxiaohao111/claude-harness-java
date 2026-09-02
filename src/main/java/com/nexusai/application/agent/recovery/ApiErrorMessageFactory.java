package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CC createAssistantAPIErrorMessage 结构等价 · messages.ts:435-456。
 *
 * <p>CC（grep 自验 435）：
 * <pre>
 * export function createAssistantAPIErrorMessage({ content, apiError, error, errorDetails }) {
 *   return baseCreateAssistantMessage({
 *     content: [{ type: 'text', text: content === '' ? NO_CONTENT_MESSAGE : content }],
 *     isApiErrorMessage: true,   // 消息级错误标志（messages.ts:453；baseCreate 默认 false messages.ts:357）
 *     apiError,                  // messages.ts:454，如 'max_output_tokens'（claude.ts:2274/2289）
 *     error,                     // messages.ts:455
 *     errorDetails,              // messages.ts:456
 *   })
 * }
 * </pre>
 *
 * <p>Java 端映射为 {@link ChatMessageDto}（消息流载体）：isApiErrorMessage=true + apiError/error/errorDetails，
 * content 空 → {@link ApiErrors#NO_CONTENT_MESSAGE}。接线后的真实消费方：
 * <ul>
 *   <li><b>LlmAgentLoop max_tokens 耗尽路径</b>（ER-IMP-11 接线）—— append 后读本消息的
 *       {@code isApiErrorMessage=true}（query.ts:1262 lastMessage.isApiErrorMessage）→ 设
 *       skipStopPipeline=true（§14 Stop hooks 跳过，防死亡螺旋）+ hasHookForEvent('StopFailure')
 *       时执行 StopFailure hook（executeStopFailureHooks，query.ts:1263）；[P-6] exitReason 统一
 *       NORMAL（对齐 CC query.ts:1264 completed，不再生产 ExitReason.MAX_OUTPUT_TOKENS）；</li>
 *   <li><b>max_tokens 恢复触发信号</b>（query.ts:178 isWithheldMaxOutputTokens）—— msg.isMaxOutputTokensError()
 *       = apiError==='max_output_tokens'（LlmAgentLoop:3811 判定入口）。</li>
 * </ul>
 */
public final class ApiErrorMessageFactory {

    private ApiErrorMessageFactory() {
        // 纯静态工厂
    }

    /**
     * CC createAssistantAPIErrorMessage · messages.ts:435-456。
     *
     * @param content     消息文本（空 → NO_CONTENT_MESSAGE）
     * @param apiError    API 错误类型（CC apiError · messages.ts:454），如 'max_output_tokens'
     * @param error       错误描述（CC error · messages.ts:455）
     * @param errorDetails 错误详情（CC errorDetails · messages.ts:456）
     * @return ChatMessageDto（role=assistant, isApiErrorMessage=true, content 空 → NO_CONTENT_MESSAGE）
     */
    public static ChatMessageDto createAssistantApiErrorMessage(
            String content, String apiError, String error, String errorDetails) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(),
            null,
            Role.assistant,
            "assistant",
            content == null || content.isEmpty() ? ApiErrors.NO_CONTENT_MESSAGE : content,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            OffsetDateTime.now(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            false,
            false,
            null,
            null,
            true,       // isApiErrorMessage=true · CC messages.ts:453
            apiError,   // CC apiError · messages.ts:454
            error,      // CC error · messages.ts:455
            errorDetails, // CC errorDetails · messages.ts:456
            null        // DEC-04 usage（API 错误消息无 usage）
        );
    }

    // ════════════════════════════════════════════════════════════════════
    // [P-11 生产生产者] 媒体尺寸错误 → assistant API 错误消息
    // · 对齐 CC getAssistantMessageFromError 媒体分支（errors.ts:577-586 / 612-639）
    // ════════════════════════════════════════════════════════════════════

    /** CC original: getImageTooLargeErrorMessage() non-interactive（errors.ts:186-190）
     *  {@code 'Image was too large. Try resizing the image or using a different approach.'} */
    public static final String IMAGE_TOO_LARGE_MESSAGE =
        "Image was too large. Try resizing the image or using a different approach.";

    /** CC original: many-image 分支 content（errors.ts:633-635 non-interactive 分支） */
    public static final String MANY_IMAGE_DIMENSION_MESSAGE =
        "An image in the conversation exceeds the dimension limit for many-image requests (2000px). "
            + "Start a new session with fewer images.";

    /** CC original: getPdfTooLargeErrorMessage() non-interactive（errors.ts:170-175 提示骨架） */
    public static final String PDF_TOO_LARGE_MESSAGE =
        "PDF too large. Try reading the file a different way (e.g., extract text with pdftotext).";

    /**
     * 媒体尺寸错误 → assistant API 错误消息 · 对齐 CC {@code getAssistantMessageFromError} 媒体分支
     * （errors.ts:577-586 PDF / :612-623 image exceeds / :626-639 many-image）。
     *
     * <p><b>CC 真源</b>：provider 层（claude.ts:2743/2801）捕获 API 错误后
     * {@code yield getAssistantMessageFromError(error, model)} 把媒体错误注入消息流——
     * content 为 {@code getImageTooLargeErrorMessage()} 等用户提示，errorDetails 为 API 错误原文
     * （errors.ts:572/584/621/637），下游 {@code isMediaSizeErrorMessage}（errors.ts:147-153）
     * 据此命中。本方法即该转换的 Java 等价（CC createAssistantAPIErrorMessage 结构由
     * {@link #createAssistantApiErrorMessage} 承载）。
     *
     * <p><b>Java 接线</b>：Java provider 以异常级（{@link LlmApiException} Kind.IMAGE，translateSdkError
     * 翻译产物，保留 status + body）表达媒体错误；LlmAgentLoop 在媒体错误处理点调用本方法把异常级
     * 转回消息级（isApiErrorMessage=true + errorDetails=原始错误），使消息级谓词
     * {@link ErrorClassifier#isMediaSizeErrorMessage}（errors.ts:147-153）闭环 → 走 reactive compact
     * 恢复链。分支条件与 errors.ts:133-139 {@code isMediaSizeError} 同源（image branches 需
     * status 400 + 子串 AND；PDF 无 status 闸），保证产出的 errorDetails 一定使消息级判定命中。
     *
     * @param error 流错误（provider 已翻译为 LlmApiException，保留 status/body）
     * @return 媒体错误 assistant 消息（isApiErrorMessage=true + errorDetails=API 错误原文），
     *         或 null = 非 CC 媒体尺寸错误（不满足 errors.ts:133-139 子串条件，消息级无法闭环，
     *         调用方回落异常级直 surface）
     */
    public static ChatMessageDto createMediaSizeErrorApiMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        String raw = ErrorClassifier.rawErrorText(error);
        if (raw == null) {
            return null;
        }
        int status = error instanceof LlmApiException lae ? lae.status() : -1;
        // image exceeds + maximum（status 400）· errors.ts:612-623
        //   createAssistantAPIErrorMessage({content: getImageTooLargeErrorMessage(), errorDetails})
        if (status == 400 && raw.contains("image exceeds") && raw.contains("maximum")) {
            return createAssistantApiErrorMessage(IMAGE_TOO_LARGE_MESSAGE, null, null, raw);
        }
        // many-image dimension（status 400）· errors.ts:626-639
        //   createAssistantAPIErrorMessage({content: 提示, error: 'invalid_request', errorDetails})——
        //   error='invalid_request'（errors.ts:636），apiError 不设（null · CC 无 apiError 字段）
        if (status == 400 && raw.contains("image dimensions exceed") && raw.contains("many-image")) {
            return createAssistantApiErrorMessage(MANY_IMAGE_DIMENSION_MESSAGE,
                null, "invalid_request", raw);
        }
        // PDF page limit · errors.ts:577-586（无 status 闸，仅 message 正则）
        //   createAssistantAPIErrorMessage({content: getPdfTooLargeErrorMessage(), error: 'invalid_request', errorDetails})——
        //   error='invalid_request'（errors.ts:583），apiError 不设（null）
        if (java.util.regex.Pattern.matches(".*maximum of \\d+ PDF pages.*", raw)) {
            return createAssistantApiErrorMessage(PDF_TOO_LARGE_MESSAGE,
                null, "invalid_request", raw);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-A4-2 · A-24 isPtlError 收窄] prompt-too-long 错误 → assistant API 错误消息
    // · 对齐 CC getAssistantMessageFromError PTL 分支（errors.ts:560-574）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 异常级 prompt-too-long 错误 → assistant API 错误消息 · 对齐 CC {@code getAssistantMessageFromError}
     * PTL 分支（errors.ts:560-574）。
     *
     * <p><b>CC 真源</b>（errors.ts:562-573）：{@code error.message.toLowerCase().includes('prompt is too long')}
     * （大小写不敏感 · Vertex 返回 "Prompt is too long" 大写，errors.ts:560-561 注释）→
     * {@code createAssistantAPIErrorMessage({content: PROMPT_TOO_LONG_ERROR_MESSAGE,
     * error: 'invalid_request', errorDetails: error.message})}——content 恒为 'Prompt is too long'，
     * errorDetails 为原始错误串，下游 {@code isPromptTooLongMessage}（errors.ts:64-77）据此命中。
     *
     * <p><b>Java 接线</b>：同 {@link #createMediaSizeErrorApiMessage}（P-11 模式）——Java provider
     * 以异常级（{@link LlmApiException}，translateSdkError 翻译产物，保留 status+body）表达 PTL 错误；
     * LlmAgentLoop 在 PTL 恢复判定点（A-24 isPtlError 收窄）调用本方法把异常级转回消息级
     * （isApiErrorMessage=true + content='Prompt is too long' + errorDetails=error.message），使消息级谓词
     * {@link ErrorClassifier#isPromptTooLongMessage}（errors.ts:64-77）闭环 → 走 reactive compact 恢复链
     * （CC isWithheld413 · query.ts:1070-1073 纯消息级）。分支条件与 errors.ts:562-564 同源
     * （error.message 大小写不敏感子串），保证产出的 content 一定使消息级判定命中。
     *
     * <p><b>收窄语义（A-24）</b>：旧 {@code ErrorClassifier.isPromptTooLong(Throwable)} 谓词匹配
     * 'context_length_exceeded' / 'max_context_window' / 裸 '413' 等宽异常（CC 不会把它们转成
     * 'Prompt is too long' 消息）→ 本方法仅认 'prompt is too long' 字面子串，消除异常级误触发 reactive。
     *
     * @param error 流错误（provider 已翻译为 LlmApiException，保留 status/body）
     * @return PTL assistant 错误消息（isApiErrorMessage=true + content='Prompt is too long' +
     *         errorDetails=error.message），或 null = 非 CC PTL 错误（message 不含 'prompt is too long'）
     */
    public static ChatMessageDto createPromptTooLongErrorApiMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        String raw = error.getMessage();
        if (raw == null) {
            return null;
        }
        // CC errors.ts:562-564: error.message.toLowerCase().includes('prompt is too long')
        //   （大小写不敏感 · Vertex "Prompt is too long" 大写 · errors.ts:560-561 注释）
        if (raw.toLowerCase().contains("prompt is too long")) {
            return createAssistantApiErrorMessage(
                ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE, null, "invalid_request", raw);
        }
        return null;
    }
}
