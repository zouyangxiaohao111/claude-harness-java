package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS 光标实现（JXA + CoreGraphics）· 鼠标缓动路径（{@code mouseAnimation}）的底座。
 *
 * <h2>CCB 对照（{@code packages/@ant/computer-use-input/src/backends/darwin.ts}）</h2>
 * <ul>
 *   <li><b>移动（不 settle）</b>：{@code :90-92} {@code moveMouse(x, y, false)}
 *       ⇒ {@code kCGEventMouseMoved}；</li>
 *   <li><b>读位置</b>：{@code :130-136} {@code mouseLocation()}
 *       ⇒ {@code CGEventCreate(null)} + {@code CGEventGetLocation(e)}（输出 {@code "x,y"}）；</li>
 *   <li><b>按键</b>：{@code :138-168} {@code mouseButton('left','press'|'release')}
 *       ⇒ {@code kCGEventLeftMouseDown} / {@code kCGEventLeftMouseUp}。</li>
 * </ul>
 *
 * <h2>⚠️ 单位换算</h2>
 * <p>{@code CGEventGetLocation} 返回的是<b>点</b>；本仓工具体系用<b>屏幕像素</b>
 * ⇒ {@link #location()} 把点换算回像素（×pixelsPerPoint），
 * 保证它与 {@link ComputerUseCaptureResult#displayWidth()} 同一空间。
 */
public final class MacOsCursor implements ComputerUseCursor {

    private static final Logger log = LoggerFactory.getLogger(MacOsCursor.class);

    private final ComputerUseProcessRunner runner;
    private final MacOsDisplays displays;
    private final MacOsTccChecker tcc;

    /** 生产构造器（真起进程）。 */
    public MacOsCursor() {
        this(ComputerUseProcessRunner.REAL, new MacOsDisplays(), new MacOsTccChecker());
    }

    /**
     * @param runner   外部命令执行器
     * @param displays 显示器几何来源（像素 ↔ 点换算）
     * @param tcc      TCC 授权检查
     */
    public MacOsCursor(ComputerUseProcessRunner runner, MacOsDisplays displays, MacOsTccChecker tcc) {
        if (runner == null || displays == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsCursor 依赖不能为空");
        }
        this.runner = runner;
        this.displays = displays;
        this.tcc = tcc;
    }

    @Override
    public void moveTo(int x, int y) throws Exception {
        tcc.requireAccessibility();
        int qx = displays.toQuartzX(x);
        int qy = displays.toQuartzY(y);
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventMouseMoved", qx, qy, 0, null));
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 光标瞬移到 ({},{}) → Quartz ({},{})", x, y, qx, qy);
        }
    }

    @Override
    public int[] location() throws Exception {
        String raw = MacOsExec.jxa(runner, MacOsJxa.mouseLocation());
        String[] parts = raw.split(",");
        if (parts.length < 2) {
            throw new IllegalStateException(
                    "[computer-use] macOS 光标位置解析失败（期望 \"x,y\"）: " + raw);
        }
        double pointX = parseDouble(parts[0], raw);
        double pointY = parseDouble(parts[1], raw);
        double ppt = displays.pixelsPerPoint();
        int px = (int) Math.round(pointX * ppt);
        int py = (int) Math.round(pointY * ppt);
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 光标位置: Quartz ({},{}) → 像素 ({},{})", pointX, pointY, px, py);
        }
        return new int[]{px, py};
    }

    @Override
    public void leftButtonDown() throws Exception {
        tcc.requireAccessibility();
        int[] px = location();
        int qx = displays.toQuartzX(px[0]);
        int qy = displays.toQuartzY(px[1]);
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventLeftMouseDown", qx, qy, 0, null));
    }

    @Override
    public void leftButtonUp() throws Exception {
        tcc.requireAccessibility();
        int[] px = location();
        int qx = displays.toQuartzX(px[0]);
        int qy = displays.toQuartzY(px[1]);
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventLeftMouseUp", qx, qy, 0, null));
    }

    /** 解析 JXA 输出的浮点数（失败 ⇒ fail loud）。 */
    private static double parseDouble(String raw, String whole) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "[computer-use] macOS 光标位置解析失败: " + whole, e);
        }
    }
}
