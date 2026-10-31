package com.nexusai.application.agent.computeruse;

/**
 * 鼠标「非左键」动作原语 · computer-use Phase 2a 的点击变体 / 滚动共用。
 *
 * <h2>WHY 与 {@link ComputerUseMouse} 分开</h2>
 * <p>{@link ComputerUseMouse} 是 Phase 0 的<b>左键点击</b>抽象（{@code moveAndSettle} +
 * {@code leftClick}），已被 {@code left_click} 工具使用；本接口承载 Phase 2a 新增的
 * <b>按键 + 次数 + 滚动</b>能力。
 * <br>分开的理由与 {@link ComputerUseCursor} 一致：<b>语义不同、生命周期不同</b> ——
 * 本仓既有约定（见 {@code ComputerUseCursor} 类 javadoc「WHY 单独一个接口」）
 * 是不把 Phase 0 的点击抽象撑成什么都干的大接口，也不逼既有实现/假实现补方法。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li><b>点击变体</b>：CCB {@code toolCalls.ts:2376-2383} 的
 *       {@code handleClickVariant(adapter, args, overrides, subGates, button, count)}，
 *       {@code button ∈ 'left'|'right'|'middle'}、{@code count ∈ 1|2|3}
 *       （{@code toolCalls.ts:4161-4170} 的 dispatch 映射）。</li>
 *   <li><b>滚动</b>：CCB {@code toolCalls.ts:2621-2685} 的 {@code handleScroll}，
 *       方向 → 位移的映射在 {@code toolCalls.ts:2647-2649}
 *       （{@code up → dy=-amount}、{@code down → dy=+amount}、{@code left → dx=-amount}、
 *       {@code right → dx=+amount}）。</li>
 * </ul>
 */
public interface ComputerUseMouseActions {

    /**
     * 在给定坐标点击（{@code count} 次）。
     *
     * <p>CCB Windows 侧（{@code src/utils/computerUse/platforms/win32.ts:259-296}）：
     * {@code SetCursorPos} → {@code SendInput}（down + up）；{@code count > 1} 时
     * CCB 的跨平台执行器（{@code executorCrossPlatform.ts:384-386}）<b>循环</b>
     * {@code count} 次「down + up」⇒ 双击 = 两组完整的按下/抬起。
     *
     * @param x      屏幕 X（虚拟屏幕坐标）
     * @param y      屏幕 Y（虚拟屏幕坐标）
     * @param button 按键（左 / 右 / 中）
     * @param count  点击次数（1 / 2 / 3）
     * @throws Exception 注入失败（fail loud，由调用方转成工具错误）
     */
    void click(int x, int y, ComputerUseMouseButton button, int count) throws Exception;

    /**
     * 移动鼠标到给定坐标（<b>不点击</b>）。
     *
     * <p>CCB 的 {@code mouse_move} 工具（{@code toolCalls.ts:389-399}）在坐标换算后调用
     * {@code executor.moveMouse(x, y)}；Windows 侧落到
     * {@code src/utils/computerUse/platforms/win32.ts:248-257} 的 {@code SetCursorPos}。
     *
     * <p>⚠️ 与 {@link ComputerUseMouse#moveAndSettle} 的差别：后者带 50ms settle
     * （CCB macOS 的 {@code moveAndSettle} 语义）；本方法<b>不带</b> settle ——
     * 与 CCB-Windows 的 {@code input.moveMouse}（纯 {@code SetCursorPos}，无 sleep）一致。
     *
     * @param x 屏幕 X（虚拟屏幕坐标）
     * @param y 屏幕 Y（虚拟屏幕坐标）
     * @throws Exception 移动失败（fail loud）
     */
    void moveTo(int x, int y) throws Exception;

    /**
     * 鼠标滚轮滚动。
     *
     * <p><b>⚠️ Windows 侧忽略坐标（CCB 逐字对齐）</b>：CCB 的跨平台执行器
     * {@code src/utils/computerUse/executorCrossPlatform.ts:501-509} 的签名是
     * {@code async scroll(_x: number, _y: number, dx: number, dy: number)} ——
     * <b>下划线前缀 = 形参未被使用</b> ⇒ Windows 的滚动事件发生在<b>当前光标</b>所在窗口，
     * <b>不会</b>先移动到请求坐标。macOS 的执行器（{@code executor.ts:664-666}）才会
     * {@code moveAndSettle(x, y)}。
     * <br>⇒ 本仓与 CCB-Windows 一致：<b>不移动光标</b>，只发滚轮事件。
     *
     * @param dx 水平位移（正 = 右，负 = 左）
     * @param dy 垂直位移（正 = 下，负 = 上 —— CCB {@code toolCalls.ts:2647-2649} 的符号约定）
     * @throws Exception 注入失败
     */
    void scroll(int dx, int dy) throws Exception;
}
