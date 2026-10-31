package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase 1b · A] computer-use <b>视觉能力闸</b>单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：CU 截图以 {@code role=user} + {@code image_url}
 * 送达 ⇒ <b>不支持视觉的模型端点（deepseek-chat 等）会 400</b>（改前是静默丢图）。
 * 本闸存在的意义 = <b>在图发出去之前</b>把这条失败变成一条模型能看懂的明确错误。
 * 三条语义必须锁死：
 * <ol>
 *   <li><b>已确定不支持</b> ⇒ CU 工具<b>返回明确错误</b>且<b>不截屏</b>（截屏器不被调用 ⇒ 绝不发图）；</li>
 *   <li><b>已确定支持</b> ⇒ 正常产出 image block（闸不能误伤正常路径）；</li>
 *   <li><b>判不出</b> ⇒ <b>放行</b>（⛔ 不得保守拒绝 —— 那等于把图重新藏起来，
 *       正是本批要修的缺陷本身）。</li>
 * </ol>
 *
 * <p>另外锁「比 {@code ModelCapabilityResolver.supportsImage} 更准」的两处：
 * Claude 家族（anthropic provider）即使 DB {@code type=chat} 也判支持；
 * 模型未登记 ⇒ 判不出（而不是 false）。
 */
class ComputerUseVisionCapabilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-vision";

    // ─────────────────────────── 闸本体 ───────────────────────────

    @Test
    @DisplayName("DB models.type=multimodal ⇒ 支持（正向命中，与 supportsImage 同向）")
    void dbTypeMultimodalIsSupported() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        stubModel(modelMapper, providerMapper, "multimodal", "openai_compatible");

        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.db(modelMapper, providerMapper)
                        .resolve("vendor/gpt-4o", "openai_compatible");

        assertThat(verdict.supported()).isTrue();
        assertThat(verdict.determinate()).isTrue();
        assertThat(verdict.source()).isEqualTo(ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE);
    }

    @Test
    @DisplayName("DB models.type=chat + openai 兼容 provider ⇒ 不支持（deepseek-chat 场景 ⇒ fail loud）")
    void dbTypeChatOnOpenAiProviderIsUnsupported() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        stubModel(modelMapper, providerMapper, "chat", "openai_compatible");

        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.db(modelMapper, providerMapper)
                        .resolve("deepseek/deepseek-chat", "openai_compatible");

        assertThat(verdict.supported()).isFalse();
        assertThat(verdict.determinate()).isTrue();
        assertThat(verdict.rejectionMessage()).contains("不支持图片输入");
    }

    @Test
    @DisplayName("anthropic provider 家族 ⇒ 支持（即使 DB type=chat —— 比 supportsImage 更准）")
    void anthropicFamilyIsSupportedEvenWhenDbTypeIsChat() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        stubModel(modelMapper, providerMapper, "chat", "anthropic");

        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.db(modelMapper, providerMapper)
                        .resolve("anthropic/claude-sonnet-4-6", "anthropic");

        assertThat(verdict.supported())
                .as("Claude 家族原生支持图片；DB type 未标多模态不应把 CU 整个禁掉")
                .isTrue();
        assertThat(verdict.source())
                .isEqualTo(ComputerUseVisionCapability.Verdict.SOURCE_PROVIDER_FAMILY);
    }

    @Test
    @DisplayName("模型未登记 ⇒ 判不出（不是 false）⇒ 消费方放行（不把图重新藏起来）")
    void unregisteredModelIsUndeterminedNotFalse() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(modelMapper.selectOneByQuery(any())).thenReturn(null);
        when(providerMapper.selectOneByQuery(any())).thenReturn(null);

        ComputerUseVisionCapability.Verdict verdict =
                ComputerUseVisionCapability.db(modelMapper, providerMapper)
                        .resolve("some/unknown-model", "openai_compatible");

        assertThat(verdict.supported()).isFalse();
        assertThat(verdict.determinate())
                .as("判不出 ≠ 不支持；消费方据此放行")
                .isFalse();
    }

    // ─────────────────────────── 工具级（闸的消费点）───────────────────────────

    @Test
    @DisplayName("工具级：模型不支持视觉 ⇒ 截图工具返回明确错误且【不截屏】（绝不发图）")
    void screenshotFailsLoudWithoutCapturingWhenVisionUnsupported() {
        CountingCapturer capturer = new CountingCapturer();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(),
                (modelName, providerType) -> ComputerUseVisionCapability.Verdict.unsupported(
                        ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName, "类型=chat"));

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(result).isInstanceOf(ToolResult.class);
        assertThat(((ToolResult<?>) result).data())
                .as("不支持视觉 ⇒ fail loud（错误结果：data 是错误文案，不是截图载荷）")
                .isInstanceOf(String.class);
        assertThat(((ToolResult<?>) result).data().toString())
                .contains("不支持图片输入");
        assertThat(capturer.captureCount)
                .as("⛔ 截屏器一次都不能被调用（不发图 ⇒ 端点不会 400）")
                .isZero();
    }

    @Test
    @DisplayName("工具级：模型支持视觉 ⇒ 正常产出 image block（闸不误伤正常路径）")
    void screenshotProducesImageBlockWhenVisionSupported() throws Exception {
        CountingCapturer capturer = new CountingCapturer();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(),
                (modelName, providerType) -> ComputerUseVisionCapability.Verdict.supported(
                        ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName));

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()),
                ctxWithModel("vendor/gpt-4o"));

        assertThat(((ToolResult<?>) result).data())
                .as("支持视觉 ⇒ 正常产出截图载荷")
                .isInstanceOf(ComputerUseScreenshotResult.class);
        assertThat(capturer.captureCount).isEqualTo(1);

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        assertThat(block).isNotNull();
        @SuppressWarnings("unchecked")
        List<ContentBlockParam> blocks = (List<ContentBlockParam>) block.content();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo("image");
    }

    @Test
    @DisplayName("工具级：视觉能力判不出 ⇒ 放行（仍产出 image block，不把图藏起来）")
    void screenshotPassesWhenVisionUndetermined() throws Exception {
        CountingCapturer capturer = new CountingCapturer();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(),
                (modelName, providerType) -> ComputerUseVisionCapability.Verdict.undetermined(
                        ComputerUseVisionCapability.Verdict.SOURCE_UNDETERMINED, modelName, "未登记"));

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()),
                ctxWithModel("some/unknown-model"));

        assertThat(((ToolResult<?>) result).data())
                .as("判不出 ⇒ 放行（⛔ 不得保守拒绝 = 把图重新藏起来）")
                .isInstanceOf(ComputerUseScreenshotResult.class);
        assertThat(capturer.captureCount).isEqualTo(1);
    }

    @Test
    @DisplayName("工具级：点击工具同样受闸约束（看不到图 ⇒ 坐标无来源 ⇒ 明确报错，不做盲点击）")
    void leftClickFailsLoudWhenVisionUnsupported() {
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new CountingCapturer(), new NoopMouse(), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null,
                (modelName, providerType) -> ComputerUseVisionCapability.Verdict.unsupported(
                        ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName, "类型=chat"));

        var call = new ToolUseBlock("call-1", tool.name(), JSON.createObjectNode()
                .putArray("coordinate").add(10).add(20));
        var result = tool.execute(call, ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data())
                .as("看不到图 ⇒ 明确报错（不做盲点击）")
                .isInstanceOf(String.class);
        assertThat(((ToolResult<?>) result).data().toString()).contains("不支持图片输入");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** stub：模型可解析 + 指定 type + 指定 provider 类型。 */
    private static void stubModel(ModelMapper modelMapper, ProviderMapper providerMapper,
                                  String type, String providerType) {
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType(providerType);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        when(providerMapper.selectOneById(any())).thenReturn(provider);
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName("m");
        model.setType(type);
        model.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
    }

    /** 带模型名 + provider 类型的工具上下文。 */
    private static ToolUseContext ctxWithModel(String modelName) {
        return ToolUseContext.of((UUID) null, SESSION)
                .withEffectiveModelName(modelName)
                .withEffectiveProviderType("openai_compatible");
    }

    /** 计数截屏器（用来断言「闸拦截时一次都不截屏」）。 */
    private static final class CountingCapturer implements ComputerUseScreenCapturer {
        int captureCount;

        @Override
        public ComputerUseCaptureResult capture() {
            captureCount++;
            return new ComputerUseCaptureResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                    "image/jpeg", 10, 10, 100, 100, 0, 0, 1.0d, 100, 100);
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    /** 空实现鼠标（闸拦截时不应被调用）。 */
    private static final class NoopMouse implements ComputerUseMouse {
        @Override
        public void moveAndSettle(int x, int y) {
            throw new AssertionError("闸拦截后不应移动鼠标");
        }

        @Override
        public void leftClick(int x, int y) {
            throw new AssertionError("闸拦截后不应点击");
        }
    }
}
