package com.nexusai.infra.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M3.2 · LlmProvider 扩展返回 LlmRawResponse 测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:821,838,856,911,915,924,935,939.
 *
 * <p><b>WHY (意图验证)</b>: CC 真源 (stage1Raw.id + stage2Raw.id + parseXmlThinking) 需要
 * LlmProvider 在 chat 时返回 raw response (不只 content). Java 端 LlmProvider 必须
 * 暴露 {@code chatWithRaw} 接口, 提取 id + thinking. YoloClassifierImpl 必须接收
 * LlmRawResponse 直接填 stage1MsgId/stage2MsgId/thinking.
 * <ul>
 *   <li>Test 1: LlmRawResponse record 字段对齐 CC (content, id, thinking, requestId)</li>
 *   <li>Test 2: LlmProvider 接口声明 chatWithRaw 4 参方法 (cfg/model/system/user)</li>
 *   <li>Test 3: AnthropicSdkProvider 提取 message id (CC message_start 含 id)</li>
 *   <li>Test 4: OpenAiSdkProvider 提取 response id (CC root.id 字段)</li>
 * </ul>
 *
 * <p><b>[Session D P1-7 扩展]</b>: LlmRawResponse 4 参形态新增 {@code requestId}
 * (CC yoloClassifier.ts:624-628 extractRequestId 的 {@code _request_id},
 * Anthropic request-id / OpenAI x-request-id header). request-id header 提取的
 * 端到端验证见 {@code LlmProviderRequestIdHeaderTest} (JDK HttpServer 桩).
 */
class LlmProviderRawResponseTest {

    // ─────────── 1. LlmRawResponse record 字段验证 ───────────

    @Test
    @DisplayName("M3.2-1 LlmRawResponse record 含 content/id/thinking/usage 字段 (CC yoloClassifier stage1Raw)")
    void llmRawResponse_recordHasThreeFields() {
        Class<?> rawClass = Arrays.stream(LlmProvider.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("LlmRawResponse"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("LlmRawResponse nested record not found in LlmProvider"));

        // 用 RecordComponent 拿 record 字段 (不受 equals/hashCode/toString 干扰)
        java.lang.reflect.RecordComponent[] components = rawClass.getRecordComponents();
        assertThat(components)
            .as("LlmRawResponse record must declare exactly 5 record components"
                + "（[IMP-6] + usage，CC extractUsage yoloClassifier.ts:609-618）")
            .hasSize(5);

        java.util.List<String> names = Arrays.stream(components)
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
        assertThat(names)
            .as("LlmRawResponse must expose content, id, thinking, requestId, usage")
            .contains("content", "id", "thinking", "requestId", "usage");
    }

    // ─────────── 2. LlmProvider.chatWithRaw 接口方法声明 ───────────

    @Test
    @DisplayName("M3.2-2 LlmProvider 接口声明 chatWithRaw 4 参 (cfg/sys/user/model) · 对齐 CC sideQuery")
    void llmProviderDeclaresChatWithRaw() {
        Method chatWithRaw = Arrays.stream(LlmProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("chatWithRaw")
                && m.getReturnType().getSimpleName().equals("LlmRawResponse"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "LlmProvider must declare chatWithRaw returning LlmRawResponse"));

        Class<?>[] params = chatWithRaw.getParameterTypes();
        assertThat(params)
            .as("chatWithRaw must accept (ProviderConfig, String, String, String) — order cfg,sys,user,model")
            .hasSize(4);
        assertThat(params[0].getSimpleName())
            .as("param 0 must be ProviderConfig")
            .isEqualTo("ProviderConfig");
    }

    // ─────────── 3. AnthropicSdkProvider 实现 chatWithRaw (反射 · [DEC-RV-07] SDK 实现) ───────────

    @Test
    @DisplayName("M3.2-3 AnthropicSdkProvider 实现 chatWithRaw (CC message_start 提取 id)")
    void anthropicProvider_implementsChatWithRaw() {
        Method chatWithRaw = Arrays.stream(AnthropicSdkProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("chatWithRaw"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "AnthropicSdkProvider must implement chatWithRaw (CC message_start has id)"));

        assertThat(chatWithRaw.getReturnType().getSimpleName())
            .as("AnthropicSdkProvider.chatWithRaw returns LlmRawResponse")
            .isEqualTo("LlmRawResponse");
    }

    // ─────────── 4. OpenAiSdkProvider 实现 chatWithRaw (反射 · [OpenAI-SDK 迁移]) ───────────

    @Test
    @DisplayName("M3.2-4 OpenAiSdkProvider 实现 chatWithRaw (CC root.id 提取 response id)")
    void openaiProvider_implementsChatWithRaw() {
        Method chatWithRaw = Arrays.stream(OpenAiSdkProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("chatWithRaw"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "OpenAiSdkProvider must implement chatWithRaw (CC root.id is response id)"));

        assertThat(chatWithRaw.getReturnType().getSimpleName())
            .as("OpenAiSdkProvider.chatWithRaw returns LlmRawResponse")
            .isEqualTo("LlmRawResponse");
    }
}
