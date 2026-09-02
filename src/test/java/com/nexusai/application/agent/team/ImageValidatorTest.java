package com.nexusai.infra.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [ER-IMP-12] ImageValidator 接线测试 · 对齐 CC imageValidation.ts:65-104 + query.ts:974-977。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: DC-13 中 ImageValidator 为全仓零调用死代码，
 * 用户拍板接线为「图片先验证非法 → image_error + 友好提示，不调模型」。本测试守住两条不变量：
 * <ol>
 *   <li><b>单元：超限 user 图 → ImageSizeError（用户友好 formatFileSize 文案）</b> ——
 *       对齐 imageValidation.ts:90-103；若回归裸字节文案或 Map 影子路径，断言 fail → RED。</li>
 *   <li><b>接线：前置校验捕获 ImageSizeError → assistant 友好消息 + IMAGE_ERROR 退出且
 *       callModel 未调用</b> —— 对齐 query.ts:974-977；若回归直接调模型 / 不附加消息，
 *       callModel 计数 + exitReason + 消息断言 fail → RED。</li>
 * </ol>
 */
class ImageValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** base64 image content block（CC imageValidation.ts:40-49 isBase64ImageBlock 形状）。 */
    private static ObjectNode base64ImageBlock(String data) {
        ObjectNode source = JSON.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", data);
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "image");
        block.set("source", source);
        return block;
    }

    /** user 消息携带 image contentBlocks · 对齐 messages.ts:545-554 user message + contentBlocks。 */
    private static ChatMessageDto userMessageWithImage(String text, List<?> contentBlocks) {
        return new ChatMessageDto(
            "m1", null, Role.user, "user", text, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, contentBlocks, List.of());
    }

    // ─── 单元：validateImagesForAPI ───

    @Test
    @DisplayName("超限 user 图 → ImageSizeError，message 用 formatFileSize 人类可读（非裸字节）")
    void oversizedImage_throwsImageSizeErrorWithReadableMessage() {
        int max = 5 * 1024 * 1024; // API_IMAGE_MAX_BASE64_SIZE = 5MB
        String bigData = "A".repeat(max + 1);

        List<ChatMessageDto> messages = List.of(userMessageWithImage("看这张图", List.of(base64ImageBlock(bigData))));

        assertThatThrownBy(() -> ImageValidator.validateImagesForAPI(messages, max))
            .isInstanceOf(ImageValidator.ImageSizeError.class)
            .satisfies(e -> {
                // CC imageValidation.ts:20-23 单图文案：Image base64 size (5.0MB) exceeds API limit (5.0MB). Please resize...
                String msg = e.getMessage();
                assertThat(msg).contains("Image base64 size (");
                assertThat(msg).contains("exceeds API limit (");
                assertThat(msg).contains("Please resize the image before sending.");
                // 人类可读 MB，非裸字节
                assertThat(msg).doesNotContain(max + " bytes").doesNotContain("1024");
            });
    }

    @Test
    @DisplayName("limit 内图片 → 通过（no-op），limit 边界 data.length==max → 通过")
    void withinLimitImage_passes() {
        int max = 5 * 1024 * 1024;
        // 恰好等于 limit 不算超限（CC imageValidation.ts:90 `>` 严格大于）
        String exact = "A".repeat(max);
        List<ChatMessageDto> messages = List.of(userMessageWithImage("ok", List.of(base64ImageBlock(exact))));
        ImageValidator.validateImagesForAPI(messages, max); // 不抛
    }

    @Test
    @DisplayName("非 user 消息 / 非 image 块 / 空 data → 跳过（CC imageValidation.ts:76/82）")
    void nonUserOrNonImage_skipped() {
        int max = 5 * 1024 * 1024;
        // assistant 消息即使带 image 块也不校验（CC 仅查 user）
        ChatMessageDto assistantWithImage = new ChatMessageDto(
            "a1", null, Role.assistant, "assistant", "reply", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(base64ImageBlock("A".repeat(max + 1))), List.of());
        // user 消息但 text block（type=text）→ 跳过
        ObjectNode textBlock = JSON.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "hello");
        ChatMessageDto userTextBlock = userMessageWithImage("txt", List.of(textBlock));
        // user 消息空 contentBlocks → 跳过
        ChatMessageDto userNoBlocks = userMessageWithImage("noblock", List.of());

        ImageValidator.validateImagesForAPI(List.of(assistantWithImage, userTextBlock, userNoBlocks), max); // 不抛
    }

    @Test
    @DisplayName("多图超限 → 聚合全部 oversized（CC imageValidation.ts:24-30 多图文案）")
    void multipleOversized_aggregatesAll() {
        int max = 5 * 1024 * 1024;
        String big = "A".repeat(max + 1);
        List<ChatMessageDto> messages = List.of(
            userMessageWithImage("fig1+2", List.of(base64ImageBlock(big), base64ImageBlock(big))));

        assertThatThrownBy(() -> ImageValidator.validateImagesForAPI(messages, max))
            .isInstanceOf(ImageValidator.ImageSizeError.class)
            .satisfies(e -> assertThat(e.getMessage())
                .contains("images exceed the API limit")
                .contains("Image 1:")
                .contains("Image 2:"));
    }

    // ─── 接线：queryLoop 前置校验 ───

    @Test
    @DisplayName("queryLoop 前置校验：超限 user 图 → IMAGE_ERROR 退出 + assistant 友好消息 + callModel 未调用")
    void queryLoop_oversizedImage_exitsImageErrorWithoutCallingModel() {
        LlmProvider provider = mock(LlmProvider.class);
        Mockito.doThrow(new IllegalStateException("must not call provider.stream directly"))
            .when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        var ctx = TestContexts.agentLoopContext(
            mock(ToolRegistry.class), factory, null, null, null);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        int max = 5 * 1024 * 1024;
        state.appendMessage(userMessageWithImage("大图", List.of(base64ImageBlock("A".repeat(max + 1)))));

        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        AtomicInteger callModelInvoked = new AtomicInteger(0);
        var deps = new LoopDepsForTest(ctx, callModelInvoked);

        var result = LlmAgentLoop.queryLoop(
            QueryParamsForTest.forLoop(state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // 1) 不调模型
        assertThat(callModelInvoked.get())
            .as("超限图片必须在调用模型前被拦截（CC query.ts:974-977 不调模型）")
            .isZero();
        // 2) IMAGE_ERROR 退出
        assertThat(result.finalState().exitReason())
            .as("图片非法 → IMAGE_ERROR 退出（对齐 CC return {reason:'image_error'}）")
            .isEqualTo(AgentState.ExitReason.IMAGE_ERROR);
        // 3) assistant 友好错误消息（含"exceeds API limit"）
        assertThat(state.lastAssistant())
            .as("必须附加用户友好 assistant 错误消息（createAssistantAPIErrorMessage）")
            .contains("exceeds API limit");
    }

    /** 极简 LoopDeps：追踪 callModel 调用次数，不真正发请求。 */
    private record LoopDepsForTest(
            com.nexusai.application.agent.loop.AgentLoopContext context,
            AtomicInteger callModelInvoked)
            implements com.nexusai.application.agent.loop.LoopDeps {
        @Override public boolean isMainLoop() { return true; }
        @Override public String resolveModel() { return "test-model"; }
        @Override public String uuid() { return "fixed-chain"; }
        @Override
        public com.nexusai.application.agent.loop.ModelResponse callModel(
                com.nexusai.application.agent.loop.ModelRequest request) {
            callModelInvoked.incrementAndGet();
            return com.nexusai.application.agent.loop.ModelResponse.SUBMITTED;
        }
    }

    /** QueryParams.forLoop 极简转发（避免 import 冲突）。 */
    private static class QueryParamsForTest {
        static com.nexusai.application.agent.loop.QueryParams forLoop(
                List<ChatMessageDto> messages, String systemPrompt, ToolUseContext tuc,
                QuerySource qs, String model, Integer maxTurns, Object taskBudget,
                String fallback, Boolean skipCache, Integer maxOutput,
                com.nexusai.application.agent.loop.LoopDeps deps, ProviderConfig cfg) {
            return com.nexusai.application.agent.loop.QueryParams.forLoop(
                messages, systemPrompt, tuc, qs, model, maxTurns, null, fallback,
                skipCache, maxOutput, deps, cfg);
        }
    }
}
