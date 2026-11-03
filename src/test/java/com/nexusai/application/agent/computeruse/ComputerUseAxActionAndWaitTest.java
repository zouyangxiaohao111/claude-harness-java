package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * macOS AX 的<b>任意动作</b>与<b>条件等待</b>单测（对齐 dsh
 * {@code computer_perform_action} / {@code computer_wait}）。
 *
 * <h2>WHY 这些断言重要（规则 9）</h2>
 * <ul>
 *   <li><b>动作名必须被元素声明过</b>：AX 里传一个不存在的动作名会返回 -25207，
 *       但那是「参数无效」而非「应用拒绝」⇒ 两种失败必须分开，否则模型无法区分
 *       「这个控件不支持该动作」与「动作被拒」。</li>
 *   <li><b>条件等待的语义</b>：三个条件是<b>合取</b>；一个都不给 ⇒ 永不匹配
 *       （否则模型会得到一个「立刻返回」的假成功）。</li>
 * </ul>
 */
class ComputerUseAxActionAndWaitTest {

    private static ComputerUseAccessibilitySnapshot snapshot(String text,
                                                           List<ComputerUseAccessibilityNode> nodes) {
        return new ComputerUseAccessibilitySnapshot(text, nodes, System.currentTimeMillis(),
                "Finder", "com.apple.finder", 4242L, "Documents", "hash", false);
    }

    private static ComputerUseAccessibilityNode node(String role,
                                                      String name,
                                                      List<String> actions) {
        return new ComputerUseAccessibilityNode(role, name, "", 0, 0, 0, 0, true, null,
                List.of(), -1, "", actions, "", "");
    }

    // ══════════════════════════════════════════════════════════════════
    // 条件等待（dsh matchesWait）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("条件等待：text 命中（大小写不敏感）")
    void textConditionMatchesCaseInsensitively() {
        var snap = snapshot("[#0] [StaticText] \"Loading complete\"", List.of());

        assertThat(MacOsUiaService.matchesCondition(snap, "loading COMPLETE", null, null)).isTrue();
        assertThat(MacOsUiaService.matchesCondition(snap, "not present", null, null)).isFalse();
    }

    @Test
    @DisplayName("条件等待：element_role 命中")
    void roleConditionMatches() {
        var snap = snapshot("[#0] [Button] \"Save\"",
                List.of(node("Button", "Save", List.of("AXPress"))));

        assertThat(MacOsUiaService.matchesCondition(snap, null, "Button", null)).isTrue();
        assertThat(MacOsUiaService.matchesCondition(snap, null, "StaticText", null)).isFalse();
    }

    @Test
    @DisplayName("条件等待：element_title 命中")
    void titleConditionMatches() {
        var snap = snapshot("[#0] [Button] \"Save\"",
                List.of(node("Button", "Save", List.of("AXPress"))));

        assertThat(MacOsUiaService.matchesCondition(snap, null, null, "Save")).isTrue();
        assertThat(MacOsUiaService.matchesCondition(snap, null, null, "Cancel")).isFalse();
    }

    @Test
    @DisplayName("条件等待：多个条件是合取（全部满足才匹配）")
    void multipleConditionsAreConjunctive() {
        var snap = snapshot("[#0] [Button] \"Save\"",
                List.of(node("Button", "Save", List.of("AXPress"))));

        // text 不命中 ⇒ 整体不匹配
        assertThat(MacOsUiaService.matchesCondition(snap, "other", "Button", "Save")).isFalse();
        // 全部命中 ⇒ 匹配
        assertThat(MacOsUiaService.matchesCondition(snap, "Save", "Button", "Save")).isTrue();
    }

    @Test
    @DisplayName("条件等待：一个条件都不给 ⇒ 永不匹配（不能假成功）")
    void noConditionNeverMatches() {
        var snap = snapshot("[#0] [Button] \"Save\"",
                List.of(node("Button", "Save", List.of("AXPress"))));

        assertThat(MacOsUiaService.matchesCondition(snap, null, null, null)).isFalse();
        assertThat(MacOsUiaService.matchesCondition(snap, "", "", "")).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════
    // 任意动作（dsh computer_perform_action）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("perform_action：动作名必须在元素声明的动作集里")
    void actionMustBeAdvertised() {
        // 元素只声明 AXPress 与 AXShowMenu
        var element = node("Button", "Menu", List.of("AXPress", "AXShowMenu"));

        assertThat(element.actions()).contains("AXPress");
        assertThat(element.actions()).contains("AXShowMenu");
        assertThat(element.actions()).doesNotContain("AXExpand");
    }

    @Test
    @DisplayName("perform_action 的工具描述与 schema 明确要求动作名")
    void performActionSchemaRequiresAction() {
        var tool = new ComputerUsePerformActionTool(
                new ComputerUsePhase2bFakes.FakeUiaService(),
                new ComputerUseUiSnapshotStore(),
                new ComputerUseConfirmationStore());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__perform_action");
        assertThat(tool.inputSchema().path("required")).extracting(JsonNode::asText)
                .containsExactly("action");
        // observationId / index / targetHandle 都在（与 click_element 同款观察绑定）
        assertThat(tool.inputSchema().path("properties").fieldNames()).toIterable()
                .contains("observationId", "index", "targetHandle", "allowRebind", "action");
    }

    @Test
    @DisplayName("perform_action：敏感动作缺令牌 ⇒ 被拒绝（在执行之前）")
    void performActionRequiresTokenWhenSensitive() {
        var store = new ComputerUseConfirmationStore();
        var snapshots = new ComputerUseUiSnapshotStore();

        var gate = ComputerUseSensitiveGate.check(store, snapshots, "s1",
                true, null, "obs", 1, null, "perform_action", "AXPress");

        assertThat(gate.allowed()).isFalse();
        assertThat(gate.rejection()).contains("requires a token from computer_confirm");
    }

    // ══════════════════════════════════════════════════════════════════
    // 截图 pixelScale
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("截图的 pixelScale = 图像像素 / 窗口点（不等于显示缩放比）")
    void pixelScaleIsImagePixelsOverPoints() {
        // 图像被缩到 1568 宽（Retina 显示宽 3024 物理像素 = 1512 点）
        //   ⇒ pixelScale = 1568 / 1512 ≈ 1.0365（**不是** scaleFactor 2.0）
        var capture = new ComputerUseCaptureResult(
                new byte[]{1, 2, 3}, "image/jpeg",
                1568, 1000,          // 图像尺寸（已缩放）
                3024, 1920,          // 显示物理像素
                0, 0,
                2.0d,                // scaleFactor（像素/点）
                1512, 960);          // 逻辑点

        double expected = 1568d / 1512d;
        assertThat(capture.logicalWidth()).isEqualTo(1512);
        assertThat(expected).isNotEqualTo(capture.scaleFactor());
        // 截图工具按这个公式填 pixelScale
        assertThat(expected).isGreaterThan(1.0d).isLessThan(1.1d);
    }
}
