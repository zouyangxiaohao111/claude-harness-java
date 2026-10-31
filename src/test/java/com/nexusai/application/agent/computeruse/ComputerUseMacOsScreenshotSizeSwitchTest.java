package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-01] macOS 截屏<b>尺寸开关</b>单测（纯单测 · <b>假进程执行器</b> · 零真实命令）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：截图尺寸直接决定两件事 ——
 * ①<b>视觉模型的坐标空间</b>（模型看到的是哪张图，坐标就是那张图的像素）；
 * ②<b>token 成本</b>（图像 token ∝ 面积）。二者都不可逆地影响「点得准不准、花多少钱」。
 * <br>⇒ 本测试锁死 macOS 侧与 Windows 侧<b>同一个开关、同一个不变式</b>：
 * <ul>
 *   <li>开关 <b>true</b>（默认，严格 CCB 口径）⇒ 长边 ≤ 1568 且图像 token ≤ 预算 1568；</li>
 *   <li>开关 <b>false</b> ⇒ 输出 = <b>原图尺寸</b>（不缩），token 显著上升；</li>
 *   <li>⭐ <b>不变式</b>：无论开关如何，<b>记录的尺寸 = 送出去的尺寸</b> ⇒ 输出尺寸由
 *       <b>同一个纯函数</b>决定（{@link ComputerUseApiResize#resolveOutputSize}），
 *       Windows 侧与 macOS 侧共用它。</li>
 * </ul>
 *
 * <p>⚠️ <b>运行时未验证</b>：本机是 Windows ⇒ macOS 侧只做到「<b>编译通过 + 假执行器单测</b>」，
 * <b>真实 macOS 上的截图尺寸行为未在真机跑过</b>（需一台 macOS 机器实测）。
 */
class ComputerUseMacOsScreenshotSizeSwitchTest {

    /** 本机 1920×1080 物理屏（真机实测基准）。 */
    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    // ══════════════════════════════════════════════════════════════════
    // ① 纯函数：尺寸计算
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 开关 true（默认 · 严格 CCB 口径）⇒ 长边 ≤1568 且图像 token ≤ 预算")
    void preSizeTrueStaysWithinCcbBudget() {
        int[] out = MacOsScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, true);

        assertThat(Math.max(out[0], out[1]))
                .as("长边必须 ≤ CCB 上限 1568")
                .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);
        assertThat(out[0]).isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);
        assertThat(out[1]).isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);

        int tokens = ComputerUseApiResize.nTokensForImg(out[0], out[1], ComputerUseApiResize.PX_PER_TOKEN);
        assertThat(tokens)
                .as("图像 token 必须 ≤ CCB 预算 1568（超了上游会二次压缩 ⇒ 坐标空间失配）")
                .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_TOKENS);
    }

    @Test
    @DisplayName("⭐ 开关 false ⇒ 输出 = 原图尺寸（不缩）")
    void preSizeFalseKeepsOriginalSize() {
        int[] out = MacOsScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, false);

        assertThat(out[0]).isEqualTo(SCREEN_W);
        assertThat(out[1]).isEqualTo(SCREEN_H);
    }

    @Test
    @DisplayName("⭐ macOS 与 Windows 共用同一尺寸函数 ⇒ 两平台输出必然一致")
    void macAndWindowsShareTheSameSizeFunction() {
        for (boolean preSize : new boolean[]{true, false}) {
            assertThat(MacOsScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, preSize))
                    .as("开关=%s 时 macOS 与 Windows 必须算出同一尺寸", preSize)
                    .containsExactly(Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, preSize));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 假执行器：端到端的「记录的尺寸 == 送出去的尺寸」不变式
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 开关 true ⇒ capture() 记录的图像尺寸 = 缩放后的尺寸（≤1568）")
    void captureRecordsCompressedSizeWhenSwitchOn() throws Exception {
        ComputerUseCaptureResult result = captureWithSwitch(true);

        assertThat(result.imageWidth()).isEqualTo(1456);
        assertThat(result.imageHeight()).isEqualTo(819);
        assertThat(result.displayWidth()).isEqualTo(SCREEN_W);
        assertThat(result.displayHeight()).isEqualTo(SCREEN_H);
        assertThat(Math.max(result.imageWidth(), result.imageHeight()))
                .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);
    }

    @Test
    @DisplayName("⭐ 开关 false ⇒ capture() 记录的图像尺寸 = 原图尺寸（不缩）")
    void captureRecordsOriginalSizeWhenSwitchOff() throws Exception {
        ComputerUseCaptureResult result = captureWithSwitch(false);

        assertThat(result.imageWidth()).isEqualTo(SCREEN_W);
        assertThat(result.imageHeight()).isEqualTo(SCREEN_H);
    }

    // ══════════════════════════════════════════════════════════════════
    // 假执行器
    // ══════════════════════════════════════════════════════════════════

    /**
     * 用假执行器跑一次整屏捕获。
     *
     * @param preSize 截图预缩放开关
     * @return 捕获结果
     */
    private static ComputerUseCaptureResult captureWithSwitch(boolean preSize) throws Exception {
        FakeRunner runner = new FakeRunner();
        MacOsScreenCapturer capturer = new MacOsScreenCapturer(
                runner, new MacOsDisplays(runner), new MacOsTccChecker(runner), preSize);
        return capturer.capture();
    }

    /** 假进程执行器：{@code screencapture} 写一张固定 PNG，其余命令回固定值。 */
    private static final class FakeRunner implements ComputerUseProcessRunner {

        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command) {
            commands.add(List.copyOf(command));
            if (!command.isEmpty() && "screencapture".equals(command.get(0))) {
                writePng(new File(command.get(command.size() - 1)), SCREEN_W, SCREEN_H);
                return new Result(0, "", "");
            }
            String script = scriptOf(command);
            if (script.contains("AXIsProcessTrusted")) {
                return new Result(0, "true", "");
            }
            if (script.contains("CGPreflightScreenCaptureAccess")) {
                return new Result(0, "true", "");
            }
            if (script.contains("CGGetActiveDisplayList")) {
                return new Result(0,
                        "[{\"displayId\":1,\"pixelWidth\":1920,\"pixelHeight\":1080,"
                                + "\"pointWidth\":960,\"pointHeight\":540,"
                                + "\"originX\":0,\"originY\":0,\"pixelsPerPoint\":2,\"isPrimary\":true}]",
                        "");
            }
            return new Result(0, "", "");
        }

        @Override
        public Result runWithStdin(List<String> command, String stdin) {
            return run(command);
        }
    }

    /** 取命令里的 JXA 脚本（无则空串）。 */
    private static String scriptOf(List<String> cmd) {
        int idx = cmd.indexOf("-e");
        return (idx >= 0 && idx + 1 < cmd.size()) ? cmd.get(idx + 1) : "";
    }

    /** 写一张纯黑 PNG（占位图，尺寸才是本测试关心的）。 */
    private static void writePng(File file, int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, w, h);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "png", file);
        } catch (Exception e) {
            throw new IllegalStateException("[测试] 写 PNG 失败: " + e, e);
        }
    }
}
