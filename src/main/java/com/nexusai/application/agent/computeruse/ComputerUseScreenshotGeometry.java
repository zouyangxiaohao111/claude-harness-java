package com.nexusai.application.agent.computeruse;

/**
 * 截图几何快照 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/executor.ts:12-23}
 * （{@code ScreenshotResult} 的尺寸部分）。
 *
 * <p>CCB 真源：
 * <pre>
 * export interface ScreenshotResult {
 *   base64: string
 *   width: number          // ← 截图输出图像的像素宽（已按 API_RESIZE_PARAMS 缩放）
 *   height: number         // ← 截图输出图像的像素高
 *   displayWidth: number   // ← 截图时刻的显示宽度
 *   displayHeight: number  // ← 截图时刻的显示高度
 *   originX: number        // ← 截图时刻的虚拟屏幕原点 X
 *   originY: number        // ← 截图时刻的虚拟屏幕原点 Y
 *   ...
 * }
 * </pre>
 *
 * <p><b>WHY 是「快照」而不是实时读取</b>：CCB {@code toolCalls.ts:270-284} 注释原文
 * “Uses the display geometry stashed AT CAPTURE TIME, not fresh. / Origin from the same snapshot
 * keeps clicks coherent with the captured display.” —— 多显示器切换 / 用户改显示设置后，
 * 实时几何会与模型实际看到的那张图脱节 ⇒ 必须用截图时刻冻结下来的几何。
 *
 * <p>本仓实现：{@link ComputerUseGeometryStore} 按 sessionId 保存本记录，
 * 截图工具写入、点击工具读取。
 */
public record ComputerUseScreenshotGeometry(
        int width,
        int height,
        int displayWidth,
        int displayHeight,
        int originX,
        int originY) {

    public ComputerUseScreenshotGeometry {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "ComputerUseScreenshotGeometry 图像尺寸非法: " + width + "x" + height);
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException(
                    "ComputerUseScreenshotGeometry 显示尺寸非法: " + displayWidth + "x" + displayHeight);
        }
    }

    /** 图像像素 → 屏幕物理像素 的横比（{@code displayWidth / width}）。 */
    public double scaleX() {
        return (double) displayWidth / width;
    }

    /** 图像像素 → 屏幕物理像素 的纵比（{@code displayHeight / height}）。 */
    public double scaleY() {
        return (double) displayHeight / height;
    }
}
