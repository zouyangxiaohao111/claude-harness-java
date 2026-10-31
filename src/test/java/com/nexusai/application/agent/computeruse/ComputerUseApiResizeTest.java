package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 尺寸链单测 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/imageResize.ts}。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：截图尺寸链决定「模型看到的图像尺寸」。
 * 若长边只做粗裁剪（1568×1014 = 56×37 = 2072 tokens 超预算），API 端会二次缩放到
 * 1372×887 —— 模型在 1372 空间给坐标，而 {@code scaleCoord} 按 1568 空间换算
 * ⇒ <b>约 14% 坐标误差</b>（CCB {@code imageResize.ts:52-56} 原文举例）。
 * 因此本测试锁两件事：① 不变式（长边 ≤1568 且 token 预算 ≤1568）；
 * ② 具体数值（回归锁，防止取整语义漂移）。
 */
class ComputerUseApiResizeTest {

    @Test
    @DisplayName("nTokensForPx = ceil(px/28)（CCB imageResize.ts:33-35）")
    void nTokensForPx_matchesCcbFormula() {
        assertThat(ComputerUseApiResize.nTokensForPx(28, 28)).isEqualTo(1);
        assertThat(ComputerUseApiResize.nTokensForPx(29, 28)).isEqualTo(2);
        assertThat(ComputerUseApiResize.nTokensForPx(1456, 28)).isEqualTo(52);
        assertThat(ComputerUseApiResize.nTokensForPx(819, 28)).isEqualTo(30);
        // CCB 公式是 floor((px-1)/pxPerToken)+1（整数 ceil-div），不是 round
        assertThat(ComputerUseApiResize.nTokensForPx(56, 28)).isEqualTo(2);
    }

    @Test
    @DisplayName("已满足约束的输入原样返回（no-op）")
    void smallImage_isNoOp() {
        int[] target = ComputerUseApiResize.targetImageSize(800, 600);
        assertThat(target).containsExactly(800, 600);
    }

    @Test
    @DisplayName("1920x1080 → 1456x819（长边≤1568 且 tokens=52*30=1560≤1568）")
    void fullHd_downscalesToCcbTarget() {
        int[] target = ComputerUseApiResize.targetImageSize(1920, 1080);
        assertThat(target).containsExactly(1456, 819);
        assertThat(Math.max(target[0], target[1])).isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);
        assertThat(ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN))
                .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_TOKENS);
    }

    @Test
    @DisplayName("4K 与 1080p 同比例 → 同目标尺寸（上限与 token 预算起作用，不是长边粗裁剪）")
    void uhd_sameAspect_sameTarget() {
        assertThat(ComputerUseApiResize.targetImageSize(3840, 2160))
                .containsExactly(1456, 819);
    }

    @Test
    @DisplayName("竖屏转置（1080x1920 → 819x1456）")
    void portrait_isTransposed() {
        assertThat(ComputerUseApiResize.targetImageSize(1080, 1920))
                .containsExactly(819, 1456);
    }

    @Test
    @DisplayName("正方形 1568x1568 → 1092x1092（token 预算比长边更紧）")
    void square_respectsTokenBudget() {
        int[] target = ComputerUseApiResize.targetImageSize(1568, 1568);
        assertThat(target).containsExactly(1092, 1092);
        assertThat(ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN))
                .isEqualTo(39 * 39);
    }

    @Test
    @DisplayName("任意输入都满足「长边≤1568 且 token 预算≤1568」不变式")
    void invariantsHoldForManySizes() {
        int[][] cases = {
                {1920, 1080}, {2560, 1440}, {3840, 2160}, {1366, 768},
                {1080, 1920}, {3440, 1440}, {1568, 1568}, {800, 600}, {1024, 768},
        };
        for (int[] c : cases) {
            int[] target = ComputerUseApiResize.targetImageSize(c[0], c[1]);
            assertThat(Math.max(target[0], target[1]))
                    .as("长边上限 · 输入 %dx%d", c[0], c[1])
                    .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_PX);
            assertThat(ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN))
                    .as("token 预算 · 输入 %dx%d", c[0], c[1])
                    .isLessThanOrEqualTo(ComputerUseApiResize.MAX_TARGET_TOKENS);
            // 宽高比保持（容差 1px 取整）
            double before = (double) c[0] / c[1];
            double after = (double) target[0] / target[1];
            assertThat(Math.abs(before - after))
                    .as("宽高比保持 · 输入 %dx%d", c[0], c[1])
                    .isLessThan(0.01d);
        }
    }
}
