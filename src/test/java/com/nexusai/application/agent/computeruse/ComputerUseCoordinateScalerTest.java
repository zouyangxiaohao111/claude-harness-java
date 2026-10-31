package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 坐标换算单测 · <b>[Phase 1d] 两态统一用「截图时刻」的冻结几何</b>。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：模型给的是<b>截图图像上的像素坐标</b>或
 * <b>屏幕百分比</b>，换算错一点点就是点错位置。本测试锁三件事：
 * <ol>
 *   <li><b>比例方向</b>：{@code rawX * (displayWidth / imageWidth)}（分母是<b>截图输出图像宽</b>，
 *       分子是<b>截图时记录的显示物理宽</b>）—— 写成反过来就全错；</li>
 *   <li><b>两态都用截图时刻的快照</b>（<b>不</b>读点击时刻的实时几何）——
 *       截图后切屏 / 改分辨率也不能改坐标空间；</li>
 *   <li><b>冷启动（无快照）⇒ 保守不放行</b>（fail loud），绝不用实时几何猜。</li>
 * </ol>
 * 用例刻意取 <b>比例 ≠ 1</b> 的输入（CCB Windows 不预缩放时比例恰为 1，测不出方向反了的问题）。
 *
 * <p><b>[Phase 1d] 与 CCB 的差异</b>：CCB 的 {@code normalized_0_100} 分支读<b>实时</b>
 * {@code display}，本仓改为读<b>截图快照</b>（修正几何漂移隐患）；CCB 的冷启动兜底
 * （{@code raw / scaleFactor}）本仓<b>删除</b>（改成 fail loud）。
 */
class ComputerUseCoordinateScalerTest {

    /** 截图几何快照：图像 1456x819（CCB 目标尺寸），显示 1920x1080（物理像素），origin (0,0)。 */
    private static final ComputerUseScreenshotGeometry SHOT =
            new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0);

    /** 带虚拟屏幕偏移的快照（多屏：显示器不在 (0,0)）。 */
    private static final ComputerUseScreenshotGeometry SHOT_OFFSET =
            new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, -1920, 0);

    @Test
    @DisplayName("pixels 态：x = round(rawX * (displayWidth / imageWidth)) + originX")
    void pixelsMode_scalesByDisplayOverImageWidth() {
        // 1456/2 = 728 → 728 * (1920/1456) = 960（正好屏幕中点）
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                728, 409.5d, ComputerUseCoordinateMode.PIXELS, SHOT);
        assertThat(p[0]).isEqualTo(960);
        // 纵向：819/2 = 409.5 → 409.5 * (1080/819) = 540
        assertThat(p[1]).isEqualTo(540);
    }

    @Test
    @DisplayName("pixels 态：比例方向不可反（除法而非乘法）")
    void pixelsMode_ratioDirectionIsMultiplicationNotDivision() {
        // 若误写成 rawX / (displayWidth/imageWidth) = 728 / 1.3187 = 552 ≠ 960
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                728, 0, ComputerUseCoordinateMode.PIXELS, SHOT);
        assertThat(p[0]).isEqualTo(960);
    }

    @Test
    @DisplayName("pixels 态：originX/originY 参与相加（虚拟屏幕坐标系）")
    void pixelsMode_addsOrigin() {
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                728, 409.5d, ComputerUseCoordinateMode.PIXELS, SHOT_OFFSET);
        assertThat(p[0]).isEqualTo(960 - 1920);
        assertThat(p[1]).isEqualTo(540);
    }

    @Test
    @DisplayName("pixels 态：比例 = 1 时恒等（截图未缩放的情形）")
    void pixelsMode_ratioOneIsIdentity() {
        ComputerUseScreenshotGeometry unscaled =
                new ComputerUseScreenshotGeometry(1920, 1080, 1920, 1080, 0, 0);
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                123, 456, ComputerUseCoordinateMode.PIXELS, unscaled);
        assertThat(p).containsExactly(123, 456);
    }

    @Test
    @DisplayName("normalized_0_100 态：x = round(rawX/100 * 快照 displayWidth) + 快照 originX")
    void normalizedMode_usesSnapshotDisplayWidth() {
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                50, 25, ComputerUseCoordinateMode.NORMALIZED_0_100, SHOT);
        assertThat(p).containsExactly(960, 270);
    }

    @Test
    @DisplayName("normalized_0_100 态：origin 偏移取【快照】的（不是实时几何的）")
    void normalizedMode_addsSnapshotOrigin() {
        int[] p = ComputerUseCoordinateScaler.scaleCoord(
                50, 25, ComputerUseCoordinateMode.NORMALIZED_0_100, SHOT_OFFSET);
        assertThat(p).containsExactly(960 - 1920, 270);
    }

    @Test
    @DisplayName("两态统一用截图快照：截图后换了显示器也不改坐标空间（修正 CCB 几何漂移）")
    void bothModesIgnoreLiveDisplayGeometry() {
        // 点击时刻的实时几何已经变成另一块屏（2560x1440 @ origin(-2560,0)）——
        // 本仓换算根本不读实时几何 ⇒ 两态结果只由快照决定。
        int[] pixels = ComputerUseCoordinateScaler.scaleCoord(
                728, 409.5d, ComputerUseCoordinateMode.PIXELS, SHOT);
        int[] normalized = ComputerUseCoordinateScaler.scaleCoord(
                50, 50, ComputerUseCoordinateMode.NORMALIZED_0_100, SHOT);

        assertThat(pixels).containsExactly(960, 540);
        assertThat(normalized).containsExactly(960, 540);
    }

    @Test
    @DisplayName("冷启动（无截图快照）⇒ fail loud：两态都不放行，绝不用实时几何猜")
    void coldStartFailsLoud() {
        assertThatThrownBy(() -> ComputerUseCoordinateScaler.scaleCoord(
                100, 200, ComputerUseCoordinateMode.PIXELS, null))
                .isInstanceOf(ComputerUseGeometryMissingException.class)
                .hasMessageContaining("截图");

        assertThatThrownBy(() -> ComputerUseCoordinateScaler.scaleCoord(
                50, 25, ComputerUseCoordinateMode.NORMALIZED_0_100, null))
                .isInstanceOf(ComputerUseGeometryMissingException.class);
    }
}
