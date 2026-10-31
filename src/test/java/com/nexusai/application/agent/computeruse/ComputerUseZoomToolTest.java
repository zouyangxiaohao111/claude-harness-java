package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code zoom} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>region 的坐标空间</b>：CCB {@code tools.ts:234} 明确「region 是<b>上次整屏截图</b>
 *       的坐标空间」⇒ 断言「region 按截图快照比例换算成屏幕矩形再抓」，
 *       否则会截到错误的区域（模型看到 A 区的字、工具截 B 区）；</li>
 *   <li><b>必须有先截图</b>：CCB {@code toolCalls.ts:2329-2335} ⇒ 没有快照就报错，
 *       而不是拿屏幕原点当基准瞎猜；</li>
 *   <li><b>只返回图片</b>：CCB {@code toolCalls.ts:2363-2372} 明确「NO .screenshot piggyback」
 *       ⇒ 断言结果里没有 piggyback。</li>
 * </ul>
 */
class ComputerUseZoomToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode region(int x0, int y0, int x1, int y1) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode arr = node.putArray("region");
        arr.add(x0);
        arr.add(y0);
        arr.add(x1);
        arr.add(y1);
        return node;
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:221-224")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseZoomTool tool = new ComputerUseZoomTool(
                new ComputerUsePhase2aFakes.FakeCapturer(), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__zoom");
        assertThat(tool.description()).isEqualTo(
                "Take a higher-resolution screenshot of a specific region of the last full-screen screenshot. "
                        + "Use this liberally to inspect small text, button labels, or fine UI details that are hard "
                        + "to read in the downsampled full-screen image. "
                        + "IMPORTANT: Coordinates in subsequent click calls always refer to the full-screen "
                        + "screenshot, never the zoomed image. This tool is read-only for inspecting detail.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:225-242（region 长度 4、整数；required=[region]）")
    void schemaIsVerbatim() {
        ComputerUseZoomTool tool = new ComputerUseZoomTool(
                new ComputerUsePhase2aFakes.FakeCapturer(), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS);
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode region = schema.path("properties").path("region");
        assertThat(region.path("type").asText()).isEqualTo("array");
        assertThat(region.path("items").path("type").asText()).isEqualTo("integer");
        assertThat(region.path("minItems").asInt()).isEqualTo(4);
        assertThat(region.path("maxItems").asInt()).isEqualTo(4);
        assertThat(region.path("description").asText()).isEqualTo(
                "(x0, y0, x1, y1): Rectangle to zoom into, in the coordinate space of the most recent "
                        + "full-screen screenshot. x0,y0 = top-left, x1,y1 = bottom-right.");
        assertThat(schema.path("properties").path("save_to_disk").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("region");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("纯读工具 ⇒ isReadOnly=true（不进门控）")
    void isReadOnly() {
        ComputerUseZoomTool tool = new ComputerUseZoomTool(
                new ComputerUsePhase2aFakes.FakeCapturer(), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS);
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("region 按截图快照比例换算成屏幕矩形后抓取")
    void regionIsScaledAgainstSnapshot() {
        ComputerUsePhase2aFakes.FakeCapturer capturer = new ComputerUsePhase2aFakes.FakeCapturer();
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        // 图像 1456x819 ↔ 显示 1920x1080 ⇒ 比例 1920/1456
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUseZoomTool tool = new ComputerUseZoomTool(capturer, geometry,
                ComputerUseCoordinateMode.PIXELS);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), region(100, 100, 356, 309)),
                com.nexusai.application.agent.tool.ToolUseContext.of(null, SESSION));

        assertThat(capturer.regions).hasSize(1);
        int[] rect = capturer.regions.get(0);
        // 比例 1920/1456 = 1080/819 ≈ 1.31868
        // x0 = round(100×1.31868)=132 · y0 = 132 · x1 = round(356×1.31868)=469 · y1 = round(309×1.31868)=407
        assertThat(rect[0]).isEqualTo(132);
        assertThat(rect[1]).isEqualTo(132);
        assertThat(rect[2]).isEqualTo(337);
        assertThat(rect[3]).isEqualTo(275);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("没有先截图 ⇒ 报错（CCB toolCalls.ts:2329-2335 逐字文案）")
    void withoutSnapshotFailsLoud() {
        ComputerUsePhase2aFakes.FakeCapturer capturer = new ComputerUsePhase2aFakes.FakeCapturer();
        ComputerUseZoomTool tool = new ComputerUseZoomTool(capturer, new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), region(0, 0, 100, 100)));

        assertThat(((ToolResult<?>) result).data().toString())
                .contains("take a screenshot before zooming");
        assertThat(capturer.regions).isEmpty();
    }

    @Test
    @DisplayName("region 越界 ⇒ 报错（CCB toolCalls.ts:2336-2341）")
    void regionExceedingBoundsIsRejected() {
        ComputerUsePhase2aFakes.FakeCapturer capturer = new ComputerUsePhase2aFakes.FakeCapturer();
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1000, 800, 1000, 800, 0, 0));
        ComputerUseZoomTool tool = new ComputerUseZoomTool(capturer, geometry,
                ComputerUseCoordinateMode.PIXELS);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), region(0, 0, 1200, 900)),
                com.nexusai.application.agent.tool.ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("exceeds screenshot bounds");
        assertThat(capturer.regions).isEmpty();
    }

    @Test
    @DisplayName("region 长度不是 4 / x1<=x0 ⇒ 报错")
    void invalidRegionIsRejected() {
        ComputerUsePhase2aFakes.FakeCapturer capturer = new ComputerUsePhase2aFakes.FakeCapturer();
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1000, 800, 1000, 800, 0, 0));
        ComputerUseZoomTool tool = new ComputerUseZoomTool(capturer, geometry,
                ComputerUseCoordinateMode.PIXELS);

        // 长度 3（不是 4）⇒ 报错
        ObjectNode shortRegion = JSON.createObjectNode();
        ArrayNode arr = shortRegion.putArray("region");
        arr.add(0);
        arr.add(0);
        arr.add(100);
        var bad = tool.execute(new ToolUseBlock("c1", tool.name(), shortRegion),
                com.nexusai.application.agent.tool.ToolUseContext.of(null, SESSION));
        assertThat(((ToolResult<?>) bad).data().toString()).contains("length 4");

        var inverted = tool.execute(new ToolUseBlock("c2", tool.name(), region(300, 0, 100, 100)),
                com.nexusai.application.agent.tool.ToolUseContext.of(null, SESSION));
        assertThat(((ToolResult<?>) inverted).data().toString()).contains("x1 must be greater than x0");

        assertThat(capturer.regions).isEmpty();
    }
}
