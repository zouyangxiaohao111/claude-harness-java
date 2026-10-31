package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS 鼠标实现（JXA + CoreGraphics）· computer-use 的点击底座。
 *
 * <h2>CCB 对照（{@code packages/@ant/computer-use-input/src/backends/darwin.ts}）</h2>
 * <ul>
 *   <li><b>移动</b>：{@code :90-92} {@code moveMouse} ⇒
 *       {@code jxa(buildMouseJxa('kCGEventMouseMoved', x, y, 0))}；</li>
 *   <li><b>点击</b>：{@code :138-168} {@code mouseButton(button,'click',count)}
 *       ⇒ 逐次 {@code CGEventCreateMouseEvent(…, $.kCGEventLeftMouseDown/Up, …)}
 *       + {@code CGEventSetIntegerValueField(e, $.kCGMouseEventClickState, i+1)}
 *       + {@code CGEventPost($.kCGHIDEventTap, e)}；</li>
 *   <li><b>脚本形状</b>：{@code :75-88} {@code buildMouseJxa}（本仓逐字搬运到
 *       {@link MacOsJxa#mouseEvent(String, int, int, int, Integer)}）。</li>
 * </ul>
 *
 * <h2>⚠️ 坐标单位</h2>
 * <p>CoreGraphics 的全局事件坐标是<b>点</b>（{@code CGDisplayBounds} 同单位），
 * 而本仓工具体系用<b>屏幕像素</b>（与 Windows 侧同约定）
 * ⇒ 本实现在<b>派发前</b>经 {@link MacOsDisplays#toQuartzX(int)} 换算。
 *
 * <h2>settle 等待</h2>
 * <p>CCB 的 50ms {@code MOVE_SETTLE_MS}（{@code src/utils/computerUse/executor.ts:168-177}）
 * 是 macOS 路径的语义 ⇒ 本实现照做（移动后等 50ms 再点）。
 */
public final class MacOsMouse implements ComputerUseMouse {

    private static final Logger log = LoggerFactory.getLogger(MacOsMouse.class);

    /** CCB {@code src/utils/computerUse/executor.ts:168} {@code MOVE_SETTLE_MS = 50}。 */
    public static final long MOVE_SETTLE_MS = 50L;

    private final ComputerUseProcessRunner runner;
    private final MacOsDisplays displays;
    private final MacOsTccChecker tcc;

    /** 生产构造器（真起进程）。 */
    public MacOsMouse() {
        this(ComputerUseProcessRunner.REAL, new MacOsDisplays(), new MacOsTccChecker());
    }

    /**
     * @param runner   外部命令执行器
     * @param displays 显示器几何来源（像素 ↔ 点换算）
     * @param tcc      TCC 授权检查
     */
    public MacOsMouse(ComputerUseProcessRunner runner, MacOsDisplays displays, MacOsTccChecker tcc) {
        if (runner == null || displays == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsMouse 依赖不能为空");
        }
        this.runner = runner;
        this.displays = displays;
        this.tcc = tcc;
    }

    @Override
    public void moveAndSettle(int x, int y) throws Exception {
        tcc.requireAccessibility();
        move(x, y);
        // settle：等「移动 → 系统 → 应用」的往返完成（CCB MOVE_SETTLE_MS 语义）
        Thread.sleep(MOVE_SETTLE_MS);
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 光标已移动到 ({},{}) 并等待 {}ms settle", x, y, MOVE_SETTLE_MS);
        }
    }

    @Override
    public void leftClick(int x, int y) throws Exception {
        tcc.requireAccessibility();
        // CCB platforms/darwin.ts:33-37 —— 先定位再点
        move(x, y);
        int qx = displays.toQuartzX(x);
        int qy = displays.toQuartzY(y);
        // CCB input/backends/darwin.ts:158-162 —— clickState = 1（首次点击）
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventLeftMouseDown", qx, qy, 0, 1));
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventLeftMouseUp", qx, qy, 0, 1));
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 左键点击完成: ({},{}) → Quartz ({},{})", x, y, qx, qy);
        }
    }

    /** 派发一次「移动」事件（{@code kCGEventMouseMoved}）。 */
    void move(int x, int y) throws Exception {
        int qx = displays.toQuartzX(x);
        int qy = displays.toQuartzY(y);
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent("kCGEventMouseMoved", qx, qy, 0, null));
    }
}
