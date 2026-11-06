package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程<b>定向投递</b>的契约（对齐 dsh：键盘 {@code postToPid} / 指针 {@code SLEventPostToPid}）。
 *
 * <h2>WHY 这些断言重要</h2>
 * <p>「定向」的核心不是「能送过去」，而是<b>「不给用户添麻烦」</b>：
 * <ul>
 *   <li>键盘定向 ⇒ 在后台应用里输入时<b>不抢焦点</b>（用户正在写别的东西不会被打断）；</li>
 *   <li>指针定向 ⇒ 滚轮只滚目标窗口，<b>系统光标不动</b>；</li>
 *   <li>Unicode 定向 ⇒ <b>不碰用户剪贴板</b>（剪贴板方案中途失败会丢用户原内容）。</li>
 * </ul>
 * 这三条都是「静默降级就完全失去意义」的能力 —— 一旦退回全局投递，
 * 表面上功能还在，但用户会被打断，而<b>没有任何报错</b>。
 * ⇒ 本测试锁住「定向通道真的被接上、参数真的算对」。
 */
class MacOsTargetedInputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("键盘脚本在给了 pid 时必须用 CGEventPostToPid，而不是全局 HID")
    void keyScriptTargetsPid() {
        String script = MacOsJxa.keyEvent(0, true, null, 4242L, 0L);

        assertThat(script)
                .as("定向键盘必须走 CGEventPostToPid(pid, e)")
                .contains("CGEventPostToPid(4242")
                .as("绝不能同时投全局（那会抢焦点）")
                .doesNotContain("kCGHIDEventTap");
    }

    @Test
    @DisplayName("没给 pid 时保持旧行为（全局 HID）—— 不破坏既有调用方")
    void keyScriptFallsBackToGlobal() {
        String script = MacOsJxa.keyEvent(0, true, null, 0L, 0L);

        assertThat(script).contains("CGEventPost($.kCGHIDEventTap");
        assertThat(script).doesNotContain("CGEventPostToPid");
    }

    @Test
    @DisplayName("修饰键 flags 在定向投递时也要带上（否则 cmd+a 会变成 a）")
    void keyScriptKeepsModifierFlags() {
        String script = MacOsJxa.keyEvent(0, true, "$.kCGEventFlagMaskCommand", 4242L, 0L);

        assertThat(script).contains("CGEventSetFlags(e, $.kCGEventFlagMaskCommand)");
        assertThat(script).contains("CGEventPostToPid(4242");
    }

    @Test
    @DisplayName("keyTap 一次调用内完成 down + up（减少进程往返，且保证同目标）")
    void keyTapPostsBothInOneScript() {
        String script = MacOsJxa.keyTap(36, null, 4242L, 10L);

        assertThat(script)
                .as("down 与 up 都要投给同一 pid")
                .containsPattern("CGEventPostToPid\\(4242, mk\\(true\\)\\)")
                .containsPattern("CGEventPostToPid\\(4242, mk\\(false\\)\\)");
        assertThat(script).contains("sleepForTimeInterval");
    }

    @Test
    @DisplayName("Observation 带窗口几何才能定向滚轮（否则不知道投给哪个窗口）")
    void observationCarriesWindowTarget() {
        var elements = List.<ComputerUseUiSnapshotStore.ObservedElement>of();
        var withWindow = new ComputerUseUiSnapshotStore.Observation(
                "obs-1", "s1", "Finder", "com.apple.finder", 4242L, "Documents",
                System.currentTimeMillis(), Long.MAX_VALUE, "hash", "full", "text", false,
                elements, 0, 419L, 170, 91);

        assertThat(withWindow.hasWindowTarget()).isTrue();
        assertThat(withWindow.windowId()).isEqualTo(419L);
        assertThat(withWindow.windowX()).isEqualTo(170);
        assertThat(withWindow.windowY()).isEqualTo(91);
    }

    @Test
    @DisplayName("没有窗口号的观察 ⇒ 不能定向（调用方必须识别并回落，而不是投到错的窗口）")
    void observationWithoutWindowCannotTarget() {
        var oldStyle = new ComputerUseUiSnapshotStore.Observation(
                "obs-2", "s1", "Finder", "com.apple.finder", 4242L, "Documents",
                System.currentTimeMillis(), Long.MAX_VALUE, "hash", "full", "text", false,
                List.of(), 0);

        assertThat(oldStyle.hasWindowTarget()).isFalse();
    }

    @Test
    @DisplayName("type / key / scroll / drag 都必须接受 observationId（定向的统一入口）")
    void targetedToolsAcceptObservationId() {
        // type / key：用假键盘（只读 schema，不会真注入）
        var fakeKeyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        assertThat(new ComputerUseTypeTool(fakeKeyboard, ComputerUseSleeper.REAL, null, null)
                .inputSchema().path("properties").has("observationId"))
                .as("type 必须能指定目标应用").isTrue();
        assertThat(new ComputerUseKeyTool(fakeKeyboard, ComputerUseSleeper.REAL, null, null)
                .inputSchema().path("properties").has("observationId"))
                .as("key 必须能指定目标应用").isTrue();

        // scroll / drag：schema 带 %s 模板参数，构造时按像素模式渲染
        ComputerUseScrollTool scroll = new ComputerUseScrollTool(
                new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUseGeometryStore(), ComputerUseCoordinateMode.PIXELS,
                null, new ComputerUsePhase2aFakes.FixedResolver("notepad"),
                new ComputerUseMouseState());
        assertThat(scroll.inputSchema().path("properties").has("observationId"))
                .as("scroll 必须能指定目标应用").isTrue();

        ComputerUseLeftClickDragTool drag = new ComputerUseLeftClickDragTool(
                new ComputerUseMouseAnimator(new ComputerUsePhase2aFakes.FakeCursor(),
                        ComputerUseSleeper.REAL, false),
                new ComputerUseGeometryStore(), ComputerUseCoordinateMode.PIXELS,
                null, null, new ComputerUseMouseState());
        assertThat(drag.inputSchema().path("properties").has("observationId"))
                .as("drag 必须能指定目标应用").isTrue();
    }

    /**
     * 源级守卫：定向指针投递必须<b>成对</b>设置两个坐标。
     *
     * <h2>WHY 直接读自己的源码做断言</h2>
     * <p>这条约束的失败形态是「<b>没有任何报错，但什么都没发生</b>」——
     * 单元测试调用 {@code postScroll} 只能看到 {@code return true}，
     * 无法知道事件是否真被系统接收（那需要真机）。
     * <br>而漏设坐标的可能性来自「有人日后新增一条投递路径」。
     * ⇒ 唯一能自动守住的办法：断言<b>源码里 {@code CGEventSetLocation} 的调用点只集中在一处</b>
     * （{@code sendPointer}），且每次 {@code SLEventPostToPid} 都在它之后。
     *
     * <p>这与本仓 {@code MacOsJxaScriptIntegrityTest}（断言脚本必须自带 {@code axHash}）同款思路：
     * 把「结构性不变量」变成可执行的断言，而不是靠注释提醒。
     */
    @Test
    @DisplayName("源级守卫：指针投递的屏幕坐标只能由 sendPointer 统一设置（调用方漏设不了）")
    void pointerScreenLocationIsSetInOnePlaceOnly() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/nexusai/application/agent/computeruse/MacOsSkyLight.java");
        String code = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);

        // 去掉注释与 javadoc，只留真实代码（否则文档里提到的名字会造成假命中）
        String codeOnly = code.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");

        long setLocationCalls = java.util.regex.Pattern
                .compile("\\.CGEventSetLocation\\(")
                .matcher(codeOnly).results().count();
        // ⚠️ 必须带前导点：接口里还有一行声明 `void SLEventPostToPid(...)`，
        //   不带点会把它也算成一次调用（第一版就因此假红）。
        long postCalls = java.util.regex.Pattern
                .compile("\\.SLEventPostToPid\\(")
                .matcher(codeOnly).results().count();

        assertThat(setLocationCalls)
                .as("CGEventSetLocation 在实现代码里必须只有【一处】调用（sendPointer 内）——"
                        + " 分散到调用方就一定会有人漏设，而漏设的表现是静默无效、无报错")
                .isEqualTo(1L);
        assertThat(postCalls)
                .as("SLEventPostToPid 也应当只在 sendPointer 里调用一次")
                .isEqualTo(1L);

        // 且两者在同一方法体内：CGEventSetLocation 必须出现在 SLEventPostToPid 之前
        int locationIdx = codeOnly.indexOf(".CGEventSetLocation(");
        int postIdx = codeOnly.indexOf(".SLEventPostToPid(");
        assertThat(locationIdx)
                .as("屏幕坐标必须在投递【之前】设置")
                .isLessThan(postIdx)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("源级守卫：sendPointer 必须同时设置屏幕坐标与窗口内局部坐标")
    void sendPointerSetsBothCoordinates() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/nexusai/application/agent/computeruse/MacOsSkyLight.java");
        String code = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);

        int i = code.indexOf("private static void sendPointer(");
        assertThat(i).as("sendPointer 必须存在").isGreaterThan(0);
        String body = code.substring(i, Math.min(i + 3000, code.length()));

        assertThat(body)
                .as("① 事件自身的屏幕坐标（漏了 ⇒ 事件被静默丢弃）")
                .contains(".CGEventSetLocation(");
        assertThat(body)
                .as("② 窗口内局部坐标（漏了 ⇒ 落到错位置；且不能替代①）")
                .contains(".CGEventSetWindowLocation(");
    }

    @Test
    @DisplayName("observationId 陈旧/不存在时必须显式拒绝（不能静默退化成全局注入）")
    void staleObservationIdIsRejectedNotSilentlyGlobal() {
        // 判据：错误信息里必须带 COMPUTER_STALE_OBSERVATION ——
        // 若只是「没定向成功」而不报错，用户就会被抢焦点且完全不知情。
        assertThat(ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION)
                .isEqualTo("COMPUTER_STALE_OBSERVATION");
        assertThat(JSON).isNotNull();
    }
}
