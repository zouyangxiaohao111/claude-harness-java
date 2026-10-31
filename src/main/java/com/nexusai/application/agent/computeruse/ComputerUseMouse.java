package com.nexusai.application.agent.computeruse;

/**
 * 鼠标操作接口 · 抽象出「怎么点」以便纯单测（不碰真实桌面）。
 *
 * <p>生产实现：{@link Win32Mouse}（JNA 直调 user32）。
 */
public interface ComputerUseMouse {

    /**
     * 移动光标并等待 settle。
     *
     * <p>CCB 语义见 {@code src/utils/computerUse/executor.ts:159-177}
     * （{@code MOVE_SETTLE_MS = 50} + {@code moveAndSettle}）：瞬移后等 50ms，
     * 让「输入 → HID → 系统 → 应用」的往返完成，再读光标位置或派发点击。
     *
     * @param x 屏幕 X（虚拟屏幕坐标）
     * @param y 屏幕 Y
     * @throws Exception 移动失败
     */
    void moveAndSettle(int x, int y) throws Exception;

    /**
     * 左键点击（当前光标位置）。
     *
     * @param x 屏幕 X（虚拟屏幕坐标；实现会在派发前再次定位）
     * @param y 屏幕 Y
     * @throws Exception 点击失败
     */
    void leftClick(int x, int y) throws Exception;
}
