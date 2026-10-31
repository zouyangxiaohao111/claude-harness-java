package com.nexusai.application.agent.computeruse;

/**
 * grounding 降级路径的一次<b>定位帧</b>（截图 + 模型给的图像像素坐标）。
 *
 * <p><b>WHY 需要它</b>：护栏 ②（点击前落点校验）要拿「<b>首次截图</b>的同一区域」
 * 与「点击前二次截图的同一区域」做像素级比对 ⇒ 必须把<b>首次那张图</b>留下来。
 *
 * <p><b>会话隔离</b>：与 {@link ComputerUseGeometryStore} 同款约定 —— 按 sessionId 分桶，
 * 不做跨会话回落（会话 B 借会话 A 的帧 = 拿别的会话的界面做比对 ⇒ 恒判「变了」）。
 *
 * @param capture    定位时的那次截图（含 JPEG 字节 + 尺寸链 + 几何）
 * @param xPixels    模型给的图像像素 X（护栏 ② 的比对锚点之一）
 * @param yPixels    模型给的图像像素 Y
 * @param target     模型回填的目标名（诊断用）
 */
public record ComputerUseGroundingFrame(
        ComputerUseCaptureResult capture,
        double xPixels,
        double yPixels,
        String target) {

    public ComputerUseGroundingFrame {
        if (capture == null) {
            throw new IllegalArgumentException("ComputerUseGroundingFrame.capture 为空");
        }
    }

    /** 该帧的几何快照（图像尺寸 + 显示尺寸 + 原点）。 */
    public ComputerUseScreenshotGeometry geometry() {
        return capture.geometry();
    }
}
