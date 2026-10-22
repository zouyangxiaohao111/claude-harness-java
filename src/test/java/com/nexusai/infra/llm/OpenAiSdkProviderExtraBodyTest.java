package com.nexusai.infra.llm;

import com.openai.models.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-10-22] 模型级「额外请求体」（{@code models.extra_body}）合并进 OpenAI 请求参数。
 *
 * <p>WHY（规则九 · 测试验证意图）：前端模型表单可填 extraBody，但此前后端**没有任何 provider
 * 读取它**（只存不读）——配置了 DeepSeek {@code {"reasoning":true}} 之类也不会发出去，
 * 表现为「填了没用 / 模型不思考」。
 *
 * <p>验收条件：逐键 **追加**到请求体（{@code putAdditionalBodyProperty}），不替换整包；
 * 空 map → 原样返回（wire 零变化）；同名键以模型配置为准。
 */
class OpenAiSdkProviderExtraBodyTest {

    private static ChatCompletionCreateParams baseParams() {
        return ChatCompletionCreateParams.builder()
            .model("deepseek-flash")
            .addUserMessage("hi")
            .build();
    }

    private static ProviderConfig configWith(Map<String, Object> extraBody) {
        return new ProviderConfig("https://api.example.com", "sk-test", Map.of(), extraBody);
    }

    @Test
    @DisplayName("extraBody 逐键合并进请求体（数组/布尔/对象值原样保留）")
    void mergesExtraBodyKeys() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reasoning", true);
        extra.put("custom_tag", "v1");
        extra.put("stop", java.util.List.of("\n\n"));

        ChatCompletionCreateParams out = OpenAiSdkProvider.applyExtraBody(baseParams(), configWith(extra));

        Map<String, com.openai.core.JsonValue> body = out._additionalBodyProperties();
        assertThat(body).containsKeys("reasoning", "custom_tag", "stop");
        assertThat(body.get("reasoning").asBoolean()).contains(true);
        assertThat(body.get("custom_tag").asString()).contains("v1");
    }

    @Test
    @DisplayName("空 extraBody → 原样返回（零额外字段、同一引用）")
    void emptyExtraBody_isNoOp() {
        ChatCompletionCreateParams base = baseParams();
        ChatCompletionCreateParams out = OpenAiSdkProvider.applyExtraBody(base, configWith(Map.of()));

        assertThat(out).isSameAs(base);
        assertThat(out._additionalBodyProperties()).isEmpty();
    }

    @Test
    @DisplayName("null config → 原样返回（不 NPE）")
    void nullConfig_isNoOp() {
        ChatCompletionCreateParams base = baseParams();
        assertThat(OpenAiSdkProvider.applyExtraBody(base, null)).isSameAs(base);
    }
}
