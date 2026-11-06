package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 元素矩形（dsh {@code elementSchema.frame}）的落点契约。
 *
 * <h2>WHY 这些断言重要</h2>
 * <p>没有 {@code frame}，模型只能按 {@code index} 盲操作 —— 它<b>说不出「我要点这个图标」</b>，
 * 也说不出「这个元素大概在屏幕哪里」，一旦 AX 动作不可用（自绘控件）就没有任何退路。
 * dsh 的 {@code elementSchema} 里 {@code frame} 是稳定字段，本仓此前恒缺。
 *
 * <p><b>⚠️ 两条已踩过的坑（本测试就是防它们回来）</b>：
 * <ol>
 *   <li><b>只回填顶层节点</b>：{@code nodes} 只是顶层列表，子节点在 {@code children} 里
 *       ⇒ 43 个元素只有 1 个（根）拿到矩形。必须递归；</li>
 *   <li><b>零尺寸元素填 (0,0) 0x0</b>：那会让模型以为真有元素在屏幕左上角 ⇒ 读不到就<b>不填</b>。</li>
 * </ol>
 */
class MacOsElementFrameTest {

    @Test
    @DisplayName("elements JSON 的 frame 是对象形状（dsh rectSchema），不是扁平四字段")
    void elementJsonCarriesFrameObject() {
        var element = new ComputerUseUiSnapshotStore.ObservedElement(
                3, "h-3", "0.1.2", "Button", "OK", null, true,
                List.of("AXPress"), 100, 200, 60, 30, "", "", null, null);

        var json = ComputerUseUiSnapshotStore.elementsToJson(List.of(element));
        var node = json.get(0);

        assertThat(node.path("frame").path("x").asInt()).isEqualTo(100);
        assertThat(node.path("frame").path("y").asInt()).isEqualTo(200);
        assertThat(node.path("frame").path("width").asInt()).isEqualTo(60);
        assertThat(node.path("frame").path("height").asInt()).isEqualTo(30);
        // 兼容既有消费者的扁平字段仍在（不破坏别的读取方）
        assertThat(node.path("x").asInt()).isEqualTo(100);
        assertThat(node.path("w").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("没有矩形的元素不输出 frame（不能填 0 ⇒ 会指向屏幕左上角）")
    void elementWithoutBoundsOmitsFrame() {
        var element = new ComputerUseUiSnapshotStore.ObservedElement(
                4, "h-4", "0.1.3", "Group", "", null, true,
                List.of(), 0, 0, 0, 0, "", "", null, null);

        var json = ComputerUseUiSnapshotStore.elementsToJson(List.of(element));

        assertThat(json.get(0).has("frame"))
                .as("零尺寸元素不能带 frame —— 否则模型会以为它在 (0,0)")
                .isFalse();
    }

    @Test
    @DisplayName("focused / selected 必须真的进 JSON（曾因 14 参构造被静默丢掉）")
    void focusedAndSelectedReachJson() {
        var element = new ComputerUseUiSnapshotStore.ObservedElement(
                5, "h-5", "0.2", "TextField", "Name", "abc", true,
                List.of("AXPress"), 10, 20, 100, 24, "", "", Boolean.TRUE, Boolean.FALSE);

        var node = ComputerUseUiSnapshotStore.elementsToJson(List.of(element)).get(0);

        assertThat(node.path("focused").asBoolean()).isTrue();
        assertThat(node.path("selected").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("toObservedElements 必须把 focused / selected 透传过去（不是只传角色与动作）")
    void observedElementsKeepFocusedAndSelected() {
        var leaf = new ComputerUseAccessibilityNode(
                "TextField", "Name", "", 10, 20, 100, 24, true, "abc",
                List.of(), 1, "0.0", List.of("AXPress"), "", "", Boolean.TRUE, Boolean.TRUE);

        var elements = ComputerUseObserveTool.toObservedElements(List.of(leaf));

        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).focused()).isTrue();
        assertThat(elements.get(0).selected()).isTrue();
        assertThat(elements.get(0).hasBounds()).isTrue();
    }

    @Test
    @DisplayName("非 macOS 上取矩形必须安全返回空（不能因为 Windows 没有 ApplicationServices 就崩）")
    void framesAreSafeOnNonMac() {
        assertThatCode(() -> MacOsAxFrames.frames(0L, 14, 500))
                .doesNotThrowAnyException();
        assertThat(MacOsAxFrames.frames(0L, 14, 500)).isEmpty();
    }
}
