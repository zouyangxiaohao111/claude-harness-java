package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * macOS 显示器几何来源 · computer-use macOS 执行端。
 *
 * <h2>CCB 对照</h2>
 * <p>CCB {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:92-152}
 * 的 {@code display.listAll()}：走 {@code jxaSync}（= {@code osascript -l JavaScript -e …}）
 * 取 {@code CGGetActiveDisplayList} → 每块屏 {@code CGDisplayPixelsWide/High}
 * + {@code CGDisplayCopyDisplayMode} 推缩放比。
 * <br>⇒ 本仓沿用同一路线（{@link MacOsJxa#displayList()}），
 * 差异见 {@link MacOsJxa#displayList()} 的类注释（缩放比方向 + 补 origin）。
 *
 * <h2>⚠️ 坐标空间（本仓与 CCB 的关键差异）</h2>
 * <p><b>Quartz 全局事件坐标是「点」（points），不是「像素」。</b>
 * <ul>
 *   <li>{@code CGEventCreateMouseEvent} / {@code CGEventGetLocation} 用的全局显示空间
 *       与 {@code CGDisplayBounds} 同单位 ⇒ <b>点</b>；</li>
 *   <li>{@code CGDisplayPixelsWide/High} 才是<b>像素</b>（背板分辨率）。</li>
 * </ul>
 * <p>本仓的工具体系（{@link ComputerUseCaptureResult} 的 {@code displayWidth/Height}、
 * {@link ComputerUseCoordinateScaler}）统一用<b>屏幕像素</b>（与 Windows 侧
 * {@code LOGPIXELSX/96} 的约定同向）⇒ 本类提供
 * {@link #toQuartzX(int)} / {@link #toQuartzY(int)} 做<b>像素 → 点</b>换算，
 * 由鼠标/光标实现类在<b>调用 CoreGraphics 的最后一刻</b>执行。
 *
 * <p><b>WHY 不把整条链改成「点」</b>：那会让截图（{@code screencapture} 出的是像素）
 * 与点击坐标分属两套单位 ⇒ 比例失配。保留像素 + 边界换算 = 截图分辨率不打折
 * （Retina 上仍是 2× 细节），而点击仍然精确。
 */
public final class MacOsDisplays {

    private static final Logger log = LoggerFactory.getLogger(MacOsDisplays.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一块显示器的几何信息。
     *
     * @param displayId      CG 显示器 id（{@code CGDirectDisplayID}）
     * @param pixelWidth     背板像素宽（{@code CGDisplayPixelsWide}）
     * @param pixelHeight    背板像素高（{@code CGDisplayPixelsHigh}）
     * @param pointWidth     点宽（{@code CGDisplayBounds.width}）
     * @param pointHeight    点高（{@code CGDisplayBounds.height}）
     * @param originX        在 Quartz 全局空间里的原点 X（<b>点</b>；多屏时非 0）
     * @param originY        在 Quartz 全局空间里的原点 Y（<b>点</b>；多屏时非 0）
     * @param pixelsPerPoint 像素/点（Retina 上 2.0；外部显示器常为 1.0）
     * @param primary        是否主显示器（{@code CGMainDisplayID}）
     */
    public record Info(int displayId,
                       int pixelWidth,
                       int pixelHeight,
                       int pointWidth,
                       int pointHeight,
                       int originX,
                       int originY,
                       double pixelsPerPoint,
                       boolean primary) {
    }

    /** CCB 的兜底几何（{@code swift/backends/darwin.ts:149}）：{@code 1920x1080 @ 2.0}。 */
    private static final Info FALLBACK =
            new Info(1, 1920, 1080, 960, 540, 0, 0, 2.0d, true);

    private final ComputerUseProcessRunner runner;

    /** 缓存（显示器拓扑在一次会话里基本不变；{@link #refresh()} 可强制刷新）。 */
    private volatile List<Info> cache;

    /** 生产构造器（真起进程）。 */
    public MacOsDisplays() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器（单测注入假实现）
     */
    public MacOsDisplays(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsDisplays 的 runner 不能为空");
        }
        this.runner = runner;
    }

    /**
     * 显示器列表（带缓存）。
     *
     * @return 显示器列表（枚举失败 ⇒ 只含 {@link #FALLBACK}）
     */
    public List<Info> list() {
        List<Info> cached = cache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cache == null) {
                cache = query();
            }
            return cache;
        }
    }

    /**
     * 强制刷新缓存（显示器热插拔后调用）。
     *
     * @return 刷新后的显示器列表
     */
    public List<Info> refresh() {
        synchronized (this) {
            cache = query();
            return cache;
        }
    }

    /**
     * 主显示器几何。
     *
     * @return 主显示器（枚举失败 ⇒ {@link #FALLBACK}）
     */
    public Info primary() {
        for (Info info : list()) {
            if (info.primary()) {
                return info;
            }
        }
        List<Info> all = list();
        return all.isEmpty() ? FALLBACK : all.get(0);
    }

    /**
     * 主显示器的「像素/点」比例。
     *
     * @return 比例（Retina 2.0；异常时回落 2.0）
     */
    public double pixelsPerPoint() {
        double ppt = primary().pixelsPerPoint();
        return (ppt > 0) ? ppt : 2.0d;
    }

    /**
     * 屏幕像素 X → Quartz 全局坐标 X（点）。
     *
     * @param pixelX 屏幕像素 X
     * @return Quartz 坐标 X（点）
     */
    public int toQuartzX(int pixelX) {
        return (int) Math.round(pixelX / pixelsPerPoint());
    }

    /**
     * 屏幕像素 Y → Quartz 全局坐标 Y（点）。
     *
     * @param pixelY 屏幕像素 Y
     * @return Quartz 坐标 Y（点）
     */
    public int toQuartzY(int pixelY) {
        return (int) Math.round(pixelY / pixelsPerPoint());
    }

    /** 执行 JXA 查询并解析。 */
    private List<Info> query() {
        try {
            ComputerUseProcessRunner.Result result = runner.run(List.of(
                    "osascript", "-l", "JavaScript", "-e", MacOsJxa.displayList()));
            if (!result.ok()) {
                log.warn("[computer-use] macOS 显示器枚举失败（退出码 {}）: {}",
                        result.exitCode(), result.stderr());
                return List.of(FALLBACK);
            }
            List<Info> parsed = parse(result.stdout());
            if (parsed.isEmpty()) {
                log.warn("[computer-use] macOS 显示器枚举结果为空 ⇒ 回落兜底几何 1920x1080@2.0");
                return List.of(FALLBACK);
            }
            if (log.isDebugEnabled()) {
                log.debug("[computer-use] macOS 显示器枚举结果: {}", parsed);
            }
            return List.copyOf(parsed);
        } catch (Exception e) {
            log.warn("[computer-use] macOS 显示器枚举异常 ⇒ 回落兜底几何: {}", e.toString());
            return List.of(FALLBACK);
        }
    }

    /** 解析 JXA 输出的 JSON 数组。 */
    static List<Info> parse(String stdout) {
        List<Info> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(stdout.trim());
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode node : root) {
                int displayId = node.path("displayId").asInt(0);
                int pixelWidth = node.path("pixelWidth").asInt(0);
                int pixelHeight = node.path("pixelHeight").asInt(0);
                int pointWidth = node.path("pointWidth").asInt(0);
                int pointHeight = node.path("pointHeight").asInt(0);
                int originX = (int) Math.round(node.path("originX").asDouble(0));
                int originY = (int) Math.round(node.path("originY").asDouble(0));
                double ppt = node.path("pixelsPerPoint").asDouble(0);
                boolean primary = node.path("isPrimary").asBoolean(false);
                if (pixelWidth <= 0 || pixelHeight <= 0) {
                    continue;
                }
                if (ppt <= 0) {
                    ppt = (pointWidth > 0) ? (double) pixelWidth / pointWidth : 2.0d;
                }
                out.add(new Info(displayId, pixelWidth, pixelHeight, pointWidth, pointHeight,
                        originX, originY, ppt, primary));
            }
        } catch (Exception e) {
            log.warn("[computer-use] macOS 显示器 JSON 解析失败: {}", e.toString());
            return List.of();
        }
        return out;
    }

    /** 摘要（供日志）。 */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MacOsDisplays{%s, pixelsPerPoint=%.3f}",
                list(), pixelsPerPoint());
    }
}
