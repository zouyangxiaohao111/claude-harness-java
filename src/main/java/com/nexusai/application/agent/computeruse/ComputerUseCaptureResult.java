package com.nexusai.application.agent.computeruse;

import java.util.Base64;

/**
 * 一次截屏的结果 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/executor.ts:12-23}
 * （{@code ScreenshotResult}）+ {@code :57}（{@code SCREENSHOT_JPEG_QUALITY = 0.75}）。
 *
 * @param jpegBytes     JPEG 字节（已按 {@link ComputerUseApiResize} 目标尺寸缩放、quality 0.75 编码）
 * @param mediaType     MIME（恒为 {@code image/jpeg} —— CCB 默认 mime）
 * @param imageWidth    截图输出图像宽（像素，≤ 1568）
 * @param imageHeight   截图输出图像高（像素，≤ 1568）
 * @param displayWidth  截图时刻的显示宽（物理像素，= 点击坐标空间宽）
 * @param displayHeight 截图时刻的显示高（物理像素，= 点击坐标空间高）
 * @param originX       截图时刻的虚拟屏幕原点 X
 * @param originY       截图时刻的虚拟屏幕原点 Y
 * @param scaleFactor   Windows 显示缩放比（{@code LOGPIXELSX / 96}）
 * @param logicalWidth  截图时刻的显示逻辑宽（{@code displayWidth / scaleFactor}）
 * @param logicalHeight 截图时刻的显示逻辑高
 */
public record ComputerUseCaptureResult(
        byte[] jpegBytes,
        String mediaType,
        int imageWidth,
        int imageHeight,
        int displayWidth,
        int displayHeight,
        int originX,
        int originY,
        double scaleFactor,
        int logicalWidth,
        int logicalHeight) {

    public ComputerUseCaptureResult {
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("ComputerUseCaptureResult.jpegBytes 为空");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("ComputerUseCaptureResult.mediaType 为空");
        }
    }

    /** base64 载荷（无 {@code data:} 前缀）——对应 CCB {@code ScreenshotResult.base64}。 */
    public String base64() {
        return Base64.getEncoder().encodeToString(jpegBytes);
    }

    /** 截图时刻的几何快照 —— 供 {@link ComputerUseCoordinateScaler} 的 {@code pixels} 态使用。 */
    public ComputerUseScreenshotGeometry geometry() {
        return new ComputerUseScreenshotGeometry(
                imageWidth, imageHeight, displayWidth, displayHeight, originX, originY);
    }
}
