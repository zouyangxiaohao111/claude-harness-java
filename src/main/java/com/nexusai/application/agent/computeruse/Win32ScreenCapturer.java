package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;

/**
 * Win32 截屏实现（JNA 直调 user32/gdi32）· computer-use Phase 0。
 *
 * <p><b>为什么选 JNA BitBlt 而不是 {@code java.awt.Robot.createScreenCapture}</b>：
 * <ul>
 *   <li><b>不引入 AWT 依赖</b>：后端是 Spring Boot 服务，{@code Robot} 需要图形环境，
 *       headless 部署会抛 {@code HeadlessException}；JNA 只碰 kernel32/user32/gdi32。</li>
 *   <li><b>与 CCB 路线同构</b>：CCB 的 Windows 截屏走 GDI（{@code src/utils/computerUse/win32/bridge.py}
 *       的 {@code screenshot_full} 用 mss、{@code screenshot_window} 用 {@code PrintWindow}+{@code BitBlt}
 *       回落），本实现走同族 GDI 路径。</li>
 *   <li><b>可控</b>：能显式控制 DIB 格式（32bpp top-down）、缩放目标与 JPEG 质量。</li>
 * </ul>
 *
 * <h2>预缩放开关（关键取舍，见报告「偏离 CCB 的地方」）</h2>
 * <p>CCB 的 <b>Windows 路径不预缩放</b>：{@code targetImageSize} 只出现在
 * {@code src/utils/computerUse/executor.ts:40,67}（macOS/Swift 执行器），
 * {@code src/utils/computerUse/executorCrossPlatform.ts} <b>未 import imageResize</b>
 * ⇒ Windows 截图保持原始物理像素尺寸。
 * <p>但 CCB 自己在 {@code src/utils/computerUse/executor.ts:468-472} 把「预缩放」写成
 * <b>坐标链的不变式</b>：
 * <pre>
 * Pre-size to `targetImageSize` output so the API transcoder's early-return
 * fires — no server-side resize, `scaleCoord` stays coherent.
 * </pre>
 * 也就是说：<b>模型看到的图像尺寸必须等于 {@code ScreenshotResult.width/height}</b>，
 * 否则模型给的坐标落在被服务端二次缩放过的空间里，{@code scaleCoord} 的比例就错了。
 * <p>本实现默认 <b>预缩放 = true</b>（即采用 macOS 的预缩放语义），
 * 因为本 PoC 的成败标准正是「模型给的坐标能真点到」；把它改成 false 即逐字复现 CCB 的
 * Windows 行为（原始物理像素、不缩放），便于做 A/B 对照。
 * <br>⚠️ 开关的<b>实测代价</b>见 {@link #preSizeToApiTarget} 字段注释（压缩省 42% token，
 * 但同目标定位误差 avg 4.4px → 15.1px）。
 *
 * <h2>坐标空间自洽性</h2>
 * <p>截图像素与点击坐标必须落在<b>同一个</b>坐标系里。本实现抓取主显示器在虚拟屏幕坐标中的
 * {@code (0,0)-(W,H)} 区域（Windows 保证主显示器原点为 (0,0)），点击也用 {@code SetCursorPos}
 * 的虚拟屏幕坐标 ⇒ 比例 {@code displayWidth / imageWidth} 精确成立。
 */
public final class Win32ScreenCapturer implements ComputerUseScreenCapturer {

    private static final Logger log = LoggerFactory.getLogger(Win32ScreenCapturer.class);

    /** CCB {@code src/utils/computerUse/executor.ts:57} {@code SCREENSHOT_JPEG_QUALITY = 0.75}。 */
    public static final float SCREENSHOT_JPEG_QUALITY = 0.75f;

    /** CCB 默认 mime（{@code wrapper.tsx:309} {@code item.mimeType ?? detectImageFormatFromBase64(...)}）。 */
    public static final String MEDIA_TYPE = "image/jpeg";

    /**
     * 是否把截图预缩放到 {@link ComputerUseApiResize#targetImageSize} 的输出尺寸（<b>可配</b>）。
     *
     * <p>{@code true}（<b>默认</b>）= CCB macOS 语义（{@code executor.ts:468-492}）——
     * 模型看到的尺寸 == 记录的尺寸，{@code scaleCoord} 的比例成立。
     * <br>{@code false} = CCB Windows 语义（{@code executorCrossPlatform.ts} 不 import imageResize）——
     * 输出原始物理像素。
     *
     * <p><b>⚠️ 实测代价（本机 2026-11-01 真机 A/B · 同一个目标 · 各 10 次采样）</b>：
     * <ul>
     *   <li><b>压缩</b>（1920×1080 → 1456×819）：图像 token 由 <b>2691 降到 1560（省 42%）</b>，
     *       但<b>定位精度下降</b>；</li>
     *   <li><b>原图</b>（1920×1080）：图像 token <b>2691（+72%）</b>，定位<b>更准</b>。</li>
     * </ul>
     * <p><b>两组实测数据</b>（同一目标、同一真值）：
     * <ul>
     *   <li><b>120px 目标</b>（用户 2026-11-01 实测）：误差 <b>avg 4.4px（原图）→ 15.1px（压缩）</b>
     *       = <b>3.4×</b>；</li>
     *   <li><b>10px 的「✕」字形</b>（本批 2026-11-01 实测 · 记事本 caption 关闭按钮）：
     *       误差 <b>avg 11.4px（原图，命中正确按钮的 6 次）→ 39.2px（压缩，10/10 命中）</b>
     *       ⇒ <b>目标越小，压缩的精度代价越大</b>。</li>
     * </ul>
     * ⇒ <b>默认值 true（压缩）</b> = <b>严格 CCB 口径</b>（省 42% token）；
     * <b>若「定位精度优先 / 目标很小 / token 不敏感」⇒ 置 false 发原图</b>。
     *
     * <p><b>⚠️ 不变式（无论开关如何都成立）</b>：<b>我们自己钉死尺寸、后续任何一环都不再压</b> ——
     * 本类记录的图像尺寸（{@code imageWidth/imageHeight}）<b>恒等于</b>实际编码并送给模型的图像尺寸。
     * <br>⚠️ 但要注意：置 false 时送出去的是 1920×1080（&gt; 1568 长边）⇒ <b>会越过 CCB 的 token 预算</b>
     * ⇒ 某些上游（如 Anthropic）会<b>服务端二次缩放</b> ⇒ 那时坐标空间会与记录尺寸脱节。
     * 本仓库实测的 {@code deepseek-flash} <b>不自带压缩</b>（两变体坐标各自落在正确空间）⇒ 该开关对它安全。
     */
    private final boolean preSizeToApiTarget;

    private final Win32Native.User32 user32 = Win32Native.User32.INSTANCE;
    private final Win32Native.Gdi32 gdi32 = Win32Native.Gdi32.INSTANCE;

    /** DPI 感知是否已生效（静态一次）。 */
    private static volatile Boolean dpiAware;

    /** 预缩放 = true（严格 CCB 口径，默认）。 */
    public Win32ScreenCapturer() {
        this(true);
    }

    /**
     * @param preSizeToApiTarget 是否预缩放到 {@link ComputerUseApiResize#targetImageSize} 的输出尺寸
     *                           （true = 严格 CCB 口径；false = 发原图，token 成本显著上升）
     */
    public Win32ScreenCapturer(boolean preSizeToApiTarget) {
        this.preSizeToApiTarget = preSizeToApiTarget;
        Win32Native.assertStructLayout();
        ensureDpiAwareness();
    }

    /**
     * @return 是否预缩放（true = 缩到 token 预算内的最大解；false = 原图）
     */
    public boolean preSizeToApiTarget() {
        return preSizeToApiTarget;
    }

    /**
     * 输出尺寸决策（<b>纯函数</b> · 可单测）。
     *
     * <p>⭐ 不变式落点：<b>这里定出来的尺寸，就是记录下来、也送给模型的那张图的尺寸</b>
     * ⇒ 后续任何一环都不得再压。
     * <br>⭐ <b>Windows 与 macOS 共用同一实现</b>（{@link ComputerUseApiResize#resolveOutputSize}）
     * ⇒ 两平台的尺寸语义天然一致（同一个开关、同一个不变式）。
     *
     * @param physW             捕获区物理宽
     * @param physH             捕获区物理高
     * @param preSizeToApiTarget 是否预缩放
     * @return {@code int[]{输出宽, 输出高}}
     */
    static int[] resolveOutputSize(int physW, int physH, boolean preSizeToApiTarget) {
        return ComputerUseApiResize.resolveOutputSize(physW, physH, preSizeToApiTarget);
    }

    /**
     * 关掉预缩放后图像 token 的增幅（百分比 · 日志用）。
     *
     * @param physW 捕获区物理宽
     * @param physH 捕获区物理高
     * @return 增幅百分比（原图为基准）；无法计算 ⇒ 0
     */
    private static int extraTokenPercent(int physW, int physH) {
        int[] compressed = ComputerUseApiResize.targetImageSize(physW, physH);
        int compressedTokens = ComputerUseApiResize.nTokensForImg(
                compressed[0], compressed[1], ComputerUseApiResize.PX_PER_TOKEN);
        int rawTokens = ComputerUseApiResize.nTokensForImg(physW, physH, ComputerUseApiResize.PX_PER_TOKEN);
        if (compressedTokens <= 0) {
            return 0;
        }
        return (int) Math.round((rawTokens - compressedTokens) * 100.0d / compressedTokens);
    }

    /**
     * 尽力取得进程 DPI 感知（PER_MONITOR_AWARE_V2）· 让 {@code GetSystemMetrics(SM_CXSCREEN)}
     * 与 {@code SetCursorPos} 都工作在<b>物理像素</b>空间。
     *
     * <p>失败不致命：Windows 会对 DPI 感知与非感知进程<b>一致地</b>虚拟化
     * {@code GetSystemMetrics} 与 {@code SetCursorPos}，坐标换算依然自洽（只是图像分辨率偏低）。
     */
    private static void ensureDpiAwareness() {
        if (dpiAware != null) {
            return;
        }
        synchronized (Win32ScreenCapturer.class) {
            if (dpiAware != null) {
                return;
            }
            boolean ok;
            try {
                ok = Win32Native.User32.INSTANCE
                        .SetProcessDpiAwarenessContext(Win32Native.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) != 0;
                if (!ok) {
                    ok = Win32Native.User32.INSTANCE.SetProcessDPIAware();
                }
            } catch (Throwable t) {
                ok = false;
                log.warn("[computer-use] 设置进程 DPI 感知失败（不影响坐标自洽，仅影响图像分辨率）: {}", t.getMessage());
            }
            dpiAware = ok;
            log.info("[computer-use] 进程 DPI 感知设置结果={}（true=SM_CXSCREEN 与 SetCursorPos 均为物理像素）", ok);
        }
    }

    @Override
    public ComputerUseCaptureResult capture() throws Exception {
        int captureW = user32.GetSystemMetrics(Win32Native.SM_CXSCREEN);
        int captureH = user32.GetSystemMetrics(Win32Native.SM_CYSCREEN);
        if (captureW <= 0 || captureH <= 0) {
            throw new IllegalStateException(
                    "[computer-use] GetSystemMetrics 返回非法屏幕尺寸: " + captureW + "x" + captureH);
        }

        double scaleFactor = readScaleFactor();
        int logicalW = (int) Math.round(captureW / scaleFactor);
        int logicalH = (int) Math.round(captureH / scaleFactor);
        int physW = (int) Math.round(logicalW * scaleFactor);
        int physH = (int) Math.round(logicalH * scaleFactor);
        if (physW != captureW || physH != captureH) {
            log.warn("[computer-use] logical({}x{})×scaleFactor({}) 推出的物理尺寸 {}x{} 与实际屏幕 {}x{} 不一致"
                            + "（DPI 虚拟化）⇒ 物理尺寸收敛回实际抓取尺寸，保证「截图像素 == 点击坐标空间」自洽",
                    logicalW, logicalH, scaleFactor, physW, physH, captureW, captureH);
            physW = captureW;
            physH = captureH;
        }

        BufferedImage shot = bitBltScreen(physW, physH);
        return finish(shot, physW, physH, 0, 0, scaleFactor, logicalW, logicalH, "整屏");
    }

    /**
     * 窗口捕获 · 对齐 CCB {@code src/utils/computerUse/win32/bridge.py:91-169}
     * （{@code GetWindowRect} → {@code PrintWindow(hwnd, hdc, 2)}（PW_RENDERFULLCONTENT）
     * → 失败回落 {@code BitBlt} SRCCOPY → {@code GetDIBits}（32bpp top-down）→ JPEG quality 75）。
     *
     * <p>PoC 阶段<b>未接线为工具</b>（工具面只有 screenshot / left_click，且无「绑定窗口」语义），
     * 此方法用于 Phase 1 的窗口类工具与本轮真机验证取证。
     *
     * @param hwnd 目标窗口句柄
     * @return 截图结果（{@code displayWidth/Height} = 窗口在虚拟屏幕坐标中的尺寸）
     * @throws Exception 捕获失败
     */
    public ComputerUseCaptureResult captureWindow(long hwnd) throws Exception {
        WinDef.HWND handle = new WinDef.HWND(Pointer.createConstant(hwnd));
        if (!user32.IsWindow(handle)) {
            throw new IllegalStateException("[computer-use] 窗口句柄无效: " + hwnd);
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!user32.GetWindowRect(handle, rect)) {
            throw new IllegalStateException("[computer-use] GetWindowRect 失败: " + hwnd);
        }
        int w = rect.right - rect.left;
        int h = rect.bottom - rect.top;
        if (w <= 0 || h <= 0) {
            throw new IllegalStateException("[computer-use] 窗口尺寸非法: " + w + "x" + h);
        }

        boolean wasMinimized = user32.IsIconic(handle);
        if (wasMinimized) {
            user32.ShowWindow(handle, Win32Native.SW_SHOWNOACTIVATE);
            Thread.sleep(100L);
            user32.GetWindowRect(handle, rect);
            w = rect.right - rect.left;
            h = rect.bottom - rect.top;
        }

        try {
            BufferedImage img = printWindow(handle, w, h);
            double scaleFactor = readScaleFactor();
            int logicalW = Math.max(1, (int) Math.round(w / scaleFactor));
            int logicalH = Math.max(1, (int) Math.round(h / scaleFactor));
            return finish(img, w, h, rect.left, rect.top, scaleFactor, logicalW, logicalH, "窗口");
        } finally {
            if (wasMinimized) {
                user32.ShowWindow(handle, Win32Native.SW_SHOWMINNOACTIVE);
            }
        }
    }

    /**
     * 抓取屏幕上的指定矩形（<b>原分辨率</b>，不做 ≤1568 缩放）。
     *
     * <p><b>WHY 原分辨率</b>：{@code zoom} 工具要的是「把上次整屏缩略图里看不清的小字
     * 放大看」⇒ 必须重新按原始像素抓那一块（从缩略图裁剪只会得到更多糊的像素）。
     *
     * <p>实现：用 {@code GetDC(null)} 拿整个虚拟桌面的 DC ⇒ {@code BitBlt} 从
     * {@code (x, y)} 处取 {@code width × height} ⇒ 原生像素。
     *
     * @param x      屏幕矩形左上角 X（虚拟屏幕坐标）
     * @param y      屏幕矩形左上角 Y（虚拟屏幕坐标）
     * @param width  矩形宽（物理像素）
     * @param height 矩形高（物理像素）
     * @return 截图结果（原分辨率）
     */
    @Override
    public ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) throws Exception {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "[computer-use] captureRegion 尺寸非法: " + width + "x" + height);
        }
        BufferedImage img = bitBltRegion(x, y, width, height);
        byte[] jpeg = encodeJpeg(img);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 区域截图完成: ({},{}) {}x{} · JPEG {} 字节",
                    x, y, width, height, jpeg.length);
        }
        // 区域截图的「显示尺寸」= 区域自身的物理像素尺寸（原分辨率，未缩放）
        return new ComputerUseCaptureResult(jpeg, MEDIA_TYPE, width, height, width, height,
                x, y, readScaleFactor(), width, height);
    }

    /**
     * GDI {@code BitBlt} 抓屏幕矩形 → {@link BufferedImage}（32bpp top-down）。
     *
     * @param x      源矩形左上角 X
     * @param y      源矩形左上角 Y
     * @param width  矩形宽
     * @param height 矩形高
     * @return RGB 图像
     */
    private BufferedImage bitBltRegion(int x, int y, int width, int height) {
        WinDef.HDC screenDc = user32.GetDC(null);
        if (screenDc == null) {
            throw new IllegalStateException("[computer-use] GetDC(NULL) 返回空（无桌面会话？）");
        }
        WinDef.HDC memDc = null;
        WinDef.HBITMAP bitmap = null;
        WinNT.HANDLE oldObject = null;
        try {
            memDc = gdi32.CreateCompatibleDC(screenDc);
            bitmap = gdi32.CreateCompatibleBitmap(screenDc, width, height);
            if (memDc == null || bitmap == null) {
                throw new IllegalStateException(String.format(
                        "[computer-use] 创建 GDI 兼容 DC/Bitmap 失败: dc=%s bitmap=%s", memDc, bitmap));
            }
            oldObject = gdi32.SelectObject(memDc, bitmap);
            boolean ok = gdi32.BitBlt(memDc, 0, 0, width, height, screenDc, x, y, Win32Native.SRCCOPY);
            if (!ok) {
                throw new IllegalStateException(
                        "[computer-use] BitBlt 区域截图失败（返回 false）: (" + x + "," + y + ") " + width + "x" + height);
            }
            return readDIBits(memDc, bitmap, width, height);
        } finally {
            if (memDc != null) {
                if (oldObject != null) {
                    gdi32.SelectObject(memDc, oldObject);
                }
                gdi32.DeleteDC(memDc);
            }
            if (bitmap != null) {
                gdi32.DeleteObject(bitmap);
            }
            user32.ReleaseDC(null, screenDc);
        }
    }

    @Override
    public ComputerUseDisplayGeometry currentDisplayGeometry() {
        int captureW = user32.GetSystemMetrics(Win32Native.SM_CXSCREEN);
        int captureH = user32.GetSystemMetrics(Win32Native.SM_CYSCREEN);
        double scaleFactor = readScaleFactor();
        int logicalW = Math.max(1, (int) Math.round(captureW / scaleFactor));
        int logicalH = Math.max(1, (int) Math.round(captureH / scaleFactor));
        // 主显示器在虚拟屏幕坐标中原点恒为 (0,0)（Windows 保证），故 origin = (0,0)。
        return new ComputerUseDisplayGeometry(0, logicalW, logicalH, scaleFactor, 0, 0);
    }

    /**
     * 公共收尾：缩放（按开关）→ JPEG 编码 → 组装结果。
     *
     * @param img          原始捕获图（物理像素）
     * @param physW        捕获区物理宽
     * @param physH        捕获区物理高
     * @param originX      捕获区在虚拟屏幕坐标中的 X
     * @param originY      捕获区在虚拟屏幕坐标中的 Y
     * @param scaleFactor  显示缩放比
     * @param logicalW     逻辑宽
     * @param logicalH     逻辑高
     * @param what         描述（日志用）
     * @return 截图结果
     */
    private ComputerUseCaptureResult finish(BufferedImage img, int physW, int physH,
                                            int originX, int originY,
                                            double scaleFactor, int logicalW, int logicalH,
                                            String what) throws Exception {
        int[] target = resolveOutputSize(physW, physH, preSizeToApiTarget);
        BufferedImage out = (target[0] == img.getWidth() && target[1] == img.getHeight())
                ? img
                : scaleTo(img, target[0], target[1]);
        byte[] jpeg = encodeJpeg(out);

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {}捕获完成: 捕获区 {}x{}（origin {}x{}）· scaleFactor={} · 图像 {}x{} · "
                            + "JPEG {} 字节 · mediaType={} · 预缩放={} · 图像token≈{}",
                    what, physW, physH, originX, originY, scaleFactor, target[0], target[1],
                    jpeg.length, MEDIA_TYPE, preSizeToApiTarget,
                    ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN));
        }
        if (!preSizeToApiTarget && log.isWarnEnabled()) {
            log.warn("[computer-use] {}捕获: 预缩放已关闭 ⇒ 发原图 {}x{}（图像token≈{}，比压缩版多约 {}%）"
                            + "· ⛔ token 成本显著上升，且依赖上游【不】做服务端二次缩放"
                            + "（本机实测 deepseek-flash 不压缩；Anthropic 系会压 ⇒ 坐标空间会失配）",
                    what, target[0], target[1],
                    ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN),
                    extraTokenPercent(physW, physH));
        }

        return new ComputerUseCaptureResult(
                jpeg, MEDIA_TYPE, target[0], target[1], physW, physH, originX, originY,
                scaleFactor, logicalW, logicalH);
    }

    /** {@code GetDeviceCaps(LOGPIXELSX) / 96} —— Windows 显示缩放比（96 → 1.0，192 → 2.0）。 */
    private double readScaleFactor() {
        WinDef.HDC dc = user32.GetDC(null);
        try {
            int dpi = gdi32.GetDeviceCaps(dc, Win32Native.LOGPIXELSX);
            if (dpi <= 0) {
                return 1.0d;
            }
            return dpi / 96.0d;
        } finally {
            user32.ReleaseDC(null, dc);
        }
    }

    /**
     * GDI {@code BitBlt} 抓屏 → {@link BufferedImage}（32bpp top-down）。
     *
     * @param width  抓取宽（= 物理像素宽）
     * @param height 抓取高
     * @return RGB 图像
     */
    private BufferedImage bitBltScreen(int width, int height) {
        WinDef.HDC screenDc = user32.GetDC(null);
        if (screenDc == null) {
            throw new IllegalStateException("[computer-use] GetDC(NULL) 返回空（无桌面会话？）");
        }
        WinDef.HDC memDc = null;
        WinDef.HBITMAP bitmap = null;
        WinNT.HANDLE oldObject = null;
        try {
            memDc = gdi32.CreateCompatibleDC(screenDc);
            bitmap = gdi32.CreateCompatibleBitmap(screenDc, width, height);
            if (memDc == null || bitmap == null) {
                throw new IllegalStateException(String.format(
                        "[computer-use] 创建 GDI 兼容 DC/Bitmap 失败: dc=%s bitmap=%s", memDc, bitmap));
            }
            oldObject = gdi32.SelectObject(memDc, bitmap);
            boolean ok = gdi32.BitBlt(memDc, 0, 0, width, height, screenDc, 0, 0, Win32Native.SRCCOPY);
            if (!ok) {
                throw new IllegalStateException("[computer-use] BitBlt 截屏失败（返回 false）");
            }
            return readDIBits(memDc, bitmap, width, height);
        } finally {
            if (memDc != null) {
                if (oldObject != null) {
                    gdi32.SelectObject(memDc, oldObject);
                }
                gdi32.DeleteDC(memDc);
            }
            if (bitmap != null) {
                gdi32.DeleteObject(bitmap);
            }
            user32.ReleaseDC(null, screenDc);
        }
    }

    /**
     * 窗口捕获 · {@code PrintWindow(PW_RENDERFULLCONTENT)}，失败回落 {@code BitBlt} SRCCOPY。
     *
     * @param hwnd   目标窗口
     * @param width  窗口宽
     * @param height 窗口高
     * @return RGB 图像
     */
    private BufferedImage printWindow(WinDef.HWND hwnd, int width, int height) {
        WinDef.HDC windowDc = user32.GetDC(hwnd);
        if (windowDc == null) {
            throw new IllegalStateException("[computer-use] GetDC(hwnd) 返回空");
        }
        WinDef.HDC memDc = null;
        WinDef.HBITMAP bitmap = null;
        WinNT.HANDLE oldObject = null;
        try {
            memDc = gdi32.CreateCompatibleDC(windowDc);
            bitmap = gdi32.CreateCompatibleBitmap(windowDc, width, height);
            if (memDc == null || bitmap == null) {
                throw new IllegalStateException("[computer-use] 创建 GDI 兼容 DC/Bitmap 失败（窗口捕获）");
            }
            oldObject = gdi32.SelectObject(memDc, bitmap);

            boolean printed = user32.PrintWindow(hwnd, memDc, Win32Native.PW_RENDERFULLCONTENT);
            if (!printed) {
                log.warn("[computer-use] PrintWindow(PW_RENDERFULLCONTENT) 失败，回落 BitBlt SRCCOPY");
                boolean copied = gdi32.BitBlt(memDc, 0, 0, width, height, windowDc, 0, 0, Win32Native.SRCCOPY);
                if (!copied) {
                    throw new IllegalStateException("[computer-use] 窗口捕获回落 BitBlt 也失败");
                }
            }
            return readDIBits(memDc, bitmap, width, height);
        } finally {
            if (memDc != null) {
                if (oldObject != null) {
                    gdi32.SelectObject(memDc, oldObject);
                }
                gdi32.DeleteDC(memDc);
            }
            if (bitmap != null) {
                gdi32.DeleteObject(bitmap);
            }
            user32.ReleaseDC(hwnd, windowDc);
        }
    }

    /** {@code GetDIBits} 取 32bpp top-down 像素 → {@link BufferedImage}。 */
    private BufferedImage readDIBits(WinDef.HDC dc, WinDef.HBITMAP bitmap, int width, int height) {
        Win32Native.BITMAPINFOHEADER header = new Win32Native.BITMAPINFOHEADER();
        header.biSize = header.size();
        header.biWidth = width;
        header.biHeight = -height; // 负值 = top-down，省掉一次翻转
        header.biPlanes = 1;
        header.biBitCount = 32;
        header.biCompression = 0; // BI_RGB
        header.write();

        byte[] raw = new byte[width * height * 4];
        int lines = gdi32.GetDIBits(dc, bitmap, 0, height, raw, header, Win32Native.DIB_RGB_COLORS);
        if (lines == 0) {
            throw new IllegalStateException("[computer-use] GetDIBits 取像素失败（返回 0 行）");
        }
        return toRgbImage(raw, width, height);
    }

    /** BGRA(top-down) 字节 → {@link BufferedImage}（TYPE_INT_RGB）。 */
    private static BufferedImage toRgbImage(byte[] raw, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int count = width * height;
        for (int i = 0; i < count; i++) {
            int b = buf.get() & 0xFF;
            int g = buf.get() & 0xFF;
            int r = buf.get() & 0xFF;
            buf.get(); // alpha 丢弃（32bpp BI_RGB 的第 4 字节未定义）
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return img;
    }

    /** 双线性缩放到目标尺寸（CCB 用 Pillow resize；本仓用 Graphics2D bilinear）。 */
    private static BufferedImage scaleTo(BufferedImage src, int targetW, int targetH) {
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** JPEG 编码 · 质量 {@link #SCREENSHOT_JPEG_QUALITY}（CCB {@code executor.ts:57}）。 */
    private static byte[] encodeJpeg(BufferedImage img) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("[computer-use] 找不到 JPEG ImageWriter（ImageIO 插件缺失）");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(SCREENSHOT_JPEG_QUALITY);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
