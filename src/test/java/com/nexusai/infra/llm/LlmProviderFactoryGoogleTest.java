package com.nexusai.infra.llm;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Factory 的 Google 路由：必须精确命中真实 provider bean，⛔ 不退 OpenAI/mock。
 * 用 Mockito 替身注入（不启 Spring）。
 */
class LlmProviderFactoryGoogleTest {

    private LlmProviderFactory factory;
    private LlmProvider google;

    @BeforeEach
    void setUp() throws Exception {
        factory = new LlmProviderFactory();
        google = mock(LlmProvider.class);
        inject("mockLlmProvider", mock(LlmProvider.class));
        inject("openAiSdkProvider", mock(LlmProvider.class));
        inject("googleGeminiProvider", google);
    }

    @Test
    @DisplayName("gemini_code_assist + 可用凭据 → 精确返回 Google bean")
    void routesToGoogle() {
        ProviderConfig cfg = ProviderConfigAssembler.forGoogleOAuth("tok", "proj", null, null);
        assertThat(factory.getProvider(cfg, "gemini_code_assist")).isSameAs(google);
        assertThat(factory.getProvider(cfg, "GEMINI_CODE_ASSIST")).isSameAs(google);
    }

    @Test
    @DisplayName("Google bean 缺失 → fail-loud（不回落 OpenAI/mock）")
    void missingBeanFailsLoud() throws Exception {
        inject("googleGeminiProvider", null);
        ProviderConfig cfg = ProviderConfigAssembler.forGoogleOAuth("tok", "proj", null, null);
        assertThatThrownBy(() -> factory.getProvider(cfg, "gemini_code_assist"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未装配");
    }

    @Test
    @DisplayName("Google 凭据不可用 → fail-loud（不静默 mock）")
    void unusableGoogleConfigFailsLoud() {
        assertThatThrownBy(() -> factory.getProvider(ProviderConfig.empty(), "gemini_code_assist"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("凭据不可用");
    }

    @Test
    @DisplayName("非 Google 路径保持既有语义：空配置 → mock")
    void nonGoogleStillFallsBackToMock() {
        assertThat(factory.getProvider(ProviderConfig.empty(), "openai_compatible"))
            .isSameAs(getField("mockLlmProvider"));
    }

    private void inject(String name, Object value) throws Exception {
        Field f = LlmProviderFactory.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(factory, value);
    }

    private Object getField(String name) {
        try {
            Field f = LlmProviderFactory.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(factory);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
