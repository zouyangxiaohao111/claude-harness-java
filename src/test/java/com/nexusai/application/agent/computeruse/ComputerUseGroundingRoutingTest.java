package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
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
 * [Phase 1e] <b>grounding 降级路径的接线</b>单测（纯单测 · <b>不发任何真实请求</b>）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：降级路径有三条互斥的分支，判错一条就会
 * 「要么该救的没救、要么不该降级的降了」：
 * <ol>
 *   <li><b>主模型确定支持视觉</b> ⇒ <b>仍走 CCB 原路径</b>（把图直接送给主模型）——
 *       grounding <b>绝不能</b>被调用（它更慢、更不准、且是二手定位）；</li>
 *   <li><b>主模型确定不支持视觉 + 降级开关开</b> ⇒ 走 grounding ⇒ 回<b>百分比坐标文本</b>；</li>
 *   <li><b>视觉判不出（undetermined）</b> ⇒ <b>保持现状：放行</b>（仍出图），
 *       grounding <b>不被调用</b>（⛔ 不把图重新藏起来）。</li>
 * </ol>
 * <p>外加：降级关闭时「确定不支持」⇒ 维持 fail loud 报错（既有行为不变）。
 *
 * <p><b>假视觉模型</b>：真 {@link ComputerUseGroundingService} + 假 provider/假旁路客户端
 * ⇒ 走的是<b>真的解析链路</b>，只是上游返回固定文本。
 */
class ComputerUseGroundingRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-grounding";

    /** 假截屏器（固定几何 · 不碰真实桌面）。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        int captureCount;

        @Override
        public ComputerUseCaptureResult capture() {
            captureCount++;
            return new ComputerUseCaptureResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
                    "image/jpeg", 1456, 819, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
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

    /** 假视觉模型（统计真实调用次数 · 断言「grounding 没被调用」）。 */
    private static final class FakeVision {
        final ProviderConfig cfg = new ProviderConfig("https://example.com", "sk-test");
        final ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        final LlmProviderFactory factory = mock(LlmProviderFactory.class);
        final LlmSideCallClient side = mock(LlmSideCallClient.class);
        int calls;

        FakeVision(String rawResponse) {
            when(resolver.resolveMultimodalModelName()).thenReturn("vision-model");
            when(resolver.resolve("vision-model"))
                    .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
            when(factory.getProvider(cfg, "anthropic")).thenReturn(mock(LlmProvider.class));
            when(side.chat(eq(cfg), eq("vision-model"), isNull(), isNull(), any(), any()))
                    .thenAnswer(inv -> {
                        calls++;
                        return rawResponse;
                    });
        }

        ComputerUseGroundingService service() {
            return new ComputerUseGroundingService(resolver, factory, side);
        }
    }

    private static ToolUseContext ctxWithModel(String modelName) {
        return ToolUseContext.of((UUID) null, SESSION)
                .withEffectiveModelName(modelName)
                .withEffectiveProviderType("openai_compatible");
    }

    private static ComputerUseVisionCapability unsupported() {
        return (modelName, providerType) -> ComputerUseVisionCapability.Verdict.unsupported(
                ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName, "类型=chat");
    }

    private static ComputerUseVisionCapability supported() {
        return (modelName, providerType) -> ComputerUseVisionCapability.Verdict.supported(
                ComputerUseVisionCapability.Verdict.SOURCE_DB_TYPE, modelName);
    }

    private static ComputerUseVisionCapability undetermined() {
        return (modelName, providerType) -> ComputerUseVisionCapability.Verdict.undetermined(
                ComputerUseVisionCapability.Verdict.SOURCE_UNDETERMINED, modelName, "未登记");
    }

    private static ObjectNode inputWithTarget(String target) {
        ObjectNode node = JSON.createObjectNode();
        if (target != null) {
            node.put("target", target);
        }
        return node;
    }

    // ─────────────────────────── 截图工具 · 三条分支 ───────────────────────────

    @Test
    @DisplayName("主模型确定支持视觉 ⇒ 仍走 CCB 原路径（出 image block），grounding 一次都不被调用")
    void supportedVisionKeepsCcbPathAndNeverGrounds() {
        FakeCapturer capturer = new FakeCapturer();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 10, \"y\": 20}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), supported(), vision.service());

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget("保存按钮")),
                ctxWithModel("vendor/gpt-4o"));

        assertThat(((ToolResult<?>) result).data())
                .isInstanceOf(ComputerUseScreenshotResult.class);
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-1", false);
        @SuppressWarnings("unchecked")
        List<ContentBlockParam> blocks = (List<ContentBlockParam>) block.content();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo("image");
        assertThat(vision.calls)
                .as("⛔ 支持视觉时不得降级（精度更高、且是主模型直接读图）")
                .isZero();
    }

    @Test
    @DisplayName("主模型确定不支持 + 降级开启 ⇒ 走 grounding ⇒ 回【百分比坐标文本】，不回图")
    void unsupportedVisionGroundsAndReturnsPercentText() {
        FakeCapturer capturer = new FakeCapturer();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 12.5, \"y\": 40, \"target\": \"保存按钮\"}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), unsupported(), vision.service());

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget("保存按钮")),
                ctxWithModel("deepseek/deepseek-chat"));

        Object data = ((ToolResult<?>) result).data();
        assertThat(data).isInstanceOf(String.class);
        String text = data.toString();
        assertThat(text).contains("保存按钮");
        assertThat(text).contains("12.5");
        assertThat(text).contains("40");
        assertThat(text).contains("百分比");
        assertThat(text).as("必须提示这是二手定位（误差预期提示）").contains("偏差");
        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("视觉判不出 ⇒ 保持现状：放行出图，grounding 不被调用（⛔ 不把图藏起来）")
    void undeterminedVisionPassesThroughWithoutGrounding() {
        FakeCapturer capturer = new FakeCapturer();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 10, \"y\": 20}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), undetermined(), vision.service());

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget(null)),
                ctxWithModel("some/unknown-model"));

        assertThat(((ToolResult<?>) result).data())
                .isInstanceOf(ComputerUseScreenshotResult.class);
        assertThat(vision.calls).isZero();
    }

    @Test
    @DisplayName("降级关闭（服务未接线）⇒ 维持 fail loud 报错（既有行为不变）")
    void groundingDisabledKeepsFailLoud() {
        FakeCapturer capturer = new FakeCapturer();
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), unsupported(), null);

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget("保存按钮")),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data().toString()).contains("不支持图片输入");
        assertThat(capturer.captureCount).isZero();
    }

    @Test
    @DisplayName("降级路径缺 target ⇒ fail loud（明确告诉模型要带 target）")
    void groundingWithoutTargetFailsLoud() {
        FakeCapturer capturer = new FakeCapturer();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 10, \"y\": 20}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), unsupported(), vision.service());

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget(null)),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data().toString()).contains("target");
        assertThat(vision.calls).isZero();
    }

    @Test
    @DisplayName("grounding 失败（found=false）⇒ fail loud 且把原因回给模型")
    void groundingFailureIsSurfacedToModel() {
        FakeCapturer capturer = new FakeCapturer();
        FakeVision vision = new FakeVision("{\"found\": false, \"x\": 0, \"y\": 0}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                capturer, new ComputerUseGeometryStore(), unsupported(), vision.service());

        var result = tool.execute(
                new ToolUseBlock("call-1", tool.name(), inputWithTarget("保存按钮")),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data().toString()).contains("没有找到");
    }

    @Test
    @DisplayName("降级路径也写几何快照（点击侧要按同一份截图换算百分比）")
    void groundingPathStoresGeometrySnapshot() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 10, \"y\": 20}");
        ComputerUseScreenshotTool tool = new ComputerUseScreenshotTool(
                new FakeCapturer(), store, unsupported(), vision.service());

        tool.execute(new ToolUseBlock("call-1", tool.name(), inputWithTarget("保存按钮")),
                ctxWithModel("deepseek/deepseek-chat"));

        ComputerUseScreenshotGeometry snapshot = store.get(SESSION);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.displayWidth()).isEqualTo(1920);
        assertThat(snapshot.displayHeight()).isEqualTo(1080);
    }

    // ─────────────────────────── 点击工具 · 降级态换算 ───────────────────────────

    @Test
    @DisplayName("降级态：点击按【百分比】换算（12.5% / 40% → 屏幕 240 / 432）")
    void clickInGroundingModeUsesNormalizedCoordinates() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 12.5, \"y\": 40}");
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(), mouse, store, ComputerUseCoordinateMode.PIXELS, null, null,
                unsupported(), vision.service());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(12.5d).add(40d);
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
        assertThat(mouse.clicks).hasSize(1);
        // 12.5% * 1920 = 240；40% * 1080 = 432
        assertThat(mouse.clicks.get(0)).containsExactly(240, 432);
    }

    @Test
    @DisplayName("降级态：点击【不再】被视觉闸拦下（坐标来源 = grounding 结果）")
    void clickInGroundingModeIsNotRejected() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 12.5, \"y\": 40}");
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(), mouse, store, ComputerUseCoordinateMode.PIXELS, null, null,
                unsupported(), vision.service());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(12.5d).add(40d);
        tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(mouse.clicks).as("降级态必须放行点击").hasSize(1);
    }

    @Test
    @DisplayName("非降级态（主模型支持视觉）⇒ 点击仍按冻结的坐标模式换算，grounding 不被调用")
    void clickOutsideGroundingModeKeepsFrozenMode() {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        RecordingMouse mouse = new RecordingMouse();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 12.5, \"y\": 40}");
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(), mouse, store, ComputerUseCoordinateMode.PIXELS, null, null,
                supported(), vision.service());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(1456d / 2).add(819d / 2);
        tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("vendor/gpt-4o"));

        assertThat(mouse.clicks).hasSize(1);
        // 像素态：728 * (1920/1456) = 960；409.5 * (1080/819) = 540
        assertThat(mouse.clicks.get(0)).containsExactly(960, 540);
        assertThat(vision.calls).isZero();
    }

    @Test
    @DisplayName("降级态但无截图快照 ⇒ 保守不放行（不猜坐标）")
    void clickInGroundingModeWithoutSnapshotFailsLoud() {
        RecordingMouse mouse = new RecordingMouse();
        FakeVision vision = new FakeVision("{\"found\": true, \"x\": 12.5, \"y\": 40}");
        ComputerUseLeftClickTool tool = new ComputerUseLeftClickTool(
                new FakeCapturer(), mouse, new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null, unsupported(), vision.service());

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(12.5d).add(40d);
        var result = tool.execute(new ToolUseBlock("call-1", tool.name(), input),
                ctxWithModel("deepseek/deepseek-chat"));

        assertThat(((ToolResult<?>) result).data().toString()).contains("截图");
        assertThat(mouse.clicks).isEmpty();
    }
}
