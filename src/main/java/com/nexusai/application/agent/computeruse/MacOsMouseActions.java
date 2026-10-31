package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS 鼠标动作实现（JXA + CoreGraphics）· 点击变体 / 滚动共用。
 *
 * <h2>CCB 对照（{@code packages/@ant/computer-use-input/src/backends/darwin.ts}）</h2>
 * <ul>
 *   <li><b>点击</b>：{@code :138-168} {@code mouseButton(button, action, count)}
 *       —— 键序号 {@code left=0 / right=1 / other(middle)=2}；
 *       down/up 事件类型 {@code kCGEventLeftMouseDown/Up}、
 *       {@code kCGEventRightMouseDown/Up}、{@code kCGEventOtherMouseDown/Up}；
 *       click 时逐次带 {@code CGEventSetIntegerValueField(e, $.kCGMouseEventClickState, i+1)}。</li>
 *   <li><b>移动</b>：{@code :90-92} {@code kCGEventMouseMoved}。</li>
 *   <li><b>滚动</b>：{@code :170-179} {@code mouseScroll}
 *       —— 垂直 {@code CGEventCreateScrollWheelEvent(null, 0, 1, amount)}、
 *       水平 {@code CGEventCreateScrollWheelEvent(null, 0, 2, 0, amount)}。</li>
 * </ul>
 *
 * <h2>⚠️ 次数语义</h2>
 * <p>CCB 的跨平台执行器（{@code src/utils/computerUse/executorCrossPlatform.ts:384-386}）
 * 对 {@code count > 1} 是<b>循环 count 次「down + up」</b>
 * ⇒ 双击 = 两组完整的按下/抬起（本仓 Windows 侧同款）。
 */
public final class MacOsMouseActions implements ComputerUseMouseActions {

    private static final Logger log = LoggerFactory.getLogger(MacOsMouseActions.class);

    private final ComputerUseProcessRunner runner;
    private final MacOsDisplays displays;
    private final MacOsTccChecker tcc;

    /** 生产构造器（真起进程）。 */
    public MacOsMouseActions() {
        this(ComputerUseProcessRunner.REAL, new MacOsDisplays(), new MacOsTccChecker());
    }

    /**
     * @param runner   外部命令执行器
     * @param displays 显示器几何来源（像素 ↔ 点换算）
     * @param tcc      TCC 授权检查
     */
    public MacOsMouseActions(ComputerUseProcessRunner runner, MacOsDisplays displays, MacOsTccChecker tcc) {
        if (runner == null || displays == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsMouseActions 依赖不能为空");
        }
        this.runner = runner;
        this.displays = displays;
        this.tcc = tcc;
    }

    @Override
    public void click(int x, int y, ComputerUseMouseButton button, int count) throws Exception {
        if (count < 1) {
            throw new IllegalArgumentException("点击次数必须 ≥ 1，实际=" + count);
        }
        tcc.requireAccessibility();
        // CCB platforms/darwin.ts:33-37 —— 先定位
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent(
                "kCGEventMouseMoved", displays.toQuartzX(x), displays.toQuartzY(y), 0, null));
        int qx = displays.toQuartzX(x);
        int qy = displays.toQuartzY(y);
        int btn = buttonIndex(button);
        String downType = downEventType(button);
        String upType = upEventType(button);
        // CCB executorCrossPlatform.ts:384-386 —— count 组完整的 down+up
        for (int i = 0; i < count; i++) {
            MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent(downType, qx, qy, btn, i + 1));
            MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent(upType, qx, qy, btn, i + 1));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 点击完成: ({},{}) → Quartz ({},{}) · 按键={} · 次数={}",
                    x, y, qx, qy, button.wire(), count);
        }
    }

    @Override
    public void moveTo(int x, int y) throws Exception {
        tcc.requireAccessibility();
        MacOsExec.jxaVoid(runner, MacOsJxa.mouseEvent(
                "kCGEventMouseMoved", displays.toQuartzX(x), displays.toQuartzY(y), 0, null));
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 光标移动到 ({},{})", x, y);
        }
    }

    @Override
    public void scroll(int dx, int dy) throws Exception {
        tcc.requireAccessibility();
        // CCB executorCrossPlatform.ts:507-508 —— 垂直先、水平后
        if (dy != 0) {
            MacOsExec.jxaVoid(runner, MacOsJxa.verticalScrollWheel(dy));
        }
        if (dx != 0) {
            MacOsExec.jxaVoid(runner, MacOsJxa.horizontalScrollWheel(dx));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS 滚动完成: dx={} dy={}", dx, dy);
        }
    }

    /** 键序号 · CCB {@code input/backends/darwin.ts:144}（left=0 / right=1 / other=2）。 */
    private static int buttonIndex(ComputerUseMouseButton button) {
        return switch (button) {
            case LEFT -> 0;
            case RIGHT -> 1;
            case MIDDLE -> 2;
        };
    }

    /** down 事件类型 · CCB {@code input/backends/darwin.ts:145-152}。 */
    private static String downEventType(ComputerUseMouseButton button) {
        return switch (button) {
            case LEFT -> "kCGEventLeftMouseDown";
            case RIGHT -> "kCGEventRightMouseDown";
            case MIDDLE -> "kCGEventOtherMouseDown";
        };
    }

    /** up 事件类型 · CCB {@code input/backends/darwin.ts:145-152}。 */
    private static String upEventType(ComputerUseMouseButton button) {
        return switch (button) {
            case LEFT -> "kCGEventLeftMouseUp";
            case RIGHT -> "kCGEventRightMouseUp";
            case MIDDLE -> "kCGEventOtherMouseUp";
        };
    }
}
