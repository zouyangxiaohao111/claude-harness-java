package com.nexusai.application.agent.computeruse;

/**
 * 光标原语 · <b>鼠标缓动路径</b>（{@code mouseAnimation} 子开关）需要的最小鼠标能力集。
 *
 * <p><b>WHY 单独一个接口（而不是往 {@link ComputerUseMouse} 上加）</b>：
 * {@link ComputerUseMouse} 是 Phase 0 的<b>点击</b>抽象（{@code moveAndSettle} + {@code leftClick}），
 * 只表达"瞬移 + 等 50ms + 点一下"；而缓动路径需要<b>不 settle 的连续移动</b>、
 * <b>按下 / 抬起分开</b>、<b>读当前光标位置</b>。
 * <br>⇒ 两者语义不同、生命周期不同 ⇒ 拆开，避免把 Phase 0 的点击抽象撑成什么都干的大接口
 * （也避免逼所有既有实现/假实现补方法）。
 *
 * <p>生产实现：{@link Win32Cursor}（JNA 直调 user32）。
 */
public interface ComputerUseCursor {

    /**
     * 瞬移光标（<b>不 settle</b>）。
     *
     * <p>CCB {@code src/utils/computerUse/executor.ts:300} 的
     * {@code input.moveMouse(x, y, false)} —— 第三个参数 {@code animated=false} 表示
     * "这一步不是动画的一帧，直接定位"。缓动的每一帧都走这里。
     *
     * @param x 屏幕 X
     * @param y 屏幕 Y
     * @throws Exception 移动失败
     */
    void moveTo(int x, int y) throws Exception;

    /**
     * 读当前光标位置（<b>不移动</b>）。
     *
     * <p>CCB {@code executor.ts:283} 的 {@code input.mouseLocation()} —— 缓动路径的起点。
     *
     * @return {@code int[]{x, y}}（屏幕坐标）
     * @throws Exception 读取失败
     */
    int[] location() throws Exception;

    /**
     * 左键<b>按下</b>（在当前光标位置，不重新定位）。
     *
     * <p>CCB {@code executor.ts:648} 的 {@code input.mouseButton('left', 'press')}。
     *
     * @throws Exception 派发失败
     */
    void leftButtonDown() throws Exception;

    /**
     * 左键<b>抬起</b>（在当前光标位置，不重新定位）。
     *
     * <p>CCB {@code executor.ts:657} 的 {@code input.mouseButton('left', 'release')}。
     *
     * @throws Exception 派发失败
     */
    void leftButtonUp() throws Exception;
}
