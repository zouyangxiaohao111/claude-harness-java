package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * Win32 窗口操作原语 · computer-use Phase 2b 的<b>窗口定向输入</b>底座。
 *
 * <p><b>WHY 抽接口</b>：11 个 Windows 专属工具（{@code bind_window} / {@code window_management} /
 * {@code virtual_mouse} / {@code virtual_keyboard} / {@code mouse_wheel} / …）都打在这层原语上，
 * 而纯单测不能碰真实桌面 ⇒ 能力必须可注入（与 {@link ComputerUseMouse} /
 * {@link ComputerUseKeyboard} / {@link ComputerUseClipboard} 同款约定）。
 *
 * <h2>CCB 对照（本仓逐条对齐 CCB 的 Win32 调用语义）</h2>
 * <table border="1">
 *   <caption>CCB Windows 执行端 → 本仓接口方法</caption>
 *   <tr><th>CCB</th><th>行号</th><th>本仓</th></tr>
 *   <tr><td>{@code EnumWindows} + 可见 + 有标题</td><td>{@code win32/windowEnum.ts:34-49}</td>
 *       <td>{@link #listVisibleWindows()}</td></tr>
 *   <tr><td>{@code GetWindowRect}</td><td>{@code win32/windowMessage.ts:151-158}</td>
 *       <td>{@link #getWindowRect(long)}</td></tr>
 *   <tr><td>{@code ClientToScreen(0,0) - GetWindowRect.left}（non-client 偏移）</td>
 *       <td>{@code executorCrossPlatform.ts:69-119}</td><td>{@link #getClientOffset(long)}</td></tr>
 *   <tr><td>{@code ShowWindow}</td><td>{@code platforms/win32.ts:858-873}</td>
 *       <td>{@link #showWindow(long, int)}</td></tr>
 *   <tr><td>{@code SetWindowPos}</td><td>{@code platforms/win32.ts:897-933}</td>
 *       <td>{@link #moveResize(long, int, int, Integer, Integer)} / {@link #moveOffscreen(long)}</td></tr>
 *   <tr><td>{@code SendMessage(WM_CLOSE)}</td><td>{@code platforms/win32.ts:876-883}</td>
 *       <td>{@link #closeWindow(long)}</td></tr>
 *   <tr><td>{@code SetForegroundWindow + BringWindowToTop}</td>
 *       <td>{@code platforms/win32.ts:885-895}</td><td>{@link #focusWindow(long)}</td></tr>
 *   <tr><td>{@code SendMessage(WM_LBUTTONDOWN/UP, client 坐标)}</td>
 *       <td>{@code win32/windowMessage.ts:542-559}</td><td>{@link #sendClick(long, int, int, boolean)}</td></tr>
 *   <tr><td>{@code SendMessage(WM_CHAR, 逐码点)}</td>
 *       <td>{@code win32/windowMessage.ts:252-288}</td><td>{@link #sendChar(long, int)} / {@link #sendText(long, String)}</td></tr>
 *   <tr><td>{@code PostMessage(WM_KEYDOWN/UP, 带扫描码 lParam)}</td>
 *       <td>{@code win32/windowMessage.ts:296-308}</td><td>{@link #sendKeyDown(long, int)} / {@link #sendKeyUp(long, int)}</td></tr>
 *   <tr><td>{@code SendMessage(WM_MOUSEWHEEL, 屏幕坐标 lParam)}</td>
 *       <td>{@code win32/windowMessage.ts:613-653}</td><td>{@link #sendMouseWheel(long, int, int, int, boolean)}</td></tr>
 *   <tr><td>{@code findEditChild}（9 类编辑控件优先级）</td>
 *       <td>{@code win32/windowMessage.ts:103-216}</td><td>{@link #findEditChild(long)}</td></tr>
 * </table>
 *
 * <p><b>⚠️ 传输层差异（如实标注）</b>：CCB 的 Windows 侧几乎全部走 <b>PowerShell 子进程</b>
 * （{@code ps(script)}），本仓改用 <b>JNA 直调</b> —— <b>调用的 Win32 API、参数、消息号逐字相同</b>，
 * 只是不起子进程（无 PowerShell 转义陷阱、无 200–500ms 启动开销）。
 * 本仓既有 {@link Win32Clipboard} / {@link Win32ForegroundAppResolver} 已是同一处置方式。
 */
public interface ComputerUseWindowOps {

    /** {@code ShowWindow} 的 {@code SW_MINIMIZE}。 */
    int SW_MINIMIZE = 6;

    /** {@code ShowWindow} 的 {@code SW_MAXIMIZE}。 */
    int SW_MAXIMIZE = 3;

    /** {@code ShowWindow} 的 {@code SW_RESTORE}。 */
    int SW_RESTORE = 9;

    /**
     * {@code ShowWindow} 的 {@code SW_SHOWMINNOACTIVE}（最小化但不激活）。
     *
     * <p>⚠️ CCB 的 {@code window_management.minimize} <b>实际</b>用的是这个常量
     * （{@code src/utils/computerUse/platforms/win32.ts:858-862}），
     * 尽管它的工具描述写的是 {@code SW_MINIMIZE} —— 本仓<b>照 CCB 的实际行为</b>实现。
     */
    int SW_SHOWMINNOACTIVE = 7;

    /**
     * 枚举所有<b>可见且标题非空</b>的顶层窗口。
     *
     * @return 窗口列表（按 {@code EnumWindows} 的原始顺序）
     */
    List<ComputerUseWindowInfo> listVisibleWindows();

    /**
     * 取窗口的屏幕矩形。
     *
     * @param hwnd 窗口句柄
     * @return {@code {x, y, width, height}}；窗口无效 ⇒ null
     */
    int[] getWindowRect(long hwnd);

    /**
     * 取窗口的 <b>non-client 偏移</b>（客户区原点相对窗口矩形原点的偏移）。
     *
     * <p>逐字对齐 CCB {@code src/utils/computerUse/executorCrossPlatform.ts:69-119} 的
     * {@code getNonClientOffset()}：{@code dx = ClientToScreen(0,0).x - GetWindowRect().left}。
     *
     * @param hwnd 窗口句柄
     * @return {@code {dx, dy}}；窗口无效 ⇒ null
     */
    int[] getClientOffset(long hwnd);

    /**
     * 客户区坐标 → 屏幕坐标。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X
     * @param y    客户区 Y
     * @return {@code {screenX, screenY}}
     */
    int[] clientToScreen(long hwnd, int x, int y);

    /**
     * 屏幕坐标 → 客户区坐标（窗口定向点击的入参换算）。
     *
     * <p><b>WHY 需要它</b>：本仓的截图工具抓的是<b>整屏</b>
     * （{@link ComputerUseScreenCapturer#capture()}），模型给的坐标换算后是<b>屏幕</b>坐标；
     * 而 {@code SendMessage(WM_LBUTTONDOWN)} 要的是<b>客户区</b>坐标 ⇒ 必须换算。
     *
     * <p><b>与 CCB 的坐标模型差异（如实标注）</b>：CCB 绑定窗口后截图走 {@code PrintWindow}
     * （{@code win32/windowCapture.ts}）⇒ 图像像素就是<b>窗口内</b>坐标，
     * 再减去 non-client 偏移（{@code executorCrossPlatform.ts:69-119}）得到客户区坐标。
     * 本仓截图仍是整屏 ⇒ 等价换算是「屏幕坐标 − 客户区原点的屏幕坐标」。
     * 两者<b>目标坐标相同</b>（都是客户区坐标），只是<b>起点不同</b>。
     *
     * @param hwnd 窗口句柄
     * @param x    屏幕 X
     * @param y    屏幕 Y
     * @return {@code {clientX, clientY}}
     */
    int[] screenToClient(long hwnd, int x, int y);

    /**
     * {@code ShowWindow}（{@code SW_MINIMIZE} / {@code SW_MAXIMIZE} / {@code SW_RESTORE} /
     * {@code SW_SHOWMINNOACTIVE}）。
     *
     * @param hwnd 窗口句柄
     * @param cmd  {@code ShowWindow} 命令
     */
    void showWindow(long hwnd, int cmd);

    /**
     * {@code SetWindowPos} 移动 / 调整大小。
     *
     * @param hwnd   窗口句柄
     * @param x      目标 X（屏幕坐标）
     * @param y      目标 Y（屏幕坐标）
     * @param width  目标宽（null / ≤0 ⇒ 不改宽度）
     * @param height 目标高（null / ≤0 ⇒ 不改高度）
     */
    void moveResize(long hwnd, int x, int y, Integer width, Integer height);

    /**
     * 把窗口挪到屏幕外（{@code (-32000,-32000)}）—— CCB 的 {@code move_offscreen}。
     *
     * @param hwnd 窗口句柄
     */
    void moveOffscreen(long hwnd);

    /**
     * {@code SendMessage(WM_CLOSE)} —— 优雅关闭（CCB 的 {@code close}）。
     *
     * @param hwnd 窗口句柄
     */
    void closeWindow(long hwnd);

    /**
     * 置前窗口：最小化时先 {@code SW_RESTORE}，再 {@code SetForegroundWindow} +
     * {@code BringWindowToTop}（CCB 的 {@code focus}）。
     *
     * @param hwnd 窗口句柄
     */
    void focusWindow(long hwnd);

    /**
     * 窗口定向点击：{@code SendMessage(WM_LBUTTONDOWN/UP)}，坐标是<b>客户区</b>坐标。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X
     * @param y    客户区 Y
     * @param right true = 右键，false = 左键
     */
    void sendClick(long hwnd, int x, int y, boolean right);

    /**
     * 窗口定向左键按下：{@code SendMessage(WM_LBUTTONDOWN)}。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X
     * @param y    客户区 Y
     */
    void sendMouseDown(long hwnd, int x, int y);

    /**
     * 窗口定向左键抬起：{@code SendMessage(WM_LBUTTONUP)}。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X
     * @param y    客户区 Y
     */
    void sendMouseUp(long hwnd, int x, int y);

    /**
     * 窗口定向鼠标移动：{@code SendMessage(WM_MOUSEMOVE)}。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X
     * @param y    客户区 Y
     */
    void sendMouseMove(long hwnd, int x, int y);

    /**
     * 窗口定向输入一个 Unicode 码点：{@code SendMessage(WM_CHAR)}。
     *
     * @param hwnd      窗口句柄
     * @param codePoint Unicode 码点（非 BMP 由调用方拆成代理对逐个传入）
     */
    void sendChar(long hwnd, int codePoint);

    /**
     * 窗口定向输入整段文本（逐码点 {@code WM_CHAR}）。
     *
     * @param hwnd 窗口句柄
     * @param text 文本
     */
    void sendText(long hwnd, String text);

    /**
     * 窗口定向按键按下：{@code PostMessage(WM_KEYDOWN)}，lParam 带扫描码。
     *
     * @param hwnd 窗口句柄
     * @param vk   虚拟键码
     */
    void sendKeyDown(long hwnd, int vk);

    /**
     * 窗口定向按键抬起：{@code PostMessage(WM_KEYUP)}，lParam 带扫描码。
     *
     * @param hwnd 窗口句柄
     * @param vk   虚拟键码
     */
    void sendKeyUp(long hwnd, int vk);

    /**
     * 窗口定向滚轮：{@code SendMessage(WM_MOUSEWHEEL / WM_MOUSEHWHEEL)}。
     *
     * <p>⚠️ {@code WM_MOUSEWHEEL} 的 lParam 必须是<b>屏幕</b>坐标（CCB
     * {@code win32/windowMessage.ts:626-646} 内部做 {@code ClientToScreen} 换算）。
     *
     * @param hwnd       窗口句柄
     * @param x          客户区 X（滚轮落点）
     * @param y          客户区 Y（滚轮落点）
     * @param delta      格数（正 = 上 / 右，负 = 下 / 左）
     * @param horizontal true = 水平滚轮（{@code WM_MOUSEHWHEEL}）
     */
    void sendMouseWheel(long hwnd, int x, int y, int delta, boolean horizontal);

    /**
     * 找出窗口里「可接收文本的子控件」（输入解析目标）。
     *
     * <p>逐字对齐 CCB {@code src/utils/computerUse/win32/windowMessage.ts:103-216} 的
     * {@code findEditChild()}：按 9 类编辑控件类名的优先级在 {@code EnumChildWindows} 结果里找。
     *
     * @param hwnd 窗口句柄
     * @return 子窗口句柄；找不到 ⇒ 0（调用方回落为父窗口）
     */
    long findEditChild(long hwnd);
}
