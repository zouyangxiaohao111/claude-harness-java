package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两态坐标（{@code pixels} / {@code normalized_0_100}）<b>真正生效</b>的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9）</b>：CCB {@code tools.ts:5-10} 的文件头注释写死了这条不变量：
 * <blockquote>
 * Coordinate descriptions are baked in at tool-list build time from the {@code chicago_coordinate_mode}
 * gate. The model sees exactly ONE coordinate convention in the param descriptions and never learns the
 * other exists. The host reads the same frozen gate value for {@code scaleCoord} — both must agree or
 * clicks land in the wrong space.
 * </blockquote>
 *
 * <p>⇒ 三件事必须同时成立，缺一即错：
 * <ol>
 *   <li><b>描述只写当前态</b>（模型绝不能同时看到两套约定 ⇒ 它会混用）；</li>
 *   <li><b>换算按当前态</b>（描述说百分比，就必须真的按百分比换算）；</li>
 *   <li><b>模式被冻结</b>（会话中途翻转 ⇒ "告诉模型 pixels 却按 normalized 换算" ⇒ 点击错位）。</li>
 * </ol>
 */
class ComputerUseCoordinateModeSwitchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CCB {@code tools.ts:22} 的像素态 x 文案（逐字）。 */
    private static final String CCB_PIXELS_X =
            "(x, y): Horizontal pixel position read directly from the most recent screenshot image, "
                    + "measured from the left edge. The server handles all scaling.";

    /** CCB {@code tools.ts:26} 的百分比态 x 文案（逐字，含 en dash）。 */
    private static final String CCB_NORMALIZED_X =
            "(x, y): Horizontal position as a percentage of screen width, 0.0–100.0 "
                    + "(0 = left edge, 100 = right edge).";

    /** 假截屏器（固定 1920×1080 显示器）。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("坐标模式测试不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    /** 记录点击坐标的假鼠标。 */
    private static final class RecordingMouse implements ComputerUseMouse {
        int[] lastClick;

        @Override
        public void moveAndSettle(int x, int y) {
            // noop
        }

        @Override
        public void leftClick(int x, int y) {
            lastClick = new int[]{x, y};
        }
    }

    private static ObjectNode coordinateInput(double x, double y) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    /**
     * 构造工具 + <b>预置一份截图时刻几何快照</b>。
     *
     * <p>[Phase 1d] 换算<b>必须</b>有截图快照（冷启动 ⇒ fail loud）⇒ 本测试里的每次点击
     * 都要先有快照。快照取「图像 1920x1080 = 显示 1920x1080」（比例 = 1）⇒ 像素态恒等，
     * 百分比态 = 50% ⇒ 屏幕中点（960, 540）。
     */
    private static ComputerUseLeftClickTool tool(ComputerUseCoordinateMode mode, RecordingMouse mouse) {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(null, new ComputerUseScreenshotGeometry(1920, 1080, 1920, 1080, 0, 0));
        return new ComputerUseLeftClickTool(new FakeCapturer(), mouse, store, mode);
    }

    @Test
    @DisplayName("像素态：coordinate 描述逐字等于 CCB tools.ts:22")
    void pixelsModeUsesCcbText() {
        ComputerUseLeftClickTool tool = tool(ComputerUseCoordinateMode.PIXELS, new RecordingMouse());

        String description = tool.inputSchema().path("properties").path("coordinate")
                .path("description").asText();

        assertThat(description).isEqualTo(CCB_PIXELS_X);
    }

    @Test
    @DisplayName("百分比态：coordinate 描述逐字等于 CCB tools.ts:26")
    void normalizedModeUsesCcbText() {
        ComputerUseLeftClickTool tool = tool(ComputerUseCoordinateMode.NORMALIZED_0_100, new RecordingMouse());

        String description = tool.inputSchema().path("properties").path("coordinate")
                .path("description").asText();

        assertThat(description).isEqualTo(CCB_NORMALIZED_X);
    }

    @Test
    @DisplayName("两态文案不同，且【当前态的描述里绝不出现另一套约定】")
    void onlyCurrentConventionIsExposed() {
        String pixels = tool(ComputerUseCoordinateMode.PIXELS, new RecordingMouse())
                .inputSchema().path("properties").path("coordinate").path("description").asText();
        String normalized = tool(ComputerUseCoordinateMode.NORMALIZED_0_100, new RecordingMouse())
                .inputSchema().path("properties").path("coordinate").path("description").asText();

        assertThat(pixels).isNotEqualTo(normalized);
        assertThat(pixels)
                .as("像素态描述里不能出现百分比约定（模型会混用）")
                .doesNotContain("percentage of screen width")
                .doesNotContain("0.0–100.0");
        assertThat(normalized)
                .as("百分比态描述里不能出现像素约定（模型会混用）")
                .doesNotContain("pixel position read directly")
                .doesNotContain("screenshot image");
    }

    @Test
    @DisplayName("换算真的按模式走：百分比态 (50,50) ⇒ 屏幕 (960,540)；像素态 (50,50) ⇒ 屏幕 (50,50)")
    void scalingActuallyFollowsTheMode() {
        RecordingMouse pixelsMouse = new RecordingMouse();
        tool(ComputerUseCoordinateMode.PIXELS, pixelsMouse).execute(
                new ToolUseBlock("c1", "mcp__computer-use__left_click", coordinateInput(50, 50)));
        assertThat(pixelsMouse.lastClick).containsExactly(50, 50);

        RecordingMouse normalizedMouse = new RecordingMouse();
        tool(ComputerUseCoordinateMode.NORMALIZED_0_100, normalizedMouse).execute(
                new ToolUseBlock("c2", "mcp__computer-use__left_click", coordinateInput(50, 50)));
        assertThat(normalizedMouse.lastClick).containsExactly(960, 540);
    }

    @Test
    @DisplayName("坐标模式首次读取即冻结（CCB gates.ts:66-72）：中途改配置不影响已冻结的值")
    void coordinateModeIsFrozenOnFirstRead() throws Exception {
        ComputerUseGates gates = new ComputerUseGates();
        Field field = ComputerUseGates.class.getDeclaredField("coordinateMode");
        field.setAccessible(true);
        field.set(gates, "normalized_0_100");

        assertThat(gates.coordinateMode())
                .as("首次读取应拿到配置值")
                .isEqualTo(ComputerUseCoordinateMode.NORMALIZED_0_100);

        field.set(gates, "pixels");
        assertThat(gates.coordinateMode())
                .as("冻结后不得再变（否则描述与换算会脱节）")
                .isEqualTo(ComputerUseCoordinateMode.NORMALIZED_0_100);
    }

    @Test
    @DisplayName("非法坐标模式回落 pixels（对齐 ComputerUseGates 既有语义）")
    void illegalCoordinateModeFallsBackToPixels() throws Exception {
        ComputerUseGates gates = new ComputerUseGates();
        Field field = ComputerUseGates.class.getDeclaredField("coordinateMode");
        field.setAccessible(true);
        field.set(gates, "nonsense");

        assertThat(gates.coordinateMode()).isEqualTo(ComputerUseCoordinateMode.PIXELS);
    }

    @Test
    @DisplayName("点击结果文案不因模式改变（CCB toolCalls.ts:2504 \"Clicked.\"）")
    void successTextIsModeIndependent() {
        RecordingMouse mouse = new RecordingMouse();
        var result = tool(ComputerUseCoordinateMode.NORMALIZED_0_100, mouse).execute(
                new ToolUseBlock("c1", "mcp__computer-use__left_click", coordinateInput(10, 10)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
    }
}
