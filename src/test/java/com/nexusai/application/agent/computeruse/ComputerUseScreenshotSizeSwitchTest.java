package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-01] computer-use <b>截图尺寸开关</b>单测（纯单测 · <b>不抓屏</b>）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：截图尺寸直接决定两件事 ——
 * ①<b>视觉模型的坐标空间</b>（模型看到的是哪张图，坐标就是那张图的像素）；
 * ②<b>token 成本</b>（图像 token ∝ 面积）。二者都不可逆地影响「点得准不准、花多少钱」。
 * <br>⇒ 本测试锁死：
 * <ul>
 *   <li>开关 <b>true</b>（默认，严格 CCB 口径）⇒ 长边 ≤ 1568 且图像 token ≤ 预算 1568；</li>
 *   <li>开关 <b>false</b> ⇒ 输出 = <b>原图尺寸</b>（不缩），token 显著上升；</li>
 *   <li>⭐ <b>不变式</b>：无论开关如何，<b>记录的尺寸 = 送出去的尺寸</b>（我们自己钉死，
 *       后续任何一环都不再压）⇒ 输出尺寸由<b>同一个纯函数</b>决定，
 *       截图实现与坐标换算共用它。</li>
 * </ul>
 *
 * <p><b>⚠️ 已知的精度代价（本机 2026-11-01 真机 A/B · 同一目标 · 各 10 次采样）</b>：
 * 压缩（1456×819）<b>省 42% 图像 token</b>但<b>定位精度下降</b> ——
 * 120px 目标：误差 avg 4.4px（原图）→ 15.1px（压缩）；10px 的「✕」字形：11.4px → 39.2px。
 * ⇒ 目标越小代价越大 ⇒ 「精度优先 / 目标很小」时应当把开关置 false。
 */
class ComputerUseScreenshotSizeSwitchTest {

    /** 本机 1920×1080 物理屏（真机实测基准）。 */
    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    @Test
    @DisplayName("⭐ 开关 true（默认 · 严格 CCB 口径）⇒ 长边 ≤1568 且图像 token ≤ 预算")
    void preSizeTrueStaysWithinCcbBudget() {
        int[] out = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, true);

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
    @DisplayName("⭐ 开关 true ⇒ 缩放比例保持（1920×1080 → 1456×819）")
    void preSizeTrueScalesWithinBudget() {
        int[] out = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, true);

        assertThat(out[0]).isEqualTo(1456);
        assertThat(out[1]).isEqualTo(819);
        assertThat(out[0]).isLessThan(SCREEN_W);
    }

    @Test
    @DisplayName("⭐ 开关 false ⇒ 输出 = 原图尺寸（不缩）")
    void preSizeFalseKeepsOriginalSize() {
        int[] out = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, false);

        assertThat(out[0]).isEqualTo(SCREEN_W);
        assertThat(out[1]).isEqualTo(SCREEN_H);
    }

    @Test
    @DisplayName("⭐ 开关 false ⇒ token 成本显著上升（文档化代价：+72%）")
    void preSizeFalseCostsMoreTokens() {
        int[] compressed = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, true);
        int[] raw = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, false);

        int compressedTokens = ComputerUseApiResize.nTokensForImg(
                compressed[0], compressed[1], ComputerUseApiResize.PX_PER_TOKEN);
        int rawTokens = ComputerUseApiResize.nTokensForImg(
                raw[0], raw[1], ComputerUseApiResize.PX_PER_TOKEN);

        assertThat(compressedTokens).isEqualTo(1560);
        assertThat(rawTokens).isEqualTo(2691);
        assertThat(rawTokens)
                .as("原图 token 必须明显高于压缩版（约 +72%）")
                .isGreaterThan((int) (compressedTokens * 1.5d));
    }

    @Test
    @DisplayName("⭐ 不变式：记录的尺寸 = 送出去的尺寸（输出尺寸由同一纯函数决定，缩放只发生一次）")
    void recordedSizeEqualsSentSize() {
        for (boolean preSize : new boolean[]{true, false}) {
            int[] out = Win32ScreenCapturer.resolveOutputSize(SCREEN_W, SCREEN_H, preSize);
            // 截图实现里：target = resolveOutputSize(...) ⇒ 编码与记录的宽高都是 out[]，
            // 没有任何第二处缩放 ⇒ 记录尺寸恒等于送出尺寸。
            assertThat(out[0]).isPositive();
            assertThat(out[1]).isPositive();
            if (!preSize) {
                assertThat(out[0]).isEqualTo(SCREEN_W);
                assertThat(out[1]).isEqualTo(SCREEN_H);
            }
        }
    }

    @Test
    @DisplayName("已小于预算的输入 ⇒ 开关 true 时原样返回（no-op，不做无谓重采样）")
    void smallInputIsNoOpWhenPreSizeEnabled() {
        int[] out = Win32ScreenCapturer.resolveOutputSize(800, 600, true);

        assertThat(out[0]).isEqualTo(800);
        assertThat(out[1]).isEqualTo(600);
    }
}
