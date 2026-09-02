package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-01 + IMP-15 · AnthropicSdkProvider maxOutputTokens 解析测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ul>
 *   <li>流式 fallback 需要 {@code maxOutputTokensOverride = min(20000, getMaxOutputTokensForModel)}
 *       透传到 Anthropic Messages API 请求体 max_tokens（对齐 CC compact.ts:1317-1320）。</li>
 *   <li>[IMP-15] max_tokens 不得再硬编码 4096（D-29）：override=null 时按模型解析
 *       {@code getMaxOutputTokensForModel(modelName)}（对齐 CC claude.ts:3399-3419）。</li>
 *   <li>[IMP-15] 64k 升级：ESCAPED override=64000 必须直达请求体（对齐 CC query.ts:1213
 *       maxOutputTokensOverride=ESCALATED_MAX_TOKENS）。</li>
 * </ul>
 */
class AnthropicSdkProviderMaxOutputTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("maxOutputTokensOverride 透传到请求体 max_tokens")
    void maxOutputTokensOverrideGoesToRequestBody() throws Exception {
        JsonNode body = buildParamsWire("claude-opus-4", 20_000);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("64k 升级 override=64000 直达请求体（CC ESCALATED_MAX_TOKENS）")
    void escalatedOverrideReachesRequestBody() throws Exception {
        JsonNode body = buildParamsWire("claude-sonnet-4-6", 64_000);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(64_000);
    }

    @Test
    @DisplayName("override=null → 按模型解析 max_tokens（不再硬编码 4096 · INV-12）")
    void nullOverrideUsesModelResolution() throws Exception {
        // claude-opus-4 → 模型族 default 32_000（CC context.ts:180-182 opus-4 → 32k）
        JsonNode body = buildParamsWire("claude-opus-4", null);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(32_000);
    }

    @Test
    @DisplayName("64k 旗舰模型按模型解析 default=64000（DRIFT-10 生效：不再截断长输出）")
    void opus46ModelResolvesTo64000() {
        assertThat(AnthropicSdkProvider.getMaxOutputTokensForModel("claude-opus-4-6"))
            .as("opus-4-6 原生 default 64k（CC context.ts:167-169）")
            .isEqualTo(64_000);
    }

    @Test
    @DisplayName("tengu_otk_slot_v1 gate 开启 → cap 到 CAPPED_DEFAULT_MAX_TOKENS(8000)（CC claude.ts:3408）")
    void capEnabledCapsTo8000() {
        String prev = System.getProperty("nexusai.feature.tengu-otk-slot-v1");
        System.setProperty("nexusai.feature.tengu-otk-slot-v1", "true");
        try {
            assertThat(AnthropicSdkProvider.getMaxOutputTokensForModel("claude-sonnet-4-6"))
                .as("gate 开启时 default 被 cap 到 8000（slot-reservation · CC claude.ts:3408-3410）")
                .isEqualTo(8_000);
        } finally {
            if (prev == null) {
                System.clearProperty("nexusai.feature.tengu-otk-slot-v1");
            } else {
                System.setProperty("nexusai.feature.tengu-otk-slot-v1", prev);
            }
        }
    }

    @Test
    @DisplayName("CLAUDE_CODE_MAX_OUTPUT_TOKENS env override 封顶 upperLimit（CC envValidation.ts:28-36）")
    void envOverrideCappedToUpperLimit() {
        // opus-4-6 upperLimit=128_000，default=64_000；env=150_000 → 封顶 128_000
        assertThat(AnthropicSdkProvider.validateBoundedIntEnvVar(
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "150000", 64_000, 128_000))
            .as("parsed > upperLimit → 封顶 upperLimit（CC envValidation.ts:28-36）")
            .isEqualTo(128_000);
        // env 非法 → 回落 default
        assertThat(AnthropicSdkProvider.validateBoundedIntEnvVar(
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "abc", 64_000, 128_000))
            .as("非数字 → 回落 default（CC envValidation.ts:18-27）")
            .isEqualTo(64_000);
        // env 合法且在界内 → 用 env
        assertThat(AnthropicSdkProvider.validateBoundedIntEnvVar(
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "20000", 64_000, 128_000))
            .as("合法 env → 直接使用（CC envValidation.ts:37）")
            .isEqualTo(20_000);
    }

    /**
     * [ER-IMP-07] finishReason 归一化：raw stop_reason 'max_tokens' / 'model_context_window_exceeded'
     * → 消息级 apiError='max_output_tokens'（对齐 CC claude.ts:2266-2292）。
     *
     * <p><b>WHY</b>: Anthropic 主 provider 下 max_tokens 恢复在循环层（LlmAgentLoop 谓词
     * {@code "length".equals}）不可达的根因是 finishReason 直通 raw stop_reason（'max_tokens'），
     * 与 OpenAI 'length' 不匹配（DC-21 / X-1）。循环层恢复判定只认消息级 apiError（query.ts:178
     * isWithheldMaxOutputTokens），因此 provider 必须把截断信号归一化为 apiError。
     */
    @Test
    @DisplayName("finishReason 归一化: raw 'max_tokens'/'model_context_window_exceeded' → apiError='max_output_tokens'")
    void finishReasonNormalizedToMaxOutputTokensApiError() {
        // 'max_tokens'（Anthropic raw stop_reason）→ apiError='max_output_tokens'
        assertThat(AnthropicSdkProvider.buildAssistantMessage(stateWithFinishReason("max_tokens")))
            .as("raw stop_reason 'max_tokens' 必须归一化为 apiError='max_output_tokens'（CC claude.ts:2266-2276）")
            .extracting(AssistantMessage::isMaxOutputTokensError, AssistantMessage::finishReason)
            .containsExactly(true, "max_tokens");
        // 'model_context_window_exceeded' 复用同一恢复路径（CC claude.ts:2279-2292）
        assertThat(AnthropicSdkProvider.buildAssistantMessage(stateWithFinishReason("model_context_window_exceeded")))
            .as("model_context_window_exceeded 复用 max_output_tokens 恢复（CC claude.ts:2287-2291）")
            .extracting(AssistantMessage::isMaxOutputTokensError)
            .isEqualTo(true);
        // 正常结束不产生 apiError
        assertThat(AnthropicSdkProvider.buildAssistantMessage(stateWithFinishReason("end_turn")))
            .as("end_turn 正常响应无 apiError（CC claude.ts 无归一化分支）")
            .extracting(AssistantMessage::isMaxOutputTokensError)
            .isEqualTo(false);
    }

    private static AnthropicSdkProvider.StreamState stateWithFinishReason(String fr) {
        AnthropicSdkProvider.StreamState st = new AnthropicSdkProvider.StreamState();
        st.finishReason = fr;
        st.content.append("response");
        return st;
    }

    /** [DEC-RV-07 REWORK-2] 生产 SDK wire：buildMessageParams → _body() 序列化 JsonNode。 */
    private static JsonNode buildParamsWire(String modelName, Integer maxOutputTokensOverride) throws Exception {
        com.anthropic.models.messages.MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            modelName, null, List.of(), null, maxOutputTokensOverride, null, null, null, null);
        return JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
            .writeValueAsString(params._body()));
    }
}
