package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [2026-11-01] computer-use <b>视觉 grounding 的输出预算 + 重试</b>单测（纯单测 · <b>不发任何真实请求</b>）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：grounding 的产物要直接变成<b>点击坐标</b>。
 * 而实测（2026-10-31，真实 API）表明：视觉模型可能是 <b>thinking 模型</b>
 * （{@code deepseek-flash}），不给 {@code max_tokens} 上限时思考链吃光预算 ⇒
 * 响应 {@code stop_reason=max_tokens, output_tokens=1200, len=0} ⇒ <b>正文为空</b>（1/5 发生）。
 * <br>⇒ 本测试锁死三条铁律：
 * <ol>
 *   <li><b>必须显式给 max_tokens</b>（不给 = 走模型/DB 缺省 = 可能空输出）；</li>
 *   <li><b>必须给足</b>（≥ 4096 量级，让 thinking 有余量）<b>且不得超过模型自己的上限</b>；</li>
 *   <li><b>空输出 / 解析失败 ⇒ 重试一次；再失败 ⇒ fail loud</b>（⛔ 绝不返回猜测坐标）。</li>
 * </ol>
 */
class ComputerUseGroundingBudgetTest {

    private ModelConfigResolver modelConfigResolver;
    private LlmProviderFactory llmProviderFactory;
    private LlmSideCallClient sideCallClient;
    private ComputerUseGroundingService service;

    private ProviderConfig cfg;

    /** 假截图（1456×819 · 1x1 JPEG magic 头即可 · grounding 只用 base64 + 尺寸）。 */
    private static ComputerUseCaptureResult capture() {
        return new ComputerUseCaptureResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                "image/jpeg", 1456, 819, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
    }

    @BeforeEach
    void setUp() {
        modelConfigResolver = mock(ModelConfigResolver.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        sideCallClient = mock(LlmSideCallClient.class);
        service = new ComputerUseGroundingService(modelConfigResolver, llmProviderFactory, sideCallClient);
        cfg = new ProviderConfig("https://example.com", "sk-test");
    }

    /** 标准成功桩：视觉模型名 + provider 可用。 */
    private void stubChain() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "openai_compatible"));
        when(llmProviderFactory.getProvider(cfg, "openai_compatible")).thenReturn(mock(LlmProvider.class));
    }

    /** 抓取旁路调用里传下去的 {@code ChatRequestOptions}（逐次）。 */
    private List<LlmProvider.ChatRequestOptions> capturedOptions() {
        ArgumentCaptor<LlmProvider.ChatRequestOptions> captor =
                ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(sideCallClient, org.mockito.Mockito.atLeastOnce())
                .chat(eq(cfg), eq("vision-model"), isNull(), isNull(), captor.capture(), any());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("⭐ 必须显式给 max_tokens（默认 4096）——不给 = 走缺省 = thinking 可能吃光预算")
    void passesExplicitMaxTokens() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("{\"found\": true, \"x\": 880, \"y\": 120, \"target\": \"保存按钮\"}");

        service.ground(capture(), "保存按钮", null);

        List<LlmProvider.ChatRequestOptions> options = capturedOptions();
        assertThat(options).hasSize(1);
        assertThat(options.get(0).maxTokens())
                .as("⛔ 不得为 null（null = 不发送 max_tokens = 走模型缺省 = 实测会空输出）")
                .isNotNull()
                .isEqualTo(ComputerUseGroundingService.DEFAULT_MAX_TOKENS)
                .isGreaterThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("⭐ max_tokens 不得超过模型自己的上限（DB models.max_tokens）")
    void clampsMaxTokensToModelLimit() {
        stubChain();
        when(modelConfigResolver.resolveMaxTokens("vision-model")).thenReturn(2048);
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("{\"found\": true, \"x\": 880, \"y\": 120, \"target\": \"保存按钮\"}");

        service.ground(capture(), "保存按钮", null);

        assertThat(capturedOptions().get(0).maxTokens())
                .as("模型上限 2048 ⇒ 只能给 2048（不能给 4096）")
                .isEqualTo(2048);
    }

    @Test
    @DisplayName("⭐ 空响应 ⇒ 重试一次（放宽 max_tokens）⇒ 成功")
    void emptyResponseRetriesThenSucceeds() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("")
                .thenReturn("{\"found\": true, \"x\": 880, \"y\": 120, \"target\": \"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.xPixels()).isEqualTo(880d);
        List<LlmProvider.ChatRequestOptions> options = capturedOptions();
        assertThat(options).as("空输出必须重试一次").hasSize(2);
        assertThat(options.get(0).maxTokens()).isEqualTo(ComputerUseGroundingService.DEFAULT_MAX_TOKENS);
        assertThat(options.get(1).maxTokens())
                .as("重试必须放宽预算（给 thinking 更多余量）")
                .isEqualTo(ComputerUseGroundingService.RETRY_MAX_TOKENS)
                .isGreaterThan(options.get(0).maxTokens());
    }

    @Test
    @DisplayName("⭐ 连续两次都空 ⇒ fail loud（⛔ 绝不返回猜测坐标）")
    void twoEmptyResponsesFailLoud() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.xPixels()).isNull();
        assertThat(outcome.yPixels()).isNull();
        assertThat(outcome.errorMessage()).contains("重试");
        verify(sideCallClient, times(2)).chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any());
    }

    @Test
    @DisplayName("⭐ 解析失败（散文/半截 JSON）⇒ 重试一次；再失败 ⇒ fail loud")
    void parseFailureRetriesThenFailsLoud() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("我觉得那个按钮大概在左边一点");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.xPixels()).isNull();
        assertThat(outcome.errorMessage()).contains("无法解析");
        assertThat(capturedOptions()).as("解析失败也必须重试一次").hasSize(2);
    }

    @Test
    @DisplayName("⭐ grounding 专用链路关闭 thinking（grounding 只要短 JSON，不需要推理链）")
    void disablesThinkingOnGroundingPath() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("{\"found\": true, \"x\": 880, \"y\": 120, \"target\": \"保存按钮\"}");

        service.ground(capture(), "保存按钮", null);

        LlmProvider.ChatRequestOptions.ThinkingConfig thinking = capturedOptions().get(0).thinkingConfig();
        assertThat(thinking).as("⛔ 不得为 null（null = 不关闭 thinking = 预算被吃光的风险）").isNotNull();
        assertThat(thinking.type()).isEqualTo("disabled");
        assertThat(thinking.budgetTokens()).isNull();
    }

    @Test
    @DisplayName("模型明确说「没找到」⇒ 不重试（那是答案，不是故障）")
    void notFoundIsNotRetried() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("{\"found\": false, \"x\": 0, \"y\": 0, \"target\": \"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.NOT_FOUND);
        assertThat(outcome.xPixels()).isNull();
        verify(sideCallClient, times(1)).chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any());
    }

    @Test
    @DisplayName("旁路调用抛异常 ⇒ CALL_FAILED 且不重试（避免放大限流压力）")
    void callFailureIsNotRetried() {
        stubChain();
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenThrow(new RuntimeException("429 rate limited"));

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.CALL_FAILED);
        assertThat(outcome.xPixels()).isNull();
        verify(sideCallClient, times(1)).chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any());
    }
}
