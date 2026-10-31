package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase 1e] computer-use <b>视觉 grounding 服务</b>单测（纯单测 · <b>不发任何真实请求</b>）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：grounding 的产物要直接变成<b>点击坐标</b>。
 * 一个「大概是」的坐标 = 点在未知位置 = 误操作（可能点到「删除」按钮）。
 * ⇒ 本测试锁死一条铁律：<b>除了「解析成功且坐标合法」这一条路，其余全部 fail loud</b>。
 *
 * <p>五种失败各自必须给出<b>可区分</b>的错误文案（模型据此自纠：换描述 / 换模型 / 重试），
 * 且<b>一律不返回坐标</b>。
 */
class ComputerUseGroundingServiceTest {

    private ModelConfigResolver modelConfigResolver;
    private LlmProviderFactory llmProviderFactory;
    private LlmSideCallClient sideCallClient;
    private ComputerUseGroundingService service;

    private ProviderConfig cfg;

    /** 假截图（32x24 蓝图 · 1x1 JPEG magic 头即可 · grounding 只用 base64 + 尺寸）。 */
    private static ComputerUseCaptureResult capture() {
        return new ComputerUseCaptureResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                "image/jpeg", 32, 24, 100, 100, 0, 0, 1.0d, 100, 100);
    }

    @BeforeEach
    void setUp() {
        modelConfigResolver = mock(ModelConfigResolver.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        sideCallClient = mock(LlmSideCallClient.class);
        service = new ComputerUseGroundingService(modelConfigResolver, llmProviderFactory, sideCallClient);
        cfg = new ProviderConfig("https://example.com", "sk-test");
    }

    /** 标准成功桩：视觉模型名 + provider 可用 + 旁路调用返回指定文本。 */
    private void stubChain(String rawResponse) {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
        when(llmProviderFactory.getProvider(cfg, "anthropic")).thenReturn(mock(LlmProvider.class));
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn(rawResponse);
    }

    @Test
    @DisplayName("成功路径：结构化 JSON ⇒ FOUND + 百分比坐标")
    void successParsesPercentCoordinates() {
        stubChain("{\"found\": true, \"x\": 12.5, \"y\": 40.0, \"target\": \"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.FOUND);
        assertThat(outcome.xPercent()).isEqualTo(12.5d);
        assertThat(outcome.yPercent()).isEqualTo(40.0d);
        assertThat(outcome.target()).isEqualTo("保存按钮");
        assertThat(outcome.errorMessage()).isNull();
    }

    @Test
    @DisplayName("围栏 JSON 也能解析（模型爱加 ```json 围栏）")
    void successAcceptsFencedJson() {
        stubChain("```json\n{\"found\": true, \"x\": 50, \"y\": 50}\n```");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "目标", null);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.xPercent()).isEqualTo(50d);
        assertThat(outcome.yPercent()).isEqualTo(50d);
    }

    @Test
    @DisplayName("found=false ⇒ NOT_FOUND（明确文案 + 不返回坐标）")
    void foundFalseIsNotFound() {
        stubChain("{\"found\": false, \"x\": 0, \"y\": 0, \"target\": \"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.NOT_FOUND);
        assertThat(outcome.xPercent()).isNull();
        assertThat(outcome.yPercent()).isNull();
        assertThat(outcome.errorMessage()).contains("没有找到");
    }

    @Test
    @DisplayName("输出不是 JSON ⇒ PARSE_FAILED（明确文案 + 不返回坐标）")
    void garbageOutputIsParseFailed() {
        stubChain("我觉得那个按钮大概在左边一点");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.xPercent()).isNull();
        assertThat(outcome.errorMessage()).contains("无法解析");
    }

    @Test
    @DisplayName("缺 found 字段 ⇒ PARSE_FAILED（结构不完整 ≠ 猜一个）")
    void missingFoundFieldIsParseFailed() {
        stubChain("{\"x\": 10, \"y\": 20}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.xPercent()).isNull();
    }

    @Test
    @DisplayName("坐标越界（>100 或 <0）⇒ OUT_OF_RANGE（明确文案 + 不返回坐标）")
    void outOfRangeCoordinatesAreRejected() {
        stubChain("{\"found\": true, \"x\": 180, \"y\": 40}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.OUT_OF_RANGE);
        assertThat(outcome.xPercent()).isNull();
        assertThat(outcome.errorMessage()).contains("越界");
    }

    @Test
    @DisplayName("坐标不是数字 ⇒ PARSE_FAILED（绝不把字符串当坐标）")
    void nonNumericCoordinatesAreParseFailed() {
        stubChain("{\"found\": true, \"x\": \"left\", \"y\": 40}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.xPercent()).isNull();
    }

    @Test
    @DisplayName("视觉模型未配置 ⇒ MODEL_UNAVAILABLE（提示去配 settings.multimodal_model_name）")
    void missingVisionModelIsModelUnavailable() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn(null);

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.MODEL_UNAVAILABLE);
        assertThat(outcome.errorMessage()).contains("多模态档位模型");
    }

    @Test
    @DisplayName("provider 不可用（无 apiKey）⇒ MODEL_UNAVAILABLE")
    void unusableProviderIsModelUnavailable() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("https://example.com", "  "), "anthropic"));

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.MODEL_UNAVAILABLE);
        assertThat(outcome.errorMessage()).contains("不可用");
    }

    @Test
    @DisplayName("旁路调用抛异常 ⇒ CALL_FAILED（明确文案 + 不返回坐标）")
    void sideCallFailureIsCallFailed() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
        when(llmProviderFactory.getProvider(cfg, "anthropic")).thenReturn(mock(LlmProvider.class));
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        ComputerUseGroundingService.GroundingOutcome outcome =
                service.ground(capture(), "保存按钮", null);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.CALL_FAILED);
        assertThat(outcome.errorMessage()).contains("调用视觉模型失败");
        assertThat(outcome.xPercent()).isNull();
    }

    @Test
    @DisplayName("prompt 要求结构化 JSON + 百分比坐标（模板契约）")
    void promptDemandsStructuredPercentJson() {
        String prompt = ComputerUseGroundingService.PROMPT_TEMPLATE.formatted("保存按钮");

        assertThat(prompt).contains("\"found\": true");
        assertThat(prompt).contains("\"x\": 0, \"y\": 0");
        assertThat(prompt).contains("百分比");
        assertThat(prompt).contains("0 到 100");
        assertThat(prompt).contains("保存按钮");
    }

    @Test
    @DisplayName("五种失败的错误文案互不相同（模型要能据此自纠）")
    void failureMessagesAreDistinguishable() {
        String notFound = new ComputerUseGroundingService.GroundingOutcome(
                ComputerUseGroundingService.Status.NOT_FOUND, null, null, "T", null).errorMessage();
        String parseFailed = new ComputerUseGroundingService.GroundingOutcome(
                ComputerUseGroundingService.Status.PARSE_FAILED, null, null, "T", null).errorMessage();
        String outOfRange = new ComputerUseGroundingService.GroundingOutcome(
                ComputerUseGroundingService.Status.OUT_OF_RANGE, null, null, "T", "x=180").errorMessage();
        String unavailable = new ComputerUseGroundingService.GroundingOutcome(
                ComputerUseGroundingService.Status.MODEL_UNAVAILABLE, null, null, "T", "no key").errorMessage();
        String callFailed = new ComputerUseGroundingService.GroundingOutcome(
                ComputerUseGroundingService.Status.CALL_FAILED, null, null, "T", "boom").errorMessage();

        assertThat(List.of(notFound, parseFailed, outOfRange, unavailable, callFailed))
                .doesNotHaveDuplicates();
    }
}
