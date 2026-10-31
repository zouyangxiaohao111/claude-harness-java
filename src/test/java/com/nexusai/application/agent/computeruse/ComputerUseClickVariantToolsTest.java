package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 点击变体工具单测（Phase 2a 的 double_click / triple_click / right_click / middle_click）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li><b>工具面逐字</b>：CCB 的 API 后端靠工具名识别 computer-use（{@code setup.ts:17-21}），
 *       模型靠 description 知道「双击选中单词 / 三击选中行 / 右键弹菜单」——
 *       描述改一个字，模型的行为就变 ⇒ 逐字断言；</li>
 *   <li><b>档位门控分档</b>：CCB {@code toolCalls.ts:2429-2432} 规定「右键 / 中键 ⇒ mouse_full」
 *       （右键菜单里带 Paste/Cut ⇒ 等价于键盘输入 ⇒ click 档不够），
 *       「左键（含双击 / 三击）⇒ mouse」⇒ 断言「click 档能双击但不能右键」；</li>
 *   <li><b>坐标换算接线</b>：点歪了就是安全事故（点到别处）⇒ 断言点击的是换算后的屏幕坐标。</li>
 * </ul>
 */
class ComputerUseClickVariantToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    /** CCB 的 FRONTMOST_GATE_DESC（tools.ts:31-32）· 逐字。 */
    private static final String GATE_DESC =
            "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing.";

    private static ObjectNode coordinate(double x, double y) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    private static ComputerUseClickVariantTool variant(String kind,
                                                       ComputerUsePhase2aFakes.RecordingMouseActions mouse,
                                                       ComputerUseGeometryStore geometry,
                                                       ComputerUseGrantStore grantStore,
                                                       String foregroundAppId) {
        ComputerUseForegroundAppResolver resolver =
                new ComputerUsePhase2aFakes.FixedResolver(foregroundAppId);
        return switch (kind) {
            case "double" -> new ComputerUseDoubleClickTool(mouse, geometry,
                    ComputerUseCoordinateMode.PIXELS, grantStore, resolver);
            case "triple" -> new ComputerUseTripleClickTool(mouse, geometry,
                    ComputerUseCoordinateMode.PIXELS, grantStore, resolver);
            case "right" -> new ComputerUseRightClickTool(mouse, geometry,
                    ComputerUseCoordinateMode.PIXELS, grantStore, resolver);
            default -> new ComputerUseMiddleClickTool(mouse, geometry,
                    ComputerUseCoordinateMode.PIXELS, grantStore, resolver);
        };
    }

    @Test
    @DisplayName("4 个点击变体的工具名逐字为 mcp__computer-use__<name>")
    void toolNamesAreExact() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        assertThat(variant("double", mouse, geometry, null, null).name())
                .isEqualTo("mcp__computer-use__double_click");
        assertThat(variant("triple", mouse, geometry, null, null).name())
                .isEqualTo("mcp__computer-use__triple_click");
        assertThat(variant("right", mouse, geometry, null, null).name())
                .isEqualTo("mcp__computer-use__right_click");
        assertThat(variant("middle", mouse, geometry, null, null).name())
                .isEqualTo("mcp__computer-use__middle_click");
    }

    @Test
    @DisplayName("描述逐字对齐 CCB tools.ts:261/274/287/300（含 FRONTMOST_GATE_DESC）")
    void descriptionsAreVerbatim() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        assertThat(variant("double", mouse, geometry, null, null).description())
                .isEqualTo("Double-click at the given coordinates. Selects a word in most text editors. " + GATE_DESC);
        assertThat(variant("triple", mouse, geometry, null, null).description())
                .isEqualTo("Triple-click at the given coordinates. Selects a line in most text editors. " + GATE_DESC);
        assertThat(variant("right", mouse, geometry, null, null).description())
                .isEqualTo("Right-click at the given coordinates. Opens a context menu in most applications. " + GATE_DESC);
        assertThat(variant("middle", mouse, geometry, null, null).description())
                .isEqualTo("Middle-click (scroll-wheel click) at the given coordinates. " + GATE_DESC);
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:140-152（coordinate 元组 + text 修饰键；required=[coordinate]）")
    void schemaIsVerbatim() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        JsonNode schema = variant("double", mouse, geometry, null, null).inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode coord = schema.path("properties").path("coordinate");
        assertThat(coord.path("type").asText()).isEqualTo("array");
        assertThat(coord.path("items").path("type").asText()).isEqualTo("number");
        assertThat(coord.path("minItems").asInt()).isEqualTo(2);
        assertThat(coord.path("maxItems").asInt()).isEqualTo(2);
        assertThat(coord.path("description").asText())
                .isEqualTo("(x, y): " + ComputerUseCoordinateDescriptions.PIXELS_X);
        assertThat(schema.path("properties").path("text").path("description").asText())
                .isEqualTo("Modifier keys to hold during the click (e.g. \"shift\", \"ctrl+shift\"). "
                        + "Supports the same syntax as the key tool.");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("coordinate");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("双击 = 左键 2 次；三击 = 左键 3 次（CCB toolCalls.ts:4163-4166）")
    void clickCountsMatchCcb() {
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        variant("double", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", coordinate(10, 20)));
        variant("triple", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c2", "mcp__computer-use__triple_click", coordinate(10, 20)));

        assertThat(mouse.buttons).containsExactly(
                ComputerUseMouseButton.LEFT, ComputerUseMouseButton.LEFT);
        assertThat(mouse.counts).containsExactly(2, 3);
    }

    @Test
    @DisplayName("右键 = 右键 1 次；中键 = 中键 1 次（CCB toolCalls.ts:4167-4170）")
    void buttonMappingMatchesCcb() {
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        variant("right", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c1", "mcp__computer-use__right_click", coordinate(10, 20)));
        variant("middle", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c2", "mcp__computer-use__middle_click", coordinate(10, 20)));

        assertThat(mouse.buttons).containsExactly(
                ComputerUseMouseButton.RIGHT, ComputerUseMouseButton.MIDDLE);
        assertThat(mouse.counts).containsExactly(1, 1);
    }

    @Test
    @DisplayName("坐标按截图快照比例换算（点到的是屏幕坐标，不是图像坐标）")
    void coordinatesAreScaled() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        // 图像 1456x819 ↔ 显示 1920x1080 ⇒ 比例 1920/1456
        geometry.put(null, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        variant("double", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", coordinate(728, 409.5d)));

        assertThat(mouse.clicks).hasSize(1);
        assertThat(mouse.clicks.get(0)).containsExactly(960, 540);
    }

    @Test
    @DisplayName("右键在 click 档被拒（CCB toolCalls.ts:2429-2432：右键 ⇒ mouse_full 档）")
    void rightClickNeedsFullTier() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "click")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        var result = variant("right", mouse, geometry, grantStore, "notepad")
                .execute(new ToolUseBlock("c1", "mcp__computer-use__right_click", coordinate(10, 20)),
                        ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(mouse.clicks).as("⛔ 档位不足 ⇒ 不得点击").isEmpty();
    }

    @Test
    @DisplayName("双击在 click 档放行（CCB：左键 ⇒ mouse 档）")
    void doubleClickAllowedAtClickTier() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "click")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        var result = variant("double", mouse, geometry, grantStore, "notepad")
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", coordinate(10, 20)),
                        ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Clicked.");
        assertThat(mouse.clicks).hasSize(1);
    }

    @Test
    @DisplayName("前台应用不在白名单 ⇒ 拒绝（CCB toolCalls.ts:578-583）")
    void notGrantedForegroundAppIsRejected() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                ComputerUseGrants.empty().toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        var result = variant("double", mouse, geometry, grantStore, "chrome")
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", coordinate(10, 20)),
                        ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("not in the allowed applications");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("[Phase 2b] 修饰键已实现：无点击路由接线时显式报错，不静默忽略模型意图")
    void modifiersRequireWiredRouter() {
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ObjectNode input = coordinate(10, 20);
        input.put("text", "shift");

        var result = variant("double", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", input));

        assertThat(((ToolResult<?>) result).data().toString()).contains("修饰键");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("无截图快照 ⇒ fail loud（不猜坐标）")
    void withoutSnapshotFailsLoud() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();

        var result = variant("double", mouse, geometry, null, null)
                .execute(new ToolUseBlock("c1", "mcp__computer-use__double_click", coordinate(10, 20)));

        assertThat(mouse.clicks).isEmpty();
        assertThat(((ToolResult<?>) result).data().toString()).contains("截图");
    }
}
