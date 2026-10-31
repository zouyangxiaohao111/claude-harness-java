package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⭐ 2026-10-31 · computer-use grounding <b>pixels 语义 + 三条护栏</b>单测（纯单测 · 零真实请求）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：本批把 grounding 从「百分比坐标」改成
 * <b>图像像素坐标</b>并加了三条护栏 ⇒ 每条都有<b>明确的失效形态</b>，必须被锁住：
 * <ul>
 *   <li><b>像素域解析</b>：模型回的是图上像素 ⇒ 越界判定必须落在
 *       {@code [0, 图像宽-1] × [0, 图像高-1]}（⛔ 不再是 0–100）；
 *       否则「模型把百分比当像素回填（50.2）」会被当成合法坐标 ⇒ 点在左上角；</li>
 *   <li><b>prompt 必须带图像宽高</b>：不带 ⇒ 模型无从给像素坐标（它会猜 0–100）；</li>
 *   <li><b>护栏①</b>：模型自报的目标名与请求描述不一致 ⇒ 必须 fail loud
 *       （否则「措辞含糊 ⇒ 高置信度指错相邻控件」会静默点错）；</li>
 *   <li><b>护栏③</b>：系统性偏移默认 <b>0</b>（不改变现有行为）；非 0 时必须真的生效。</li>
 * </ul>
 */
class ComputerUseGroundingPixelsTest {

    private ModelConfigResolver modelConfigResolver;
    private LlmProviderFactory llmProviderFactory;
    private LlmSideCallClient sideCallClient;
    private ProviderConfig cfg;

    @BeforeEach
    void setUp() {
        modelConfigResolver = mock(ModelConfigResolver.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        sideCallClient = mock(LlmSideCallClient.class);
        cfg = new ProviderConfig("https://example.com", "sk-test");
    }

    private ComputerUseGroundingService service() {
        return new ComputerUseGroundingService(modelConfigResolver, llmProviderFactory, sideCallClient);
    }

    private ComputerUseGroundingService service(double offsetX, double offsetY) {
        return new ComputerUseGroundingService(modelConfigResolver, llmProviderFactory, sideCallClient,
                offsetX, offsetY);
    }

    /** 桩：视觉模型名 + provider 可用 + 旁路调用返回指定文本。 */
    private void stubChain(String rawResponse) {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
        when(llmProviderFactory.getProvider(cfg, "anthropic")).thenReturn(mock(LlmProvider.class));
        when(sideCallClient.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn(rawResponse);
    }

    /** 1920 屏预缩放后的真实截图尺寸（1456×819）。 */
    private static ComputerUseCaptureResult capture() {
        return new ComputerUseCaptureResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                "image/jpeg", 1456, 819, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
    }

    // ─────────────────── ① 像素域解析 + prompt ───────────────────

    @Test
    @DisplayName("pixels 解析：x/y 就是图像像素坐标（不是百分比）")
    void parsesPixelCoordinates() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":880,\"y\":120,\"target\":\"保存按钮\"}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.xPixels()).isEqualTo(880d);
        assertThat(outcome.yPixels()).isEqualTo(120d);
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.FOUND);
    }

    @Test
    @DisplayName("越界判定用【图像像素域】：x=100 在 32 宽的图上越界（⛔ 不再是 0–100 域）")
    void outOfRangeUsesImagePixelDomain() {
        ComputerUseCaptureResult small = new ComputerUseCaptureResult(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                "image/jpeg", 32, 24, 1920, 1080, 0, 0, 1.0d, 1920, 1080);

        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":10,\"target\":\"目标\"}", "目标",
                        small.imageWidth(), small.imageHeight());

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.OUT_OF_RANGE);
        assertThat(outcome.xPixels()).isNull();
        assertThat(outcome.errorMessage()).contains("越界");
    }

    @Test
    @DisplayName("prompt 里必须写明【本次图像的宽高】（否则模型无从给像素坐标）")
    void promptCarriesImageDimensions() {
        stubChain("{\"found\":true,\"x\":10,\"y\":10,\"target\":\"目标\"}");
        service().ground(capture(), "目标", null);

        // prompt 由 service 内部构造 ⇒ 通过捕获旁路调用的入参断言
        org.mockito.ArgumentCaptor<com.nexusai.infra.llm.LlmProvider.ChatRequestOptions> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.class);
        org.mockito.Mockito.verify(sideCallClient)
                .chat(eq(cfg), eq("vision-model"), isNull(), isNull(), captor.capture(), any());
        String payload = captor.getValue().history().get(0).content();

        assertThat(payload).contains("1456");
        assertThat(payload).contains("819");
        assertThat(payload).contains("像素坐标");
    }

    @Test
    @DisplayName("prompt 的图像宽高跟着【每次截图】变（不是写死的常量）")
    void promptDimensionsFollowCapture() {
        ComputerUseCaptureResult other = new ComputerUseCaptureResult(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                "image/jpeg", 800, 600, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
        stubChain("{\"found\":true,\"x\":10,\"y\":10,\"target\":\"目标\"}");
        service().ground(other, "目标", null);

        org.mockito.ArgumentCaptor<com.nexusai.infra.llm.LlmProvider.ChatRequestOptions> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.class);
        org.mockito.Mockito.verify(sideCallClient)
                .chat(eq(cfg), eq("vision-model"), isNull(), isNull(), captor.capture(), any());
        String payload = captor.getValue().history().get(0).content();

        assertThat(payload).contains("800");
        assertThat(payload).contains("600");
    }

    // ─────────────────── ② 百分比 ↔ 像素互校 ───────────────────

    @Test
    @DisplayName("模型额外回填的百分比与像素【一致】⇒ 放行")
    void percentPixelCrossCheckPasses() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":728,\"y\":409.5,\"target\":\"保存按钮\","
                                + "\"xPercent\":50,\"yPercent\":50}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.xPixels()).isEqualTo(728d);
        assertThat(outcome.echoedPercentX()).isEqualTo(50d);
    }

    @Test
    @DisplayName("百分比与像素【矛盾】⇒ fail loud（错误里报出两个值）")
    void percentPixelCrossCheckRejectsMismatch() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":728,\"y\":409.5,\"target\":\"保存按钮\","
                                + "\"xPercent\":50,\"yPercent\":90}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status())
                .isEqualTo(ComputerUseGroundingService.Status.PERCENT_PIXEL_MISMATCH);
        assertThat(outcome.detail()).contains("yPercent=90");
        assertThat(outcome.detail()).contains("y=409.5");
        assertThat(outcome.errorMessage()).contains("矛盾");
    }

    @Test
    @DisplayName("模型没回填百分比 ⇒ 不做互校（不误伤）")
    void percentCrossCheckSkippedWhenAbsent() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":728,\"y\":409,\"target\":\"保存按钮\"}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.echoedPercentX()).isNull();
    }

    // ─────────────────── ③ 护栏① 目标一致性 ───────────────────

    @Test
    @DisplayName("护栏①：模型回填的目标与请求一致 ⇒ 放行")
    void guardrailTargetMatchPassesOnSameTarget() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"保存按钮\"}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isTrue();
    }

    @Test
    @DisplayName("护栏①：模型把「保存按钮」回填成「保存」（同一目标的简称）⇒ 放行")
    void guardrailTargetMatchAcceptsAbbreviation() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"最小化\"}",
                        "记事本右上角的最小化按钮", 1456, 819);

        assertThat(outcome.found()).isTrue();
    }

    @Test
    @DisplayName("护栏①：模型回填的是【另一个控件】⇒ fail loud（错误里带两段原文）")
    void guardrailTargetMatchRejectsDifferentControl() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"取消按钮\"}",
                        "保存按钮", 1456, 819);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.TARGET_MISMATCH);
        assertThat(outcome.detail()).contains("保存按钮");
        assertThat(outcome.detail()).contains("取消按钮");
        assertThat(outcome.errorMessage()).contains("不一致");
    }

    @Test
    @DisplayName("护栏①：模型回填得【比请求更长】（匹配到了更大的元素）⇒ fail loud")
    void guardrailTargetMatchRejectsLongerEcho() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"确定取消\"}",
                        "确定", 1456, 819);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.TARGET_MISMATCH);
    }

    @Test
    @DisplayName("护栏①：模型没回填目标名 ⇒ 无从交叉验证 ⇒ fail loud（不放行）")
    void guardrailTargetMatchRejectsMissingEcho() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100}", "保存按钮", 1456, 819);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.PARSE_FAILED);
        assertThat(outcome.detail()).contains("target");
    }

    @Test
    @DisplayName("护栏①：found=false 时不要求 target 字段（如实说「没找到」即可）")
    void notFoundDoesNotRequireTargetField() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":false,\"x\":0,\"y\":0}", "保存按钮", 1456, 819);

        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("护栏①：模型用【中文】回填同一个控件（请求是英文）⇒ 放行（跨语言同义）")
    void guardrailAcceptsCrossLanguageEchoOfSameControl() {
        // PoC 真机实测的失效形态：请求英文、模型回填中文 ⇒ 字符相似度恒 0 ⇒ 误判失败
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"关闭\"}",
                        "the X button (close) of the Notepad window", 1456, 819);

        assertThat(outcome.found()).isTrue();
    }

    @Test
    @DisplayName("护栏①：模型回填【另一个控件】（中文回填也要拦下）")
    void guardrailRejectsCrossLanguageEchoOfDifferentControl() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"最小化按钮\"}",
                        "the X button (close) of the Notepad window", 1456, 819);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.TARGET_MISMATCH);
    }

    @Test
    @DisplayName("护栏①：同义词展开不能误伤子串（\"maximize\" 里的 x 不等于「关闭」）")
    void synonymExpansionDoesNotCorruptOtherWords() {
        assertThat(ComputerUseGroundingTargetMatch.expandSynonyms("maximize"))
                .isEqualTo("maximize");
        assertThat(ComputerUseGroundingTargetMatch.expandSynonyms("the x button"))
                .isEqualTo("the close button");
        assertThat(ComputerUseGroundingTargetMatch.expandSynonyms("关闭"))
                .isEqualTo("close");
    }

    // ─────────────────── ④ 护栏③ 系统性偏移 ───────────────────

    @Test
    @DisplayName("护栏③：默认偏移 0 ⇒ 坐标与模型给的完全一致（行为不变）")
    void guardrailOffsetDefaultsToZero() {
        stubChain("{\"found\":true,\"x\":728,\"y\":400,\"target\":\"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service().ground(capture(), "保存按钮", null);

        assertThat(outcome.xPixels()).isEqualTo(728d);
        assertThat(outcome.yPixels()).isEqualTo(400d);
    }

    @Test
    @DisplayName("护栏③：配置非 0 ⇒ 坐标被平移（按截图缩放折算成图像像素）")
    void guardrailOffsetAppliesWhenConfigured() {
        stubChain("{\"found\":true,\"x\":728,\"y\":400,\"target\":\"保存按钮\"}");

        ComputerUseGroundingService.GroundingOutcome outcome =
                service(10.0d, 5.0d).ground(capture(), "保存按钮", null);

        // scale = displayWidth / imageWidth = 1920 / 1456 ⇒ 10 屏幕像素 = 7.58 图像像素
        assertThat(outcome.xPixels()).isCloseTo(728 + 10.0d / (1920.0d / 1456.0d),
                within(0.01d));
        assertThat(outcome.yPixels()).isCloseTo(400 + 5.0d / (1080.0d / 819.0d),
                within(0.01d));
        assertThat(outcome.detail()).contains("偏移补偿");
    }
}
