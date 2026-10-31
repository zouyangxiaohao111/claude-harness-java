package com.nexusai.application.agent.computeruse;

/**
 * 一台显示器 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/types.ts} 的 {@code DisplayGeometry}。
 *
 * <p><b>CCB 原形状（TS）</b>：
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
 * <p><b>⚠️ CCB-Windows 的 {@code label} 恒为 undefined</b>
 * （{@code src/utils/computerUse/platforms/win32.ts:452-479} 的 {@code listAll} 只产出
 * {@code width / height / scaleFactor / displayId / isPrimary}，<b>没有 label</b>）
 * ⇒ CCB 的 {@code uniqueDisplayLabels}（{@code toolCalls.ts:2015-2032}）回落到
 * {@code `display ${d.displayId}`} ⇒ <b>Windows 上 CCB 的显示器名就是 "display 0" /
 * "display 1"</b>（macOS 才有 "Built-in Retina Display" 这类友好名）。
 * ⇒ 本仓<b>照此</b>：{@link #label()} 产出 {@code "display N"}。
 *
 * @param displayId   显示器 id（Windows 上是枚举序号）
 * @param label       显示器名（{@code "display N"}）
 * @param width       物理宽（像素）
 * @param height      物理高（像素）
 * @param originX     在虚拟屏幕坐标系里的原点 X（多屏时非 0）
 * @param originY     在虚拟屏幕坐标系里的原点 Y（多屏时非 0）
 * @param isPrimary   是否主显示器
 * @param deviceName  Win32 设备名（{@code \\.\DISPLAY1}；供日志排查用）
 */
public record ComputerUseDisplay(
        int displayId,
        String label,
        int width,
        int height,
        int originX,
        int originY,
        boolean isPrimary,
        String deviceName) {

    public ComputerUseDisplay {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "ComputerUseDisplay 尺寸非法: " + width + "x" + height);
        }
        if (label == null || label.isBlank()) {
            label = "display " + displayId;
        }
        if (deviceName == null) {
            deviceName = "";
        }
    }

    /** 摘要（供日志）。 */
    @Override
    public String toString() {
        return "ComputerUseDisplay{id=" + displayId + ", label=" + label
                + ", size=" + width + "x" + height
                + ", origin=" + originX + "," + originY
                + ", primary=" + isPrimary + ", device=" + deviceName + "}";
    }
}
