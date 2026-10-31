package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS 截屏实现（{@code screencapture -x}）· computer-use 的取像底座。
 *
 * <h2>CCB 对照（{@code packages/@ant/computer-use-swift/src/backends/darwin.ts:270-303}）</h2>
 * <pre>
 * async function captureScreenToBase64(args) {
 *   const tmpFile = join(tmpdir(), `cu-screenshot-${Date.now()}.png`)
 *   const proc = Bun.spawn(['screencapture', ...args, tmpFile], ...)
 *   await proc.exited
 *   const buf = readFileSync(tmpFile)
 *   const base64 = buf.toString('base64')
 *   const width = buf.readUInt32BE(16)     // PNG IHDR 宽
 *   const height = buf.readUInt32BE(20)    // PNG IHDR 高
 *   return { base64, width, height }
 * }
 *
 * captureExcluding(_allowedBundleIds, ...) { const args = ['-x']; ... }   // ← 白名单首参未使用
 * captureRegion(_allowedBundleIds, x, y, w, h, ...) { const args = ['-x', '-R', `${x},${y},${w},${h}`] }
 * </pre>
 * ⇒ 本仓逐字沿用：整屏 = {@code screencapture -x <file>}；
 * 区域 = {@code screencapture -x -R x,y,w,h <file>}。
 *
 * <h2>⚠️ 截图白名单过滤：<b>不做</b>（与 CCB 一致）</h2>
 * <p>CCB 的 macOS {@code captureExcluding(_allowedBundleIds, …)} 的<b>首参未被使用</b>
 * ⇒ <b>CCB 的 macOS 实际不做白名单过滤</b>（它只在<b>原生 Swift 模块</b>里有合成器级过滤）。
 * <br>⇒ 本仓<b>同样不做</b>，且工具描述与实现一致
 * （{@link ComputerUseScreenshotTool} 的描述写的是「screenshots are NOT filtered」）。
 * ⇒ {@link ComputerUseRequestAccessResult#SCREENSHOT_FILTERING} 恒为 {@code "none"}。
 *
 * <h2>尺寸链</h2>
 * <ul>
 *   <li>整屏：PNG 原生像素 → 按 {@link #resolveOutputSize(int, int, boolean)} 决定输出尺寸
 *       （开关 true ⇒ 按 {@link ComputerUseApiResize#targetImageSize(int, int)}
 *       缩到长边 ≤1568；开关 false ⇒ 原图）⇒ <b>模型看到的图像尺寸 == 记录的 imageWidth/Height</b>
 *       （与 Windows 侧同款不变式 · <b>同一个开关</b>）。</li>
 *   <li>区域（zoom）：<b>原分辨率</b>（不缩到 1568）—— 与 {@link Win32ScreenCapturer#captureRegion}
 *       同款（zoom 的语义就是「看清小字」）。</li>
 * </ul>
 *
 * <h2>预缩放开关（2026-11-01 · 与 Windows 侧并轨）</h2>
 * <p>开关 = {@code nexusai.computer-use.screenshot-pre-size-to-api-target}（默认 <b>true</b>）。
 * <br><b>WHY 要并轨</b>：本仓的截图尺寸开关原先<b>只有 Windows 侧接了</b>，
 * macOS 侧<b>恒预缩放</b> ⇒ 同一个配置在两个平台上语义不同（换平台 ⇒ token 成本与坐标空间
 * 悄悄变样）⇒ 两边统一走 {@link ComputerUseApiResize#resolveOutputSize(int, int, boolean)}。
 * <br>⚠️ <b>运行时未验证</b>：本机是 Windows ⇒ macOS 侧只做到「<b>编译通过 + 纯单测</b>」
 * （假执行器断言尺寸计算），<b>真实 macOS 上的截图尺寸行为未在真机验证过</b>。
 *
 * <h2>⚠️ 单位</h2>
 * <p>本仓工具体系的坐标空间是<b>屏幕像素</b>；{@code screencapture -R} 的矩形用的是
 * Quartz 全局坐标（<b>点</b>）⇒ 区域抓取前做像素 → 点换算。
 * <br>⚠️ <b>需真机复核</b>：{@code screencapture -R} 的矩形单位（点 / 像素）在 Apple 文档里
 * 表述含糊；本仓按「与 {@code CGDisplayBounds} 同单位（点）」实现。
 */
public final class MacOsScreenCapturer implements ComputerUseScreenCapturer {

    private static final Logger log = LoggerFactory.getLogger(MacOsScreenCapturer.class);

    /** CCB 默认 mime（{@code wrapper.tsx:309}）。 */
    public static final String MEDIA_TYPE = "image/jpeg";

    private final ComputerUseProcessRunner runner;
    private final MacOsDisplays displays;
    private final MacOsTccChecker tcc;

    /**
     * 是否把整屏截图预缩放到 {@link ComputerUseApiResize#targetImageSize(int, int)} 的输出尺寸
     * （<b>可配</b> · 与 Windows 侧同一个开关）。
     *
     * <p>{@code true}（<b>默认</b>）= 缩到长边 ≤1568 且图像 token ≤1568
     * ⇒ 模型看到的尺寸 == 记录的尺寸（坐标链不变式成立）。
     * <br>{@code false} = 发原图（token 成本显著上升；定位更准 —— 见
     * {@link Win32ScreenCapturer} 的实测数据）。
     */
    private final boolean preSizeToApiTarget;

    /** 生产构造器（真起进程 · 预缩放 = true）。 */
    public MacOsScreenCapturer() {
        this(ComputerUseProcessRunner.REAL, new MacOsDisplays(), new MacOsTccChecker(), true);
    }

    /**
     * 生产构造器（真起进程 · 可指定预缩放开关）。
     *
     * @param preSizeToApiTarget 是否预缩放（true = 缩到 token 预算内的最大解；false = 原图）
     */
    public MacOsScreenCapturer(boolean preSizeToApiTarget) {
        this(ComputerUseProcessRunner.REAL, new MacOsDisplays(), new MacOsTccChecker(), preSizeToApiTarget);
    }

    /**
     * @param runner   外部命令执行器
     * @param displays 显示器几何来源（像素 ↔ 点换算）
     * @param tcc      TCC 授权检查（屏幕录制）
     */
    public MacOsScreenCapturer(ComputerUseProcessRunner runner, MacOsDisplays displays, MacOsTccChecker tcc) {
        this(runner, displays, tcc, true);
    }

    /**
     * @param runner             外部命令执行器
     * @param displays           显示器几何来源（像素 ↔ 点换算）
     * @param tcc                TCC 授权检查（屏幕录制）
     * @param preSizeToApiTarget 是否预缩放（true = 缩到 token 预算内的最大解；false = 原图）
     */
    public MacOsScreenCapturer(ComputerUseProcessRunner runner, MacOsDisplays displays, MacOsTccChecker tcc,
                               boolean preSizeToApiTarget) {
        if (runner == null || displays == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsScreenCapturer 依赖不能为空");
        }
        this.runner = runner;
        this.displays = displays;
        this.tcc = tcc;
        this.preSizeToApiTarget = preSizeToApiTarget;
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
     * <br>⭐ 与 Windows 侧<b>共用同一实现</b>（{@link ComputerUseApiResize#resolveOutputSize}）
     * ⇒ 两平台的尺寸语义天然一致。
     *
     * @param physW             捕获区物理宽
     * @param physH             捕获区物理高
     * @param preSizeToApiTarget 是否预缩放
     * @return {@code int[]{输出宽, 输出高}}
     */
    static int[] resolveOutputSize(int physW, int physH, boolean preSizeToApiTarget) {
        return ComputerUseApiResize.resolveOutputSize(physW, physH, preSizeToApiTarget);
    }

    @Override
    public ComputerUseCaptureResult capture() throws Exception {
        tcc.requireScreenRecording();
        byte[] png = runScreencapture(List.of("-x"));
        BufferedImage img = ComputerUseImageCodec.decode(png);
        int physW = img.getWidth();
        int physH = img.getHeight();
        double scaleFactor = displays.pixelsPerPoint();
        int[] target = resolveOutputSize(physW, physH, preSizeToApiTarget);
        BufferedImage out = (target[0] == physW && target[1] == physH)
                ? img
                : ComputerUseImageCodec.scaleTo(img, target[0], target[1]);
        byte[] jpeg = ComputerUseImageCodec.encodeJpeg(out);
        int logicalW = Math.max(1, (int) Math.round(physW / scaleFactor));
        int logicalH = Math.max(1, (int) Math.round(physH / scaleFactor));
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 整屏捕获完成: 捕获区 {}x{} · 图像 {}x{} · scaleFactor={} · "
                            + "JPEG {} 字节 · mediaType={} · 预缩放={} · 图像token≈{}",
                    physW, physH, target[0], target[1], scaleFactor, jpeg.length, MEDIA_TYPE,
                    preSizeToApiTarget,
                    ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN));
        }
        if (!preSizeToApiTarget && log.isWarnEnabled()) {
            log.warn("[computer-use] macOS 整屏捕获: 预缩放已关闭 ⇒ 发原图 {}x{}（图像token≈{}）"
                            + "· ⛔ token 成本显著上升，且依赖上游【不】做服务端二次缩放",
                    target[0], target[1],
                    ComputerUseApiResize.nTokensForImg(target[0], target[1], ComputerUseApiResize.PX_PER_TOKEN));
        }
        return new ComputerUseCaptureResult(jpeg, MEDIA_TYPE, target[0], target[1],
                physW, physH, 0, 0, scaleFactor, logicalW, logicalH);
    }

    @Override
    public ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) throws Exception {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "[computer-use] captureRegion 尺寸非法: " + width + "x" + height);
        }
        tcc.requireScreenRecording();
        // 像素 → 点（screencapture -R 的矩形在 Quartz 全局空间）
        int qx = displays.toQuartzX(x);
        int qy = displays.toQuartzY(y);
        double ppt = displays.pixelsPerPoint();
        int qw = Math.max(1, (int) Math.round(width / ppt));
        int qh = Math.max(1, (int) Math.round(height / ppt));
        byte[] png = runScreencapture(List.of("-x", "-R", qx + "," + qy + "," + qw + "," + qh));
        BufferedImage img = ComputerUseImageCodec.decode(png);
        byte[] jpeg = ComputerUseImageCodec.encodeJpeg(img);
        int physW = img.getWidth();
        int physH = img.getHeight();
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 区域截图完成: ({},{}) {}x{} → Quartz ({},{}) {}x{} · "
                            + "图像 {}x{} · JPEG {} 字节",
                    x, y, width, height, qx, qy, qw, qh, physW, physH, jpeg.length);
        }
        // 区域截图：原分辨率（不缩到 1568），显示尺寸 = 实际抓到的像素尺寸
        return new ComputerUseCaptureResult(jpeg, MEDIA_TYPE, physW, physH, physW, physH,
                x, y, ppt, Math.max(1, (int) Math.round(physW / ppt)),
                Math.max(1, (int) Math.round(physH / ppt)));
    }

    @Override
    public ComputerUseDisplayGeometry currentDisplayGeometry() {
        MacOsDisplays.Info info = displays.primary();
        // 与 Windows 侧同约定：width/height = 逻辑宽高（点），scaleFactor = 像素/点
        return new ComputerUseDisplayGeometry(info.displayId(), info.pointWidth(), info.pointHeight(),
                info.pixelsPerPoint(), info.originX(), info.originY());
    }

    /**
     * 跑 {@code screencapture} 并读回 PNG 字节。
     *
     * @param extraArgs 额外参数（如 {@code -x} / {@code -x -R x,y,w,h}）
     * @return PNG 字节
     * @throws Exception 命令失败 / 读文件失败
     */
    private byte[] runScreencapture(List<String> extraArgs) throws Exception {
        Path tmp = Files.createTempFile("cu-screenshot-", ".png");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("screencapture");
            cmd.addAll(extraArgs);
            cmd.add(tmp.toString());
            ComputerUseProcessRunner.Result result = runner.run(cmd);
            if (!result.ok()) {
                throw new IllegalStateException(
                        "[computer-use] screencapture 失败（退出码 " + result.exitCode() + "）: "
                                + result.stderr());
            }
            if (!Files.exists(tmp) || Files.size(tmp) == 0) {
                throw new IllegalStateException("[computer-use] screencapture 未产出图像文件");
            }
            return Files.readAllBytes(tmp);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("[computer-use] macOS 临时截图文件删除失败: {}", e.getMessage());
            }
        }
    }
}
