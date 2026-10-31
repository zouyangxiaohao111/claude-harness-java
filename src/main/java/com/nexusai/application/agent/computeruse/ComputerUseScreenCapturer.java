package com.nexusai.application.agent.computeruse;

/**
 * 截屏能力接口 · 抽象出「怎么截」以便纯单测（不碰真实桌面）。
 *
 * <p>生产实现：{@link Win32ScreenCapturer}（JNA 直调 user32/gdi32）。
 * 单测实现：注入固定字节 + 固定几何的假实现，验证「模型拿到的图」与「坐标换算」两条纯逻辑。
 */
public interface ComputerUseScreenCapturer {

    /**
     * 截取当前主显示器。
     *
     * @return 截图结果（JPEG 字节 + 尺寸链 + 几何快照）
     * @throws Exception 截屏失败（fail loud，由调用方转成工具错误）
     */
    ComputerUseCaptureResult capture() throws Exception;

    /**
     * 抓取屏幕上的<b>指定矩形</b>（原分辨率，不做 ≤1568 缩放）。
     *
     * <p><b>WHY 需要它</b>：{@code zoom} 工具（CCB {@code tools.ts:220-244}）的语义是
     * 「把上次整屏截图的某个区域<b>放大</b>看」。整屏截图被缩到 ≤1568 长边后小字就糊了
     * ⇒ 放大必须<b>重新按原分辨率抓那一块</b>，而不是从缩略图上裁剪。
     *
     * <p><b>CCB 对照</b>：macOS 的 {@code platforms/darwin.ts:83-86} 走 swift 的
     * {@code captureRegion}（真按区域抓）；Windows 的
     * {@code src/utils/computerUse/platforms/win32.ts:423-430} 在未绑定窗口时
     * <b>回落到整屏截图</b>（忽略区域）⇒ 本仓按 macOS 语义实现（真按区域抓）。
     *
     * @param x      屏幕矩形左上角 X（虚拟屏幕坐标）
     * @param y      屏幕矩形左上角 Y（虚拟屏幕坐标）
     * @param width  矩形宽（物理像素）
     * @param height 矩形高（物理像素）
     * @return 截图结果（原分辨率）
     * @throws Exception 抓取失败（fail loud，由调用方转成工具错误）
     */
    default ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) throws Exception {
        throw new UnsupportedOperationException(
                "[computer-use] 该截屏实现不支持区域抓取（captureRegion）");
    }

    /**
     * 当前显示器几何（点击工具在<b>冷启动兜底分支</b>需要）。
     *
     * @return 当前显示器几何
     */
    ComputerUseDisplayGeometry currentDisplayGeometry();
}
