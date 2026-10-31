package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 坐标换算 · <b>两态统一用「截图时刻」的冻结几何</b>。
 *
 * <h2>CCB 原函数（TS，逐字）· 对照基准</h2>
 * <pre>
 * function scaleCoord(
 *   rawX: number,
 *   rawY: number,
 *   mode: CoordinateMode,
 *   display: DisplayGeometry,
 *   lastScreenshot: ScreenshotResult | undefined,
 *   logger: Logger,
 * ): { x: number; y: number } {
 *   if (mode === 'normalized_0_100') {
 *     // Origin offset targets the selected display in virtual-screen space.
 *     return {
 *       x: Math.round((rawX / 100) * display.width) + display.originX,
 *       y: Math.round((rawY / 100) * display.height) + display.originY,
 *     }
 *   }
 *
 *   // mode === "pixels": model sent image-space pixel coords.
 *   if (lastScreenshot) {
 *     return {
 *       x: Math.round(rawX * (lastScreenshot.displayWidth / lastScreenshot.width)) + lastScreenshot.originX,
 *       y: Math.round(rawY * (lastScreenshot.displayHeight / lastScreenshot.height)) + lastScreenshot.originY,
 *     }
 *   }
 *
 *   // Cold start: model sent pixel coords without having taken a screenshot.
 *   logger.warn('... falling back to /scaleFactor ...')
 *   return {
 *     x: Math.round(rawX / display.scaleFactor) + display.originX,
 *     y: Math.round(rawY / display.scaleFactor) + display.originY,
 *   }
 * }
 * </pre>
 * （CCB {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:253-297}）
 *
 * <h2>⚠️ 本实现<b>修正 CCB 的几何漂移隐患</b>（与 CCB 的差异，如实标注）</h2>
 * <p>CCB 的 {@code normalized_0_100} 分支读的是<b>点击时刻的实时</b> {@code display}，
 * 而 {@code pixels} 分支读<b>截图时刻冻结</b>的 {@code lastScreenshot}
 * ⇒ <b>两态几何来源不同源</b>。后果：<b>截图之后</b>用户切屏 / 改分辨率 / 拔了副屏，
 * {@code normalized_0_100} 态会把坐标换算到<b>另一块屏</b>上（点错屏），
 * 而 {@code pixels} 态不会 ⇒ <b>同一个模型、同一个会话里两态行为不一致</b>。
 *
 * <p>⇒ <b>本仓两态统一用「截图时刻」的快照</b>（{@link ComputerUseScreenshotGeometry}）：
 * <ul>
 *   <li>{@code pixels} 态：{@code raw * (displayWidth / imageWidth)} + 快照 origin
 *       （比例方向<b>不可反</b>：分母是<b>截图输出图像宽</b>，分子是<b>截图时的显示物理宽</b>）；</li>
 *   <li>{@code normalized_0_100} 态：{@code (raw / 100) * 快照 displayWidth/Height} + 快照 origin
 *       （百分比与截图缩放无关 ⇒ 用快照的显示宽高换算即与截图时刻一致）；</li>
 *   <li>两态的 {@code originX/originY} 都取<b>快照</b>的虚拟屏幕原点（多屏时非 0）。</li>
 * </ul>
 *
 * <h2>冷启动（无先截图）⇒ <b>保守不放行</b>（fail loud）</h2>
 * <p>CCB 的冷启动兜底（{@code raw / scaleFactor}）与 {@code /sf} 旧行为同款，
 * 是<b>猜</b>：模型没截图就没有像素参照，而 {@code scaleFactor} 只覆盖系统缩放，
 * <b>不覆盖截图缩放</b>（截图被缩到 ≤1568 时比例 ≠ 1）⇒ 猜出来的坐标大概率偏。
 * <p>⇒ 本仓<b>删除</b>该兜底：无快照 ⇒ 抛
 * {@link ComputerUseGeometryMissingException} ⇒ 点击工具返回模型可见的明确错误，
 * <b>绝不盲点</b>。这条比 CCB 更保守（CCB 会点到一个可能错的位置），是有意的安全取舍。
 *
 * <p><b>WHY 不用实时几何兜底</b>：实时几何只能回答「现在屏幕多大」，回答不了
 * 「模型看的那张图对应哪块屏、多大」⇒ 用它兜底 = 把漂移隐患从一半扩大到全部。
 */
public final class ComputerUseCoordinateScaler {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseCoordinateScaler.class);

    private ComputerUseCoordinateScaler() {
    }

    /**
     * 模型坐标 → 屏幕坐标（虚拟屏幕坐标系）· <b>两态统一用截图时刻快照</b>。
     *
     * @param rawX    模型给的 X（图像像素或百分比，取决于 mode）
     * @param rawY    模型给的 Y
     * @param mode    坐标模式
     * @param snapshot 截图时刻的几何快照（<b>必填</b>；null ⇒ 抛
     *                 {@link ComputerUseGeometryMissingException} ⇒ 冷启动不放行）
     * @return {@code int[]{x, y}} 屏幕坐标（虚拟屏幕坐标系）
     * @throws ComputerUseGeometryMissingException 无先截图快照（冷启动）⇒ fail loud
     */
    public static int[] scaleCoord(double rawX, double rawY,
                                   ComputerUseCoordinateMode mode,
                                   ComputerUseScreenshotGeometry snapshot) {
        if (snapshot == null) {
            throw new ComputerUseGeometryMissingException(
                    "没有找到先前的屏幕截图，无法确定坐标对应的屏幕位置。"
                            + "请先调用截图工具（mcp__computer-use__screenshot）再点击。");
        }
        if (mode == ComputerUseCoordinateMode.NORMALIZED_0_100) {
            // 百分比态：用【截图时刻】的显示宽高 + 快照原点（⛔ 不用点击时刻的实时几何）
            int x = (int) Math.round((rawX / 100.0) * snapshot.displayWidth()) + snapshot.originX();
            int y = (int) Math.round((rawY / 100.0) * snapshot.displayHeight()) + snapshot.originY();
            if (log.isDebugEnabled()) {
                log.debug("[computer-use] normalized_0_100 换算: ({},{}) → ({},{}) · 快照显示 {}x{} origin ({},{})",
                        rawX, rawY, x, y, snapshot.displayWidth(), snapshot.displayHeight(),
                        snapshot.originX(), snapshot.originY());
            }
            return new int[]{x, y};
        }

        // pixels 态：模型给的是截图图像上的像素 ⇒ 比例 = 快照显示宽 / 快照图像宽（乘法，方向不可反）
        int x = (int) Math.round(rawX * snapshot.scaleX()) + snapshot.originX();
        int y = (int) Math.round(rawY * snapshot.scaleY()) + snapshot.originY();
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] pixels 换算: ({},{}) → ({},{}) · 快照显示 {}x{} 图像 {}x{} origin ({},{})",
                    rawX, rawY, x, y, snapshot.displayWidth(), snapshot.displayHeight(),
                    snapshot.width(), snapshot.height(), snapshot.originX(), snapshot.originY());
        }
        return new int[]{x, y};
    }
}
