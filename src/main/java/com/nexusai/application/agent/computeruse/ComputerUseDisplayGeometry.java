package com.nexusai.application.agent.computeruse;

/**
 * 显示器几何 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/executor.ts:1-10}
 * （{@code DisplayGeometry}）。
 *
 * <p>CCB 真源：
 * <pre>
 * export interface DisplayGeometry {
 *   displayId: number
 *   width: number
 *   height: number
 *   scaleFactor: number
 *   originX: number
 *   originY: number
 *   label?: string
 *   isPrimary?: boolean
 * }
 * </pre>
 *
 * <p><b>语义要点</b>：{@code width/height} 是「当前选定显示器」的逻辑宽高，
 * {@code originX/originY} 是它在<b>虚拟屏幕坐标系</b>里的偏移（多屏时非 0）。
 * 本记录用于 {@link ComputerUseCoordinateScaler} 的 {@code normalized_0_100} 态与
 * <b>冷启动兜底分支</b>（无先截图时的退化换算）。
 */
public record ComputerUseDisplayGeometry(
        int displayId,
        int width,
        int height,
        double scaleFactor,
        int originX,
        int originY) {

    public ComputerUseDisplayGeometry {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "ComputerUseDisplayGeometry 尺寸非法: " + width + "x" + height);
        }
        if (!(scaleFactor > 0)) {
            throw new IllegalArgumentException(
                    "ComputerUseDisplayGeometry.scaleFactor 非法: " + scaleFactor);
        }
    }
}
