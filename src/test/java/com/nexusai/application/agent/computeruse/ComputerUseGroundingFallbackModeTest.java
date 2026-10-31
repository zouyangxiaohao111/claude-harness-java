package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⭐ 2026-10-31 · <b>降级路径不再改坐标模式</b> + <b>护栏②（点击前落点校验）</b>单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>坐标模式同源不变量</b>：CCB {@code tools.ts:5-10} 要求「工具描述里给模型的坐标约定」
 *       与「执行期换算用的约定」<b>必须是同一个</b>。降级路径现在回的是<b>图像像素</b>
 *       ⇒ 若装配层还把模式切成 {@code normalized_0_100}，模型就会拿着像素坐标被按百分比换算
 *       ⇒ 点在别处 ⇒ <b>必须锁死「降级不改模式」</b>。</li>
 *   <li><b>护栏②</b>：区域变了 ⇒ <b>一次都不能点</b>；没变 ⇒ 必须放行（否则工具不可用）。</li>
 * </ol>
 */
class ComputerUseGroundingFallbackModeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-fallback-mode";

    /** 纯色图。 */
    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** 假截屏器：区域抓取返回固定内容（整屏不实现）。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        private final BufferedImage regionImage;

        FakeCapturer(BufferedImage regionImage) {
            this.regionImage = regionImage;
        }

        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("护栏② 单测不需要整屏截图");
        }

        @Override
        public ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) {
            byte[] jpeg;
            try {
                jpeg = ComputerUseImageCodec.encodeJpeg(regionImage);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ComputerUseCaptureResult(jpeg, "image/jpeg",
                    regionImage.getWidth(), regionImage.getHeight(),
                    regionImage.getWidth(), regionImage.getHeight(),
                    x, y, 1.0d, regionImage.getWidth(), regionImage.getHeight());
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    /** 记录点击坐标的假鼠标。 */
    private static final class RecordingMouse implements ComputerUseMouse {
        final List<int[]> clicks = new ArrayList<>();

        @Override
        public void moveAndSettle(int x, int y) {
            // noop
        }

        @Override
        public void leftClick(int x, int y) {
            clicks.add(new int[]{x, y});
        }
    }

    private static ComputerUseVisionCapability unsupported() {
        return (modelName, providerType) -> ComputerUseVisionCapability.Verdict.unsupported(
                ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName, "类型=chat");
    }

    private static ToolUseContext ctxWithModel(String modelName) {
        return ToolUseContext.of((UUID) null, SESSION)
                .withEffectiveModelName(modelName)
                .withEffectiveProviderType("openai_compatible");
    }

    /** 一个「已装配」的 grounding 服务（降级态只判它非空，不会真的调用）。 */
    private static ComputerUseGroundingService groundingService() {
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmSideCallClient side = mock(LlmSideCallClient.class);
        ProviderConfig cfg = new ProviderConfig("https://example.com", "sk-test");
        when(resolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(resolver.resolve("vision-model"))
                .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
        when(factory.getProvider(cfg, "anthropic")).thenReturn(mock(LlmProvider.class));
        when(side.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                .thenReturn("{\"found\":true,\"x\":10,\"y\":10,\"target\":\"目标\"}");
        return new ComputerUseGroundingService(resolver, factory, side);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ─────────────────── ① 降级不改坐标模式 ───────────────────

    @Test
    @DisplayName("降级开关打开时，坐标模式【不被覆盖】（仍是冻结的 pixels）")
    void groundingFallbackDoesNotOverrideCoordinateMode() throws Exception {
        ComputerUseToolConfig config = new ComputerUseToolConfig();
        ComputerUseGates gates = new ComputerUseGates();
        setField(gates, "coordinateMode", "pixels");
        setField(gates, "groundingFallbackEnabled", true);
        setField(config, "gates", gates);

        assertThat(config.resolveCoordinateMode())
                .as("⭐ 降级路径已改为 pixels 语义 ⇒ 装配层绝不能再把模式切成 normalized_0_100")
                .isEqualTo(ComputerUseCoordinateMode.PIXELS);
    }

    @Test
    @DisplayName("降态点击：坐标按【像素】换算（728,409.5 → 屏幕 960,540）")
    void clickInFallbackModeUsesPixelCoordinates() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();

        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(solid(79, 79, Color.WHITE)), mouse, store,
                ComputerUseCoordinateMode.PIXELS, null, null,
                unsupported(), groundingService(), null);

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(728d).add(409.5d);
        tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(mouse.clicks).hasSize(1);
        // pixels 态：728 × (1920/1456) = 960；409.5 × (1080/819) = 540
        assertThat(mouse.clicks.get(0)).containsExactly(960, 540);
    }

    @Test
    @DisplayName("降态点击工具的坐标描述是【pixels 文案】（模型不会看到百分比描述）")
    void fallbackClickToolDescribesPixelConvention() {
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(solid(79, 79, Color.WHITE)), new RecordingMouse(),
                new ComputerUseGeometryStore(), ComputerUseCoordinateMode.PIXELS,
                null, null, unsupported(), groundingService(), null);

        String schema = tool.inputSchema().toString();

        assertThat(schema).contains("read directly from the most recent screenshot image");
        assertThat(schema).as("⛔ 绝不能让模型看到百分比描述却收到像素坐标")
                .doesNotContain("percentage of screen width");
    }

    // ─────────────────── ② 护栏② 点击前落点校验 ───────────────────

    private static ComputerUseLeftClickTool clickTool(ComputerUseScreenCapturer capturer,
                                                      RecordingMouse mouse,
                                                      ComputerUseGeometryStore store,
                                                      ComputerUseGroundingStore groundingStore) {
        return new ComputerUseLeftClickTool(capturer, mouse, store,
                ComputerUseCoordinateMode.PIXELS, null, null,
                unsupported(), groundingService(), null,
                groundingStore, ComputerUseRegionVerifyConfig.defaults());
    }

    private static ComputerUseGroundingStore storeWithWhiteFrame() {
        ComputerUseGroundingStore groundingStore = new ComputerUseGroundingStore();
        try {
            byte[] jpeg = ComputerUseImageCodec.encodeJpeg(solid(1456, 819, Color.WHITE));
            ComputerUseCaptureResult capture = new ComputerUseCaptureResult(
                    jpeg, "image/jpeg", 1456, 819, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
            groundingStore.put(SESSION, new ComputerUseGroundingFrame(capture, 730d, 410d, "保存按钮"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return groundingStore;
    }

    @Test
    @DisplayName("护栏②：目标区域已变化 ⇒ 拒绝点击（鼠标一次都不动）+ 明确错误")
    void changedRegionBlocksClick() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = clickTool(new FakeCapturer(solid(79, 79, Color.BLACK)),
                mouse, store, storeWithWhiteFrame());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(730d).add(410d);
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(mouse.clicks).as("⛔ 区域变了 ⇒ 绝不点击").isEmpty();
        assertThat(((ToolResult<?>) result).data().toString()).contains("目标区域已变化");
    }

    @Test
    @DisplayName("护栏②：区域没变 ⇒ 放行（点击照常发生）")
    void unchangedRegionAllowsClick() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = clickTool(new FakeCapturer(solid(79, 79, Color.WHITE)),
                mouse, store, storeWithWhiteFrame());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(730d).add(410d);
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(mouse.clicks).as("区域没变 ⇒ 必须放行（否则工具不可用）").hasSize(1);
        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
    }

    @Test
    @DisplayName("护栏② 开关关闭 ⇒ 不做校验（无 GUI 环境可跑）")
    void regionVerifyCanBeDisabled() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(solid(79, 79, Color.BLACK)), mouse, store,
                ComputerUseCoordinateMode.PIXELS, null, null,
                unsupported(), groundingService(), null,
                storeWithWhiteFrame(), ComputerUseRegionVerifyConfig.disabled());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(730d).add(410d);
        tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(mouse.clicks).as("开关关闭 ⇒ 跳过校验").hasSize(1);
    }
}
