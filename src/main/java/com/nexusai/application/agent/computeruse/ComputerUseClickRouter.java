package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * 点击路由 · 决定「这次点击走<b>窗口定向</b>还是<b>全局注入</b>」，并负责修饰键的按下 / 释放。
 *
 * <h2>路由规则（CCB {@code platforms/win32.ts:241-245}）</h2>
 * <pre>
 * // Input — When HWND is bound, ALL operations go through SendMessage.
 * // NO global API (SetCursorPos/SendInput/keybd_event/SendKeys) is used.
 * </pre>
 * ⇒ <b>有绑定窗口 ⇒ 窗口定向</b>（{@code SendMessage} 到该 HWND，<b>不动真实鼠标、不抢焦点</b>）；
 * <b>无绑定窗口 ⇒ 全局注入</b>（本仓 Phase 2a 的既有路线）。
 *
 * <p><b>⚠️ 与 CCB 的两处差异（如实标注）</b>：
 * <ol>
 *   <li><b>坐标起点不同</b>：CCB 绑定后截图走 {@code PrintWindow}（只截绑定窗口）⇒
 *       模型给的坐标是<b>窗口内</b>坐标；本仓截图仍是<b>整屏</b>⇒ 模型给的坐标是
 *       <b>屏幕</b>坐标 ⇒ 换算路径是「屏幕 → 客户区」
 *       （{@link ComputerUseWindowOps#screenToClient}），
 *       而非 CCB 的「窗口内 → 客户区」（减 non-client 偏移）。
 *       <b>两者最终都得到客户区坐标</b>，只是起点不同。</li>
 *   <li><b>修饰键在窗口定向下的投递方式</b>：CCB 的 {@code virtual_mouse}
 *       （{@code executorCrossPlatform.ts:831-900}）<b>根本没有修饰键参数</b>
 *       ⇒ CCB 绑定态点击<b>不带修饰键</b>。本仓按 {@code windowMessage.ts:341-366}
 *       的「修饰键按下 → 动作 → 修饰键逆序释放」语义，用 {@code PostMessage(WM_KEYDOWN/UP)}
 *       把修饰键投给<b>绑定窗口</b>（与 CCB 的组合键投递同款）。</li>
 * </ol>
 *
 * <h2>修饰键顺序（CCB {@code windowMessage.ts:341-366}）</h2>
 * <b>修饰键按输入顺序按下 → 执行动作 → 修饰键按逆序释放</b>；
 * 且释放必须放在 {@code finally} 里 ⇒ <b>异常路径不卡键</b>。
 */
public final class ComputerUseClickRouter {

    /** 可抛异常的动作（用于把「修饰键按下 → 动作 → 修饰键释放」包成 try/finally）。 */
    @FunctionalInterface
    public interface ClickAction {
        /**
         * 执行点击动作。
         *
         * @throws Exception 动作失败
         */
        void run() throws Exception;
    }

    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseKeyboard keyboard;

    /**
     * @param bindingStore 绑定窗口存储（null ⇒ 永远走全局注入）
     * @param windowOps    窗口操作原语（null ⇒ 永远走全局注入）
     * @param keyboard     全局键盘注入（null ⇒ 全局路径不支持修饰键）
     */
    public ComputerUseClickRouter(ComputerUseWindowBindingStore bindingStore,
                                   ComputerUseWindowOps windowOps,
                                   ComputerUseKeyboard keyboard) {
        this.bindingStore = bindingStore;
        this.windowOps = windowOps;
        this.keyboard = keyboard;
    }

    /**
     * 该会话是否走窗口定向。
     *
     * @param sessionId 会话 id
     * @return true = 有绑定窗口且窗口操作可用 ⇒ 走窗口定向
     */
    public boolean isWindowTargeted(String sessionId) {
        if (bindingStore == null || windowOps == null) {
            return false;
        }
        return bindingStore.get(sessionId) != null;
    }

    /**
     * 窗口定向点击（含修饰键）。
     *
     * @param sessionId 会话 id
     * @param screenX   屏幕 X（{@link ComputerUseCoordinateScaler} 的产物）
     * @param screenY   屏幕 Y
     * @param right     true = 右键
     * @param count     点击次数（1 / 2 / 3）
     * @param modifiers 修饰键按下事件（空 ⇒ 无修饰键）
     */
    public void clickWindowDirected(String sessionId,
                                    int screenX,
                                    int screenY,
                                    boolean right,
                                    int count,
                                    List<ComputerUseKeyEvent> modifiers) {
        ComputerUseWindowBinding binding = bindingStore.get(sessionId);
        if (binding == null) {
            throw new IllegalStateException("没有绑定窗口，无法窗口定向点击");
        }
        long hwnd = binding.hwnd();
        int[] client = windowOps.screenToClient(hwnd, screenX, screenY);
        try {
            // 修饰键正序按下（CCB windowMessage.ts:341-366）
            for (ComputerUseKeyEvent mod : modifiers) {
                windowOps.sendKeyDown(hwnd, mod.virtualKey());
            }
            // 动作：count 组完整的 down+up（CCB executorCrossPlatform.ts:384-386）
            for (int i = 0; i < count; i++) {
                windowOps.sendClick(hwnd, client[0], client[1], right);
            }
        } finally {
            // 修饰键逆序释放（异常路径也不卡键）
            List<ComputerUseKeyEvent> release = ComputerUseModifierChord.releaseOrder(modifiers);
            for (ComputerUseKeyEvent mod : release) {
                windowOps.sendKeyUp(hwnd, mod.virtualKey());
            }
        }
    }

    /**
     * 全局注入点击（含修饰键）。
     *
     * @param modifiers 修饰键按下事件（空 ⇒ 无修饰键）
     * @param action    点击动作（由调用方提供，保持各自既有的全局路线）
     * @throws Exception 动作失败
     */
    public void clickGlobal(List<ComputerUseKeyEvent> modifiers, ClickAction action) throws Exception {
        if (modifiers.isEmpty()) {
            action.run();
            return;
        }
        if (keyboard == null) {
            throw new IllegalStateException("修饰键点击需要键盘注入能力（未接线）");
        }
        List<ComputerUseKeyEvent> pressed = new java.util.ArrayList<>();
        try {
            // 修饰键正序按下（CCB windowMessage.ts:341-366）
            for (ComputerUseKeyEvent mod : modifiers) {
                keyboard.sendKeyEvent(mod);
                pressed.add(mod);
            }
            action.run();
        } finally {
            // 修饰键逆序释放（异常路径也不卡键）
            for (ComputerUseKeyEvent mod : ComputerUseModifierChord.releaseOrder(pressed)) {
                try {
                    keyboard.sendKeyEvent(mod);
                } catch (Exception e) {
                    // 释放失败不能掩盖原始异常
                }
            }
        }
    }
}
