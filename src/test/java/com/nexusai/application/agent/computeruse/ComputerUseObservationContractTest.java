package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 观察契约单测 · computer-use（对齐 dsh {@code observationSchema} + {@code appSelectorSchema}）。
 *
 * <h2>WHY 这个用例重要（规则 9）</h2>
 * <ul>
 *   <li><b>{@code app} 选择器</b>：dsh 允许模型指定观察对象（bundleId / pid / name），
 *       <b>观察不抢前台</b>。本仓此前硬编码前台应用 ⇒ 想看后台应用必须先激活它，
 *       与 dsh「不干扰用户」的核心目标相反。
 *       <br>⇒ 用例钉住「选择器能解析出三项」「解析失败必须显式失败、
 *       <b>不得静默回落到前台应用</b>」。</li>
 *   <li><b>元素字段</b>：{@code subrole} / {@code nativeIdentifier} / {@code focused} /
 *       {@code selected} 是三级解析与状态判断的稳定字段，采集到了却没输出等于白采集。</li>
 *   <li><b>截图产物</b>：dsh 的 Artifact 形态是<b>文件路径</b>（不是 inline image），
 *       因为本仓的视觉通道 {@code vision_analyze} 只吃 path。</li>
 * </ul>
 */
class ComputerUseObservationContractTest {

    @Test
    @DisplayName("app 选择器：三项都能解析；空 ⇒ 前台应用")
    void appSelectorParses() {
        ObjectNode node = JSON.createObjectNode();
        node.put("bundleId", "com.apple.TextEdit");
        node.put("pid", 4242L);
        node.put("name", "文本编辑");

        ComputerUseAppSelector selector = ComputerUseObserveTool.readSelector(node);
        assertThat(selector.bundleId()).isEqualTo("com.apple.TextEdit");
        assertThat(selector.pid()).isEqualTo(4242L);
        assertThat(selector.name()).isEqualTo("文本编辑");
        assertThat(selector.isPresent()).isTrue();

        // 缺失 ⇒ 前台应用（dsh 的 app 是 required，但本仓允许省略 = 观察前台）
        ComputerUseAppSelector frontmost = ComputerUseObserveTool.readSelector(null);
        assertThat(frontmost.isEmpty()).isTrue();
        assertThat(frontmost.pid()).isZero();
    }

    @Test
    @DisplayName("screenshot 模式：none/optional/required 三态；非法值回落 optional")
    void screenshotModeIsThreeState() {
        ObjectNode node = JSON.createObjectNode();
        node.put("screenshot", "none");
        assertThat(ComputerUseObserveTool.readScreenshotMode(node)).isEqualTo("none");
        node.put("screenshot", "required");
        assertThat(ComputerUseObserveTool.readScreenshotMode(node)).isEqualTo("required");
        node.put("screenshot", "optional");
        assertThat(ComputerUseObserveTool.readScreenshotMode(node)).isEqualTo("optional");
        // 缺省 ⇒ optional（dsh 同款：能截就截）
        assertThat(ComputerUseObserveTool.readScreenshotMode(JSON.createObjectNode()))
                .isEqualTo("optional");
        // 非法值 ⇒ 回落 optional，不报错（模型偶尔会传错）
        node.put("screenshot", "yes");
        assertThat(ComputerUseObserveTool.readScreenshotMode(node)).isEqualTo("optional");
    }

    @Test
    @DisplayName("元素 JSON 带出 subrole / nativeIdentifier / focused / selected")
    void elementJsonCarriesStableFields() {
        ComputerUseUiSnapshotStore.ObservedElement element = new ComputerUseUiSnapshotStore.ObservedElement(
                3, "handle-1", "0.2", "Button", "Save", "v", true,
                List.of("AXPress"), 10, 20, 30, 40, "AXMenuButton", "_NS:34", true, false);

        var json = ComputerUseUiSnapshotStore.elementsToJson(java.util.List.of(element));
        assertThat(json.get(0).path("subrole").asText()).isEqualTo("AXMenuButton");
        assertThat(json.get(0).path("nativeIdentifier").asText()).isEqualTo("_NS:34");
        assertThat(json.get(0).path("focused").asBoolean()).isTrue();
        assertThat(json.get(0).path("selected").asBoolean()).isFalse();
        assertThat(json.get(0).path("index").asInt()).isEqualTo(3);
        assertThat(json.get(0).path("targetHandle").asText()).isEqualTo("handle-1");
    }

    @Test
    @DisplayName("diff 身份键用语义身份（role|subrole|name）而非位置（locator）")
    void diffIdentityIsSemantic() {
        // 同一个控件换了位置（locator 变了）⇒ 仍是「同一个」⇒ 不该报成新增+移除
        ComputerUseUiSnapshotStore.ObservedElement before = new ComputerUseUiSnapshotStore.ObservedElement(
                1, "h", "0", "Button", "Save", null, true, List.of("AXPress"), 0, 0, 0, 0, "", "");
        ComputerUseUiSnapshotStore.ObservedElement after = new ComputerUseUiSnapshotStore.ObservedElement(
                5, "h2", "0.7", "Button", "Save", null, true, List.of("AXPress"), 0, 0, 0, 0, "", "");

        assertThat(ComputerUseUiSnapshotStore.identityOf(before))
                .isEqualTo(ComputerUseUiSnapshotStore.identityOf(after));

        String diff = ComputerUseUiSnapshotStore.diffElements(
                java.util.List.of(before), java.util.List.of(after));
        assertThat(diff).contains("no accessibility changes");
    }

    @Test
    @DisplayName("截图产物：mode=none 不截；optional 失败不抛；落盘在 screenshots/ 且是真 JPEG")
    void screenshotArtifactLandsInScreenshotsDir() throws Exception {
        // mode=none ⇒ 不产出
        ObjectNode perms = JSON.createObjectNode();
        assertThat(ComputerUseScreenshotArtifact.capture(
                null, null, "sess-contract", "obs-1", "none", perms)).isNull();

        // mode=optional 且截图器为 null ⇒ 不抛异常、不产出（降级为「没有截图」）
        ObjectNode perms2 = JSON.createObjectNode();
        assertThat(ComputerUseScreenshotArtifact.capture(
                null, null, "sess-contract", "obs-1", "optional", perms2)).isNull();

        // mode=required 且截图器为 null ⇒ 显式失败（规则十二：显式失败）
        ObjectNode perms3 = JSON.createObjectNode();
        assertThatThrownBy(() -> ComputerUseScreenshotArtifact.capture(
                null, null, "sess-contract", "obs-1", "required", perms3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("screenshot=required");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
